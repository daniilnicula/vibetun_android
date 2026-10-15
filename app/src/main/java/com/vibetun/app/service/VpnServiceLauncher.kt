package com.vibetun.app.service

import android.content.Context
import android.content.Intent
import android.os.Build
import com.vibetun.app.data.AppLog
import com.vibetun.app.data.VibeTunStore
import com.vibetun.app.data.ProxyProfile

/**
 * Единая точка запуска/остановки VPN-служб. Выбирает службу по протоколу профиля:
 * AmneziaWG → [VibeTunAwgVpnService] (процесс :vpn_awg), всё остальное → [VibeTunVpnService] (процесс :vpn).
 *
 * Остановка выполняется командой [VibeTunVpnService.ACTION_STOP] самой службе, а не `stopService()`:
 * `stopService()` из другого процесса не срабатывает или срабатывает поздно (подтверждено логами:
 * UI писал STOPPED, а в `:vpn` не было ни stopConnection, ни onDestroy, ядро продолжало работать).
 * Единственный владелец жизненного цикла — служба: она закрывает ядро и TUN, пишет STOPPED
 * и гасит свой процесс.
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

    /** Запускает подключение с профилем; службе другого семейства предварительно отправляет ACTION_STOP. */
    fun start(context: Context, profile: ProxyProfile) {
        val target = serviceClassFor(profile.protocol)
        allServices.filter { it != target }.forEach { cls ->
            // Раньше здесь был stopService(): чужая служба в другом процессе не получала команду,
            // её ядро оставалось живым вместе с TUN. Теперь останавливается явным ACTION_STOP.
            send(context, cls, VibeTunVpnService.ACTION_STOP)
        }
        send(context, target, VibeTunVpnService.ACTION_START, profile.id)
    }

    /**
     * Просит обе службы остановиться (неработающая просто ничего не закроет).
     * STOPPED придёт от службы: UI и плитка не должны писать его сами.
     */
    fun stop(context: Context) {
        // Запись синхронная (файл + commit): процесс :vpn / :vpn_awg будет убит сразу после
        // закрытия ядра, и apply() может не успеть — тогда после рестарта VPN поднялся бы заново.
        VibeTunStore(context).proxyWasActive = false
        allServices.forEach { cls -> send(context, cls, VibeTunVpnService.ACTION_STOP) }
    }

    /**
     * Принудительная остановка: служба сразу убивает свой процесс, и ядро ОС закрывает все fd TUN
     * (включая дубликат, который держит libbox). Страховка на случай, если `close()` завис.
     */
    fun forceKill(context: Context) {
        allServices.forEach { cls -> send(context, cls, VibeTunVpnService.ACTION_FORCE_KILL) }
    }

    /**
     * Доставка команды службе. startForegroundService обязателен: службы живут в отдельных
     * процессах, а команда может прийти, когда приложение в фоне (плитка, watchdog).
     * Если система запретила поднять foreground-службу, пробуем обычный startService,
     * и лишь в самом конце — stopService() (onDestroy закрывает ядро, хоть и ненадёжно).
     */
    private fun send(
        context: Context,
        cls: Class<out VibeTunVpnService>,
        action: String,
        profileId: String? = null,
    ): Boolean {
        val intent = Intent(context, cls).setAction(action)
        if (profileId != null) intent.putExtra(VibeTunVpnService.EXTRA_PROFILE_ID, profileId)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { context.startForegroundService(intent) }
                .onSuccess { return true }
                .onFailure { AppLog.e(TAG, "startForegroundService($action → ${cls.simpleName}) не удался", it) }
        }
        runCatching { context.startService(intent) }
            .onSuccess { return true }
            .onFailure { AppLog.e(TAG, "startService($action → ${cls.simpleName}) не удался", it) }

        if (action == VibeTunVpnService.ACTION_STOP) {
            AppLog.e(TAG, "ACTION_STOP не доставлен — пробуем stopService() как последнее средство")
            runCatching { context.stopService(Intent(context, cls)) }
                .onFailure { AppLog.e(TAG, "stopService(→ ${cls.simpleName}) не удался", it) }
        }
        return false
    }

    private const val TAG = "VpnServiceLauncher"
}
