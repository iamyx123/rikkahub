package me.rerere.rikkahub.ui.pages.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

private val chineseLocale = Locale.SIMPLIFIED_CHINESE
private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", chineseLocale)
private val dateFormatter = DateTimeFormatter.ofPattern("M月d日 EEEE", chineseLocale)
private val weekdayLabels = listOf("一", "二", "三", "四", "五", "六", "日")

@Composable
fun StudyTodayPanel(
    sideRail: Boolean,
    modifier: Modifier = Modifier,
) {
    val now by rememberMinuteClock()
    val currentMonth = YearMonth.from(now)
    var displayedMonthValue by rememberSaveable { mutableStateOf(currentMonth.toString()) }
    var followsCurrentMonth by rememberSaveable { mutableStateOf(true) }
    val displayedMonth = runCatching { YearMonth.parse(displayedMonthValue) }.getOrDefault(currentMonth)
    LaunchedEffect(currentMonth) {
        if (followsCurrentMonth) displayedMonthValue = currentMonth.toString()
    }
    val setMonth: (YearMonth) -> Unit = {
        displayedMonthValue = it.toString()
        followsCurrentMonth = it == currentMonth
    }

    Surface(
        modifier = modifier,
        color = Color.White,
        contentColor = Color.Black,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Color.Black),
    ) {
        if (sideRail) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                StudyClockBlock(now)
                HorizontalDivider(color = Color.Black)
                StudyMonthCalendar(
                    month = displayedMonth,
                    today = now.toLocalDate(),
                    onMonthChange = setMonth,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "今日计划，从一次专注开始。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Black.copy(alpha = 0.72f),
                )
            }
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth >= 640.dp) {
                    Row(
                        modifier = Modifier.padding(22.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StudyClockBlock(now, modifier = Modifier.weight(1f))
                        VerticalDivider(
                            modifier = Modifier.height(210.dp),
                            color = Color.Black,
                        )
                        StudyMonthCalendar(
                            month = displayedMonth,
                            today = now.toLocalDate(),
                            onMonthChange = setMonth,
                            modifier = Modifier.width(330.dp),
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        StudyClockBlock(now)
                        HorizontalDivider(color = Color.Black)
                        StudyMonthCalendar(
                            month = displayedMonth,
                            today = now.toLocalDate(),
                            onMonthChange = setMonth,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StudyClockBlock(now: LocalDateTime, modifier: Modifier = Modifier) {
    val week = now.toLocalDate().get(WeekFields.of(chineseLocale).weekOfWeekBasedYear())
    Column(modifier = modifier) {
        Text(
            text = timeFormatter.format(now),
            style = MaterialTheme.typography.displayLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Light,
                letterSpacing = (-2).sp,
            ),
            color = Color.Black,
        )
        Text(
            text = dateFormatter.format(now),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.Black,
        )
        Text(
            text = "${now.year} 年 · 第 $week 周",
            style = MaterialTheme.typography.labelLarge,
            color = Color.Black.copy(alpha = 0.62f),
        )
    }
}

@Composable
private fun StudyMonthCalendar(
    month: YearMonth,
    today: LocalDate,
    onMonthChange: (YearMonth) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${month.year}年 ${month.monthValue}月",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = { onMonthChange(month.minusMonths(1)) }, colors = ButtonDefaults.textButtonColors(contentColor = Color.Black)) { Text("‹") }
            TextButton(onClick = { onMonthChange(YearMonth.from(today)) }, colors = ButtonDefaults.textButtonColors(contentColor = Color.Black)) { Text("今") }
            TextButton(onClick = { onMonthChange(month.plusMonths(1)) }, colors = ButtonDefaults.textButtonColors(contentColor = Color.Black)) { Text("›") }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            weekdayLabels.forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = Color.Black.copy(alpha = 0.58f),
                )
            }
        }
        val firstOffset = month.atDay(1).dayOfWeek.value - 1
        repeat(6) { weekIndex ->
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(7) { dayIndex ->
                    val day = weekIndex * 7 + dayIndex - firstOffset + 1
                    val inMonth = day in 1..month.lengthOfMonth()
                    val isToday = inMonth && month == YearMonth.from(today) && day == today.dayOfMonth
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1.18f)
                            .padding(2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isToday) {
                            Surface(
                                modifier = Modifier.size(30.dp),
                                color = Color.Black,
                                contentColor = Color.White,
                                shape = RoundedCornerShape(7.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(day.toString(), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        } else if (inMonth) {
                            Text(
                                text = day.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberMinuteClock() = produceState(initialValue = LocalDateTime.now()) {
    while (true) {
        value = LocalDateTime.now()
        val wait = 60_000L - (System.currentTimeMillis() % 60_000L)
        delay(wait.coerceAtLeast(1_000L))
    }
}
