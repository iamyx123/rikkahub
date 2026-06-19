package me.rerere.rikkahub.data.screenshot

import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
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

    /**
     * 自动扫描局域网，发现 Screenshotter 服务端，免去手动输入 IP。
     *
     * 取设备所有「站点本地」IPv4 接口（如 192.168.x / 10.x / 172.16-31.x）的 /24 网段，
     * 并发探测 .1~.254 的 /api/info；返回第一个响应成功的 host，未发现返回 null。
     * 单个探测使用很短的超时，发现后立即取消其余探测。
     */
    suspend fun discoverServer(port: Int): String? = withContext(Dispatchers.IO) {
        val candidates = localIpv4Prefixes().flatMap { prefix -> (1..254).map { "$prefix.$it" } }
        if (candidates.isEmpty()) return@withContext null
        val probe = client.newBuilder()
            .connectTimeout(350, TimeUnit.MILLISECONDS)
            .readTimeout(700, TimeUnit.MILLISECONDS)
            .callTimeout(1000, TimeUnit.MILLISECONDS)
            .build()
        val found = CompletableDeferred<String?>()
        val scanScope = CoroutineScope(coroutineContext + Job())
        val gate = Semaphore(40) // 限制并发，避免一次性打开几百个连接
        val jobs = candidates.map { host ->
            scanScope.launch {
                if (found.isCompleted) return@launch
                gate.withPermit {
                    if (found.isCompleted) return@withPermit
                    val ok = runCatching {
                        probe.newCall(
                            Request.Builder().url("http://$host:$port/api/info").get().build()
                        ).execute().use { it.isSuccessful }
                    }.getOrDefault(false)
                    if (ok && !found.isCompleted) found.complete(host)
                }
            }
        }
        // 全部探测结束仍未发现，则以 null 结束等待
        scanScope.launch {
            jobs.joinAll()
            if (!found.isCompleted) found.complete(null)
        }
        val result = found.await()
        scanScope.cancel() // 已有结果，取消剩余探测
        result
    }

    /** 设备所有站点本地 IPv4 接口的 /24 网段前缀（如 "192.168.1"）。 */
    private fun localIpv4Prefixes(): List<String> {
        val prefixes = mutableListOf<String>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        val ip = addr.hostAddress ?: continue
                        val prefix = ip.substringBeforeLast('.')
                        if (prefix.isNotBlank() && prefix != ip) prefixes.add(prefix)
                    }
                }
            }
        }
        return prefixes.distinct()
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
