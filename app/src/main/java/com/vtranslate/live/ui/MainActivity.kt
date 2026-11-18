package com.vtranslate.live.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vtranslate.live.R
import com.vtranslate.live.data.GeminiPoolClient
import com.vtranslate.live.data.SettingsRepository
import com.vtranslate.live.domain.CaptureMode
import com.vtranslate.live.domain.LiveConfig
import com.vtranslate.live.domain.LiveSubtitleCue
import com.vtranslate.live.service.FloatingOverlayController
import com.vtranslate.live.service.LiveSessionStateBus
import com.vtranslate.live.service.LiveTranslateForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var overlayPreviewController: FloatingOverlayController
    private val geminiTester = GeminiPoolClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepo = SettingsRepository(this)
        overlayPreviewController = FloatingOverlayController(this)

        setContent {
            val darkScheme = darkColorScheme(
                primary = Color(0xFF38BDF8),
                secondary = Color(0xFF4ADE80),
                background = Color(0xFF0F172A),
                surface = Color(0xFF1E293B),
                onPrimary = Color.Black,
                onBackground = Color(0xFFF8FAFC),
                onSurface = Color(0xFFF8FAFC)
            )
            MaterialTheme(colorScheme = darkScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    VTranslateMainScreen(
                        settingsRepo = settingsRepo,
                        overlayPreviewController = overlayPreviewController,
                        geminiTester = geminiTester
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        overlayPreviewController.showOrUpdateRegionGuide(settingsRepo.loadConfig().ocrRegion, false)
        super.onDestroy()
    }
}

@Composable
fun VTranslateMainScreen(
    settingsRepo: SettingsRepository,
    overlayPreviewController: FloatingOverlayController,
    geminiTester: GeminiPoolClient,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(settingsRepo.loadConfig()) }
    var selectedTab by remember { mutableIntStateOf(0) }

    val isRunning by LiveSessionStateBus.isRunning.collectAsState()
    val isPaused by LiveSessionStateBus.isPaused.collectAsState()
    val clickThrough by LiveSessionStateBus.clickThrough.collectAsState()
    val showRegionBox by LiveSessionStateBus.showRegionBox.collectAsState()
    val history by LiveSessionStateBus.history.collectAsState()
    val logs by LiveSessionStateBus.logs.collectAsState()

    fun updateAndSave(newCfg: LiveConfig) {
        config = newCfg
        settingsRepo.saveConfig(newCfg)
        if (isRunning) {
            val intent = Intent(context, LiveTranslateForegroundService::class.java).apply {
                action = LiveTranslateForegroundService.ACTION_REFRESH_STYLE
            }
            context.startService(intent)
        }
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            val srvIntent = Intent(context, LiveTranslateForegroundService::class.java).apply {
                action = LiveTranslateForegroundService.ACTION_START
                putExtra(LiveTranslateForegroundService.EXTRA_RESULT_CODE, res.resultCode)
                putExtra(LiveTranslateForegroundService.EXTRA_RESULT_DATA, res.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(srvIntent)
            } else {
                context.startService(srvIntent)
            }
        } else {
            Toast.makeText(context, "Đã hủy cấp quyền ghi màn hình", Toast.LENGTH_SHORT).show()
        }
    }

    val audioPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        requestMediaProjection(context, mediaProjectionLauncher::launch)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.app_bg),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x450B1120))
        )

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xBF0F172A))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                Text(
                    text = "V-Translate Live Android",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF38BDF8)
                )
                Text(
                    text = "Dịch Phụ Đề Nổi & Lồng Tiếng Trực Tiếp (Douyin / Bilibili / YouTube)",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8)
                )
                Spacer(modifier = Modifier.height(8.dp))
                val tabs = listOf("🔴 Dịch Live", "🔑 API Pool", "🎛 Phụ Đề & Giọng", "📜 Lịch Sử")
                TabRow(selectedTabIndex = selectedTab, containerColor = Color(0xFF1E293B)) {
                    tabs.forEachIndexed { idx, title ->
                        Tab(
                            selected = selectedTab == idx,
                            onClick = { selectedTab = idx },
                            text = { Text(title, fontSize = 12.sp) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(12.dp)
        ) {
            when (selectedTab) {
                0 -> LiveControlTab(
                    context = context,
                    config = config,
                    isRunning = isRunning,
                    isPaused = isPaused,
                    clickThrough = clickThrough,
                    showRegionBox = showRegionBox,
                    logs = logs,
                    onUpdateConfig = ::updateAndSave,
                    onToggleRegionGuide = { visible ->
                        overlayPreviewController.showOrUpdateRegionGuide(config.ocrRegion, visible)
                    },
                    onStartRequested = {
                        if (config.apiKeys.isEmpty()) {
                            Toast.makeText(context, "Hãy nhập ít nhất 1 Google AI Studio API Key ở tab '🔑 API Pool'!", Toast.LENGTH_LONG).show()
                            selectedTab = 1
                        } else if (!Settings.canDrawOverlays(context)) {
                            Toast.makeText(context, "Vui lòng cấp quyền 'Hiển thị trên ứng dụng khác'", Toast.LENGTH_LONG).show()
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        } else {
                            val perms = mutableListOf<String>()
                            if (config.captureMode == CaptureMode.INTERNAL_AUDIO &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                            ) {
                                perms.add(Manifest.permission.RECORD_AUDIO)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) {
                                perms.add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            if (perms.isNotEmpty()) {
                                audioPermLauncher.launch(perms.toTypedArray())
                            } else {
                                requestMediaProjection(context, mediaProjectionLauncher::launch)
                            }
                        }
                    }
                )
                1 -> ApiPoolTab(
                    config = config,
                    onUpdateConfig = ::updateAndSave,
                    onTestKey = { key, model, onResult ->
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                geminiTester.testConnection(key, model)
                            }
                            onResult(res.fold(onSuccess = { "✅ $it" }, onFailure = { "❌ ${it.message}" }))
                        }
                    },
                    onFetchModels = { key, onResult ->
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                geminiTester.fetchAvailableModels(key)
                            }
                            onResult(res)
                        }
                    }
                )
                2 -> StyleAndVoiceTab(
                    config = config,
                    onUpdateConfig = ::updateAndSave
                )
                3 -> HistoryTranscriptTab(
                    context = context,
                    history = history,
                    onClear = { LiveSessionStateBus.clearHistory() }
                )
            }
        }
        }
    }
}

private fun requestMediaProjection(context: Context, launchIntent: (Intent) -> Unit) {
    val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    launchIntent(mpm.createScreenCaptureIntent())
}

@Composable
private fun LiveControlTab(
    context: Context,
    config: LiveConfig,
    isRunning: Boolean,
    isPaused: Boolean,
    clickThrough: Boolean,
    showRegionBox: Boolean,
    logs: List<String>,
    onUpdateConfig: (LiveConfig) -> Unit,
    onToggleRegionGuide: (Boolean) -> Unit,
    onStartRequested: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Primary Start / Pause / Stop Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (!isRunning) {
                        Button(
                            onClick = onStartRequested,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("▶ BẮT ĐẦU DỊCH TRỰC TIẾP", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = {
                                context.startService(
                                    Intent(context, LiveTranslateForegroundService::class.java).apply {
                                        action = LiveTranslateForegroundService.ACTION_TOGGLE_PAUSE
                                    }
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isPaused) "▶ Tiếp tục" else "⏸ Tạm dừng", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = {
                                context.startService(
                                    Intent(context, LiveTranslateForegroundService::class.java).apply {
                                        action = LiveTranslateForegroundService.ACTION_STOP
                                    }
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF991B1B)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("⏹ Dừng dịch", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(
                        checked = clickThrough,
                        onCheckedChange = { enabled ->
                            onUpdateConfig(config.copy(overlayClickThrough = enabled))
                            if (isRunning) {
                                context.startService(
                                    Intent(context, LiveTranslateForegroundService::class.java).apply {
                                        action = LiveTranslateForegroundService.ACTION_TOGGLE_CLICK_THROUGH
                                    }
                                )
                            } else {
                                LiveSessionStateBus.setClickThrough(enabled)
                            }
                        }
                    )
                    Text(
                        text = "🔒 Khóa xuyên chạm (Vuốt lướt Douyin xuyên qua khung phụ đề nổi)",
                        fontSize = 13.sp
                    )
                }
            }
        }

        // Mode & Language Selection Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Chế độ bắt & Ngôn ngữ", fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CaptureMode.entries.forEach { mode ->
                        val selected = config.captureMode == mode
                        Button(
                            onClick = { onUpdateConfig(config.copy(captureMode = mode)) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) Color(0xFF0284C7) else Color(0xFF334155)
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                if (mode == CaptureMode.SCREEN_OCR) "📷 Quét Màn Hình (Douyin OCR)"
                                else "🔊 Thu Âm Nội Bộ",
                                fontSize = 12.sp,
                                color = Color.White
                            )
                        }
                    }
                }

                val srcLangs = listOf("Tự động nhận diện (Auto)", "Tiếng Trung (Douyin/Bilibili)", "Tiếng Anh", "Tiếng Nhật", "Tiếng Hàn")
                Text("Ngôn ngữ gốc: ${config.sourceLanguage}", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    srcLangs.forEach { lang ->
                        val shortName = lang.substringBefore(" (")
                        OutlinedButton(
                            onClick = { onUpdateConfig(config.copy(sourceLanguage = lang)) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(shortName, fontSize = 10.sp)
                        }
                    }
                }

                val dstLangs = listOf("Tiếng Việt", "English", "中文", "日本語", "한국어")
                Text("Dịch sang: ${config.targetLanguage}", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    dstLangs.forEach { lang ->
                        OutlinedButton(
                            onClick = { onUpdateConfig(config.copy(targetLanguage = lang)) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(lang, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // OCR Region Adjustment Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("2. Vùng Quét Phụ Đề Màn Hình (OCR)", fontWeight = FontWeight.Bold, color = Color(0xFF4ADE80))
                    Switch(checked = showRegionBox, onCheckedChange = onToggleRegionGuide)
                }
                Text(
                    "Bật công tắc bên phải để hiện khung viền xanh lá trên màn hình và căn chỉnh vừa khít dòng phụ đề Douyin:",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8)
                )

                val r = config.ocrRegion
                Text("Vị trí Trên (Top): ${(r.topPct * 100).toInt()}%  |  Dưới (Bottom): ${(r.bottomPct * 100).toInt()}%", fontSize = 12.sp)
                Slider(
                    value = r.topPct,
                    onValueChange = { v ->
                        val next = r.copy(topPct = v.coerceAtMost(r.bottomPct - 0.06f))
                        onUpdateConfig(config.copy(ocrRegion = next))
                        if (showRegionBox) onToggleRegionGuide(true)
                    },
                    valueRange = 0.10f..0.90f
                )
                Slider(
                    value = r.bottomPct,
                    onValueChange = { v ->
                        val next = r.copy(bottomPct = v.coerceAtLeast(r.topPct + 0.06f))
                        onUpdateConfig(config.copy(ocrRegion = next))
                        if (showRegionBox) onToggleRegionGuide(true)
                    },
                    valueRange = 0.20f..0.98f
                )
            }
        }

        // Logs Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Nhật ký hoạt động:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(4.dp))
                logs.takeLast(6).forEach { ln ->
                    Text(ln, fontSize = 11.sp, color = Color(0xFFCBD5E1))
                }
            }
        }
    }
}

@Composable
private fun ApiPoolTab(
    config: LiveConfig,
    onUpdateConfig: (LiveConfig) -> Unit,
    onTestKey: (String, String, (String) -> Unit) -> Unit,
    onFetchModels: (String, (Result<List<String>>) -> Unit) -> Unit,
) {
    var keysText by remember(config.apiKeys) { mutableStateOf(config.apiKeys.joinToString("\n")) }
    var testResult by remember { mutableStateOf("Chưa kiểm tra.") }
    var modelListText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🔑 Danh Sách Google AI Studio API Keys", fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                Text(
                    "Nhập mỗi dòng 1 API Key (AIza...). Ứng dụng sẽ tự động xoay vòng đổi Key và đổi model Flash khi gặp giới hạn 429:",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8)
                )
                OutlinedTextField(
                    value = keysText,
                    onValueChange = { keysText = it },
                    placeholder = { Text("AIzaSy...\nAIzaSy...") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val parsed = keysText.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }
                            onUpdateConfig(config.copy(apiKeys = parsed))
                            testResult = "Đã lưu ${parsed.size} API Key."
                        }
                    ) {
                        Text("💾 Lưu Danh Sách Key")
                    }
                    OutlinedButton(
                        onClick = {
                            val firstKey = keysText.split(",", "\n").map { it.trim() }.firstOrNull { it.isNotEmpty() }
                            if (firstKey == null) {
                                testResult = "❌ Hãy nhập ít nhất 1 API Key trước."
                            } else {
                                testResult = "⏳ Đang kiểm tra kết nối..."
                                onTestKey(firstKey, config.preferredModel) { msg ->
                                    testResult = msg
                                }
                            }
                        }
                    ) {
                        Text("⚡ Test Key Đầu Tiên")
                    }
                }
                Text(testResult, fontSize = 12.sp, color = Color(0xFF4ADE80))
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🤖 Mô Hình Gemini Flash Ưu Tiên", fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                val models = (config.flashModelPool + listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-3.5-flash-lite", "gemini-2.5-pro")).distinct()
                models.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = config.preferredModel == m,
                            onCheckedChange = { if (it) onUpdateConfig(config.copy(preferredModel = m)) }
                        )
                        Text(m, fontSize = 13.sp)
                    }
                }
                OutlinedButton(
                    onClick = {
                        val firstKey = keysText.split(",", "\n").map { it.trim() }.firstOrNull { it.isNotEmpty() }
                        if (firstKey == null) {
                            modelListText = "❌ Hãy nhập ít nhất 1 API Key ở trên trước."
                        } else {
                            modelListText = "⏳ Đang truy vấn Google AI Studio..."
                            onFetchModels(firstKey) { res ->
                                res.onSuccess { list ->
                                    val flashList = list.filter { it.contains("flash", ignoreCase = true) }
                                        .ifEmpty { list.take(6) }
                                    onUpdateConfig(
                                        config.copy(
                                            flashModelPool = flashList,
                                            preferredModel = flashList.firstOrNull() ?: config.preferredModel
                                        )
                                    )
                                    modelListText = "✅ Tìm thấy ${list.size} model hoạt động (${flashList.joinToString(", ")})"
                                }.onFailure { err ->
                                    modelListText = "❌ Lỗi lấy DS model: ${err.message}"
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🔄 Lấy & Xác nhận DS Model còn hoạt động")
                }
                if (modelListText.isNotBlank()) {
                    Text(modelListText, fontSize = 12.sp, color = Color(0xFF4ADE80))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = config.autoRotateFlashModels,
                        onCheckedChange = { onUpdateConfig(config.copy(autoRotateFlashModels = it)) }
                    )
                    Text("Tự động xoay vòng qua các model Flash dự phòng khi gặp lỗi 429", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun StyleAndVoiceTab(
    config: LiveConfig,
    onUpdateConfig: (LiveConfig) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🔊 Lồng Tiếng TTS & Tự Động Nhỏ Tiếng Douyin", fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = config.enableTtsDubbing,
                        onCheckedChange = { onUpdateConfig(config.copy(enableTtsDubbing = it)) }
                    )
                    Text("Bật đọc lồng tiếng tự động ngay khi có câu dịch mới", fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = config.enableAudioDucking,
                        onCheckedChange = { onUpdateConfig(config.copy(enableAudioDucking = it)) }
                    )
                    Text("Tự động hạ nhỏ tiếng ứng dụng video gốc (Audio Ducking) khi đang đọc", fontSize = 13.sp)
                }
                Text("Tốc độ đọc cơ sở: ${String.format("%.2fx", config.ttsBaseSpeed)}", fontSize = 13.sp)
                Slider(
                    value = config.ttsBaseSpeed,
                    onValueChange = { onUpdateConfig(config.copy(ttsBaseSpeed = it)) },
                    valueRange = 0.85f..1.50f
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🪟 Giao Diện Khung Phụ Đề Nổi", fontWeight = FontWeight.Bold, color = Color(0xFF4ADE80))
                Text("Cỡ chữ phụ đề nổi: ${config.overlayFontSizeSp} sp", fontSize = 13.sp)
                Slider(
                    value = config.overlayFontSizeSp.toFloat(),
                    onValueChange = { onUpdateConfig(config.copy(overlayFontSizeSp = it.toInt())) },
                    valueRange = 14f..32f
                )
                Text("Độ mờ nền khung phụ đề: ${(config.overlayBgAlpha * 100).toInt()}%", fontSize = 13.sp)
                Slider(
                    value = config.overlayBgAlpha,
                    onValueChange = { onUpdateConfig(config.copy(overlayBgAlpha = it)) },
                    valueRange = 0.30f..0.95f
                )
                val colors = listOf(
                    "#FFE600" to "Vàng",
                    "#FFFFFF" to "Trắng",
                    "#4ADE80" to "Xanh lá",
                    "#38BDF8" to "Xanh dương"
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    colors.forEach { (hex, label) ->
                        OutlinedButton(
                            onClick = { onUpdateConfig(config.copy(overlayTextColorHex = hex)) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(label, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryTranscriptTab(
    context: Context,
    history: List<LiveSubtitleCue>,
    onClear: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    if (history.isEmpty()) return@Button
                    val srtText = history.joinToString("\n") { it.toSrtBlock() }
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "live_session_vi.srt")
                        putExtra(Intent.EXTRA_TEXT, srtText)
                    }
                    context.startActivity(Intent.createChooser(sendIntent, "Xuất / Chia sẻ phụ đề .SRT"))
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("📤 Xuất / Chia sẻ .SRT (${history.size})")
            }
            OutlinedButton(onClick = onClear) {
                Text("🗑 Xóa")
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(history.reversed()) { cue ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "#${cue.index} [${LiveSubtitleCue.formatSrtTime(cue.timestampMs).take(8)}]  ${cue.translatedText}",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFFE600),
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Gốc: ${cue.sourceText}",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}
