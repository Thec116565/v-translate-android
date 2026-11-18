package com.vtranslate.live.domain

enum class CaptureMode(val id: String, val titleVi: String) {
    SCREEN_OCR("screen_ocr", "Quét Phụ Đề Màn Hình (Douyin / TikTok / Hardsub)"),
    INTERNAL_AUDIO("internal_audio", "Thu Âm Thanh Nội Bộ (AudioPlaybackCapture)");

    companion object {
        fun fromId(id: String?): CaptureMode =
            entries.firstOrNull { it.id == id } ?: SCREEN_OCR
    }
}

data class OcrRegionPct(
    val leftPct: Float = 0.06f,
    val topPct: Float = 0.68f,
    val rightPct: Float = 0.94f,
    val bottomPct: Float = 0.88f,
)

data class LiveConfig(
    val apiKeys: List<String> = emptyList(),
    val preferredModel: String = "gemini-2.5-flash",
    val autoRotateFlashModels: Boolean = true,
    val flashModelPool: List<String> = listOf(
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite",
        "gemini-3.5-flash-lite",
        "gemini-2.5-pro"
    ),
    val captureMode: CaptureMode = CaptureMode.SCREEN_OCR,
    val sourceLanguage: String = "Tự động nhận diện (Auto)",
    val targetLanguage: String = "Tiếng Việt",
    val sampleFps: Float = 3.5f,
    val ocrRegion: OcrRegionPct = OcrRegionPct(),
    val enableTtsDubbing: Boolean = true,
    val enableAudioDucking: Boolean = true,
    val ttsBaseSpeed: Float = 1.15f,
    val ttsPitch: Float = 1.0f,
    val overlayFontSizeSp: Int = 20,
    val overlayTextColorHex: String = "#FFE600",
    val overlayBgAlpha: Float = 0.82f,
    val overlayClickThrough: Boolean = false,
)

data class LiveSubtitleCue(
    val index: Int,
    val timestampMs: Long,
    val sourceText: String,
    val translatedText: String,
) {
    fun toSrtBlock(): String {
        val start = formatSrtTime(timestampMs)
        val end = formatSrtTime(timestampMs + 2800L)
        return "$index\n$start --> $end\n$translatedText\n"
    }

    companion object {
        fun formatSrtTime(ms: Long): String {
            val clamped = ms.coerceAtLeast(0L)
            val millis = clamped % 1000L
            val totalSec = clamped / 1000L
            val sec = totalSec % 60L
            val totalMin = totalSec / 60L
            val min = totalMin % 60L
            val hr = totalMin / 60L
            return String.format("%02d:%02d:%02d,%03d", hr, min, sec, millis)
        }
    }
}
