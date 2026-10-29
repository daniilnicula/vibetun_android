package com.vibetun.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.vibetun.app.data.ProxyProfile
import com.vibetun.app.data.Subscription
import com.vibetun.app.data.UpdateManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Диалог ручного добавления профиля (ссылка, подписка или JSON). */
@Composable
fun ImportProfileDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
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

/** Диалог редактирования профиля. */
@Composable
fun EditProfileDialog(
    profile: ProxyProfile,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onCopy: (() -> Unit)? = null,
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
                Chip(
                    text = profile.protocolLabel,
                    selected = true,
                    onClick = { },
                    tone = protocolTint(profile.protocol),
                )
                DialogTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "Имя профиля",
                    modifier = Modifier.padding(top = 11.dp),
                    minLines = 1,
                    maxLines = 1,
                )
                DialogTextField(
                    value = source,
                    onValueChange = { source = it },
                    placeholder = "Ссылка или JSON",
                    modifier = Modifier.padding(top = 9.dp),
                    minLines = 4,
                    maxLines = 8,
                )
                Text("Секреты профиля отображаются здесь и хранятся локально.", color = p.textMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
                if (onCopy != null) {
                    TextButton(onClick = onCopy, modifier = Modifier.padding(top = 2.dp)) {
                        Text("Скопировать ссылку", color = p.textSecondary, fontSize = 11.sp)
                    }
                }
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

/** Диалог подтверждения удаления профиля. */
@Composable
fun ConfirmRemoveDialog(profileName: String, onDismiss: () -> Unit, onRemove: () -> Unit) {
    val p = LocalGorePalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = p.panel,
        titleContentColor = p.text,
        textContentColor = p.textSecondary,
        title = { Text("Удалить профиль?", fontWeight = FontWeight.SemiBold) },
        text = { Text("Профиль «$profileName» будет удалён с этого устройства.", fontSize = 12.sp) },
        confirmButton = {
            TextButton(onClick = onRemove) {
                Text("Удалить", color = p.danger, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = p.textSecondary) } },
    )
}

/** Диалог доступного обновления: описание релиза и скачивание. */
@Composable
fun UpdateAvailableDialog(
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
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (downloading) {
                    Text("Скачивание… $progress%", fontSize = 11.sp, color = p.textSecondary)
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
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
            TextButton(onClick = onDismiss, enabled = !downloading) {
                Text("Позже", color = p.textSecondary)
            }
        },
    )
}

/** Диалог одноразового импорта подписки по URL. */
@Composable
fun SubscriptionImportDialog(
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
                    "По ссылке будет создана группа подписки: её профили обновляются " +
                        "одним нажатием на значок обновления в заголовке группы.",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
                Text(
                    url.take(64) + if (url.length > 64) "…" else "",
                    color = p.textMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
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
                    if (loading) "Загрузка…" else "Создать группу",
                    color = if (loading) p.textMuted else p.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) {
                Text("Отмена", color = p.textSecondary)
            }
        },
    )
}

/**
 * Диалог группы подписки: обновление подписки (перекачивает ссылку и обновляет
 * конфиги внутри группы), переименование и удаление.
 */
@Composable
fun SubscriptionGroupDialog(
    subscription: Subscription,
    profileCount: Int,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onRename: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val p = LocalGorePalette.current
    var name by remember(subscription.id) { mutableStateOf(subscription.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = p.panel,
        titleContentColor = p.text,
        textContentColor = p.textSecondary,
        title = { Text("Подписка", fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
        text = {
            Column {
                Text("Профилей в группе: $profileCount", fontSize = 12.sp, color = p.text)
                Text(
                    subscription.url.take(72) + if (subscription.url.length > 72) "…" else "",
                    color = p.textMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (subscription.updatedAt > 0L) {
                    Text(
                        "Обновлено: " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                            .format(Date(subscription.updatedAt)),
                        color = p.textMuted,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                DialogTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "Название группы",
                    modifier = Modifier.padding(top = 10.dp),
                    minLines = 1,
                    maxLines = 1,
                )
                TextButton(onClick = onRefresh, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Обновить подписку", color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = onRemove) {
                    Text("Удалить подписку и её профили", color = p.danger, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onRename(name); onDismiss() }) {
                Text("Сохранить", color = p.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть", color = p.textSecondary) } },
    )
}

/** Первый запуск: рекомендация снять ограничения батареи. */
@Composable
fun PowerOnboardingDialog(
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
        icon = {
            Icon(
                Icons.Outlined.BatteryChargingFull,
                contentDescription = null,
                tint = p.accent,
                modifier = Modifier.size(25.dp),
            )
        },
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
                TextButton(onClick = onOpenAppSettings) {
                    Text("Ограничения фона", color = p.textSecondary, fontSize = 11.sp)
                }
                TextButton(onClick = onDismiss) { Text("Позже", color = p.textMuted, fontSize = 11.sp) }
            }
        },
    )
}

/** Поле ввода в диалогах. */
@Composable
fun DialogTextField(
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
