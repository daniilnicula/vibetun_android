package com.vibetun.app.service

import android.content.Context
import android.content.Intent
import android.os.Build
import com.vibetun.app.data.VibeTunStore
import com.vibetun.app.data.ProxyProfile

/**
 * Единая точка запуска/остановки VPN-служб. Выбирает службу по протоколу профиля:
 * AmneziaWG → [VibeTunAwgVpnService] (процесс :vpn_awg), всё остальное → [VibeTunVpnService] (процесс :vpn).
 */
object VpnServiceLauncher {
    private val allServices: List<Class<out VibeTunVpnService>> =
        listOf(VibeTunVpnService::class.java, VibeTunAwgVpnService::class.java)

    fun serviceClassFor(protocol: String): Class<out VibeTunVpnService> =
        if (VibeTunVpnService.familyOf(protocol) == VibeTunVpnService.FAMILY_AWG) {
            VibeTunAwgVpnService::class.java
        } else {
            VibeTunVpnService::class.java
        }

    /** Запускает подключение с профилем; службу другого семейства предварительно останавливает. */
    fun start(context: Context, profile: ProxyProfile) {
        val target = serviceClassFor(profile.protocol)
        allServices.filter { it != target }.forEach { context.stopService(Intent(context, it)) }
        val intent = Intent(context, target)
            .setAction(VibeTunVpnService.ACTION_START)
            .putExtra(VibeTunVpnService.EXTRA_PROFILE_ID, profile.id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /** Останавливает обе службы (неработающая просто игнорируется) и сбрасывает флаг автозапуска. */
    fun stop(context: Context) {
        allServices.forEach { context.stopService(Intent(context, it)) }
        VibeTunStore(context).proxyWasActive = false
    }
}
