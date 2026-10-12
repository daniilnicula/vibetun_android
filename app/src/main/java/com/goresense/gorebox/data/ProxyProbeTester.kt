package com.goresense.gorebox.data

import android.content.Context
import com.goresense.gorebox.service.GoreBoxVpnService
import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.PlatformInterface
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

    @Volatile
    private var libboxReady: Boolean = false

    fun ping(context: Context, profile: ProxyProfile, attempts: Int = 3, timeoutMs: Int = 5000): Long? {
        val appContext = context.applicationContext
        val port = runCatching { ServerSocket(0).use { it.localPort } }.getOrNull()
            ?: return LatencyTester.ping(profile, attempts, timeoutMs)
        val config = SingBoxConfigBuilder.buildProbeConfig(profile, port)
            ?: return LatencyTester.ping(profile, attempts, timeoutMs)
        if (!ensureLibbox(appContext)) return LatencyTester.ping(profile, attempts, timeoutMs)

        val service: BoxService = try {
            Libbox.newService(config, ProbePlatformInterface())
        } catch (_: Exception) {
            return LatencyTester.ping(profile, attempts, timeoutMs)
        }
        var best: Long? = null
        try {
            service.start()
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
                        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                        if (elapsed >= 0 && (best == null || elapsed < best!!)) best = elapsed
                    }
                } catch (_: Exception) {
                    // попытка не считается
                }
                if (attempt < attempts - 1) Thread.sleep(300)
            }
        } catch (_: Exception) {
            // сервис не стартовал — best останется null
        } finally {
            runCatching { service.close() }
        }
        return best
    }

    private fun ensureLibbox(context: Context): Boolean {
        if (libboxReady) return true
        synchronized(this) {
            if (libboxReady) return true
            return runCatching {
                Libbox.setup(
                    context.filesDir.absolutePath,
                    context.filesDir.absolutePath,
                    context.cacheDir.absolutePath,
                    false,
                )
                libboxReady = true
                true
            }.getOrDefault(false)
        }
    }

    /** Минимальная реализация PlatformInterface для временного probe-сервиса (без TUN). */
    private class ProbePlatformInterface : PlatformInterface {
        override fun autoDetectInterfaceControl(fd: Int) {
            // Если VPN активен, сокет иначе уйдёт в собственный туннель.
            GoreBoxVpnService.instance?.protect(fd)
        }

        override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
        override fun openTun(options: TunOptions): Int =
            throw IllegalStateException("Probe-сервис не использует TUN.")

        override fun useProcFS(): Boolean = false
        override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String?, sourcePort: Int, destinationAddress: String?, destinationPort: Int): Int = -1
        override fun packageNameByUid(uid: Int): String = ""
        override fun uidByPackageName(packageName: String?): Int = -1
        override fun usePlatformDefaultInterfaceMonitor(): Boolean = false
        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {}
        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {}
        override fun usePlatformInterfaceGetter(): Boolean = false

        override fun getInterfaces(): NetworkInterfaceIterator = object : NetworkInterfaceIterator {
            override fun hasNext(): Boolean = false
            override fun next(): NetworkInterface = throw NoSuchElementException()
        }

        override fun underNetworkExtension(): Boolean = false
        override fun includeAllNetworks(): Boolean = false
        override fun readWIFIState(): WIFIState? = null
        override fun clearDNSCache() {}
        override fun writeLog(message: String?) {}
    }
}
