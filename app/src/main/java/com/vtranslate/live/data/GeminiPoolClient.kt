package com.vtranslate.live.data

import android.util.Base64
import com.vtranslate.live.domain.LiveConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Google AI Studio Gemini Pool REST Client for Android with:
 * - Automatic multi-key and Flash model rotation on HTTP 429 quota limits
 * - 5-line Sliding Context Window for accurate pronouns & natural Vietnamese dubbing
 * - 1-Hop Inline Audio (WAV -> Translated JSON) and Text Translation
 */
class GeminiPoolClient {

    data class AudioTranslationResult(val sourceText: String, val translatedText: String)

    private val cooldownMap = ConcurrentHashMap<String, Long>() // "key|model" -> readyAtEpochMs
    private val slidingContext = ArrayDeque<Pair<String, String>>()

    @Synchronized
    fun clearContext() {
        slidingContext.clear()
    }

    @Synchronized
    private fun appendContext(source: String, translated: String) {
        if (slidingContext.size >= 5) {
            slidingContext.removeFirst()
        }
        slidingContext.addLast(source to translated)
    }

    @Synchronized
    private fun formatContext(): String {
        if (slidingContext.isEmpty()) return "Chưa có câu trước."
        return slidingContext.joinToString("\n") { (s, t) -> "- Gốc: $s => Dịch: $t" }
    }

    private fun buildCandidatePairs(cfg: LiveConfig): List<Pair<String, String>> {
        val keys = cfg.apiKeys.map { it.trim() }.filter { it.isNotEmpty() }
        if (keys.isEmpty()) return emptyList()

        val models = mutableListOf(cfg.preferredModel)
        if (cfg.autoRotateFlashModels) {
            for (m in cfg.flashModelPool) {
                if (m !in models) models.add(m)
            }
        }

        val now = System.currentTimeMillis()
        val active = mutableListOf<Pair<String, String>>()
        val cooling = mutableListOf<Pair<String, String>>()

        for (model in models) {
            for (key in keys) {
                val readyAt = cooldownMap["$key|$model"] ?: 0L
                if (now >= readyAt) {
                    active.add(key to model)
                } else {
                    cooling.add(key to model)
                }
            }
        }
        return active + cooling
    }

    fun testConnection(apiKey: String, model: String): Result<String> {
        return runCatching {
            val body = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", "Reply with OK only.")))
                }))
            }
            val resp = executeGenerateContent(apiKey.trim(), model.trim(), body)
            "Kết nối thành công ($model): ${resp.take(40)}"
        }
    }

    fun translateSubtitleText(sourceText: String, cfg: LiveConfig): Result<String> {
        val candidates = buildCandidatePairs(cfg)
        if (candidates.isEmpty()) {
            return Result.failure(IllegalStateException("Chưa nhập Google AI Studio API Key trong tab Cấu hình API."))
        }

        val ctxStr = formatContext()
        val systemPrompt =
            "Bạn là bộ dịch phụ đề video trực tiếp siêu tốc từ [${cfg.sourceLanguage}] sang [${cfg.targetLanguage}].\n" +
            "- Chỉ trả về ĐÚNG 1 dòng bản dịch ngắn gọn, tự nhiên, phù hợp để đọc lồng tiếng ngay.\n" +
            "- Không giải thích, không thêm ngoặc kép thừa.\n" +
            "- Ngữ cảnh các câu trước:\n$ctxStr"

        val payload = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", sourceText)))
            }))
            put("generationConfig", JSONObject().put("temperature", 0.15))
        }

        var lastError: Throwable? = null
        for ((apiKey, model) in candidates) {
            try {
                val out = executeGenerateContent(apiKey, model, payload)
                    .lineSequence()
                    .firstOrNull { it.isNotBlank() }
                    ?.trim()
                    .orEmpty()
                if (out.isNotEmpty()) {
                    appendContext(sourceText, out)
                    return Result.success(out)
                }
            } catch (e: HttpStatusException) {
                lastError = e
                if (e.code == 429 || e.message?.contains("RESOURCE_EXHAUSTED", ignoreCase = true) == true) {
                    cooldownMap["$apiKey|$model"] = System.currentTimeMillis() + 60_000L
                    continue
                }
                if (e.code == 404) {
                    // Retired or unavailable model: skip for 24h and rotate to next model
                    cooldownMap["$apiKey|$model"] = System.currentTimeMillis() + 86_400_000L
                    continue
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        return Result.failure(lastError ?: RuntimeException("Không thể dịch với các API Key hiện tại."))
    }

    fun translateAudioWav(wavBytes: ByteArray, cfg: LiveConfig): Result<AudioTranslationResult> {
        val candidates = buildCandidatePairs(cfg)
        if (candidates.isEmpty()) {
            return Result.failure(IllegalStateException("Chưa nhập Google AI Studio API Key."))
        }

        val b64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        val prompt =
            "Nghe đoạn âm thanh ngắn này (ngôn ngữ gốc: ${cfg.sourceLanguage}) và dịch trực tiếp sang ${cfg.targetLanguage}.\n" +
            "Ngữ cảnh các câu trước:\n${formatContext()}\n\n" +
            "Trả về ĐÚNG JSON 1 dòng: {\"source\": \"câu gốc\", \"translation\": \"bản dịch ngắn gọn tự nhiên\"}\n" +
            "Nếu chỉ có nhạc nền/tiếng ồn không có tiếng người nói, trả về: {\"source\": \"\", \"translation\": \"\"}"

        val parts = JSONArray()
            .put(JSONObject().apply {
                put("inlineData", JSONObject().apply {
                    put("mimeType", "audio/wav")
                    put("data", b64Audio)
                })
            })
            .put(JSONObject().put("text", prompt))

        val payload = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            put("generationConfig", JSONObject().put("temperature", 0.15))
        }

        var lastError: Throwable? = null
        for ((apiKey, model) in candidates) {
            try {
                val raw = executeGenerateContent(apiKey, model, payload)
                val parsed = parseAudioJson(raw)
                if (parsed.translatedText.isNotBlank()) {
                    appendContext(parsed.sourceText.ifBlank { "(Audio)" }, parsed.translatedText)
                }
                return Result.success(parsed)
            } catch (e: HttpStatusException) {
                lastError = e
                if (e.code == 429 || e.message?.contains("RESOURCE_EXHAUSTED", ignoreCase = true) == true) {
                    cooldownMap["$apiKey|$model"] = System.currentTimeMillis() + 60_000L
                    continue
                }
                if (e.code == 404) {
                    cooldownMap["$apiKey|$model"] = System.currentTimeMillis() + 86_400_000L
                    continue
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        return Result.failure(lastError ?: RuntimeException("Lỗi dịch âm thanh."))
    }

    private fun executeGenerateContent(apiKey: String, model: String, body: JSONObject): String {
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val respText = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) {
                throw HttpStatusException(code, "HTTP $code: ${respText.take(240)}")
            }
            return extractCandidateText(respText)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        class HttpStatusException(val code: Int, message: String) : Exception(message)

        fun extractCandidateText(rawJsonResponse: String): String {
            val root = JSONObject(rawJsonResponse)
            val candidates = root.optJSONArray("candidates") ?: return ""
            if (candidates.length() == 0) return ""
            val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: return ""
            val parts = content.optJSONArray("parts") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val t = parts.optJSONObject(i)?.optString("text", "") ?: ""
                if (t.isNotEmpty()) sb.append(t)
            }
            return sb.toString().trim()
        }

        fun parseAudioJson(rawText: String): AudioTranslationResult {
            var cleaned = rawText.trim()
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replace(Regex("^```[a-zA-Z]*\\s*"), "").replace(Regex("\\s*```$"), "")
            }
            val startIdx = cleaned.indexOf('{')
            val endIdx = cleaned.lastIndexOf('}')
            if (startIdx >= 0 && endIdx > startIdx) {
                cleaned = cleaned.substring(startIdx, endIdx + 1)
            }
            return try {
                val obj = JSONObject(cleaned)
                AudioTranslationResult(
                    sourceText = obj.optString("source", "").trim(),
                    translatedText = obj.optString("translation", "").trim(),
                )
            } catch (_: Exception) {
                AudioTranslationResult("", "")
            }
        }
    }
}
