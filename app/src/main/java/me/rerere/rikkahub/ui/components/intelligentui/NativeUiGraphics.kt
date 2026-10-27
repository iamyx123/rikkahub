package me.rerere.rikkahub.ui.components.intelligentui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import me.rerere.rikkahub.ui.context.LocalSettings
import coil3.compose.AsyncImage
import coil3.decode.BitmapFactoryDecoder
import coil3.request.ImageRequest
import coil3.request.crossfade

@Composable
internal fun NativeUiImage(p:JsonObject) {
    val url=p.text("url")
    val context=LocalContext.current
    var failed by remember(url){mutableStateOf(false)}
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        if (url.startsWith("https://") || url.startsWith("http://")) {
            val request=remember(url){ImageRequest.Builder(context).data(url).size(1024,1024)
                .decoderFactory(BitmapFactoryDecoder.Factory()).crossfade(false).build()}
            AsyncImage(model=request,contentDescription=p.text("alt"),modifier=Modifier.fillMaxWidth().heightIn(max=320.dp),onError={failed=true})
        }
        if (failed || p.text("alt").isNotBlank() && url.isBlank()) Text(p.text("alt"),style=MaterialTheme.typography.bodySmall)
    }
}

/** Static native graphics: no animator, web surface, polling or tile cache. */
@Composable
internal fun NativeUiChart(p: JsonObject) {
    val rows = p.list("data").take(200).mapNotNull { it as? JsonObject }
    val series = p.list("series").take(8).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    val ink = MaterialTheme.colorScheme.onSurface
    val palette = if (LocalSettings.current.displaySetting.disableCursorBlink) listOf(ink, ink.copy(alpha=.75f), ink.copy(alpha=.5f))
        else listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary)
    val type = p.text("type")
    val xValues = rows.map { (it[p.text("xKey")] as? JsonPrimitive)?.doubleOrNull }
    val numericX = type != "bar" && xValues.all { it != null && it.isFinite() }
    val minX = if(numericX) xValues.filterNotNull().minOrNull() ?: 0.0 else 0.0
    val xRange = if(numericX) ((xValues.filterNotNull().maxOrNull() ?: 1.0)-minX).coerceAtLeast(.001) else 1.0
    val domain = me.rerere.rikkahub.data.intelligentui.interactiveChartDomain(p)
    val low = domain.minimum
    val high = domain.maximum
    val range = (high-low).coerceAtLeast(.001)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (p.text("title").isNotBlank()) Text(p.text("title"), style=MaterialTheme.typography.titleSmall)
        if (rows.isEmpty() || series.isEmpty()) Text("暂无图表数据")
        else Canvas(Modifier.fillMaxWidth().height(p.number("height", 240.0).coerceIn(160.0, 360.0).dp)) {
            val left = 44.dp.toPx()
            val bottom = size.height - 22.dp.toPx()
            val plotWidth = (size.width - left - 12.dp.toPx()).coerceAtLeast(1f)
            val plotHeight = (bottom - 12.dp.toPx()).coerceAtLeast(1f)
            fun point(i: Int, v: Double) = Offset(left + (if(numericX) ((xValues[i]!!-minX)/xRange).toFloat() else i.toFloat()/maxOf(1,rows.size-1))*plotWidth, bottom - domain.fraction(v) * plotHeight)
            val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=ink.toArgb();textSize=11.dp.toPx() }
            for (fraction in listOf(0f, .5f, 1f)) {
                val y = bottom - fraction * plotHeight
                drawLine(ink.copy(alpha=.18f), Offset(left,y), Offset(left+plotWidth,y), 1f)
                val label = "%.1f".format(low + fraction * range)
                drawContext.canvas.nativeCanvas.drawText(label, 0f, y + axisPaint.textSize/3, axisPaint)
            }
            if(rows.isNotEmpty()) {
                drawContext.canvas.nativeCanvas.drawText(rows.first().text(p.text("xKey")), left, size.height-2.dp.toPx(), axisPaint)
                val last = rows.last().text(p.text("xKey"))
                drawContext.canvas.nativeCanvas.drawText(last, (left+plotWidth-axisPaint.measureText(last)).coerceAtLeast(left), size.height-2.dp.toPx(), axisPaint)
            }
            if (type=="pie" || type=="donut") {
                val total = rows.sumOf { it.number(series.first()).coerceAtLeast(0.0) }
                var start = -90f
                val diameter = minOf(size.width,size.height)-8f
                rows.forEachIndexed { i,row ->
                    val sweep = if(total>0) (row.number(series.first()).coerceAtLeast(0.0)/total*360).toFloat() else 0f
                    drawArc(palette[i%palette.size],start,sweep,type=="pie",Offset((size.width-diameter)/2,4f),Size(diameter,diameter),style=if(type=="pie") Fill else Stroke(diameter*.18f))
                    start += sweep
                }
            } else {
                val zero = point(0,0.0).y
                if (zero in 0f..bottom) drawLine(ink.copy(alpha=.35f),Offset(left,zero),Offset(left+plotWidth,zero),1f)
                series.forEachIndexed { s,name ->
                    val color = palette[s%palette.size]
                    val points = rows.mapIndexed { i,row -> point(i,row.number(name)) }
                    val path = Path().apply { points.forEachIndexed { i,v -> if(i==0)moveTo(v.x,v.y) else lineTo(v.x,v.y) } }
                    when(type) {
                        "bar" -> rows.forEachIndexed { i,row ->
                            val group = plotWidth/rows.size
                            val width = group/series.size*.8f
                            val y = point(i,row.number(name)).y
                            drawRect(color,Offset(left+i*group+s*group/series.size,minOf(y,zero)),Size(width,kotlin.math.abs(y-zero).coerceAtLeast(1f)))
                        }
                        "scatter" -> points.forEach { drawCircle(color,4f,it) }
                        else -> {
                            if(type=="area") {
                                val area=Path().apply { addPath(path); lineTo(points.last().x,zero);lineTo(points.first().x,zero);close() }
                                drawPath(area,color.copy(alpha=.18f))
                            }
                            drawPath(path,color,style=Stroke(2.5f))
                            points.forEach { drawCircle(color,3f,it) }
                        }
                    }
                }
            }
        }
        if (series.size > 1 || type == "pie" || type == "donut") Text(series.joinToString(" · "),style=MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun NativeUiMap(p: JsonObject) = NativeUiStreetMap(p)
