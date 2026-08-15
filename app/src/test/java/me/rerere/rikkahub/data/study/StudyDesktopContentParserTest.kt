package me.rerere.rikkahub.data.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class StudyDesktopContentParserTest {
    @Test
    fun parsesCurrentShenzhenWeatherAndLimitsForecastToThreeDays() {
        val snapshot = StudyDesktopContentParser.parseWeather(WEATHER_JSON, updatedAt = 1234L)

        assertEquals(26, snapshot.temperature)
        assertEquals(31, snapshot.apparentTemperature)
        assertEquals(95, snapshot.humidity)
        assertEquals(10, snapshot.windSpeed)
        assertEquals(3, snapshot.weatherCode)
        assertEquals(3, snapshot.forecast.size)
        assertEquals(LocalDate.of(2026, 7, 27), snapshot.forecast.first().date)
        assertEquals(24, snapshot.forecast.first().minimumTemperature)
        assertEquals(28, snapshot.forecast.first().maximumTemperature)
        assertEquals(100, snapshot.forecast.first().precipitationProbability)
        assertEquals(1234L, snapshot.updatedAt)
    }

    @Test
    fun parsesHitokotoSourceAndNullableAuthor() {
        val snapshot = StudyDesktopContentParser.parseQuote(QUOTE_JSON, updatedAt = 5678L)

        assertEquals("取法于上，仅得为中；取法于中，故为其下。", snapshot.text)
        assertEquals("每日一习话", snapshot.source)
        assertNull(snapshot.author)
        assertEquals("k", snapshot.categoryCode)
        assertEquals(5678L, snapshot.updatedAt)
    }

    companion object {
        private val WEATHER_JSON = """
            {
              "current": {
                "temperature_2m": 25.7,
                "apparent_temperature": 30.9,
                "relative_humidity_2m": 95,
                "weather_code": 3,
                "wind_speed_10m": 9.8
              },
              "daily": {
                "time": ["2026-07-27", "2026-07-28", "2026-07-29", "2026-07-30"],
                "temperature_2m_max": [28.1, 30.1, 26.4, 31.0],
                "temperature_2m_min": [23.6, 24.8, 24.0, 25.0],
                "precipitation_probability_max": [100, 100, 97, 20],
                "weather_code": [96, 80, 95, 1]
              }
            }
        """.trimIndent()

        private val QUOTE_JSON = """
            {
              "uuid": "86b3fe81-dd36-42bd-80ce-1664cd37f118",
              "hitokoto": "取法于上，仅得为中；取法于中，故为其下。",
              "type": "k",
              "from": "每日一习话",
              "from_who": null
            }
        """.trimIndent()
    }
}
