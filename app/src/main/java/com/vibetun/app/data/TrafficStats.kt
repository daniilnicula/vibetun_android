package com.vibetun.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File

/**
 * Счётчики трафика через VPN-интерфейс.
 *
 * upload — байты, принятые интерфейсом (трафик устройства в туннель),
 * download — байты, отправленные интерфейсом (трафик из туннеля в устройство).
 */
data class TrafficSnapshot(
    val uploadBytes: Long,
    val downloadBytes: Long,
    val interfaceName: String,
)

/**
 * Трафик через VPN-интерфейс устройства.
 *
 * Считаем по счётчикам самого TUN: ядро sing-box работает в userspace и не отдаёт
 * счётчики наружу, а /proc/net/dev доступен и другим процессам приложения. Значения
 * накапливаются с момента поднятия интерфейса, поэтому при новом подключении (TUN
 * создаётся заново) счётчик начинается с нуля.
 */
object TrafficStats {
    fun read(context: Context): TrafficSnapshot? = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = manager.allNetworks.firstOrNull { candidate ->
            val capabilities = manager.getNetworkCapabilities(candidate) ?: return@firstOrNull false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } ?: return null
        val interfaceName = manager.getLinkProperties(network)?.interfaceName?.takeIf { it.isNotBlank() }
            ?: return null
        val counters = readInterfaceCounters(interfaceName) ?: return null
        TrafficSnapshot(
            uploadBytes = counters.first,
            downloadBytes = counters.second,
            interfaceName = interfaceName,
        )
    }.getOrNull()

    /**
     * Счётчики интерфейса из /proc/net/dev.
     *
     * В Android у `java.net.NetworkInterface` нет `getBytesReceived()`/`getBytesSent()`
     * (это методы из JDK 14, в AOSP/libcore их нет), поэтому читаем счётчики ядра
     * напрямую. Строка формата:
     * `  tun0: rx_bytes rx_packets ... tx_bytes tx_packets ...`
     * — принято байт 8 полей, передано байт начинается с девятого.
     */
    private fun readInterfaceCounters(interfaceName: String): Pair<Long, Long>? {
        val dev = File("/proc/net/dev")
        if (!dev.canRead()) return null
        dev.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) return@forEach
                if (line.substring(0, colon).trim() != interfaceName) return@forEach
                val fields = line.substring(colon + 1).trim().split(' ', '\t').filter { it.isNotBlank() }
                if (fields.size < 9) return@forEach
                val received = fields[0].toLongOrNull() ?: return@forEach
                val transmitted = fields[8].toLongOrNull() ?: return@forEach
                return received to transmitted
            }
        }
        return null
    }

    /** «37.6 МБ» — как в Windows-версии. */
    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "—"
        val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (unit == 0) {
            "${value.toInt()} ${units[unit]}"
        } else {
            String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
        }
    }

    /** «00:28:43» — длительность сеанса. */
    fun formatDuration(millis: Long): String {
        if (millis < 0) return "—"
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}
