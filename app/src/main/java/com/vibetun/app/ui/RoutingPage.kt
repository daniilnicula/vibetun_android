package com.vibetun.app.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibetun.app.data.AppDefaultRoute
import com.vibetun.app.data.InstalledApp
import com.vibetun.app.data.RouteAction
import com.vibetun.app.data.RouteRule
import com.vibetun.app.data.VibeTunViewModel

/**
 * Маршрутизация: правила доменов/IP (с типом, действием и включением) и маршруты
 * приложений. Разделы переключаются сегментом, как в Windows-версии.
 */
@Composable
fun RoutingPage(
    viewModel: VibeTunViewModel,
    onExportRules: () -> Unit,
    onImportRules: () -> Unit,
) {
    val p = LocalGorePalette.current
    var appsSection by remember { mutableStateOf(false) }
    var appSearch by remember { mutableStateOf("") }
    var ruleInput by remember { mutableStateOf("") }
    var ruleAction by remember { mutableStateOf(RouteAction.PROXY) }
    var setsMenuOpen by remember { mutableStateOf(false) }
    val filteredApps = remember(viewModel.visibleInstalledApps, appSearch) {
        viewModel.visibleInstalledApps.filter { app ->
            app.label.contains(appSearch, ignoreCase = true) || app.packageName.contains(appSearch, ignoreCase = true)
        }
    }
    // Тип правила определяется автоматически по введённому значению.
    val detectedRuleType = remember(ruleInput) { RouteRule.detectType(ruleInput) }
    val activeRules = viewModel.routeRules.count { it.enabled }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Маршрутизация", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Входящие подключения, TUN, правила для доменов/IP и прил.",
                    color = p.textMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Box {
                SquareIconButton(
                    icon = Icons.Outlined.Tune,
                    contentDescription = "Наборы маршрутов",
                    onClick = { setsMenuOpen = true },
                )
                DropdownMenu(
                    expanded = setsMenuOpen,
                    onDismissRequest = { setsMenuOpen = false },
                ) {
                    if (viewModel.routeSetNames.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("Наборов пока нет", fontSize = 12.sp) },
                            onClick = { setsMenuOpen = false },
                        )
                    }
                    viewModel.routeSetNames.forEach { name ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (name == viewModel.activeRouteSet) "✓ $name" else name,
                                    fontSize = 12.sp,
                                    color = if (name == viewModel.activeRouteSet) p.accent else p.text,
                                )
                            },
                            onClick = {
                                setsMenuOpen = false
                                viewModel.switchRouteSet(name)
                            },
                        )
                    }
                }
            }
        }
        if (viewModel.activeRouteSet.isNotBlank()) {
            Text(
                "Набор правил: ${viewModel.activeRouteSet}",
                color = p.textMuted,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        SegmentedControl(
            labels = listOf("Домены и IP", "Приложения"),
            selectedIndex = if (appsSection) 1 else 0,
            onSelect = { appsSection = it == 1 },
        )
        Spacer(Modifier.height(12.dp))

        if (!appsSection) {
            Text(
                "Российские сервисы и локальные адреса — напрямую, всё остальное через прокси.",
                color = p.textSecondary,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = ruleInput,
                onValueChange = { ruleInput = it },
                placeholder = { Text("vk, youtube.com, 91.108.55.0/24", fontSize = 12.sp, color = p.textMuted) },
                textStyle = MaterialTheme.typography.bodySmall.copy(color = p.text, fontSize = 13.sp),
                singleLine = true,
                shape = RoundedCornerShape(11.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = p.text,
                    unfocusedTextColor = p.text,
                    focusedBorderColor = p.accent,
                    unfocusedBorderColor = p.border,
                    focusedContainerColor = p.input,
                    unfocusedContainerColor = p.input,
                    cursorColor = p.accent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(RouteAction.PROXY, RouteAction.DIRECT, RouteAction.BLOCK).forEach { action ->
                    Chip(
                        text = action.name,
                        selected = ruleAction == action,
                        onClick = { ruleAction = action },
                        tone = actionTone(action),
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(p.accent)
                        .clickable {
                            viewModel.addRouteRule(ruleInput, ruleAction)
                            ruleInput = ""
                        }
                        .padding(horizontal = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, tint = p.onAccent, modifier = Modifier.size(14.dp))
                    Text("Добавить", color = p.onAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
                }
            }

            // Тип правила выбирать не нужно: ядро получает его по самому значению.
            Text(
                "Тип определяется автоматически: ${detectedRuleType.label}",
                color = p.textMuted,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(10.dp))
            Text(
                "${viewModel.routeRules.size} правил ($activeRules активных) · " +
                    "${viewModel.selectedPackages.size + viewModel.directPackages.size} приложений",
                color = p.textMuted,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(6.dp))

            if (viewModel.routeRules.isEmpty()) {
                PanelCard(Modifier.fillMaxWidth()) {
                    Text(
                        "Правил пока нет. Добавьте домен или IP выше — правило уйдёт в конфигурацию ядра.",
                        color = p.textMuted,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            } else {
                viewModel.routeRules.forEach { rule ->
                    RouteRuleRow(
                        rule = rule,
                        onToggle = { viewModel.toggleRouteRuleEnabled(rule) },
                        onAction = { viewModel.toggleRouteRuleAction(rule) },
                        onRemove = { viewModel.removeRouteRule(rule) },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(
                    label = "Экспорт",
                    onClick = onExportRules,
                    icon = Icons.Outlined.Upload,
                    modifier = Modifier.weight(1f),
                )
                GhostButton(
                    label = "Импорт",
                    onClick = onImportRules,
                    icon = Icons.Outlined.Download,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Text(
                "Маршрут по умолчанию для приложений: «Direct» — трафик идёт мимо туннеля, «Proxy» — через туннель.",
                color = p.textSecondary,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(10.dp))
            SegmentedControl(
                labels = AppDefaultRoute.entries.map { it.label },
                selectedIndex = AppDefaultRoute.entries.indexOf(viewModel.appDefaultRoute),
                onSelect = { viewModel.updateAppDefaultRoute(AppDefaultRoute.entries[it]) },
            )
            Spacer(Modifier.height(10.dp))
            SearchField(
                value = appSearch,
                onValueChange = { appSearch = it },
                placeholder = "Поиск приложений",
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Показать системные приложения",
                    color = p.textSecondary,
                    fontSize = 11.5.sp,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = viewModel.showSystemApps,
                    onCheckedChange = { viewModel.updateShowSystemApps(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = p.onAccent,
                        checkedTrackColor = p.accent,
                        uncheckedThumbColor = p.textMuted,
                        uncheckedTrackColor = p.cardAlt,
                        uncheckedBorderColor = p.border,
                    ),
                )
            }
            Spacer(Modifier.height(4.dp))
            if (viewModel.visibleInstalledApps.isEmpty()) {
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
                    Text(
                        "Показаны первые 80 приложений — уточните поиск.",
                        color = p.textMuted,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** Цвет действия правила. */
@Composable
fun actionTone(action: RouteAction): Color {
    val p = LocalGorePalette.current
    return when (action) {
        RouteAction.PROXY -> p.accent
        RouteAction.DIRECT -> p.success
        RouteAction.BLOCK -> p.danger
    }
}

/** Строка правила: галочка, значение, тип, действие и удаление. */
@Composable
private fun RouteRuleRow(
    rule: RouteRule,
    onToggle: () -> Unit,
    onAction: () -> Unit,
    onRemove: () -> Unit,
) {
    val p = LocalGorePalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (rule.enabled) p.card else p.card.copy(alpha = 0.55f))
            .padding(start = 2.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = rule.enabled,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = p.accent, uncheckedColor = p.border),
        )
        Text(
            rule.value,
            color = if (rule.enabled) p.text else p.textMuted,
            fontSize = 12.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 2.dp),
        )
        Text(
            rule.typeLabel,
            color = p.textMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 5.dp),
        )
        Chip(text = rule.action.name, selected = true, onClick = onAction, tone = actionTone(rule.action))
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Outlined.Close, contentDescription = "Удалить правило", tint = p.textMuted, modifier = Modifier.size(15.dp))
        }
    }
}

/** Строка приложения: имя, пакет и метки PROXY/DIRECT. */
@Composable
private fun InstalledAppRow(app: InstalledApp, route: String?, onRoute: (String?) -> Unit) {
    val p = LocalGorePalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(p.card)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(p.cardAlt),
            contentAlignment = Alignment.Center,
        ) {
            Text(app.label.take(1).uppercase(), color = p.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 10.dp, end = 6.dp),
        ) {
            Text(app.label, color = p.text, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName, color = p.textMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Chip(
            text = "PROXY",
            selected = route == "proxy",
            onClick = { onRoute(if (route == "proxy") null else "proxy") },
            tone = p.accent,
        )
        Spacer(Modifier.width(5.dp))
        Chip(
            text = "DIRECT",
            selected = route == "direct",
            onClick = { onRoute(if (route == "direct") null else "direct") },
            tone = p.success,
        )
    }
}
