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
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import com.goresense.gorebox.MainActivity
import com.goresense.gorebox.R
import com.goresense.gorebox.data.AmneziaWgConfig
import com.goresense.gorebox.data.ConnectionState
import com.goresense.gorebox.data.GoreBoxStore
import com.goresense.gorebox.data.ProxyProfile
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

/** Android VpnService hosting the bundled sing-box/libbox data plane. */
class GoreBoxVpnService : VpnService(), PlatformInterface {
    private val stateStore by lazy { ConnectionStateStore(this) }
    private val profileStore by lazy { GoreBoxStore(this) }
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val interfaceCallbacks = mutableMapOf<InterfaceUpdateListener, ConnectivityManager.NetworkCallback>()

    @Volatile private var boxService: BoxService? = null
    @Volatile private var amneziaHandle: Int = NO_AMNEZIA_HANDLE
    @Volatile private var tunDescriptor: ParcelFileDescriptor? = null
    private val endpointResolver = Executors.newSingleThreadExecutor { task ->
        Thread(task, "GoreBox-AWG-DNS").apply { isDaemon = true }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> stopConnection()
            ACTION_TOGGLE -> if (isProxyActive()) stopConnection() else startConnection()
            ACTION_START -> startConnection()
            else -> stopSelf(startId)
        }
        return START_STICKY
    }

    private fun startConnection() {
        if (isProxyActive()) return
        if (!NativeCoreStatus.isIntegrated) {
            fail(NativeCoreStatus.message)
            return
        }
        if (prepare(this) != null) {
            fail("Сначала подтвердите VPN-подключение в GoreBox.")
            return
        }

        stateStore.update(ConnectionState.STARTING, "Подготовка VPN-интерфейса…")
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        try {
            val profile = loadSelectedProfile()
            if (profile.protocol.equals("amneziawg", ignoreCase = true)) {
                startAmneziaWg(profile)
            } else {
                startSingBox(profile)
            }
            stateStore.update(ConnectionState.RUNNING, "Прокси включён · ${profile.name}")
            Log.i(TAG, "Proxy core started for profile ${profile.id} (${profile.protocol})")
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            Log.e(TAG, "Failed to start proxy core", error)
            fail(error.message?.takeIf { it.isNotBlank() } ?: "Не удалось запустить прокси-ядро.")
        }
    }

    private fun startSingBox(profile: ProxyProfile) {
        val config = SingBoxConfigBuilder.build(
            profile = profile,
            selectedPackages = profileStore.selectedAppPackages,
            selectedAppsOnly = profileStore.selectedAppsOnly,
        )
        val basePath = filesDir.absolutePath
        Libbox.setup(basePath, basePath, cacheDir.absolutePath, false)
        val service = Libbox.newService(config, this)
        boxService = service
        service.start()
    }

    private fun startAmneziaWg(profile: ProxyProfile) {
        val config = AmneziaWgConfig.parse(profile.source)
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
        config.peerAllowedIps().forEach { cidr ->
            val (address, prefix) = parseIpPrefix(cidr)
            builder.addRoute(address, prefix)
            if (prefix == 0 && address is Inet4Address) hasIpv4DefaultRoute = true
            if (prefix == 0 && address is Inet6Address) hasIpv6DefaultRoute = true
        }
        if (!hasIpv4DefaultRoute) builder.allowFamily(OsConstants.AF_INET)
        if (!hasIpv6DefaultRoute) builder.allowFamily(OsConstants.AF_INET6)
        if (profileStore.selectedAppsOnly) {
            val selectedPackages = profileStore.selectedAppPackages.sorted()
            require(selectedPackages.isNotEmpty()) {
                "Для режима «Выбранные приложения» отметьте хотя бы одно приложение."
            }
            addApplications(builder, StringIteratorImpl(selectedPackages), allowed = true)
        }

        val establishedTun = checkNotNull(builder.establish()) { "Android did not establish the AmneziaWG VPN interface." }
        runCatching { tunDescriptor?.close() }
        tunDescriptor = establishedTun
        val nativeFd = ParcelFileDescriptor.dup(establishedTun.fileDescriptor).detachFd()
        val handle = AmneziaGoBackend.awgTurnOn("gorebox-awg", nativeFd, userspaceConfig)
        check(handle >= 0) { "Не удалось запустить AmneziaWG. Проверьте ключи, endpoint и параметры конфигурации." }
        amneziaHandle = handle

        val socketFds = listOf(
            AmneziaGoBackend.awgGetSocketV4(handle),
            AmneziaGoBackend.awgGetSocketV6(handle),
        ).filter { it >= 0 }.distinct()
        check(socketFds.isNotEmpty()) { "AmneziaWG не создал UDP-сокет для подключения." }
        socketFds.forEach { fd ->
            check(protect(fd)) { "Android не смог исключить транспорт AmneziaWG из VPN." }
        }
        Log.i(TAG, "AmneziaWG ${AmneziaGoBackend.awgVersion()} started for profile ${profile.id}")
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
        val selectedId = profileStore.selectedProfileId
        return profileStore.loadProfiles().firstOrNull { it.id == selectedId }
            ?: throw IllegalStateException("Профиль не найден. Откройте GoreBox и выберите профиль.")
    }

    private fun fail(message: String) {
        stateStore.update(ConnectionState.ERROR, message)
        closeNativeService()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopConnection() {
        closeNativeService()
        stateStore.update(ConnectionState.STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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

    private fun publishDefaultNetwork(
        listener: InterfaceUpdateListener,
        network: Network,
        knownInterfaceName: String? = null,
    ) {
        val name = knownInterfaceName ?: connectivity.getLinkProperties(network)?.interfaceName.orEmpty()
        val index = if (name.isBlank()) -1 else runCatching { JavaNetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
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
        if (line.isNotEmpty()) Log.i(TAG, line)
    }

    override fun onRevoke() {
        stopConnection()
        super.onRevoke()
    }

    override fun onDestroy() {
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

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val selected = runCatching { loadSelectedProfile().name }.getOrDefault("Прокси активен")
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
        private const val CHANNEL_ID = "gorebox_proxy"
        private const val NOTIFICATION_ID = 4201
        private const val NO_AMNEZIA_HANDLE = -1
        private val IPV4_PATTERN = Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")
        private const val TAG = "GoreBoxVpnService"
    }
}
