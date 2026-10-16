package com.vibetun.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Small private on-device store; profile credentials never leave the app.
 *
 * Все ключи, которые читают процессы службы (:vpn / :vpn_awg), живут в файле с
 * атомарной записью (tmp + rename), а не в SharedPreferences: MODE_MULTI_PROCESS
 * между процессами не гарантирует ни свежесть чтений, ни целостность записей —
 * commit() из процесса службы пишет на диск весь свой протухший кэш и затирает
 * свежие данные UI (по логам так исчезал профиль, добавленный импортом подписки:
 * «в store 3 профилей», а выбранного id среди них нет). Файл читается заново при
 * каждом обращении — оба процесса всегда видят одно и то же состояние.
 *
 * UI-only ключи (тема, акцент, онбординг) остаются в prefs.
 */
@Suppress("DEPRECATION")
class VibeTunStore(context: Context) {
    private val appContext = context.applicationContext

    private val preferences = appContext.getSharedPreferences("vibetun", Context.MODE_MULTI_PROCESS)

    private val storeFile: File get() = File(appContext.filesDir, STORE_FILE)

    private fun readFileJson(): JSONObject = runCatching {
        if (!storeFile.isFile) return JSONObject()
        JSONObject(storeFile.readText())
    }.getOrDefault(JSONObject())

    private fun writeFileJson(json: JSONObject) {
        runCatching {
            val tmp = File(storeFile.parentFile, storeFile.name + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(storeFile)) {
                storeFile.delete()
                tmp.renameTo(storeFile)
            }
        }
    }

    private var migrated = false

    /** Одноразовая миграция: файл — источник истины, при первом обращении
     *  заполняем его актуальными значениями из prefs старых сборок. */
    private fun migrateIfNeeded() {
        if (migrated) return
        migrated = true
        if (storeFile.isFile) return
        val json = JSONObject()
        json.put(KEY_PROFILES, preferences.getString(KEY_PROFILES, "[]") ?: "[]")
        json.put(KEY_SELECTED_PROFILE, preferences.getString(KEY_SELECTED_PROFILE, "").orEmpty())
        json.put(KEY_SELECTED_APPS, JSONArray(preferences.getStringSet(KEY_SELECTED_APPS, emptySet()).orEmpty().sorted()))
        json.put(KEY_SELECTED_APPS_ONLY, preferences.getBoolean(KEY_SELECTED_APPS_ONLY, false))
        json.put(KEY_DIRECT_APPS, JSONArray(preferences.getStringSet(KEY_DIRECT_APPS, emptySet()).orEmpty().sorted()))
        json.put(KEY_ACTIVE_ROUTE_SET, preferences.getString(KEY_ACTIVE_ROUTE_SET, "").orEmpty())
        writeFileJson(json)
    }

    fun loadProfiles(): List<ProxyProfile> {
        migrateIfNeeded()
        val raw = readFileJson().optString(KEY_PROFILES, "[]")
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val name = item.optString("name").takeIf { it.isNotBlank() } ?: "Профиль"
                    add(
                        ProxyProfile(
                            id = item.optString("id").ifBlank { "profile-$index" },
                            name = name,
                            protocol = item.optString("protocol", "tunnel"),
                            server = item.optString("server"),
                            port = item.optInt("port", 0),
                            source = item.optString("source"),
                            latencyMs = if (item.has("latencyMs") && !item.isNull("latencyMs")) item.optLong("latencyMs") else null,
                            favorite = item.optBoolean("favorite", false),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveProfiles(profiles: List<ProxyProfile>) {
        migrateIfNeeded()
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("protocol", profile.protocol)
                    .put("server", profile.server)
                    .put("port", profile.port)
                    .put("source", profile.source)
                    .put("latencyMs", profile.latencyMs)
                    .put("favorite", profile.favorite),
            )
        }
        val json = readFileJson().put(KEY_PROFILES, array)
        writeFileJson(json)
    }

    var selectedProfileId: String
        get() {
            migrateIfNeeded()
            return readFileJson().optString(KEY_SELECTED_PROFILE, "")
        }
        set(value) {
            migrateIfNeeded()
            writeFileJson(readFileJson().put(KEY_SELECTED_PROFILE, value))
        }

    var darkTheme: Boolean
        get() = preferences.getBoolean(KEY_DARK_THEME, true)
        set(value) { preferences.edit().putBoolean(KEY_DARK_THEME, value).apply() }

    // Маршрутизацию по приложениям читает процесс службы (:vpn/:vpn_awg) при старте ядра —
    // только файловое хранилище (см. заголовок класса): иначе per-app правила либо «не
    // применялись» (протухший снапшот), либо затирались чужим commit().

    var selectedAppPackages: Set<String>
        get() {
            migrateIfNeeded()
            return readFileJson().optJSONArray(KEY_SELECTED_APPS)?.let { array ->
                (0 until array.length()).mapTo(mutableSetOf()) { array.optString(it) }
            }.orEmpty()
        }
        set(value) {
            migrateIfNeeded()
            writeFileJson(readFileJson().put(KEY_SELECTED_APPS, JSONArray(value.sorted())))
        }

    var selectedAppsOnly: Boolean
        get() {
            migrateIfNeeded()
            return readFileJson().optBoolean(KEY_SELECTED_APPS_ONLY, false)
        }
        set(value) {
            migrateIfNeeded()
            writeFileJson(readFileJson().put(KEY_SELECTED_APPS_ONLY, value))
        }

    /** Цвет акцента приложения (ARGB); 0 — акцент по умолчанию для текущей темы. */
    var accentColorArgb: Int
        get() = preferences.getInt(KEY_ACCENT_COLOR, 0)
        set(value) { preferences.edit().putInt(KEY_ACCENT_COLOR, value).commit() }

    var powerOnboardingComplete: Boolean
        get() = preferences.getBoolean(KEY_POWER_SETUP_COMPLETE, false)
        set(value) { preferences.edit().putBoolean(KEY_POWER_SETUP_COMPLETE, value).apply() }

    var launchAtBoot: Boolean
        get() = preferences.getBoolean(KEY_LAUNCH_AT_BOOT, false)
        set(value) { preferences.edit().putBoolean(KEY_LAUNCH_AT_BOOT, value).apply() }

    /**
     * Запоминание состояния: VPN работал при последнем завершении — включить после перезагрузки.
     *
     * Флаг пишется из процессов `:vpn` / `:vpn_awg` (часто прямо перед kill процесса) и читается
     * UI и службой, поэтому основное хранилище — файл с атомарной записью (tmp + rename).
     */
    var proxyWasActive: Boolean
        get() = runCatching {
            val file = proxyWasActiveFile
            if (file.isFile) file.readText().trim() == "1"
            else preferences.getBoolean(KEY_PROXY_WAS_ACTIVE, false)
        }.getOrDefault(false)
        set(value) {
            runCatching {
                val file = proxyWasActiveFile
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(if (value) "1" else "0")
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }
            preferences.edit().putBoolean(KEY_PROXY_WAS_ACTIVE, value).commit()
        }

    private val proxyWasActiveFile: File
        get() = File(appContext.filesDir, PROXY_WAS_ACTIVE_FILE)

    /** Имя активного набора правил маршрутизации (файл routes/<имя>.json). Служба читает при старте ядра. */
    var activeRouteSetName: String
        get() {
            migrateIfNeeded()
            return readFileJson().optString(KEY_ACTIVE_ROUTE_SET, "")
        }
        set(value) {
            migrateIfNeeded()
            writeFileJson(readFileJson().put(KEY_ACTIVE_ROUTE_SET, value))
        }

    /** Приложения с маршрутом DIRECT: идут мимо туннеля (exclude). Служба читает при старте ядра. */
    var directAppPackages: Set<String>
        get() {
            migrateIfNeeded()
            return readFileJson().optJSONArray(KEY_DIRECT_APPS)?.let { array ->
                (0 until array.length()).mapTo(mutableSetOf()) { array.optString(it) }
            }.orEmpty()
        }
        set(value) {
            migrateIfNeeded()
            writeFileJson(readFileJson().put(KEY_DIRECT_APPS, JSONArray(value.sorted())))
        }

    companion object {
        private const val STORE_FILE = "vibetun_store.json"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_SELECTED_PROFILE = "selected_profile"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_SELECTED_APPS = "selected_apps"
        private const val KEY_DIRECT_APPS = "direct_apps"
        private const val KEY_SELECTED_APPS_ONLY = "selected_apps_only"
        private const val KEY_POWER_SETUP_COMPLETE = "power_setup_complete"
        private const val KEY_LAUNCH_AT_BOOT = "launch_at_boot"
        private const val KEY_PROXY_WAS_ACTIVE = "proxy_was_active"
        private const val KEY_ACCENT_COLOR = "accent_color"
        private const val KEY_ACTIVE_ROUTE_SET = "active_route_set"
        private const val PROXY_WAS_ACTIVE_FILE = "proxy_was_active.txt"
    }
}
