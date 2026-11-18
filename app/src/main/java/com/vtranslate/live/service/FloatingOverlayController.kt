package com.vtranslate.live.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.vtranslate.live.domain.LiveConfig
import com.vtranslate.live.domain.OcrRegionPct

/**
 * Manages the floating subtitle banner overlay (SYSTEM_ALERT_WINDOW) on top of Douyin/TikTok/YouTube:
 * - Supports Drag-to-Reposition when unlocked
 * - Supports Click-Through (FLAG_NOT_TOUCHABLE) when locked so user swipes & taps Douyin freely
 * - Optional visual OCR scan region frame guide
 */
class FloatingOverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var subtitleRoot: LinearLayout? = null
    private var tvSource: TextView? = null
    private var tvTranslated: TextView? = null
    private var subtitleParams: WindowManager.LayoutParams? = null

    private var regionGuideView: View? = null
    private var regionGuideParams: WindowManager.LayoutParams? = null

    fun canDrawOverlays(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true

    @SuppressLint("ClickableViewAccessibility")
    fun showSubtitleOverlay(cfg: LiveConfig) {
        if (!canDrawOverlays()) return
        mainHandler.post {
            if (subtitleRoot != null) {
                applyStyle(cfg)
                setClickThrough(cfg.overlayClickThrough)
                return@post
            }

            val dm = context.resources.displayMetrics
            val boxWidth = (dm.widthPixels * 0.90f).toInt()

            val bgDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 28f
                val alphaInt = (cfg.overlayBgAlpha.coerceIn(0.25f, 1.0f) * 255).toInt()
                setColor(Color.argb(alphaInt, 15, 23, 42))
                setStroke(2, Color.argb(140, 56, 189, 248))
            }

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = bgDrawable
                setPadding(32, 20, 32, 24)
            }

            val sourceView = TextView(context).apply {
                text = "🔴 V-Translate Live đang chạy..."
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, (cfg.overlayFontSizeSp - 6).coerceAtLeast(11).toFloat())
                maxLines = 2
            }

            val translatedView = TextView(context).apply {
                text = "Mở ứng dụng Douyin / Bilibili / YouTube để dịch trực tiếp"
                setTextColor(parseHexColorSafe(cfg.overlayTextColorHex, Color.parseColor("#FFE600")))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, cfg.overlayFontSizeSp.toFloat())
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 4
            }

            container.addView(sourceView)
            container.addView(translatedView)

            val flags = buildWindowFlags(cfg.overlayClickThrough)
            val params = WindowManager.LayoutParams(
                boxWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = (dm.heightPixels * 0.14f).toInt()
            }

            var initialX = 0
            var initialY = 0
            var touchX = 0f
            var touchY = 0f

            container.setOnTouchListener { _, event ->
                if (LiveSessionStateBus.clickThrough.value) return@setOnTouchListener false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - touchX).toInt()
                        params.y = initialY + (event.rawY - touchY).toInt()
                        runCatching { windowManager.updateViewLayout(container, params) }
                        true
                    }
                    else -> false
                }
            }

            runCatching {
                windowManager.addView(container, params)
                subtitleRoot = container
                tvSource = sourceView
                tvTranslated = translatedView
                subtitleParams = params
            }
        }
    }

    fun updateSubtitles(sourceText: String, translatedText: String) {
        mainHandler.post {
            tvSource?.text = sourceText
            tvTranslated?.text = translatedText
        }
    }

    fun applyStyle(cfg: LiveConfig) {
        mainHandler.post {
            tvSource?.setTextSize(TypedValue.COMPLEX_UNIT_SP, (cfg.overlayFontSizeSp - 6).coerceAtLeast(11).toFloat())
            tvTranslated?.setTextSize(TypedValue.COMPLEX_UNIT_SP, cfg.overlayFontSizeSp.toFloat())
            tvTranslated?.setTextColor(parseHexColorSafe(cfg.overlayTextColorHex, Color.parseColor("#FFE600")))
            (subtitleRoot?.background as? GradientDrawable)?.let { gd ->
                val alphaInt = (cfg.overlayBgAlpha.coerceIn(0.25f, 1.0f) * 255).toInt()
                gd.setColor(Color.argb(alphaInt, 15, 23, 42))
            }
        }
    }

    fun setClickThrough(enabled: Boolean) {
        LiveSessionStateBus.setClickThrough(enabled)
        mainHandler.post {
            val view = subtitleRoot ?: return@post
            val params = subtitleParams ?: return@post
            params.flags = buildWindowFlags(enabled)
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    fun showOrUpdateRegionGuide(region: OcrRegionPct, visible: Boolean) {
        if (!canDrawOverlays()) return
        LiveSessionStateBus.setShowRegionBox(visible)
        mainHandler.post {
            if (!visible) {
                regionGuideView?.let { v ->
                    runCatching { windowManager.removeView(v) }
                }
                regionGuideView = null
                regionGuideParams = null
                return@post
            }

            val dm = context.resources.displayMetrics
            val leftPx = (region.leftPct * dm.widthPixels).toInt()
            val topPx = (region.topPct * dm.heightPixels).toInt()
            val widthPx = ((region.rightPct - region.leftPct).coerceAtLeast(0.1f) * dm.widthPixels).toInt()
            val heightPx = ((region.bottomPct - region.topPct).coerceAtLeast(0.05f) * dm.heightPixels).toInt()

            if (regionGuideView == null) {
                val frame = View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(Color.argb(28, 34, 197, 94))
                        setStroke(5, Color.parseColor("#22C55E"), 14f, 8f)
                    }
                }
                val p = WindowManager.LayoutParams(
                    widthPx,
                    heightPx,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = leftPx
                    y = topPx
                }
                runCatching {
                    windowManager.addView(frame, p)
                    regionGuideView = frame
                    regionGuideParams = p
                }
            } else {
                val p = regionGuideParams ?: return@post
                p.x = leftPx
                p.y = topPx
                p.width = widthPx
                p.height = heightPx
                runCatching { windowManager.updateViewLayout(regionGuideView, p) }
            }
        }
    }

    fun hideAll() {
        mainHandler.post {
            subtitleRoot?.let { v -> runCatching { windowManager.removeView(v) } }
            subtitleRoot = null
            tvSource = null
            tvTranslated = null
            subtitleParams = null

            regionGuideView?.let { v -> runCatching { windowManager.removeView(v) } }
            regionGuideView = null
            regionGuideParams = null
        }
    }

    private fun buildWindowFlags(clickThrough: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (clickThrough) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return flags
    }

    private fun parseHexColorSafe(hex: String, fallback: Int): Int =
        try {
            Color.parseColor(hex)
        } catch (_: Exception) {
            fallback
        }
}
