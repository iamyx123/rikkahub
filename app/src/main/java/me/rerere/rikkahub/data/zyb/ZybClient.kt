package me.rerere.rikkahub.data.zyb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 作业帮错题本客户端（复刻 zyb_errorbook.py）。
 * 登录（手机号+密码）-> 拉取各科目错题 -> 图片原图 + 题干/答案/解析文字(HTML+LaTeX)。
 */
class ZybClient(
    private val client: OkHttpClient,
) {
    companion object {
        private const val BASE_URL = "https://mo.paperang.com/"
        private const val LOGIN_PATH = "session/wsubmit/login"
        private const val ERR_GROUPS_PATH = "studyk12/studygroup/getstudygroup/apiversion/v2"
        private const val ERR_LIST_PATH = "studyk12/study/getstudycontentlistpad"
        private const val DEFAULT_KEY = "fc07b8600c393d46eded582aad32a452"
        private const val DEFAULT_TOKEN = "md7pf62N2ccQpwuwWFEPkPGxUyUHwOn32BuUsxm/i4I="
    }

    // 会话状态
    @Volatile var sessionKey: String = DEFAULT_KEY; private set
    @Volatile var signToken: String = DEFAULT_TOKEN; private set
    @Volatile var userId: Long = 0; private set
    @Volatile var mbuss: String = ""; private set
    @Volatile var gradeId: Int = 0; private set
    @Volatile var nickName: String = ""; private set

    private val deviceId = "9D253DCD39491C26894065ABCDD39CE5|0"
    private val userAgent = "MiaoMiaoJi/76000/YD YDD011/8.1.0"
    private val appVersion = "7.60.00"

    val loggedIn: Boolean get() = userId > 0 && mbuss.isNotEmpty()

    data class ErrGroup(val name: String, val groupId: Int, val courseId: Int, val count: Int)

    data class ErrText(
        val questionHtml: String, val questionText: String,
        val answerHtml: String, val answerText: String,
        val analysisHtml: String, val analysisText: String,
    ) {
        val hasText: Boolean get() = questionText.isNotBlank() || answerText.isNotBlank()
    }

    data class ErrItem(
        val tid: String,
        val subject: Int,
        val degree: String,
        val reason: String,
        val create: String,
        /** [(标签, url)]，原图优先 */
        val images: List<Pair<String, String>>,
        val text: ErrText,
    )

    private fun post(path: String, obj: List<Pair<String, Any>>): JSONObject? {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val inner = ZybCrypto.prettyFlatObject(obj)
        val body = ZybCrypto.prettyFlatObject(
            listOf("method" to "", "parameter" to ZybCrypto.aesEncrypt(inner, sessionKey))
        )
        val sign = ZybCrypto.sha1Hex("$userId&$ts&$signToken&$body")
        val request = Request.Builder()
            .url(BASE_URL + path)
            .post(body.toByteArray(Charsets.UTF_8).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("content-type", "application/json; charset=utf-8")
            .header("iencrypt", "aes")
            .header("user-agent", userAgent)
            .header("language", "zh_CN")
            .header("timestamp", ts)
            .header("systype", "1")
            .header("versiontype", "1")
            .apply { if (mbuss.isNotEmpty()) header("cookie", "MBUSS=$mbuss") }
            .header("sign", sign)
            .header("version", appVersion)
            .header("deviceid", deviceId)
            .header("zyb-cuid", deviceId)
            .header("na__zyb_source__", "miaobao")
            .build()
        val call = client.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
            .newCall(request)
        call.execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (text.isBlank()) return null
            return runCatching { JSONObject(text) }.getOrNull()
        }
    }

    /** 解密响应中的 data 字段，返回 JSONObject 或 null。 */
    private fun decryptData(resp: JSONObject?): JSONObject? {
        if (resp == null) return null
        val data = resp.opt("data") ?: resp.opt("Data") ?: resp.opt("result")
        return when (data) {
            is String -> {
                if (data.isBlank()) return null
                for (key in listOf(sessionKey, DEFAULT_KEY)) {
                    val dec = runCatching { JSONObject(ZybCrypto.aesDecrypt(data, key)) }.getOrNull()
                    if (dec != null) return dec
                }
                null
            }
            is JSONObject -> data
            else -> null
        }
    }

    suspend fun login(phone: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // 登录用默认会话密钥
            sessionKey = DEFAULT_KEY
            signToken = DEFAULT_TOKEN
            userId = 0
            mbuss = ""
            val req = listOf(
                "userName" to phone,
                "userPassWord" to ZybCrypto.md5Lower(password),
                "type" to 1,
                "version" to 76000,
                "remark" to "{}",
                "ip" to "",
                "language" to "zh",
                "area" to "CN",
                "country" to "CN",
            )
            val resp = post(LOGIN_PATH, req)
            val dec = decryptData(resp) ?: error("登录失败：无法解析响应")
            sessionKey = dec.optString("aesKey", sessionKey)
            signToken = dec.optString("signToken", signToken)
            userId = dec.optLong("userId", userId)
            mbuss = dec.optString("mbuss", mbuss)
            gradeId = dec.optInt("gradeId", gradeId)
            nickName = dec.optString("nickName", nickName)
            if (!loggedIn) {
                val errMsg = resp?.optString("errNo").orEmpty() + " " + resp?.optString("errstr").orEmpty()
                error("登录失败：账号或密码错误 ${errMsg.trim()}")
            }
        }
    }

    suspend fun getErrGroups(): Result<List<ErrGroup>> = withContext(Dispatchers.IO) {
        runCatching {
            val req = listOf("gradeId" to gradeId, "iconVersion" to 6, "autoCreate" to 0)
            val dec = decryptData(post(ERR_GROUPS_PATH, req)) ?: error("获取科目失败")
            val list = dec.optJSONArray("list") ?: JSONArray()
            (0 until list.length()).mapNotNull { i ->
                val o = list.optJSONObject(i) ?: return@mapNotNull null
                ErrGroup(
                    name = o.optString("name"),
                    groupId = o.optInt("groupId"),
                    courseId = o.optInt("courseId"),
                    count = o.optInt("studyContentCount"),
                )
            }
        }
    }

    suspend fun getErrList(groupId: Int, page: Int = 1, size: Int = 20): Result<Pair<Int, List<ErrItem>>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = listOf(
                    "groupIds" to groupId.toString(),
                    "gradeId" to gradeId,
                    "pageIndex" to page,
                    "pageSize" to size,
                )
                val dec = decryptData(post(ERR_LIST_PATH, req)) ?: error("获取错题失败")
                val total = dec.optInt("total", 0)
                val list = dec.optJSONArray("list") ?: JSONArray()
                val items = (0 until list.length()).mapNotNull { i ->
                    val it = list.optJSONObject(i) ?: return@mapNotNull null
                    val detail = it.optJSONObject("detail") ?: JSONObject()
                    ErrItem(
                        tid = detail.optString("tid", it.optString("tid", it.optString("studyContentId"))),
                        subject = detail.optInt("subject", 0),
                        degree = it.optString("degreeName", ""),
                        reason = it.optString("reasonName", ""),
                        create = it.optString("updateTime", it.optString("sysDate", "")),
                        images = extractImages(it),
                        text = extractText(detail),
                    )
                }
                total to items
            }
        }

    suspend fun downloadImage(url: String): ByteArray = withContext(Dispatchers.IO) {
        val u = originalUrl(url)
        val request = Request.Builder().url(u).get().build()
        client.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()
            .newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("下载失败 HTTP ${resp.code}")
                resp.body?.bytes() ?: error("空响应")
            }
    }

    // ─── 图片/文字提取 ───

    private fun originalUrl(url: String): String = if (url.isEmpty()) url else url.substringBefore("?")

    private fun extractImages(item: JSONObject): List<Pair<String, String>> {
        val found = ArrayList<Pair<String, String>>() // (path, url)
        fun walk(node: Any?, path: String) {
            when (node) {
                is JSONObject -> node.keys().forEach { k -> walk(node.opt(k), "$path.$k") }
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), "$path[$i]")
                is String -> {
                    val low = node.lowercase()
                    if (node.startsWith("http") &&
                        listOf(".jpg", ".png", ".jpeg", "imageview", "/study/").any { low.contains(it) }
                    ) found.add(path to node)
                }
            }
        }
        walk(item, "")

        fun labelOf(path: String): String {
            val p = path.lowercase()
            return when {
                p.contains("smallimgsrc") -> "缩略"
                p.contains("imgsrc") -> "原图"
                p.contains("jyfs") || p.contains("zyb") || p.contains("sourcetid") -> "搜题"
                p.contains("answer") -> "答案"
                p.contains("analysis") -> "解析"
                p.contains("question") -> "题目"
                else -> "图"
            }
        }

        val prio = mapOf("原图" to 0, "题目" to 1, "搜题" to 2, "答案" to 3, "解析" to 4, "图" to 5, "缩略" to 9)
        val best = LinkedHashMap<String, Pair<String, String>>() // base -> (label, url)
        for ((path, url) in found) {
            val base = originalUrl(url)
            val lab = labelOf(path)
            if (lab == "缩略") continue
            val cur = best[base]
            if (cur == null || (prio[lab] ?: 9) < (prio[cur.first] ?: 9)) best[base] = lab to url
        }
        return best.values.sortedBy { prio[it.first] ?: 9 }
    }

    private fun extractText(detail: JSONObject): ErrText {
        fun grab(vararg keys: String): String {
            var node: Any? = detail
            for (k in keys) node = (node as? JSONObject)?.opt(k)
            return (node as? String) ?: ""
        }
        val q = grab("question", "content")
        var a = grab("answer", "content")
        val ae = grab("answer", "contentExtra")
        val an = grab("analysis", "subjectAnalysis", "content").ifBlank { grab("analysis", "content") }
        if (ae.isNotBlank() && !a.contains(ae)) a = (a + "\n" + ae).trim()
        return ErrText(
            questionHtml = q, questionText = htmlToText(q),
            answerHtml = a, answerText = htmlToText(a),
            analysisHtml = an, analysisText = htmlToText(an),
        )
    }

    /** HTML+LaTeX -> 纯文本，保留 $...$ 公式。 */
    fun htmlToText(input: String): String {
        if (input.isBlank()) return ""
        var s = input
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</(p|div|tr|h[1-6])>"), "\n")
        s = s.replace(Regex("(?i)<li[^>]*>"), "\n• ")
        s = s.replace(Regex("(?i)<sub>(.*?)</sub>"), "_$1")
        s = s.replace(Regex("(?i)<sup>(.*?)</sup>"), "^$1")
        s = s.replace(Regex("<[^>]+>"), "")
        s = unescapeHtml(s)
        s = s.replace(Regex("\n[ \t]*\n[ \t]*\n+"), "\n\n")
        return s.trim()
    }

    private fun unescapeHtml(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&nbsp;", " ").replace("&amp;", "&")
}
