package com.goresense.gorebox.service

import android.content.Context
import android.content.Intent
import android.os.Build
import com.goresense.gorebox.data.GoreBoxStore
import com.goresense.gorebox.data.ProxyProfile

/**
 * Единая точка запуска/остановки VPN-служб. Выбирает службу по протоколу профиля:
 * AmneziaWG → [GoreBoxAwgVpnService] (процесс :vpn_awg), всё остальное → [GoreBoxVpnService] (процесс :vpn).
 */
object VpnServiceLauncher {
    private val allServices: List<Class<out GoreBoxVpnService>> =
        listOf(GoreBoxVpnService::class.java, GoreBoxAwgVpnService::class.java)

    fun serviceClassFor(protocol: String): Class<out GoreBoxVpnService> =
        if (GoreBoxVpnService.familyOf(protocol) == GoreBoxVpnService.FAMILY_AWG) {
            GoreBoxAwgVpnService::class.java
        } else {
            GoreBoxVpnService::class.java
        }

    /** Запускает подключение с профилем; службу другого семейства предварительно останавливает. */
    fun start(context: Context, profile: ProxyProfile) {
        val target = serviceClassFor(profile.protocol)
        allServices.filter { it != target }.forEach { context.stopService(Intent(context, it)) }
        val intent = Intent(context, target)
            .setAction(GoreBoxVpnService.ACTION_START)
            .putExtra(GoreBoxVpnService.EXTRA_PROFILE_ID, profile.id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /** Останавливает обе службы (неработающая просто игнорируется) и сбрасывает флаг автозапуска. */
    fun stop(context: Context) {
        allServices.forEach { context.stopService(Intent(context, it)) }
        GoreBoxStore(context).proxyWasActive = false
    }
}
