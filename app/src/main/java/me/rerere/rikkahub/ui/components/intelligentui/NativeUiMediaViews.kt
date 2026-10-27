package me.rerere.rikkahub.ui.components.intelligentui

import android.graphics.BitmapFactory
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clipToBounds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.intelligentui.*
import me.rerere.rikkahub.ui.context.LocalSettings
import okhttp3.OkHttpClient
import org.koin.compose.koinInject
import kotlin.math.floor

@Composable
internal fun NativeUiSearchImage(p: JsonObject) {
    val client: OkHttpClient = koinInject()
    val query = p.text("query")
    val scope = key(query) { rememberCoroutineScope() }
    var photo by remember(query) { mutableStateOf<JsonObject?>(null) }
    var busy by remember(query) { mutableStateOf(false) }
    var error by remember(query) { mutableStateOf<String?>(null) }
    suspend fun search() {
        busy = true; error = null
        try { photo = InteractiveUiMedia.photo(client, query) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message?.take(160) ?: "图片搜索失败" }
        finally { busy = false }
    }
    // 卡片显示出来就开始找图，不用再点一次
    LaunchedEffect(query) { if (photo == null && query.isNotBlank()) search() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (photo == null) Text(p.text("alt", query), style = MaterialTheme.typography.bodyMedium)
        if (error != null) {
            Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !busy, onClick = { scope.launch { search() } }) { Text("重新查找") }
        }
        photo?.let { found ->
            NativeUiImage(buildJsonObject { put("url", found.text("url")); put("alt", found.text("title")) })
            Text(found.text("credit"), style = MaterialTheme.typography.labelSmall)
            NativeUiLink(buildJsonObject { put("text", "图片来源与授权"); put("url", found.text("pageUrl")) })
        }
    }
}

@Composable
internal fun NativeUiStreetMap(p: JsonObject) {
    val client: OkHttpClient = koinInject()
    val markers = remember(p) { markerPoints(p.list("markers")) }
    var category by remember(p) { mutableStateOf("") }
    val points = markers.filter { category.isEmpty() || it.category == category }
    var view by remember(points) { mutableStateOf(fitMap(points)) }
    var opened by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var road by remember(points) { mutableStateOf<JsonObject?>(null) }
    var busy by remember(points) { mutableStateOf(false) }
    var error by remember(points) { mutableStateOf<String?>(null) }
    val scope = key(points) { rememberCoroutineScope() }
    val eink = LocalSettings.current.displaySetting.disableCursorBlink
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    val categories = markers.map { it.category }.filter { it.isNotBlank() }.distinct()
    fun move(dx: Int, dy: Int) {
        val n = (1 shl view.zoom).toDouble()
        view = view.copy(x = (view.x + dx + n) % n, y = (view.y + dy).coerceIn(0.0, n - .001))
    }
    fun zoom(delta: Int) {
        val z = (view.zoom + delta).coerceIn(1, 17)
        val scale = (1 shl z).toDouble() / (1 shl view.zoom)
        view = UiMapView(z, view.x * scale, view.y * scale)
    }
    val content: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(p.text("title", "地图"), style = MaterialTheme.typography.titleSmall)
            if (categories.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { category = "" }) { Text(if(category.isEmpty()) "● 全部" else "全部") }
                    // A bounded dropdown avoids a wide row on a phone.
                    var menu by remember { mutableStateOf(false) }
                    Column {
                        TextButton(onClick = { menu = !menu }) { Text(category.ifEmpty { "筛选类别" }) }
                        if(eink && menu) Column {
                            categories.forEach { value -> TextButton(onClick = { category = value; menu = false }) { Text(value) } }
                        }
                        if(!eink) DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            categories.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { category = value; menu = false }) }
                        }
                    }
                }
            }
            val n = 1 shl view.zoom
            val startX = floor(view.x).toInt() - 1
            val startY = floor(view.y).toInt() - 1
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clipToBounds()) {
                if (opened) Column(Modifier.fillMaxSize()) {
                    repeat(3) { dy -> Row(Modifier.weight(1f)) {
                        repeat(3) { dx ->
                            val x = ((startX + dx) % n + n) % n; val y = startY + dy
                            var tile by remember(view.zoom, x, y) { mutableStateOf<ImageBitmap?>(null) }
                            LaunchedEffect(view.zoom, x, y) {
                                if(y in 0 until n) try {
                                    tile = withContext(Dispatchers.IO) {
                                        val bytes = InteractiveUiMedia.tile(client, view.zoom, x, y)
                                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                    }
                                } catch(e: CancellationException) { throw e }
                                catch(_: Exception) { error = "部分地图未能加载，可缩放重试或打开外部地图" }
                            }
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                tile?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds,
                                    colorFilter = if(eink) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null) }
                            }
                        }
                    } }
                }
                val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
                Canvas(Modifier.fillMaxSize()) {
                    fun at(lat: Double, lon: Double): Offset {
                        val raw = mapPosition(lat, lon, view.zoom)
                        val worldX = view.x + ((raw.first - view.x + n * 1.5) % n) - n / 2.0
                        return Offset(((worldX - startX) / 3 * size.width).toFloat(), ((raw.second - startY) / 3 * size.height).toFloat())
                    }
                    val coords = road?.list("coordinates")?.mapNotNull { (it as? JsonArray)?.takeIf { a -> a.size >= 2 } }
                    val path = Path()
                    val locations = coords?.map { at(it[1].jsonPrimitive.double, it[0].jsonPrimitive.double) }
                        ?: points.map { at(it.lat, it.lon) }
                    locations.forEachIndexed { i, point -> if(i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y) }
                    drawPath(path, ink, style = Stroke(3f, pathEffect = if(road == null) PathEffect.dashPathEffect(floatArrayOf(8f, 8f)) else null))
                    points.forEachIndexed { i, marker ->
                        val point = at(marker.lat, marker.lon)
                        drawCircle(paper, 13.dp.toPx(), point); drawCircle(ink, 13.dp.toPx(), point, style = Stroke(2.dp.toPx()))
                        paint.color = ink.toArgb(); paint.textSize = 12.dp.toPx(); paint.textAlign = Paint.Align.CENTER
                        drawContext.canvas.nativeCanvas.drawText("${i+1}", point.x, point.y + 4.dp.toPx(), paint)
                    }
                    drawRect(ink.copy(alpha = .4f), style = Stroke(1f))
                }
            }
            if (!opened) OutlinedButton(onClick = { opened = true }) { Text("加载街道地图") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { zoom(-1) }, enabled = view.zoom > 1) { Text("缩小") }
                TextButton(onClick = { zoom(1) }, enabled = view.zoom < 17) { Text("放大") }
                TextButton(onClick = { view = fitMap(points) }) { Text("适应地点") }
                TextButton(onClick = { expanded = !expanded }) { Text(if(expanded) "收起" else "展开") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { move(-1, 0) }) { Text("向西") }; TextButton(onClick = { move(0, -1) }) { Text("向北") }
                TextButton(onClick = { move(0, 1) }) { Text("向南") }; TextButton(onClick = { move(1, 0) }) { Text("向东") }
            }
            TextButton(enabled = !busy && points.size in 2..20, onClick = {
                scope.launch {
                    busy = true; error = null
                    try {
                        road = InteractiveUiMedia.road(client, JsonArray(points.map { point -> buildJsonObject {
                            put("lat", point.lat); put("lon", point.lon); put("label", point.label)
                        } }))
                    } catch(e: CancellationException) { throw e }
                    catch(e: Exception) { error = e.message?.take(160) ?: "道路服务不可用" }
                    finally { busy = false }
                }
            }) { Text(if(busy) "正在获取道路" else "加载道路概览") }
            Text(road?.text("description") ?: "虚线是地点直线连接，并非道路", style = MaterialTheme.typography.labelSmall)
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            points.forEachIndexed { i, point -> NativeUiLink(buildJsonObject {
                put("text", "${i+1}. ${point.label}")
                put("url", "https://www.openstreetmap.org/?mlat=${point.lat}&mlon=${point.lon}#map=14/${point.lat}/${point.lon}")
            }) }
            NativeUiLink(buildJsonObject { put("text", "© OpenStreetMap contributors"); put("url", "https://www.openstreetmap.org/copyright") })
        }
    }
    if(expanded) androidx.compose.ui.window.Dialog(onDismissRequest = { expanded = false }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(12.dp).verticalScrollCompat()) { content() }
        }
    } else content()
}

@Composable
private fun Modifier.verticalScrollCompat() = this.then(
    Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())
)
