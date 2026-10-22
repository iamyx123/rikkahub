package me.rerere.rikkahub.ui.components

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AirplaneMode
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.power.ReadingPowerPhase
import me.rerere.rikkahub.data.power.ReadingPowerState

/** The caller checks root access before persisting enabled=true. No network state is assumed here. */
@Composable
fun ReadingPowerButton(
    enabled: Boolean,
    noticeAcknowledged: Boolean,
    delaySeconds: Int,
    state: ReadingPowerState,
    onEnabledChange: suspend (Boolean) -> Unit,
    onNoticeAcknowledged: suspend () -> Unit,
    onDelaySecondsChange: suspend (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showRootNotice by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val status = readingPowerStatus(state.phase, state.ownsFlightMode, state.flightModeEnabled)
    val error = actionError ?: state.error
    val visual = readingPowerButtonVisual(enabled, state.flightModeEnabled, state.phase, error != null)

    fun changeEnabled(value: Boolean, acknowledgeNotice: Boolean = false) {
        if (busy) return
        busy = true
        scope.launch {
            actionError = null
            try {
                if (acknowledgeNotice) {
                    // Remember the app explanation independently of whether the subsequent
                    // real Root check succeeds, is denied, or is cancelled by the manager.
                    onNoticeAcknowledged()
                    showRootNotice = false
                }
                onEnabledChange(value)
                showRootNotice = false
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                actionError = failure.message ?: "未能控制飞行模式，请检查 root 授权后重试。"
                if (!value) showSettings = true
            } finally {
                busy = false
            }
        }
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface),
        modifier = modifier.heightIn(min = 48.dp).widthIn(min = 88.dp, max = 96.dp).clip(MaterialTheme.shapes.small)
            .semantics(mergeDescendants = true) {
                contentDescription = "阅读省电，长按设置"
                toggleableState = if (enabled) ToggleableState.On else ToggleableState.Off
                stateDescription = "${visual.automaticLabel}，${visual.flightLabel}，$status" + (error?.let { "：$it" } ?: "")
            }
            .combinedClickable(
                role = Role.Switch,
                onClickLabel = if (enabled) "关闭阅读省电" else "开启阅读省电",
                onLongClickLabel = "设置阅读省电等待秒数",
                onLongClick = { showSettings = true },
                onClick = {
                    if (busy) showSettings = true
                    else if (!enabled && !noticeAcknowledged) {
                        actionError = null
                        showRootNotice = true
                    } else changeEnabled(!enabled)
                },
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when (visual.flightIcon) {
                    ReadingPowerFlightIcon.FILLED -> Icon(painterResource(R.drawable.ic_reading_flight_filled), contentDescription = null, modifier = Modifier.size(24.dp))
                    ReadingPowerFlightIcon.OUTLINE -> Icon(HugeIcons.AirplaneMode, contentDescription = null, modifier = Modifier.size(24.dp))
                    ReadingPowerFlightIcon.UNKNOWN -> Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        Text("?", fontSize = 24.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
                    }
                }
                visual.phaseLabel?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Column {
                Text(visual.automaticLabel, style = MaterialTheme.typography.labelSmall, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(visual.flightLabel, style = MaterialTheme.typography.labelSmall, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    if (showRootNotice && !noticeAcknowledged && !enabled) {
        AlertDialog(
            onDismissRequest = { if (!busy) showRootNotice = false },
            title = { Text("阅读省电需要 root") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("回答完成并等待设定秒数后，会自动切换飞行模式。关闭开关或退到后台时，恢复此前的飞行模式状态。")
                    Text("此功能需要设备 root 授权。接下来会检查权限，未 root 或未授权时不会启用。")
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = { changeEnabled(true, acknowledgeNotice = true) }) {
                    Text(if (busy) "正在检查…" else "检查权限并启用")
                }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { showRootNotice = false }) { Text("取消") } },
        )
    }

    if (showSettings) {
        var seconds by remember { mutableStateOf(delaySeconds.toString()) }
        var invalid by remember { mutableStateOf(false) }
        val parsed = parseReadingPowerDelaySeconds(seconds)
        AlertDialog(
            onDismissRequest = { if (!busy) showSettings = false },
            title = { Text("阅读省电") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${visual.automaticLabel} · ${visual.flightLabel}", style = MaterialTheme.typography.titleSmall)
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    if (state.phase == ReadingPowerPhase.COUNTDOWN && state.deadlineElapsedMs != null) {
                        ReadingPowerCountdown(state.deadlineElapsedMs)
                    }
                    Text("回答完成后等待指定秒数，再自动切换飞行模式；发送消息和下载资料时会恢复联网。退到后台或关闭功能时恢复此前状态。", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = seconds,
                        onValueChange = { seconds = it; invalid = false; actionError = null },
                        enabled = !busy,
                        label = { Text("等待秒数") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = invalid || parsed == null,
                        supportingText = { Text(if (parsed == null) "请输入 0 到 3600 的整数秒" else "0 秒表示回答完成后立即执行") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    val value = parseReadingPowerDelaySeconds(seconds)
                    if (value == null) invalid = true
                    else scope.launch {
                        busy = true
                        actionError = null
                        try {
                            onDelaySecondsChange(value)
                            showSettings = false
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            actionError = failure.message ?: "等待秒数未能保存，请重试。"
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(if (busy) "保存中…" else "保存") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { showSettings = false }) { Text("取消") } },
        )
    }
}

/** Only this dialog-local composition ticks; the top-bar toggle never counts down. */
@Composable
private fun ReadingPowerCountdown(deadlineElapsedMs: Long) {
    var remaining by remember(deadlineElapsedMs) { mutableStateOf(readingPowerRemainingSeconds(deadlineElapsedMs, SystemClock.elapsedRealtime())) }
    LaunchedEffect(deadlineElapsedMs) {
        do {
            remaining = readingPowerRemainingSeconds(deadlineElapsedMs, SystemClock.elapsedRealtime())
            if (remaining > 0) delay(1000)
        } while (remaining > 0)
    }
    Text(if (remaining > 0) "剩余 $remaining 秒" else "等待切换飞行模式", style = MaterialTheme.typography.bodyMedium)
}

internal fun parseReadingPowerDelaySeconds(value: String): Int? {
    val text = value.trim()
    if (!text.matches(Regex("[0-9]+"))) return null
    return text.toIntOrNull()?.takeIf { it in 0..3600 }
}

internal fun readingPowerRemainingSeconds(deadlineElapsedMs: Long, nowElapsedMs: Long): Long =
    ((deadlineElapsedMs - nowElapsedMs).coerceAtLeast(0) + 999) / 1000

internal enum class ReadingPowerFlightIcon { OUTLINE, FILLED, UNKNOWN }

internal data class ReadingPowerButtonVisual(
    val automaticLabel: String,
    val flightLabel: String,
    val flightIcon: ReadingPowerFlightIcon,
    val phaseLabel: String?,
)

/** Actual system read-back alone determines the airplane silhouette, independently of app intent. */
internal fun readingPowerButtonVisual(
    enabled: Boolean,
    flightModeEnabled: Boolean?,
    phase: ReadingPowerPhase,
    hasError: Boolean = false,
): ReadingPowerButtonVisual = ReadingPowerButtonVisual(
    automaticLabel = if (enabled) "自动开" else "自动关",
    flightLabel = when (flightModeEnabled) { true -> "飞行已开"; false -> "飞行未开"; null -> "飞行未知" },
    flightIcon = when (flightModeEnabled) { true -> ReadingPowerFlightIcon.FILLED; false -> ReadingPowerFlightIcon.OUTLINE; null -> ReadingPowerFlightIcon.UNKNOWN },
    phaseLabel = when {
        hasError || phase == ReadingPowerPhase.ERROR -> "错误"
        phase == ReadingPowerPhase.COUNTDOWN -> "等待"
        phase == ReadingPowerPhase.ENABLING -> "切换"
        phase == ReadingPowerPhase.RESTORING -> "恢复"
        else -> null
    },
)

internal fun readingPowerStatus(phase: ReadingPowerPhase, ownsFlightMode: Boolean, flightModeEnabled: Boolean? = null): String = when (phase) {
    ReadingPowerPhase.DISABLED -> "阅读省电已关闭"
    ReadingPowerPhase.INACTIVE -> "等待阅读已完成的回答"
    ReadingPowerPhase.COUNTDOWN -> "回答已完成，等待切换飞行模式"
    ReadingPowerPhase.ENABLING -> "正在确认飞行模式"
    ReadingPowerPhase.AIRPLANE -> when (flightModeEnabled) {
        true -> if (ownsFlightMode) "阅读中，飞行模式已开启" else "当前已处于飞行模式"
        false -> "系统飞行模式未开启"
        null -> "尚未读到系统飞行模式状态"
    }
    ReadingPowerPhase.RESTORING -> "正在恢复此前的飞行模式状态"
    ReadingPowerPhase.ERROR -> "飞行模式控制失败"
}
