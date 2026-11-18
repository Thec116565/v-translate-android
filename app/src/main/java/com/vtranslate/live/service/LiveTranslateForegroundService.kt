package com.vtranslate.live.service

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.vtranslate.live.data.GeminiPoolClient
import com.vtranslate.live.data.SettingsRepository
import com.vtranslate.live.domain.CaptureMode
import com.vtranslate.live.domain.LiveConfig
import com.vtranslate.live.domain.OcrRegionPct
import com.vtranslate.live.domain.TextDeduplicator
import com.vtranslate.live.domain.WavAudioEncoder
import com.vtranslate.live.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Standalone Android Foreground Service that executes:
 * - Mode A: MediaProjection Screen Capture + ML Kit On-Device OCR + Gemini Pool Translation
 * - Mode B: AudioPlaybackCapture Internal Audio + RMS VAD + Gemini 1-Hop Audio Translation
 * - Floating Subtitle Overlay + Dynamic TTS Dubbing with Automatic App Audio Ducking
 */
class LiveTranslateForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var captureJob: Job? = null

    private lateinit var settingsRepo: SettingsRepository
    private val geminiClient = GeminiPoolClient()
    private val deduplicator = TextDeduplicator()
    private lateinit var overlayController: FloatingOverlayController
    private lateinit var ttsSpeaker: TtsAudioDuckerSpeaker

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var audioRecord: AudioRecord? = null

    private val isTtsSpeaking = AtomicBoolean(false)
    private var sessionStartEpochMs: Long = 0L

    @Volatile
    private var cachedConfig: LiveConfig = LiveConfig()

    private val toolbarCallbacks = object : FloatingOverlayController.ToolbarCallbacks {
        override fun onTogglePause() = handleTogglePause()

        override fun onStop() {
            stopLiveSession()
            stopSelf()
        }

        override fun onCycleModel() {
            val cfg = cachedConfig
            val pool = cfg.flashModelPool.ifEmpty {
                listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-3.1-pro-preview")
            }
            val curIdx = pool.indexOf(cfg.preferredModel)
            val nextModel = pool[(curIdx + 1) % pool.size]
            val updated = cfg.copy(preferredModel = nextModel)
            cachedConfig = updated
            settingsRepo.saveConfig(updated)
            overlayController.updateToolbarState(
                paused = LiveSessionStateBus.isPaused.value,
                model = nextModel,
                mode = updated.captureMode,
                clickThrough = LiveSessionStateBus.clickThrough.value,
            )
            overlayController.showToastBanner("🤖 Đã chuyển sang: $nextModel")
            LiveSessionStateBus.log("🤖 Đổi model nhanh -> $nextModel")
        }

        override fun onSwitchMode() {
            val cfg = cachedConfig
            val nextMode = if (cfg.captureMode == CaptureMode.SCREEN_OCR) {
                CaptureMode.INTERNAL_AUDIO
            } else {
                CaptureMode.SCREEN_OCR
            }
            val updated = cfg.copy(captureMode = nextMode)
            cachedConfig = updated
            settingsRepo.saveConfig(updated)
            overlayController.updateToolbarState(
                paused = LiveSessionStateBus.isPaused.value,
                model = updated.preferredModel,
                mode = nextMode,
                clickThrough = LiveSessionStateBus.clickThrough.value,
            )
            overlayController.showToastBanner("🔄 Chế độ: ${nextMode.titleVi}")
            LiveSessionStateBus.log("🔄 Chuyển nhanh sang: ${nextMode.titleVi}")
            restartCaptureLoopOnly(nextMode)
        }

        override fun onToggleClickThrough() {
            val next = !LiveSessionStateBus.clickThrough.value
            val updated = cachedConfig.copy(overlayClickThrough = next)
            cachedConfig = updated
            settingsRepo.saveConfig(updated)
            overlayController.setClickThrough(next)
            overlayController.showToastBanner(if (next) "🔒 Đã khóa xuyên chạm" else "🔓 Đã mở khóa chạm")
        }

        override fun onRegionEdited(newRegion: OcrRegionPct) {
            val updated = cachedConfig.copy(ocrRegion = newRegion)
            cachedConfig = updated
            settingsRepo.saveConfig(updated)
        }
    }

    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository(this)
        overlayController = FloatingOverlayController(this)
        ttsSpeaker = TtsAudioDuckerSpeaker(this) { speaking ->
            isTtsSpeaking.set(speaking)
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startLiveSession(resultCode, resultData)
                } else {
                    LiveSessionStateBus.log("❌ Chưa cấp quyền ghi màn hình MediaProjection.")
                    stopSelf()
                }
            }
            ACTION_TOGGLE_PAUSE -> handleTogglePause()
            ACTION_TOGGLE_CLICK_THROUGH -> toolbarCallbacks.onToggleClickThrough()
            ACTION_REFRESH_STYLE -> {
                val cfg = settingsRepo.loadConfig()
                cachedConfig = cfg
                overlayController.applyStyle(cfg)
                overlayController.setClickThrough(cfg.overlayClickThrough)
                overlayController.updateToolbarState(
                    paused = LiveSessionStateBus.isPaused.value,
                    model = cfg.preferredModel,
                    mode = cfg.captureMode,
                    clickThrough = cfg.overlayClickThrough,
                )
                overlayController.showOrUpdateRegionGuide(cfg.ocrRegion, LiveSessionStateBus.showRegionBox.value)
                ttsSpeaker.baseSpeed = cfg.ttsBaseSpeed
                ttsSpeaker.basePitch = cfg.ttsPitch
                ttsSpeaker.selectedVoiceName = cfg.ttsVoiceName
                ttsSpeaker.enableDucking = cfg.enableAudioDucking
            }
            ACTION_STOP -> {
                stopLiveSession()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startLiveSession(resultCode: Int, resultData: Intent) {
        try {
            val notification = buildForegroundNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            LiveSessionStateBus.log("❌ Lỗi Foreground Service: ${e.message}")
            stopSelf()
            return
        }

        stopCaptureResources()
        val cfg = settingsRepo.loadConfig()
        cachedConfig = cfg
        geminiClient.clearContext()
        deduplicator.reset()
        sessionStartEpochMs = System.currentTimeMillis()

        ttsSpeaker.baseSpeed = cfg.ttsBaseSpeed
        ttsSpeaker.basePitch = cfg.ttsPitch
        ttsSpeaker.selectedVoiceName = cfg.ttsVoiceName
        ttsSpeaker.enableDucking = cfg.enableAudioDucking

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, resultData)
        if (proj == null) {
            LiveSessionStateBus.log("❌ Không nhận được quyền MediaProjection từ hệ thống.")
            stopSelf()
            return
        }
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                LiveSessionStateBus.log("⏹ Hệ thống đã dừng phiên ghi màn hình.")
                stopLiveSession()
                stopSelf()
            }
        }
        proj.registerCallback(cb, Handler(Looper.getMainLooper()))
        projectionCallback = cb
        mediaProjection = proj

        overlayController.showSubtitleOverlay(cfg)
        overlayController.showVerticalToolbar(cfg, toolbarCallbacks)
        LiveSessionStateBus.setRunning(true)
        LiveSessionStateBus.log("🚀 Đã bật Dịch Trực Tiếp (${cfg.captureMode.titleVi}) -> ${cfg.targetLanguage}")

        restartCaptureLoopOnly(cfg.captureMode)
    }

    private fun handleTogglePause() {
        val nextPaused = !LiveSessionStateBus.isPaused.value
        LiveSessionStateBus.setPaused(nextPaused)
        if (nextPaused) {
            ttsSpeaker.stopImmediately()
            LiveSessionStateBus.log("⏸ Đã tạm dừng dịch trực tiếp.")
        } else {
            LiveSessionStateBus.log("▶ Tiếp tục dịch trực tiếp.")
        }
        overlayController.updateToolbarState(
            paused = nextPaused,
            model = cachedConfig.preferredModel,
            mode = cachedConfig.captureMode,
            clickThrough = LiveSessionStateBus.clickThrough.value,
        )
    }

    private fun restartCaptureLoopOnly(mode: CaptureMode) {
        captureJob?.cancel()
        captureJob = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null

        captureJob = serviceScope.launch {
            if (mode == CaptureMode.SCREEN_OCR) {
                runScreenOcrLoop()
            } else {
                runInternalAudioLoop()
            }
        }
    }

    private suspend fun runScreenOcrLoop() {
        var currentLangKey = ""
        var recognizer: TextRecognizer? = null
        var prevLumaSample: ByteArray? = null

        try {
            val dm = resources.displayMetrics
            val screenW = dm.widthPixels.coerceAtLeast(360)
            val screenH = dm.heightPixels.coerceAtLeast(640)
            val density = dm.densityDpi.coerceAtLeast(160)

            val reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
            imageReader = reader
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "VTranslateLiveOCR",
                screenW,
                screenH,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )

            while (serviceScope.isActive && LiveSessionStateBus.isRunning.value) {
                val cfg = cachedConfig
                val intervalMs = (1000f / cfg.sampleFps.coerceIn(1.0f, 8.0f)).toLong()

                if (LiveSessionStateBus.isPaused.value) {
                    delay(250L)
                    continue
                }

                if (recognizer == null || currentLangKey != cfg.sourceLanguage) {
                    recognizer?.close()
                    recognizer = buildMlKitRecognizer(cfg.sourceLanguage)
                    currentLangKey = cfg.sourceLanguage
                }

                val cropBmp = acquireCroppedScreenBitmap(reader, cfg)
                if (cropBmp != null) {
                    try {
                        val currLuma = sampleBitmapLuma(cropBmp)
                        val diff = TextDeduplicator.meanLumaDifference(prevLumaSample, currLuma)
                        if (diff >= 2.3) {
                            prevLumaSample = currLuma
                            val inputImg = InputImage.fromBitmap(cropBmp, 0)
                            val visionText = recognizer.process(inputImg).await()
                            val rawText = visionText.textBlocks
                                .sortedBy { it.boundingBox?.top ?: 0 }
                                .joinToString(" ") { it.text.trim() }
                                .trim()

                            val freshLine = deduplicator.filterNewSubtitle(rawText)
                            if (!freshLine.isNullOrBlank()) {
                                translateAndEmitText(freshLine, cfg)
                            }
                        }
                    } finally {
                        cropBmp.recycle()
                    }
                }
                delay(intervalMs)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LiveSessionStateBus.log("⚠️ Lỗi quét màn hình OCR: ${e.message}")
        } finally {
            recognizer?.close()
        }
    }

    /**
     * Memory-optimized cropped screen capture: slices ONLY the target vertical row band [y .. y+h]
     * from the ImageReader ByteBuffer instead of allocating a full-screen 10MB Bitmap per frame.
     */
    private fun acquireCroppedScreenBitmap(reader: ImageReader, cfg: LiveConfig): Bitmap? {
        val image = reader.acquireLatestImage() ?: return null
        try {
            val plane = image.planes.firstOrNull() ?: return null
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride

            val r = cfg.ocrRegion
            val x = (r.leftPct.coerceIn(0f, 0.9f) * image.width).toInt()
            val y = (r.topPct.coerceIn(0f, 0.9f) * image.height).toInt()
            val w = ((r.rightPct - r.leftPct).coerceIn(0.1f, 1.0f) * image.width).toInt()
                .coerceAtMost(image.width - x).coerceAtLeast(32)
            val h = ((r.bottomPct - r.topPct).coerceIn(0.05f, 1.0f) * image.height).toInt()
                .coerceAtMost(image.height - y).coerceAtLeast(24)

            val fullStrideW = rowStride / pixelStride
            val startOffset = y * rowStride
            if (startOffset + h * rowStride <= buffer.capacity()) {
                buffer.position(startOffset)
                val bandBmp = Bitmap.createBitmap(fullStrideW, h, Bitmap.Config.ARGB_8888)
                bandBmp.copyPixelsFromBuffer(buffer.slice())
                val cropped = Bitmap.createBitmap(bandBmp, x, 0, w.coerceAtMost(bandBmp.width - x), h)
                bandBmp.recycle()
                return cropped
            }

            // Fallback if row padding differs on the final row
            buffer.rewind()
            val fullBmp = Bitmap.createBitmap(fullStrideW, image.height, Bitmap.Config.ARGB_8888)
            fullBmp.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(fullBmp, x, y, w, h)
            fullBmp.recycle()
            return cropped
        } catch (_: Exception) {
            return null
        } finally {
            image.close()
        }
    }

    private fun sampleBitmapLuma(bmp: Bitmap): ByteArray {
        val stepX = (bmp.width / 32).coerceAtLeast(1)
        val stepY = (bmp.height / 12).coerceAtLeast(1)
        val out = ByteArrayOutputStream(512)
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val px = bmp.getPixel(x, y)
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                val luma = ((r * 30 + g * 59 + b * 11) / 100).toByte()
                out.write(luma.toInt())
                x += stepX
            }
            y += stepY
        }
        return out.toByteArray()
    }

    private fun buildMlKitRecognizer(sourceLang: String): TextRecognizer {
        return when {
            sourceLang.contains("Nhật", ignoreCase = true) ->
                TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            sourceLang.contains("Hàn", ignoreCase = true) ->
                TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            sourceLang.contains("Anh", ignoreCase = true) || sourceLang.contains("Pháp", ignoreCase = true) ->
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            else ->
                // Chinese recognizer handles both Chinese (Douyin/Bilibili) and Latin characters!
                TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runInternalAudioLoop() {
        val proj = mediaProjection ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            LiveSessionStateBus.log("❌ Thu âm nội bộ cần Android 10 (API 29) trở lên.")
            return
        }

        val sampleRate = 16000
        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3200)

        val record = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .build()

        audioRecord = record
        record.startRecording()
        LiveSessionStateBus.log("🔊 Đang thu âm thanh nội bộ từ ứng dụng phát video (16kHz Mono)...")

        val frameSamples = 1600 // 100ms at 16kHz
        val shortBuf = ShortArray(frameSamples)
        val byteChunk = ByteArrayOutputStream()
        var speechSec = 0.0
        var silenceSec = 0.0

        try {
            while (serviceScope.isActive && LiveSessionStateBus.isRunning.value) {
                if (LiveSessionStateBus.isPaused.value) {
                    delay(200L)
                    continue
                }

                val readCount = record.read(shortBuf, 0, frameSamples)
                if (readCount <= 0) {
                    delay(30L)
                    continue
                }

                // Anti-feedback gate: ignore internal audio frames while our own TTS is speaking
                if (isTtsSpeaking.get()) {
                    continue
                }

                val rms = WavAudioEncoder.calculateRms(shortBuf, readCount)
                val frameDur = readCount.toDouble() / sampleRate.toDouble()

                if (rms >= 300.0) {
                    appendShortsLittleEndian(byteChunk, shortBuf, readCount)
                    speechSec += frameDur
                    silenceSec = 0.0
                } else if (speechSec > 0.0) {
                    appendShortsLittleEndian(byteChunk, shortBuf, readCount)
                    silenceSec += frameDur
                }

                val shouldFlush = speechSec >= 4.6 || (speechSec >= 1.4 && silenceSec >= 0.45)
                if (shouldFlush && byteChunk.size() > 0) {
                    val pcmBytes = byteChunk.toByteArray()
                    byteChunk.reset()
                    speechSec = 0.0
                    silenceSec = 0.0

                    val cfg = settingsRepo.loadConfig()
                    val wavBytes = WavAudioEncoder.encodePcm16ToWavBytes(pcmBytes, sampleRate, 1)
                    serviceScope.launch(Dispatchers.IO) {
                        val res = geminiClient.translateAudioWav(wavBytes, cfg)
                        res.onSuccess { item ->
                            if (item.translatedText.isNotBlank()) {
                                onTranslationReady(
                                    item.sourceText.ifBlank { "🎵 (Âm thanh trực tiếp)" },
                                    item.translatedText,
                                    cfg
                                )
                            }
                        }.onFailure { err ->
                            LiveSessionStateBus.log("⚠️ Lỗi dịch Audio: ${err.message}")
                        }
                    }
                }
            }
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            audioRecord = null
        }
    }

    private fun appendShortsLittleEndian(out: ByteArrayOutputStream, samples: ShortArray, count: Int) {
        for (i in 0 until count) {
            val v = samples[i].toInt()
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
    }

    private fun translateAndEmitText(sourceText: String, cfg: LiveConfig) {
        serviceScope.launch(Dispatchers.IO) {
            val res = geminiClient.translateSubtitleText(sourceText, cfg)
            res.onSuccess { translated ->
                if (translated.isNotBlank()) {
                    onTranslationReady(sourceText, translated, cfg)
                }
            }.onFailure { err ->
                LiveSessionStateBus.log("⚠️ Lỗi Gemini API: ${err.message}")
            }
        }
    }

    private fun onTranslationReady(sourceText: String, translatedText: String, cfg: LiveConfig) {
        val elapsed = (System.currentTimeMillis() - sessionStartEpochMs).coerceAtLeast(0L)
        LiveSessionStateBus.publishCue(sourceText, translatedText, elapsed)
        overlayController.updateSubtitles(sourceText, translatedText)
        if (cfg.enableTtsDubbing && !LiveSessionStateBus.isPaused.value) {
            ttsSpeaker.enqueueSpeech(translatedText, cfg.targetLanguage)
        }
    }

    private fun stopCaptureResources() {
        captureJob?.cancel()
        captureJob = null
        ttsSpeaker.stopImmediately()
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        projectionCallback?.let { cb ->
            runCatching { mediaProjection?.unregisterCallback(cb) }
        }
        projectionCallback = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
    }

    private fun stopLiveSession() {
        stopCaptureResources()
        overlayController.hideAll()
        LiveSessionStateBus.setRunning(false)
        LiveSessionStateBus.log("⏹ Đã dừng phiên dịch trực tiếp & trả lại âm lượng gốc.")
    }

    override fun onDestroy() {
        stopLiveSession()
        ttsSpeaker.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "V-Translate Dịch Trực Tiếp",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LiveTranslateForegroundService::class.java).apply { action = ACTION_TOGGLE_PAUSE },
            PendingIntent.FLAG_IMMUTABLE
        )
        val lockIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, LiveTranslateForegroundService::class.java).apply { action = ACTION_TOGGLE_CLICK_THROUGH },
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, LiveTranslateForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("V-Translate Live đang hoạt động")
            .setContentText("Đang dịch & lồng tiếng trực tiếp trên màn hình")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, "Tạm dừng/Tiếp", pauseIntent)
            .addAction(0, "Khóa/Mở chạm", lockIntent)
            .addAction(0, "Dừng", stopIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "vtranslate_live_channel"
        const val NOTIFICATION_ID = 1008
        const val ACTION_START = "com.vtranslate.live.ACTION_START"
        const val ACTION_TOGGLE_PAUSE = "com.vtranslate.live.ACTION_TOGGLE_PAUSE"
        const val ACTION_TOGGLE_CLICK_THROUGH = "com.vtranslate.live.ACTION_TOGGLE_CLICK_THROUGH"
        const val ACTION_REFRESH_STYLE = "com.vtranslate.live.ACTION_REFRESH_STYLE"
        const val ACTION_STOP = "com.vtranslate.live.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
    }
}
