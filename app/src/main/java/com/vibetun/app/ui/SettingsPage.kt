package com.vibetun.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.vibetun.app.data.AppLog
import com.vibetun.app.data.NetworkPersona
import com.vibetun.app.data.VibeTunViewModel
import com.vibetun.app.service.NativeCoreStatus

/**
 * Настройки: внешний вид, работа в фоне, состояние ядра, логи, плитка быстрых
 * настроек и обновления.
 */
@Composable
fun SettingsPage(
    viewModel: VibeTunViewModel,
    onOpenBatterySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onBatteryStatusRefresh: () -> Unit,
) {
    val p = LocalGorePalette.current
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { onBatteryStatusRefresh() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Text("Настройки", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Оформление, фон и системные функции.",
            color = p.textMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
        )

        PanelCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Caption("ВНЕШНИЙ ВИД")
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (viewModel.darkTheme) Icons.Outlined.DarkMode else Icons.Outlined.LightMode,
                        contentDescription = null,
                        tint = p.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        "Тёмная тема",
                        color = p.text,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 9.dp).weight(1f),
                    )
                    Switch(
                        checked = viewModel.darkTheme,
                        onCheckedChange = viewModel::updateDarkTheme,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = p.accent,
                            uncheckedThumbColor = p.textSecondary,
                            uncheckedTrackColor = p.cardAlt,
                            uncheckedBorderColor = p.border,
                        ),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Цвет акцента", color = p.text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "Нажмите на круг, чтобы выбрать цвет",
                            color = p.textMuted,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    val current = if (viewModel.accentColorArgb != 0) Color(viewModel.accentColorArgb) else p.accent
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(current)
                            .border(BorderStroke(2.dp, p.border), CircleShape)
                            .clickable { showPicker = true },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        PanelCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.BatteryChargingFull,
                        contentDescription = null,
                        tint = if (viewModel.batteryOptimizationExempt) p.success else p.warning,
                        modifier = Modifier.size(18.dp),
                    )
                    Column(Modifier.weight(1f).padding(start = 9.dp)) {
                        Text("Работа в фоне", color = p.text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (viewModel.batteryOptimizationExempt) {
                                "Оптимизация батареи отключена для VibeTun"
                            } else {
                                "Рекомендуется разрешить работу без ограничений"
                            },
                            color = if (viewModel.batteryOptimizationExempt) p.success else p.warning,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
                Text(
                    "VibeTun не может снять системные ограничения самостоятельно. В Android откройте «Батарея → Без ограничений»; на некоторых устройствах также включите автозапуск.",
                    color = p.textMuted,
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 9.dp),
                )
                Row(Modifier.padding(top = 11.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton(
                        label = "Оптимизация батареи",
                        onClick = onOpenBatterySettings,
                        icon = Icons.Outlined.BatteryChargingFull,
                        modifier = Modifier.weight(1f),
                    )
                    GhostButton(
                        label = "Настройки VibeTun",
                        onClick = onOpenAppSettings,
                        icon = Icons.Outlined.Settings,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        PanelCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Security, contentDescription = null, tint = p.accent, modifier = Modifier.size(18.dp))
                    Text("Ядро прокси", color = p.text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 9.dp).weight(1f))
                    Chip(
                        text = if (NativeCoreStatus.isIntegrated) "ГОТОВО" else "НЕ СОБРАНО",
                        selected = NativeCoreStatus.isIntegrated,
                        onClick = { },
                        tone = if (NativeCoreStatus.isIntegrated) p.success else p.warning,
                    )
                }
                Text(
                    NativeCoreStatus.message,
                    color = p.textSecondary,
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "sing-box работает внутри VpnService: Android создаёт TUN, а ядро обрабатывает TCP/UDP и отправляет соединения через выбранный outbound.",
                    color = p.textMuted,
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(11.dp))
                Caption("ПОДДЕРЖИВАЕМЫЕ ПРОТОКОЛЫ")
                Text(
                    "VLESS · VMess · Trojan · Shadowsocks · SOCKS · HTTP/HTTPS · Hysteria · Hysteria 2 · TUIC · WireGuard / AmneziaWG · sing-box JSON",
                    color = p.textSecondary,
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        PanelCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Caption("MTU ТУННЕЛЯ SING-BOX")
                Text(
                    "576–9000. По умолчанию — 9000. Уменьшайте значение только при проблемах с загрузкой сайтов или потерей пакетов. AmneziaWG использует MTU из своего конфига.",
                    color = p.textMuted,
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 7.dp),
                )
                var mtuInput by remember(viewModel.tunMtu) { mutableStateOf(viewModel.tunMtu.toString()) }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DialogTextField(
                        value = mtuInput,
                        onValueChange = { mtuInput = it.filter(Char::isDigit).take(4) },
                        placeholder = "MTU",
                        modifier = Modifier.weight(1f),
                        minLines = 1,
                        maxLines = 1,
                    )
                    TextButton(onClick = { viewModel.updateTunMtu(mtuInput.toIntOrNull() ?: 0) }) {
                        Text("Применить", color = p.accent)
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        PanelCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Caption("МАСКИРОВКА ЗАПРОСОВ")
                Text(
                    "Под кого маскироваться",
                    color = p.text,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 10.dp),
                )
                var personaMenuOpen by remember { mutableStateOf(false) }
                val personaLabel = when (viewModel.personaMode) {
                    NetworkPersona.MODE_CHROME -> "Google Chrome"
                    NetworkPersona.MODE_FIREFOX -> "Mozilla Firefox"
                    NetworkPersona.MODE_EDGE -> "Microsoft Edge"
                    NetworkPersona.MODE_APP -> "Без маскировки (VibeTun)"
                    else -> "Авто"
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(p.input)
                                .border(BorderStroke(1.dp, p.border), RoundedCornerShape(9.dp))
                                .clickable { personaMenuOpen = true }
                                .padding(horizontal = 11.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(personaLabel, color = p.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Icon(
                                Icons.Outlined.Tune,
                                contentDescription = null,
                                tint = p.textMuted,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                        DropdownMenu(expanded = personaMenuOpen, onDismissRequest = { personaMenuOpen = false }) {
                            listOf(
                                NetworkPersona.MODE_AUTO to "Авто",
                                NetworkPersona.MODE_CHROME to "Google Chrome",
                                NetworkPersona.MODE_FIREFOX to "Mozilla Firefox",
                                NetworkPersona.MODE_EDGE to "Microsoft Edge",
                                NetworkPersona.MODE_APP to "Без маскировки (VibeTun)",
                            ).forEach { (mode, label) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (mode == viewModel.personaMode) "✓ $label" else label,
                                            fontSize = 12.sp,
                                            color = if (mode == viewModel.personaMode) p.accent else p.text,
                                        )
                                    },
                                    onClick = {
                                        personaMenuOpen = false
                                        viewModel.updatePersonaMode(mode)
                                    },
                                )
                            }
                        }
                    }
                }
                // метка пересчитывается после ручного обновления (personaStamp)
                val personaAge = viewModel.personaStamp.let { viewModel.personaAgeText() }
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        if (personaAge.isNotBlank()) {
                            Text(personaAge, color = p.textMuted, fontSize = 10.sp)
                        }
                    }
                    if (viewModel.personaMode != NetworkPersona.MODE_APP) {
                        TextButton(onClick = { viewModel.refreshPersona() }, enabled = !viewModel.personaBusy) {
                            Text(
                                if (viewModel.personaBusy) "Обновление…" else "Обновить сейчас",
                                color = if (viewModel.personaBusy) p.textMuted else p.accent,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        PanelCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tune, contentDescription = null, tint = p.accent, modifier = Modifier.size(18.dp))
                    Column(Modifier.padding(start = 9.dp)) {
                        Text("Быстрый доступ", color = p.text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        Text("Переключатель VibeTun в шторке Android", color = p.textMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                }
                Text(
                    "Добавьте «VibeTun Proxy» через карандаш в панели быстрых настроек. Короткое нажатие включает или выключает прокси; долгое — открывает приложение.",
                    color = p.textSecondary,
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text("VibeTun Android · ${viewModel.appVersionLabel()}", color = p.textMuted, fontSize = 9.sp, modifier = Modifier.padding(top = 11.dp))
            }
        }

        Spacer(Modifier.height(10.dp))
        PanelCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Share, contentDescription = null, tint = p.accent, modifier = Modifier.size(18.dp))
                    Column(Modifier.padding(start = 9.dp)) {
                        Text("Диагностика", color = p.text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        Text("Логи приложения и ядра прокси", color = p.textMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                }
                Text(
                    "Отправьте логи разработчику, если что-то не работает: в них видно состояние ядра, маршруты и ошибки подключения.",
                    color = p.textMuted,
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton(
                        label = "Поделиться логами",
                        onClick = {
                            AppLog.i("UI", "Пользователь отправил логи")
                            val logFiles = AppLog.shareableFiles(context)
                            if (logFiles.isEmpty()) {
                                Toast.makeText(context, "Лог-файлы ещё не созданы", Toast.LENGTH_SHORT).show()
                                return@GhostButton
                            }
                            runCatching {
                                val uris = ArrayList(
                                    logFiles.map { file ->
                                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
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
                        },
                        icon = Icons.Outlined.Share,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        PanelCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = null, tint = p.accent, modifier = Modifier.size(18.dp))
                    Column(Modifier.padding(start = 9.dp)) {
                        Text("Обновление", color = p.text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        Text("Установка новых версий поверх текущей", color = p.textMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton(
                        label = if (viewModel.updateChecking) "Проверяем…" else "Проверить обновления",
                        onClick = { viewModel.checkForUpdates() },
                        icon = Icons.Outlined.Refresh,
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

        Spacer(Modifier.height(20.dp))
    }

    if (showPicker) {
        AccentColorPickerDialog(
            initial = if (viewModel.accentColorArgb != 0) Color(viewModel.accentColorArgb) else p.accent,
            onDismiss = { showPicker = false },
            onSave = { color ->
                viewModel.updateAccentColor(color.toArgb())
                showPicker = false
            },
        )
    }
}

/** Диалог выбора цвета акцента: цветовое колесо и ползунок яркости. */
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
        properties = DialogProperties(usePlatformDefaultWidth = false),
        shape = RoundedCornerShape(14.dp),
        containerColor = p.panel,
        titleContentColor = p.text,
        textContentColor = p.textSecondary,
        title = { Text("Цвет акцента", fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
        text = {
            Column {
                Box(
                    Modifier
                        .size(210.dp)
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
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(24.dp)
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
                Text("Яркость", color = p.textMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 10.dp))
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = p.textSecondary) } },
    )
}

/**
 * Колесо выбора цвета: точка задаёт оттенок и насыщенность, ползунок — яркость.
 */
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
