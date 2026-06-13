package me.rerere.rikkahub.data.screenshot

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 连接电脑端 Screenshotter 服务，远程截取电脑屏幕。
 *
 * 服务端接口（windows/Screenshotter.Windows）：
 *  - GET  /api/info                      连通性测试，返回服务器状态/IP/端口
 *  - POST /api/screenshot/all-separate   截取所有显示器，每个显示器返回一张 base64 PNG
 *
 * 仅用于本地局域网，无需鉴权。
 */
class RemoteScreenshotClient(
    private val client: OkHttpClient,
) {
    /** 单张截图：显示器名称 + PNG 字节 */
    data class Shot(val monitorName: String, val bytes: ByteArray)

    /**
     * 截取所有显示器，每个显示器一张图片。
     * @return 每个显示器对应一张 PNG 截图
     */
    suspend fun captureAll(host: String, port: Int): List<Shot> = withContext(Dispatchers.IO) {
        val url = "http://${host.trim()}:$port/api/screenshot/all-separate"
        val request = Request.Builder()
            .url(url)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        callWithTimeout(request).use { resp ->
            if (!resp.isSuccessful) {
                error("服务器返回错误: HTTP ${resp.code}")
            }
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) error("服务器返回空响应")
            val array = JSONArray(body)
            val shots = ArrayList<Shot>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val base64 = obj.optStringIgnoreCase("imageBase64")
                    ?: error("响应缺少图像数据")
                val name = obj.optStringIgnoreCase("monitorName") ?: "Monitor ${i + 1}"
                shots.add(Shot(name, Base64.decode(base64, Base64.DEFAULT)))
            }
            if (shots.isEmpty()) error("未截取到任何屏幕")
            shots
        }
    }

    /**
     * 连通性测试，返回服务器信息原文（JSON）。失败时抛出异常。
     */
    suspend fun testConnection(host: String, port: Int): String = withContext(Dispatchers.IO) {
        val url = "http://${host.trim()}:$port/api/info"
        val request = Request.Builder().url(url).get().build()
        callWithTimeout(request).use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
    }

    private fun callWithTimeout(request: Request) =
        client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .execute()

    // 服务端可能使用 camelCase 或 PascalCase 序列化字段，统一按忽略大小写匹配
    private fun JSONObject.optStringIgnoreCase(key: String): String? {
        if (has(key)) return getString(key)
        val keys = keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.equals(key, ignoreCase = true)) return getString(k)
        }
        return null
    }
}
