package me.rerere.rikkahub.data.intelligentui

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class InteractiveUiMediaTest {
    @Test fun `photo lookup continues when a language edition is unavailable`() = runBlocking {
        val hosts = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val host = chain.request().url.host
            hosts += host
            val available = host == "en.wikipedia.org"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (available) 200 else 503).message(if (available) "OK" else "Unavailable")
                .body((if (available) """{"query":{"pages":{"1":{"title":"Fallback","fullurl":"https://en.wikipedia.org/wiki/Fallback","thumbnail":{"source":"https://upload.wikimedia.org/fallback.jpg"}}}}}""" else "Unavailable").toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = InteractiveUiMedia.photo(client, "fallback-network-test")
        assertEquals(listOf("zh.wikipedia.org", "en.wikipedia.org"), hosts)
        assertEquals("Fallback", result["title"]!!.jsonPrimitive.content)
    }

    @Test fun `photo lookup uses bounded known hosts and preserves source attribution`() = runBlocking {
        var requested = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requested = true
            assertEquals("zh.wikipedia.org", chain.request().url.host)
            assertEquals("interactive test 北京 & 東京", chain.request().url.queryParameter("gsrsearch"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"query":{"pages":{"1":{"title":"北京","index":1,"fullurl":"https://zh.wikipedia.org/wiki/Beijing","thumbnail":{"source":"https://upload.wikimedia.org/test-photo.jpg"}}}}}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = InteractiveUiMedia.photo(client, "interactive test 北京 & 東京")
        assertTrue(requested)
        assertEquals("北京", result["title"]!!.jsonPrimitive.content)
        assertTrue(result["pageUrl"]!!.jsonPrimitive.content.startsWith("https://zh.wikipedia.org/"))
        assertTrue(result["credit"]!!.jsonPrimitive.content.contains("授权"))
    }

    @Test fun `unsafe and animated photo candidates are rejected`() {
        for (url in listOf("http://upload.wikimedia.org/test.jpg", "https://other.example/test.jpg", "https://thumb.wikimedia.org.evil.example/test.jpg", "https://upload.wikimedia.org/test.gif")) {
            val response = buildJsonObject { put("query", buildJsonObject { put("pages", buildJsonObject {
                put("1", buildJsonObject { put("thumbnail", buildJsonObject { put("source", url) }) })
            }) }) }
            assertNull(InteractiveUiMedia.wikiPhoto(response))
        }
    }

    @Test fun `official Wikimedia thumbnail CDN is accepted`() {
        val response = Json.parseToJsonElement("""{"query":{"pages":{"1":{"title":"天坛","fullurl":"https://zh.wikipedia.org/wiki/天坛","thumbnail":{"source":"https://thumb.wikimedia.org/wikipedia/commons/thumb/test.jpg/960px-test.jpg"}}}}}""").jsonObject
        assertNotNull(InteractiveUiMedia.wikiPhoto(response))
    }

    @Test fun `map accepts components rejects invalid positions and fits dateline`() {
        val markers = Json.parseToJsonElement("""[{"type":"MapMarker","props":{"lat":0,"lon":179.9,"label":"A","category":"地点"}},{"lat":0,"lon":-179.9,"label":"B"},{"lat":91,"lon":0},{"lat":0,"lon":181}]""").jsonArray
        val points = markerPoints(markers)
        assertEquals(2, points.size)
        val viewport = fitMap(points)
        assertTrue("Dateline points are nearby, not world-spanning", viewport.zoom >= 8)
        assertTrue(mapPosition(90.0, 0.0, 10).second.isFinite())
        assertTrue(mapPosition(-90.0, 0.0, 10).second.isFinite())
    }

    @Test fun `map downloads only known tile hosts and bounds response size`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("tile.openstreetmap.org", chain.request().url.host)
            assertEquals("/3/1/2.png", chain.request().url.encodedPath)
            assertTrue(chain.request().header("User-Agent")!!.contains("RikkaHub"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ByteArray(512 * 1024 + 1).toResponseBody("image/png".toMediaType())).build()
        }.build()
        try { InteractiveUiMedia.tile(client, 3, 1, 2); fail("Oversized tile must fail") }
        catch (_: IllegalArgumentException) { }
        try { InteractiveUiMedia.tile(client, 18, 0, 0); fail("Invalid zoom must fail before network") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun `road response retains geometry and identifies driving profile`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("router.project-osrm.org", chain.request().url.host)
            assertTrue(chain.request().url.encodedPath.startsWith("/route/v1/driving/"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":"Ok","routes":[{"distance":123,"geometry":{"coordinates":[[13.0,52.0],[13.1,52.1]]}}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = InteractiveUiMedia.road(client, Json.parseToJsonElement("""[{"lat":52,"lon":13},{"lat":52.1,"lon":13.1}]""").jsonArray)
        assertEquals(2, result["coordinates"]!!.jsonArray.size)
        assertEquals(123, result["distance"]!!.jsonPrimitive.int)
        assertTrue(result["description"]!!.jsonPrimitive.content.contains("驾车"))
    }
}
