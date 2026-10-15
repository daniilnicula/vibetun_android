package com.vibetun.app.service

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.vibetun.app.MainActivity
import com.vibetun.app.data.ConnectionAction
import com.vibetun.app.data.ConnectionState
import com.vibetun.app.data.VibeTunStore
import com.vibetun.app.data.actionOnTap

class VibeTunTileService : TileService() {
    private val stateStore by lazy { ConnectionStateStore(this) }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        when (stateStore.state.actionOnTap()) {
            ConnectionAction.STOP -> {
                // Остановку выполняет служба (закрывает ядро и TUN) и только она пишет STOPPED:
                // плитка раньше сразу ставила STOPPED, поэтому «Выключен» показывался, пока
                // VPN-ключ в шторке и туннель были живы.
                stateStore.update(ConnectionState.STOPPING, "Отключаемся…")
                VpnServiceLauncher.stop(this)
                StopWatchdog.arm(this)
                updateTileState()
                return
            }
            // Пока идёт остановка или запуск, тап ничего не меняет: иначе stop и start гоняются,
            // и запоздавшая остановка убивает только что запущенную службу.
            ConnectionAction.IGNORE -> {
                updateTileState()
                return
            }
            ConnectionAction.CONNECT -> Unit
        }

        // VPN consent is an Activity result, so the tile hands off to VibeTun when consent
        // or a profile is needed. With an existing grant, it can start the service directly.
        val store = VibeTunStore(this)
        val selectedProfileExists = store.loadProfiles().any { it.id == store.selectedProfileId }
        if (!NativeCoreStatus.isIntegrated || !selectedProfileExists || VpnService.prepare(this) != null) {
            openApp(requestConnect = true)
        } else {
            store.loadProfiles().firstOrNull { it.id == store.selectedProfileId }
                ?.let { VpnServiceLauncher.start(this, it) }
            updateTileState()
        }
    }

    private fun openApp(requestConnect: Boolean) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_CONNECT_FROM_TILE, requestConnect)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                8,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        // Watchdog тайла: зависшее STARTING (служба умерла или не ответила) переводим в ошибку,
        // иначе плитка навсегда останется «недоступна».
        if (stateStore.state == ConnectionState.STARTING && stateStore.ageMs > ConnectionStateStore.START_TIMEOUT_MS) {
            stateStore.update(ConnectionState.ERROR, "VPN-служба не ответила. Пришлите логи (Настройки → Логи).")
            VpnServiceLauncher.stop(this)
            StopWatchdog.arm(this)
        }
        // Зависшее STOPPING: служба не подтвердила остановку за 6 с — гасим её процесс,
        // иначе VPN-ключ в шторке может остаться.
        StopWatchdog.forceStopIfStuck(this)
        val state = stateStore.state
        tile.state = when (state) {
            ConnectionState.RUNNING -> Tile.STATE_ACTIVE
            ConnectionState.STARTING, ConnectionState.STOPPING -> Tile.STATE_UNAVAILABLE
            ConnectionState.STOPPED, ConnectionState.ERROR -> Tile.STATE_INACTIVE
        }
        tile.label = "VibeTun"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !NativeCoreStatus.isIntegrated -> "Ядро не подключено"
                state == ConnectionState.RUNNING -> "Включён"
                state == ConnectionState.STOPPING -> "Отключаемся…"
                else -> "Выключен"
            }
            tile.stateDescription = stateStore.message.takeIf { it.isNotBlank() }
                ?: when (state) {
                    ConnectionState.RUNNING -> "Прокси включён"
                    ConnectionState.STOPPING -> "Отключаемся…"
                    else -> "Прокси выключен"
                }
        }
        tile.updateTile()
    }
}
