package com.vibetun.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.vibetun.app.data.AppLog

/**
 * Сторож закрытия приложения: живёт в основном процессе и получает onTaskRemoved,
 * когда пользователь смахивает VibeTun из списка недавних приложений.
 *
 * Состояние VPN мы не запоминаем: при полном закрытии приложения VPN выключается,
 * а не продолжает жить и не восстанавливается при следующем запуске/перезагрузке.
 */
class AppTaskWatcherService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.i(TAG, "Приложение выгружено из недавних — выключаем VPN и ничего не запоминаем")
        runCatching { VpnServiceLauncher.stop(applicationContext) }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private companion object {
        const val TAG = "AppTaskWatcher"
    }
}
