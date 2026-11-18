package com.vtranslate.live.data

import android.content.Context
import android.content.SharedPreferences
import com.vtranslate.live.domain.CaptureMode
import com.vtranslate.live.domain.LiveConfig
import com.vtranslate.live.domain.OcrRegionPct

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("vtranslate_live_prefs", Context.MODE_PRIVATE)

    fun loadConfig(): LiveConfig {
        val rawKeys = prefs.getString("api_keys", "") ?: ""
        val keys = rawKeys.split(",", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val rawModels = prefs.getString(
            "flash_models",
            "gemini-3.8-flash,gemini-3.5-flash-lite,gemini-3.1-pro-preview,gemini-2.5-flash"
        ) ?: ""
        val models = rawModels.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("gemini-2.0") && it != "gemini-2.5-pro" }

        val savedPref = prefs.getString("preferred_model", "gemini-3.8-flash") ?: "gemini-3.8-flash"
        val cleanPref = if (savedPref.startsWith("gemini-2.0") || savedPref == "gemini-2.5-pro") {
            "gemini-3.8-flash"
        } else {
            savedPref
        }

        return LiveConfig(
            apiKeys = keys,
            preferredModel = cleanPref,
            autoRotateFlashModels = prefs.getBoolean("auto_rotate_flash", true),
            flashModelPool = models.ifEmpty {
                listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-3.1-pro-preview")
            },
            captureMode = CaptureMode.fromId(prefs.getString("capture_mode", CaptureMode.SCREEN_OCR.id)),
            sourceLanguage = prefs.getString("source_lang", "Tự động nhận diện (Auto)") ?: "Tự động nhận diện (Auto)",
            targetLanguage = prefs.getString("target_lang", "Tiếng Việt") ?: "Tiếng Việt",
            sampleFps = prefs.getFloat("sample_fps", 3.5f),
            ocrRegion = OcrRegionPct(
                leftPct = prefs.getFloat("ocr_left", 0.06f),
                topPct = prefs.getFloat("ocr_top", 0.68f),
                rightPct = prefs.getFloat("ocr_right", 0.94f),
                bottomPct = prefs.getFloat("ocr_bottom", 0.88f),
            ),
            enableTtsDubbing = prefs.getBoolean("enable_tts", true),
            enableAudioDucking = prefs.getBoolean("enable_ducking", true),
            ttsBaseSpeed = prefs.getFloat("tts_speed", 1.15f),
            ttsPitch = prefs.getFloat("tts_pitch", 1.0f),
            ttsVoiceName = prefs.getString("tts_voice_name", "") ?: "",
            overlayFontSizeSp = prefs.getInt("overlay_font_sp", 20),
            overlayTextColorHex = prefs.getString("overlay_color", "#FFE600") ?: "#FFE600",
            overlayBgAlpha = prefs.getFloat("overlay_alpha", 0.82f),
            overlayClickThrough = prefs.getBoolean("overlay_click_through", false),
        )
    }

    fun saveConfig(cfg: LiveConfig) {
        prefs.edit()
            .putString("api_keys", cfg.apiKeys.joinToString("\n"))
            .putString("preferred_model", cfg.preferredModel)
            .putBoolean("auto_rotate_flash", cfg.autoRotateFlashModels)
            .putString("flash_models", cfg.flashModelPool.joinToString(","))
            .putString("capture_mode", cfg.captureMode.id)
            .putString("source_lang", cfg.sourceLanguage)
            .putString("target_lang", cfg.targetLanguage)
            .putFloat("sample_fps", cfg.sampleFps)
            .putFloat("ocr_left", cfg.ocrRegion.leftPct)
            .putFloat("ocr_top", cfg.ocrRegion.topPct)
            .putFloat("ocr_right", cfg.ocrRegion.rightPct)
            .putFloat("ocr_bottom", cfg.ocrRegion.bottomPct)
            .putBoolean("enable_tts", cfg.enableTtsDubbing)
            .putBoolean("enable_ducking", cfg.enableAudioDucking)
            .putFloat("tts_speed", cfg.ttsBaseSpeed)
            .putFloat("tts_pitch", cfg.ttsPitch)
            .putString("tts_voice_name", cfg.ttsVoiceName)
            .putInt("overlay_font_sp", cfg.overlayFontSizeSp)
            .putString("overlay_color", cfg.overlayTextColorHex)
            .putFloat("overlay_alpha", cfg.overlayBgAlpha)
            .putBoolean("overlay_click_through", cfg.overlayClickThrough)
            .apply()
    }
}
