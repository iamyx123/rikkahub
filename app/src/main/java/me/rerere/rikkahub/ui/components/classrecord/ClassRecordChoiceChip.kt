package me.rerere.rikkahub.ui.components.classrecord

import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick02

/** The native check mark keeps selection readable without relying on color. */
@Composable
internal fun ClassRecordChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    enabled: Boolean = true,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        enabled = enabled,
        leadingIcon = if (selected) ({
            Icon(HugeIcons.Tick02, contentDescription = "已选择", modifier = Modifier.size(FilterChipDefaults.IconSize))
        }) else null,
    )
}
