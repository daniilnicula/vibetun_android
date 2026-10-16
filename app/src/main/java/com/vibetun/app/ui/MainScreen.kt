package com.vibetun.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NetworkPing
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.vibetun.app.data.AppLog
import com.vibetun.app.R
import com.vibetun.app.data.AppTab
import com.vibetun.app.data.ConnectionState
import com.vibetun.app.data.UpdateManager
import com.vibetun.app.data.VibeTunViewModel
import com.vibetun.app.data.InstalledApp
import com.vibetun.app.data.ProtocolCatalog
import com.vibetun.app.data.AppDefaultRoute
import com.vibetun.app.data.ProxyMode
import com.vibetun.app.data.ProxyProfile
import com.vibetun.app.data.RouteAction
import com.vibetun.app.service.NativeCoreStatus

@Composable
fun VibeTunRoot(
    viewModel: VibeTunViewModel,
    onConnect: () -> Unit,
    onOpenImport: () -> Unit,
    onPaste: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onBatteryStatusRefresh: () -> Unit,
    onCopyProfile: (ProxyProfile) -> Unit = {},
    onExportRules: () -> Unit = {},
    onImportRules: () -> Unit = {},
) {
    val palette = LocalGorePalette.current
    val snackbarHost = remember { SnackbarHostState() }
    var showImportDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ProxyProfile?>(null) }
    var removingProfile by remember { mutableStateOf<ProxyProfile?>(null) }

    LaunchedEffect(viewModel.feedback) {
        val message = viewModel.feedback ?: return@LaunchedEffect
        snackbarHost.showSnackbar(message)
        viewModel.clearFeedback()
    }
    LaunchedEffect(viewModel.currentTab) {
        if (viewModel.currentTab == AppTab.Routing) viewModel.loadInstalledApps()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(palette.window)
            .statusBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            AppTitleBar(darkTheme = viewModel.darkTheme, onToggleTheme = { viewModel.updateDarkTheme(!viewModel.darkTheme) })
            Box(Modifier.weight(1f)) {
                when (viewModel.currentTab) {
                    AppTab.Profiles -> ProfilesPage(
                        viewModel = viewModel,
                        onConnect = onConnect,
                        onAddManual = { showImportDialog = true },
                        onOpenImport = onOpenImport,
                        onPaste = onPaste,
                        onEdit = { editingProfile = it },
                        onRemove = { removingProfile = it },
                        onCopy = onCopyProfile,
                    )
                    AppTab.Routing -> RoutingPage(viewModel, onExportRules = onExportRules, onImportRules = onImportRules)
                    AppTab.Settings -> SettingsPage(
                        viewModel = viewModel,
                        onOpenBatterySettings = onOpenBatterySettings,
                        onOpenAppSettings = onOpenAppSettings,
                        onBatteryStatusRefresh = onBatteryStatusRefresh,
                    )
                }
            }
            BottomNavigation(current = viewModel.currentTab, onSelect = { viewModel.currentTab = it })
        }

        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 76.dp),
            snackbar = { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = palette.cardAlt,
                    contentColor = palette.text,
                    shape = RoundedCornerShape(10.dp),
                )
            },
        )
    }

    if (viewModel.powerDialogVisible) {
        PowerOnboardingDialog(
            onDismiss = viewModel::dismissPowerDialog,
            onOpenBatterySettings = {
                viewModel.dismissPowerDialog()
                onOpenBatterySettings()
            },
            onOpenAppSettings = {
                viewModel.dismissPowerDialog()
                onOpenAppSettings()
            },
        )
    }
    if (showImportDialog) {
        ImportProfileDialog(
            onDismiss = { showImportDialog = false },
            onImport = { text ->
                viewModel.importText(text)
                showImportDialog = false
            },
        )
    }
    viewModel.updateAvailable?.let { release ->
        UpdateAvailableDialog(
            release = release,
            downloading = viewModel.updateDownloading,
            progress = viewModel.updateProgress,
            onDismiss = { viewModel.dismissUpdate() },
            onDownload = { viewModel.downloadAndInstallUpdate() },
        )
    }
    viewModel.pendingSubscriptionUrl?.let { url ->
        SubscriptionImportDialog(
            url = url,
            loading = viewModel.subscriptionLoading,
            error = viewModel.subscriptionError,
            onDismiss = { viewModel.dismissSubscription() },
            onFetch = { allowInsecure -> viewModel.fetchSubscription(url, allowInsecure) },
        )
    }
    editingProfile?.let { profile ->
        EditProfileDialog(
            profile = profile,
            onDismiss = { editingProfile = null },
            onSave = { name, source ->
                viewModel.saveEditedProfile(profile.id, name, source)
                editingProfile = null
            },
        )
    }
    removingProfile?.let { profile ->
        ConfirmRemoveDialog(
            profileName = profile.name,
            onDismiss = { removingProfile = null },
            onRemove = {
                viewModel.removeProfile(profile)
                removingProfile = null
            },
        )
    }
}

@Composable
private fun AppTitleBar(darkTheme: Boolean, onToggleTheme: () -> Unit) {
    val p = LocalGorePalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.window)
            .padding(start = 18.dp, end = 12.dp, top = 9.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = RoundedCornerShape(7.dp), color = p.card, border = BorderStroke(1.dp, p.border)) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.ic_vibetun),
                contentDescription = "VibeTun",
                modifier = Modifier.size(32.dp),
            )
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text("VibeTun", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.15.sp)
            Text("PROXY CLIENT  ·  ANDROID", color = p.textMuted, fontSize = 8.5.sp, letterSpacing = 1.1.sp)
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun BottomNavigation(current: AppTab, onSelect: (AppTab) -> Unit) {
    val p = LocalGorePalette.current
    val entries = listOf(
        Triple(AppTab.Profiles, Icons.Outlined.Shield, "Профили"),
        Triple(AppTab.Routing, Icons.Outlined.Route, "Маршруты"),
        Triple(AppTab.Settings, Icons.Outlined.Settings, "Настройки"),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(p.panel)
            .border(BorderStroke(1.dp, p.border))
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.forEach { (tab, icon, title) ->
            val selected = current == tab
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) p.accentSoft else Color.Transparent)
                    .clickable { onSelect(tab) }
                    .padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(icon, contentDescription = title, tint = if (selected) p.accent else p.textMuted, modifier = Modifier.size(19.dp))
                Text(title, fontSize = 10.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) p.accent else p.textMuted)
            }
        }
    }
}

@Composable
private fun ProfilesPage(
    viewModel: VibeTunViewModel,
    onConnect: () -> Unit,
    onAddManual: () -> Unit,
    onOpenImport: () -> Unit,
    onPaste: () -> Unit,
    onEdit: (ProxyProfile) -> Unit,
    onRemove: (ProxyProfile) -> Unit,
    onCopy: (ProxyProfile) -> Unit,
) {
    val p = LocalGorePalette.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        PageHeading(
            title = "Профили",
            subtitle = "Серверы и конфигурации VibeTun",
        )
        ConnectionCard(viewModel = viewModel, onConnect = onConnect)

        Text(
            "СОХРАНЁННЫЕ ПРОФИЛИ",
            color = p.textMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.05.sp,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            "${viewModel.profiles.size} ${profileCountWord(viewModel.profiles.size)}",
            color = p.textSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            SmallActionButton(
                label = if (viewModel.pingingAll) "Пинг…" else "Ping all",
                icon = Icons.Outlined.NetworkPing,
                onClick = { viewModel.pingAll() },
            )
            // «Добавить» — единственная кнопка добавления: по нажатию меню
            // «Импорт / Вставить / Вручную» вместо трёх отдельных кнопок.
            Box {
                var addMenuOpen by remember { mutableStateOf(false) }
                AddButton(onClick = { addMenuOpen = true })
                DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Импорт") },
                        leadingIcon = { Icon(Icons.Outlined.FileOpen, contentDescription = null) },
                        onClick = { addMenuOpen = false; onOpenImport() },
                    )
                    DropdownMenuItem(
                        text = { Text("Вставить") },
                        leadingIcon = { Icon(Icons.Outlined.ContentPaste, contentDescription = null) },
                        onClick = { addMenuOpen = false; onPaste() },
                    )
                    DropdownMenuItem(
                        text = { Text("Вручную") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = { addMenuOpen = false; onAddManual() },
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        if (viewModel.profiles.isEmpty()) {
            // Только текст «нет профилей»: кнопки «Вставить»/«Из файла»/«Добавить»
            // уже есть строкой выше, в пустом состоянии они дублировались.
            EmptyProfilesCard()
        } else {
            SearchInput(
                value = viewModel.searchQuery,
                onValueChange = { viewModel.searchQuery = it },
                placeholder = "Поиск по имени, адресу или протоколу",
                modifier = Modifier.padding(bottom = 10.dp),
            )
            if (viewModel.visibleProfiles.isEmpty()) {
                EmptySearchCard()
            } else {
                viewModel.visibleProfiles.forEach { profile ->
                    ProfileRow(
                        profile = profile,
                        selected = profile.id == viewModel.selectedProfileId,
                        connected = viewModel.isConnected && profile.id == viewModel.activeProfileId,
                        pinging = profile.id in viewModel.pingingIds,
                        onSelect = { viewModel.selectProfile(profile.id) },
                        onEdit = { onEdit(profile) },
                        onRemove = { onRemove(profile) },
                        onFavorite = { viewModel.toggleFavorite(profile) },
                        onCopy = { onCopy(profile) },
                        onPing = { viewModel.pingProfile(profile.id) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun ConnectionCard(viewModel: VibeTunViewModel, onConnect: () -> Unit) {
    val p = LocalGorePalette.current
    val state = viewModel.connectionState
    val dotColor = when (state) {
        ConnectionState.RUNNING -> p.success
        ConnectionState.STARTING, ConnectionState.STOPPING -> p.warning
        ConnectionState.ERROR -> p.danger
        ConnectionState.STOPPED -> p.textMuted
    }
    val stateLabel = when (state) {
        ConnectionState.RUNNING -> "ПОДКЛЮЧЕНО"
        ConnectionState.STARTING -> "ПОДКЛЮЧЕНИЕ"
        ConnectionState.STOPPING -> "ОТКЛЮЧЕНИЕ"
        ConnectionState.ERROR -> "ТРЕБУЕТ ВНИМАНИЯ"
        ConnectionState.STOPPED -> "ОТКЛЮЧЕНО"
    }
    PanelCard(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                Text(stateLabel, color = dotColor, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp, modifier = Modifier.padding(start = 7.dp))
                Spacer(Modifier.weight(1f))
                StatusTag(text = if (NativeCoreStatus.isIntegrated) "CORE READY" else "CORE · ОЖИДАЕТ", tone = if (NativeCoreStatus.isIntegrated) p.success else p.warning)
            }
            Text(
                text = when (state) {
                    ConnectionState.RUNNING -> "VibeTun работает"
                    ConnectionState.STARTING -> "Поднимаем туннель…"
                    // Остановку подтверждает служба: пока она не ответила, показываем процесс,
                    // а не «выключено» — иначе UI врал бы про живой туннель.
                    ConnectionState.STOPPING -> "Отключаемся…"
                    ConnectionState.ERROR -> "Нужна настройка ядра"
                    ConnectionState.STOPPED -> "Прокси выключен"
                },
                color = p.text,
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp),
            )
            // Пока VPN работает показываем профиль АКТИВНОГО туннеля (пишет служба),
            // а не просто выбранный: иначе при смене выбора UI врал бы про протокол.
            val selected = viewModel.selectedProfile
            val displayedProfile = if (state == ConnectionState.RUNNING || state == ConnectionState.STARTING) {
                viewModel.activeProfile ?: selected
            } else selected
            Text(
                text = displayedProfile?.let { "${it.protocolLabel}  ·  ${it.endpoint}" } ?: "Выберите профиль, чтобы начать",
                color = p.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
            if (state == ConnectionState.ERROR || !NativeCoreStatus.isIntegrated) {
                Text(
                    text = viewModel.connectionMessage.takeIf { it.isNotBlank() } ?: NativeCoreStatus.message,
                    color = p.warning,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(p.warningSoft)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(13.dp))
            PrimaryActionButton(
                label = when {
                    viewModel.isConnecting -> "Подключаемся…"
                    viewModel.isStopping -> "Отключаемся…"
                    viewModel.isConnected -> "Отключить прокси"
                    else -> "Подключить прокси"
                },
                icon = if (viewModel.isConnected || viewModel.isStopping) Icons.Outlined.Close else Icons.Outlined.PowerSettingsNew,
                onClick = onConnect,
                // Пока идёт запуск или остановка, кнопка неактивна: иначе stop и start гоняются,
                // и запоздавшая остановка убивает только что запущенную службу.
                enabled = !viewModel.isConnecting && !viewModel.isStopping,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                MiniMetric(
                    title = "ПРОФИЛЬ",
                    value = displayedProfile?.protocolLabel ?: "не выбран",
                    modifier = Modifier.weight(1f),
                )
                MiniMetric(
                    title = "МАРШРУТ",
                    value = if (viewModel.appDefaultRoute == AppDefaultRoute.PROXY) "По умолчанию proxy" else "По умолчанию direct",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MiniMetric(title: String, value: String, modifier: Modifier = Modifier) {
    val p = LocalGorePalette.current
    Column(
        modifier
            .clip(RoundedCornerShape(7.dp))
            .background(p.cardAlt)
            .padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
        Text(title, color = p.textMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Text(value, color = p.textSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun EmptyProfilesCard() {
    val p = LocalGorePalette.current
    PanelCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 25.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = p.accentSoft, modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Shield, contentDescription = null, tint = p.accent, modifier = Modifier.size(24.dp))
                }
            }
            Text("Пока нет профилей", color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            Text(
                "Нет профилей. Добавьте первый кнопками выше: вставьте ссылку прокси, импортируйте файл или добавьте конфиг вручную.",
                color = p.textMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 5.dp),
            )
            Text("VLESS · VMess · Trojan · Shadowsocks · WireGuard · JSON", color = p.textMuted, fontSize = 9.sp, modifier = Modifier.padding(top = 13.dp))
        }
    }
}

@Composable
private fun EmptySearchCard() {
    val p = LocalGorePalette.current
    PanelCard(Modifier.fillMaxWidth()) {
        Text("Ничего не найдено", color = p.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(18.dp))
    }
}

@Composable
private fun ProfileRow(
    profile: ProxyProfile,
    selected: Boolean,
    connected: Boolean,
    pinging: Boolean = false,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onFavorite: () -> Unit,
    onCopy: () -> Unit,
    onPing: () -> Unit = {},
) {
    val p = LocalGorePalette.current
    var menuExpanded by remember(profile.id) { mutableStateOf(false) }
    val tint = protocolTint(profile.protocol)
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) p.cardAlt else p.card,
        border = BorderStroke(1.dp, if (connected) p.success.copy(alpha = 0.55f) else if (selected) p.accent.copy(alpha = 0.65f) else p.border),
    ) {
        Row(
            Modifier.padding(start = 11.dp, end = 5.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(39.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(tint.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(ProtocolCatalog.badge(profile.protocol), color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).padding(start = 10.dp, end = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(profile.name, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (selected) {
                        Spacer(Modifier.width(6.dp))
                        StatusTag(text = if (connected) "АКТИВЕН" else "ВЫБРАН", tone = if (connected) p.success else p.accent)
                    }
                }
                Text(
                    "${profile.protocolLabel}  ·  ${profile.endpoint}",
                    color = p.textMuted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (pinging) {
                Text("пинг…", color = p.warning, fontSize = 10.sp, modifier = Modifier.padding(end = 1.dp))
            } else if (profile.latencyMs != null) {
                Text("${profile.latencyMs} ms", color = p.textSecondary, fontSize = 10.sp, modifier = Modifier.padding(end = 1.dp))
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Действия профиля", tint = p.textMuted, modifier = Modifier.size(19.dp))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Замерить пинг") },
                        leadingIcon = { Icon(Icons.Outlined.NetworkPing, contentDescription = null) },
                        onClick = { menuExpanded = false; onPing() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (profile.favorite) "Убрать из избранного" else "В избранное") },
                        leadingIcon = { Icon(if (profile.favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, contentDescription = null) },
                        onClick = { menuExpanded = false; onFavorite() },
                    )
                    DropdownMenuItem(
                        text = { Text("Редактировать") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = { menuExpanded = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text("Скопировать ссылку") },
                        leadingIcon = { Icon(Icons.Outlined.ContentPaste, contentDescription = null) },
                        onClick = { menuExpanded = false; onCopy() },
                    )
                    DropdownMenuItem(
                        text = { Text("Удалить", color = p.danger) },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = p.danger) },
                        onClick = { menuExpanded = false; onRemove() },
                    )
                }
            }
        }
    }
}

@Composable
private fun RoutingPage(
    viewModel: VibeTunViewModel,
    onExportRules: () -> Unit,
    onImportRules: () -> Unit,
) {
    val p = LocalGorePalette.current
    var appsSection by remember { mutableStateOf(false) }
    var appSearch by remember { mutableStateOf("") }
    var ruleInput by remember { mutableStateOf("") }
    var ruleAction by remember { mutableStateOf(RouteAction.DIRECT) }
    val filteredApps = remember(viewModel.installedApps, appSearch) {
        viewModel.installedApps.filter { app ->
            app.label.contains(appSearch, ignoreCase = true) || app.packageName.contains(appSearch, ignoreCase = true)
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        PageHeading(title = "Маршрутизация", subtitle = "Правила доменов/IP и маршруты приложений")

        // Переключатель разделов: слева «Домены и IP», справа «Приложения».
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(p.cardAlt)
                .border(BorderStroke(1.dp, p.border), RoundedCornerShape(9.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            listOf(false to "Домены и IP", true to "Приложения").forEach { (apps, label) ->
                val selected = appsSection == apps
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (selected) p.accent else Color.Transparent)
                        .clickable { appsSection = apps }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (selected) Color.White else p.textMuted,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
        Spacer(Modifier.height(13.dp))

        if (!appsSection) {
        // ==================== правила доменов/IP ====================
        PanelCard(Modifier.fillMaxWidth().padding(bottom = 13.dp)) {
            Column(Modifier.padding(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Правила маршрутизации", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (viewModel.activeRouteSet.isBlank()) "Маршруты не созданы" else "Маршруты: ${viewModel.activeRouteSet} · ${viewModel.routeRules.size} правил",
                            color = p.textMuted,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
                Text(
                    "Домен (yandex.ru — с поддоменами), IP/CIDR (1.2.3.0/24) или ключевое слово (youtube — regex-вхождение). DIRECT уходит мимо прокси.",
                    color = p.textMuted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )

                // строка добавления: поле ввода на всю ширину, под ним три равные кнопки
                OutlinedTextField(
                    value = ruleInput,
                    onValueChange = { ruleInput = it },
                    placeholder = { Text("Домен, IP или ключевое слово", fontSize = 12.sp) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = p.text, fontSize = 13.sp),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(RouteAction.PROXY, RouteAction.DIRECT).forEach { action ->
                        val selected = ruleAction == action
                        Surface(
                            // shape задаём самому Surface: рамка (border) рисуется по shape,
                            // а clip() до Surface резал только фон — окантовка оставалась квадратной.
                            modifier = Modifier
                                .weight(1f)
                                .clickable { ruleAction = action },
                            shape = RoundedCornerShape(7.dp),
                            color = if (selected) (if (action == RouteAction.DIRECT) p.warningSoft else p.accentSoft) else p.cardAlt,
                            border = BorderStroke(1.dp, if (selected) (if (action == RouteAction.DIRECT) p.warning else p.accent) else p.border),
                        ) {
                            Text(
                                if (action == RouteAction.DIRECT) "DIRECT" else "PROXY",
                                color = if (selected) (if (action == RouteAction.DIRECT) p.warning else p.accent) else p.textMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            )
                        }
                    }
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                viewModel.addRouteRule(ruleInput, ruleAction)
                                ruleInput = ""
                            },
                        shape = RoundedCornerShape(7.dp),
                        color = p.accentSoft,
                        border = BorderStroke(1.dp, p.accent),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, tint = p.accent, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Добавить", color = p.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }

                // добавленные правила — под строкой добавления
                viewModel.routeRules.forEach { rule ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            rule.value,
                            color = p.text,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        StatusTag(
                            text = if (rule.action == RouteAction.DIRECT) "DIRECT" else "PROXY",
                            tone = if (rule.action == RouteAction.DIRECT) p.warning else p.success,
                            onClick = { viewModel.toggleRouteRuleAction(rule) },
                        )
                        Spacer(Modifier.width(6.dp))
                        IconButton(
                            onClick = { viewModel.removeRouteRule(rule) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = "Удалить правило", tint = p.textMuted, modifier = Modifier.size(15.dp))
                        }
                    }
                }

                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryActionButton("Экспорт маршрутов", Icons.Outlined.Upload, onExportRules, Modifier.weight(1f))
                    SecondaryActionButton("Импорт маршрутов", Icons.Outlined.Download, onImportRules, Modifier.weight(1f))
                }
            }
        }
        } else {
        // ==================== приложения: маршрут по умолчанию и per-app ====================
        Text("ПО УМОЛЧАНИЮ ДЛЯ ПРИЛОЖЕНИЙ", color = p.textMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(p.cardAlt)
                .border(BorderStroke(1.dp, p.border), RoundedCornerShape(9.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            AppDefaultRoute.entries.forEach { route ->
                val selected = viewModel.appDefaultRoute == route
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (selected) p.accent else Color.Transparent)
                        .clickable { viewModel.updateAppDefaultRoute(route) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        route.label,
                        color = if (selected) Color.White else p.textMuted,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
        Text(
            if (viewModel.appDefaultRoute == AppDefaultRoute.PROXY) {
                "Все приложения идут через прокси, кроме отмеченных ниже DIRECT. Для проксируемых приложений домены и IP из раздела «Домены и IP» действуют поверх: указанный там DIRECT-домен пойдёт напрямую."
            } else {
                "Все приложения идут мимо прокси, кроме отмеченных ниже PROXY. Для проксируемых приложений домены и IP из раздела «Домены и IP» действуют поверх: указанный там DIRECT-домен пойдёт напрямую."
            },
            color = p.textMuted,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            modifier = Modifier.padding(top = 7.dp, bottom = 8.dp),
        )
        PanelCard(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp)) {
            Column(Modifier.padding(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Приложения в туннеле", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("PROXY: ${viewModel.selectedPackages.size} · DIRECT: ${viewModel.directPackages.size}", color = p.textMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                }
                Text(
                    "Отметка у приложения важнее маршрута по умолчанию: PROXY — приложение через VPN, DIRECT — мимо туннеля.",
                    color = p.warning,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                SearchInput(
                    value = appSearch,
                    onValueChange = { appSearch = it },
                    placeholder = "Поиск приложений",
                    modifier = Modifier.padding(top = 10.dp, bottom = 7.dp),
                )
                if (viewModel.installedApps.isEmpty()) {
                    Text("Ищем приложения с иконкой на главном экране…", color = p.textMuted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 12.dp))
                } else if (filteredApps.isEmpty()) {
                    Text("Приложения не найдены.", color = p.textMuted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    filteredApps.take(80).forEach { app ->
                        val route = when {
                            app.packageName in viewModel.directPackages -> "direct"
                            app.packageName in viewModel.selectedPackages -> "proxy"
                            else -> null
                        }
                        InstalledAppRow(
                            app = app,
                            route = route,
                            onRoute = { viewModel.setAppRoute(app.packageName, it) },
                        )
                    }
                    if (filteredApps.size > 80) {
                        Text("Показаны первые 80 приложений — уточните поиск.", color = p.textMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
        }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ModeCard(mode: ProxyMode, selected: Boolean, onClick: () -> Unit) {
    val p = LocalGorePalette.current
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) p.accentSoft else p.card,
        border = BorderStroke(1.dp, if (selected) p.accent.copy(alpha = 0.75f) else p.border),
    ) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(31.dp).clip(RoundedCornerShape(8.dp)).background(if (selected) p.accent.copy(alpha = 0.14f) else p.cardAlt),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (mode == ProxyMode.AllApps) Icons.Outlined.Shield else Icons.Outlined.Apps, contentDescription = null,
                    tint = if (selected) p.accent else p.textSecondary, modifier = Modifier.size(17.dp))
            }
            Column(Modifier.weight(1f).padding(start = 10.dp, end = 7.dp)) {
                Text(mode.label, color = p.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(mode.description, color = p.textMuted, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 3.dp))
            }
            if (selected) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(p.accent), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Check, contentDescription = "Выбрано", tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun InstalledAppRow(app: InstalledApp, route: String?, onRoute: (String?) -> Unit) {
    val p = LocalGorePalette.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(33.dp).clip(RoundedCornerShape(9.dp)).background(p.cardAlt), contentAlignment = Alignment.Center) {
            Text(app.label.take(1).uppercase(), color = p.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp, end = 3.dp)) {
            Text(app.label, color = p.text, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName, color = p.textMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        AppRouteChip("PROXY", route == "proxy", p.accent, p.accentSoft) { onRoute(if (route == "proxy") null else "proxy") }
        Spacer(Modifier.width(5.dp))
        AppRouteChip("DIRECT", route == "direct", p.warning, p.warningSoft) { onRoute(if (route == "direct") null else "direct") }
    }
}

@Composable
private fun AppRouteChip(
    label: String,
    selected: Boolean,
    activeColor: Color,
    activeSoft: Color,
    onClick: () -> Unit,
) {
    val p = LocalGorePalette.current
    Surface(
        // см. строку добавления правил: border рисуется по shape самого Surface,
        // внешний clip() её не скруглял.
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        color = if (selected) activeSoft else p.cardAlt,
        border = BorderStroke(1.dp, if (selected) activeColor else p.border),
    ) {
        Text(
            label,
            color = if (selected) activeColor else p.textMuted,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SettingsPage(
    viewModel: VibeTunViewModel,
    onOpenBatterySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onBatteryStatusRefresh: () -> Unit,
) {
    LaunchedEffect(Unit) { onBatteryStatusRefresh() }
    val p = LocalGorePalette.current
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        PageHeading(title = "Настройки", subtitle = "Оформление, фон и системные функции")
        PanelCard(Modifier.fillMaxWidth().padding(top = 5.dp)) {
            Column(Modifier.padding(15.dp)) {
                Text("ВНЕШНИЙ ВИД", color = p.textMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                SettingToggleRow(
                    icon = if (viewModel.darkTheme) Icons.Outlined.DarkMode else Icons.Outlined.LightMode,
                    title = "Тёмная тема приложения",
                    description = "",
                    checked = viewModel.darkTheme,
                    onCheckedChange = viewModel::updateDarkTheme,
                )
                AccentColorRow(
                    currentArgb = viewModel.accentColorArgb,
                    onColorSaved = viewModel::updateAccentColor,
                )
            }
        }

        PanelCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Column(Modifier.padding(15.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.BatteryChargingFull, contentDescription = null, tint = if (viewModel.batteryOptimizationExempt) p.success else p.warning, modifier = Modifier.size(19.dp))
                    Column(Modifier.weight(1f).padding(start = 9.dp)) {
                        Text("Работа в фоне", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (viewModel.batteryOptimizationExempt) "Оптимизация батареи отключена для VibeTun" else "Рекомендуется разрешить работу без ограничений",
                            color = if (viewModel.batteryOptimizationExempt) p.success else p.warning,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
                Text(
                    "VibeTun не может снять системные ограничения самостоятельно. В Android откройте «Батарея → Без ограничений»; на некоторых устройствах также включите автозапуск и снимите ограничения фоновой активности.",
                    color = p.textMuted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryActionButton("Оптимизация батареи", Icons.Outlined.BatteryChargingFull, onOpenBatterySettings, Modifier.weight(1f))
                    SecondaryActionButton("Настройки VibeTun", Icons.Outlined.Settings, onOpenAppSettings, Modifier.weight(1f))
                }
            }
        }

        PanelCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Column(Modifier.padding(15.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Security, contentDescription = null, tint = p.accent, modifier = Modifier.size(19.dp))
                    Text("Ядро прокси", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 9.dp))
                    Spacer(Modifier.weight(1f))
                    StatusTag(
                        text = if (NativeCoreStatus.isIntegrated) "ГОТОВО" else "НЕ СОБРАНО",
                        tone = if (NativeCoreStatus.isIntegrated) p.success else p.warning,
                    )
                }
                Text(
                    NativeCoreStatus.message,
                    color = p.textSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 9.dp),
                )
                Text(
                    if (NativeCoreStatus.isIntegrated) {
                        "sing-box работает внутри VpnService: Android создаёт TUN, а ядро обрабатывает TCP/UDP и отправляет соединения через выбранный outbound. Настройки выбранных приложений применяются на уровне Android VPN."
                    } else {
                        "Для этой сборки не найден libbox. Сначала выполните scripts/build-libbox-android.sh и только затем собирайте APK — приложение не будет запускать фиктивный VPN."
                    },
                    color = p.textMuted,
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 7.dp),
                )
                Spacer(Modifier.height(13.dp))
                Text("ПОДДЕРЖИВАЕМЫЕ ПРОТОКОЛЫ", color = p.textMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp)
                Text(
                    "VLESS · VMess · Trojan · Shadowsocks · SOCKS · HTTP/HTTPS · Hysteria · Hysteria 2 · TUIC · WireGuard / AmneziaWG · sing-box JSON",
                    color = p.textSecondary,
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 5.dp),
                )
                Text(
                    "SSH, SSR, AnyTLS и MTProto можно импортировать, но это ядро их не подключает.",
                    color = p.textMuted,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        PanelCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Column(Modifier.padding(15.dp)) {
                Text("ДИАГНОСТИКА", color = p.textMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(
                    "Логи приложения и ядра прокси пишутся в файлы. Отправьте их разработчику, если что-то не работает.",
                    color = p.textMuted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val context = LocalContext.current
                    SecondaryActionButton("Поделиться логами", Icons.Outlined.Share, {
                        AppLog.i("UI", "Пользователь отправил логи")
                        // Логи всех процессов: UI (vibetun-main), sing-box (vibetun-vpn), AmneziaWG (vibetun-awg), core.log.
                        val logFiles = AppLog.shareableFiles(context)
                        if (logFiles.isEmpty()) {
                            Toast.makeText(context, "Лог-файлы ещё не созданы", Toast.LENGTH_SHORT).show()
                            return@SecondaryActionButton
                        }
                        runCatching {
                            val uris = ArrayList(
                                logFiles.map { file ->
                                    FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file,
                                    )
                                },
                            )
                            val sendIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                type = "text/plain"
                                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Отправить логи VibeTun"))
                        }.onFailure { error ->
                            Toast.makeText(context, "Не удалось отправить логи: ${error.message}", Toast.LENGTH_LONG).show()
                        }
                    }, Modifier.weight(1f))
                }
            }
        }

        PanelCard(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 17.dp)) {
            Column(Modifier.padding(15.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tune, contentDescription = null, tint = p.accent, modifier = Modifier.size(19.dp))
                    Column(Modifier.padding(start = 9.dp)) {
                        Text("Быстрый доступ", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Переключатель VibeTun в шторке Android", color = p.textMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                }
                Text(
                    "Добавьте «VibeTun Proxy» через карандаш в панели быстрых настроек. Короткое нажатие включает или выключает прокси; долгое нажатие открывает VibeTun на экране настроек плитки.",
                    color = p.textSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 9.dp),
                )
                Text("VibeTun Android · ${viewModel.appVersionLabel()}", color = p.textMuted, fontSize = 9.sp, modifier = Modifier.padding(top = 12.dp))
            }
        }

        // Обновление — всегда последний раздел настроек.
        PanelCard(Modifier.fillMaxWidth().padding(bottom = 17.dp)) {
            Column(Modifier.padding(15.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = null, tint = p.accent, modifier = Modifier.size(19.dp))
                    Column(Modifier.padding(start = 9.dp)) {
                        Text("Обновление", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Установка новых версий поверх текущей", color = p.textMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    SecondaryActionButton(
                        if (viewModel.updateChecking) "Проверяем…" else "Проверить обновления",
                        Icons.Outlined.Refresh,
                        onClick = { viewModel.checkForUpdates() },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (viewModel.updateDownloading) {
                    Text(
                        "Скачивание обновления… ${viewModel.updateProgress}%",
                        color = p.textSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    LinearProgressIndicator(
                        progress = { viewModel.updateProgress / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        color = p.accent,
                        trackColor = p.cardAlt,
                    )
                }
                viewModel.updateStatusLine?.let { status ->
                    Text(
                        status,
                        color = if (status.startsWith("Доступна")) p.success else p.textMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }

    // Скачанное обновление отдаём системному установщику (стандартный диалог установки).
    val pendingInstall = viewModel.pendingInstallApk
    LaunchedEffect(pendingInstall) {
        val apk = pendingInstall ?: return@LaunchedEffect
        runCatching { context.startActivity(UpdateManager.installIntent(context, apk)) }
            .onFailure { Toast.makeText(context, "Не удалось открыть установщик: ${it.message}", Toast.LENGTH_LONG).show() }
        viewModel.consumePendingInstall()
    }
}

@Composable
private fun AccentColorRow(currentArgb: Int, onColorSaved: (Int) -> Unit) {
    val p = LocalGorePalette.current
    var showPicker by remember { mutableStateOf(false) }
    val current = if (currentArgb != 0) Color(currentArgb) else p.accent
    Row(Modifier.fillMaxWidth().padding(top = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 7.dp)) {
            Text("Цвет акцента приложения", color = p.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text("Нажмите на круг, чтобы выбрать цвет", color = p.textMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(current)
                .border(BorderStroke(2.dp, p.border), CircleShape)
                .clickable { showPicker = true },
        )
    }
    if (showPicker) {
        AccentColorPickerDialog(
            initial = current,
            onDismiss = { showPicker = false },
            onSave = { color ->
                onColorSaved(color.toArgb())
                showPicker = false
            },
        )
    }
}

/** Выбор цвета акцента: цветовое колесо (оттенок+насыщенность) и ползунок яркости. */
@Composable
private fun AccentColorPickerDialog(initial: Color, onDismiss: () -> Unit, onSave: (Color) -> Unit) {
    val p = LocalGorePalette.current
    val hsv = remember {
        val array = FloatArray(3)
        android.graphics.Color.colorToHSV(initial.toArgb(), array)
        array
    }
    var hue by remember { mutableStateOf(hsv[0]) }
    var saturation by remember { mutableStateOf(hsv[1].coerceIn(0f, 1f)) }
    var value by remember { mutableStateOf(hsv[2].coerceIn(0.15f, 1f)) }
    val selected = Color.hsv(hue, saturation, value)
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = p.panel,
        titleContentColor = p.text,
        textContentColor = p.textSecondary,
        title = { Text("Цвет акцента", fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(230.dp)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset -> updateWheel(offset, size.width.toFloat()) { h, s -> hue = h; saturation = s } },
                                onDrag = { change, _ -> updateWheel(change.position, size.width.toFloat()) { h, s -> hue = h; saturation = s } },
                            )
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val radius = size.minDimension / 2f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        drawCircle(
                            brush = Brush.sweepGradient(
                                listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
                                center,
                            ),
                            radius = radius,
                            center = center,
                        )
                        drawCircle(
                            brush = Brush.radialGradient(listOf(Color.White, Color.Transparent), center, radius),
                            radius = radius,
                            center = center,
                        )
                        val angle = Math.toRadians(hue.toDouble())
                        val marker = Offset(
                            center.x + (kotlin.math.cos(angle) * radius * saturation).toFloat(),
                            center.y + (kotlin.math.sin(angle) * radius * saturation).toFloat(),
                        )
                        drawCircle(Color.White, radius = 9.dp.toPx(), center = marker)
                        drawCircle(selected, radius = 6.5.dp.toPx(), center = marker)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(selected)
                            .border(BorderStroke(1.dp, p.border), CircleShape),
                    )
                    Text(
                        "#" + Integer.toHexString(selected.toArgb()).uppercase().padStart(8, '0').drop(2),
                        color = p.textSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 9.dp),
                    )
                }
                Text("Яркость", color = p.textMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 10.dp).align(Alignment.Start))
                androidx.compose.material3.Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = 0.15f..1f,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = selected,
                        activeTrackColor = p.accent,
                        inactiveTrackColor = p.cardAlt,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selected) }) {
                Text("Сохранить", color = p.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = p.textSecondary) }
        },
    )
}

private fun updateWheel(offset: Offset, widthPx: Float, onPick: (Float, Float) -> Unit) {
    val radius = widthPx / 2f
    val dx = offset.x - radius
    val dy = offset.y - radius
    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
    // SweepGradient колеса начинается справа (красный) и идёт по часовой стрелке —
    // угол atan2 в экранных координатах (y вниз) совпадает с оттенком как есть.
    var degrees = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble()))
    if (degrees < 0) degrees += 360.0
    onPick(degrees.toFloat(), (distance / radius).coerceIn(0f, 1f))
}

@Composable
private fun SettingToggleRow(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val p = LocalGorePalette.current
    Row(Modifier.fillMaxWidth().padding(top = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = p.textSecondary, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f).padding(start = 9.dp, end = 7.dp)) {
            Text(title, color = p.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            if (description.isNotBlank()) {
                Text(description, color = p.textMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = p.accent,
                uncheckedThumbColor = p.textSecondary,
                uncheckedTrackColor = p.cardAlt,
                uncheckedBorderColor = p.border,
            ),
        )
    }
}

@Composable
private fun PageHeading(title: String, subtitle: String, trailing: @Composable (() -> Unit)? = null) {
    val p = LocalGorePalette.current
    Row(
        Modifier.fillMaxWidth().padding(top = 11.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp)
            Text(subtitle, color = p.textMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (trailing != null) trailing()
    }
}

@Composable
private fun SearchInput(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val p = LocalGorePalette.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = p.textMuted, modifier = Modifier.size(18.dp)) },
        placeholder = { Text(placeholder, fontSize = 12.sp, color = p.textMuted) },
        textStyle = MaterialTheme.typography.bodySmall.copy(color = p.text, fontSize = 13.sp),
        shape = RoundedCornerShape(9.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = p.text,
            unfocusedTextColor = p.text,
            focusedBorderColor = p.accent,
            unfocusedBorderColor = p.border,
            focusedContainerColor = p.input,
            unfocusedContainerColor = p.input,
            cursorColor = p.accent,
        ),
    )
}

@Composable
private fun PanelCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = LocalGorePalette.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(11.dp),
        color = p.card,
        border = BorderStroke(1.dp, p.border),
        content = content,
    )
}

@Composable
private fun StatusTag(text: String, tone: Color, onClick: (() -> Unit)? = null) {
    val p = LocalGorePalette.current
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(tone.copy(alpha = if (p.window == Color(0xFF191B1D)) 0.13f else 0.1f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(text, color = tone, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp, maxLines = 1)
    }
}

@Composable
private fun PrimaryActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val p = LocalGorePalette.current
    Row(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) p.accent else p.accent.copy(alpha = 0.45f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun AddButton(onClick: () -> Unit) {
    val p = LocalGorePalette.current
    Row(
        Modifier
            .height(31.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(p.accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
        Text("Добавить", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 3.dp))
    }
}

@Composable
private fun SmallActionButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    val p = LocalGorePalette.current
    Row(
        Modifier
            .height(31.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(p.cardAlt)
            .border(BorderStroke(1.dp, p.border), RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = p.textSecondary, modifier = Modifier.size(14.dp))
        Text(label, color = p.textSecondary, fontSize = 10.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 5.dp))
    }
}

@Composable
private fun SecondaryActionButton(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalGorePalette.current
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(p.cardAlt)
            .border(BorderStroke(1.dp, p.border), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = null, tint = p.textSecondary, modifier = Modifier.size(17.dp))
        Text(label, color = p.textSecondary, fontSize = 9.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ImportProfileDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    val p = LocalGorePalette.current
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        shape = RoundedCornerShape(14.dp),
        containerColor = p.panel,
        titleContentColor = p.text,
        textContentColor = p.textSecondary,
        title = { Text("Добавить профиль", fontWeight = FontWeight.SemiBold, fontSize = 18.sp) },
        text = {
            Column {
                Text("Вставьте ссылку, подписку или JSON-конфиг sing-box.", fontSize = 12.sp, lineHeight = 17.sp)
                DialogTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = "vless://…  ·  vmess://…  ·  JSON",
                    modifier = Modifier.padding(top = 11.dp),
                    minLines = 5,
                    maxLines = 9,
                )
                Text("Профили и ключи остаются в локальном хранилище приложения.", color = p.textMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 7.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(text) }, enabled = text.isNotBlank()) {
                Text("Добавить", color = if (text.isNotBlank()) p.accent else p.textMuted, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = p.textSecondary) } },
    )
}

@Composable
private fun EditProfileDialog(
    profile: ProxyProfile,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    val p = LocalGorePalette.current
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    var source by remember(profile.id) { mutableStateOf(profile.source) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        shape = RoundedCornerShape(14.dp),
        containerColor = p.panel,
        titleContentColor = p.text,
        textContentColor = p.textSecondary,
        title = { Text("Редактировать профиль", fontWeight = FontWeight.SemiBold, fontSize = 18.sp) },
        text = {
            Column {
                StatusTag(profile.protocolLabel, protocolTint(profile.protocol))
                DialogTextField(value = name, onValueChange = { name = it }, placeholder = "Имя профиля", modifier = Modifier.padding(top = 11.dp), minLines = 1, maxLines = 1)
                DialogTextField(value = source, onValueChange = { source = it }, placeholder = "Ссылка или JSON", modifier = Modifier.padding(top = 9.dp), minLines = 4, maxLines = 8)
                Text("Секреты профиля отображаются здесь и хранятся локально.", color = p.textMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, source) }, enabled = source.isNotBlank()) {
                Text("Сохранить", color = if (source.isNotBlank()) p.accent else p.textMuted, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = p.textSecondary) } },
    )
}

@Composable
private fun ConfirmRemoveDialog(profileName: String, onDismiss: () -> Unit, onRemove: () -> Unit) {
    val p = LocalGorePalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = p.panel,
        titleContentColor = p.text,
        textContentColor = p.textSecondary,
        title = { Text("Удалить профиль?", fontWeight = FontWeight.SemiBold) },
        text = { Text("Профиль «$profileName» будет удалён с этого устройства.", fontSize = 12.sp) },
        confirmButton = { TextButton(onClick = onRemove) { Text("Удалить", color = p.danger, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = p.textSecondary) } },
    )
}

/** Диалог доступного обновления: описание релиза + скачивание с прогрессом. */
@Composable
private fun UpdateAvailableDialog(
    release: UpdateManager.Release,
    downloading: Boolean,
    progress: Int,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    val p = LocalGorePalette.current
    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        shape = RoundedCornerShape(14.dp),
        containerColor = p.panel,
        titleContentColor = p.text,
        textContentColor = p.textSecondary,
        title = { Text("Доступна версия ${release.tag}", fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
        text = {
            Column {
                val notes = release.notes.trim()
                if (notes.isNotBlank()) {
                    Text(
                        notes.take(1500),
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(7.dp))
                            .background(p.cardAlt)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (downloading) {
                    Text("Скачивание… $progress%", fontSize = 11.sp, color = p.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = p.accent,
                        trackColor = p.cardAlt,
                    )
                } else {
                    Text(
                        "Обновление установится поверх текущей версии, профили и настройки сохранятся.",
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDownload, enabled = !downloading) {
                Text(
                    if (downloading) "Скачивание…" else "Скачать и установить",
                    color = if (downloading) p.textMuted else p.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !downloading) { Text("Позже", color = p.textSecondary) }
        },
    )
}

/** Диалог одноразового импорта подписки по URL (3x-ui /sub/...). */
@Composable
private fun SubscriptionImportDialog(
    url: String,
    loading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onFetch: (allowInsecure: Boolean) -> Unit,
) {
    val p = LocalGorePalette.current
    var allowInsecure by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        shape = RoundedCornerShape(14.dp),
        containerColor = p.panel,
        titleContentColor = p.text,
        textContentColor = p.textSecondary,
        title = { Text("Подписка", fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
        text = {
            Column {
                Text(
                    "По ссылке будет загружен список профилей и добавлен в приложение.",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
                Text(
                    url.take(64) + if (url.length > 64) "…" else "",
                    color = p.textMuted,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(7.dp))
                        .clickable(enabled = !loading) { allowInsecure = !allowInsecure }
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = allowInsecure,
                        onCheckedChange = { allowInsecure = it },
                        enabled = !loading,
                        colors = CheckboxDefaults.colors(checkedColor = p.warning, uncheckedColor = p.border),
                    )
                    Text(
                        "Не проверять TLS-сертификат (самоподписанный)\nОпасно: трафик подписки можно перехватить",
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        color = if (allowInsecure) p.warning else p.textSecondary,
                    )
                }
                error?.let {
                    Text(it, color = p.danger, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onFetch(allowInsecure) }, enabled = !loading) {
                Text(
                    if (loading) "Загрузка…" else "Загрузить профили",
                    color = if (loading) p.textMuted else p.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) { Text("Отмена", color = p.textSecondary) }
        },
    )
}

@Composable
private fun PowerOnboardingDialog(
    onDismiss: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val p = LocalGorePalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        shape = RoundedCornerShape(14.dp),
        containerColor = p.panel,
        titleContentColor = p.text,
        textContentColor = p.textSecondary,
        icon = { Icon(Icons.Outlined.BatteryChargingFull, contentDescription = null, tint = p.accent, modifier = Modifier.size(25.dp)) },
        title = { Text("Разрешить VibeTun работать в фоне?", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Text(
                "Чтобы Android реже останавливал прокси при выключенном экране, рекомендуем снять оптимизацию батареи и выбрать «Без ограничений» для VibeTun. Android покажет системное подтверждение — приложение не может изменить это за вас.",
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenBatterySettings) {
                Text("Настроить батарею", color = p.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpenAppSettings) { Text("Ограничения фона", color = p.textSecondary, fontSize = 11.sp) }
                TextButton(onClick = onDismiss) { Text("Позже", color = p.textMuted, fontSize = 11.sp) }
            }
        },
    )
}

@Composable
private fun DialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    minLines: Int,
    maxLines: Int,
) {
    val p = LocalGorePalette.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, color = p.textMuted, fontSize = 11.sp) },
        textStyle = MaterialTheme.typography.bodySmall.copy(color = p.text, fontSize = 11.sp, lineHeight = 16.sp),
        minLines = minLines,
        maxLines = maxLines,
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = p.text,
            unfocusedTextColor = p.text,
            focusedBorderColor = p.accent,
            unfocusedBorderColor = p.border,
            focusedContainerColor = p.input,
            unfocusedContainerColor = p.input,
            cursorColor = p.accent,
        ),
    )
}

private fun protocolTint(protocol: String): Color = when (protocol.lowercase()) {
    "vmess" -> Color(0xFF667BDD)
    "vless" -> Color(0xFF42A78F)
    "trojan" -> Color(0xFFE1666B)
    "shadowsocks", "shadowsocksr" -> Color(0xFF9277E8)
    "wireguard" -> Color(0xFF5598ED)
    "amneziawg" -> Color(0xFF4D87C9)
    "hysteria", "hysteria2" -> Color(0xFF9A75E5)
    "tuic" -> Color(0xFF55A6AE)
    "socks" -> Color(0xFFE3A052)
    "http" -> Color(0xFFD17B49)
    "ssh" -> Color(0xFF75808E)
    "mtproto" -> Color(0xFF47A3CF)
    "anytls" -> Color(0xFF58A1C8)
    else -> Color(0xFF7A8798)
}

private fun profileCountWord(count: Int): String = when {
    count % 10 == 1 && count % 100 != 11 -> "профиль"
    count % 10 in 2..4 && count % 100 !in 12..14 -> "профиля"
    else -> "профилей"
}
