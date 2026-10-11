package com.goresense.gorebox.data

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Замер задержки профиля: TCP-подключение к серверу, [attempts] попыток,
 * в интерфейс уходит лучшее значение; null — сервер не ответил ни разу.
 */
object LatencyTester {
    fun ping(profile: ProxyProfile, attempts: Int = 3, timeoutMs: Int = 2500): Long? {
        val host = profile.server.trim()
        if (host.isBlank()) return null
        val port = profile.port.takeIf { it in 1..65535 } ?: defaultPort(profile.protocol)
        var best: Long? = null
        repeat(attempts) {
            val started = System.nanoTime()
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    if (elapsed >= 0 && (best == null || elapsed < best!!)) best = elapsed
                }
            } catch (_: Exception) {
                // попытка не считается: сервер/порт недоступны
            }
        }
        return best
    }

    private fun defaultPort(protocol: String): Int = when (protocol.lowercase()) {
        "http" -> 8080
        "socks" -> 1080
        "wireguard", "amneziawg" -> 51820
        else -> 443
    }
}
