package com.goresense.gorebox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.goresense.gorebox.data.GoreBoxViewModel
import com.goresense.gorebox.service.ConnectionStateStore
import com.goresense.gorebox.service.GoreBoxVpnService
import com.goresense.gorebox.service.NativeCoreStatus
import com.goresense.gorebox.ui.GoreBoxRoot
import com.goresense.gorebox.ui.GoreBoxTheme

class MainActivity : ComponentActivity() {
    private val viewModel: GoreBoxViewModel by viewModels()
    private var stateReceiverRegistered = false
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            viewModel.refreshConnectionState()
        }
    }

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) startVpnConnection()
        else viewModel.setConnectionState(
            com.goresense.gorebox.data.ConnectionState.STOPPED,
            "Разрешение VPN не выдано.",
        )
    }

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFromUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.rgb(25, 27, 29)
        window.navigationBarColor = android.graphics.Color.rgb(25, 27, 29)
        handleTileIntent(intent)

        setContent {
            GoreBoxTheme(darkTheme = viewModel.darkTheme) {
                SideEffect {
                    val barColor = if (viewModel.darkTheme) android.graphics.Color.rgb(25, 27, 29) else android.graphics.Color.rgb(244, 245, 247)
                    window.statusBarColor = barColor
                    window.navigationBarColor = barColor
                    WindowInsetsControllerCompat(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !viewModel.darkTheme
                        isAppearanceLightNavigationBars = !viewModel.darkTheme
                    }
                }
                GoreBoxRoot(
                    viewModel = viewModel,
                    onConnect = ::toggleProxy,
                    onOpenImport = { openFile.launch(arrayOf("text/*", "application/json", "application/octet-stream")) },
                    onPaste = ::pasteFromClipboard,
                    onOpenBatterySettings = ::openBatterySettings,
                    onOpenAppSettings = ::openAppSettings,
                    onBatteryStatusRefresh = ::refreshBatteryStatus,
                    onCopyProfile = ::copyProfile,
                )
                LaunchedEffect(intent?.getBooleanExtra(EXTRA_CONNECT_FROM_TILE, false)) {
                    if (intent?.getBooleanExtra(EXTRA_CONNECT_FROM_TILE, false) == true) {
                        viewModel.currentTab = com.goresense.gorebox.data.AppTab.Profiles
                        toggleProxy()
                        intent.removeExtra(EXTRA_CONNECT_FROM_TILE)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTileIntent(intent)
        if (intent.getBooleanExtra(EXTRA_CONNECT_FROM_TILE, false)) {
            toggleProxy()
            intent.removeExtra(EXTRA_CONNECT_FROM_TILE)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBatteryStatus()
        viewModel.refreshConnectionState()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ConnectionStateStore.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stateReceiver, filter)
        }
        stateReceiverRegistered = true
    }

    override fun onStop() {
        if (stateReceiverRegistered) {
            unregisterReceiver(stateReceiver)
            stateReceiverRegistered = false
        }
        super.onStop()
    }

    private fun handleTileIntent(intent: Intent?) {
        if (intent?.action == "android.service.quicksettings.action.QS_TILE_PREFERENCES") {
            viewModel.currentTab = com.goresense.gorebox.data.AppTab.Settings
        }
    }

    private fun toggleProxy() {
        if (viewModel.connectionState == com.goresense.gorebox.data.ConnectionState.RUNNING) {
            requestService(GoreBoxVpnService.ACTION_STOP)
            viewModel.setConnectionState(com.goresense.gorebox.data.ConnectionState.STOPPED)
            return
        }
        if (viewModel.selectedProfile == null) {
            viewModel.feedback = "Сначала добавьте или выберите профиль."
            return
        }
        if (!NativeCoreStatus.isIntegrated) {
            viewModel.showCoreUnavailable()
            return
        }

        val permission = VpnService.prepare(this)
        if (permission != null) vpnConsent.launch(permission) else startVpnConnection()
    }

    private fun startVpnConnection() {
        if (!NativeCoreStatus.isIntegrated) {
            viewModel.showCoreUnavailable()
            return
        }
        viewModel.setConnectionState(com.goresense.gorebox.data.ConnectionState.STARTING, "Запуск…")
        requestService(GoreBoxVpnService.ACTION_START)
    }

    private fun requestService(action: String) {
        val serviceIntent = Intent(this, GoreBoxVpnService::class.java).setAction(action)
        if (action == GoreBoxVpnService.ACTION_STOP) {
            startService(serviceIntent)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun pasteFromClipboard() {
        val manager = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = manager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) viewModel.feedback = "Буфер обмена пуст."
        else viewModel.importText(text)
    }

    private fun copyProfile(profile: com.goresense.gorebox.data.ProxyProfile) {
        val manager = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        manager.setPrimaryClip(android.content.ClipData.newPlainText("GoreBox profile", profile.source))
        viewModel.feedback = "Ссылка скопирована в буфер обмена."
    }

    private fun importFromUri(uri: Uri) {
        val content = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (content.isNullOrBlank()) viewModel.feedback = "Не удалось прочитать файл."
        else viewModel.importText(content)
    }

    private fun openBatterySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val power = getSystemService(POWER_SERVICE) as PowerManager
            if (!power.isIgnoringBatteryOptimizations(packageName)) {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName")),
                    )
                }.onFailure {
                    runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                        .onFailure { openAppSettings() }
                }
                return
            }
        }
        openAppSettings()
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName")))
        }.onFailure { viewModel.feedback = "Откройте настройки GoreBox вручную: Приложения → GoreBox → Батарея." }
    }

    private fun refreshBatteryStatus() {
        val exempt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        } else {
            true
        }
        viewModel.refreshBatteryStatus(exempt)
    }

    companion object {
        const val EXTRA_CONNECT_FROM_TILE = "connect_from_qs_tile"
    }
}
