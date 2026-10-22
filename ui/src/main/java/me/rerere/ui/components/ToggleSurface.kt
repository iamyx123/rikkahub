package me.rerere.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
fun ToggleSurface(
    checked: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(50),
    onClick: () -> Unit = {},
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    role: Role = Role.Button,
    content: @Composable () -> Unit
) {
    val contentColor =
        if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val surfaceContent: @Composable () -> Unit = {
        ProvideTextStyle(MaterialTheme.typography.labelLarge) {
            content()
        }
    }
    if (onLongClick == null) {
        // Preserve the existing native Surface behavior and appearance for all old callers.
        Surface(
            onClick = onClick,
            enabled = enabled,
            color = Color.Transparent,
            contentColor = contentColor,
            modifier = modifier,
            shape = shape,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            content = surfaceContent,
        )
    } else {
        val interactionSource = remember { MutableInteractionSource() }
        Surface(
            color = Color.Transparent,
            contentColor = contentColor,
            modifier = modifier.minimumInteractiveComponentSize().clip(shape).combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(),
                role = role,
                // A disabled short tap must not disable the settings long press.
                enabled = true,
                onClick = { if (enabled) onClick() },
                onLongClick = onLongClick,
                onLongClickLabel = onLongClickLabel,
            ),
            shape = shape,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            content = surfaceContent,
        )
    }
}
