package com.vibetun.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.vibetun.app.data.VibeTunStore

/**
 * Включает VPN после перезагрузки устройства, если он работал до неё
 * и в настройках включён автозапуск. Без подтверждённого разрешения
 * VPN не стартуем — Android покажет запрос при открытии приложения.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = VibeTunStore(context)
        if (!store.launchAtBoot || !store.proxyWasActive) return
        if (VpnService.prepare(context) != null) return
        val profile = store.loadProfiles().firstOrNull { it.id == store.selectedProfileId } ?: return
        runCatching { VpnServiceLauncher.start(context, profile) }
    }
}
