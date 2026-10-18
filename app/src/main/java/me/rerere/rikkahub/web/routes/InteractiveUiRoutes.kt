package me.rerere.rikkahub.web.routes

import io.ktor.http.ContentType
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.intelligentui.InteractiveUiMedia
import me.rerere.rikkahub.web.BadRequestException
import okhttp3.OkHttpClient
import org.koin.core.context.GlobalContext

/** Mounted under the same authentication as chat, never a general URL proxy. */
fun Route.interactiveUiMediaRoutes(client: () -> OkHttpClient = { GlobalContext.get().get() }) {
    route("/interactive-ui") {
        get("/photo") {
            val query = call.request.queryParameters["query"].orEmpty()
            if (query.isBlank() || query.length > 160) throw BadRequestException("搜索词应为 1 至 160 个字符")
            call.respond(InteractiveUiMedia.photo(client(), query))
        }
        get("/tile") {
            val z = call.request.queryParameters["z"]?.toIntOrNull() ?: 0
            val x = call.request.queryParameters["x"]?.toIntOrNull() ?: -1
            val y = call.request.queryParameters["y"]?.toIntOrNull() ?: -1
            if(z !in 1..17 || x !in 0 until (1 shl z) || y !in 0 until (1 shl z)) throw BadRequestException("地图坐标无效")
            call.respondBytes(InteractiveUiMedia.tile(client(), z, x, y), ContentType.Image.PNG)
        }
        post("/road") {
            val request = call.receive<JsonObject>()
            val points = request["markers"] as? JsonArray ?: throw BadRequestException("缺少地点")
            if(points.size !in 2..20) throw BadRequestException("道路概览需要 2 至 20 个地点")
            call.respond(InteractiveUiMedia.road(client(), points))
        }
    }
}
