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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Карточка-панель: скруглённый прямоугольник с тонкой рамкой. */
@Composable
fun PanelCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = LocalGorePalette.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = p.card,
        border = BorderStroke(1.dp, p.border),
        content = content,
    )
}

/** Переключатель-сегмент: как «Proxy | DPI bypass» в Windows-версии. */
@Composable
fun SegmentedControl(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalGorePalette.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(p.cardAlt)
            .border(BorderStroke(1.dp, p.border), RoundedCornerShape(11.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) p.accent else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (selected) p.onAccent else p.textMuted,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Поле поиска в стиле Windows-версии. На BasicTextField: фиксированная высота 40 dp
 *  (ровно как у квадратных кнопок рядом) с гарантированным вертикальным центрированием
 *  текста — у OutlinedTextField с явной высотой текст уезжает вниз. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val p = LocalGorePalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(11.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().height(40.dp),
        singleLine = true,
        interactionSource = interactionSource,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = p.text, fontSize = 13.sp),
        cursorBrush = SolidColor(p.accent),
        decorationBox = { innerTextField ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(p.input)
                    .border(BorderStroke(1.dp, if (focused) p.accent else p.border), shape)
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = p.textMuted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(placeholder, fontSize = 12.sp, color = p.textMuted)
                    }
                    innerTextField()
                }
            }
        },
    )
}

/** Главная кнопка (акцентная заливка). */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val p = LocalGorePalette.current
    Row(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (enabled) p.accent else p.accent.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = p.onAccent, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = p.onAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Вторичная кнопка-призрак (тонкая рамка). */
@Composable
fun GhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val p = LocalGorePalette.current
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(p.cardAlt)
            .border(BorderStroke(1.dp, p.border), RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = p.textSecondary, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(label, color = p.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Квадратная кнопка-иконка (как кнопки справа от поиска). */
@Composable
fun SquareIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    filled: Boolean = false,
) {
    val p = LocalGorePalette.current
    Box(
        modifier
            .size(40.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (filled) p.accent else p.card)
            .border(BorderStroke(1.dp, if (filled) p.accent else p.border), RoundedCornerShape(11.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint ?: if (filled) p.onAccent else p.textSecondary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Маленький переключатель-чип (PROXY / DIRECT / BLOCK, тип правила и т.п.). */
@Composable
fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: Color? = null,
) {
    val p = LocalGorePalette.current
    val active = tone ?: p.accent
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(7.dp),
        color = if (selected) active.copy(alpha = 0.16f) else p.cardAlt,
        border = BorderStroke(1.dp, if (selected) active else p.border),
    ) {
        Text(
            text,
            color = if (selected) active else p.textMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
        )
    }
}

/** Подпись раздела (мелкий капс). */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    val p = LocalGorePalette.current
    Text(
        text,
        color = color ?: p.textMuted,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = modifier,
    )
}

/** Ячейка статистики: подпись сверху, значение снизу. */
@Composable
fun StatCell(caption: String, value: String, modifier: Modifier = Modifier, valueColor: Color? = null) {
    val p = LocalGorePalette.current
    Column(modifier) {
        Text(caption, color = p.textMuted, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
        Text(
            value,
            color = valueColor ?: p.text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Точка статуса. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Int = 8) {
    Box(
        modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** Квадрат-аватар профиля: первая буква протокола цветом протокола. */
@Composable
fun ProfileAvatar(letter: String, tint: Color, modifier: Modifier = Modifier, size: Int = 36) {
    Box(
        modifier
            .size(size.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = tint, fontSize = (size / 3.4f).sp, fontWeight = FontWeight.Bold)
    }
}

/** Цвет протокола: используется для аватара и меток. */
fun protocolTint(protocol: String): Color = when (protocol.lowercase()) {
    "vmess" -> Color(0xFF7C8CF0)
    "vless" -> Color(0xFF3FBF8F)
    "trojan" -> Color(0xFFE1666B)
    "shadowsocks", "shadowsocksr" -> Color(0xFF9B7BEA)
    "wireguard" -> Color(0xFF5598ED)
    "amneziawg" -> Color(0xFF7C6BE8)
    "hysteria", "hysteria2" -> Color(0xFF4C8DF6)
    "tuic" -> Color(0xFF3FB8B0)
    "socks" -> Color(0xFFE3A052)
    "http" -> Color(0xFFD17B49)
    "ssh" -> Color(0xFF8A93A0)
    "mtproto" -> Color(0xFF47A3CF)
    "anytls" -> Color(0xFF58A1C8)
    else -> Color(0xFF8A93A0)
}

/** Первая буква протокола для аватара. */
fun protocolLetter(protocol: String): String = when (protocol.lowercase()) {
    "vmess" -> "V"
    "vless" -> "V"
    "trojan" -> "T"
    "shadowsocks" -> "S"
    "shadowsocksr" -> "S"
    "wireguard" -> "W"
    "amneziawg" -> "A"
    "hysteria", "hysteria2" -> "H"
    "tuic" -> "T"
    "socks" -> "S"
    "http" -> "H"
    "ssh" -> "S"
    "mtproto" -> "M"
    "anytls" -> "A"
    "tunnel" -> "{}"
    else -> protocol.take(1).uppercase().ifBlank { "?" }
}
