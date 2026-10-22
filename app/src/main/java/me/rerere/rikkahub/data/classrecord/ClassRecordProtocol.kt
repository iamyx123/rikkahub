package me.rerere.rikkahub.data.classrecord

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal const val CLASS_RECORD_ORIGIN = "https://classroom.iamyx.co"
internal const val CLASS_RECORD_PREFIX = "/api/v1/auth/classrecord"
internal const val CLASS_RECORD_JSON_LIMIT = 16L * 1024 * 1024
internal const val CLASS_RECORD_FILE_LIMIT = 2L * 1024 * 1024 * 1024

internal fun classRecordOrigin(value: String): HttpUrl {
    val url = value.trim().trimEnd('/').toHttpUrl()
    require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
        url.encodedPath == "/" && url.query == null && url.fragment == null) {
        "学校服务地址必须是 HTTPS 站点地址，不能包含账号、路径或查询参数。"
    }
    return url
}

internal fun classRecordUrl(origin: HttpUrl, path: String, query: JsonObject): HttpUrl {
    require(path.startsWith("/api/v1/") && !path.contains('\\') && !path.contains('#') &&
        !path.contains('?') && !path.contains(Regex("[\\u0000-\\u0020]"))) { "接口路径无效。" }
    val url = origin.resolve(path) ?: error("接口路径无效。")
    require(url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port &&
        url.encodedPath == path && !Regex("(?i)%(2e|2f|5c|25)").containsMatchIn(path)) {
        "接口路径不能跳转到其他站点或目录。"
    }
    return url.newBuilder().apply {
        query.forEach { (name, value) ->
            val values = if (value is JsonArray) value else listOf(value)
            values.forEach { item ->
                if (item is JsonPrimitive && item.contentOrNull != null) addQueryParameter(name, item.content)
            }
        }
    }.build()
}

internal fun classRecordSafeName(name: String): String = name.substringAfterLast('/')
    .substringAfterLast('\\').replace(Regex("[\\p{Cntrl}<>:\"|?*]"), "_")
    .trim().take(160).ifBlank { "classrecord-file" }

/** Stream to disk and verify both advertised identities before registering a chat attachment. */
internal fun copyVerifiedClassRecordFile(
    input: InputStream,
    output: OutputStream,
    expectedBytes: Long,
    expectedSha256: String,
    checkCancelled: () -> Unit = {},
) {
    require(expectedBytes in 0..CLASS_RECORD_FILE_LIMIT) { "文件超过 2 GB，请分段调取录音。" }
    require(expectedSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "服务未返回有效的文件校验值。" }
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var size = 0L
    while (true) {
        checkCancelled()
        val read = input.read(buffer)
        if (read < 0) break
        size += read
        check(size <= expectedBytes) { "下载大小与原件不符，请重新下载。" }
        digest.update(buffer, 0, read)
        output.write(buffer, 0, read)
    }
    val hash = digest.digest().joinToString("") { "%02x".format(it) }
    check(size == expectedBytes && hash.equals(expectedSha256, ignoreCase = true)) {
        "下载未通过大小及 SHA-256 校验，请重新下载。"
    }
}

internal fun JsonObject.crString(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonElement.crObject(): JsonObject = this as? JsonObject ?: error("服务返回的数据格式无效。")
