package com.vibetun.app.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.NetworkPing
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibetun.app.data.ConnectionState
import com.vibetun.app.data.ProfileGroup
import com.vibetun.app.data.ProxyProfile
import com.vibetun.app.data.TrafficStats
import com.vibetun.app.data.VibeTunViewModel
import kotlinx.coroutines.delay

/**
 * Страница профилей: серверы сгруппированы (подписки — отдельными группами со своим
 * значком обновления), снизу — компактная карточка активного профиля с кнопкой
 * подключения и счётчиками трафика.
 */
@Composable
fun ProfilesPage(
    viewModel: VibeTunViewModel,
    onConnect: () -> Unit,
    onAddProfile: () -> Unit,
    onOpenImport: () -> Unit,
    onPaste: () -> Unit,
    onEdit: (ProxyProfile) -> Unit,
    onRemove: (ProxyProfile) -> Unit,
) {
    val p = LocalGorePalette.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var addMenuOpen by remember { mutableStateOf(false) }
    var openSubscriptionId by remember { mutableStateOf<String?>(null) }
    val collapsedGroups = remember { mutableStateMapOf<String, Boolean>() }

    // Секундный тик: длительность сеанса и трафик обновляются сами.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
            viewModel.refreshTraffic()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        // строка поиска: поле, замер задержки для всех, добавление профиля
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchField(
                value = viewModel.searchQuery,
                onValueChange = { viewModel.searchQuery = it },
                placeholder = "Поиск",
                modifier = Modifier.weight(1f),
            )
            SquareIconButton(
                icon = Icons.Outlined.NetworkPing,
                contentDescription = "Проверить задержку всех профилей",
                onClick = { viewModel.pingAll() },
                tint = if (viewModel.pingingAll) p.accent else p.textSecondary,
            )
            Box {
                SquareIconButton(
                    icon = Icons.Outlined.Add,
                    contentDescription = "Добавить профиль",
                    onClick = { addMenuOpen = true },
                    filled = true,
                )
                DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Вставить") },
                        leadingIcon = { Icon(Icons.Outlined.ContentPaste, contentDescription = null) },
                        onClick = { addMenuOpen = false; onPaste() },
                    )
                    DropdownMenuItem(
                        text = { Text("Импорт из файла") },
                        leadingIcon = { Icon(Icons.Outlined.FileOpen, contentDescription = null) },
                        onClick = { addMenuOpen = false; onOpenImport() },
                    )
                    DropdownMenuItem(
                        text = { Text("Вручную") },
                        leadingIcon = { Icon(Icons.Outlined.Code, contentDescription = null) },
                        onClick = { addMenuOpen = false; onAddProfile() },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        ActiveProfileCard(
            viewModel = viewModel,
            now = now,
            onConnect = onConnect,
            onEdit = { profile -> onEdit(profile) },
        )
        Spacer(Modifier.height(12.dp))

        if (viewModel.profileGroups.isEmpty()) {
            EmptyProfilesCard()
        } else {
            viewModel.profileGroups.forEach { group ->
                val collapsed = collapsedGroups[group.id] == true
                GroupHeader(
                    group = group,
                    collapsed = collapsed,
                    refreshing = group.subscription?.id == viewModel.subscriptionRefreshingId,
                    onToggleCollapsed = { collapsedGroups[group.id] = !collapsed },
                    onRefresh = {
                        val subscription = group.subscription
                        if (subscription != null) viewModel.refreshSubscription(subscription)
                    },
                    onOpen = { group.subscription?.let { openSubscriptionId = it.id } },
                    onPing = { viewModel.pingGroup(group) },
                )
                if (!collapsed) {
                    Spacer(Modifier.height(6.dp))
                    group.profiles.forEach { profile ->
                        ProfileCard(
                            profile = profile,
                            selected = profile.id == viewModel.selectedProfileId,
                            connected = viewModel.isConnected && profile.id == viewModel.activeProfileId,
                            pinging = profile.id in viewModel.pingingIds,
                            onSelect = { viewModel.selectProfile(profile.id) },
                            onEdit = { onEdit(profile) },
                            onRemove = { onRemove(profile) },
                            onFavorite = { viewModel.toggleFavorite(profile) },
                            onPing = { viewModel.pingProfile(profile.id) },
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        Spacer(Modifier.height(20.dp))
    }

    // Диалог группы подписки: обновление, переименование, удаление.
    val subscription = viewModel.subscriptions.firstOrNull { it.id == openSubscriptionId }
    if (subscription != null) {
        SubscriptionGroupDialog(
            subscription = subscription,
            profileCount = viewModel.profileGroups.firstOrNull { it.subscription?.id == subscription.id }
                ?.profiles?.size ?: 0,
            onDismiss = { openSubscriptionId = null },
            onRefresh = {
                viewModel.refreshSubscription(subscription)
                openSubscriptionId = null
            },
            onRename = { name -> viewModel.renameSubscription(subscription, name) },
            onRemove = {
                viewModel.removeSubscription(subscription)
                openSubscriptionId = null
            },
        )
    }
}

/**
 * Заголовок группы: название, значок обновления (для подписки — обновляет подписку
 * и конфиги внутри неё), счётчик профилей и сворачивание группы.
 */
@Composable
private fun GroupHeader(
    group: ProfileGroup,
    collapsed: Boolean,
    refreshing: Boolean,
    onToggleCollapsed: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: () -> Unit,
    onPing: () -> Unit,
) {
    val p = LocalGorePalette.current
    val isSubscription = group.subscription != null
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { if (isSubscription) onOpen() else onToggleCollapsed() }
            .padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (isSubscription) Icons.Outlined.RssFeed else Icons.Outlined.Dns,
            contentDescription = null,
            tint = if (isSubscription) p.accent else p.textMuted,
            modifier = Modifier.size(15.dp),
        )
        Text(
            group.title,
            color = if (isSubscription) p.text else p.textSecondary,
            fontSize = 12.sp,
            fontWeight = if (isSubscription) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 7.dp).weight(1f),
        )
        IconButton(
            onClick = { if (isSubscription) onRefresh() else onPing() },
            modifier = Modifier.size(30.dp),
        ) {
            Icon(
                if (isSubscription) Icons.Outlined.Refresh else Icons.Outlined.NetworkPing,
                contentDescription = if (isSubscription) "Обновить подписку" else "Проверить задержку группы",
                tint = if (refreshing) p.accent else p.textMuted,
                modifier = Modifier.size(16.dp),
            )
        }
        Box(
            Modifier
                .padding(start = 2.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(p.cardAlt)
                .padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            Text("${group.profiles.size}", color = p.textSecondary, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = onToggleCollapsed, modifier = Modifier.size(30.dp)) {
            Icon(
                if (collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                contentDescription = if (collapsed) "Развернуть группу" else "Свернуть группу",
                tint = p.textMuted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Карточка профиля в списке серверов. */
@Composable
private fun ProfileCard(
    profile: ProxyProfile,
    selected: Boolean,
    connected: Boolean,
    pinging: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onFavorite: () -> Unit,
    onPing: () -> Unit,
) {
    val p = LocalGorePalette.current
    val tint = protocolTint(profile.protocol)
    val borderColor = when {
        connected -> p.success
        selected -> p.accent
        else -> p.border
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) p.cardAlt else p.card,
        border = BorderStroke(if (selected || connected) 1.5.dp else 1.dp, borderColor),
    ) {
        Row(
            Modifier.padding(start = 11.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileAvatar(letter = protocolLetter(profile.protocol), tint = tint)
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 10.dp, end = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        profile.name,
                        color = p.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (connected) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(p.success.copy(alpha = 0.15f))
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        ) {
                            Text("АКТИВЕН", color = p.success, fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text(
                    "${profile.protocolLabel} · ${profile.endpoint}",
                    color = p.textMuted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            when {
                pinging -> Text("…", color = p.accent, fontSize = 10.sp, modifier = Modifier.padding(end = 2.dp))
                profile.latencyMs != null -> Text(
                    "${profile.latencyMs} ms",
                    color = if (profile.latencyMs <= 300) p.success else p.textSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(end = 2.dp),
                )
                else -> Text("—", color = p.textMuted, fontSize = 10.sp, modifier = Modifier.padding(end = 2.dp))
            }
            IconButton(onClick = { onPing() }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.NetworkPing,
                    contentDescription = "Проверить задержку",
                    tint = p.textMuted,
                    modifier = Modifier.size(15.dp),
                )
            }
            IconButton(onClick = { onEdit() }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = "Редактировать профиль",
                    tint = p.textMuted,
                    modifier = Modifier.size(15.dp),
                )
            }
            IconButton(onClick = { onRemove() }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = "Удалить профиль",
                    tint = p.danger,
                    modifier = Modifier.size(15.dp),
                )
            }
            IconButton(onClick = { onFavorite() }, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (profile.favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = "В избранное",
                    tint = if (profile.favorite) p.accent else p.textMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** Пустое состояние: профилей ещё нет. */
@Composable
private fun EmptyProfilesCard() {
    val p = LocalGorePalette.current
    PanelCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Пока нет профилей", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Нажмите «+» и вставьте ссылку прокси, импортируйте файл или добавьте конфиг вручную.",
                color = p.textMuted,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                "VLESS · VMess · Trojan · Shadowsocks · Hysteria 2 · TUIC · WireGuard · JSON",
                color = p.textMuted,
                fontSize = 9.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/**
 * Карточка активного профиля: состояние, кнопка подключения, проверка задержки
 * и счётчики трафика/сеанса. Режима «DPI bypass» больше нет — управление только
 * прокси.
 */
@Composable
private fun ActiveProfileCard(
    viewModel: VibeTunViewModel,
    now: Long,
    onConnect: () -> Unit,
    onEdit: (ProxyProfile) -> Unit,
) {
    val p = LocalGorePalette.current
    val state = viewModel.connectionState
    val profile = if (state == ConnectionState.RUNNING || state == ConnectionState.STARTING) {
        viewModel.activeProfile ?: viewModel.selectedProfile
    } else {
        viewModel.selectedProfile
    }
    val statusColor = when (state) {
        ConnectionState.RUNNING -> p.success
        ConnectionState.STARTING, ConnectionState.STOPPING -> p.warning
        ConnectionState.ERROR -> p.danger
        ConnectionState.STOPPED -> p.textMuted
    }
    val statusText = when (state) {
        ConnectionState.RUNNING -> "Подключено"
        ConnectionState.STARTING -> "Подключаемся…"
        ConnectionState.STOPPING -> "Отключаемся…"
        ConnectionState.ERROR -> "Ошибка подключения"
        ConnectionState.STOPPED -> "Отключено"
    }
    val traffic = viewModel.traffic
    val sessionMs = if (viewModel.sessionStartedAtMs == 0L) 0L else (now - viewModel.sessionStartedAtMs).coerceAtLeast(0L)
    val pinging = profile != null && profile.id in viewModel.pingingIds

    PanelCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caption("Активный профиль", modifier = Modifier.weight(1f))
                if (profile != null) {
                    IconButton(
                        onClick = { if (pinging) Unit else viewModel.pingProfile(profile.id) },
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            Icons.Outlined.NetworkPing,
                            contentDescription = "Проверить задержку",
                            tint = if (pinging) p.accent else p.textMuted,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    IconButton(onClick = { onEdit(profile) }, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = "Открыть конфигурацию",
                            tint = p.textMuted,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
            Text(
                profile?.name ?: "Профиль не выбран",
                color = p.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                profile?.let { "${it.protocolLabel} · ${it.endpoint}" } ?: "Добавьте профиль, чтобы подключиться",
                color = p.textMuted,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(statusColor)
                Text(
                    statusText,
                    color = statusColor,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 7.dp),
                )
                Spacer(Modifier.weight(1f))
                profile?.latencyMs?.let {
                    Text("$it ms", color = p.textMuted, fontSize = 10.sp)
                }
            }

            if (state == ConnectionState.ERROR && viewModel.connectionMessage.isNotBlank()) {
                Text(
                    viewModel.connectionMessage,
                    color = p.warning,
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(p.warningSoft)
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                )
            }

            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                label = when {
                    viewModel.isConnecting -> "Подключаемся…"
                    viewModel.isStopping -> "Отключаемся…"
                    viewModel.isConnected -> "Отключить"
                    else -> "Подключить"
                },
                onClick = onConnect,
                enabled = !viewModel.isConnecting && !viewModel.isStopping,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatCell(
                    caption = "ТРАФИК",
                    value = if (traffic == null) {
                        "—"
                    } else {
                        "↑ ${TrafficStats.formatBytes(traffic.uploadBytes)}  ↓ ${TrafficStats.formatBytes(traffic.downloadBytes)}"
                    },
                    modifier = Modifier.weight(1.4f),
                )
                StatCell(
                    caption = "СЕАНС",
                    value = if (viewModel.sessionStartedAtMs == 0L) "—" else TrafficStats.formatDuration(sessionMs),
                    modifier = Modifier.weight(1f),
                )
                StatCell(
                    caption = "ПИНГ",
                    value = profile?.latencyMs?.let { "$it ms" } ?: "—",
                    modifier = Modifier.weight(0.7f),
                )
            }
        }
    }
}
