package me.rerere.rikkahub.ui.components.intelligentui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.LocalDarkMode

/**
 * 卡片里的颜色：主题色名或 #RRGGBB。
 * 墨水屏按亮度换成灰度（浅色填充仍是浅灰，不会变成一片黑），深色主题下把模型写死的浅色填充翻成深色。
 */
internal class NativeUiPalette(private val scheme: ColorScheme, private val dark: Boolean, private val monochrome: Boolean) {
    private fun status(name: String): Color? = when (name) {
        "info", "primary", "chart1" -> if (dark) Color(0xFF8AB4F8) else Color(0xFF1A73E8)
        "success", "chart2" -> if (dark) Color(0xFF81C995) else Color(0xFF188038)
        "warning", "chart3" -> if (dark) Color(0xFFFDD663) else Color(0xFFE37400)
        "danger", "chart4" -> if (dark) Color(0xFFF28B82) else Color(0xFFD93025)
        "purple", "secondary", "chart5" -> if (dark) Color(0xFFC58AF9) else Color(0xFF8430CE)
        "pink", "chart6" -> if (dark) Color(0xFFFF8BCB) else Color(0xFFD01884)
        else -> null
    }

    /** 线条和文字用的颜色 */
    fun ink(value: String, default: Color = scheme.onSurface): Color = adapt(
        when (value) {
            "", "ink" -> default
            "none", "transparent" -> Color.Transparent
            "muted" -> scheme.onSurfaceVariant
            "faint" -> scheme.outline
            "surface", "canvas" -> scheme.surface
            "surface-strong" -> scheme.surfaceContainerHighest
            else -> status(value) ?: parse(value) ?: default
        }
    )

    /** 面积较大的填充：主题色名换成浅色底，免得大块深色盖住上面的线条和文字 */
    fun area(value: String): Color = adapt(
        when (value) {
            "none", "transparent" -> Color.Transparent
            "surface" -> scheme.surfaceContainerHigh
            "canvas" -> scheme.surface
            "surface-strong", "border" -> scheme.surfaceContainerHighest
            else -> status(value)?.let { soft(it) } ?: return ink(value)
        }
    )

    private fun soft(color: Color): Color = if (dark) {
        lerp(scheme.surface, color, 0.28f)
    } else {
        lerp(Color.White, color, 0.16f)
    }

    private fun parse(value: String): Color? {
        val color = runCatching { Color(android.graphics.Color.parseColor(value.trim())) }.getOrNull() ?: return null
        // 模型写的固定颜色不跟主题，深色下把亮度翻过来，保持「浅底深线」的关系
        if (!dark) return color
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(color.toArgb(), hsl)
        hsl[2] = 1f - hsl[2] * 0.85f
        return Color(androidx.core.graphics.ColorUtils.HSLToColor(hsl)).copy(alpha = color.alpha)
    }

    private fun adapt(color: Color): Color {
        if (!monochrome || color.alpha == 0f) return color
        val gray = color.luminance()
        return Color(gray, gray, gray, color.alpha)
    }

    private fun lerp(a: Color, b: Color, t: Float) =
        Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)
}

@Composable
internal fun rememberNativeUiPalette(): NativeUiPalette {
    val scheme = MaterialTheme.colorScheme
    val dark = LocalDarkMode.current
    val monochrome = LocalSettings.current.displaySetting.disableCursorBlink
    return remember(scheme, dark, monochrome) { NativeUiPalette(scheme, dark, monochrome) }
}

private const val SMALL_AREA = 900f

/** 矢量图：统一缩放、居中，不拉伸 */
@Composable
internal fun NativeUiVector(p: JsonObject, showDescription: Boolean = true) {
    val palette = rememberNativeUiPalette()
    val view = remember(p.text("viewBox")) {
        p.text("viewBox").split(Regex("[ ,]+")).mapNotNull { it.toFloatOrNull()?.takeIf(Float::isFinite) }
            .takeIf { it.size == 4 && it[2] in 1f..100000f && it[3] in 1f..100000f } ?: listOf(0f, 0f, 240f, 160f)
    }
    val textPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    // 不指定宽度时铺满卡片，但不超过 viewBox 的 1.8 倍，免得小图被放得过大
    val widthLimit = p.number("width", 0.0).takeIf { it > 0 }?.dp ?: (view[2] * 1.8f).coerceAtLeast(280f).dp
    Column {
        Canvas(
            Modifier
                .widthIn(max = widthLimit)
                .fillMaxWidth()
                .aspectRatio((view[2] / view[3]).coerceIn(0.33f, 4f))
        ) {
            val s = minOf(size.width / view[2], size.height / view[3])
            val dx = (size.width - view[2] * s) / 2f
            val dy = (size.height - view[3] * s) / 2f
            clipRect {
                translate(dx, dy) {
                    scale(s, s, pivot = Offset.Zero) {
                        translate(-view[0], -view[1]) {
                            p.list("children").forEach { drawVectorNode(it, palette, textPaint, s, 0) }
                        }
                    }
                }
            }
        }
        if (showDescription && p.text("description").isNotBlank()) {
            Text(p.text("description"), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun DrawScope.drawVectorNode(node: JsonElement, palette: NativeUiPalette, textPaint: Paint, scale: Float, depth: Int) {
    if (depth > 32) return
    val o = node as? JsonObject ?: return
    val v = o["props"] as? JsonObject ?: return
    val type = o.text("type")
    fun n(key: String, fallback: Double = 0.0) = v.number(key, fallback).coerceIn(-100000.0, 100000.0).toFloat()
    val opacity = v.number("opacity", 1.0).coerceIn(0.0, 1.0).toFloat()
    when (type) {
        "G" -> {
            withTransform({ applySvgTransform(v.text("transform")) }) {
                v.list("children").forEach { drawVectorNode(it, palette, textPaint, scale, depth + 1) }
            }
            return
        }

        "SvgText" -> {
            val color = palette.ink(v.text("fill"))
            textPaint.color = color.copy(alpha = color.alpha * opacity).toArgb()
            textPaint.textSize = v.number("size", 12.0).coerceIn(4.0, 96.0).toFloat()
            textPaint.typeface = if (v.text("weight") in setOf("semibold", "bold", "medium")) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textPaint.textAlign = when (v.text("anchor")) {
                "middle" -> Paint.Align.CENTER
                "end" -> Paint.Align.RIGHT
                else -> Paint.Align.LEFT
            }
            drawContext.canvas.nativeCanvas.drawText(v.text("text").take(500), n("x"), n("y"), textPaint)
            return
        }
    }
    val path = Path()
    var area = Float.MAX_VALUE
    when (type) {
        "Circle" -> path.addOval(Rect(Offset(n("cx"), n("cy")), n("r"))).also { area = 3.14f * n("r") * n("r") }
        "Ellipse" -> path.addOval(Rect(n("cx") - n("rx"), n("cy") - n("ry"), n("cx") + n("rx"), n("cy") + n("ry")))
            .also { area = 3.14f * n("rx") * n("ry") }

        "Rect" -> {
            val rect = Rect(n("x"), n("y"), n("x") + n("width"), n("y") + n("height"))
            val rx = n("rx")
            if (rx > 0) path.addRoundRect(androidx.compose.ui.geometry.RoundRect(rect, androidx.compose.ui.geometry.CornerRadius(rx)))
            else path.addRect(rect)
            area = rect.width * rect.height
        }

        "Line" -> {
            path.moveTo(n("x1"), n("y1"))
            path.lineTo(n("x2"), n("y2"))
        }

        "Path" -> runCatching { path.addPath(PathParser().parsePathString(v.text("data").take(16000)).toPath()) }
        "Polygon", "Polyline" -> {
            val coords = v.text("data").split(Regex("[ ,]+")).mapNotNull { it.toFloatOrNull()?.takeIf(Float::isFinite) }.take(4000)
            coords.chunked(2).filter { it.size == 2 }.forEachIndexed { i, xy -> if (i == 0) path.moveTo(xy[0], xy[1]) else path.lineTo(xy[0], xy[1]) }
            if (type == "Polygon") {
                path.close()
                area = path.getBounds().let { it.width * it.height / 2f }
            }
        }

        else -> return
    }
    val closed = type in setOf("Circle", "Ellipse", "Rect", "Polygon")
    val fillValue = v.text("fill")
    val strokeValue = v.text("stroke")
    // 和上游一致：只给了描边的图形是空心的，路径和折线只有明确给了填充才填。
    // 什么都没给时，小图形（点、小箭头）填实，大图形只画轮廓——一大块默认墨色会把整张图涂黑
    val fill = when {
        fillValue.isNotEmpty() -> if (area <= SMALL_AREA) palette.ink(fillValue) else palette.area(fillValue)
        closed && strokeValue.isEmpty() && area <= SMALL_AREA -> palette.ink("")
        else -> Color.Transparent
    }
    // 填充为空（没写或写了 none）又没描边的图形什么都看不见，模型不会是这个意思，补一圈轮廓
    val strokeColor = when {
        strokeValue.isNotEmpty() -> palette.ink(strokeValue)
        fill == Color.Transparent -> palette.ink("")
        else -> Color.Transparent
    }
    if (fill.alpha > 0f) drawPath(path, fill.copy(alpha = fill.alpha * opacity), style = Fill)
    if (strokeColor.alpha > 0f) {
        val dashed = (v["dashed"] as? JsonPrimitive)?.booleanOrNull == true
        // 描边宽度按 viewBox 单位，但至少约 1 个物理像素，缩小后也看得见
        val width = v.number("strokeWidth", if (closed || type == "Path") 1.5 else 2.0).coerceIn(0.1, 20.0).toFloat()
            .coerceAtLeast(1f / scale)
        drawPath(
            path,
            strokeColor.copy(alpha = strokeColor.alpha * opacity),
            style = Stroke(
                width = width,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(width * 3f, width * 2.5f)) else null,
            ),
        )
    }
}

private val TRANSFORM_REGEX = Regex("""([a-zA-Z]+)\s*\(([^()]*)\)""")

/** 支持 translate、rotate、scale；参数写错的变换直接忽略 */
private fun androidx.compose.ui.graphics.drawscope.DrawTransform.applySvgTransform(value: String) {
    if (value.isBlank() || value.length > 500) return
    TRANSFORM_REGEX.findAll(value).forEach { match ->
        val args = match.groupValues[2].split(Regex("[\\s,]+")).filter { it.isNotBlank() }.mapNotNull { it.toFloatOrNull() }
        when (match.groupValues[1]) {
            "translate" -> if (args.isNotEmpty()) translate(args[0], args.getOrElse(1) { 0f })
            "rotate" -> if (args.size == 1) rotate(args[0], Offset.Zero) else if (args.size == 3) rotate(args[0], Offset(args[1], args[2]))
            "scale" -> if (args.isNotEmpty()) scale(args[0], args.getOrElse(1) { args[0] }, Offset.Zero)
        }
    }
}
