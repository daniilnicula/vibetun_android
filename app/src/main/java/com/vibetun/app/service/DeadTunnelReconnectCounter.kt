package com.vibetun.app.service

/**
 * Бюджет автопереподключений одной серии сбоев.
 * Сбрасывается только явным действием пользователя или подтверждённо живым туннелем,
 * но не самим фактом запуска ядра: запуск ещё не означает, что трафик проходит.
 */
internal class DeadTunnelReconnectCounter(private val maxAttempts: Int) {
    private var attempts = 0

    @Synchronized
    fun reset() {
        attempts = 0
    }

    @Synchronized
    fun nextAttemptOrNull(): Int? {
        if (attempts >= maxAttempts) return null
        attempts++
        return attempts
    }
}
