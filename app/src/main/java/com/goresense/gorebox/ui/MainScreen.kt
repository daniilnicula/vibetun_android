package com.goresense.gorebox.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.goresense.gorebox.R
import com.goresense.gorebox.data.AppTab
import com.goresense.gorebox.data.ConnectionState
import com.goresense.gorebox.data.GoreBoxViewModel
import com.goresense.gorebox.data.InstalledApp
import com.goresense.gorebox.data.ProtocolCatalog
import com.goresense.gorebox.data.ProxyMode
import com.goresense.gorebox.data.ProxyProfile
import com.goresense.gorebox.service.NativeCoreStatus

@Composable
fun GoreBoxRoot(
    viewModel: GoreBoxViewModel,
    onConnect: () -> Unit,
    onOpenImport: () -> Unit,
    onPaste: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onBatteryStatusRefresh: () -> Unit,
    onCopyProfile: (ProxyProfile) -> Unit = {},
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
                    AppTab.Routing -> RoutingPage(viewModel)
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
                painter = painterResource(R.drawable.ic_gorebox),
                contentDescription = "GoreBox",
                modifier = Modifier.size(32.dp),
            )
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text("GoreBox", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.15.sp)
            Text("PROXY CLIENT  ·  ANDROID", color = p.textMuted, fontSize = 8.5.sp, letterSpacing = 1.1.sp)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onToggleTheme, modifier = Modifier.size(42.dp)) {
            Icon(
                imageVector = if (darkTheme) Icons.Outlined.Security else Icons.Outlined.Info,
                contentDescription = if (darkTheme) "Сменить тему" else "Сменить тему",
                tint = p.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
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
    viewModel: GoreBoxViewModel,
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
            subtitle = "Серверы и конфигурации GoreBox",
            trailing = {
                SmallActionButton(
                    label = "Импорт",
                    icon = Icons.Outlined.FileOpen,
                    onClick = onOpenImport,
                )
            },
        )
        ConnectionCard(viewModel = viewModel, onConnect = onConnect)

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("СОХРАНЁННЫЕ ПРОФИЛИ", color = p.textMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.05.sp)
                Text("${viewModel.profiles.size} ${profileCountWord(viewModel.profiles.size)}", color = p.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
            }
            SmallActionButton(label = "Вставить", icon = Icons.Outlined.ContentPaste, onClick = onPaste)
            Spacer(Modifier.width(7.dp))
            AddButton(onClick = onAddManual)
        }
        Spacer(Modifier.height(10.dp))

        if (viewModel.profiles.isEmpty()) {
            EmptyProfilesCard(onPaste = onPaste, onImport = onOpenImport, onAdd = onAddManual)
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
                        connected = viewModel.isConnected && profile.id == viewModel.selectedProfileId,
                        onSelect = { viewModel.selectProfile(profile.id) },
                        onEdit = { onEdit(profile) },
                        onRemove = { onRemove(profile) },
                        onFavorite = { viewModel.toggleFavorite(profile) },
                        onCopy = { onCopy(profile) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun ConnectionCard(viewModel: GoreBoxViewModel, onConnect: () -> Unit) {
    val p = LocalGorePalette.current
    val state = viewModel.connectionState
    val dotColor = when (state) {
        ConnectionState.RUNNING -> p.success
        ConnectionState.STARTING -> p.warning
        ConnectionState.ERROR -> p.danger
        ConnectionState.STOPPED -> p.textMuted
    }
    val stateLabel = when (state) {
        ConnectionState.RUNNING -> "ПОДКЛЮЧЕНО"
        ConnectionState.STARTING -> "ПОДКЛЮЧЕНИЕ"
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
                    ConnectionState.RUNNING -> "GoreBox работает"
                    ConnectionState.STARTING -> "Поднимаем туннель…"
                    ConnectionState.ERROR -> "Нужна настройка ядра"
                    ConnectionState.STOPPED -> "Прокси выключен"
                },
                color = p.text,
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp),
            )
            val selected = viewModel.selectedProfile
            Text(
                text = selected?.let { "${it.protocolLabel}  ·  ${it.endpoint}" } ?: "Выберите профиль, чтобы начать",
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
                    viewModel.isConnected -> "Отключить прокси"
                    else -> "Подключить прокси"
                },
                icon = if (viewModel.isConnected) Icons.Outlined.Close else Icons.Outlined.PowerSettingsNew,
                onClick = onConnect,
                enabled = !viewModel.isConnecting,
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
                    value = selected?.protocolLabel ?: "не выбран",
                    modifier = Modifier.weight(1f),
                )
                MiniMetric(
                    title = "МАРШРУТ",
                    value = if (viewModel.proxyMode == ProxyMode.AllApps) "Все приложения" else "Выбранные",
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
private fun EmptyProfilesCard(onPaste: () -> Unit, onImport: () -> Unit, onAdd: () -> Unit) {
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
                "Вставьте ссылку прокси, импортируйте файл или добавьте конфиг вручную.",
                color = p.textMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 5.dp),
            )
            Row(Modifier.padding(top = 15.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallActionButton(label = "Вставить", icon = Icons.Outlined.ContentPaste, onClick = onPaste)
                SmallActionButton(label = "Из файла", icon = Icons.Outlined.FileOpen, onClick = onImport)
                AddButton(onClick = onAdd)
            }
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
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onFavorite: () -> Unit,
    onCopy: () -> Unit,
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
            if (profile.latencyMs != null) {
                Text("${profile.latencyMs} ms", color = p.textSecondary, fontSize = 10.sp, modifier = Modifier.padding(end = 1.dp))
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Действия профиля", tint = p.textMuted, modifier = Modifier.size(19.dp))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
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
private fun RoutingPage(viewModel: GoreBoxViewModel) {
    val p = LocalGorePalette.current
    var appSearch by remember { mutableStateOf("") }
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
        PageHeading(title = "Маршрутизация", subtitle = "Режим прокси и приложения Android")
        PanelCard(Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 13.dp)) {
            Row(Modifier.padding(13.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = p.accent, modifier = Modifier.size(19.dp))
                Column(Modifier.padding(start = 10.dp)) {
                    Text("На Android прокси для приложений работает через VPN", color = p.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Отдельной системной настройки per-app proxy нет. GoreBox использует VpnService и TUN; разрешение VPN запросит Android. Выбор ниже сохраняется для ядра.",
                        color = p.textMuted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        Text("РЕЖИМ", color = p.textMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp))
        ProxyMode.entries.forEach { mode ->
            ModeCard(mode = mode, selected = viewModel.proxyMode == mode, onClick = { viewModel.updateProxyMode(mode) })
            Spacer(Modifier.height(8.dp))
        }
        if (viewModel.proxyMode == ProxyMode.SelectedApps) {
            PanelCard(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp)) {
                Column(Modifier.padding(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Приложения в туннеле", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Выбрано: ${viewModel.selectedPackages.size}", color = p.textMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                        StatusTag(text = "PER-APP", tone = p.accent)
                    }
                    Text(
                        "Список применяется только после подключения совместимого ядра GoreBox.",
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
                            InstalledAppRow(
                                app = app,
                                checked = app.packageName in viewModel.selectedPackages,
                                onToggle = { viewModel.togglePackage(app.packageName) },
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
private fun InstalledAppRow(app: InstalledApp, checked: Boolean, onToggle: () -> Unit) {
    val p = LocalGorePalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(33.dp).clip(RoundedCornerShape(9.dp)).background(p.cardAlt), contentAlignment = Alignment.Center) {
            Text(app.label.take(1).uppercase(), color = p.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp, end = 3.dp)) {
            Text(app.label, color = p.text, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName, color = p.textMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = p.accent,
                uncheckedColor = p.textMuted,
                checkmarkColor = Color.White,
            ),
        )
    }
}

@Composable
private fun SettingsPage(
    viewModel: GoreBoxViewModel,
    onOpenBatterySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onBatteryStatusRefresh: () -> Unit,
) {
    LaunchedEffect(Unit) { onBatteryStatusRefresh() }
    val p = LocalGorePalette.current
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
                    title = "Тёмная тема Windows",
                    description = "Палитра GoreBox для Windows и Android",
                    checked = viewModel.darkTheme,
                    onCheckedChange = viewModel::updateDarkTheme,
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
                            if (viewModel.batteryOptimizationExempt) "Оптимизация батареи отключена для GoreBox" else "Рекомендуется разрешить работу без ограничений",
                            color = if (viewModel.batteryOptimizationExempt) p.success else p.warning,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
                Text(
                    "GoreBox не может снять системные ограничения самостоятельно. В Android откройте «Батарея → Без ограничений»; на некоторых устройствах также включите автозапуск и снимите ограничения фоновой активности.",
                    color = p.textMuted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryActionButton("Оптимизация батареи", Icons.Outlined.BatteryChargingFull, onOpenBatterySettings, Modifier.weight(1f))
                    SecondaryActionButton("Настройки GoreBox", Icons.Outlined.Settings, onOpenAppSettings, Modifier.weight(1f))
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
                Text("ПРОФИЛИ WINDOWS", color = p.textMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp)
                Text(
                    "VLESS · VMess · Trojan · Shadowsocks · SOCKS · HTTP/HTTPS · Hysteria · Hysteria 2 · TUIC · WireGuard · SSH · sing-box JSON", 
                    color = p.textSecondary,
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 5.dp),
                )
                Text(
                    "AmneziaWG, AnyTLS, SSR и MTProto можно импортировать, но это ядро их не подключает.", 
                    color = p.textMuted,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        PanelCard(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 17.dp)) {
            Column(Modifier.padding(15.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tune, contentDescription = null, tint = p.accent, modifier = Modifier.size(19.dp))
                    Column(Modifier.padding(start = 9.dp)) {
                        Text("Быстрый доступ", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Переключатель GoreBox в шторке Android", color = p.textMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                }
                Text(
                    "Добавьте «GoreBox Proxy» через карандаш в панели быстрых настроек. Короткое нажатие включает или выключает прокси; долгое нажатие открывает GoreBox на экране настроек плитки.",
                    color = p.textSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 9.dp),
                )
                Text("GoreBox Android · 0.1.0", color = p.textMuted, fontSize = 9.sp, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
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
            Text(description, color = p.textMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
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
        placeholder = { Text(placeholder, fontSize = 11.sp, color = p.textMuted) },
        textStyle = MaterialTheme.typography.bodySmall.copy(color = p.text, fontSize = 11.sp),
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
private fun StatusTag(text: String, tone: Color) {
    val p = LocalGorePalette.current
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
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
        title = { Text("Разрешить GoreBox работать в фоне?", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Text(
                "Чтобы Android реже останавливал прокси при выключенном экране, рекомендуем снять оптимизацию батареи и выбрать «Без ограничений» для GoreBox. Android покажет системное подтверждение — приложение не может изменить это за вас.",
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
