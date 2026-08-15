package me.rerere.rikkahub.ui.pages.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.rerere.rikkahub.data.study.StudyDesktopContentRepository
import me.rerere.rikkahub.data.study.StudyQuoteCategory
import me.rerere.rikkahub.data.study.StudyQuoteSnapshot
import me.rerere.rikkahub.data.study.StudyWeatherDay
import me.rerere.rikkahub.data.study.StudyWeatherSnapshot
import org.koin.compose.koinInject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private val ambientLocale = Locale.SIMPLIFIED_CHINESE
private val ambientTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", ambientLocale)
private val ambientDateFormatter = DateTimeFormatter.ofPattern("M月d日 EEEE", ambientLocale)
private val forecastDayFormatter = DateTimeFormatter.ofPattern("E", ambientLocale)
private val ambientInk = Color(0xFF132536)
private val ambientBlue = Color(0xFF0E6F91)
private val ambientSky = Color(0xFFE9F5FA)
private val ambientPaper = Color(0xFFFFF3D6)
private val ambientWarm = Color(0xFFD97832)

@Composable
fun StudyAmbientPanel(
    sideRail: Boolean,
    modifier: Modifier = Modifier,
) {
    val repository: StudyDesktopContentRepository = koinInject()
    val categoryCodes by repository.quoteCategoryCodes.collectAsStateWithLifecycle()
    var quoteRefresh by remember { mutableIntStateOf(0) }
    var weatherRefresh by remember { mutableIntStateOf(0) }
    val now by rememberAmbientClock()
    val weather by produceState<Result<StudyWeatherSnapshot>?>(null, weatherRefresh) {
        value = repository.loadWeather(forceRefresh = weatherRefresh > 0)
    }
    val quote by produceState<Result<StudyQuoteSnapshot>?>(null, categoryCodes, quoteRefresh) {
        value = repository.loadQuote(forceRefresh = quoteRefresh > 0)
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30 * 60 * 1000L)
            weatherRefresh++
        }
    }

    Column(
        modifier = if (sideRail) modifier.verticalScroll(rememberScrollState()) else modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (sideRail) {
            DateCard(now = now)
            WeatherCard(weather = weather)
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth >= 700.dp) {
                    val availableWidth = maxWidth
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        DateCard(now = now, modifier = Modifier.width(availableWidth * 0.36f))
                        WeatherCard(weather = weather, modifier = Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DateCard(now = now)
                        WeatherCard(weather = weather)
                    }
                }
            }
        }
        QuoteCard(
            quote = quote,
            onRefresh = { quoteRefresh++ },
        )
    }
}

@Composable
private fun DateCard(
    now: LocalDateTime,
    modifier: Modifier = Modifier,
) {
    val monday = remember(now.toLocalDate()) {
        now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.White,
        contentColor = ambientInk,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, ambientInk),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = ambientDateFormatter.format(now),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${now.year} · 第 ${now.dayOfYear} 天",
                        style = MaterialTheme.typography.labelMedium,
                        color = ambientInk.copy(alpha = 0.62f),
                    )
                }
                Text(
                    text = ambientTimeFormatter.format(now),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (-1).sp,
                    ),
                )
            }
            WeekStrip(monday = monday, today = now.toLocalDate())
        }
    }
}

@Composable
private fun WeekStrip(monday: LocalDate, today: LocalDate) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(7) { index ->
            val date = monday.plusDays(index.toLong())
            val selected = date == today
            Surface(
                modifier = Modifier.weight(1f),
                color = if (selected) ambientInk else Color.Transparent,
                contentColor = if (selected) Color.White else ambientInk,
                shape = RoundedCornerShape(10.dp),
                border = if (selected) null else BorderStroke(1.dp, ambientInk.copy(alpha = 0.18f)),
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = listOf("一", "二", "三", "四", "五", "六", "日")[index],
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun WeatherCard(
    weather: Result<StudyWeatherSnapshot>?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = ambientSky,
        contentColor = ambientInk,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, ambientBlue),
    ) {
        Box {
            ShenzhenSkyline(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .fillMaxWidth(0.54f)
                    .height(68.dp),
            )
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "深圳天气",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = ambientBlue,
                        )
                        Text(
                            text = "SHENZHEN · LIVE",
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 1.2.sp,
                            color = ambientBlue.copy(alpha = 0.72f),
                        )
                    }
                    Text(
                        text = "Open-Meteo",
                        style = MaterialTheme.typography.labelSmall,
                        color = ambientInk.copy(alpha = 0.52f),
                    )
                }
                when {
                    weather == null -> WeatherLoading()
                    weather.isSuccess -> WeatherContent(weather.getOrThrow())
                    else -> WeatherError()
                }
            }
        }
    }
}

@Composable
private fun WeatherLoading() {
    Text(
        text = "正在读取深圳天气…",
        style = MaterialTheme.typography.bodyLarge,
        color = ambientInk.copy(alpha = 0.62f),
    )
}

@Composable
private fun WeatherError() {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("天气暂时未连接", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "网络恢复后会自动更新，不影响学习桌面使用。",
            style = MaterialTheme.typography.bodySmall,
            color = ambientInk.copy(alpha = 0.64f),
        )
    }
}

@Composable
private fun WeatherContent(snapshot: StudyWeatherSnapshot) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = "${snapshot.temperature}°",
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Column(modifier = Modifier.padding(bottom = 7.dp)) {
            Text(
                text = weatherDescription(snapshot.weatherCode),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "体感 ${snapshot.apparentTemperature}° · 湿度 ${snapshot.humidity}% · 风 ${snapshot.windSpeed} km/h",
                style = MaterialTheme.typography.labelMedium,
                color = ambientInk.copy(alpha = 0.66f),
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        snapshot.forecast.forEach { day ->
            ForecastChip(day = day, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ForecastChip(
    day: StudyWeatherDay,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color.White.copy(alpha = 0.82f),
        contentColor = ambientInk,
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(1.dp, ambientBlue.copy(alpha = 0.24f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (day.date == LocalDate.now()) "今天" else forecastDayFormatter.format(day.date),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = "${day.maximumTemperature}° / ${day.minimumTemperature}°",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${weatherDescription(day.weatherCode)} · ${day.precipitationProbability}%",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = ambientBlue,
            )
        }
    }
}

@Composable
private fun QuoteCard(
    quote: Result<StudyQuoteSnapshot>?,
    onRefresh: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ambientPaper,
        contentColor = ambientInk,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, ambientWarm),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "今日一言",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = ambientWarm,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onRefresh) {
                    Text("换一句", color = ambientInk)
                }
            }
            when {
                quote == null -> Text("正在翻开一句话…", color = ambientInk.copy(alpha = 0.62f))
                quote.isSuccess -> {
                    val value = quote.getOrThrow()
                    Text(
                        text = "“${value.text}”",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 29.sp,
                    )
                    val authorAndSource = listOfNotNull(value.author, value.source.takeIf { it != value.author })
                        .joinToString(" · ")
                    val category = StudyQuoteCategory.entries
                        .firstOrNull { it.code == value.categoryCode }
                        ?.displayName
                        .orEmpty()
                    Text(
                        text = "— $authorAndSource${if (category.isBlank()) "" else "  /  $category"}",
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ambientInk.copy(alpha = 0.68f),
                        textAlign = TextAlign.End,
                    )
                }
                else -> {
                    Text(
                        text = "保持好奇，今天也从一个小问题开始。",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "一言服务暂时未连接",
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelMedium,
                        color = ambientInk.copy(alpha = 0.58f),
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShenzhenSkyline(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val line = ambientBlue.copy(alpha = 0.18f)
        val baseY = size.height * 0.88f
        drawLine(line, Offset(0f, baseY), Offset(size.width, baseY), strokeWidth = 2f)
        val buildings = listOf(
            0.03f to 0.40f,
            0.16f to 0.64f,
            0.30f to 0.48f,
            0.43f to 0.78f,
            0.59f to 0.55f,
            0.72f to 0.70f,
            0.86f to 0.44f,
        )
        buildings.forEachIndexed { index, (x, height) ->
            val left = size.width * x
            val width = size.width * if (index == 3) 0.10f else 0.08f
            drawRect(
                color = line,
                topLeft = Offset(left, baseY - size.height * height),
                size = androidx.compose.ui.geometry.Size(width, size.height * height),
                style = Stroke(width = 2f),
            )
        }
        val towerX = size.width * 0.48f
        drawLine(line, Offset(towerX, baseY - size.height * 0.78f), Offset(towerX, 0f), strokeWidth = 2f)
    }
}

private fun weatherDescription(code: Int): String = when (code) {
    0 -> "晴"
    1 -> "晴间多云"
    2 -> "多云"
    3 -> "阴"
    45, 48 -> "有雾"
    51, 53, 55, 56, 57 -> "毛毛雨"
    61, 63, 65, 66, 67 -> "雨"
    71, 73, 75, 77 -> "雪"
    80, 81, 82 -> "阵雨"
    85, 86 -> "阵雪"
    95, 96, 99 -> "雷雨"
    else -> "天气变化"
}

@Composable
private fun rememberAmbientClock() = produceState(initialValue = LocalDateTime.now()) {
    while (true) {
        value = LocalDateTime.now()
        val wait = 60_000L - (System.currentTimeMillis() % 60_000L)
        delay(wait.coerceAtLeast(1_000L))
    }
}
