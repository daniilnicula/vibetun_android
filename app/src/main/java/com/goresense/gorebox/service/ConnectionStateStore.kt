package com.goresense.gorebox.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import com.goresense.gorebox.data.ConnectionState

class ConnectionStateStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("gorebox_connection", Context.MODE_PRIVATE)

    val state: ConnectionState
        get() = runCatching { ConnectionState.valueOf(prefs.getString(KEY_STATE, null) ?: "STOPPED") }
            .getOrDefault(ConnectionState.STOPPED)

    val message: String
        get() = prefs.getString(KEY_MESSAGE, "").orEmpty()

    fun update(state: ConnectionState, message: String = "") {
        prefs.edit()
            .putString(KEY_STATE, state.name)
            .putString(KEY_MESSAGE, message)
            .apply()
        appContext.sendBroadcast(
            Intent(ACTION_STATE_CHANGED).setPackage(appContext.packageName)
                .putExtra(EXTRA_STATE, state.name),
        )
        runCatching {
            TileService.requestListeningState(
                appContext,
                ComponentName(appContext, GoreBoxTileService::class.java),
            )
        }
    }

    companion object {
        const val ACTION_STATE_CHANGED = "com.goresense.gorebox.CONNECTION_STATE_CHANGED"
        const val EXTRA_STATE = "state"
        private const val KEY_STATE = "state"
        private const val KEY_MESSAGE = "message"
    }
}
