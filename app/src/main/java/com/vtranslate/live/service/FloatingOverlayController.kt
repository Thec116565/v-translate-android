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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.vtranslate.live.domain.CaptureMode
import com.vtranslate.live.domain.LiveConfig
import com.vtranslate.live.domain.OcrRegionPct

/**
 * Manages 3 floating overlays (SYSTEM_ALERT_WINDOW) on top of Douyin/TikTok/YouTube:
 * 1) Subtitle Banner HUD (with Click-Through FLAG_NOT_TOUCHABLE option)
 * 2) Vertical Floating Quick Toolbar (Start/Pause, Stop, Switch Model, Switch OCR/Audio, Direct Region Edit, Lock)
 * 3) Interactive Direct OCR Region Box (Drag body to move + drag bottom-right handle to resize right over Douyin)
 */
class FloatingOverlayController(private val context: Context) {

    interface ToolbarCallbacks {
        fun onTogglePause()
        fun onStop()
        fun onCycleModel()
        fun onSwitchMode()
        fun onToggleClickThrough()
        fun onRegionEdited(newRegion: OcrRegionPct)
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // 1. Subtitle Banner
    private var subtitleRoot: LinearLayout? = null
    private var tvSource: TextView? = null
    private var tvTranslated: TextView? = null
    private var subtitleParams: WindowManager.LayoutParams? = null

    // 2. Vertical Floating Toolbar
    private var toolbarRoot: LinearLayout? = null
    private var toolbarButtonsBox: LinearLayout? = null
    private var toolbarParams: WindowManager.LayoutParams? = null
    private var btnPause: TextView? = null
    private var btnModel: TextView? = null
    private var btnMode: TextView? = null
    private var btnRegion: TextView? = null
    private var btnLock: TextView? = null
    private var isToolbarCollapsed = false

    // 3. Interactive OCR Region Editor / Guide
    private var regionRoot: FrameLayout? = null
    private var regionParams: WindowManager.LayoutParams? = null
    private var isRegionInteractive = false
    private var currentRegionPct = OcrRegionPct()

    fun canDrawOverlays(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true

    @SuppressLint("ClickableViewAccessibility")
    fun showSubtitleOverlay(cfg: LiveConfig) {
        if (!canDrawOverlays()) return
        currentRegionPct = cfg.ocrRegion
        mainHandler.post {
            if (subtitleRoot != null) {
                applyStyle(cfg)
                setClickThrough(cfg.overlayClickThrough)
                return@post
            }

            val dm = context.resources.displayMetrics
            val boxWidth = (dm.widthPixels * 0.86f).toInt()

            val bgDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 26f
                val alphaInt = (cfg.overlayBgAlpha.coerceIn(0.25f, 1.0f) * 255).toInt()
                setColor(Color.argb(alphaInt, 15, 23, 42))
                setStroke(2, Color.argb(140, 56, 189, 248))
            }

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = bgDrawable
                setPadding(28, 16, 28, 20)
            }

            val sourceView = TextView(context).apply {
                text = "🔴 V-Translate Live (${shortModelLabel(cfg.preferredModel)})"
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, (cfg.overlayFontSizeSp - 6).coerceAtLeast(11).toFloat())
                maxLines = 2
            }

            val translatedView = TextView(context).apply {
                text = "Đang chờ phụ đề / giọng nói từ video..."
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
                y = (dm.heightPixels * 0.12f).toInt()
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

    @SuppressLint("ClickableViewAccessibility")
    fun showVerticalToolbar(cfg: LiveConfig, callbacks: ToolbarCallbacks) {
        if (!canDrawOverlays()) return
        mainHandler.post {
            if (toolbarRoot != null) {
                updateToolbarState(
                    paused = LiveSessionStateBus.isPaused.value,
                    model = cfg.preferredModel,
                    mode = cfg.captureMode,
                    clickThrough = LiveSessionStateBus.clickThrough.value,
                )
                return@post
            }

            val dm = context.resources.displayMetrics
            val rootBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 40f
                setColor(Color.argb(220, 15, 23, 42))
                setStroke(2, Color.argb(180, 56, 189, 248))
            }

            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = rootBg
                setPadding(10, 12, 10, 12)
            }

            // Collapse / Drag Handle
            val btnHandle = makeToolButton("⚡", "#38BDF8")
            val buttonsBox = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }

            val pauseBtn = makeToolButton("⏸", "#FBBF24") { callbacks.onTogglePause() }
            val stopBtn = makeToolButton("⏹", "#F87171") { callbacks.onStop() }
            val modeBtn = makeToolButton(if (cfg.captureMode == CaptureMode.SCREEN_OCR) "📷" else "🔊", "#4ADE80") {
                callbacks.onSwitchMode()
            }
            val modelBtn = makeToolButton(shortModelLabel(cfg.preferredModel), "#C084FC", textSp = 10f) {
                callbacks.onCycleModel()
            }
            val regionBtn = makeToolButton("🎯", "#22C55E") {
                toggleInteractiveRegionEditor(currentRegionPct, callbacks)
            }
            val lockBtn = makeToolButton(if (cfg.overlayClickThrough) "🔒" else "🔓", "#E2E8F0") {
                callbacks.onToggleClickThrough()
            }

            buttonsBox.addView(pauseBtn)
            buttonsBox.addView(modeBtn)
            buttonsBox.addView(modelBtn)
            buttonsBox.addView(regionBtn)
            buttonsBox.addView(lockBtn)
            buttonsBox.addView(stopBtn)

            root.addView(btnHandle)
            root.addView(buttonsBox)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 12
                y = (dm.heightPixels * 0.28f).toInt()
            }

            // Drag handle: tap to collapse/expand, drag to reposition vertical toolbar anywhere
            var startX = 0
            var startY = 0
            var downRawX = 0f
            var downRawY = 0f
            var moved = false
            btnHandle.setOnTouchListener { _, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        downRawX = ev.rawX
                        downRawY = ev.rawY
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (downRawX - ev.rawX).toInt()
                        val dy = (ev.rawY - downRawY).toInt()
                        if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                        params.x = (startX + dx).coerceAtLeast(0)
                        params.y = (startY + dy).coerceIn(40, dm.heightPixels - 120)
                        runCatching { windowManager.updateViewLayout(root, params) }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            isToolbarCollapsed = !isToolbarCollapsed
                            buttonsBox.visibility = if (isToolbarCollapsed) View.GONE else View.VISIBLE
                            btnHandle.text = if (isToolbarCollapsed) "⚡" else "✕"
                        }
                        true
                    }
                    else -> false
                }
            }

            runCatching {
                windowManager.addView(root, params)
                toolbarRoot = root
                toolbarButtonsBox = buttonsBox
                toolbarParams = params
                btnPause = pauseBtn
                btnMode = modeBtn
                btnModel = modelBtn
                btnRegion = regionBtn
                btnLock = lockBtn
            }
        }
    }

    fun updateToolbarState(paused: Boolean, model: String, mode: CaptureMode, clickThrough: Boolean) {
        mainHandler.post {
            btnPause?.text = if (paused) "▶" else "⏸"
            btnMode?.text = if (mode == CaptureMode.SCREEN_OCR) "📷" else "🔊"
            btnModel?.text = shortModelLabel(model)
            btnLock?.text = if (clickThrough) "🔒" else "🔓"
        }
    }

    private fun makeToolButton(
        label: String,
        hexColor: String,
        textSp: Float = 16f,
        onClick: (() -> Unit)? = null,
    ): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(Color.parseColor(hexColor))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(14, 14, 14, 14)
            minWidth = 96
            minHeight = 88
            if (onClick != null) {
                setOnClickListener { onClick() }
            }
        }
    }

    private fun shortModelLabel(model: String): String = when {
        model.contains("3.8") -> "3.8F"
        model.contains("3.5") -> "3.5L"
        model.contains("3.1") -> "3.1P"
        model.contains("2.5") -> "2.5F"
        else -> model.take(4).uppercase()
    }

    fun updateSubtitles(sourceText: String, translatedText: String) {
        mainHandler.post {
            tvSource?.text = sourceText
            tvTranslated?.text = translatedText
        }
    }

    fun showToastBanner(message: String) {
        mainHandler.post {
            tvSource?.text = message
        }
    }

    fun applyStyle(cfg: LiveConfig) {
        currentRegionPct = cfg.ocrRegion
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
            btnLock?.text = if (enabled) "🔒" else "🔓"
            val view = subtitleRoot ?: return@post
            val params = subtitleParams ?: return@post
            params.flags = buildWindowFlags(enabled)
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun toggleInteractiveRegionEditor(region: OcrRegionPct, callbacks: ToolbarCallbacks) {
        if (isRegionInteractive) {
            isRegionInteractive = false
            showOrUpdateRegionGuide(currentRegionPct, visible = false, interactive = false, callbacks = null)
            callbacks.onRegionEdited(currentRegionPct)
            showToastBanner("✅ Đã lưu vùng quét phụ đề mới!")
        } else {
            isRegionInteractive = true
            showOrUpdateRegionGuide(region, visible = true, interactive = true, callbacks = callbacks)
            showToastBanner("🎯 Kéo khung xanh để di chuyển, kéo góc ↘ để chỉnh kích thước, bấm 🎯 lần nữa để lưu")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showOrUpdateRegionGuide(
        region: OcrRegionPct,
        visible: Boolean,
        interactive: Boolean = false,
        callbacks: ToolbarCallbacks? = null,
    ) {
        if (!canDrawOverlays()) return
        currentRegionPct = region
        isRegionInteractive = visible && interactive
        LiveSessionStateBus.setShowRegionBox(visible)

        mainHandler.post {
            if (!visible) {
                regionRoot?.let { v -> runCatching { windowManager.removeView(v) } }
                regionRoot = null
                regionParams = null
                btnRegion?.setTextColor(Color.parseColor("#22C55E"))
                return@post
            }

            if (interactive) {
                btnRegion?.setTextColor(Color.parseColor("#FACC15"))
            }

            val dm = context.resources.displayMetrics
            val leftPx = (region.leftPct * dm.widthPixels).toInt()
            val topPx = (region.topPct * dm.heightPixels).toInt()
            val widthPx = ((region.rightPct - region.leftPct).coerceAtLeast(0.12f) * dm.widthPixels).toInt()
            val heightPx = ((region.bottomPct - region.topPct).coerceAtLeast(0.06f) * dm.heightPixels).toInt()

            // Remove & rebuild cleanly when switching interactive mode
            regionRoot?.let { old -> runCatching { windowManager.removeView(old) } }

            val box = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(if (interactive) Color.argb(48, 34, 197, 94) else Color.argb(24, 34, 197, 94))
                    setStroke(6, if (interactive) Color.parseColor("#FACC15") else Color.parseColor("#22C55E"), 14f, 8f)
                }
            }

            val flags = if (interactive) {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }

            val p = WindowManager.LayoutParams(
                widthPx,
                heightPx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = leftPx
                y = topPx
            }

            if (interactive) {
                val hint = TextView(context).apply {
                    text = "Kéo di chuyển · Góc ↘ đổi cỡ · Bấm 🎯 để lưu"
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.argb(170, 0, 0, 0))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setPadding(12, 4, 12, 4)
                }
                box.addView(
                    hint,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.START
                    )
                )

                val resizeHandle = TextView(context).apply {
                    text = "↘"
                    setTextColor(Color.BLACK)
                    setBackgroundColor(Color.parseColor("#FACC15"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                }
                box.addView(
                    resizeHandle,
                    FrameLayout.LayoutParams(72, 72, Gravity.BOTTOM or Gravity.END)
                )

                var moveInitX = 0
                var moveInitY = 0
                var touchDownX = 0f
                var touchDownY = 0f

                box.setOnTouchListener { _, ev ->
                    when (ev.action) {
                        MotionEvent.ACTION_DOWN -> {
                            moveInitX = p.x
                            moveInitY = p.y
                            touchDownX = ev.rawX
                            touchDownY = ev.rawY
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            p.x = (moveInitX + (ev.rawX - touchDownX).toInt()).coerceIn(0, dm.widthPixels - p.width)
                            p.y = (moveInitY + (ev.rawY - touchDownY).toInt()).coerceIn(0, dm.heightPixels - p.height)
                            runCatching { windowManager.updateViewLayout(box, p) }
                            updateRegionPctFromParams(p, dm.widthPixels, dm.heightPixels, callbacks)
                            true
                        }
                        else -> false
                    }
                }

                var resInitW = 0
                var resInitH = 0
                var resDownX = 0f
                var resDownY = 0f
                resizeHandle.setOnTouchListener { _, ev ->
                    when (ev.action) {
                        MotionEvent.ACTION_DOWN -> {
                            resInitW = p.width
                            resInitH = p.height
                            resDownX = ev.rawX
                            resDownY = ev.rawY
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            p.width = (resInitW + (ev.rawX - resDownX).toInt()).coerceIn(180, dm.widthPixels - p.x)
                            p.height = (resInitH + (ev.rawY - resDownY).toInt()).coerceIn(90, dm.heightPixels - p.y)
                            runCatching { windowManager.updateViewLayout(box, p) }
                            updateRegionPctFromParams(p, dm.widthPixels, dm.heightPixels, callbacks)
                            true
                        }
                        else -> false
                    }
                }
            }

            runCatching {
                windowManager.addView(box, p)
                regionRoot = box
                regionParams = p
            }
        }
    }

    private fun updateRegionPctFromParams(
        p: WindowManager.LayoutParams,
        screenW: Int,
        screenH: Int,
        callbacks: ToolbarCallbacks?,
    ) {
        val w = screenW.coerceAtLeast(1).toFloat()
        val h = screenH.coerceAtLeast(1).toFloat()
        currentRegionPct = OcrRegionPct(
            leftPct = (p.x / w).coerceIn(0f, 0.90f),
            topPct = (p.y / h).coerceIn(0f, 0.90f),
            rightPct = ((p.x + p.width) / w).coerceIn(0.10f, 1.0f),
            bottomPct = ((p.y + p.height) / h).coerceIn(0.06f, 1.0f),
        )
        callbacks?.onRegionEdited(currentRegionPct)
    }

    fun hideAll() {
        mainHandler.post {
            subtitleRoot?.let { v -> runCatching { windowManager.removeView(v) } }
            subtitleRoot = null
            tvSource = null
            tvTranslated = null
            subtitleParams = null

            toolbarRoot?.let { v -> runCatching { windowManager.removeView(v) } }
            toolbarRoot = null
            toolbarButtonsBox = null
            toolbarParams = null

            regionRoot?.let { v -> runCatching { windowManager.removeView(v) } }
            regionRoot = null
            regionParams = null
            isRegionInteractive = false
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
