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
 * 登录（手机号+密码）-> 拉取各科目错题 -> 官方题干/答案/解析（文字 + 清晰图）+ 自拍原图。
 *
 * 官方内容藏在哪（关键，容易全部漏掉）：
 * - `detail.question.content` / `detail.answer.content` / `contentExtra` 是 **HTML 字符串**，
 *   官方清晰图是里面的 `<img src="https://img.cdnjtzy.com/...">`，不是裸 URL 字段。
 *   只递归找「以 http 开头的字符串叶子」永远只能拿到用户自己拍的照片。
 * - `detail.analysis` 下没有 `content`/`subjectAnalysis`，只有结构化的 `aiAnalysis`
 *   （`answer` / `general_idea` / `step[]{main_point, analyze, equation}`）。
 * - `isQuery == false` 的题作业帮当时没匹配到题库，云端只有照片，任何接口都拿不到官方内容。
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

        /** HTML 里的 `<img src="...">`：官方题干/答案图都藏在这里。 */
        private val IMG_TAG_RE =
            Regex("""<img\b[^>]*?\bsrc\s*=\s*["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)

        /** 只有这些域名是作业帮官方题库图；miaobao-pub 是用户自己上传/裁剪的照片。 */
        private val OFFICIAL_IMG_HOSTS =
            listOf("cdnjtzy.com", "zuoyebang.com", "zybang.com", "suanshubang.com")

        /** 正文里的图片占位符，判断「是否有实质文字」时要先剔除。 */
        private val PLACEHOLDER_RE = Regex("""\[图\d*(?:\([^)]*\))?]""")

        // 图片标签（与 zyb_errorbook.py 一致）
        const val LABEL_QUESTION = "题干"   // 官方题库清晰图
        const val LABEL_ANSWER = "答案"     // 官方答案图
        const val LABEL_ANALYSIS = "解析"   // 官方解析图
        const val LABEL_SEARCH = "搜题"     // 搜题来源图
        const val LABEL_CROP = "裁剪"       // App 对自拍图的裁剪/增强
        const val LABEL_PHOTO = "自拍"      // 用户自己拍的原始照片
        const val LABEL_OTHER = "图"
        private const val LABEL_THUMB = "缩略"

        /** 官方内容优先于用户照片；导入/显示都按这个顺序。 */
        private val LABEL_PRIORITY = mapOf(
            LABEL_QUESTION to 0, LABEL_ANSWER to 1, LABEL_ANALYSIS to 2, LABEL_SEARCH to 3,
            LABEL_CROP to 4, LABEL_PHOTO to 5, LABEL_OTHER to 6, LABEL_THUMB to 9,
        )

        /** 是否为作业帮官方题库图（而非用户自己拍/裁的图）。 */
        fun isOfficialImage(url: String): Boolean {
            val low = url.lowercase()
            return OFFICIAL_IMG_HOSTS.any { low.contains(it) }
        }

        /** 去掉 `?imageView2/...` 缩放参数，取原图。 */
        fun originalUrl(url: String): String = if (url.isEmpty()) url else url.substringBefore("?")

        /** 服务端布尔可能是 true / 1 / "1"，统一判真。 */
        private fun truthy(v: Any?): Boolean = when (v) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.equals("true", true) || v == "1"
            else -> false
        }

        /** 兼容旧配置里的历史标签名（"题目"/"原图" 是重命名前的写法）。 */
        fun normalizeLabel(label: String): String = when (label) {
            "题目" -> LABEL_QUESTION
            "原图" -> LABEL_PHOTO
            else -> label
        }
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

    /** 一张图片：标签（题干/答案/解析/裁剪/自拍…）+ URL。 */
    data class ErrImage(val label: String, val url: String) {
        val isOfficial: Boolean get() = isOfficialImage(url)
    }

    /**
     * 题干/答案/解析中的一节。
     *
     * @param html 作业帮原始 HTML（含 LaTeX，页面渲染用）
     * @param text 去标签纯文本，保留 `$...$` 公式；图片位置留 `[图N]` 占位符（喂 AI 用）
     * @param images 本节 HTML 里按出现顺序的图片 URL，`images[N-1]` 对应 `[图N]`
     */
    data class ErrSection(
        val html: String = "",
        val text: String = "",
        val images: List<String> = emptyList(),
    ) {
        /** 去掉 `[图N]` 占位符后是否还有实质文字。 */
        val hasRealText: Boolean get() = PLACEHOLDER_RE.replace(text, "").isNotBlank()
    }

    data class ErrText(
        val question: ErrSection = ErrSection(),
        val answer: ErrSection = ErrSection(),
        val analysis: ErrSection = ErrSection(),
    ) {
        val questionHtml: String get() = question.html
        val questionText: String get() = question.text
        val answerHtml: String get() = answer.html
        val answerText: String get() = answer.text
        val analysisHtml: String get() = analysis.html
        val analysisText: String get() = analysis.text

        /** 只有 `[图N]` 占位符不算「有文字」。 */
        val hasText: Boolean
            get() = question.hasRealText || answer.hasRealText || analysis.hasRealText
    }

    data class ErrItem(
        val tid: String,
        /** studyContentId，数字 ID，适合做文件名/去重键 */
        val cid: String,
        val subject: Int,
        val degree: String,
        val reason: String,
        val create: String,
        /** 全部图片，官方图优先排序 */
        val images: List<ErrImage>,
        val text: ErrText,
        /** 作业帮当时是否识别出了这道题（题库命中） */
        val isQuery: Boolean,
    ) {
        /** 是否真拿到了官方内容：有实质官方文字，或有官方 CDN 的题库图。 */
        val hasOfficial: Boolean get() = text.hasText || images.any { it.isOfficial }

        fun imagesOf(labels: Set<String>): List<ErrImage> = images.filter { it.label in labels }
    }

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
            val text = resp.body.string()
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
                    list.optJSONObject(i)?.let { parseItem(it) }
                }
                total to items
            }
        }

    /** 把列表接口里的一条记录解析成 [ErrItem]（官方内容全在 `detail` 里，无需额外详情接口）。 */
    fun parseItem(raw: JSONObject): ErrItem {
        val detail = raw.optJSONObject("detail") ?: JSONObject()
        return ErrItem(
            tid = detail.optString("tid", raw.optString("tid", raw.optString("studyContentId"))),
            cid = raw.optString("studyContentId"),
            subject = detail.optInt("subject", 0),
            degree = raw.optString("degreeName", ""),
            reason = raw.optString("reasonName", ""),
            create = raw.optString("updateTime", raw.optString("sysDate", "")),
            images = extractImages(raw),
            text = extractText(detail),
            isQuery = truthy(raw.opt("isQuery")),
        )
    }

    /** 下图：先取原图（去掉缩放参数），失败回落原始 URL，各自重试一次。 */
    suspend fun downloadImage(url: String): ByteArray = withContext(Dispatchers.IO) {
        val http = client.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()
        val candidates = listOf(originalUrl(url), url).distinct()
        var last: Throwable? = null
        for (u in candidates) {
            repeat(2) {
                val bytes = runCatching {
                    http.newCall(Request.Builder().url(u).get().build()).execute().use { resp ->
                        if (!resp.isSuccessful) error("下载失败 HTTP ${resp.code}")
                        resp.body.bytes().takeIf { b -> b.isNotEmpty() } ?: error("空响应")
                    }
                }.onFailure { e -> last = e }.getOrNull()
                if (bytes != null) return@withContext bytes
            }
        }
        throw last ?: IllegalStateException("下载失败: $url")
    }

    // ─── 图片/文字提取 ───

    /**
     * 收集一道错题里所有图片 URL，按来源打标签并去重（按原图 URL 去重）。
     *
     * 关键：官方题干/答案图是 HTML 字符串里的 `<img src>`，不是裸 URL 叶子，必须用正则抽。
     */
    private fun extractImages(item: JSONObject): List<ErrImage> {
        // (出现顺序, JSON 路径, url)
        val found = ArrayList<Triple<Int, String, String>>()
        fun walk(node: Any?, path: String) {
            when (node) {
                is JSONObject -> node.keys().forEach { k -> walk(node.opt(k), "$path.$k") }
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), "$path[$i]")
                is String -> {
                    if (node.isEmpty()) return
                    val low = node.lowercase()
                    if (node.startsWith("http") &&
                        listOf(".jpg", ".png", ".jpeg", "imageview", "/study/").any { low.contains(it) }
                    ) {
                        found.add(Triple(found.size, path, node))
                    } else if (low.contains("<img")) {
                        // 官方清晰图在这里
                        IMG_TAG_RE.findAll(node).forEach { m ->
                            found.add(Triple(found.size, path, unescapeHtml(m.groupValues[1])))
                        }
                    }
                }
            }
        }
        walk(item, "")

        fun labelOf(path: String, url: String): String {
            val p = path.lowercase()
            return when {
                p.contains("smallimgsrc") -> LABEL_THUMB
                p.contains("imgsrc") -> LABEL_PHOTO
                // HTML 里但不是官方 CDN = App 对自拍图做的裁剪/增强，不是官方内容
                !isOfficialImage(url) -> LABEL_CROP
                p.contains("question") -> LABEL_QUESTION
                p.contains("answer") -> LABEL_ANSWER
                p.contains("analysis") -> LABEL_ANALYSIS
                p.contains("jyfs") || p.contains("sourcetid") -> LABEL_SEARCH
                else -> LABEL_OTHER
            }
        }

        // 同一张图可能在多处出现，保留优先级最高（最「官方」）的标签
        val best = LinkedHashMap<String, Triple<String, String, Int>>() // base -> (label, url, order)
        for ((order, path, url) in found) {
            val base = originalUrl(url)
            val label = labelOf(path, url)
            if (label == LABEL_THUMB) continue // 缩略图和原图同源，跳过
            val cur = best[base]
            val prio = LABEL_PRIORITY[label] ?: 9
            if (cur == null || prio < (LABEL_PRIORITY[cur.first] ?: 9)) {
                best[base] = Triple(label, url, order)
            }
        }
        return best.values
            .sortedWith(compareBy({ LABEL_PRIORITY[it.first] ?: 9 }, { it.third }))
            .map { (label, url, _) -> ErrImage(label, url) }
    }

    private fun extractText(detail: JSONObject): ErrText {
        fun grab(root: JSONObject?, vararg keys: String): String {
            var node: Any? = root
            for (k in keys) node = (node as? JSONObject)?.opt(k)
            return (node as? String) ?: ""
        }

        val q = grab(detail, "question", "content")

        var a = grab(detail, "answer", "content")
        val ae = grab(detail, "answer", "contentExtra")
        // contentExtra 通常是 content 的超集（多个「【答案】」抬头），取更全的那个
        if (ae.isNotBlank()) {
            a = when {
                a.isBlank() || a in ae || ae.length > a.length -> ae
                ae in a -> a
                else -> (a + "\n" + ae).trim()
            }
        }

        val analysisNode = detail.optJSONObject("analysis")
        val analysisHtml = grab(analysisNode, "subjectAnalysis", "content")
            .ifBlank { grab(analysisNode, "content") }
        // detail.analysis 通常只有结构化的 aiAnalysis，没有 content
        val aiText = renderAiAnalysis(analysisNode?.optJSONObject("aiAnalysis"))

        fun section(html: String): ErrSection {
            val images = ArrayList<String>()
            return ErrSection(html = html, text = htmlToText(html, images), images = images)
        }

        val analysis = section(analysisHtml).let { s ->
            if (aiText.isBlank()) s
            else s.copy(text = if (s.text.isBlank()) aiText else (s.text + "\n\n" + aiText).trim())
        }
        return ErrText(question = section(q), answer = section(a), analysis = analysis)
    }

    /**
     * `detail.analysis.aiAnalysis` 是结构化对象（不是 content 字符串），渲染成可读文本：
     * 【答案】/【总体思路】/【步骤N】分析 + 推导。
     */
    private fun renderAiAnalysis(ai: JSONObject?): String {
        if (ai == null) return ""
        val parts = ArrayList<String>()
        ai.optString("answer").takeIf { it.isNotBlank() }?.let { parts += "【答案】\n" + htmlToText(it) }
        ai.optString("general_idea").takeIf { it.isNotBlank() }
            ?.let { parts += "【总体思路】\n" + htmlToText(it) }
        val steps = ai.optJSONArray("step") ?: JSONArray()
        for (i in 0 until steps.length()) {
            val st = steps.optJSONObject(i) ?: continue
            val block = ArrayList<String>()
            block += ("【步骤${i + 1}】" + htmlToText(st.optString("main_point"))).trimEnd()
            st.optString("analyze").takeIf { it.isNotBlank() }?.let { block += "分析：" + htmlToText(it) }
            st.optString("equation").takeIf { it.isNotBlank() }?.let { block += "推导：" + htmlToText(it) }
            parts += block.joinToString("\n")
        }
        return parts.filter { it.isNotBlank() }.joinToString("\n\n")
    }

    /**
     * HTML+LaTeX -> 纯文本，保留 `$...$` 公式。
     *
     * `<img>` 不丢弃，而是替换成 `[图N]` 占位符，URL 按出现顺序追加到 [images]，
     * 这样图文顺序不会错乱。
     */
    fun htmlToText(input: String, images: MutableList<String>? = null): String {
        if (input.isBlank()) return ""
        var s = input
        // 先抽图（占位），再拆标签
        s = IMG_TAG_RE.replace(s) { m ->
            if (images == null) "\n[图]\n" else {
                images.add(unescapeHtml(m.groupValues[1]))
                "\n[图${images.size}]\n"
            }
        }
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</(p|div|tr|h[1-6])>"), "\n")
        s = s.replace(Regex("(?i)<li[^>]*>"), "\n• ")
        s = s.replace(Regex("(?i)<sub>(.*?)</sub>"), "_$1")   // 化学下标 CO_2
        s = s.replace(Regex("(?i)<sup>(.*?)</sup>"), "^$1")   // 上标
        s = s.replace(Regex("<[^>]+>"), "")                   // 去掉其余标签
        s = unescapeHtml(s)
        s = s.replace(' ', ' ')                          // &nbsp;
        s = s.replace(Regex("[ \t]+"), " ")
        s = s.replace(Regex(" *\n *"), "\n")
        s = s.replace(Regex("\n{3,}"), "\n\n")
        return s.trim()
    }

    private fun unescapeHtml(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&nbsp;", " ").replace("&amp;", "&")
}
