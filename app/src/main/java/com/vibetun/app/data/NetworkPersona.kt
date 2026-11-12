package com.vibetun.app.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Сетевой облик приложения: User-Agent, которым подписаны исходящие HTTP-запросы
 * (подписки, проверка обновлений). Аналог NetworkPersonaService Windows-версии.
 *
 * Режимы: auto — свежий облик раз в сутки из официальных источников (Google для Chrome,
 * Mozilla для Firefox); chrome/firefox/edge — фиксированный браузер, версия по тому же
 * механизму; app — честный облик приложения без маскировки. Результат кэшируется в
 * filesDir/ua.json; офлайн или сбой источника — кэш, затем константа из сборки.
 * Сбои молчат: облик некритичен.
 */
object NetworkPersona {
    private const val CACHE_FILE = "ua.json"
    private const val REFRESH_INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val FETCH_TIMEOUT_MS = 10_000

    // Запасной вариант на случай полного офлайна.
    private const val FALLBACK_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    // Шаблоны под актуальную мажорную версию браузера (%1\$d — мажорная версия).
    private const val CHROME_TEMPLATE =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/%1\$d.0.0.0 Mobile Safari/537.36"
    private const val FIREFOX_TEMPLATE =
        "Mozilla/5.0 (Android 13; Mobile; rv:%1\$d.0) Gecko/%1\$d.0 Firefox/%1\$d.0"
    // Edge синхронизирует мажорную версию с Chromium — версию берём из того же источника, что у Chrome.
    private const val EDGE_TEMPLATE =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/%1\$d.0.0.0 Mobile Safari/537.36 EdgA/%1\$d.0.0.0"

    const val MODE_AUTO = "auto"
    const val MODE_CHROME = "chrome"
    const val MODE_FIREFOX = "firefox"
    const val MODE_EDGE = "edge"
    const val MODE_APP = "app"
    val MODES = listOf(MODE_AUTO, MODE_CHROME, MODE_FIREFOX, MODE_EDGE, MODE_APP)

    private const val APP_UA = "VibeTun-Android/1.0"

    @Volatile private var cacheFile: File? = null
    @Volatile private var initialized = false

    /** Текущий режим: auto | chrome | firefox | edge | app. */
    @Volatile var mode: String = MODE_AUTO
        private set

    /** Текущий User-Agent. До первой подгрузки — константа из сборки. */
    @Volatile var userAgent: String = FALLBACK_UA
        private set

    /** Когда облик в последний раз обновлялся из сети (UTC мс); null — ещё ни разу. */
    @Volatile var refreshedAtUtcMs: Long? = null
        private set

    /** Точка входа при старте приложения: применяем сохранённый режим и тянем свежую версию, если протухла. */
    @Synchronized
    fun init(context: Context, savedMode: String) {
        cacheFile = File(context.filesDir, CACHE_FILE)
        applyMode(savedMode)
        initialized = true
        if (mode == MODE_APP) return
        val cached = readCache()
        if (cached != null && buildForMode(mode, cached)) {
            refreshedAtUtcMs = cached.atUtcMs
            if (System.currentTimeMillis() - cached.atUtcMs < REFRESH_INTERVAL_MS) return
        }
        refreshInBackground()
    }

    /** Режим изменён в настройках: применяем сразу и фоном тянем версию под новый режим. */
    @Synchronized
    fun setMode(newMode: String) {
        applyMode(newMode)
        if (!initialized) return
        val cached = readCache()
        if (cached != null && buildForMode(mode, cached)) refreshedAtUtcMs = cached.atUtcMs
        refreshInBackground()
    }

    private fun applyMode(raw: String) {
        mode = if (raw in MODES) raw else MODE_AUTO
        if (mode == MODE_APP) {
            userAgent = APP_UA
            refreshedAtUtcMs = null
        }
    }

    /** Кнопка «Обновить сейчас» в настройках. Вызов НЕ с UI-потока. True — облик обновлён из сети. */
    fun refreshNowBlocking(): Boolean {
        if (mode == MODE_APP) return false
        return refreshCore()
    }

    private fun refreshInBackground() {
        Thread({ runCatching { refreshCore() } }, "VibeTun-Persona").apply { isDaemon = true }.start()
    }

    /** «Обновлён: …» для настроек; в режиме без маскировки — пусто. */
    fun ageText(): String {
        val at = refreshedAtUtcMs ?: return ""
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        fmt.timeZone = TimeZone.getDefault()
        return "Обновлён: ${fmt.format(Date(at))}"
    }

    // auto: Google, затем Mozilla; фиксированный режим — только свой источник.
    private fun refreshCore(): Boolean {
        try {
            if (mode == MODE_AUTO || mode == MODE_CHROME || mode == MODE_EDGE) {
                val major = fetchChromeMajor()
                if (major != null) return apply("chrome", major)
            }
            if (mode == MODE_AUTO || mode == MODE_FIREFOX) {
                val major = fetchFirefoxMajor()
                if (major != null) return apply("firefox", major)
            }
        } catch (_: Exception) {
            // облик некритичен: остаёмся на прежнем значении
        }
        // сеть/источники недоступны: поднимаем последний кэш (константа — запасной вариант)
        val cached = readCache()
        if (cached != null && buildForMode(mode, cached)) refreshedAtUtcMs = cached.atUtcMs
        return false
    }

    // Chrome: официальный API истории версий Google, stable-канал под Android.
    // Edge синхронизирует мажорную версию с Chromium — источник подходит и ему.
    private fun fetchChromeMajor(): Int? {
        val text = httpGet(
            "https://versionhistory.googleapis.com/v1/chrome/platforms/android/channels/stable/versions",
        ) ?: return null
        val versions = JSONObject(text).optJSONArray("versions") ?: return null
        var best: Int? = null
        for (i in 0 until versions.length()) {
            val major = parseMajor(versions.optJSONObject(i)?.optString("version"))
            if (major != null && (best == null || major > best)) best = major
        }
        return best
    }

    // Firefox: официальный JSON с версиями Mozilla (поле — последний релиз под Android).
    private fun fetchFirefoxMajor(): Int? {
        val text = httpGet("https://product-details.mozilla.org/1.0/mobile_versions.json")
            ?: httpGet("https://product-details.mozilla.org/1.0/firefox_versions.json")
            ?: return null
        val json = JSONObject(text)
        return parseMajor(
            json.optString("version").ifBlank { json.optString("LATEST_FIREFOX_VERSION") },
        )
    }

    private fun httpGet(url: String): String? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = FETCH_TIMEOUT_MS
        connection.readTimeout = FETCH_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        // и сами запросы версий идут под текущим обликом
        connection.setRequestProperty("User-Agent", userAgent)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun parseMajor(version: String?): Int? {
        val head = version?.substringBefore('.')?.trim().orEmpty()
        return head.toIntOrNull()
    }

    private class PersonaCache(val atUtcMs: Long, val source: String, val major: Int)

    private fun apply(source: String, major: Int): Boolean {
        val cache = PersonaCache(System.currentTimeMillis(), source, major)
        if (!buildForMode(mode, cache)) return false
        writeCache(cache)
        return true
    }

    // Версия из кэша подходит режиму, если её источник совпадает с источником режима;
    // в auto подходит любая; edge строится по версии Chromium.
    private fun buildForMode(targetMode: String, cache: PersonaCache): Boolean {
        if (cache.major <= 0) return false
        val sourceOk = when (targetMode) {
            MODE_CHROME, MODE_EDGE -> cache.source == "chrome"
            MODE_FIREFOX -> cache.source == "firefox"
            else -> true
        }
        if (!sourceOk) return false
        userAgent = when (targetMode) {
            MODE_FIREFOX -> String.format(Locale.US, FIREFOX_TEMPLATE, cache.major)
            MODE_EDGE -> String.format(Locale.US, EDGE_TEMPLATE, cache.major)
            else -> String.format(Locale.US, CHROME_TEMPLATE, cache.major)
        }
        refreshedAtUtcMs = cache.atUtcMs
        return true
    }

    private fun readCache(): PersonaCache? = runCatching {
        val file = cacheFile ?: return null
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        val major = json.optInt("major", 0)
        if (major <= 0) return null
        PersonaCache(
            atUtcMs = json.optLong("atUtcMs", 0L),
            source = json.optString("source", "chrome"),
            major = major,
        )
    }.getOrNull()

    private fun writeCache(cache: PersonaCache) {
        runCatching {
            val file = cacheFile ?: return
            val json = JSONObject()
                .put("atUtcMs", cache.atUtcMs)
                .put("source", cache.source)
                .put("major", cache.major)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) file.writeText(json.toString())
        }
    }
}
