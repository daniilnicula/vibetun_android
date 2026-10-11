package com.goresense.gorebox.service

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.goresense.gorebox.MainActivity
import com.goresense.gorebox.data.ConnectionState
import com.goresense.gorebox.data.GoreBoxStore

class GoreBoxTileService : TileService() {
    private val stateStore by lazy { ConnectionStateStore(this) }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val state = stateStore.state
        if (state == ConnectionState.RUNNING) {
            startVpnService(GoreBoxVpnService.ACTION_STOP)
            stateStore.update(ConnectionState.STOPPED)
            updateTileState()
            return
        }

        // VPN consent is an Activity result, so the tile hands off to GoreBox when consent
        // or a profile is needed. With an existing grant, it can start the service directly.
        val store = GoreBoxStore(this)
        val selectedProfileExists = store.loadProfiles().any { it.id == store.selectedProfileId }
        if (!NativeCoreStatus.isIntegrated || !selectedProfileExists || VpnService.prepare(this) != null) {
            openApp(requestConnect = true)
        } else {
            startVpnService(GoreBoxVpnService.ACTION_TOGGLE)
            updateTileState()
        }
    }

    private fun startVpnService(action: String) {
        val intent = Intent(this, GoreBoxVpnService::class.java).setAction(action)
        if (action == GoreBoxVpnService.ACTION_STOP) {
            startService(intent)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
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
        val state = stateStore.state
        tile.state = when (state) {
            ConnectionState.RUNNING -> Tile.STATE_ACTIVE
            ConnectionState.STARTING -> Tile.STATE_UNAVAILABLE
            ConnectionState.STOPPED, ConnectionState.ERROR -> Tile.STATE_INACTIVE
        }
        tile.label = "GoreBox"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !NativeCoreStatus.isIntegrated -> "Ядро не подключено"
                state == ConnectionState.RUNNING -> "Включён"
                else -> "Выключен"
            }
            tile.stateDescription = stateStore.message.takeIf { it.isNotBlank() }
                ?: if (state == ConnectionState.RUNNING) "Прокси включён" else "Прокси выключен"
        }
        tile.updateTile()
    }
}
