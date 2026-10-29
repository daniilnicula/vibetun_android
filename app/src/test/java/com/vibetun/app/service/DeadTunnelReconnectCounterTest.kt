package com.vibetun.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeadTunnelReconnectCounterTest {
    @Test
    fun `исчерпывает попытки без сброса при запуске ядра`() {
        val counter = DeadTunnelReconnectCounter(maxAttempts = 3)

        assertEquals(1, counter.nextAttemptOrNull())
        assertEquals(2, counter.nextAttemptOrNull())
        assertEquals(3, counter.nextAttemptOrNull())
        assertNull(counter.nextAttemptOrNull())
    }

    @Test
    fun `подтверждённо живой туннель начинает новую серию попыток`() {
        val counter = DeadTunnelReconnectCounter(maxAttempts = 2)

        assertEquals(1, counter.nextAttemptOrNull())
        assertEquals(2, counter.nextAttemptOrNull())
        counter.reset()

        assertEquals(1, counter.nextAttemptOrNull())
    }
}
