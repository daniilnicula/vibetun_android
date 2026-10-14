package com.vibetun.app.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import com.vibetun.app.data.AppLog
import com.vibetun.app.data.ConnectionState
import java.io.File

/**
 * Состояние соединения. Основное хранилище — маленький файл с атомарной записью
 * (tmp + rename): SharedPreferences кэшируются per-process, поэтому между процессами
 * (UI и :vpn) prefs протухают, а файл всегда читается с диска. Broadcast по-прежнему
 * служит для мгновенных обновлений UI, prefs оставлены как запасной вариант.
 */
class ConnectionStateStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("vibetun_connection", Context.MODE_PRIVATE)

    private val stateFile: File get() = File(appContext.filesDir, "connection_state.txt")

    val state: ConnectionState
        get() = readFromFile()?.first
            ?: runCatching { ConnectionState.valueOf(prefs.getString(KEY_STATE, null) ?: "STOPPED") }
                .getOrDefault(ConnectionState.STOPPED)

    val message: String
        get() = readFromFile()?.second ?: prefs.getString(KEY_MESSAGE, "").orEmpty()

    /** Сколько миллисекунд состояние не менялось (для watchdog зависшего STARTING). */
    val ageMs: Long
        get() = if (stateFile.isFile) System.currentTimeMillis() - stateFile.lastModified() else Long.MAX_VALUE

    fun update(state: ConnectionState, message: String = "") {
        val previous = runCatching { readFromFile()?.first }.getOrNull()
        AppLog.i("State", "${previous ?: "—"} -> $state${if (message.isNotBlank()) " ($message)" else ""}")
        runCatching {
            val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
            tmp.writeText(state.name + "\n" + message)
            if (!tmp.renameTo(stateFile)) {
                stateFile.delete()
                tmp.renameTo(stateFile)
            }
        }
        prefs.edit()
            .putString(KEY_STATE, state.name)
            .putString(KEY_MESSAGE, message)
            .apply()
        appContext.sendBroadcast(
            Intent(ACTION_STATE_CHANGED).setPackage(appContext.packageName)
                .putExtra(EXTRA_STATE, state.name)
                .putExtra(EXTRA_MESSAGE, message),
        )
        runCatching {
            TileService.requestListeningState(
                appContext,
                ComponentName(appContext, VibeTunTileService::class.java),
            )
        }
    }

    private fun readFromFile(): Pair<ConnectionState, String>? = runCatching {
        if (!stateFile.isFile) return@runCatching null
        val lines = stateFile.readLines()
        val parsedState = ConnectionState.valueOf(lines.firstOrNull().orEmpty())
        parsedState to lines.drop(1).joinToString("\n")
    }.getOrNull()

    companion object {
        const val ACTION_STATE_CHANGED = "com.vibetun.app.CONNECTION_STATE_CHANGED"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"
        /** Сколько ждём перехода из STARTING, прежде чем признать подключение зависшим. */
        const val START_TIMEOUT_MS = 60_000L
        private const val KEY_STATE = "state"
        private const val KEY_MESSAGE = "message"
    }
}
