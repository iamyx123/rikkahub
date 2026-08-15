package me.rerere.rikkahub.data.study

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate

enum class StudyQuoteCategory(val code: String, val displayName: String) {
    ANIMATION("a", "动画"),
    COMIC("b", "漫画"),
    GAME("c", "游戏"),
    LITERATURE("d", "文学"),
    ORIGINAL("e", "原创"),
    INTERNET("f", "网络"),
    OTHER("g", "其他"),
    FILM("h", "影视"),
    POETRY("i", "诗词"),
    PHILOSOPHY("k", "哲学"),
    WIT("l", "抖机灵"),
}

data class StudyWeatherDay(
    val date: LocalDate,
    val minimumTemperature: Int,
    val maximumTemperature: Int,
    val precipitationProbability: Int,
    val weatherCode: Int,
)

data class StudyWeatherSnapshot(
    val temperature: Int,
    val apparentTemperature: Int,
    val humidity: Int,
    val windSpeed: Int,
    val weatherCode: Int,
    val forecast: List<StudyWeatherDay>,
    val updatedAt: Long,
)

data class StudyQuoteSnapshot(
    val text: String,
    val source: String,
    val author: String?,
    val categoryCode: String,
    val updatedAt: Long,
)

class StudyDesktopContentRepository(
    context: Context,
    private val client: OkHttpClient,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val _quoteCategoryCodes = MutableStateFlow(loadQuoteCategories())

    val quoteCategoryCodes = _quoteCategoryCodes.asStateFlow()

    fun setQuoteCategory(category: StudyQuoteCategory, enabled: Boolean): Boolean {
        val updated = _quoteCategoryCodes.value.toMutableSet().apply {
            if (enabled) add(category.code) else remove(category.code)
        }
        if (updated.isEmpty()) return false
        preferences.edit()
            .putStringSet(KEY_QUOTE_CATEGORIES, updated)
            .remove(KEY_QUOTE_JSON)
            .remove(KEY_QUOTE_UPDATED_AT)
            .apply()
        _quoteCategoryCodes.value = updated
        return true
    }

    suspend fun loadWeather(forceRefresh: Boolean = false): Result<StudyWeatherSnapshot> =
        withContext(Dispatchers.IO) {
            val cached = cachedWeather()
            if (!forceRefresh && cached != null && isFresh(cached.updatedAt, WEATHER_CACHE_MILLIS)) {
                return@withContext Result.success(cached)
            }
            runCatching {
                val response = execute(WEATHER_URL)
                val now = System.currentTimeMillis()
                StudyDesktopContentParser.parseWeather(response, now).also {
                    preferences.edit()
                        .putString(KEY_WEATHER_JSON, response)
                        .putLong(KEY_WEATHER_UPDATED_AT, now)
                        .apply()
                }
            }.recoverCatching { cached ?: throw it }
        }

    suspend fun loadQuote(forceRefresh: Boolean = false): Result<StudyQuoteSnapshot> =
        withContext(Dispatchers.IO) {
            val cached = cachedQuote()
            if (!forceRefresh && cached != null && isFresh(cached.updatedAt, QUOTE_CACHE_MILLIS)) {
                return@withContext Result.success(cached)
            }
            runCatching {
                val categories = _quoteCategoryCodes.value.ifEmpty { DEFAULT_QUOTE_CATEGORIES }
                val categoryQuery = categories.sorted().joinToString(separator = "") { "&c=$it" }
                val response = execute("$HITOKOTO_URL$categoryQuery")
                val now = System.currentTimeMillis()
                StudyDesktopContentParser.parseQuote(response, now).also {
                    preferences.edit()
                        .putString(KEY_QUOTE_JSON, response)
                        .putLong(KEY_QUOTE_UPDATED_AT, now)
                        .apply()
                }
            }.recoverCatching { cached ?: throw it }
        }

    private fun execute(url: String): String {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            return response.body.string()
        }
    }

    private fun cachedWeather(): StudyWeatherSnapshot? {
        val raw = preferences.getString(KEY_WEATHER_JSON, null) ?: return null
        val updatedAt = preferences.getLong(KEY_WEATHER_UPDATED_AT, 0L)
        return runCatching { StudyDesktopContentParser.parseWeather(raw, updatedAt) }.getOrNull()
    }

    private fun cachedQuote(): StudyQuoteSnapshot? {
        val raw = preferences.getString(KEY_QUOTE_JSON, null) ?: return null
        val updatedAt = preferences.getLong(KEY_QUOTE_UPDATED_AT, 0L)
        return runCatching { StudyDesktopContentParser.parseQuote(raw, updatedAt) }.getOrNull()
    }

    private fun loadQuoteCategories(): Set<String> {
        val validCodes = StudyQuoteCategory.entries.mapTo(mutableSetOf()) { it.code }
        return preferences.getStringSet(KEY_QUOTE_CATEGORIES, null)
            ?.intersect(validCodes)
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_QUOTE_CATEGORIES
    }

    private fun isFresh(timestamp: Long, lifetime: Long): Boolean =
        timestamp > 0L && System.currentTimeMillis() - timestamp < lifetime

    companion object {
        private const val PREFERENCES_NAME = "study_desktop_content"
        private const val KEY_QUOTE_CATEGORIES = "quote_categories"
        private const val KEY_WEATHER_JSON = "weather_json"
        private const val KEY_WEATHER_UPDATED_AT = "weather_updated_at"
        private const val KEY_QUOTE_JSON = "quote_json"
        private const val KEY_QUOTE_UPDATED_AT = "quote_updated_at"
        private const val WEATHER_CACHE_MILLIS = 30 * 60 * 1000L
        private const val QUOTE_CACHE_MILLIS = 6 * 60 * 60 * 1000L
        private const val WEATHER_URL =
            "https://api.open-meteo.com/v1/forecast?latitude=22.5431&longitude=114.0579" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code" +
                "&timezone=Asia%2FShanghai&forecast_days=3"
        private const val HITOKOTO_URL =
            "https://v1.hitokoto.cn/?encode=json&max_length=42"
        private val DEFAULT_QUOTE_CATEGORIES = setOf("d", "e", "i", "k")
    }
}
