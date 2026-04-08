package com.exam.cheater

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.exam.cheater.data.SettingsStore
import com.exam.cheater.model.AppSettings
import com.exam.cheater.service.CaptureOverlayService

class MainActivity : ComponentActivity() {
    private lateinit var captureLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val startIntent = Intent(this, CaptureOverlayService::class.java).apply {
                    action = CaptureOverlayService.ACTION_START
                    putExtra(CaptureOverlayService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(CaptureOverlayService.EXTRA_RESULT_DATA, data)
                }
                ContextCompat.startForegroundService(this, startIntent)
                toast("服务已启动")
            } else {
                toast("未授予录屏权限")
            }
        }

        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* no-op */ }

        requestNotificationIfNeeded()

        setContent {
            MaterialTheme {
                ConsoleScreen(
                    onRequestOverlayPermission = { openOverlayPermissionPage() },
                    onSaveSettings = { settings -> saveSettings(settings) },
                    onStartCapture = { settings -> startCapture(settings) },
                    onStopCapture = { stopCaptureService() }
                )
            }
        }
    }

    private fun requestNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openOverlayPermissionPage() {
        if (Settings.canDrawOverlays(this)) {
            toast("已拥有悬浮窗权限")
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun saveSettings(settings: AppSettings) {
        SettingsStore.save(this, settings)
        if (CaptureOverlayService.isRunning) {
            val refreshIntent = Intent(this, CaptureOverlayService::class.java).apply {
                action = CaptureOverlayService.ACTION_REFRESH_SETTINGS
            }
            startService(refreshIntent)
        }
        toast("配置已保存")
    }

    private fun startCapture(settings: AppSettings) {
        SettingsStore.save(this, settings)

        if (!Settings.canDrawOverlays(this)) {
            toast("请先授予悬浮窗权限")
            openOverlayPermissionPage()
            return
        }

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun stopCaptureService() {
        val stopIntent = Intent(this, CaptureOverlayService::class.java).apply {
            action = CaptureOverlayService.ACTION_STOP
        }
        startService(stopIntent)
        toast("服务已停止")
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun ConsoleScreen(
    onRequestOverlayPermission: () -> Unit,
    onSaveSettings: (AppSettings) -> Unit,
    onStartCapture: (AppSettings) -> Unit,
    onStopCapture: () -> Unit
) {
    val context = LocalContext.current
    val initial = remember { SettingsStore.load(context) }

    var intervalSeconds by rememberSaveable { mutableStateOf(initial.intervalSeconds.toString()) }
    var apiKey by rememberSaveable { mutableStateOf(initial.apiKey) }
    var baseUrl by rememberSaveable { mutableStateOf(initial.baseUrl) }
    var model by rememberSaveable { mutableStateOf(initial.model) }

    var overlayX by rememberSaveable { mutableStateOf(initial.overlayX.toString()) }
    var overlayY by rememberSaveable { mutableStateOf(initial.overlayY.toString()) }
    var overlayWidth by rememberSaveable { mutableStateOf(initial.overlayWidth.toString()) }
    var overlayHeight by rememberSaveable { mutableStateOf(initial.overlayHeight.toString()) }

    var fontSize by rememberSaveable { mutableStateOf(initial.fontSizeSp.toString()) }
    var textColorHex by rememberSaveable { mutableStateOf(initial.textColorHex) }
    var textAlpha by rememberSaveable { mutableStateOf(initial.textAlpha.toString()) }

    var borderEnabled by rememberSaveable { mutableStateOf(initial.borderEnabled) }
    var borderColorHex by rememberSaveable { mutableStateOf(initial.borderColorHex) }
    var borderWidthDp by rememberSaveable { mutableStateOf(initial.borderWidthDp.toString()) }
    var borderAlpha by rememberSaveable { mutableStateOf(initial.borderAlpha.toString()) }

    fun buildSettings(): AppSettings {
        return AppSettings(
            intervalSeconds = intervalSeconds.toIntOrNull()?.coerceIn(1, 120) ?: 5,
            apiKey = apiKey.trim(),
            baseUrl = baseUrl.trim(),
            model = model.trim(),
            overlayX = overlayX.toIntOrNull() ?: 0,
            overlayY = overlayY.toIntOrNull() ?: 0,
            overlayWidth = overlayWidth.toIntOrNull()?.coerceAtLeast(100) ?: 1080,
            overlayHeight = overlayHeight.toIntOrNull()?.coerceAtLeast(80) ?: 220,
            fontSizeSp = fontSize.toFloatOrNull()?.coerceIn(8f, 96f) ?: 20f,
            textColorHex = textColorHex.trim(),
            textAlpha = textAlpha.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f,
            borderEnabled = borderEnabled,
            borderColorHex = borderColorHex.trim(),
            borderWidthDp = borderWidthDp.toFloatOrNull()?.coerceIn(0f, 20f) ?: 2f,
            borderAlpha = borderAlpha.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.9f
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("控制台", style = MaterialTheme.typography.headlineSmall)
        Text("先保存配置，再点击“授权录屏并启动”。")

        Button(onClick = onRequestOverlayPermission, modifier = Modifier.fillMaxWidth()) {
            Text("申请悬浮窗权限")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { onSaveSettings(buildSettings()) },
                modifier = Modifier.weight(1f)
            ) { Text("保存配置") }
            Button(
                onClick = { onStartCapture(buildSettings()) },
                modifier = Modifier.weight(1f)
            ) { Text("授权录屏并启动") }
        }

        Button(onClick = onStopCapture, modifier = Modifier.fillMaxWidth()) {
            Text("停止服务")
        }

        Text("模型参数", style = MaterialTheme.typography.titleMedium)
        LabeledTextField("API Key", apiKey, { apiKey = it })
        LabeledTextField("Base URL", baseUrl, { baseUrl = it })
        LabeledTextField("Model", model, { model = it })
        LabeledTextField("截图间隔秒数(1-120)", intervalSeconds, { intervalSeconds = it }, numeric = true)

        Text("显示区域", style = MaterialTheme.typography.titleMedium)
        LabeledTextField("区域 X", overlayX, { overlayX = it }, numeric = true)
        LabeledTextField("区域 Y", overlayY, { overlayY = it }, numeric = true)
        LabeledTextField("区域宽度", overlayWidth, { overlayWidth = it }, numeric = true)
        LabeledTextField("区域高度", overlayHeight, { overlayHeight = it }, numeric = true)

        Text("字体样式", style = MaterialTheme.typography.titleMedium)
        LabeledTextField("字体大小 SP", fontSize, { fontSize = it }, decimal = true)
        LabeledTextField("字体颜色 HEX(如 #00FF00)", textColorHex, { textColorHex = it })
        LabeledTextField("字体透明度(0-1)", textAlpha, { textAlpha = it }, decimal = true)

        Text("边界框", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("启用边界框")
            Switch(checked = borderEnabled, onCheckedChange = { borderEnabled = it })
        }
        LabeledTextField("边界框颜色 HEX", borderColorHex, { borderColorHex = it })
        LabeledTextField("边框粗细 DP", borderWidthDp, { borderWidthDp = it }, decimal = true)
        LabeledTextField("边框透明度(0-1)", borderAlpha, { borderAlpha = it }, decimal = true)
    }
}

@Composable
private fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    numeric: Boolean = false,
    decimal: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = if (decimal) {
            KeyboardOptions(keyboardType = KeyboardType.Decimal)
        } else if (numeric) {
            KeyboardOptions(keyboardType = KeyboardType.Number)
        } else {
            KeyboardOptions.Default
        }
    )
}
