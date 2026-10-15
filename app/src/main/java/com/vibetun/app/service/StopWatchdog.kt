package com.vibetun.app.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.vibetun.app.data.AppLog
import com.vibetun.app.data.ConnectionState

/**
 * Страховка остановки: «Отключаемся…» не должно превращаться в вечное состояние.
 *
 * Если служба не подтвердила остановку за [ConnectionStateStore.STOP_TIMEOUT_MS], UI или плитка
 * просят службу убить свой процесс ([VpnServiceLauncher.forceKill]) и фиксируют STOPPED сами —
 * смерть процесса закрывает все fd TUN, поэтому VPN-ключ в шторке исчезает в любом случае.
 *
 * Единственное место, где UI-процесс пишет STOPPED: обычный путь всегда завершает сама служба.
 */
object StopWatchdog {
    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    private val timeout = Runnable {
        val context = appContext ?: return@Runnable
        forceStopIfStuck(context)
    }

    /** Взводит таймер после запроса остановки (повторный вызов перезапускает его). */
    fun arm(context: Context) {
        appContext = context.applicationContext
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, ConnectionStateStore.STOP_TIMEOUT_MS)
    }

    fun cancel() {
        handler.removeCallbacks(timeout)
    }

    /**
     * Если состояние висит в STOPPING дольше таймаута — принудительная остановка.
     * Вызывается и таймером, и при обновлении плитки (служба могла «зависнуть» до её открытия).
     */
    fun forceStopIfStuck(context: Context): Boolean {
        val stateStore = ConnectionStateStore(context)
        if (stateStore.state != ConnectionState.STOPPING) return false
        if (stateStore.ageMs <= ConnectionStateStore.STOP_TIMEOUT_MS) return false
        AppLog.e(
            TAG,
            "принудительная остановка: служба не подтвердила STOPPED за " +
                "${ConnectionStateStore.STOP_TIMEOUT_MS}мс — убиваем процесс VPN-службы",
        )
        VpnServiceLauncher.forceKill(context)
        stateStore.update(ConnectionState.STOPPED, "Остановлено принудительно")
        return true
    }

    private const val TAG = "StopWatchdog"
}
