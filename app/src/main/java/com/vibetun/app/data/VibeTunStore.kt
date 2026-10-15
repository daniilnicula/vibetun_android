package com.vibetun.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Small private on-device store; profile credentials never leave the app. */
@Suppress("DEPRECATION")
class VibeTunStore(context: Context) {
    private val appContext = context.applicationContext

    // MODE_MULTI_PROCESS: сервис живёт в процессе :vpn, а SharedPreferences кэшируются
    // per-process — без этого флага сервис видит протухший снапшот (профили, выбор,
    // списки приложений). Записи редкие, флага достаточно.
    private val preferences = appContext.getSharedPreferences("vibetun", Context.MODE_MULTI_PROCESS)

    fun loadProfiles(): List<ProxyProfile> {
        val raw = preferences.getString(KEY_PROFILES, "[]") ?: "[]"
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
        preferences.edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    var selectedProfileId: String
        get() = preferences.getString(KEY_SELECTED_PROFILE, "").orEmpty()
        set(value) { preferences.edit().putString(KEY_SELECTED_PROFILE, value).apply() }

    var darkTheme: Boolean
        get() = preferences.getBoolean(KEY_DARK_THEME, true)
        set(value) { preferences.edit().putBoolean(KEY_DARK_THEME, value).apply() }

    // Маршрутизацию читает процесс службы (:vpn/:vpn_awg) сразу после старта, а apply()
    // сбрасывает данные на диск асинхронно — служба могла прочитать протухший снапшот,
    // и per-app правила «не применялись». Поэтому здесь только синхронный commit().
    var selectedAppPackages: Set<String>
        get() = preferences.getStringSet(KEY_SELECTED_APPS, emptySet())?.toSet().orEmpty()
        set(value) { preferences.edit().putStringSet(KEY_SELECTED_APPS, value.toSet()).commit() }

    var selectedAppsOnly: Boolean
        get() = preferences.getBoolean(KEY_SELECTED_APPS_ONLY, false)
        set(value) { preferences.edit().putBoolean(KEY_SELECTED_APPS_ONLY, value).commit() }

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
     * UI и службой, поэтому основное хранилище — файл с
     * атомарной записью (tmp + rename): SharedPreferences кэшируются per-process, а commit()
     * при MODE_MULTI_PROCESS между процессами не гарантирован. Prefs дублируются для совместимости
     * с уже установленными сборками.
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

    /** Имя активного набора правил маршрутизации (файл routes/<имя>.json).
     *  Служба :vpn читает при старте ядра; commit() чтобы процесс видел актуальное значение. */
    var activeRouteSetName: String
        get() = preferences.getString(KEY_ACTIVE_ROUTE_SET, "").orEmpty()
        set(value) { preferences.edit().putString(KEY_ACTIVE_ROUTE_SET, value).commit() }

    /** Приложения с маршрутом DIRECT: идут мимо туннеля (exclude). */
    var directAppPackages: Set<String>
        get() = preferences.getStringSet(KEY_DIRECT_APPS, emptySet())?.toSet().orEmpty()
        set(value) { preferences.edit().putStringSet(KEY_DIRECT_APPS, value.toSet()).commit() }

    companion object {
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
