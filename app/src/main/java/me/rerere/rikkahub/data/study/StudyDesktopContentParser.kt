package me.rerere.rikkahub.data.study

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import kotlin.math.roundToInt

internal object StudyDesktopContentParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseWeather(raw: String, updatedAt: Long): StudyWeatherSnapshot {
        val root = json.parseToJsonElement(raw).jsonObject
        val current = root.requiredObject("current")
        val daily = root.requiredObject("daily")
        val dates = daily.requiredArray("time")
        val minimums = daily.requiredArray("temperature_2m_min")
        val maximums = daily.requiredArray("temperature_2m_max")
        val precipitation = daily.requiredArray("precipitation_probability_max")
        val codes = daily.requiredArray("weather_code")
        val dayCount = minOf(dates.size, minimums.size, maximums.size, precipitation.size, codes.size, 3)

        return StudyWeatherSnapshot(
            temperature = current.roundedInt("temperature_2m"),
            apparentTemperature = current.roundedInt("apparent_temperature"),
            humidity = current.roundedInt("relative_humidity_2m"),
            windSpeed = current.roundedInt("wind_speed_10m"),
            weatherCode = current.roundedInt("weather_code"),
            forecast = (0 until dayCount).map { index ->
                StudyWeatherDay(
                    date = LocalDate.parse(dates[index].jsonPrimitive.content),
                    minimumTemperature = minimums[index].roundedInt(),
                    maximumTemperature = maximums[index].roundedInt(),
                    precipitationProbability = precipitation[index].roundedInt(),
                    weatherCode = codes[index].roundedInt(),
                )
            },
            updatedAt = updatedAt,
        )
    }

    fun parseQuote(raw: String, updatedAt: Long): StudyQuoteSnapshot {
        val root = json.parseToJsonElement(raw).jsonObject
        return StudyQuoteSnapshot(
            text = root.requiredText("hitokoto"),
            source = root["from"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "佚名" },
            author = root["from_who"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            categoryCode = root["type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            updatedAt = updatedAt,
        )
    }

    private fun JsonObject.requiredObject(name: String): JsonObject =
        requireNotNull(this[name]) { "Missing $name" }.jsonObject

    private fun JsonObject.requiredArray(name: String): JsonArray =
        requireNotNull(this[name]) { "Missing $name" }.jsonArray

    private fun JsonObject.requiredText(name: String): String =
        requireNotNull(this[name]?.jsonPrimitive?.contentOrNull) { "Missing $name" }

    private fun JsonObject.roundedInt(name: String): Int =
        requireNotNull(this[name]?.jsonPrimitive?.contentOrNull) { "Missing $name" }
            .toDouble()
            .roundToInt()

    private fun kotlinx.serialization.json.JsonElement.roundedInt(): Int =
        jsonPrimitive.content.toDouble().roundToInt()
}
