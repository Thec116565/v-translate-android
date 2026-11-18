package com.vtranslate.live.domain

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure Kotlin subtitle deduplicator and frame pixel-difference filter.
 * Prevents duplicate translations when Douyin/TikTok subtitles flicker or animate.
 */
class TextDeduplicator(
    private val similarityThreshold: Double = 0.80,
    private val cooldownMs: Long = 45_000L,
    private val maxHistory: Int = 20,
) {
    private data class EmittedItem(val text: String, val timestampMs: Long)

    private val history = ArrayDeque<EmittedItem>()
    private var lastCandidate: String = ""
    private var lastEmittedNorm: String = ""

    @Synchronized
    fun reset() {
        history.clear()
        lastCandidate = ""
        lastEmittedNorm = ""
    }

    /**
     * Evaluates an OCR text observation and returns non-null only if it is clean, stable, and non-duplicate.
     */
    @Synchronized
    fun filterNewSubtitle(rawText: String, nowMs: Long = System.currentTimeMillis()): String? {
        val cleaned = rawText.trim().replace(Regex("\\s+"), " ")
        val norm = normalize(cleaned)
        if (norm.isEmpty() || norm == lastEmittedNorm) return null

        // Debounce typing-effect prefixes
        if (lastCandidate.isNotEmpty() &&
            similarity(lastCandidate, cleaned) < 0.65 &&
            cleaned.startsWith(lastCandidate.take(min(3, lastCandidate.length)))
        ) {
            lastCandidate = cleaned
            return null
        }
        lastCandidate = cleaned

        if (isDuplicateRecent(cleaned, nowMs)) {
            return null
        }

        if (history.size >= maxHistory) {
            history.removeFirst()
        }
        history.addLast(EmittedItem(cleaned, nowMs))
        lastEmittedNorm = norm
        return cleaned
    }

    @Synchronized
    fun isDuplicateRecent(text: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val normNew = normalize(text)
        if (normNew.isEmpty()) return true

        for (item in history) {
            if (nowMs - item.timestampMs <= cooldownMs) {
                val sim = similarity(item.text, text)
                if (sim >= similarityThreshold) {
                    val normOld = normalize(item.text)
                    if (normNew.length > normOld.length + 4) {
                        continue
                    }
                    return true
                }
            }
        }
        return false
    }

    companion object {
        private val NON_WORD_REGEX = Regex("[\\s\\p{P}\\p{S}]+")

        fun normalize(text: String): String =
            NON_WORD_REGEX.replace(text.lowercase(), "")

        fun similarity(a: String, b: String): Double {
            val na = normalize(a)
            val nb = normalize(b)
            if (na.isEmpty() && nb.isEmpty()) return 1.0
            if (na.isEmpty() || nb.isEmpty()) return 0.0
            if (na == nb) return 1.0
            val dist = levenshteinDistance(na, nb)
            return 1.0 - (dist.toDouble() / max(na.length, nb.length).toDouble())
        }

        fun levenshteinDistance(lhs: CharSequence, rhs: CharSequence): Int {
            val lLen = lhs.length
            val rLen = rhs.length
            var cost = IntArray(lLen + 1) { it }
            var newCost = IntArray(lLen + 1)

            for (i in 1..rLen) {
                newCost[0] = i
                for (j in 1..lLen) {
                    val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                    val replace = cost[j - 1] + match
                    val insert = cost[j] + 1
                    val delete = newCost[j - 1] + 1
                    newCost[j] = min(min(insert, delete), replace)
                }
                val swap = cost
                cost = newCost
                newCost = swap
            }
            return cost[lLen]
        }

        /**
         * Fast mean absolute luma difference on subsampled ARGB pixels to skip unchanged video frames.
         */
        fun meanLumaDifference(prevLuma: ByteArray?, currLuma: ByteArray): Double {
            if (prevLuma == null || prevLuma.size != currLuma.size || currLuma.isEmpty()) {
                return 999.0
            }
            var sum = 0L
            for (i in currLuma.indices) {
                val a = prevLuma[i].toInt() and 0xFF
                val b = currLuma[i].toInt() and 0xFF
                sum += abs(a - b)
            }
            return sum.toDouble() / currLuma.size.toDouble()
        }
    }
}
