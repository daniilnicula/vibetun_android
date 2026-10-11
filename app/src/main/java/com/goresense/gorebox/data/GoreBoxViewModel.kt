package com.goresense.gorebox.data

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.goresense.gorebox.service.ConnectionStateStore
import com.goresense.gorebox.service.NativeCoreStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class GoreBoxViewModel(application: Application) : AndroidViewModel(application) {
    private val store = GoreBoxStore(application)
    private val connectionStore = ConnectionStateStore(application)

    val profiles = mutableStateListOf<ProxyProfile>()
    var selectedProfileId by mutableStateOf(store.selectedProfileId)
        private set
    var currentTab by mutableStateOf(AppTab.Profiles)
    var darkTheme by mutableStateOf(store.darkTheme)
        private set
    var proxyMode by mutableStateOf(if (store.selectedAppsOnly) ProxyMode.SelectedApps else ProxyMode.AllApps)
        private set
    var selectedPackages by mutableStateOf(store.selectedAppPackages)
        private set
    var installedApps by mutableStateOf<List<InstalledApp>>(emptyList())
        private set
    var connectionState by mutableStateOf(connectionStore.state)
        private set
    var connectionMessage by mutableStateOf(connectionStore.message)
        private set
    var feedback by mutableStateOf<String?>(null)
    var powerDialogVisible by mutableStateOf(!store.powerOnboardingComplete)
        private set
    var batteryOptimizationExempt by mutableStateOf(false)
        private set
    var searchQuery by mutableStateOf("")

    private var appsLoaded = false

    init {
        profiles.addAll(store.loadProfiles())
        if (profiles.none { it.id == selectedProfileId }) {
            selectedProfileId = profiles.firstOrNull()?.id.orEmpty()
            store.selectedProfileId = selectedProfileId
        }
        refreshConnectionState()
    }

    val selectedProfile: ProxyProfile?
        get() = profiles.firstOrNull { it.id == selectedProfileId }

    val visibleProfiles: List<ProxyProfile>
        get() {
            val query = searchQuery.trim()
            return profiles
                .filter { query.isEmpty() || it.name.contains(query, true) || it.server.contains(query, true) || it.protocolLabel.contains(query, true) }
                .sortedWith(compareByDescending<ProxyProfile> { it.favorite }.thenBy { it.name.lowercase() })
        }

    val isConnecting: Boolean
        get() = connectionState == ConnectionState.STARTING

    val isConnected: Boolean
        get() = connectionState == ConnectionState.RUNNING

    val isBatterySetupRecommended: Boolean
        get() = !batteryOptimizationExempt

    fun importText(text: String): Int {
        val result = ProfileLinkParser.parseMany(text)
        if (result.profiles.isNotEmpty()) {
            val existingSources = profiles.map { it.source }.toSet()
            val newProfiles = result.profiles.filterNot { it.source in existingSources }
            profiles.addAll(newProfiles)
            if (selectedProfileId.isBlank() && profiles.isNotEmpty()) selectProfile(profiles.first().id)
            persistProfiles()
            feedback = if (newProfiles.isEmpty()) "Такие профили уже добавлены" else "Добавлено профилей: ${newProfiles.size}"
        } else {
            feedback = result.errors.firstOrNull() ?: "Не удалось найти профиль"
        }
        if (result.errors.isNotEmpty() && result.profiles.isNotEmpty()) {
            feedback = "Добавлено ${result.profiles.size}; часть строк не распознана"
        }
        return result.profiles.size
    }

    fun saveEditedProfile(profileId: String, name: String, source: String) {
        val old = profiles.firstOrNull { it.id == profileId } ?: return
        val parsed = ProfileLinkParser.parseOne(source) ?: run {
            feedback = "Не удалось распознать ссылку или конфиг"
            return
        }
        val index = profiles.indexOfFirst { it.id == profileId }
        profiles[index] = parsed.copy(id = old.id, name = name.trim().ifBlank { parsed.name }, favorite = old.favorite)
        persistProfiles()
        feedback = "Профиль сохранён"
    }

    fun selectProfile(id: String) {
        if (profiles.none { it.id == id }) return
        selectedProfileId = id
        store.selectedProfileId = id
    }

    fun toggleFavorite(profile: ProxyProfile) {
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index < 0) return
        profiles[index] = profiles[index].copy(favorite = !profiles[index].favorite)
        persistProfiles()
    }

    fun removeProfile(profile: ProxyProfile) {
        profiles.removeAll { it.id == profile.id }
        if (selectedProfileId == profile.id) {
            selectedProfileId = profiles.firstOrNull()?.id.orEmpty()
            store.selectedProfileId = selectedProfileId
        }
        persistProfiles()
        feedback = "Профиль удалён"
    }

    fun updateDarkTheme(enabled: Boolean) {
        darkTheme = enabled
        store.darkTheme = enabled
    }

    fun updateProxyMode(mode: ProxyMode) {
        proxyMode = mode
        store.selectedAppsOnly = mode == ProxyMode.SelectedApps
    }

    fun togglePackage(packageName: String) {
        val mutable = selectedPackages.toMutableSet()
        if (!mutable.add(packageName)) mutable.remove(packageName)
        selectedPackages = mutable
        store.selectedAppPackages = mutable
    }

    fun loadInstalledApps() {
        if (appsLoaded) return
        appsLoaded = true
        viewModelScope.launch {
            val app = getApplication<Application>()
            val found = withContext(Dispatchers.IO) {
                val manager = app.packageManager
                @Suppress("DEPRECATION")
                val all = if (Build.VERSION.SDK_INT >= 33) {
                    manager.getInstalledApplications(android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    manager.getInstalledApplications(0)
                }
                all.asSequence()
                    .filter { it.packageName != app.packageName }
                    .filter { manager.getLaunchIntentForPackage(it.packageName) != null }
                    .map { info ->
                        InstalledApp(
                            packageName = info.packageName,
                            label = runCatching { manager.getApplicationLabel(info).toString() }
                                .getOrDefault(info.packageName),
                        )
                    }
                    .distinctBy { it.packageName }
                    .sortedBy { it.label.lowercase() }
                    .toList()
            }
            installedApps = found
        }
    }

    fun refreshConnectionState() {
        connectionState = connectionStore.state
        connectionMessage = connectionStore.message
    }

    fun setConnectionState(state: ConnectionState, message: String = "") {
        connectionStore.update(state, message)
        refreshConnectionState()
    }

    fun refreshBatteryStatus(isExempt: Boolean) {
        batteryOptimizationExempt = isExempt
    }

    fun dismissPowerDialog() {
        powerDialogVisible = false
        store.powerOnboardingComplete = true
    }

    fun showPowerDialogAgain() {
        powerDialogVisible = true
    }

    fun showCoreUnavailable() {
        connectionState = ConnectionState.ERROR
        connectionMessage = NativeCoreStatus.message
        feedback = NativeCoreStatus.message
    }

    fun clearFeedback() {
        feedback = null
    }

    private fun persistProfiles() = store.saveProfiles(profiles.toList())
}
