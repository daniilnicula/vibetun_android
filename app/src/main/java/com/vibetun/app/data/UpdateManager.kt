package com.vibetun.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Проверка и скачивание обновлений из GitHub Releases. Репозиторий публичный —
 * токен не нужен. Установка — стандартным системным установщиком (ACTION_VIEW +
 * FileProvider): Android ставит APK поверх текущей версии, данные приложения
 * (профили, настройки) сохраняются. Системный диалог подтверждения неизбежен —
 * это штатное ограничение Android, обходить его не пытаемся.
 */
object UpdateManager {
    const val REPO = "daniilnicula/vibetun_android"
    private const val APK_ASSET_NAME = "VibeTun.apk"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    data class Release(
        val tag: String,
        val notes: String,
        val apkUrl: String,
        val apkSizeBytes: Long,
    )

    /** Последний опубликованный релиз. Бросает [IOException] с пояснением при сбое. */
    fun fetchLatest(): Release {
        val body = httpGet(API_LATEST)
        val obj = JSONObject(body)
        val assets = obj.getJSONArray("assets")
        var apkUrl: String? = null
        var apkSize = 0L
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            if (asset.optString("name") == APK_ASSET_NAME) {
                apkUrl = asset.getString("browser_download_url")
                apkSize = asset.optLong("size")
            }
        }
        val url = apkUrl ?: throw IOException("В релизе нет файла $APK_ASSET_NAME.")
        return Release(
            tag = obj.getString("tag_name"),
            notes = obj.optString("body"),
            apkUrl = url,
            apkSizeBytes = apkSize,
        )
    }

    /**
     * Тег релиза «26.10.15v5» → числовой код ((YY·100+MM)·100+DD)·1000+N.
     * Монотонно растёт между днями и версиями внутри дня, влезает в int.
     * Неизвестный формат → null (сравнение тогда идёт по строкам).
     */
    fun tagToVersionCode(tag: String): Long? {
        val match = Regex("^(\\d{2})\\.(\\d{2})\\.(\\d{2})v(\\d{1,3})$").find(tag.trim()) ?: return null
        val (yy, mm, dd, n) = match.destructured
        return (((yy.toLong() * 100 + mm.toLong()) * 100 + dd.toLong()) * 1000 + n.toLong())
    }

    fun isUpdateAvailable(latestTag: String, currentVersionName: String): Boolean {
        val latestCode = tagToVersionCode(latestTag)
        val currentCode = tagToVersionCode(currentVersionName)
        return if (latestCode != null && currentCode != null) latestCode > currentCode
        else latestTag.trim() != currentVersionName.trim()
    }

    /** Скачивает APK в cacheDir/updates/; [onProgress] получает процент 0..100. */
    fun download(context: Context, url: String, onProgress: (Int) -> Unit): File {
        val dest = File(context.cacheDir, "updates/VibeTun-update.apk")
        dest.parentFile?.mkdirs()
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("Сервер вернул HTTP $code.")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress((done * 100 / total).toInt().coerceIn(0, 100))
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (dest.length() == 0L) throw IOException("Скачан пустой файл.")
        onProgress(100)
        return dest
    }

    /** Стандартная установка: отдаём файл системному установщику. */
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        // GitHub API просит User-Agent.
        connection.setRequestProperty("User-Agent", "VibeTun-Updater")
        return try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("Сервер вернул HTTP $code.")
            connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }
}
