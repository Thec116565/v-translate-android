package com.vtranslate.live.service

import com.vtranslate.live.domain.LiveSubtitleCue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared reactive state bus between LiveTranslateForegroundService and MainActivity UI.
 */
object LiveSessionStateBus {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _clickThrough = MutableStateFlow(false)
    val clickThrough: StateFlow<Boolean> = _clickThrough.asStateFlow()

    private val _showRegionBox = MutableStateFlow(false)
    val showRegionBox: StateFlow<Boolean> = _showRegionBox.asStateFlow()

    private val _latestCue = MutableStateFlow<LiveSubtitleCue?>(null)
    val latestCue: StateFlow<LiveSubtitleCue?> = _latestCue.asStateFlow()

    private val _history = MutableStateFlow<List<LiveSubtitleCue>>(emptyList())
    val history: StateFlow<List<LiveSubtitleCue>> = _history.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(listOf("Sẵn sàng. Hãy nhập API Key và bấm Bắt đầu Dịch Trực Tiếp."))
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun setRunning(running: Boolean) {
        _isRunning.value = running
        if (!running) {
            _isPaused.value = false
        }
    }

    fun setPaused(paused: Boolean) {
        _isPaused.value = paused
    }

    fun setClickThrough(enabled: Boolean) {
        _clickThrough.value = enabled
    }

    fun setShowRegionBox(visible: Boolean) {
        _showRegionBox.value = visible
    }

    @Synchronized
    fun publishCue(source: String, translated: String, elapsedMs: Long) {
        val nextIdx = _history.value.size + 1
        val cue = LiveSubtitleCue(
            index = nextIdx,
            timestampMs = elapsedMs,
            sourceText = source,
            translatedText = translated,
        )
        _latestCue.value = cue
        _history.value = (_history.value + cue).takeLast(300)
    }

    @Synchronized
    fun clearHistory() {
        _history.value = emptyList()
        _latestCue.value = null
    }

    @Synchronized
    fun log(message: String) {
        _logs.value = (_logs.value + message).takeLast(120)
    }
}
