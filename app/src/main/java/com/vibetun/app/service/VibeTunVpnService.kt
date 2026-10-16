package com.vibetun.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.IpPrefix
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vibetun.app.MainActivity
import com.vibetun.app.R
import com.vibetun.app.data.AmneziaWgConfig
import com.vibetun.app.data.AppLog
import com.vibetun.app.data.ConnectionState
import com.vibetun.app.data.VibeTunStore
import com.vibetun.app.data.ProxyProfile
import com.vibetun.app.data.RouteSetStore
import com.vibetun.app.data.SingBoxConfigBuilder
import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator as LibboxNetworkInterfaceIterator
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import org.amnezia.awg.GoBackend as AmneziaGoBackend
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface as JavaNetworkInterface
import java.util.Collections
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android VpnService hosting the bundled sing-box/libbox data plane.
 *
 * Два Go-runtime (libbox.so и libwg-go.so) не уживаются в одном процессе, поэтому у каждого
 * семейства ядра своя служба в своём процессе: эта (libbox, процесс `:vpn`) и
 * [VibeTunAwgVpnService] (AmneziaWG, процесс `:vpn_awg`). Нужную службу выбирает
 * [VpnServiceLauncher] по протоколу профиля — убивать процесс «на лету» больше не нужно.
 */
open class VibeTunVpnService : VpnService(), PlatformInterface {
    /** Семейство ядра, которое обслуживает эта служба. */
    protected open val engineFamily: String get() = FAMILY_LIBBOX

    private val stateStore by lazy { ConnectionStateStore(this) }
    private val profileStore by lazy { VibeTunStore(this) }
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val interfaceCallbacks = mutableMapOf<InterfaceUpdateListener, ConnectivityManager.NetworkCallback>()

    @Volatile private var boxService: BoxService? = null
    @Volatile private var amneziaHandle: Int = NO_AMNEZIA_HANDLE
    @Volatile private var tunDescriptor: ParcelFileDescriptor? = null
    @Volatile private var lastStoppedAtMs: Long = 0L

    /** Остановка в процессе: повторные ACTION_STOP игнорируются, старт — ставится в очередь. */
    private val stopping = AtomicBoolean(false)

    /** Пришёл ACTION_START, пока закрывалось ядро: процесс не гасим, а подключаемся заново. */
    @Volatile private var startRequestedWhileStopping: Boolean = false

    /** Защита от параллельного закрытия ядра (stop + onDestroy + fail). */
    private val closeLock = Any()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val endpointResolver = Executors.newSingleThreadExecutor { task ->
        Thread(task, "VibeTun-AWG-DNS").apply { isDaemon = true }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerNetworkChangeMonitor()
    }

    // ==================== автопереподключение при смене сети ====================

    /** Колбэк смены default-сети. Отдельный от мониторов libbox (interfaceCallbacks):
     *  тот информирует ядро об интерфейсе для НОВЫХ соединений, этот решает, когда
     *  живой туннель пора пересоздать — QUIC-сессии и AWG-сокет к старому интерфейсу
     *  не переезжают сами (в логах: ENETUNREACH / tx>0, rx=0 до ручного рестарта). */
    private var networkChangeCallback: ConnectivityManager.NetworkCallback? = null
    private var pendingNetworkReconnect: Runnable? = null

    @Volatile private var lastRealNetworkName: String = ""

    private fun registerNetworkChangeMonitor() {
        if (networkChangeCallback != null) return
        // Базовая линия ДО регистрации: иначе первый onAvailable с текущей сетью
        // посчитается сменой и спровоцирует лишний рестарт при поднятии службы.
        lastRealNetworkName = connectivity.activeNetwork?.let(::interfaceNameOfNetwork).orEmpty()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = considerNetworkChange(network)
            override fun onLinkPropertiesChanged(network: Network, props: android.net.LinkProperties) =
                considerNetworkChange(network)
        }
        networkChangeCallback = callback
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
    }

    private fun unregisterNetworkChangeMonitor() {
        networkChangeCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkChangeCallback = null
        pendingNetworkReconnect?.let { mainHandler.removeCallbacks(it) }
        pendingNetworkReconnect = null
    }

    private fun interfaceNameOfNetwork(network: Network): String? {
        val caps = connectivity.getNetworkCapabilities(network) ?: return null
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        return connectivity.getLinkProperties(network)?.interfaceName?.takeIf { it.isNotBlank() }
    }

    private fun considerNetworkChange(network: Network) {
        val name = interfaceNameOfNetwork(network) ?: return
        val previous = lastRealNetworkName
        if (name == previous) return
        lastRealNetworkName = name
        if (previous.isBlank()) return // первое известное имя — не смена сети
        if (!isProxyActive() || stopping.get()) return
        AppLog.i(TAG, "Смена сети: $previous -> $name — переподключение через ${NETWORK_RECONNECT_DELAY_MS}мс")
        scheduleNetworkReconnect(previous, name)
    }

    /** Дебаунс: Wi-Fi ↔ LTE может дребезжать несколько секунд — склеиваем всплески
     *  в один перезапуск. Пока ядро не поднялось или идёт остановка — не дёргаемся. */
    private fun scheduleNetworkReconnect(from: String, to: String) {
        pendingNetworkReconnect?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            pendingNetworkReconnect = null
            if (stopping.get()) return@Runnable
            if (!isProxyActive()) return@Runnable
            reconnectAfterNetworkChange(from, to)
        }
        pendingNetworkReconnect = runnable
        mainHandler.postDelayed(runnable, NETWORK_RECONNECT_DELAY_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        AppLog.i(TAG, "onStartCommand action=${action ?: "null"} startId=$startId pid=${Process.myPid()}")

        // Системный запуск (не наша команда): состояние VPN НЕ запоминаем и не восстанавливаем.
        // Если приложение полностью закрыли/выгрузили из памяти, VPN должен остаться выключенным,
        // а не подниматься заново после рестарта процесса или устройства.
        if (action == null || action == VpnService.SERVICE_INTERFACE) {
            AppLog.i(TAG, "onStartCommand: системный запуск — VPN не восстанавливаем, служба не нужна")
            profileStore.proxyWasActive = false
            satisfyForegroundObligation()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_STOP -> {
                // startForegroundService обязывает вызвать startForeground в течение 5 с, иначе
                // ForegroundServiceDidNotStartInTimeException. Поэтому — всегда, до любых проверок.
                promoteToForeground(null, STOP_NOTIFICATION_TEXT)
                stopConnection("ACTION_STOP")
            }
            ACTION_FORCE_KILL -> {
                // Последнее средство: ядро (или его close()) не отвечает — смерть процесса
                // гарантированно закрывает все fd TUN, включая дубликат из libbox.
                promoteToForeground(null, STOP_NOTIFICATION_TEXT)
                AppLog.e(TAG, "ACTION_FORCE_KILL: принудительное завершение процесса pid=${Process.myPid()}")
                profileStore.proxyWasActive = false
                Process.killProcess(Process.myPid())
            }
            ACTION_TOGGLE -> {
                // Новый запрос жизни отменяет отложенное убийство процесса: иначе при частом
                // переподключении kill по таймеру убивал только что запрошенный старт, и UI
                // навсегда оставался в «Поднимаем туннель…», хотя службы уже не было.
                mainHandler.removeCallbacks(killProcessRunnable)
                // startForegroundService обязывает поднять уведомление в течение 5 с — делаем это
                // до любых проверок, даже если сейчас пойдёт остановка.
                promoteToForeground(intent?.getStringExtra(EXTRA_PROFILE_ID))
                if (stopping.get()) {
                    AppLog.i(TAG, "ACTION_TOGGLE во время остановки — игнорируем")
                } else if (isProxyActive()) {
                    stopConnection("ACTION_TOGGLE")
                } else {
                    scheduleStart()
                    armStartWatchdog()
                }
            }
            ACTION_START -> {
                // Отменяем отложенное убийство процесса (см. ACTION_TOGGLE): частые
                // переподключения попадали ровно в это окно, и старт терялся вместе с процессом.
                mainHandler.removeCallbacks(killProcessRunnable)
                // startForegroundService обязывает вызвать startForeground в течение 5 с. Поэтому
                // поднимаем уведомление сразу, ДО любых проверок (VPN-разрешение, ядро, профиль):
                // иначе ранний fail() даёт ForegroundServiceDidNotStartInTimeException.
                promoteToForeground(intent?.getStringExtra(EXTRA_PROFILE_ID))
                // Явный старт при активном подключении = пользователь сменил профиль.
                // Раньше старт молча проглатывался (isProxyActive -> return), UI вис в
                // «Подключаемся…», а старый туннель продолжал работать.
                requestedProfileId = intent?.getStringExtra(EXTRA_PROFILE_ID)?.takeIf { it.isNotBlank() }
                if (stopping.get()) {
                    // Остановка уже идёт. Процесс :vpn/:vpn_awg выделен под одно ядро и после
                    // закрытия будет убит, поэтому запрос старта нельзя выполнять сразу — он
                    // потерялся бы вместе с процессом. Запоминаем и стартуем после teardown.
                    AppLog.i(TAG, "ACTION_START во время остановки — стартуем сразу после закрытия ядра")
                    startRequestedWhileStopping = true
                    return START_NOT_STICKY
                }
                if (isProxyActive()) {
                    AppLog.i(TAG, "ACTION_START при активном подключении — перезапуск с выбранным профилем")
                    restartWithSelectedProfile()
                } else {
                    scheduleStart()
                    armStartWatchdog()
                }
            }
            else -> {
                // Неизвестная команда: работы нет, но обязательство startForegroundService
                // (startForeground в течение 5 с) всё равно нужно снять.
                AppLog.i(TAG, "onStartCommand: неизвестное действие ($action) — служба не нужна")
                satisfyForegroundObligation()
                stopSelf(startId)
            }
        }
        // START_NOT_STICKY: система не должна перезапускать службу после kill процесса —
        // иначе она поднимет VPN заново (см. обработку action == null выше).
        return START_NOT_STICKY
    }

    /**
     * Снимает обязательство startForegroundService (startForeground в течение 5 с) для случая,
     * когда команда получена, но работы для службы нет.
     */
    private fun satisfyForegroundObligation() {
        promoteToForeground(null, STOP_NOTIFICATION_TEXT)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private fun promoteToForeground(profileId: String?, contentText: String? = null) {
        createNotificationChannel()
        val notification = buildNotification(profileId, contentText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            }.onFailure { error ->
                // Требование API 34 (тип + разрешение) может не выполниться на чужой сборке:
                // тогда поднимаем уведомление без явного типа — важнее не потерять команду.
                AppLog.e(TAG, "startForeground(systemExempted) не удался — пробуем без типа", error)
                runCatching { startForeground(NOTIFICATION_ID, notification) }
                    .onFailure { AppLog.e(TAG, "startForeground не удался", it) }
            }
        } else {
            runCatching { startForeground(NOTIFICATION_ID, notification) }
                .onFailure { AppLog.e(TAG, "startForeground не удался", it) }
        }
    }

    /**
     * Страховка службы: если подключение зависло в STARTING (ядро не ответило, UI закрыт и тайл
     * не может обновиться), переводим состояние в ERROR и гасим службу.
     */
    private val startTimeout = Runnable {
        if (stateStore.state == ConnectionState.STARTING) {
            fail("VPN-служба не ответила за ${ConnectionStateStore.START_TIMEOUT_MS / 1000} с. Пришлите логи (Настройки → Логи).")
        }
    }

    private fun armStartWatchdog() {
        mainHandler.removeCallbacks(startTimeout)
        mainHandler.postDelayed(startTimeout, ConnectionStateStore.START_TIMEOUT_MS)
    }

    /**
     * Нативное ядро падает, если стартовать сразу после остановки (teardown ещё идёт).
     * Даём короткую паузу и только затем запускаем подключение.
     */
    private fun scheduleStart() {
        mainHandler.removeCallbacks(pendingStart)
        mainHandler.removeCallbacks(killProcessRunnable)
        if (stopping.get()) {
            // Старт во время teardown не теряем: finishStop/kill-процесс подхватит флаг,
            // а молчаливый возврат здесь раньше оставлял UI в «Поднимаем туннель…» навсегда.
            AppLog.i(TAG, "scheduleStart: идёт остановка — старт поставлен в очередь после teardown")
            startRequestedWhileStopping = true
            return
        }
        if (isProxyActive()) {
            // Ядро уже живо (повторный старт/дубль команды): подтверждаем RUNNING,
            // а не молчим — иначе состояние так и останется STARTING.
            if (boxService != null) stateStore.update(ConnectionState.RUNNING, "Прокси включён")
            return
        }
        val waitMs = SETTLE_DELAY_MS - (SystemClock.elapsedRealtime() - lastStoppedAtMs)
        if (waitMs > 0) {
            AppLog.i(TAG, "Пауза ${waitMs}мс после остановки перед стартом")
            mainHandler.postDelayed(pendingStart, waitMs)
        } else {
            pendingStart.run()
        }
    }

    private val pendingStart = Runnable { startConnection() }

    /**
     * Отменяет отложенный старт и watchdog'и. Отложенное убийство процесса НЕ отменяется:
     * иначе служба осталась бы жить после остановки.
     */
    private fun cancelPendingWork() {
        mainHandler.removeCallbacks(pendingStart)
        mainHandler.removeCallbacks(startTimeout)
    }

    /**
     * Смена профиля при активном подключении. Закрытие ядра идёт в фоне (QUIC-сессии прощаются
     * секундами — на главном потоке это ANR и потерянный START), саму службу НЕ гасим и процесс
     * НЕ убиваем: следом идёт старт с новым профилем.
     */
    private fun restartWithSelectedProfile() = restartCore("Смена профиля…", "смена профиля")

    /** Переподключение после смены default-сети (Wi-Fi ↔ LTE и т.п.). Транспортный сокет
     *  старого туннеля прибит к исчезнувшему интерфейсу: без рестарта ядро пишет в никуда
     *  (hysteria2/tuic — «network is unreachable», AmneziaWG — tx>0, rx=0), а статус
     *  остаётся RUNNING. Делаем то, что делают v2rayNG/NekoBox/AmneziaWG: перезапуск ядра. */
    private fun reconnectAfterNetworkChange(from: String, to: String) =
        restartCore("Переподключение: смена сети ($from → $to)…", "смена сети $from → $to")

    private fun restartCore(statusText: String, reason: String) {
        cancelPendingWork()
        stateStore.update(ConnectionState.STARTING, statusText)
        Thread({
            val startedAt = SystemClock.elapsedRealtime()
            val closed = closeNativeServiceBlocking(STOP_TIMEOUT_MS)
            AppLog.i(TAG, "$reason: ядро закрыто (успешно=$closed) за ${SystemClock.elapsedRealtime() - startedAt}мс")
            mainHandler.post {
                if (stopping.get()) {
                    AppLog.i(TAG, "$reason отменена: пришла команда остановки")
                    return@post
                }
                lastStoppedAtMs = SystemClock.elapsedRealtime()
                scheduleStart()
                armStartWatchdog()
            }
        }, "VibeTun-Restart").apply { isDaemon = true }.start()
    }

    private fun startConnection() {
        if (stopping.get()) {
            // Остановка в полёте: поднимать ядро поверх неё нельзя — teardown перетрёт его.
            // Но и терять старт нельзя: ставим в очередь, finishStop его подхватит.
            AppLog.i(TAG, "startConnection: идёт остановка — старт поставлен в очередь")
            startRequestedWhileStopping = true
            return
        }
        if (isProxyActive()) {
            // См. scheduleStart: не оставляем UI в STARTING при уже живом ядре.
            if (boxService != null) stateStore.update(ConnectionState.RUNNING, "Прокси включён")
            return
        }
        if (!NativeCoreStatus.isIntegrated) {
            AppLog.e(TAG, "startConnection: ядро не собрано — ${NativeCoreStatus.message}")
            fail(NativeCoreStatus.message)
            return
        }
        if (prepare(this) != null) {
            fail("Сначала подтвердите VPN-подключение в VibeTun.")
            return
        }

        stateStore.update(ConnectionState.STARTING, "Подготовка VPN-интерфейса…")

        val explicitRequest = requestedProfileId != null
        try {
            val profile = loadSelectedProfile()
            logStartHeader(profile)
            val storeProfiles = profileStore.loadProfiles()
            val storeSnapshot = storeProfiles.joinToString(", ") { "${it.id.take(8)}:${it.protocol}" }
            AppLog.i(TAG, "store: профилей=${storeProfiles.size}: $storeSnapshot")
            AppLog.i(TAG, "startConnection: профиль ${profile.id} (${profile.protocol}) ${profile.name}")
            // Служба обязана соответствовать семейству ядра профиля (см. VpnServiceLauncher).
            val family = familyOf(profile.protocol)
            if (family != engineFamily) {
                AppLog.e(TAG, "startConnection: профиль ${profile.protocol} запущен не той службой ($engineFamily)")
                val hint = if (explicitRequest) "" else
                    " Always-on VPN поддерживает только профили sing-box: для AmneziaWG отключите always-on."
                fail("Профиль ${profile.name} запущен не той VPN-службой. Переподключитесь.$hint")
                return
            }
            if (profile.protocol.equals("amneziawg", ignoreCase = true)) {
                startAmneziaWg(profile)
                stateStore.update(ConnectionState.STARTING, "Ожидание handshake с сервером…", profile.id)
            } else {
                startSingBox(profile)
                stateStore.update(ConnectionState.RUNNING, "Прокси включён · ${profile.name}", profile.id)
            }
            profileStore.proxyWasActive = true
            Log.i(TAG, "Proxy core started for profile ${profile.id} (${profile.protocol})")
            AppLog.i(TAG, "startConnection: ядро запущено успешно (${profile.protocol})")
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            Log.e(TAG, "Failed to start proxy core", error)
            AppLog.e(TAG, "Failed to start proxy core", error)
            coreLogTail().takeIf { it.isNotEmpty() }?.let { AppLog.e(TAG, "Хвост core.log (50 строк):\n$it") }
            fail(error.message?.takeIf { it.isNotBlank() } ?: "Не удалось запустить прокси-ядро.")
        }
    }

    /** Заголовок лога при старте: версия приложения, Android, ABI, протокол и версии ядер. */
    private fun logStartHeader(profile: ProxyProfile) {
        val appVersion = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        val awg = if (familyOf(profile.protocol) == FAMILY_AWG) {
            runCatching { AmneziaGoBackend.awgVersion() }.getOrDefault("недоступно")
        } else {
            "не используется"
        }
        AppLog.i(
            TAG,
            "=== старт: VibeTun $appVersion, Android API ${Build.VERSION.SDK_INT}, ABI ${Build.SUPPORTED_ABIS.joinToString()}, " +
                "протокол ${profile.protocol}, процесс ${engineFamily}, sing-box ${NativeCoreStatus.SING_BOX_VERSION}, AmneziaWG $awg",
        )
    }

    /** Последние 50 строк core.log (лог sing-box), чтобы причина падения попала в vibetun-*.log. */
    private fun coreLogTail(): String = runCatching {
        File(filesDir, "logs/core.log").takeIf { it.isFile }?.readLines()?.takeLast(50)?.joinToString("\n").orEmpty()
    }.getOrDefault("")

    private fun startSingBox(profile: ProxyProfile) {
        val routeSetName = activeRouteSet()
        val rules = RouteSetStore(this).load(routeSetName)
        AppLog.i(TAG, "startSingBox: сборка конфигурации… набор=«$routeSetName», правил=${rules.size}")
        AppLog.i(TAG, "startSingBox: DNS — прямой=vibetun-dns-direct (DoH common.dot.dns.yandex.net, резолвер 77.88.8.8), reverse_mapping=true, strategy=ipv4_only. Проверка: в core.log (debug) строки «match[N] ip_cidr=… => vibetun-direct» / «domain_suffix=… => vibetun-direct» подтверждают работу DIRECT-правил; ошибки DNS — «failed to resolve»")
        val config = SingBoxConfigBuilder.build(
            profile = profile,
            selectedPackages = profileStore.selectedAppPackages,
            selectedAppsOnly = profileStore.selectedAppsOnly,
            directPackages = profileStore.directAppPackages,
            routeRules = rules,
        )
        val basePath = filesDir.absolutePath
        ensureLibboxReady(basePath)
        AppLog.i(TAG, "startSingBox: конфиг (секреты скрыты) ${SingBoxConfigBuilder.redactForLog(config)}")
        // Предстартовая проверка: понятная причина ошибки вместо нативного падения при newService.
        try {
            Libbox.checkConfig(config)
        } catch (error: Exception) {
            throw IllegalStateException("Конфигурация отклонена ядром sing-box: ${error.message}", error)
        }
        AppLog.i(TAG, "startSingBox: Libbox.newService…")
        val service = Libbox.newService(config, this)
        boxService = service
        AppLog.i(TAG, "startSingBox: service.start()…")
        service.start()
        AppLog.i(TAG, "startSingBox: service.start() завершён")
    }

    private fun ensureLibboxReady(basePath: String) {
        if (libboxReady) return
        synchronized(VibeTunVpnService::class.java) {
            if (libboxReady) return
            AppLog.i(TAG, "startSingBox: Libbox.setup(basePath=$basePath, однократно)")
            Libbox.setup(basePath, basePath, cacheDir.absolutePath, false)
            libboxReady = true
        }
    }

    private fun activeRouteSet(): String {
        val saved = profileStore.activeRouteSetName
        val sets = RouteSetStore(this).listSets()
        // Если сохранённый набор существует на диске — используем его; иначе fallback на первый.
        val name = if (saved.isNotBlank() && saved in sets) saved else sets.firstOrNull().orEmpty()
        AppLog.i(TAG, "activeRouteSet: сохранён=«$saved», выбран=«$name», всего наборов=${sets.size}")
        return name
    }

    private fun startAmneziaWg(profile: ProxyProfile) {
        AppLog.i(TAG, "startAmneziaWg: разбор конфигурации…")
        val config = AmneziaWgConfig.parse(profile.source)
        // Параметры обфускации в журнал: при «tx растёт, rx=0» сразу видно,
        // какие Jc/S/H-значения реально дошли до ядра (ключи не логируются).
        AppLog.i(TAG, "startAmneziaWg: параметры ${config.debugDescription()}")
        val userspaceConfig = config.userspaceConfig(::resolveAwgEndpoint)
        val builder = Builder()
            .setSession("VibeTun · AmneziaWG")
            .setMtu(config.mtu)
            .setBlocking(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        setUnderlyingNetworks(null)

        config.addresses.forEach { cidr ->
            val (address, prefix) = parseIpPrefix(cidr)
            builder.addAddress(address, prefix)
        }
        config.dnsServers.forEach { builder.addDnsServer(it) }
        config.dnsSearchDomains.forEach { builder.addSearchDomain(it) }

        var hasIpv4DefaultRoute = false
        var hasIpv6DefaultRoute = false
        // IPv6-маршруты пропускаем: серверы IPv4-only, AAAA-трафик через туннель
        // зависает (YouTube). Туннель становится IPv4-only, приложения идут по v4.
        config.peerAllowedIps().filterNot { it.contains(':') }.forEach { cidr ->
            val (address, prefix) = parseIpPrefix(cidr)
            builder.addRoute(address, prefix)
            if (prefix == 0 && address is Inet4Address) hasIpv4DefaultRoute = true
            if (prefix == 0 && address is Inet6Address) hasIpv6DefaultRoute = true
        }
        // IPv4 без default-маршрута идёт мимо туннеля по дизайну (split-маршруты из AllowedIPs).
        if (!hasIpv4DefaultRoute) builder.allowFamily(OsConstants.AF_INET)
        // IPv6 НЕ разрешаем через allowFamily: по документации Builder.allowFamily такой трафик
        // уходит в обычную сеть в обход VPN (утечка). Вместо этого весь IPv6 отправляем в TUN,
        // где WireGuard-пир (IPv4-only AllowedIPs) его отбрасывает.
        if (!hasIpv6DefaultRoute) builder.addRoute("::", 0)
        // приложения с маршрутом DIRECT идут мимо туннеля AmneziaWG; DIRECT важнее PROXY,
        // поэтому сначала вычитаем их из выбранных (на случай пересечения в хранилище).
        val directPackages = profileStore.directAppPackages.sorted()
        if (profileStore.selectedAppsOnly) {
            val selectedPackages = (profileStore.selectedAppPackages - directPackages.toSet()).sorted()
            require(selectedPackages.isNotEmpty()) {
                "Для режима «Выбранные приложения» отметьте хотя бы одно приложение."
            }
            // Только allowed-список: addDisallowedApplication после addAllowedApplication
            // запрещён Android (UnsupportedOperationException), а DIRECT-приложения
            // и так не входят в allowed и потому идут мимо VPN.
            addApplications(builder, selectedPackages, allowed = true)
        } else {
            // VibeTun не должен попадать в собственный VPN: транспортный UDP-сокет
            // AmneziaWG создаётся при первой отправке и в этот момент ещё не защищён
            // protect() — без исключения первый handshake уходит в собственный TUN
            // и до сервера не доходит (rx=0 при растущем tx).
            builder.addDisallowedApplication(packageName)
            if (directPackages.isNotEmpty()) {
                addApplications(builder, directPackages, allowed = false)
            }
        }

        val establishedTun = checkNotNull(builder.establish()) { "Android did not establish the AmneziaWG VPN interface." }
        runCatching { tunDescriptor?.close() }
        tunDescriptor = establishedTun
        val nativeFd = ParcelFileDescriptor.dup(establishedTun.fileDescriptor).detachFd()
        AppLog.i(TAG, "startAmneziaWg: TUN интерфейс создан, запуск awgTurnOn…")
        val handle = AmneziaGoBackend.awgTurnOn("vibetun-awg", nativeFd, userspaceConfig)
        check(handle >= 0) { "Не удалось запустить AmneziaWG. Проверьте ключи, endpoint и параметры конфигурации." }
        amneziaHandle = handle
        watchAwgHandshake(handle, profile)

        val socketFds = listOf(
            AmneziaGoBackend.awgGetSocketV4(handle),
            AmneziaGoBackend.awgGetSocketV6(handle),
        ).filter { it >= 0 }.distinct()
        check(socketFds.isNotEmpty()) { "AmneziaWG не создал UDP-сокет для подключения." }
        socketFds.forEach { fd ->
            check(protect(fd)) { "Android не смог исключить транспорт AmneziaWG из VPN." }
        }
        Log.i(TAG, "AmneziaWG ${AmneziaGoBackend.awgVersion()} started for profile ${profile.id}")
        AppLog.i(TAG, "startAmneziaWg: запущено (${AmneziaGoBackend.awgVersion()})")
    }

    /**
     * AmneziaWG: awgTurnOn лишь создаёт туннель — реальное соединение подтверждается
     * handshake'ем. Поллим UAPI-статистику на фоновом потоке: handshake есть → RUNNING,
     * за таймаут не появился → понятная ошибка вместо вечного «подключено, но не работает».
     */
    private fun watchAwgHandshake(handle: Int, profile: ProxyProfile) {
        Thread {
            val deadline = SystemClock.elapsedRealtime() + AWG_HANDSHAKE_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline && amneziaHandle == handle) {
                Thread.sleep(2_000)
                val dump = runCatching { AmneziaGoBackend.awgGetStats(handle) }.getOrNull() ?: continue
                val handshakeSec = dump.lineSequence()
                    .firstOrNull { it.startsWith("last_handshake_time_sec=") }
                    ?.substringAfter('=')?.trim()?.toLongOrNull() ?: 0L
                if (handshakeSec > 0L) {
                    AppLog.i(TAG, "AmneziaWG: handshake с сервером установлен, туннель активен")
                    if (amneziaHandle == handle) {
                        stateStore.update(ConnectionState.RUNNING, "Прокси включён · ${profile.name}", profile.id)
                    }
                    return@Thread
                }
            }
            if (amneziaHandle == handle) {
                // tx>0 и rx=0 → пакеты уходят, но сервер молчит (ключ/параметры обфускации/порт/блокировка);
                // tx=0 → ничего не отправлено (петля в TUN, нет маршрута, не разрешился endpoint).
                val stats = runCatching { AmneziaGoBackend.awgGetStats(handle) }.getOrNull().orEmpty()
                fun stat(name: String) = stats.lineSequence().firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.trim()
                val tx = stat("tx_bytes")?.toLongOrNull() ?: 0L
                val rx = stat("rx_bytes")?.toLongOrNull() ?: 0L
                AppLog.e(TAG, "AmneziaWG: handshake не установлен за ${AWG_HANDSHAKE_TIMEOUT_MS / 1000}с " +
                    "(tx_bytes=${stat("tx_bytes")}, rx_bytes=${stat("rx_bytes")}, endpoint=${stat("endpoint")})")
                val hint = if (tx > 0 && rx == 0L) {
                    " Пакеты уходят, но сервер не отвечает: чаще всего это несовпадение параметров " +
                        "обфускации (Jc/Jmin/Jmax, S1–S4, H1–H4) с сервером, неверный порт/ключ " +
                        "или блокировка протокола сетью."
                } else ""
                mainHandler.post {
                    if (amneziaHandle == handle) {
                        fail("AmneziaWG не смог связаться с сервером (нет handshake за ${AWG_HANDSHAKE_TIMEOUT_MS / 1000} секунд). Проверьте ключ и сеть.$hint")
                    }
                }
            }
        }.apply { isDaemon = true; name = "VibeTun-AWG-Handshake" }.start()
    }

    private fun parseIpPrefix(cidr: String): Pair<InetAddress, Int> {
        val separator = cidr.lastIndexOf('/')
        require(separator > 0 && separator < cidr.lastIndex) { "Некорректный IP-префикс: $cidr" }
        val host = cidr.substring(0, separator)
        require(host.contains(':') || IPV4_PATTERN.matches(host)) { "В туннельном маршруте ожидался IP-адрес: $cidr" }
        val address = InetAddress.getByName(host)
        val prefix = cidr.substring(separator + 1).toIntOrNull()
            ?: throw IllegalArgumentException("Некорректная длина IP-префикса: $cidr")
        val maxPrefix = if (address is Inet4Address) 32 else 128
        require(prefix in 0..maxPrefix) { "Некорректная длина IP-префикса: $cidr" }
        return address to prefix
    }

    private fun resolveAwgEndpoint(endpoint: String): String {
        val (host, port) = splitEndpoint(endpoint)
        val address = if (host.contains(':') || IPV4_PATTERN.matches(host)) {
            InetAddress.getByName(host)
        } else {
            try {
                endpointResolver.submit<InetAddress> {
                    val networkAddresses = connectivity.activeNetwork?.getAllByName(host)
                    networkAddresses?.firstOrNull() ?: InetAddress.getAllByName(host).firstOrNull()
                        ?: throw IllegalArgumentException("Не найден IP-адрес endpoint AmneziaWG: $host")
                }.get(15, TimeUnit.SECONDS)
            } catch (error: TimeoutException) {
                throw IllegalArgumentException("Истекло время DNS-разрешения endpoint AmneziaWG: $host", error)
            } catch (error: ExecutionException) {
                throw IllegalArgumentException("Не удалось разрешить endpoint AmneziaWG $host: ${error.cause?.message}", error.cause ?: error)
            }
        }
        val numericHost = checkNotNull(address.hostAddress).substringBefore('%')
        val formattedHost = if (address is Inet6Address) "[$numericHost]" else numericHost
        AppLog.i(TAG, "AmneziaWG endpoint: $host -> $formattedHost:$port")
        return "$formattedHost:$port"
    }

    private fun splitEndpoint(endpoint: String): Pair<String, Int> {
        val host: String
        val portText: String
        if (endpoint.startsWith('[')) {
            val closingBracket = endpoint.indexOf(']')
            require(closingBracket > 1 && endpoint.getOrNull(closingBracket + 1) == ':') {
                "Некорректный IPv6 endpoint AmneziaWG: $endpoint"
            }
            host = endpoint.substring(1, closingBracket)
            portText = endpoint.substring(closingBracket + 2)
        } else {
            val separator = endpoint.lastIndexOf(':')
            require(separator > 0 && !endpoint.substring(0, separator).contains(':')) {
                "Некорректный endpoint AmneziaWG: $endpoint"
            }
            host = endpoint.substring(0, separator)
            portText = endpoint.substring(separator + 1)
        }
        val port = portText.toIntOrNull()
        require(host.isNotBlank() && port != null && port in 1..65535) {
            "Некорректный endpoint AmneziaWG: $endpoint"
        }
        return host to port
    }

    private fun isProxyActive(): Boolean = boxService != null || amneziaHandle != NO_AMNEZIA_HANDLE

    private fun loadSelectedProfile(): ProxyProfile {
        val requested = requestedProfileId
        requestedProfileId = null
        // MODE_MULTI_PROCESS prefs: свежеподнятый процесс службы может прочитать протухший
        // снапшот (запись apply() из UI ещё не доехала до диска) и честно не найти профиль.
        // По логам такой случай реален, поэтому перед ошибкой — повторные попытки с паузой.
        repeat(3) { attempt ->
            val profiles = profileStore.loadProfiles()
            val selectedId = requested ?: profileStore.selectedProfileId
            profiles.firstOrNull { it.id == selectedId }?.let { return it }
            if (attempt < 2) {
                val ids = profiles.joinToString(", ") { it.id.take(8) }
                AppLog.i(
                    TAG,
                    "loadSelectedProfile: «$selectedId» не найден (попытка ${attempt + 1}/3, в store ${profiles.size} профилей: $ids) — ждём синхронизации",
                )
                Thread.sleep(300)
            }
        }
        throw IllegalStateException("Профиль не найден. Откройте VibeTun и выберите профиль.")
    }

    private fun fail(message: String) {
        AppLog.e(TAG, "fail: $message")
        cancelPendingWork()
        stateStore.update(ConnectionState.ERROR, message)
        // Ошибка запуска тоже не должна оставлять TUN: закрываем ядро в фоне и гасим процесс
        // тем же путём, что и обычная остановка.
        profileStore.proxyWasActive = false
        stopping.set(true)
        startRequestedWhileStopping = false
        teardownInBackground("fail") { closed ->
            AppLog.i(TAG, "fail: ядро закрыто (успешно=$closed), гасим службу")
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            scheduleProcessKill("fail")
        }
    }

    /**
     * Остановка подключения. Единственный владелец жизненного цикла — служба: UI и плитка только
     * просят остановку (ACTION_STOP) и ждут STOPPED. Закрытие ядра выполняется в фоновом потоке,
     * поэтому главный поток службы не блокируется зависшим `boxService.close()`.
     */
    private fun stopConnection(reason: String) {
        val requestedAtMs = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "stop($reason): запрос остановки, ядро активно=${isProxyActive()}")
        cancelPendingWork()
        if (!stopping.compareAndSet(false, true)) {
            AppLog.i(TAG, "stop($reason): остановка уже выполняется — повторный запрос игнорируем")
            return
        }
        startRequestedWhileStopping = false
        // Синхронная запись (файл + commit): процесс будет убит сразу после закрытия ядра,
        // и apply() может не успеть — тогда после рестарта VPN поднялся бы заново.
        profileStore.proxyWasActive = false
        stateStore.update(ConnectionState.STOPPING, "Отключаемся…")
        teardownInBackground("stop") { closed -> finishStop(closed, requestedAtMs) }
    }

    /**
     * Закрывает ядро и TUN в фоновом потоке (boxService.close() может занимать секунды:
     * QUIC-ядра прощаются с сервером), результат отдаёт на главный поток.
     */
    private fun teardownInBackground(stage: String, finish: (closed: Boolean) -> Unit) {
        Thread({
            val startedAt = SystemClock.elapsedRealtime()
            val closed = closeNativeServiceBlocking(STOP_TIMEOUT_MS)
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            AppLog.i(TAG, "$stage: закрытие ядра завершено (успешно=$closed) за ${elapsed}мс")
            mainHandler.post { finish(closed) }
        }, STOP_THREAD_NAME).apply { isDaemon = true }.start()
    }

    private fun finishStop(closed: Boolean, requestedAtMs: Long) {
        if (startRequestedWhileStopping) {
            // Пока закрывали ядро, пришёл ACTION_START (смена профиля/повторное подключение).
            // Процесс не гасим: новый start потерялся бы вместе с ним.
            AppLog.i(TAG, "stop: во время остановки запрошен старт — процесс оставляем, подключаемся заново")
            stopping.set(false)
            startRequestedWhileStopping = false
            lastStoppedAtMs = SystemClock.elapsedRealtime()
            scheduleStart()
            armStartWatchdog()
            return
        }

        // TUN закрыт (включая дубликат fd, который держал libbox) — VPN-ключ в шторке должен исчезнуть.
        AppLog.i(
            TAG,
            "stop: TUN закрыт (ядро закрыто успешно=$closed) за ${SystemClock.elapsedRealtime() - requestedAtMs}мс с запроса",
        )
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            .onFailure { AppLog.e(TAG, "stop: stopForeground не удался", it) }
        AppLog.i(TAG, "stop: stopForeground(STOP_FOREGROUND_REMOVE) выполнен")
        if (stateStore.state == ConnectionState.STOPPING) {
            stateStore.update(ConnectionState.STOPPED)
            AppLog.i(TAG, "stop: состояние STOPPED записано службой")
        } else {
            AppLog.i(TAG, "stop: состояние уже ${stateStore.state} — STOPPED не пишем")
        }
        // Сбрасываем флаг остановки ДО отложенного убийства процесса: раньше stopping
        // оставался true до самой смерти процесса, и ACTION_START в этом окне уходил
        // в ветку «ждём teardown», хотя teardown уже завершён.
        stopping.set(false)
        scheduleProcessKill("stop")
    }

    /**
     * Планирует завершение процесса. Процесс `:vpn` / `:vpn_awg` выделен под одно ядро: его смерть
     * гарантированно закрывает все fd TUN (включая дубликат libbox, который не закрылся, если
     * `boxService.close()` завис) и сбрасывает Go-runtime перед следующим подключением.
     * Небольшая задержка нужна, чтобы новый ACTION_START успел отменить убийство.
     */
    private fun scheduleProcessKill(stage: String) {
        mainHandler.removeCallbacks(killProcessRunnable)
        mainHandler.postDelayed(killProcessRunnable, PROCESS_KILL_DELAY_MS)
        AppLog.i(TAG, "$stage: процесс будет убит через ${PROCESS_KILL_DELAY_MS}мс, если не придёт новый старт")
    }

    private val killProcessRunnable = Runnable {
        val current = instance
        if (current != null && current !== this@VibeTunVpnService) {
            // Службу уже пересоздали под новое подключение — убивать процесс нельзя.
            AppLog.i(TAG, "kill: служба пересоздана — убийство процесса отменено")
            return@Runnable
        }
        if (startRequestedWhileStopping) {
            AppLog.i(TAG, "kill: за время ожидания пришёл ACTION_START — процесс не убиваем")
            stopping.set(false)
            startRequestedWhileStopping = false
            lastStoppedAtMs = SystemClock.elapsedRealtime()
            scheduleStart()
            armStartWatchdog()
            return@Runnable
        }
        AppLog.i(TAG, "kill: stopSelf + Process.killProcess(pid=${Process.myPid()}) — ядро ОС закроет оставшиеся fd TUN")
        runCatching { stopSelf() }
        Process.killProcess(Process.myPid())
    }

    /**
     * Синхронно закрывает ядро и TUN. Вызывать только из фонового потока.
     * false — закрытие не уложилось в таймаут: вызывающий обязан убить процесс,
     * иначе TUN (и VPN-ключ в шторке) может остаться.
     */
    private fun closeNativeServiceBlocking(timeoutMs: Long): Boolean {
        val result = AtomicBoolean(false)
        val closer = Thread({ result.set(closeNativeService()) }, CLOSE_THREAD_NAME).apply { isDaemon = true }
        closer.start()
        closer.join(timeoutMs)
        if (closer.isAlive) {
            AppLog.e(TAG, "close: закрытие ядра не уложилось в ${timeoutMs}мс — закроем его смертью процесса")
            return false
        }
        return result.get()
    }

    /** Идемпотентное закрытие нативного ядра и TUN. Возвращает false, если что-то закрылось с ошибкой. */
    private fun closeNativeService(): Boolean {
        var success = true
        synchronized(closeLock) {
            val currentBoxService = boxService
            boxService = null
            if (currentBoxService != null) {
                val startedAt = SystemClock.elapsedRealtime()
                runCatching { currentBoxService.close() }
                    .onSuccess { AppLog.i(TAG, "close: boxService.close() завершён за ${SystemClock.elapsedRealtime() - startedAt}мс") }
                    .onFailure {
                        success = false
                        AppLog.e(TAG, "close: ошибка boxService.close()", it)
                        Log.w(TAG, "Error while stopping sing-box", it)
                    }
            }

            val currentAmneziaHandle = amneziaHandle
            amneziaHandle = NO_AMNEZIA_HANDLE
            if (currentAmneziaHandle != NO_AMNEZIA_HANDLE) {
                val startedAt = SystemClock.elapsedRealtime()
                runCatching { AmneziaGoBackend.awgTurnOff(currentAmneziaHandle) }
                    .onSuccess { AppLog.i(TAG, "close: awgTurnOff() завершён за ${SystemClock.elapsedRealtime() - startedAt}мс") }
                    .onFailure {
                        success = false
                        AppLog.e(TAG, "close: ошибка awgTurnOff()", it)
                        Log.w(TAG, "Error while stopping AmneziaWG", it)
                    }
            }

            val descriptor = tunDescriptor
            tunDescriptor = null
            if (descriptor != null) {
                runCatching { descriptor.close() }
                    .onSuccess { AppLog.i(TAG, "close: tunDescriptor закрыт") }
                    .onFailure {
                        success = false
                        AppLog.e(TAG, "close: ошибка закрытия tunDescriptor", it)
                        Log.w(TAG, "Error while closing VPN descriptor", it)
                    }
            }
        }
        return success
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        check(protect(fd)) { "Android could not protect an outbound socket from the VPN." }
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun openTun(options: TunOptions): Int {
        check(prepare(this) == null) { "Android VPN permission is missing or revoked." }
        val mtu = options.mtu.coerceIn(576, 9000)
        val builder = Builder()
            .setSession("VibeTun")
            .setMtu(mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        val inet4 = options.inet4Address
        val hasInet4 = inet4.hasNext()
        while (inet4.hasNext()) {
            val address = inet4.next()
            builder.addAddress(address.address(), address.prefix())
        }
        val inet6 = options.inet6Address
        val hasInet6 = inet6.hasNext()
        while (inet6.hasNext()) {
            val address = inet6.next()
            builder.addAddress(address.address(), address.prefix())
        }

        if (options.autoRoute) {
            options.dnsServerAddress.takeIf { it.isNotBlank() }?.let(builder::addDnsServer)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                addConfiguredRoutes(builder, options.inet4RouteAddress, "0.0.0.0", hasInet4)
                addConfiguredRoutes(builder, options.inet6RouteAddress, "::", hasInet6)
                addExcludedRoutes(builder, options.inet4RouteExcludeAddress)
                addExcludedRoutes(builder, options.inet6RouteExcludeAddress)
            } else {
                addRouteRanges(builder, options.inet4RouteRange, "0.0.0.0", hasInet4)
                addRouteRanges(builder, options.inet6RouteRange, "::", hasInet6)
            }
            // Android запрещает смешивать addAllowedApplication и addDisallowedApplication
            // в одном Builder (UnsupportedOperationException): если задан список включённых
            // приложений, DIRECT-приложения и так не попадут в VPN — они просто не входят
            // в allowed-список, отдельный exclude для них не нужен и вреден.
            val includePackages = drainPackages(options.includePackage)
            val excludePackages = drainPackages(options.excludePackage)
            if (includePackages.isNotEmpty()) {
                addApplications(builder, includePackages, allowed = true)
            } else {
                addApplications(builder, excludePackages, allowed = false)
            }
        }

        val newTun = checkNotNull(builder.establish()) { "Android did not establish the VPN interface." }
        runCatching { tunDescriptor?.close() }
        tunDescriptor = newTun
        // libbox duplicates this descriptor; the service keeps the original open until stop.
        return newTun.fd
    }

    private fun addConfiguredRoutes(builder: Builder, routes: RoutePrefixIterator, fallbackAddress: String, hasFamilyAddress: Boolean) {
        if (routes.hasNext()) {
            while (routes.hasNext()) {
                val route = routes.next()
                builder.addRoute(route.address(), route.prefix())
            }
        } else if (hasFamilyAddress) {
            builder.addRoute(fallbackAddress, 0)
        }
    }

    private fun addRouteRanges(builder: Builder, routes: RoutePrefixIterator, fallbackAddress: String, hasFamilyAddress: Boolean) {
        if (routes.hasNext()) {
            while (routes.hasNext()) {
                val route = routes.next()
                builder.addRoute(route.address(), route.prefix())
            }
        } else if (hasFamilyAddress) {
            builder.addRoute(fallbackAddress, 0)
        }
    }

    private fun addExcludedRoutes(builder: Builder, routes: RoutePrefixIterator) {
        while (routes.hasNext()) {
            val route = routes.next()
            val address = java.net.InetAddress.getByName(route.address())
            builder.excludeRoute(IpPrefix(address, route.prefix()))
        }
    }

    private fun drainPackages(packages: StringIterator): List<String> {
        val packageNames = mutableListOf<String>()
        while (packages.hasNext()) packageNames += packages.next()
        return packageNames
    }

    private fun addApplications(builder: Builder, packageNames: List<String>, allowed: Boolean) {
        if (packageNames.isEmpty()) return
        if (allowed && packageNames.any { it == packageName }) {
            throw IllegalArgumentException("VibeTun не может быть включён в собственный VPN-маршрут.")
        }
        var added = 0
        packageNames.distinct().forEach { appPackage ->
            try {
                if (allowed) builder.addAllowedApplication(appPackage) else builder.addDisallowedApplication(appPackage)
                added++
            } catch (error: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Skip unavailable app $appPackage", error)
            }
        }
        if (allowed && added == 0) {
            throw IllegalArgumentException("Ни одно выбранное приложение не установлено.")
        }
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int,
    ): Int {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "Connection-owner lookup requires Android 10 or newer." }
        val uid = connectivity.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress.orEmpty(), sourcePort),
            InetSocketAddress(destinationAddress.orEmpty(), destinationPort),
        )
        check(uid >= 0) { "Android could not identify the connection owner." }
        return uid
    }

    override fun packageNameByUid(uid: Int): String {
        return packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty()
    }

    override fun uidByPackageName(packageName: String?): Int {
        val name = packageName?.takeIf { it.isNotBlank() } ?: error("Package name is empty.")
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(name, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(name, 0)
        }
        return info.uid
    }

    override fun usePlatformDefaultInterfaceMonitor(): Boolean = true

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        if (interfaceCallbacks.containsKey(listener)) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publishDefaultNetwork(listener, network)
            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) =
                publishDefaultNetwork(listener, network, linkProperties.interfaceName)

            override fun onLost(network: Network) {
                val active = connectivity.activeNetwork
                if (active == null || active == network) listener.updateDefaultInterface("", -1)
            }
        }
        interfaceCallbacks[listener] = callback
        connectivity.registerDefaultNetworkCallback(callback)
        connectivity.activeNetwork?.let { publishDefaultNetwork(listener, it) }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val callback = interfaceCallbacks.remove(listener) ?: return
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }

    @Volatile private var lastRealInterfaceName: String = ""
    @Volatile private var lastRealInterfaceIndex: Int = -1

    private fun publishDefaultNetwork(
        listener: InterfaceUpdateListener,
        network: Network,
        knownInterfaceName: String? = null,
    ) {
        // Собственный VPN-интерфейс (TRANSPORT_VPN) нельзя публиковать как default:
        // иначе outbound ядра привяжется к tun0 и весь трафик зависнет в петле.
        // Вместо сброса в пустоту (ядро ругается "missing default interface" и теряет
        // upstream-сессии, ломая DNS/DoH) сохраняем последний реальный интерфейс.
        val capabilities = connectivity.getNetworkCapabilities(network)
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
            AppLog.i(TAG, "publishDefaultNetwork: собственный VPN-интерфейс — сохраняем $lastRealInterfaceName")
            if (lastRealInterfaceName.isNotBlank()) {
                listener.updateDefaultInterface(lastRealInterfaceName, lastRealInterfaceIndex)
            }
            return
        }
        val name = knownInterfaceName ?: connectivity.getLinkProperties(network)?.interfaceName.orEmpty()
        val index = if (name.isBlank()) -1 else runCatching { JavaNetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
        if (name.isNotBlank()) {
            lastRealInterfaceName = name
            lastRealInterfaceIndex = index
        }
        listener.updateDefaultInterface(name, index)
    }

    override fun usePlatformInterfaceGetter(): Boolean = true

    override fun getInterfaces(): LibboxNetworkInterfaceIterator {
        val interfaces = runCatching { Collections.list(JavaNetworkInterface.getNetworkInterfaces()) }
            .getOrDefault(emptyList())
            .mapNotNull { networkInterface ->
                runCatching {
                    LibboxNetworkInterface().apply {
                        index = networkInterface.index
                        name = networkInterface.name
                        mtu = networkInterface.mtu
                        addresses = StringIteratorImpl(
                            networkInterface.interfaceAddresses.mapNotNull { interfaceAddress ->
                                val address = interfaceAddress.address?.hostAddress?.substringBefore('%') ?: return@mapNotNull null
                                "$address/${interfaceAddress.networkPrefixLength.toInt()}"
                            },
                        )
                    }
                }.getOrNull()
            }
        return NetworkInterfaceIteratorImpl(interfaces)
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun readWIFIState(): WIFIState? = null

    override fun clearDNSCache() {
        // The Android resolver owns the system DNS cache; the core creates fresh upstream sessions.
    }

    override fun writeLog(message: String?) {
        val line = message?.trim().orEmpty()
        if (line.isEmpty()) return
        // debug/trace уровни — только в файл ядра (logs/core.log), иначе AppLog превратится в кашу.
        // Уровень приходит с ANSI-кодами вида «[36mINFO[0m[0000]», поэтому сначала снимаем их.
        val plain = line.replace(Regex("\u001B\\[[;\\d]*m"), "")
        if (plain.startsWith("DEBUG") || plain.startsWith("TRACE")) return
        Log.i(TAG, plain)
        AppLog.i("Core", plain)
    }

    override fun onRevoke() {
        // Система отзывает VPN (пользователь отключил его в настройках, подключился другой VPN).
        // Идём тем же асинхронным путём: закрываем ядро в фоне, пишем STOPPED, гасим процесс.
        AppLog.i(TAG, "onRevoke: система отзывает VPN")
        stopConnection("onRevoke")
        super.onRevoke()
    }

    override fun onDestroy() {
        AppLog.i(TAG, "onDestroy: начало (ядро активно=${isProxyActive()}, остановка=${stopping.get()})")
        if (instance === this) instance = null
        cancelPendingWork()
        unregisterNetworkChangeMonitor()
        lastStoppedAtMs = SystemClock.elapsedRealtime()
        interfaceCallbacks.values.toList().forEach { callback ->
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
        interfaceCallbacks.clear()
        // onDestroy не должен блокировать главный поток: до 1 с на закрытие, остальное сделает
        // убийство процесса, которое уже запланировано остановкой.
        val hadCore = isProxyActive()
        val closed = closeNativeServiceBlocking(ON_DESTROY_TIMEOUT_MS)
        AppLog.i(TAG, "onDestroy: ядро закрыто успешно=$closed")
        if (hadCore && stateStore.state != ConnectionState.STOPPED && stateStore.state != ConnectionState.ERROR) {
            // Службу уничтожила система, а TUN всё равно закрыт — UI не должен остаться «Подключено».
            stateStore.update(ConnectionState.STOPPED, "VPN остановлен системой")
        }
        endpointResolver.shutdownNow()
        AppLog.i(TAG, "onDestroy: завершён")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(profileId: String? = requestedProfileId, contentText: String? = null): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Не используем loadSelectedProfile(): он сбрасывает requestedProfileId.
        val selected = runCatching {
            profileStore.loadProfiles().firstOrNull { it.id == (profileId ?: profileStore.selectedProfileId) }?.name
        }.getOrNull() ?: "Прокси активен"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_vibetun)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText ?: selected)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private class StringIteratorImpl(values: List<String>) : StringIterator {
        private val iterator = values.iterator()
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): String = iterator.next()
    }

    private class NetworkInterfaceIteratorImpl(values: List<LibboxNetworkInterface>) : LibboxNetworkInterfaceIterator {
        private val iterator = values.iterator()
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): LibboxNetworkInterface = iterator.next()
    }

    companion object {
        const val ACTION_START = "com.vibetun.app.action.START"
        const val ACTION_STOP = "com.vibetun.app.action.STOP"
        const val ACTION_TOGGLE = "com.vibetun.app.action.TOGGLE"

        /**
         * Аварийная остановка: служба сразу убивает свой процесс, не дожидаясь закрытия ядра.
         * Отправляется watchdog'ом [StopWatchdog], если STOPPED не пришёл за [STOP_TIMEOUT_MS].
         */
        const val ACTION_FORCE_KILL = "com.vibetun.app.action.FORCE_KILL"
        const val EXTRA_PROFILE_ID = "com.vibetun.app.extra.PROFILE_ID"
        private const val CHANNEL_ID = "vibetun_proxy"
        private const val NOTIFICATION_ID = 4201
        private const val NO_AMNEZIA_HANDLE = -1
        private const val SETTLE_DELAY_MS = 2500L
        private const val AWG_HANDSHAKE_TIMEOUT_MS = 30_000L

        /** Дебаунс автопереподключения при смене default-сети (Wi-Fi ↔ LTE). */
        private const val NETWORK_RECONNECT_DELAY_MS = 3500L

        /** Сколько ждём закрытия ядра, прежде чем закрыть его смертью процесса. */
        private const val STOP_TIMEOUT_MS = 4_000L

        /** onDestroy не должен блокировать главный поток дольше этого времени. */
        private const val ON_DESTROY_TIMEOUT_MS = 1_000L

        /** Отсрочка kill процесса: за это время новый ACTION_START успевает отменить убийство. */
        private const val PROCESS_KILL_DELAY_MS = 500L

        private const val STOP_NOTIFICATION_TEXT = "Отключаемся…"
        private const val STOP_THREAD_NAME = "VibeTun-Stop"
        private const val CLOSE_THREAD_NAME = "VibeTun-Close"
        internal const val FAMILY_AWG = "awg"
        internal const val FAMILY_LIBBOX = "libbox"

        internal fun familyOf(protocol: String): String =
            if (protocol.equals("amneziawg", ignoreCase = true)) FAMILY_AWG else FAMILY_LIBBOX
        private val IPV4_PATTERN = Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")
        private const val TAG = "VibeTunVpnService"

        /** Libbox.setup допустим только один раз за жизнь процесса — повторные вызовы роняли нативный код. */
        @Volatile
        private var libboxReady: Boolean = false

        /** Активный экземпляр сервиса в этом процессе (для protect() probe-сокетов). */
        @Volatile
        var instance: VibeTunVpnService? = null
            private set

        /** Профиль из последнего ACTION_START (передаётся явно, надёжнее prefs между процессами). */
        @Volatile
        private var requestedProfileId: String? = null
    }
}
