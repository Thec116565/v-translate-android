package com.vtranslate.live.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class TtsVoiceOption(
    val name: String,
    val displayLabel: String,
    val localeTag: String,
    val isNetworkRequired: Boolean,
)

/**
 * Real-Time Android TTS Dubbing Speaker with:
 * - Multiple selectable system/neural voices per language
 * - Automatic Audio Focus Ducking (AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK lowers Douyin/TikTok volume while speaking)
 * - Dynamic Speed Catch-Up when subtitles arrive rapidly
 * - Anti-Feedback Flag so Internal Audio Capture knows when TTS is actively speaking
 */
class TtsAudioDuckerSpeaker(
    context: Context,
    private val onSpeakingStateChanged: (Boolean) -> Unit = {},
) : TextToSpeech.OnInitListener {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isReady = false

    private val queue = ConcurrentLinkedQueue<Pair<String, Long>>()
    private val isSpeakingNow = AtomicBoolean(false)
    private val utteranceCounter = AtomicInteger(0)

    var baseSpeed: Float = 1.15f
    var basePitch: Float = 1.0f
    var enableDucking: Boolean = true
    var selectedVoiceName: String = ""

    private val audioFocusRequest: AudioFocusRequest = AudioFocusRequest.Builder(
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
    )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setWillPauseWhenDucked(false)
        .build()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("vi", "VN")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeakingNow.set(true)
                    onSpeakingStateChanged(true)
                }

                override fun onDone(utteranceId: String?) {
                    onUtteranceFinished()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    onUtteranceFinished()
                }
            })
            isReady = true
            drainNext()
        }
    }

    fun listVoicesForLanguage(targetLanguage: String): List<TtsVoiceOption> {
        if (!isReady) return emptyList()
        val engine = tts ?: return emptyList()
        val prefix = languagePrefix(targetLanguage)
        val voices: Set<Voice> = runCatching { engine.voices }.getOrNull() ?: return emptyList()
        return voices
            .filter { v ->
                val lang = v.locale.language.lowercase()
                lang == prefix || v.locale.toLanguageTag().lowercase().startsWith(prefix)
            }
            .sortedWith(compareBy<Voice> { it.isNetworkConnectionRequired }.thenBy { it.name })
            .mapIndexed { idx, v ->
                val netTag = if (v.isNetworkConnectionRequired) "Online" else "Offline"
                val tag = v.locale.toLanguageTag()
                val shortCode = v.name.substringAfterLast("-").take(14)
                TtsVoiceOption(
                    name = v.name,
                    displayLabel = "Giọng ${idx + 1} ($tag · $netTag · $shortCode)",
                    localeTag = tag,
                    isNetworkRequired = v.isNetworkConnectionRequired,
                )
            }
    }

    fun previewVoice(sampleText: String, targetLanguage: String, voiceName: String) {
        selectedVoiceName = voiceName
        enqueueSpeech(sampleText, targetLanguage)
    }

    private fun languagePrefix(targetLanguage: String): String = when {
        targetLanguage.contains("Việt", ignoreCase = true) -> "vi"
        targetLanguage.contains("English", ignoreCase = true) -> "en"
        targetLanguage.contains("中", ignoreCase = true) -> "zh"
        targetLanguage.contains("日本", ignoreCase = true) -> "ja"
        targetLanguage.contains("한국", ignoreCase = true) -> "ko"
        targetLanguage.contains("ไทย", ignoreCase = true) -> "th"
        else -> "vi"
    }

    private fun applyTargetVoiceAndLocale(engine: TextToSpeech, targetLanguage: String) {
        if (selectedVoiceName.isNotBlank()) {
            val match = runCatching { engine.voices?.firstOrNull { it.name == selectedVoiceName } }.getOrNull()
            if (match != null) {
                engine.voice = match
                return
            }
        }
        val targetLocale = when (languagePrefix(targetLanguage)) {
            "en" -> Locale.US
            "zh" -> Locale.SIMPLIFIED_CHINESE
            "ja" -> Locale.JAPANESE
            "ko" -> Locale.KOREAN
            else -> Locale("vi", "VN")
        }
        engine.language = targetLocale
    }

    fun enqueueSpeech(text: String, targetLanguage: String = "Tiếng Việt") {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return

        if (isReady) {
            tts?.let { applyTargetVoiceAndLocale(it, targetLanguage) }
        }

        while (queue.size >= 4) {
            queue.poll()
        }
        queue.offer(cleaned to System.currentTimeMillis())

        if (!isSpeakingNow.get()) {
            drainNext()
        }
    }

    private fun drainNext() {
        if (!isReady) return
        val engine = tts ?: return

        val now = System.currentTimeMillis()
        var nextItem = queue.poll()
        while (nextItem != null && now - nextItem.second > 7_500L && queue.isNotEmpty()) {
            nextItem = queue.poll()
        }

        if (nextItem == null) {
            isSpeakingNow.set(false)
            onSpeakingStateChanged(false)
            abandonDuckingFocus()
            return
        }

        val backlog = queue.size
        val dynamicSpeed = when {
            backlog >= 2 -> (baseSpeed + 0.30f).coerceAtMost(1.65f)
            backlog == 1 -> (baseSpeed + 0.15f).coerceAtMost(1.50f)
            else -> baseSpeed.coerceIn(0.75f, 1.50f)
        }

        if (enableDucking) {
            requestDuckingFocus()
        }

        isSpeakingNow.set(true)
        onSpeakingStateChanged(true)
        engine.setPitch(basePitch.coerceIn(0.6f, 1.5f))
        engine.setSpeechRate(dynamicSpeed)

        val uId = "vt_utt_${utteranceCounter.incrementAndGet()}"
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        engine.speak(nextItem.first, TextToSpeech.QUEUE_FLUSH, params, uId)
    }

    private fun onUtteranceFinished() {
        if (queue.isNotEmpty()) {
            drainNext()
        } else {
            isSpeakingNow.set(false)
            onSpeakingStateChanged(false)
            abandonDuckingFocus()
        }
    }

    private fun requestDuckingFocus() {
        runCatching { audioManager.requestAudioFocus(audioFocusRequest) }
    }

    private fun abandonDuckingFocus() {
        runCatching { audioManager.abandonAudioFocusRequest(audioFocusRequest) }
    }

    fun stopImmediately() {
        queue.clear()
        isSpeakingNow.set(false)
        onSpeakingStateChanged(false)
        runCatching { tts?.stop() }
        abandonDuckingFocus()
    }

    fun shutdown() {
        stopImmediately()
        runCatching { tts?.shutdown() }
        tts = null
        isReady = false
    }
}
