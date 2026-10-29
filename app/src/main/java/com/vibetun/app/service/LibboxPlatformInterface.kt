package com.vibetun.app.service

import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator

/**
 * Безопасная базовая реализация API libbox 1.14 для VPN и локального probe.
 * Root-only возможности официального клиента в VibeTun не используются.
 */
interface LibboxPlatformInterface : PlatformInterface {
    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int,
    ): ConnectionOwner = error("Connection-owner lookup is unavailable in this service.")

    override fun sendNotification(notification: Notification) {
        // VibeTun показывает собственное постоянное VPN-уведомление.
    }

    override fun cancelNotification(identifier: String, typeID: Int) = Unit

    override fun startNeighborMonitor(listener: NeighborUpdateListener?) {
        error("Neighbor monitoring is not supported without root access.")
    }

    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit

    override fun registerMyInterface(name: String?) = Unit

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() {
        error("Platform shell is not supported by VibeTun.")
    }

    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int,
    ): ShellSession = error("Platform shell is not supported by VibeTun.")

    override fun lookupUser(username: String?): PlatformUser =
        error("Platform users are not supported by VibeTun.")

    override fun lookupSFTPServer(): String = error("SFTP lookup is not supported by VibeTun.")

    override fun readSystemSSHHostKey(): String = error("System SSH keys are unavailable on Android.")

    override fun tailscaleHostname(): String = "VibeTun"

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions?): BridgeSession =
        error("Platform bridge is not supported without root access.")
}
