package com.vibetun.app.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import com.vibetun.app.data.AppLog
import com.vibetun.app.data.ConnectionState
import java.io.File

/**
 * Правила записи состояния между процессами. UI и плитка — голос пользователя, их запрос пишется
 * всегда. Служба пишет, если состояние принадлежит ей, было записано UI (она подтверждает запрос)
 * или предыдущее состояние терминальное (STOPPED/ERROR — владение свободно). Ошибку пишем всегда:
 * она не должна теряться из-за гонки.
 *
 * Без этих правил запоздалая остановка одного семейства ядра (`:vpn_awg` закрывается секундами)
 * перетирала бы состояние уже поднятого подключения другого: UI показывал бы «Отключено» при
 * живом туннеле, а watchdog остановки убил бы только что запущенную службу.
 */
internal fun mayWriteConnectionState(
    previousState: ConnectionState?,
    previousOwner: String?,
    incoming: ConnectionState,
    writer: String,
): Boolean {
    if (previousState == null) return true
    if (writer == ConnectionStateStore.OWNER_UI) return true
    if (previousOwner == null) return true
    if (previousOwner == ConnectionStateStore.OWNER_UI) return true
    if (previousOwner == writer) return true
    if (incoming == ConnectionState.ERROR) return true
    return previousState == ConnectionState.STOPPED || previousState == ConnectionState.ERROR
}

/**
 * Состояние соединения. Основное хранилище — маленький файл с атомарной записью
 * (tmp + rename): SharedPreferences кэшируются per-process, поэтому между процессами
 * (UI и :vpn) prefs протухают, а файл всегда читается с диска. Broadcast по-прежнему
 * служит для мгновенных обновлений UI, prefs оставлены как запасной вариант.
 *
 * Файл пишут три процесса (UI/плитка, `:vpn`, `:vpn_awg`), поэтому в нём хранится и автор записи.
 * Без этого запоздалая запись остановки одного семейства ядра перетирала бы состояние уже
 * поднятого подключения другого семейства: UI показывал бы «Отключено» при живом туннеле
 * (а watchdog остановки ещё и убил бы только что запущенную службу).
 */
class ConnectionStateStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("vibetun_connection", Context.MODE_PRIVATE)

    /** Кто пишет состояние: "main" (UI и плитка), "vpn" или "awg". */
    private val owner: String = AppLog.processSuffix(appContext)

    private val stateFile: File get() = File(appContext.filesDir, "connection_state.txt")

    val state: ConnectionState
        get() = readFromFile()?.state
            ?: runCatching { ConnectionState.valueOf(prefs.getString(KEY_STATE, null) ?: "STOPPED") }
                .getOrDefault(ConnectionState.STOPPED)

    val message: String
        get() = readFromFile()?.message ?: prefs.getString(KEY_MESSAGE, "").orEmpty()

    /** Сколько миллисекунд состояние не менялось (для watchdog зависшего STARTING/STOPPING). */
    val ageMs: Long
        get() = if (stateFile.isFile) System.currentTimeMillis() - stateFile.lastModified() else Long.MAX_VALUE

    /** Процесс, записавший текущее состояние ("main"/"vpn"/"awg"); null — запись старого формата. */
    val recordedBy: String?
        get() = readFromFile()?.owner

    fun update(state: ConnectionState, message: String = "") {
        val previous = readFromFile()
        AppLog.i("State", "${previous?.state ?: "—"} -> $state${if (message.isNotBlank()) " ($message)" else ""}")
        if (!mayWriteConnectionState(previous?.state, previous?.owner, state, owner)) {
            AppLog.i(
                "State",
                "запись $state от «$owner» отклонена: состояние ${previous?.state} записано «${previous?.owner}»",
            )
            return
        }
        runCatching {
            val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
            tmp.writeText(state.name + "\n" + message.replace('\n', ' ') + "\n" + OWNER_PREFIX + owner + "\n")
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
    private data class Snapshot(val state: ConnectionState, val message: String, val owner: String?)

    private fun readFromFile(): Snapshot? = runCatching {
        if (!stateFile.isFile) return@runCatching null
        val lines = stateFile.readLines()
        val parsedState = ConnectionState.valueOf(lines.firstOrNull().orEmpty())
        val ownerLine = lines.getOrNull(2)?.takeIf { it.startsWith(OWNER_PREFIX) }
        Snapshot(
            state = parsedState,
            message = lines.getOrNull(1).orEmpty(),
            owner = ownerLine?.removePrefix(OWNER_PREFIX)?.takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    companion object {
        const val ACTION_STATE_CHANGED = "com.vibetun.app.CONNECTION_STATE_CHANGED"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"
        /** Сколько ждём перехода из STARTING, прежде чем признать подключение зависшим. */
        const val START_TIMEOUT_MS = 60_000L
        /** Сколько ждём подтверждения остановки (STOPPING -> STOPPED), прежде чем гасить процесс. */
        const val STOP_TIMEOUT_MS = 6_000L
        /** Владелец записи — UI/плитка (их запрос никогда не отклоняется). */
        const val OWNER_UI = "main"
        private const val OWNER_PREFIX = "owner="
        private const val KEY_STATE = "state"
        private const val KEY_MESSAGE = "message"
    }
}
