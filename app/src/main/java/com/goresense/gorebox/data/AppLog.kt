package com.goresense.gorebox.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Простой файловый лог приложения. Пишется в filesDir/logs/gorebox.log,
 * при превышении лимита старый файл переименовывается в .old.
 * Нужен, чтобы с тестового устройства можно было прислать лог без adb.
 */
object AppLog {
    private val lock = Any()
    private val timestampFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    private const val MAX_BYTES = 512 * 1024

    fun init(context: Context) {
        synchronized(lock) {
            if (logFile == null) {
                val dir = File(context.filesDir, "logs")
                dir.mkdirs()
                logFile = File(dir, "gorebox.log")
            }
        }
    }

    fun file(context: Context): File {
        init(context)
        return File(File(context.filesDir, "logs"), "gorebox.log")
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
                    append(' ').append(level).append('/').append(tag).append(": ").append(message).append('\n')
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
}
