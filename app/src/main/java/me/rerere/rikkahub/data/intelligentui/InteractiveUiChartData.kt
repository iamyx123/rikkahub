package me.rerere.rikkahub.data.intelligentui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

internal data class InteractiveChartDomain(val minimum: Double, val maximum: Double) {
    fun fraction(value: Double): Float = ((value - minimum) / (maximum - minimum)).toFloat()
}

internal fun interactiveChartDomain(props: JsonObject): InteractiveChartDomain {
    val rows = (props["data"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.take(200)
    val series = (props["series"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.take(8)
    val values = rows.flatMap { row -> series.mapNotNull { (row[it] as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite) } }
    fun explicit(key: String) = (props[key] as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite)
    val minimum = explicit("yMin") ?: minOf(0.0, values.minOrNull() ?: 0.0)
    val maximum = explicit("yMax") ?: maxOf(0.0, values.maxOrNull() ?: 1.0)
    return InteractiveChartDomain(minimum, if (maximum > minimum) maximum else minimum + 1.0)
}
