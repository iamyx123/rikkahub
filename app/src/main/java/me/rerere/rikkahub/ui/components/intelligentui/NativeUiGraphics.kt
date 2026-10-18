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
    var opened by remember(url){mutableStateOf(false)}
    var failed by remember(url){mutableStateOf(false)}
    Column {
        Text(p.text("alt"),style=MaterialTheme.typography.bodySmall)
        if (url.startsWith("https://")) {
            if(!opened) OutlinedButton(onClick={opened=true}){Text("加载图片")}
            else {
                val request=remember(url){ImageRequest.Builder(context).data(url).size(1024,1024)
                    .decoderFactory(BitmapFactoryDecoder.Factory()).crossfade(false).build()}
                AsyncImage(model=request,contentDescription=p.text("alt"),modifier=Modifier.fillMaxWidth().heightIn(max=320.dp),onError={failed=true})
                if(failed)Text("图片未能加载",style=MaterialTheme.typography.labelSmall)
            }
        }
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
    val values = rows.flatMap { row -> series.map { row.number(it) } }
    val low = minOf(0.0, values.minOrNull() ?: 0.0)
    val high = maxOf(0.0, values.maxOrNull() ?: 1.0)
    val range = (high-low).coerceAtLeast(.001)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (p.text("title").isNotBlank()) Text(p.text("title"), style=MaterialTheme.typography.titleSmall)
        if (rows.isEmpty() || series.isEmpty()) Text("暂无图表数据")
        else Canvas(Modifier.fillMaxWidth().height(200.dp)) {
            fun point(i: Int, v: Double) = Offset(12f + i.toFloat()/maxOf(1,rows.size-1)*(size.width-24f), (size.height-12f-((v-low)/range)*(size.height-24f)).toFloat())
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
                drawLine(ink.copy(alpha=.35f),Offset(0f,zero),Offset(size.width,zero),1f)
                series.forEachIndexed { s,name ->
                    val color = palette[s%palette.size]
                    val points = rows.mapIndexed { i,row -> point(i,row.number(name)) }
                    val path = Path().apply { points.forEachIndexed { i,v -> if(i==0)moveTo(v.x,v.y) else lineTo(v.x,v.y) } }
                    when(type) {
                        "bar" -> rows.forEachIndexed { i,row ->
                            val group = size.width/rows.size
                            val width = group/series.size*.8f
                            val y = point(i,row.number(name)).y
                            drawRect(color,Offset(i*group+s*group/series.size,minOf(y,zero)),Size(width,kotlin.math.abs(y-zero).coerceAtLeast(1f)))
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
        // Exact values make charts readable on monochrome screens and accessible without color.
        Text(series.joinToString(" · "),style=MaterialTheme.typography.labelMedium)
        rows.take(12).forEach { row -> Text(row.text(p.text("xKey"))+"："+series.joinToString("，"){"$it ${row.number(it)}"},style=MaterialTheme.typography.bodySmall) }
        if(rows.size>12) Text("共 ${rows.size} 个数据点；完整数据见源码",style=MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun NativeUiVector(p: JsonObject) {
    val scheme = MaterialTheme.colorScheme
    val monochrome=LocalSettings.current.displaySetting.disableCursorBlink
    val view=remember(p.text("viewBox")) { p.text("viewBox").split(Regex("[ ,]+")).mapNotNull{it.toFloatOrNull()?.takeIf(Float::isFinite)}.takeIf{it.size==4&&it[2] in 1f..100000f&&it[3] in 1f..100000f} ?: listOf(0f,0f,400f,240f) }
    fun color(value:String,default:Color=scheme.onSurface):Color = when(value) {
        "none" -> Color.Transparent
        "primary" -> if(monochrome)scheme.onSurface else scheme.primary
        "secondary" -> if(monochrome)scheme.onSurface else scheme.secondary
        "surface" -> scheme.surface
        "muted" -> scheme.onSurfaceVariant
        "ink","" -> default
        else -> if(monochrome)scheme.onSurface else runCatching{Color(android.graphics.Color.parseColor(value))}.getOrDefault(default)
    }
    val textPaint=remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    Column {
        Canvas(Modifier.fillMaxWidth().aspectRatio((view[2]/view[3]).coerceIn(.5f,3f))) {
            fun DrawScope.shape(node:JsonElement,depth:Int=0) {
                if(depth>24)return
                val o=node as? JsonObject ?: return
                val v=o["props"] as? JsonObject ?: return
                val type=o.text("type")
                fun n(key:String)=v.number(key).coerceIn(-100000.0,100000.0).toFloat()
                if(type=="G"){v.list("children").forEach{shape(it,depth+1)};return}
                if(type=="SvgText") {
                    textPaint.color=color(v.text("fill")).toArgb();textPaint.textSize=v.number("size",14.0).coerceIn(6.0,96.0).toFloat()
                    drawContext.canvas.nativeCanvas.drawText(v.text("text").take(1000),n("x"),n("y"),textPaint);return
                }
                val path=Path()
                when(type) {
                    "Circle" -> path.addOval(androidx.compose.ui.geometry.Rect(n("cx")-n("r"),n("cy")-n("r"),n("cx")+n("r"),n("cy")+n("r")))
                    "Ellipse" -> path.addOval(androidx.compose.ui.geometry.Rect(n("cx")-n("rx"),n("cy")-n("ry"),n("cx")+n("rx"),n("cy")+n("ry")))
                    "Rect" -> path.addRect(androidx.compose.ui.geometry.Rect(n("x"),n("y"),n("x")+n("width"),n("y")+n("height")))
                    "Line" -> {path.moveTo(n("x1"),n("y1"));path.lineTo(n("x2"),n("y2"))}
                    "Path" -> runCatching{path.addPath(PathParser().parsePathString(v.text("data").take(16000)).toPath())}
                    "Polygon","Polyline" -> {
                        val coords=v.text("data").split(Regex("[ ,]+")).mapNotNull{it.toFloatOrNull()?.takeIf(Float::isFinite)}.take(2000)
                        coords.chunked(2).filter{it.size==2}.forEachIndexed{i,xy->if(i==0)path.moveTo(xy[0],xy[1]) else path.lineTo(xy[0],xy[1])}
                        if(type=="Polygon")path.close()
                    }
                }
                if(type!="Line"&&type!="Polyline")drawPath(path,color(v.text("fill","ink")))
                if(v.text("stroke").isNotEmpty()||type=="Line"||type=="Polyline")drawPath(path,color(v.text("stroke")),style=Stroke(v.number("strokeWidth",1.5).coerceIn(.1,20.0).toFloat()))
            }
            clipRect { scale(size.width/view[2],size.height/view[3],pivot=Offset.Zero){translate(-view[0],-view[1]){p.list("children").forEach{shape(it)}}} }
        }
        Text(p.text("description"),style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun NativeUiMap(p:JsonObject) {
    val markers=p.list("markers").take(50).mapNotNull{it as? JsonObject}.filter{it.number("lat") in -90.0..90.0&&it.number("lon") in -180.0..180.0}
    val uri=LocalUriHandler.current
    val ink=MaterialTheme.colorScheme.onSurface
    Column {
        Text(p.text("title","地点坐标概览"),style=MaterialTheme.typography.titleSmall)
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            drawRect(ink.copy(alpha=.3f),style=Stroke(1f))
            for(i in 1..3){drawLine(ink.copy(alpha=.15f),Offset(0f,size.height*i/4),Offset(size.width,size.height*i/4),1f)}
            markers.forEach { drawCircle(ink,5f,Offset(((it.number("lon")+180)/360*size.width).toFloat(),((90-it.number("lat"))/180*size.height).toFloat())) }
        }
        Text("坐标概览；点击地点打开地图",style=MaterialTheme.typography.labelSmall)
        markers.forEach { point -> TextButton(onClick={uri.openUri("geo:${point.number("lat")},${point.number("lon")}?q=${point.number("lat")},${point.number("lon")}")}) { Text("${point.text("label")} (${point.number("lat")}, ${point.number("lon")})") } }
    }
}
