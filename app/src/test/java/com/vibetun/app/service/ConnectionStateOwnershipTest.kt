package com.vibetun.app.service

import com.vibetun.app.data.ConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила записи состояния между процессами. Состояние соединения пишут три процесса (UI/плитка,
 * `:vpn`, `:vpn_awg`), и запоздалая остановка одного семейства ядра не должна перетирать состояние
 * уже поднятого подключения другого: иначе UI показывал бы «Отключено» при живом туннеле, а
 * watchdog остановки убил бы только что запущенную службу.
 */
class ConnectionStateOwnershipTest {
    @Test
    fun `запоздалая остановка не перетирает новое подключение`() {
        // :vpn уже владеет состоянием RUNNING, а :vpn_awg дописывает свою остановку.
        assertFalse(mayWriteConnectionState(ConnectionState.RUNNING, "vpn", ConnectionState.STOPPING, "awg"))
        assertFalse(mayWriteConnectionState(ConnectionState.RUNNING, "vpn", ConnectionState.STOPPED, "awg"))
        assertFalse(mayWriteConnectionState(ConnectionState.STARTING, "vpn", ConnectionState.STOPPING, "awg"))
    }

    @Test
    fun `служба продолжает своё состояние и подтверждает запрос UI`() {
        assertTrue(mayWriteConnectionState(ConnectionState.RUNNING, "vpn", ConnectionState.STOPPED, "vpn"))
        assertTrue(
            mayWriteConnectionState(
                ConnectionState.STOPPING,
                ConnectionStateStore.OWNER_UI,
                ConnectionState.STOPPED,
                "vpn",
            ),
        )
    }

    @Test
    fun `после терминального состояния владение свободно`() {
        assertTrue(mayWriteConnectionState(ConnectionState.STOPPED, "awg", ConnectionState.STARTING, "vpn"))
        assertTrue(mayWriteConnectionState(ConnectionState.ERROR, "vpn", ConnectionState.STARTING, "awg"))
        assertTrue(mayWriteConnectionState(null, null, ConnectionState.STARTING, "vpn"))
    }

    @Test
    fun `запросы UI и ошибки не теряются из-за гонки`() {
        assertTrue(
            mayWriteConnectionState(
                ConnectionState.RUNNING,
                "vpn",
                ConnectionState.STOPPING,
                ConnectionStateStore.OWNER_UI,
            ),
        )
        assertTrue(mayWriteConnectionState(ConnectionState.RUNNING, "vpn", ConnectionState.ERROR, "awg"))
    }
}
