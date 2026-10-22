package me.rerere.rikkahub.ui.components.classrecord

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.rikkahub.ui.context.LocalSettings

/** Selection has a native check mark and thicker outline, including on monochrome displays. */
@Composable
internal fun ClassRecordChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    enabled: Boolean = true,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val eink = LocalSettings.current.displaySetting.disableCursorBlink
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        enabled = enabled,
        leadingIcon = if (selected) ({ Icon(HugeIcons.Tick02, contentDescription = "已选择", tint = ink) }) else null,
        border = BorderStroke(if (selected) 2.dp else 1.dp, MaterialTheme.colorScheme.outline),
        colors = if (eink) FilterChipDefaults.filterChipColors(
            labelColor = ink,
            selectedLabelColor = ink,
            disabledLabelColor = ink,
        ) else FilterChipDefaults.filterChipColors(),
    )
}
