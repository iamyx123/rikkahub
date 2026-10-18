package me.rerere.rikkahub.data.intelligentui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.*

/** Only explicit component clicks call this service. URLs are built here, never supplied by a model. */
object InteractiveUiMedia {
    private val network = Semaphore(3)
    private data class Cached(val bytes: ByteArray, val expires: Long)
    private val cache = LinkedHashMap<String, Cached>(32, .75f, true)

    private suspend fun fetch(client: OkHttpClient, url: String, limit: Int = 2 * 1024 * 1024): ByteArray {
        synchronized(cache) {
            cache[url]?.takeIf { it.expires > System.currentTimeMillis() }?.let { return it.bytes }
        }
        return network.withPermit { withContext(Dispatchers.IO) {
            val bounded = client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
            val request = Request.Builder().url(url)
                .header("User-Agent", "RikkaHub-InteractiveUI/1.0 (+https://github.com/iamyx123/rikkahub)")
                .build()
            bounded.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "资源服务暂不可用（${response.code}），可稍后重试" }
                val body = requireNotNull(response.body)
                require(body.contentLength() <= limit) { "资源过大" }
                val source = body.source()
                source.request(limit.toLong() + 1)
                require(source.buffer.size <= limit) { "资源过大" }
                val bytes = source.readByteArray()
                synchronized(cache) {
                    cache[url] = Cached(bytes, System.currentTimeMillis() + 10 * 60_000)
                    while (cache.size > 32 || cache.values.sumOf { it.bytes.size } > 4 * 1024 * 1024) {
                        cache.remove(cache.keys.first())
                    }
                }
                bytes
            }
        } }
    }

    suspend fun photo(client: OkHttpClient, query: String): JsonObject {
        require(query.isNotBlank() && query.length <= 160) { "搜索词应为 1 至 160 个字符" }
        // Wikipedia supplies a relevant lead photograph. Commons is the image-only fallback.
        for (language in listOf("zh", "en")) {
            val url = "https://$language.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
                .addQueryParameter("action", "query").addQueryParameter("format", "json")
                .addQueryParameter("generator", "search").addQueryParameter("gsrsearch", query)
                .addQueryParameter("gsrlimit", "5").addQueryParameter("prop", "pageimages|info")
                .addQueryParameter("inprop", "url").addQueryParameter("piprop", "thumbnail")
                .addQueryParameter("pithumbsize", "800").build()
            try {
                val json = Json.parseToJsonElement(fetch(client, url.toString()).decodeToString()).jsonObject
                wikiPhoto(json)?.let { return it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // An unavailable language edition must not prevent trying the other sources.
            }
        }
        val url = "https://commons.wikimedia.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query").addQueryParameter("format", "json")
            .addQueryParameter("generator", "search").addQueryParameter("gsrsearch", query)
            .addQueryParameter("gsrnamespace", "6").addQueryParameter("gsrlimit", "8")
            .addQueryParameter("prop", "imageinfo").addQueryParameter("iiprop", "url|mime")
            .addQueryParameter("iiurlwidth", "800").build()
        val json = Json.parseToJsonElement(fetch(client, url.toString()).decodeToString()).jsonObject
        return wikiPhoto(json) ?: error("没有找到合适的图片，可修改搜索词后重试")
    }

    internal fun wikiPhoto(json: JsonObject): JsonObject? {
        val pages = (json["query"] as? JsonObject)?.get("pages") as? JsonObject ?: return null
        return pages.values.mapNotNull { it as? JsonObject }.sortedBy { it["index"]?.jsonPrimitive?.intOrNull ?: 0 }
            .firstNotNullOfOrNull { page ->
                val info = (page["imageinfo"] as? JsonArray)?.firstOrNull() as? JsonObject
                val thumb = page["thumbnail"] as? JsonObject
                val url = (thumb?.get("source") ?: info?.get("thumburl") ?: info?.get("url"))?.jsonPrimitive?.contentOrNull
                val mime = info?.get("mime")?.jsonPrimitive?.contentOrNull
                if (url == null || !(url.startsWith("https://upload.wikimedia.org/") || url.startsWith("https://thumb.wikimedia.org/")) ||
                    (mime != null && mime !in setOf("image/jpeg", "image/png", "image/webp")) ||
                    url.substringBefore('?').endsWith(".gif", true)) null
                else buildJsonObject {
                    put("url", url)
                    put("title", page["title"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    put("pageUrl", (page["fullurl"] ?: info?.get("descriptionurl"))?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.startsWith("https://") }.orEmpty())
                    put("credit", "Wikipedia / Wikimedia Commons · 作者及授权见图片来源")
                }
            }
    }

    suspend fun tile(client: OkHttpClient, zoom: Int, x: Int, y: Int): ByteArray {
        require(zoom in 1..17 && x in 0 until (1 shl zoom) && y in 0 until (1 shl zoom))
        return fetch(client, "https://tile.openstreetmap.org/$zoom/$x/$y.png", 512 * 1024)
    }

    suspend fun road(client: OkHttpClient, markers: JsonArray): JsonObject {
        val points = markerPoints(markers)
        require(points.size in 2..20) { "道路概览需要 2 至 20 个地点" }
        val coordinates = points.joinToString(";") { "${it.lon},${it.lat}" }
        val json = Json.parseToJsonElement(fetch(client,
            "https://router.project-osrm.org/route/v1/driving/$coordinates?overview=full&geometries=geojson&steps=false"
        ).decodeToString()).jsonObject
        require(json["code"]?.jsonPrimitive?.contentOrNull == "Ok") { "这些地点之间没有可用道路，可查看直线连线" }
        val route = (json["routes"] as? JsonArray)?.firstOrNull()?.jsonObject ?: error("道路服务未返回路线")
        val raw = (route["geometry"]?.jsonObject?.get("coordinates") as? JsonArray).orEmpty()
        val stride = ceil(raw.size / 2000.0).toInt().coerceAtLeast(1)
        return buildJsonObject {
            put("coordinates", JsonArray(raw.filterIndexed { index, _ -> index % stride == 0 || index == raw.lastIndex }))
            put("distance", route["distance"] ?: JsonPrimitive(0))
            put("description", "驾车道路概览（OSRM / OpenStreetMap），不是步行或公共交通导航")
        }
    }
}

data class UiMapPoint(val lat: Double, val lon: Double, val label: String, val category: String)
fun markerPoints(markers: JsonArray): List<UiMapPoint> = markers.take(50).mapNotNull { element ->
    val outer = element as? JsonObject ?: return@mapNotNull null
    val p = (outer["props"] as? JsonObject) ?: outer
    val lat = (p["lat"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
    val lon = (p["lon"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
    if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
    UiMapPoint(lat, lon, (p["label"] as? JsonPrimitive)?.contentOrNull.orEmpty(), (p["category"] as? JsonPrimitive)?.contentOrNull.orEmpty())
}

/** Coordinates in tile units, clamped to the Web Mercator latitude boundary. */
fun mapPosition(lat: Double, lon: Double, zoom: Int): Pair<Double, Double> {
    val scale = (1 shl zoom).toDouble()
    val radians = lat.coerceIn(-85.05112878, 85.05112878) * PI / 180
    return ((lon + 180) / 360 * scale) to ((1 - asinh(tan(radians)) / PI) / 2 * scale)
}

data class UiMapView(val zoom: Int, val x: Double, val y: Double)
fun fitMap(points: List<UiMapPoint>): UiMapView {
    if (points.isEmpty()) return UiMapView(2, 2.0, 2.0)
    for (zoom in 15 downTo 1) {
        val n = (1 shl zoom).toDouble()
        val first = mapPosition(points.first().lat, points.first().lon, zoom).first
        val projected = points.map { mapPosition(it.lat, it.lon, zoom).let { p ->
            (first + ((p.first - first + n * 1.5) % n) - n / 2) to p.second
        } }
        val minX = projected.minOf { it.first }; val maxX = projected.maxOf { it.first }
        val minY = projected.minOf { it.second }; val maxY = projected.maxOf { it.second }
        if (zoom == 1 || maxX - minX <= 1.5 && maxY - minY <= 1.5) {
            return UiMapView(zoom, ((minX + maxX) / 2 + n) % n, ((minY + maxY) / 2).coerceIn(0.0, n - .001))
        }
    }
    error("No viewport")
}
