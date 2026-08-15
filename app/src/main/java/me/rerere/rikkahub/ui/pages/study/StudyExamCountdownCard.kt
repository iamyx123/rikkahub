package me.rerere.rikkahub.ui.pages.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.rikkahub.data.study.StudyExamCountdown
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.absoluteValue

private val examDateFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)

@Composable
internal fun StudyExamCountdownCard(
    targetDate: LocalDate,
    onTargetDateChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    val today by produceState(initialValue = LocalDate.now()) {
        while (true) {
            delay(60_000L)
            value = LocalDate.now()
        }
    }
    val countdown = remember(today, targetDate) {
        StudyExamCountdown.calculate(today = today, targetDate = targetDate)
    }
    val isDefaultTarget = targetDate == StudyExamCountdown.DEFAULT_TARGET_DATE
    val mainValue = when {
        countdown.isToday -> "今天"
        else -> countdown.daysUntil.absoluteValue.toString()
    }
    val valueSuffix = when {
        countdown.isToday -> "开考"
        countdown.hasPassed -> "天前"
        else -> "天"
    }
    val supportingText = when {
        countdown.isToday -> "今天上场，稳住节奏"
        countdown.hasPassed -> "目标日期已过，点击重新设定"
        else -> "把今天用在真正重要的事上"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { showDatePicker = true },
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
        ),
    ) {
        Box {
            Icon(
                imageVector = HugeIcons.Calendar03,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp)
                    .size(112.dp)
                    .alpha(0.08f),
            )
            BoxWithConstraints(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                if (maxWidth >= 540.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CountdownValue(
                            label = if (isDefaultTarget) "2027 高考倒计时" else "我的高考倒计时",
                            value = mainValue,
                            suffix = valueSuffix,
                            modifier = Modifier.weight(1f),
                        )
                        CountdownTarget(
                            date = targetDate,
                            supportingText = supportingText,
                            onEdit = { showDatePicker = true },
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        CountdownValue(
                            label = if (isDefaultTarget) "2027 高考倒计时" else "我的高考倒计时",
                            value = mainValue,
                            suffix = valueSuffix,
                        )
                        CountdownTarget(
                            date = targetDate,
                            supportingText = supportingText,
                            onEdit = { showDatePicker = true },
                        )
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = targetDate
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selectedMillis ->
                            onTargetDateChange(
                                Instant.ofEpochMilli(selectedMillis)
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDate(),
                            )
                        }
                        showDatePicker = false
                    },
                ) {
                    Text("保存日期")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("取消")
                }
            },
        ) {
            DatePicker(
                state = datePickerState,
                title = {
                    Text(
                        text = "设置高考日期",
                        modifier = Modifier.padding(start = 24.dp, top = 20.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun CountdownValue(
    label: String,
    value: String,
    suffix: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = HugeIcons.Calendar03,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = suffix,
                modifier = Modifier.padding(start = 6.dp, bottom = 7.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun CountdownTarget(
    date: LocalDate,
    supportingText: String,
    onEdit: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = examDateFormatter.format(date),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = supportingText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
        )
        TextButton(
            onClick = onEdit,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 0.dp,
                vertical = 4.dp,
            ),
        ) {
            Icon(
                imageVector = HugeIcons.Edit02,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("修改考试日期")
        }
    }
}
