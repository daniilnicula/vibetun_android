package com.goresense.gorebox.service

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
import android.os.SystemClock
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import com.goresense.gorebox.MainActivity
import com.goresense.gorebox.R
import com.goresense.gorebox.data.AmneziaWgConfig
import com.goresense.gorebox.data.AppLog
import com.goresense.gorebox.data.ConnectionState
import com.goresense.gorebox.data.GoreBoxStore
import com.goresense.gorebox.data.ProxyProfile
import com.goresense.gorebox.data.RouteSetStore
import com.goresense.gorebox.data.SingBoxConfigBuilder
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

/**
 * Android VpnService hosting the bundled sing-box/libbox data plane.
 *
 * Два Go-runtime (libbox.so и libwg-go.so) не уживаются в одном процессе, поэтому у каждого
 * семейства ядра своя служба в своём процессе: эта (libbox, процесс `:vpn`) и
 * [GoreBoxAwgVpnService] (AmneziaWG, процесс `:vpn_awg`). Нужную службу выбирает
 * [VpnServiceLauncher] по протоколу профиля — убивать процесс «на лету» больше не нужно.
 */
open class GoreBoxVpnService : VpnService(), PlatformInterface {
    /** Семейство ядра, которое обслуживает эта служба. */
    protected open val engineFamily: String get() = FAMILY_LIBBOX

    private val stateStore by lazy { ConnectionStateStore(this) }
    private val profileStore by lazy { GoreBoxStore(this) }
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val interfaceCallbacks = mutableMapOf<InterfaceUpdateListener, ConnectivityManager.NetworkCallback>()

    @Volatile private var boxService: BoxService? = null
    @Volatile private var amneziaHandle: Int = NO_AMNEZIA_HANDLE
    @Volatile private var tunDescriptor: ParcelFileDescriptor? = null
    @Volatile private var lastStoppedAtMs: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val endpointResolver = Executors.newSingleThreadExecutor { task ->
        Thread(task, "GoreBox-AWG-DNS").apply { isDaemon = true }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        AppLog.i(TAG, "onStartCommand action=$action")
        // startForegroundService обязывает вызвать startForeground в течение 5 с. Поэтому поднимаем
        // уведомление сразу, ДО любых проверок (VPN-разрешение, ядро, профиль): иначе ранний fail()
        // даёт ForegroundServiceDidNotStartInTimeException.
        if (action == ACTION_START || action == ACTION_TOGGLE) {
            promoteToForeground(intent?.getStringExtra(EXTRA_PROFILE_ID))
        }
        when (action) {
            ACTION_STOP -> stopConnection()
            ACTION_TOGGLE -> if (isProxyActive()) stopConnection() else {
                scheduleStart()
                armStartWatchdog()
            }
            ACTION_START -> {
                // Явный старт при активном подключении = пользователь сменил профиль.
                // Раньше старт молча проглатывался (isProxyActive -> return), UI вис в
                // «Подключаемся…», а старый туннель продолжал работать.
                if (isProxyActive()) {
                    AppLog.i(TAG, "ACTION_START при активном подключении — перезапуск с выбранным профилем")
                    // Останавливаем только ядро, но НЕ саму службу: stopSelf() здесь приводил к onDestroy,
                    // который сбрасывал отложенный старт (removeCallbacksAndMessages) — UI вис в «Подключаемся…».
                    mainHandler.removeCallbacksAndMessages(null)
                    closeNativeService()
                    lastStoppedAtMs = SystemClock.elapsedRealtime()
                }
                requestedProfileId = intent?.getStringExtra(EXTRA_PROFILE_ID)?.takeIf { it.isNotBlank() }
                scheduleStart()
                armStartWatchdog()
            }
            else -> stopSelf(startId)
        }
        return START_STICKY
    }

    private fun promoteToForeground(profileId: String?) {
        createNotificationChannel()
        val notification = buildNotification(profileId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIFICATION_ID, notification)
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
        if (isProxyActive()) return
        val waitMs = SETTLE_DELAY_MS - (SystemClock.elapsedRealtime() - lastStoppedAtMs)
        if (waitMs > 0) {
            AppLog.i(TAG, "Пауза ${waitMs}мс после остановки перед стартом")
            mainHandler.postDelayed({ startConnection() }, waitMs)
        } else {
            startConnection()
        }
    }

    private fun startConnection() {
        if (isProxyActive()) return
        if (!NativeCoreStatus.isIntegrated) {
            AppLog.e(TAG, "startConnection: ядро не собрано — ${NativeCoreStatus.message}")
            fail(NativeCoreStatus.message)
            return
        }
        if (prepare(this) != null) {
            fail("Сначала подтвердите VPN-подключение в GoreBox.")
            return
        }

        stateStore.update(ConnectionState.STARTING, "Подготовка VPN-интерфейса…")

        val explicitRequest = requestedProfileId != null
        try {
            val profile = loadSelectedProfile()
            logStartHeader(profile)
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
                stateStore.update(ConnectionState.STARTING, "Ожидание handshake с сервером…")
            } else {
                startSingBox(profile)
                stateStore.update(ConnectionState.RUNNING, "Прокси включён · ${profile.name}")
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
            "=== старт: GoreBox $appVersion, Android API ${Build.VERSION.SDK_INT}, ABI ${Build.SUPPORTED_ABIS.joinToString()}, " +
                "протокол ${profile.protocol}, процесс ${engineFamily}, sing-box ${NativeCoreStatus.SING_BOX_VERSION}, AmneziaWG $awg",
        )
    }

    /** Последние 50 строк core.log (лог sing-box), чтобы причина падения попала в gorebox-*.log. */
    private fun coreLogTail(): String = runCatching {
        File(filesDir, "logs/core.log").takeIf { it.isFile }?.readLines()?.takeLast(50)?.joinToString("\n").orEmpty()
    }.getOrDefault("")

    private fun startSingBox(profile: ProxyProfile) {
        AppLog.i(TAG, "startSingBox: сборка конфигурации…")
        val config = SingBoxConfigBuilder.build(
            profile = profile,
            selectedPackages = profileStore.selectedAppPackages,
            selectedAppsOnly = profileStore.selectedAppsOnly,
            directPackages = profileStore.directAppPackages,
            routeRules = RouteSetStore(this).load(activeRouteSet()),
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
        synchronized(GoreBoxVpnService::class.java) {
            if (libboxReady) return
            AppLog.i(TAG, "startSingBox: Libbox.setup(basePath=$basePath, однократно)")
            Libbox.setup(basePath, basePath, cacheDir.absolutePath, false)
            libboxReady = true
        }
    }

    private fun activeRouteSet(): String {
        val store = RouteSetStore(this)
        val sets = store.listSets()
        return sets.firstOrNull() ?: ""
    }

    private fun startAmneziaWg(profile: ProxyProfile) {
        AppLog.i(TAG, "startAmneziaWg: разбор конфигурации…")
        val config = AmneziaWgConfig.parse(profile.source)
        // Параметры обфускации в журнал: при «tx растёт, rx=0» сразу видно,
        // какие Jc/S/H-значения реально дошли до ядра (ключи не логируются).
        AppLog.i(TAG, "startAmneziaWg: параметры ${config.debugDescription()}")
        val userspaceConfig = config.userspaceConfig(::resolveAwgEndpoint)
        val builder = Builder()
            .setSession("GoreBox · AmneziaWG")
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
        if (profileStore.selectedAppsOnly) {
            val selectedPackages = profileStore.selectedAppPackages.sorted()
            require(selectedPackages.isNotEmpty()) {
                "Для режима «Выбранные приложения» отметьте хотя бы одно приложение."
            }
            addApplications(builder, StringIteratorImpl(selectedPackages), allowed = true)
        } else {
            // GoreBox не должен попадать в собственный VPN: транспортный UDP-сокет
            // AmneziaWG создаётся при первой отправке и в этот момент ещё не защищён
            // protect() — без исключения первый handshake уходит в собственный TUN
            // и до сервера не доходит (rx=0 при растущем tx).
            builder.addDisallowedApplication(packageName)
        }
        // приложения с маршрутом DIRECT идут мимо туннеля AmneziaWG
        val directPackages = profileStore.directAppPackages.sorted()
        if (directPackages.isNotEmpty()) {
            addApplications(builder, StringIteratorImpl(directPackages), allowed = false)
        }

        val establishedTun = checkNotNull(builder.establish()) { "Android did not establish the AmneziaWG VPN interface." }
        runCatching { tunDescriptor?.close() }
        tunDescriptor = establishedTun
        val nativeFd = ParcelFileDescriptor.dup(establishedTun.fileDescriptor).detachFd()
        AppLog.i(TAG, "startAmneziaWg: TUN интерфейс создан, запуск awgTurnOn…")
        val handle = AmneziaGoBackend.awgTurnOn("gorebox-awg", nativeFd, userspaceConfig)
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
                        stateStore.update(ConnectionState.RUNNING, "Прокси включён · ${profile.name}")
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
        }.apply { isDaemon = true; name = "GoreBox-AWG-Handshake" }.start()
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
        val profiles = profileStore.loadProfiles()
        val selectedId = requested ?: profileStore.selectedProfileId
        return profiles.firstOrNull { it.id == selectedId }
            ?: throw IllegalStateException("Профиль не найден. Откройте GoreBox и выберите профиль.")
    }

    private fun fail(message: String) {
        AppLog.e(TAG, "fail: $message")
        mainHandler.removeCallbacksAndMessages(null)
        stateStore.update(ConnectionState.ERROR, message)
        profileStore.proxyWasActive = false
        closeNativeService()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        lastStoppedAtMs = SystemClock.elapsedRealtime()
    }

    private fun stopConnection() {
        AppLog.i(TAG, "stopConnection")
        mainHandler.removeCallbacksAndMessages(null)
        closeNativeService()
        profileStore.proxyWasActive = false
        stateStore.update(ConnectionState.STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        lastStoppedAtMs = SystemClock.elapsedRealtime()
    }

    private fun closeNativeService() {
        val currentBoxService = boxService
        boxService = null
        runCatching { currentBoxService?.close() }
            .onFailure { Log.w(TAG, "Error while stopping sing-box", it) }

        val currentAmneziaHandle = amneziaHandle
        amneziaHandle = NO_AMNEZIA_HANDLE
        if (currentAmneziaHandle != NO_AMNEZIA_HANDLE) {
            runCatching { AmneziaGoBackend.awgTurnOff(currentAmneziaHandle) }
                .onFailure { Log.w(TAG, "Error while stopping AmneziaWG", it) }
        }

        runCatching { tunDescriptor?.close() }
            .onFailure { Log.w(TAG, "Error while closing VPN descriptor", it) }
        tunDescriptor = null
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        check(protect(fd)) { "Android could not protect an outbound socket from the VPN." }
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun openTun(options: TunOptions): Int {
        check(prepare(this) == null) { "Android VPN permission is missing or revoked." }
        val mtu = options.mtu.coerceIn(576, 9000)
        val builder = Builder()
            .setSession("GoreBox")
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
            addApplications(builder, options.includePackage, allowed = true)
            addApplications(builder, options.excludePackage, allowed = false)
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

    private fun addApplications(builder: Builder, packages: StringIterator, allowed: Boolean) {
        val packageNames = mutableListOf<String>()
        while (packages.hasNext()) packageNames += packages.next()
        if (packageNames.isEmpty()) return
        if (allowed && packageNames.any { it == packageName }) {
            throw IllegalArgumentException("GoreBox не может быть включён в собственный VPN-маршрут.")
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
        stopConnection()
        super.onRevoke()
    }

    override fun onDestroy() {
        AppLog.i(TAG, "onDestroy")
        if (instance === this) instance = null
        mainHandler.removeCallbacksAndMessages(null)
        lastStoppedAtMs = SystemClock.elapsedRealtime()
        interfaceCallbacks.values.toList().forEach { callback ->
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
        interfaceCallbacks.clear()
        closeNativeService()
        endpointResolver.shutdownNow()
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

    private fun buildNotification(profileId: String? = requestedProfileId): Notification {
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
            .setSmallIcon(R.drawable.ic_tile_gorebox)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(selected)
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
        const val ACTION_START = "com.goresense.gorebox.action.START"
        const val ACTION_STOP = "com.goresense.gorebox.action.STOP"
        const val ACTION_TOGGLE = "com.goresense.gorebox.action.TOGGLE"
        const val EXTRA_PROFILE_ID = "com.goresense.gorebox.extra.PROFILE_ID"
        private const val CHANNEL_ID = "gorebox_proxy"
        private const val NOTIFICATION_ID = 4201
        private const val NO_AMNEZIA_HANDLE = -1
        private const val SETTLE_DELAY_MS = 2500L
        private const val AWG_HANDSHAKE_TIMEOUT_MS = 30_000L
        internal const val FAMILY_AWG = "awg"
        internal const val FAMILY_LIBBOX = "libbox"

        internal fun familyOf(protocol: String): String =
            if (protocol.equals("amneziawg", ignoreCase = true)) FAMILY_AWG else FAMILY_LIBBOX
        private val IPV4_PATTERN = Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")
        private const val TAG = "GoreBoxVpnService"

        /** Libbox.setup допустим только один раз за жизнь процесса — повторные вызовы роняли нативный код. */
        @Volatile
        private var libboxReady: Boolean = false

        /** Активный экземпляр сервиса в этом процессе (для protect() probe-сокетов). */
        @Volatile
        var instance: GoreBoxVpnService? = null
            private set

        /** Профиль из последнего ACTION_START (передаётся явно, надёжнее prefs между процессами). */
        @Volatile
        private var requestedProfileId: String? = null
    }
}
