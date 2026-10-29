package com.vibetun.app.service

import android.util.Log
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.SystemProxyStatus

/**
 * Локальный CommandServer используется только как жизненный цикл sing-box.
 * Внешние команды UI VibeTun через него не отправляются, поэтому системный proxy,
 * reload и debug-операции здесь не требуются.
 */
class LibboxCommandServerHandler : CommandServerHandler {
    override fun serviceStop() = Unit

    override fun serviceReload() = Unit

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()

    override fun setSystemProxyEnabled(enabled: Boolean) = Unit

    override fun triggerNativeCrash() {
        Log.w("LibboxCommand", "Native crash request ignored")
    }

    override fun writeDebugMessage(message: String?) {
        if (!message.isNullOrBlank()) Log.d("LibboxCommand", message)
    }

    override fun connectSSHAgent(): Int = -1
}
