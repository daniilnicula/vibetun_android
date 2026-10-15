package com.vibetun.app.data

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Простой файловый лог приложения. У каждого процесса свой файл, чтобы три процесса
 * (UI, `:vpn`, `:vpn_awg`) не гонялись за один файл и ротация не портила записи:
 *   filesDir/logs/vibetun-main.log  — UI-процесс
 *   filesDir/logs/vibetun-vpn.log   — служба sing-box (процесс :vpn)
 *   filesDir/logs/vibetun-awg.log   — служба AmneziaWG (процесс :vpn_awg)
 * При превышении лимита файл переименовывается в .old. Каждая строка содержит имя процесса и PID.
 */
object AppLog {
    private val lock = Any()
    private val timestampFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var processName: String = "main"

    private const val MAX_BYTES = 512 * 1024

    /** Имена файлов, которые отправляются разработчику («Поделиться логами»). */
    private val SHARED_BASENAMES = listOf("vibetun-main.log", "vibetun-vpn.log", "vibetun-awg.log", "vibetun.log", "core.log")

    fun init(context: Context) {
        synchronized(lock) {
            if (logFile == null) {
                val dir = File(context.filesDir, "logs")
                dir.mkdirs()
                val suffix = currentProcessSuffix()
                processName = suffix
                logFile = File(dir, "vibetun-$suffix.log")
            }
        }
    }

    /** Файл текущего процесса. */
    fun file(context: Context): File {
        init(context)
        return logFile ?: File(File(context.filesDir, "logs"), "vibetun-main.log")
    }

    /**
     * Суффикс текущего процесса: "main" (UI, плитка), "vpn" (`:vpn`), "awg" (`:vpn_awg`).
     * Используется в именах лог-файлов и как владелец состояния соединения.
     */
    fun processSuffix(context: Context): String {
        init(context)
        return processName
    }

    /** Все существующие лог-файлы (всех процессов, включая ротированные) для отправки. */
    fun shareableFiles(context: Context): List<File> {
        val dir = File(context.filesDir, "logs")
        return SHARED_BASENAMES
            .flatMap { name -> listOf(name, "$name.old") }
            .map { name -> File(dir, name) }
            .filter { it.isFile && it.length() > 0 }
    }

    fun i(tag: String, message: String) = write("I", tag, message, null)

    fun e(tag: String, message: String, error: Throwable? = null) = write("E", tag, message, error)

    private fun write(level: String, tag: String, message: String, error: Throwable?) {
        val logcatMessage = if (error != null) "$message\n${Log.getStackTraceString(error)}" else message
        Log.println(if (level == "E") Log.ERROR else Log.INFO, tag, logcatMessage)
        val file = logFile ?: return
        synchronized(lock) {
            runCatching {
                if (file.exists() && file.length() > MAX_BYTES) rotate(file)
                val line = buildString {
                    append(timestampFormat.format(Date()))
                    append(" [").append(processName).append(':').append(Process.myPid()).append("] ")
                    append(level).append('/').append(tag).append(": ").append(message).append('\n')
                    if (error != null) append(Log.getStackTraceString(error)).append('\n')
                }
                file.appendText(line)
            }
        }
    }

    private fun rotate(file: File) {
        val old = File(file.parentFile, file.name + ".old")
        old.delete()
        file.renameTo(old)
    }

    /** Имя процесса берём из /proc: Application.getProcessName() доступен только с API 28, а minSdk = 26. */
    private fun currentProcessSuffix(): String {
        val cmdline = runCatching {
            File("/proc/self/cmdline").readBytes().toString(Charsets.UTF_8).substringBefore('\u0000')
        }.getOrDefault("")
        return when {
            cmdline.endsWith(":vpn_awg") -> "awg"
            cmdline.endsWith(":vpn") -> "vpn"
            else -> "main"
        }
    }
}
