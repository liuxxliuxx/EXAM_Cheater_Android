package com.exam.cheater

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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
import androidx.compose.foundation.text.KeyboardOptions
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
                CaptureOverlayService.cacheProjectionPermission(result.resultCode, data)
                val startIntent = Intent(this, CaptureOverlayService::class.java).apply {
                    action = CaptureOverlayService.ACTION_START
                    putExtra(CaptureOverlayService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(CaptureOverlayService.EXTRA_RESULT_DATA, data)
                }
                try {
                    ContextCompat.startForegroundService(this, startIntent)
                    toast("Service started")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start capture service", e)
                    toast("Failed to start: ${e.javaClass.simpleName}")
                }
            } else {
                toast("Screen-capture permission denied")
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
            toast("Overlay permission already granted")
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
        toast("Settings saved")
    }

    private fun startCapture(settings: AppSettings) {
        SettingsStore.save(this, settings)

        if (!Settings.canDrawOverlays(this)) {
            toast("Please grant overlay permission first")
            openOverlayPermissionPage()
            return
        }

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager == null) {
            toast("MediaProjection unavailable")
            return
        }

        try {
            captureLauncher.launch(manager.createScreenCaptureIntent())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch screen-capture permission", e)
            toast("Screen-capture request failed")
        }
    }

    private fun stopCaptureService() {
        val stopIntent = Intent(this, CaptureOverlayService::class.java).apply {
            action = CaptureOverlayService.ACTION_STOP
        }
        startService(stopIntent)
        toast("Service stopped")
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "MainActivity"
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
        Text("Control Panel", style = MaterialTheme.typography.headlineSmall)
        Text("Save settings first, then tap \"Grant Screen Capture & Start\".")

        Button(onClick = onRequestOverlayPermission, modifier = Modifier.fillMaxWidth()) {
            Text("Request Overlay Permission")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { onSaveSettings(buildSettings()) },
                modifier = Modifier.weight(1f)
            ) { Text("Save Settings") }
            Button(
                onClick = { onStartCapture(buildSettings()) },
                modifier = Modifier.weight(1f)
            ) { Text("Grant Screen Capture & Start") }
        }

        Button(onClick = onStopCapture, modifier = Modifier.fillMaxWidth()) {
            Text("Stop Service")
        }

        Text("Model Settings", style = MaterialTheme.typography.titleMedium)
        LabeledTextField("API Key", apiKey, { apiKey = it })
        LabeledTextField("Base URL", baseUrl, { baseUrl = it })
        LabeledTextField("Model", model, { model = it })
        LabeledTextField("Capture Interval Seconds (1-120)", intervalSeconds, { intervalSeconds = it }, numeric = true)

        Text("Overlay Area", style = MaterialTheme.typography.titleMedium)
        LabeledTextField("Area X", overlayX, { overlayX = it }, numeric = true)
        LabeledTextField("Area Y", overlayY, { overlayY = it }, numeric = true)
        LabeledTextField("Area Width", overlayWidth, { overlayWidth = it }, numeric = true)
        LabeledTextField("Area Height", overlayHeight, { overlayHeight = it }, numeric = true)

        Text("Text Style", style = MaterialTheme.typography.titleMedium)
        LabeledTextField("Font Size (SP)", fontSize, { fontSize = it }, decimal = true)
        LabeledTextField("Text Color HEX (e.g. #00FF00)", textColorHex, { textColorHex = it })
        LabeledTextField("Text Alpha (0-1)", textAlpha, { textAlpha = it }, decimal = true)

        Text("Border", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Enable Border")
            Switch(checked = borderEnabled, onCheckedChange = { borderEnabled = it })
        }
        LabeledTextField("Border Color HEX", borderColorHex, { borderColorHex = it })
        LabeledTextField("Border Width DP", borderWidthDp, { borderWidthDp = it }, decimal = true)
        LabeledTextField("Border Alpha (0-1)", borderAlpha, { borderAlpha = it }, decimal = true)
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
