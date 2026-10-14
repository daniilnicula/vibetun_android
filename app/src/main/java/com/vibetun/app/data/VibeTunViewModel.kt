package com.vibetun.app.data

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vibetun.app.service.ConnectionStateStore
import com.vibetun.app.service.NativeCoreStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class VibeTunViewModel(application: Application) : AndroidViewModel(application) {
    private val store = VibeTunStore(application)
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
    var directPackages by mutableStateOf(store.directAppPackages)
        private set
    var launchAtBoot by mutableStateOf(store.launchAtBoot)
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

    /** Профили, по которым сейчас идёт замер задержки. */
    var pingingIds by mutableStateOf(setOf<String>())
        private set
    var pingingAll by mutableStateOf(false)
        private set

    /** Наборы правил маршрутизации (отдельные json-файлы, как routes\ в Windows). */
    var routeSetNames by mutableStateOf(listOf<String>())
        private set
    var activeRouteSet by mutableStateOf("")
        private set
    var routeRules by mutableStateOf(listOf<RouteRule>())
        private set
    private val routeSetStore = RouteSetStore(application)

    private var appsLoaded = false

    init {
        profiles.addAll(store.loadProfiles())
        if (profiles.none { it.id == selectedProfileId }) {
            selectedProfileId = profiles.firstOrNull()?.id.orEmpty()
            store.selectedProfileId = selectedProfileId
        }
        refreshRouteSets()
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
        // приложение в туннеле больше не может быть direct
        if (packageName in mutable && packageName in directPackages) {
            directPackages = directPackages - packageName
            store.directAppPackages = directPackages
        }
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

    /** Тихое обновление полей из broadcast сервиса :vpn — без записи в prefs и реброадкаста. */
    fun onConnectionStateFromService(state: ConnectionState, message: String) {
        connectionState = state
        connectionMessage = message
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

    /** Замер задержки профиля: реальный HTTP-проброс через ядро sing-box (3 попытки, лучшее значение). */
    fun pingProfile(profileId: String) {
        val profile = profiles.firstOrNull { it.id == profileId } ?: return
        if (profileId in pingingIds) return
        pingingIds = pingingIds + profileId
        viewModelScope.launch {
            val latency = withContext(Dispatchers.IO) { ProxyProbeTester.ping(getApplication(), profile) }
            pingingIds = pingingIds - profileId
            val index = profiles.indexOfFirst { it.id == profileId }
            if (index >= 0) profiles[index] = profiles[index].copy(latencyMs = latency)
            persistProfiles()
        }
    }

    /** Замер всех профилей сразу; «нет ответа» сохраняется как пустое значение. */
    fun pingAll() {
        if (pingingAll) return
        val targets = profiles.toList()
        if (targets.isEmpty()) {
            feedback = "Сначала добавьте профили."
            return
        }
        pingingAll = true
        pingingIds = targets.map { it.id }.toSet()
        viewModelScope.launch {
            val app = getApplication<android.app.Application>()
            val results = withContext(Dispatchers.IO) {
                targets.associate { it.id to ProxyProbeTester.ping(app, it) }
            }
            pingingIds = emptySet()
            pingingAll = false
            targets.forEach { profile ->
                val index = profiles.indexOfFirst { it.id == profile.id }
                if (index >= 0) profiles[index] = profiles[index].copy(latencyMs = results[profile.id])
            }
            persistProfiles()
            val reachable = results.values.count { it != null }
            feedback = "Ответили $reachable из ${targets.size}"
        }
    }

    // ==================== маршрутизация доменов/IP ====================

    fun refreshRouteSets() {
        routeSetNames = routeSetStore.listSets()
        if (activeRouteSet.isBlank() || activeRouteSet !in routeSetNames) {
            activeRouteSet = routeSetNames.firstOrNull() ?: ""
        }
        routeRules = activeRouteSet.takeIf { it.isNotBlank() }?.let { routeSetStore.load(it) } ?: emptyList()
    }

    fun switchRouteSet(setName: String) {
        activeRouteSet = setName
        routeRules = routeSetStore.load(setName)
    }

    fun addRouteRule(value: String, action: RouteAction) {
        val trimmed = value.trim()
        if (!RouteSetStore.isValidValue(trimmed)) {
            feedback = "Введите домен, IP-адрес или ключевое слово."
            return
        }
        if (routeRules.any { it.value.equals(trimmed, ignoreCase = true) }) {
            feedback = "Такое правило уже есть в наборе."
            return
        }
        if (activeRouteSet.isBlank()) activeRouteSet = "default"
        routeRules = routeRules + RouteRule(trimmed, action)
        routeSetStore.save(activeRouteSet, routeRules)
        routeSetNames = routeSetStore.listSets()
    }

    fun removeRouteRule(rule: RouteRule) {
        routeRules = routeRules - rule
        if (activeRouteSet.isNotBlank()) routeSetStore.save(activeRouteSet, routeRules)
    }

    /** Экспорт активного набора в json-текст (для сохранения в файл). */
    fun exportActiveRouteSet(): String? {
        if (activeRouteSet.isBlank()) {
            feedback = "Нет активного набора правил."
            return null
        }
        return routeSetStore.toJson(routeRules)
    }

    /** Импорт набора из json-текста; имя набора берётся из переданного. */
    fun importRouteSet(setName: String, text: String): Boolean {
        val rules = runCatching { routeSetStore.parseJson(text) }.getOrNull()
        if (rules == null) {
            feedback = "Файл не похож на набор правил VibeTun."
            return false
        }
        val name = setName.trim().ifBlank { "imported" }
        routeSetStore.save(name, rules)
        routeSetNames = routeSetStore.listSets()
        switchRouteSet(name)
        feedback = "Набор «$name» импортирован (${rules.size} правил)"
        return true
    }

    // ==================== приложения: proxy / direct ====================

    /** PROXY — приложение в туннеле (include), DIRECT — мимо туннеля (exclude). */
    fun setAppRoute(packageName: String, route: String?) {
        val proxy = selectedPackages.toMutableSet()
        val direct = directPackages.toMutableSet()
        when (route) {
            "proxy" -> { proxy.add(packageName); direct.remove(packageName) }
            "direct" -> { direct.add(packageName); proxy.remove(packageName) }
            else -> { proxy.remove(packageName); direct.remove(packageName) }
        }
        selectedPackages = proxy
        directPackages = direct
        store.selectedAppPackages = proxy
        store.directAppPackages = direct
    }

    fun updateLaunchAtBoot(enabled: Boolean) {
        launchAtBoot = enabled
        store.launchAtBoot = enabled
    }

    private fun persistProfiles() = store.saveProfiles(profiles.toList())
}
