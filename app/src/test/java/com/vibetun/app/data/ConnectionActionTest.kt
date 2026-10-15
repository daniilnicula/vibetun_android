package com.vibetun.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Правило тапа по кнопке/плитке. Главное здесь — гонка stop → start: пока служба закрывает ядро
 * и TUN (STOPPING), новый запуск запрещён, иначе запоздавшая остановка убивает только что
 * запущенную службу, и UI висит в «Подключение» до таймаута.
 */
class ConnectionActionTest {
    @Test
    fun `работающий VPN выключается тапом`() {
        assertEquals(ConnectionAction.STOP, ConnectionState.RUNNING.actionOnTap())
    }

    @Test
    fun `во время остановки и запуска тап игнорируется`() {
        assertEquals(ConnectionAction.IGNORE, ConnectionState.STOPPING.actionOnTap())
        assertEquals(ConnectionAction.IGNORE, ConnectionState.STARTING.actionOnTap())
    }

    @Test
    fun `выключенный и ошибочный VPN подключается тапом`() {
        assertEquals(ConnectionAction.CONNECT, ConnectionState.STOPPED.actionOnTap())
        assertEquals(ConnectionAction.CONNECT, ConnectionState.ERROR.actionOnTap())
    }
}
