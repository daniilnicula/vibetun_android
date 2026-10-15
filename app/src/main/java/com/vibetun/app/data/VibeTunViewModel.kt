package com.vibetun.app.data

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vibetun.app.service.ConnectionStateStore
import com.vibetun.app.service.NativeCoreStatus
import com.vibetun.app.service.VpnServiceLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    /** Маршрут приложений по умолчанию; хранится тем же флагом selectedAppsOnly (DIRECT = only). */
    var appDefaultRoute by mutableStateOf(if (store.selectedAppsOnly) AppDefaultRoute.DIRECT else AppDefaultRoute.PROXY)
        private set
    var selectedPackages by mutableStateOf(store.selectedAppPackages)
        private set
    var directPackages by mutableStateOf(store.directAppPackages)
        private set
    var launchAtBoot by mutableStateOf(store.launchAtBoot)
        private set

    // ==================== обновления ====================
    var updateChecking by mutableStateOf(false)
        private set
    var updateDownloading by mutableStateOf(false)
        private set
    var updateProgress by mutableStateOf(0)
        private set
    /** Релиз, доступный к установке; диалог с описанием висит, пока не null. */
    var updateAvailable by mutableStateOf<UpdateManager.Release?>(null)
    /** Однострочный статус под кнопкой («Установлена актуальная версия», ошибки). */
    var updateStatusLine by mutableStateOf<String?>(null)
    /** Скачанный APK, ожидающий запуска системного установщика (забирает UI). */
    var pendingInstallApk by mutableStateOf<java.io.File?>(null)

    // ==================== подписки (одноразовый импорт по URL) ====================
    /** URL подписки из диалога импорта; пока не null — показываем диалог подтверждения. */
    var pendingSubscriptionUrl by mutableStateOf<String?>(null)
    var subscriptionLoading by mutableStateOf(false)
        private set
    var subscriptionError by mutableStateOf<String?>(null)
    /** Цвет акцента (ARGB); 0 — акцент по умолчанию для текущей темы. Хранится навсегда. */
    var accentColorArgb by mutableStateOf(store.accentColorArgb)
        private set
    var installedApps by mutableStateOf<List<InstalledApp>>(emptyList())
        private set
    var connectionState by mutableStateOf(connectionStore.state)
        private set
    var connectionMessage by mutableStateOf(connectionStore.message)
        private set
    /** Профиль, которым реально поднят туннель (пишет служба). Отличается от выбранного,
     *  пока пользователь при живом подключении ткнул другой профиль — до переподключения. */
    var activeProfileId by mutableStateOf(connectionStore.activeProfileId)
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
    /** Дебаунс автопереподключения при смене правил: стопим предыдущий отложенный restart. */
    private var reconnectJob: Job? = null

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

    /** Профиль активного туннеля; UI обязан показывать его, а не выбранный, пока VPN работает. */
    val activeProfile: ProxyProfile?
        get() = profiles.firstOrNull { it.id == activeProfileId }

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

    /** Остановка запрошена, служба ещё не подтвердила STOPPED. */
    val isStopping: Boolean
        get() = connectionState == ConnectionState.STOPPING

    val isBatterySetupRecommended: Boolean
        get() = !batteryOptimizationExempt

    fun importText(text: String): Int {
        // Ссылка-подписка (3x-ui /sub/...): не парсим как текст, предлагаем скачать.
        if (isSubscriptionUrl(text)) {
            pendingSubscriptionUrl = text.trim()
            return 0
        }
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

    /** Одна строка, начинающаяся с http(s):// — считаем ссылкой-подпиской. */
    private fun isSubscriptionUrl(text: String): Boolean {
        val trimmed = text.trim()
        return !trimmed.contains('\n') &&
            (trimmed.startsWith("https://") || trimmed.startsWith("http://"))
    }

    /** Загрузка подписки и добавление распарсенных профилей (без дублей по source). */
    fun fetchSubscription(url: String, allowInsecure: Boolean) {
        if (subscriptionLoading) return
        subscriptionLoading = true
        subscriptionError = null
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { SubscriptionFetcher.fetch(url, allowInsecure) } }
                .onSuccess { payload ->
                    val result = ProfileLinkParser.parseMany(payload)
                    if (result.profiles.isEmpty()) {
                        subscriptionError = "В подписке не найдено поддерживаемых профилей."
                        return@onSuccess
                    }
                    val existingSources = profiles.map { it.source }.toSet()
                    val newProfiles = result.profiles.filterNot { it.source in existingSources }
                    profiles.addAll(newProfiles)
                    if (selectedProfileId.isBlank() && profiles.isNotEmpty()) selectProfile(profiles.first().id)
                    persistProfiles()
                    val skipped = result.profiles.size - newProfiles.size
                    feedback = if (skipped > 0) {
                        "Добавлено из подписки: ${newProfiles.size} (дублей пропущено: $skipped)"
                    } else {
                        "Добавлено из подписки: ${newProfiles.size}"
                    }
                    pendingSubscriptionUrl = null
                }
                .onFailure { subscriptionError = "Не удалось загрузить подписку: ${it.message}" }
            subscriptionLoading = false
        }
    }

    fun dismissSubscription() {
        pendingSubscriptionUrl = null
        subscriptionError = null
    }

    // ==================== обновления ====================

    private fun currentVersionName(): String = runCatching {
        val app = getApplication<Application>()
        @Suppress("DEPRECATION")
        app.packageManager.getPackageInfo(app.packageName, 0).versionName
    }.getOrNull().orEmpty()

    /** Проверка релизов на GitHub. Результат — диалог [updateAvailable] или строка статуса. */
    fun checkForUpdates() {
        if (updateChecking || updateDownloading) return
        updateChecking = true
        updateStatusLine = null
        updateAvailable = null
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { UpdateManager.fetchLatest() } }
                .onSuccess { release ->
                    updateStatusLine = if (UpdateManager.isUpdateAvailable(release.tag, currentVersionName())) {
                        updateAvailable = release
                        "Доступна версия ${release.tag}"
                    } else {
                        "Установлена актуальная версия (${currentVersionName()})"
                    }
                }
                .onFailure { updateStatusLine = "Не удалось проверить обновления: ${it.message}" }
            updateChecking = false
        }
    }

    fun dismissUpdate() {
        updateAvailable = null
    }

    /** Скачивание APK выбранного релиза; по завершении UI запускает системный установщик. */
    fun downloadAndInstallUpdate() {
        val release = updateAvailable ?: return
        if (updateDownloading) return
        updateDownloading = true
        updateProgress = 0
        updateStatusLine = null
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    UpdateManager.download(getApplication(), release.apkUrl) { percent -> updateProgress = percent }
                }
            }
                .onSuccess { apk ->
                    pendingInstallApk = apk
                    updateAvailable = null
                }
                .onFailure { updateStatusLine = "Не удалось скачать обновление: ${it.message}" }
            updateDownloading = false
        }
    }

    fun consumePendingInstall(): java.io.File? {
        val apk = pendingInstallApk
        pendingInstallApk = null
        return apk
    }

    /** Подпись в футере настроек: текущая versionName (из CI) либо dev-метка. */
    fun appVersionLabel(): String = currentVersionName().ifBlank { "dev" }

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

    fun selectProfile(id: String, reconnectIfRunning: Boolean = true) {
        if (profiles.none { it.id == id }) return
        val previous = selectedProfileId
        selectedProfileId = id
        store.selectedProfileId = id
        // Смена выбора при живом подключении раньше ничего не делала: UI показывал новый
        // профиль как «подключённый», а туннель оставался старого. Теперь — переподключение.
        if (reconnectIfRunning && previous != id && connectionState == ConnectionState.RUNNING) {
            scheduleReconnectIfRunning("смена профиля", "Переключаем профиль…")
        }
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
            // Удаление — не пользовательская смена выбора: переподключение не дёргаем.
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
        appDefaultRoute = if (mode == ProxyMode.SelectedApps) AppDefaultRoute.DIRECT else AppDefaultRoute.PROXY
        store.selectedAppsOnly = mode == ProxyMode.SelectedApps
    }

    /** «По умолчанию для приложений»: PROXY — весь трафик в VPN кроме DIRECT-приложений,
     *  DIRECT — мимо VPN кроме PROXY-приложений. */
    fun updateAppDefaultRoute(route: AppDefaultRoute) {
        appDefaultRoute = route
        proxyMode = if (route == AppDefaultRoute.DIRECT) ProxyMode.SelectedApps else ProxyMode.AllApps
        store.selectedAppsOnly = route == AppDefaultRoute.DIRECT
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
        activeProfileId = connectionStore.activeProfileId
    }

    /** Тихое обновление полей из broadcast сервиса :vpn — без записи в prefs и реброадкаста. */
    fun onConnectionStateFromService(state: ConnectionState, message: String, activeProfileId: String? = null) {
        connectionState = state
        connectionMessage = message
        this.activeProfileId = activeProfileId ?: connectionStore.activeProfileId
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
        // Читаем сохранённое имя набора; если его нет на диске — fallback на первый.
        val saved = store.activeRouteSetName
        val resolved = resolveActiveRouteSet(saved, routeSetNames)
        activeRouteSet = resolved
        if (saved != resolved) store.activeRouteSetName = resolved
        routeRules = activeRouteSet.takeIf { it.isNotBlank() }?.let { routeSetStore.load(it) } ?: emptyList()
    }

    fun switchRouteSet(setName: String) {
        activeRouteSet = setName
        store.activeRouteSetName = setName
        routeRules = routeSetStore.load(setName)
        scheduleReconnectIfRunning("смена набора маршрутов «$setName»")
    }

    fun addRouteRule(value: String, action: RouteAction) {
        val trimmed = value.trim()
        if (!RouteSetStore.isValidValue(trimmed)) {
            feedback = "Введите домен, IP-адрес или ключевое слово."
            return
        }
        if (routeRules.any { it.value.equals(trimmed, ignoreCase = true) }) {
            feedback = "Такое правило уже есть в маршрутах."
            return
        }
        if (activeRouteSet.isBlank()) {
            activeRouteSet = "default"
            store.activeRouteSetName = "default"
        }
        routeRules = routeRules + RouteRule(trimmed, action)
        routeSetStore.save(activeRouteSet, routeRules)
        routeSetNames = routeSetStore.listSets()
        scheduleReconnectIfRunning("добавление правила «$trimmed»")
    }

    fun removeRouteRule(rule: RouteRule) {
        routeRules = routeRules - rule
        if (activeRouteSet.isNotBlank()) routeSetStore.save(activeRouteSet, routeRules)
        scheduleReconnectIfRunning("удаление правила «${rule.value}»")
    }

    /** Переключение действия правила PROXY ↔ DIRECT одним нажатием на его метку. */
    fun toggleRouteRuleAction(rule: RouteRule) {
        val next = if (rule.action == RouteAction.DIRECT) RouteAction.PROXY else RouteAction.DIRECT
        routeRules = routeRules.map { if (it == rule) it.copy(action = next) else it }
        if (activeRouteSet.isNotBlank()) routeSetStore.save(activeRouteSet, routeRules)
        scheduleReconnectIfRunning("смена действия правила «${rule.value}» на $next")
    }

    /** Экспорт активных маршрутов в json-текст (для сохранения в файл). */
    fun exportActiveRouteSet(): String? {
        if (activeRouteSet.isBlank()) {
            feedback = "Нет активных маршрутов."
            return null
        }
        return routeSetStore.toJson(routeRules)
    }

    /** Импорт маршрутов из json-текста; имя набора берётся из переданного. */
    fun importRouteSet(setName: String, text: String): Boolean {
        val rules = runCatching { routeSetStore.parseJson(text) }.getOrNull()
        if (rules == null) {
            feedback = "Файл не похож на маршруты VibeTun."
            return false
        }
        val name = setName.trim().ifBlank { "imported" }
        routeSetStore.save(name, rules)
        routeSetNames = routeSetStore.listSets()
        activeRouteSet = name
        store.activeRouteSetName = name
        routeRules = rules
        feedback = "Маршруты «$name» импортированы (${rules.size} правил)"
        scheduleReconnectIfRunning("импорт набора «$name»")
        return true
    }

    /**
     * Если VPN сейчас работает — переподключаем с дебаунсом ~1 с, чтобы серия быстрых
     * изменений правил (импорт, удаление, смена набора) не дёргала VPN каждый раз.
     * Машина состояний: не запускаем при STARTING/STOPPING (см. ConnectionState.actionOnTap()).
     */
    private fun scheduleReconnectIfRunning(reason: String, feedbackText: String = "Правила применены") {
        if (connectionState != ConnectionState.RUNNING) return
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            delay(RECONNECT_DEBOUNCE_MS)
            if (connectionState != ConnectionState.RUNNING) return@launch
            val profile = selectedProfile ?: return@launch
            AppLog.i("ViewModel", "Переподключение VPN: $reason")
            feedback = feedbackText
            VpnServiceLauncher.start(getApplication(), profile)
        }
    }

    companion object {
        private const val RECONNECT_DEBOUNCE_MS = 1000L
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

    fun updateAccentColor(argb: Int) {
        accentColorArgb = argb
        store.accentColorArgb = argb
    }

    private fun persistProfiles() = store.saveProfiles(profiles.toList())
}
