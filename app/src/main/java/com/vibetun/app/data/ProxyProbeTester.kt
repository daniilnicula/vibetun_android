package com.vibetun.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.vibetun.app.service.LibboxCommandServerHandler
import com.vibetun.app.service.LibboxPlatformInterface
import com.vibetun.app.service.VibeTunVpnService
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Честный замер задержки профиля через само ядро sing-box: поднимается временный
 * сервис с локальным mixed-inbound (127.0.0.1) и исходящим outbound профиля,
 * затем через этот прокси запрашивается HTTP-204. Так измеряются и UDP-протоколы
 * (hysteria2/tuic), на которые TCP-ping никогда не получал ответа.
 * AmneziaWG ядром sing-box не поддерживается — там остаётся старый TCP-ping.
 */
object ProxyProbeTester {
    private const val PROBE_TARGET = "http://www.gstatic.com/generate_204"
    private val probeLock = Any()

    @Volatile
    private var libboxReady: Boolean = false

    fun ping(context: Context, profile: ProxyProfile, attempts: Int = 3, timeoutMs: Int = 5000): Long? =
        synchronized(probeLock) { pingLocked(context, profile, attempts, timeoutMs) }

    /** Групповые кнопки приходят параллельно, но временные ядра запускаем строго по одному. */
    private fun pingLocked(context: Context, profile: ProxyProfile, attempts: Int, timeoutMs: Int): Long? {
        val appContext = context.applicationContext
        val port = runCatching { ServerSocket(0).use { it.localPort } }.getOrNull()
            ?: return LatencyTester.ping(profile, attempts, timeoutMs)
        val config = SingBoxConfigBuilder.buildProbeConfig(profile, port)
            ?: return LatencyTester.ping(profile, attempts, timeoutMs)
        if (!ensureLibbox(appContext)) return LatencyTester.ping(profile, attempts, timeoutMs)

        val service: CommandServer = try {
            Libbox.newCommandServer(LibboxCommandServerHandler(), ProbePlatformInterface())
        } catch (_: Exception) {
            return LatencyTester.ping(profile, attempts, timeoutMs)
        }
        var best: Long? = null
        // Сокеты probe-сервиса создаются в ЭТОМ процессе, а не в :vpn, где есть protect().
        // Если VPN активен, без привязки они уйдут в собственный TUN и замер не получится.
        val boundNetwork = bindProcessToPhysicalNetwork(appContext)
        try {
            // gRPC command.sock для локальной пробы не нужен: startOrReloadService работает напрямую.
            service.startOrReloadService(config, OverrideOptions())
            repeat(attempts) { attempt ->
                val started = System.nanoTime()
                try {
                    val connection = URL(PROBE_TARGET).openConnection(
                        Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)),
                    ) as HttpURLConnection
                    connection.connectTimeout = timeoutMs
                    connection.readTimeout = timeoutMs
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Connection", "close")
                    val code = connection.responseCode
                    connection.disconnect()
                    if (code in 200..399) {
                        val elapsed = maxOf(1L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                        if (best == null || elapsed < best!!) best = elapsed
                    }
                } catch (_: Exception) {
                    // попытка не считается
                }
                if (attempt < attempts - 1) Thread.sleep(300)
            }
        } catch (_: Exception) {
            // сервис не стартовал — best останется null
        } finally {
            // Сначала останавливаем BoxService и освобождаем probe-cache.db, затем CommandServer.
            runCatching { service.closeService() }
            runCatching { service.close() }
            if (boundNetwork) {
                runCatching { appContext.getSystemService(ConnectivityManager::class.java)?.bindProcessToNetwork(null) }
            }
        }
        return best
    }

    /** Привязываем процесс к сети без VPN-транспорта (Wi-Fi/сотовая/Ethernet); true — если получилось. */
    private fun bindProcessToPhysicalNetwork(context: Context): Boolean = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching false
        val physical = connectivity.allNetworks.firstOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    )
        } ?: return@runCatching false
        connectivity.bindProcessToNetwork(physical)
    }.getOrDefault(false)

    private fun ensureLibbox(context: Context): Boolean {
        if (libboxReady) return true
        synchronized(this) {
            if (libboxReady) return true
            return runCatching {
                Libbox.setup(SetupOptions().apply {
                    basePath = context.filesDir.absolutePath
                    workingPath = context.filesDir.absolutePath
                    tempPath = context.cacheDir.absolutePath
                    fixAndroidStack = false
                    logMaxLines = 3000
                    debug = false
                })
                libboxReady = true
                true
            }.getOrDefault(false)
        }
    }

    /** Минимальная реализация PlatformInterface для временного probe-сервиса (без TUN). */
    private class ProbePlatformInterface : LibboxPlatformInterface {
        override fun autoDetectInterfaceControl(fd: Int) {
            // Если VPN активен, сокет иначе уйдёт в собственный туннель.
            VibeTunVpnService.instance?.protect(fd)
        }

        override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
        override fun openTun(options: TunOptions): Int =
            throw IllegalStateException("Probe-сервис не использует TUN.")

        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {}
        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {}

        override fun getInterfaces(): NetworkInterfaceIterator = object : NetworkInterfaceIterator {
            override fun hasNext(): Boolean = false
            override fun next(): NetworkInterface = throw NoSuchElementException()
        }

        override fun underNetworkExtension(): Boolean = false
        override fun includeAllNetworks(): Boolean = false
        override fun readWIFIState(): WIFIState? = null
        override fun clearDNSCache() {}
    }
}
