package com.vibetun.app

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
import com.vibetun.app.data.ConnectionAction
import com.vibetun.app.data.ConnectionState
import com.vibetun.app.data.VibeTunViewModel
import com.vibetun.app.data.actionOnTap
import com.vibetun.app.service.ConnectionStateStore
import com.vibetun.app.service.VpnServiceLauncher
import com.vibetun.app.service.NativeCoreStatus
import com.vibetun.app.service.StopWatchdog
import com.vibetun.app.ui.VibeTunRoot
import com.vibetun.app.ui.VibeTunTheme

class MainActivity : ComponentActivity() {
    private val viewModel: VibeTunViewModel by viewModels()
    private var stateReceiverRegistered = false
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Сервис живёт в процессе :vpn, а SharedPreferences кэшируются per-process —
            // надёжнее брать состояние прямо из extras broadcast, а не из prefs.
            val stateName = intent.getStringExtra(ConnectionStateStore.EXTRA_STATE)
            if (stateName != null) {
                val state = runCatching { ConnectionState.valueOf(stateName) }
                    .getOrNull() ?: return
                viewModel.onConnectionStateFromService(
                    state,
                    intent.getStringExtra(ConnectionStateStore.EXTRA_MESSAGE).orEmpty(),
                    intent.getStringExtra(ConnectionStateStore.EXTRA_ACTIVE_PROFILE)?.takeIf { it.isNotBlank() },
                )
            } else {
                viewModel.refreshConnectionState()
            }
        }
    }

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) startVpnConnection()
        else viewModel.setConnectionState(
            // Единственный случай, когда STOPPED пишет UI: подключение даже не начиналось
            // (разрешение VPN не выдано), служба не запускалась — обмануть нечем.
            ConnectionState.STOPPED,
            "Разрешение VPN не выдано.",
        )
    }

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFromUri(uri)
    }

    private val exportRulesFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        val json = viewModel.exportActiveRouteSet() ?: return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.writer()?.use { it.write(json) }
        }.onSuccess {
            viewModel.feedback = "Маршруты экспортированы."
        }.onFailure {
            viewModel.feedback = "Не удалось записать файл: ${it.message}"
        }
    }

    private val importRulesFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val content = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (content.isNullOrBlank()) {
            viewModel.feedback = "Не удалось прочитать файл."
            return@registerForActivityResult
        }
        val setName = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "imported"
        viewModel.importRouteSet(setName, content)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.rgb(25, 27, 29)
        window.navigationBarColor = android.graphics.Color.rgb(25, 27, 29)
        handleTileIntent(intent)
        // Сторож закрытия: при смахивании приложения из недавних VPN выключится.
        runCatching { startService(Intent(this, com.vibetun.app.service.AppTaskWatcherService::class.java)) }

        setContent {
            VibeTunTheme(darkTheme = viewModel.darkTheme, accentColorArgb = viewModel.accentColorArgb) {
                SideEffect {
                    val barColor = if (viewModel.darkTheme) android.graphics.Color.rgb(25, 27, 29) else android.graphics.Color.rgb(244, 245, 247)
                    window.statusBarColor = barColor
                    window.navigationBarColor = barColor
                    WindowInsetsControllerCompat(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !viewModel.darkTheme
                        isAppearanceLightNavigationBars = !viewModel.darkTheme
                    }
                }
                VibeTunRoot(
                    viewModel = viewModel,
                    onConnect = ::toggleProxy,
                    onOpenImport = { openFile.launch(arrayOf("text/*", "application/json", "application/octet-stream")) },
                    onPaste = ::pasteFromClipboard,
                    onOpenBatterySettings = ::openBatterySettings,
                    onOpenAppSettings = ::openAppSettings,
                    onBatteryStatusRefresh = ::refreshBatteryStatus,
                    onCopyProfile = ::copyProfile,
                    onExportRules = { exportRulesFile.launch("vibetun-rules.json") },
                    onImportRules = { importRulesFile.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                )
                LaunchedEffect(intent?.getBooleanExtra(EXTRA_CONNECT_FROM_TILE, false)) {
                    if (intent?.getBooleanExtra(EXTRA_CONNECT_FROM_TILE, false) == true) {
                        connectFromTile()
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
            connectFromTile()
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
        // Пока Activity была остановлена, broadcast'ы состояния могли быть пропущены —
        // подтягиваем актуальное состояние из файла (свежее, чем prefs кросс-процессно).
        viewModel.refreshConnectionState()
    }

    override fun onDestroy() {
        startWatchdog.removeCallbacksAndMessages(null)
        // Полное закрытие приложения (finish, не пересоздание) — VPN не запоминаем и гасим.
        // Смахивание из недавних ловит AppTaskWatcherService: там onDestroy может не вызваться.
        if (isFinishing && viewModel.isConnected) {
            VpnServiceLauncher.stop(this)
        }
        super.onDestroy()
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
            viewModel.currentTab = com.vibetun.app.data.AppTab.Settings
        }
    }

    private fun toggleProxy() {
        when (viewModel.connectionState.actionOnTap()) {
            ConnectionAction.STOP -> requestStop()
            // Кнопка неактивна и во время запуска, и во время остановки: дублирующий тап игнорируем.
            ConnectionAction.IGNORE ->
                if (viewModel.connectionState == ConnectionState.STOPPING) viewModel.feedback = "Отключение уже выполняется…"
            ConnectionAction.CONNECT -> startProxyFromUi()
        }
    }

    /**
     * Остановка по тапу «Отключить». Служба живёт в своём процессе и сама закрывает ядро и TUN,
     * поэтому STOPPED ждём от неё (broadcast + файл состояния), а не пишем его сразу: иначе UI
     * показывал бы «Отключено», пока VPN-ключ в шторке и туннель ещё живы.
     */
    private fun requestStop() {
        startWatchdog.removeCallbacksAndMessages(null)
        viewModel.setConnectionState(ConnectionState.STOPPING, "Отключаемся…")
        VpnServiceLauncher.stop(this)
        StopWatchdog.arm(this)
    }

    /** Подключение из приложения; разрешение VPN спрашивается здесь же. */
    private fun startProxyFromUi() {
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

    /**
     * Запрос подключения из плитки быстрых настроек: в отличие от [toggleProxy] он никогда
     * не выключает уже работающий VPN (плитка шлёт его только когда хочет подключиться).
     */
    private fun connectFromTile() {
        viewModel.currentTab = com.vibetun.app.data.AppTab.Profiles
        when (viewModel.connectionState.actionOnTap()) {
            // Плитка шлёт этот запрос, только когда хочет подключиться: уже работающий VPN не гасим.
            ConnectionAction.STOP, ConnectionAction.IGNORE -> Unit
            ConnectionAction.CONNECT -> startProxyFromUi()
        }
    }

    private fun startVpnConnection() {
        if (!NativeCoreStatus.isIntegrated) {
            viewModel.showCoreUnavailable()
            return
        }
        val profile = viewModel.selectedProfile ?: return
        viewModel.setConnectionState(ConnectionState.STARTING, "Запуск…")
        // Службу выбирает лаунчер по протоколу: AmneziaWG и sing-box живут в разных процессах.
        VpnServiceLauncher.start(this, profile)
        armStartWatchdog()
    }

    private val startWatchdog = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Страховка от вечного «Подключение…»: состояние пишет служба в другом процессе,
     * и если она умерла или не стартовала, никто не переведёт UI в ошибку.
     */
    private fun armStartWatchdog() {
        startWatchdog.removeCallbacksAndMessages(null)
        startWatchdog.postDelayed({
            viewModel.refreshConnectionState() // свежее состояние из файла
            if (viewModel.connectionState == ConnectionState.STARTING) {
                // Сначала фиксируем причину, потом гасим зависшую службу: остановка переведёт
                // состояние в STOPPING/STOPPED и сообщение об ошибке останется только в логах.
                viewModel.setConnectionState(
                    ConnectionState.ERROR,
                    "VPN-служба не ответила за ${START_TIMEOUT_MS / 1000} с. Пришлите логи (Настройки → Логи).",
                )
                VpnServiceLauncher.stop(this)
                StopWatchdog.arm(this)
            }
        }, START_TIMEOUT_MS)
    }

    private fun pasteFromClipboard() {
        val manager = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = manager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) viewModel.feedback = "Буфер обмена пуст."
        else viewModel.importText(text)
    }

    private fun copyProfile(profile: com.vibetun.app.data.ProxyProfile) {
        val manager = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        manager.setPrimaryClip(android.content.ClipData.newPlainText("VibeTun profile", profile.source))
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
        }.onFailure { viewModel.feedback = "Откройте настройки VibeTun вручную: Приложения → VibeTun → Батарея." }
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
        private const val START_TIMEOUT_MS = 60_000L
    }
}
