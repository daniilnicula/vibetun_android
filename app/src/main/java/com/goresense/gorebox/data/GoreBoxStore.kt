package com.goresense.gorebox.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small private on-device store; profile credentials never leave the app. */
class GoreBoxStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("gorebox", Context.MODE_PRIVATE)

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

    var selectedAppPackages: Set<String>
        get() = preferences.getStringSet(KEY_SELECTED_APPS, emptySet())?.toSet().orEmpty()
        set(value) { preferences.edit().putStringSet(KEY_SELECTED_APPS, value.toSet()).apply() }

    var selectedAppsOnly: Boolean
        get() = preferences.getBoolean(KEY_SELECTED_APPS_ONLY, false)
        set(value) { preferences.edit().putBoolean(KEY_SELECTED_APPS_ONLY, value).apply() }

    var powerOnboardingComplete: Boolean
        get() = preferences.getBoolean(KEY_POWER_SETUP_COMPLETE, false)
        set(value) { preferences.edit().putBoolean(KEY_POWER_SETUP_COMPLETE, value).apply() }

    var launchAtBoot: Boolean
        get() = preferences.getBoolean(KEY_LAUNCH_AT_BOOT, false)
        set(value) { preferences.edit().putBoolean(KEY_LAUNCH_AT_BOOT, value).apply() }

    companion object {
        private const val KEY_PROFILES = "profiles"
        private const val KEY_SELECTED_PROFILE = "selected_profile"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_SELECTED_APPS = "selected_apps"
        private const val KEY_SELECTED_APPS_ONLY = "selected_apps_only"
        private const val KEY_POWER_SETUP_COMPLETE = "power_setup_complete"
        private const val KEY_LAUNCH_AT_BOOT = "launch_at_boot"
    }
}
