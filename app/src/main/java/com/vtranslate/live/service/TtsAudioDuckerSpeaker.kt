package com.vtranslate.live.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real-Time Android TTS Dubbing Speaker with:
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

    fun enqueueSpeech(text: String, targetLanguage: String = "Tiếng Việt") {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return

        // Update locale dynamically if user changes target language
        if (isReady) {
            val targetLocale = when {
                targetLanguage.contains("Việt", ignoreCase = true) -> Locale("vi", "VN")
                targetLanguage.contains("English", ignoreCase = true) -> Locale.US
                targetLanguage.contains("中", ignoreCase = true) -> Locale.SIMPLIFIED_CHINESE
                targetLanguage.contains("日本", ignoreCase = true) -> Locale.JAPANESE
                targetLanguage.contains("한국", ignoreCase = true) -> Locale.KOREAN
                else -> Locale("vi", "VN")
            }
            tts?.language = targetLocale
        }

        // Prevent runaway queue if video speaks extremely fast
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
            // Drop stale sentence older than 7.5s if newer ones are waiting
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
        runCatching {
            audioManager.requestAudioFocus(audioFocusRequest)
        }
    }

    private fun abandonDuckingFocus() {
        runCatching {
            audioManager.abandonAudioFocusRequest(audioFocusRequest)
        }
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
