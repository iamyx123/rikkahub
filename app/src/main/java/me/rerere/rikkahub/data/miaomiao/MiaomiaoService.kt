package me.rerere.rikkahub.data.miaomiao

import me.rerere.rikkahub.data.datastore.MiaomiaoErrorbookConfig
import me.rerere.rikkahub.data.datastore.MiaomiaoImportMode
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.zyb.ZybClient

/**
 * 喵喵机错题本高层服务：登录 -> 选科目 -> 取错题 -> 按设置筛选并下载图片 + 组织文字。
 * 供聊天页「喵喵机错题」按钮、错题浏览页与设置页调用。
 */
class MiaomiaoService(
    private val zyb: ZybClient,
    private val settingsStore: SettingsStore,
) {
    val client: ZybClient get() = zyb

    data class ErrbookImport(
        val subjectName: String,
        /** 按设置筛选后下载好的原图字节 */
        val images: List<ByteArray>,
        /** 题干（可选含答案/解析）的 Markdown/LaTeX 文本；无文字为 null */
        val questionText: String?,
        val hasText: Boolean,
        /** 是否真拿到了官方内容（官方文字或官方 CDN 题库图） */
        val hasOfficial: Boolean,
    )

    private val config: MiaomiaoErrorbookConfig
        get() = settingsStore.settingsFlow.value.displaySetting.miaomiaoErrorbook

    /** 用当前配置登录（幂等：每次刷新会话密钥）。 */
    suspend fun ensureLogin(): Result<Unit> {
        val cfg = config
        if (cfg.phone.isBlank() || cfg.password.isBlank()) {
            return Result.failure(IllegalStateException("请先在 设置 → 喵喵机 里登录作业帮账号"))
        }
        return zyb.login(cfg.phone, cfg.password)
    }

    /** 登录后拉取全部错题科目（分类）。供浏览页顶部分类使用。 */
    suspend fun listGroups(): Result<List<ZybClient.ErrGroup>> {
        ensureLogin().getOrElse { return Result.failure(it) }
        return zyb.getErrGroups()
    }

    /** 登录后按科目分页拉取错题列表，返回 (总数, 当前页列表)。 */
    suspend fun listItems(groupId: Int, page: Int, size: Int): Result<Pair<Int, List<ZybClient.ErrItem>>> {
        ensureLogin().getOrElse { return Result.failure(it) }
        return zyb.getErrList(groupId, page, size)
    }

    /**
     * 按设置里勾选的类别筛选图片。
     *
     * - 题干 = 官方题库清晰图
     * - 答案/解析/搜题 = 官方答案与解析图
     * - 裁剪/自拍 = 自己拍的照片（含 App 裁剪增强）
     */
    fun selectImages(item: ZybClient.ErrItem, cfg: MiaomiaoErrorbookConfig = config): List<ZybClient.ErrImage> {
        val labels = buildSet {
            if (cfg.includeOfficialQuestionImage) add(ZybClient.LABEL_QUESTION)
            if (cfg.includeOfficialAnswerImage) {
                add(ZybClient.LABEL_ANSWER); add(ZybClient.LABEL_ANALYSIS); add(ZybClient.LABEL_SEARCH)
            }
            if (cfg.includePhotoImage) {
                add(ZybClient.LABEL_PHOTO); add(ZybClient.LABEL_CROP); add(ZybClient.LABEL_OTHER)
            }
        }
        val picked = item.imagesOf(labels)
        // 一张都没勾中时，若允许照片就退回全部图片，避免「什么都导不出来」
        if (picked.isEmpty() && labels.isNotEmpty()) return item.images
        val prefer = ZybClient.normalizeLabel(cfg.preferImageLabel)
        return picked.sortedByDescending { it.label == prefer }
    }

    /** 下载某道错题按设置筛选后的图片并组织文字，得到可导入的数据。 */
    suspend fun buildImport(subjectName: String, item: ZybClient.ErrItem): ErrbookImport {
        val cfg = config
        val images = selectImages(item, cfg).mapNotNull { img ->
            runCatching { zyb.downloadImage(img.url) }.getOrNull()
        }
        val text = buildText(subjectName, item, cfg)
        return ErrbookImport(
            subjectName = subjectName,
            images = images,
            questionText = text,
            hasText = text != null,
            hasOfficial = item.hasOfficial,
        )
    }

    /** 拉取「最新一题」并按设置下载图片、组织文字。 */
    suspend fun fetchLatest(): Result<ErrbookImport> {
        val cfg = config
        ensureLogin().getOrElse { return Result.failure(it) }

        val groups = zyb.getErrGroups().getOrElse { return Result.failure(it) }
        if (groups.isEmpty()) return Result.failure(IllegalStateException("错题本里还没有科目/错题"))

        val (group, item) = if (cfg.lastGroupId > 0) {
            val g = groups.find { it.groupId == cfg.lastGroupId } ?: groups.first()
            val picked = pickLatest(g, cfg)
                ?: return Result.failure(IllegalStateException(noItemMessage(g.name, cfg)))
            g to picked
        } else {
            // 未指定科目：扫描各科目最新一题，取更新时间最晚者（即最近拍摄的错题）
            var best: Pair<ZybClient.ErrGroup, ZybClient.ErrItem>? = null
            for (g in groups) {
                val candidate = pickLatest(g, cfg) ?: continue
                val cur = best
                if (cur == null || candidate.create > cur.second.create) best = g to candidate
            }
            best ?: return Result.failure(IllegalStateException(noItemMessage(null, cfg)))
        }

        return Result.success(buildImport(group.name, item))
    }

    /**
     * 取某科目最新一题。开启「跳过无官方内容」时，往后多翻几题找第一个有官方内容的
     * （isQuery=false 的题云端只有照片，官方内容任何接口都拿不到）。
     */
    private suspend fun pickLatest(group: ZybClient.ErrGroup, cfg: MiaomiaoErrorbookConfig): ZybClient.ErrItem? {
        val size = if (cfg.skipWithoutOfficial) 10 else 1
        val list = zyb.getErrList(group.groupId, 1, size).getOrNull()?.second ?: return null
        if (!cfg.skipWithoutOfficial) return list.firstOrNull()
        return list.firstOrNull { it.hasOfficial }
    }

    private fun noItemMessage(groupName: String?, cfg: MiaomiaoErrorbookConfig): String {
        val where = if (groupName != null) "「$groupName」" else "错题本里"
        return if (cfg.skipWithoutOfficial) {
            "${where}最近的错题都没有官方内容（作业帮未识别出题库），可在设置里关闭「只导入有官方内容的题」"
        } else {
            "${where}暂无错题"
        }
    }

    /**
     * 组织导入文字：按设置拼「题干 / 答案 / 解析」。
     *
     * 正文里图片位置的 `[图N]` 占位符会替换成 `[图片N]`，提示 AI 图片已作为附件一起发送。
     */
    private fun buildText(subject: String, item: ZybClient.ErrItem, cfg: MiaomiaoErrorbookConfig): String? {
        val sections = buildList {
            if (cfg.includeQuestionText) add("题干" to item.text.question)
            if (cfg.includeAnswerText) add("答案" to item.text.answer)
            if (cfg.includeAnalysis) add("解析" to item.text.analysis)
        }.filter { (_, sec) -> sec.hasRealText }
        if (sections.isEmpty()) return null

        val sb = StringBuilder()
        sb.append("【错题·").append(subject).append("】")
        if (!item.isQuery) sb.append("（作业帮未识别出题库，以下仅为已有内容）")
        sb.append('\n')
        sections.forEachIndexed { i, (title, sec) ->
            if (i > 0) sb.append('\n')
            // 只有题干一节时不加小标题，保持和以前一样简洁
            if (sections.size > 1) sb.append('【').append(title).append("】\n")
            sb.append(normalizePlaceholders(sec.text.trim())).append('\n')
        }
        return sb.toString().trim()
    }

    /** `[图N]` -> `[图片N]`：与作为附件发送的图片对应，避免 AI 以为图片缺失。 */
    private fun normalizePlaceholders(text: String): String =
        text.replace(Regex("""\[图(\d*)]""")) { m ->
            val n = m.groupValues[1]
            if (n.isBlank()) "[图片]" else "[图片$n]"
        }

    /** 判断某模式下是否应导入文字（图片缺失时兜底导入文字）。 */
    fun shouldAddText(mode: MiaomiaoImportMode, hasImages: Boolean, hasText: Boolean): Boolean = when (mode) {
        MiaomiaoImportMode.TEXT_ONLY -> hasText
        MiaomiaoImportMode.BOTH -> hasText
        MiaomiaoImportMode.IMAGE_ONLY -> hasText && !hasImages
    }

    fun shouldAddImages(mode: MiaomiaoImportMode, hasImages: Boolean): Boolean =
        mode != MiaomiaoImportMode.TEXT_ONLY && hasImages
}
