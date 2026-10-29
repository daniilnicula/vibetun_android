package com.vibetun.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibetun.app.R
import com.vibetun.app.data.AppTab
import com.vibetun.app.data.ConnectionState
import com.vibetun.app.data.ProxyProfile
import com.vibetun.app.data.VibeTunViewModel
import com.vibetun.app.service.NativeCoreStatus

/**
 * Корневой экран VibeTun: верхняя панель со статусом ядра, содержимое раздела
 * и нижняя навигация (Профили · Маршрутизация · Настройки).
 */
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
    val p = LocalGorePalette.current
    val snackbarHost = remember { SnackbarHostState() }
    var showImportDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ProxyProfile?>(null) }
    var removingProfile by remember { mutableStateOf<ProxyProfile?>(null) }

    LaunchedEffect(viewModel.currentTab) {
        if (viewModel.currentTab == AppTab.Routing) {
            viewModel.loadInstalledApps()
            viewModel.refreshRouteSets()
        }
    }
    LaunchedEffect(viewModel.feedback) {
        val message = viewModel.feedback ?: return@LaunchedEffect
        snackbarHost.showSnackbar(message)
        viewModel.clearFeedback()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(p.window)
            .statusBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            AppTitleBar(state = viewModel.connectionState)
            Box(Modifier.weight(1f)) {
                when (viewModel.currentTab) {
                    AppTab.Profiles -> ProfilesPage(
                        viewModel = viewModel,
                        onConnect = onConnect,
                        onAddProfile = { showImportDialog = true },
                        onOpenImport = onOpenImport,
                        onPaste = onPaste,
                        onEdit = { editingProfile = it },
                        onRemove = { removingProfile = it },
                    )
                    AppTab.Routing -> RoutingPage(
                        viewModel = viewModel,
                        onExportRules = onExportRules,
                        onImportRules = onImportRules,
                    )
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
                    containerColor = p.cardAlt,
                    contentColor = p.text,
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
            onCopy = { onCopyProfile(profile) },
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

/**
 * Верхняя панель: логотип (окрашен акцентным цветом темы), название и состояние
 * ядра — вместо прежнего «В сети» здесь состояние самого ядра sing-box.
 */
@Composable
private fun AppTitleBar(state: ConnectionState) {
    val p = LocalGorePalette.current
    val coreReady = NativeCoreStatus.isIntegrated
    val coreBusy = state == ConnectionState.RUNNING || state == ConnectionState.STARTING
    val coreColor = when {
        !coreReady -> p.danger
        coreBusy -> p.success
        else -> p.textMuted
    }
    val coreText = when {
        !coreReady -> "Ядро недоступно"
        coreBusy -> "Ядро в работе"
        else -> "Ядро готово"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.window)
            .padding(start = 14.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(p.card)
                .border(BorderStroke(1.dp, p.border), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.vibetun_logo),
                contentDescription = "VibeTun",
                modifier = Modifier.size(24.dp),
            )
        }
        Text(
            "VibeTun",
            color = p.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 9.dp),
        )
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(p.card)
                .border(BorderStroke(1.dp, p.border), RoundedCornerShape(8.dp))
                .padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(coreColor, size = 7)
            Text(
                coreText,
                color = if (coreReady) p.textSecondary else p.danger,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/** Нижняя навигация: три раздела, выбранный — с подложкой акцентного цвета. */
@Composable
private fun BottomNavigation(current: AppTab, onSelect: (AppTab) -> Unit) {
    val p = LocalGorePalette.current
    val entries = listOf(
        NavEntry(AppTab.Profiles, Icons.Outlined.Shield, "Профили"),
        NavEntry(AppTab.Routing, Icons.Outlined.Route, "Маршрутизация"),
        NavEntry(AppTab.Settings, Icons.Outlined.Settings, "Настройки"),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(p.panel)
            .border(BorderStroke(1.dp, p.border))
            .navigationBarsPadding()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        entries.forEach { entry ->
            val selected = current == entry.tab
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelect(entry.tab) }
                    .padding(vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (selected) p.accent else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        entry.icon,
                        contentDescription = entry.title,
                        tint = if (selected) p.onAccent else p.textMuted,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Text(
                    entry.title,
                    fontSize = 9.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) p.accent else p.textMuted,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private data class NavEntry(val tab: AppTab, val icon: ImageVector, val title: String)
