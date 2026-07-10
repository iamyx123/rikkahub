package me.rerere.rikkahub.data.miaomiao

import me.rerere.rikkahub.data.datastore.MiaomiaoImportMode
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.zyb.ZybClient

/**
 * 喵喵机错题本高层服务：登录 -> 选科目 -> 取最新错题 -> 下载图片 + 组织文字。
 * 供聊天页「喵喵机错题」按钮、错题浏览页与设置页调用。
 */
class MiaomiaoService(
    private val zyb: ZybClient,
    private val settingsStore: SettingsStore,
) {
    val client: ZybClient get() = zyb

    data class ErrbookImport(
        val subjectName: String,
        /** 下载好的原图字节（原图/题目/搜题…全部保留） */
        val images: List<ByteArray>,
        /** 题干（可选含解析）的 Markdown/LaTeX 文本；无文字为 null */
        val questionText: String?,
        val hasText: Boolean,
    )

    /** 用当前配置登录（幂等：每次刷新会话密钥）。 */
    suspend fun ensureLogin(): Result<Unit> {
        val cfg = settingsStore.settingsFlow.value.displaySetting.miaomiaoErrorbook
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

    /** 下载某道错题的全部图片并组织文字，得到可导入的数据。 */
    suspend fun buildImport(subjectName: String, item: ZybClient.ErrItem): ErrbookImport {
        val includeAnalysis = settingsStore.settingsFlow.value.displaySetting.miaomiaoErrorbook.includeAnalysis
        val images = item.images.mapNotNull { (_, url) ->
            runCatching { zyb.downloadImage(url) }.getOrNull()
        }
        val text = buildText(subjectName, item, includeAnalysis)
        return ErrbookImport(subjectName, images, text, item.text.hasText)
    }

    /** 拉取「最新一题」并下载其所有图片、组织文字。 */
    suspend fun fetchLatest(): Result<ErrbookImport> {
        val cfg = settingsStore.settingsFlow.value.displaySetting.miaomiaoErrorbook
        ensureLogin().getOrElse { return Result.failure(it) }

        val groups = zyb.getErrGroups().getOrElse { return Result.failure(it) }
        if (groups.isEmpty()) return Result.failure(IllegalStateException("错题本里还没有科目/错题"))

        val (group, item) = if (cfg.lastGroupId > 0) {
            val g = groups.find { it.groupId == cfg.lastGroupId } ?: groups.first()
            val it0 = zyb.getErrList(g.groupId, 1, 1).getOrElse { return Result.failure(it) }
                .second.firstOrNull() ?: return Result.failure(IllegalStateException("「${g.name}」暂无错题"))
            g to it0
        } else {
            // 未指定科目：扫描各科目最新一题，取更新时间最晚者（即最近拍摄的错题）
            var bestGroup: ZybClient.ErrGroup? = null
            var bestItem: ZybClient.ErrItem? = null
            for (g in groups) {
                val it0 = zyb.getErrList(g.groupId, 1, 1).getOrNull()?.second?.firstOrNull() ?: continue
                if (bestItem == null || it0.create > (bestItem!!.create)) {
                    bestItem = it0; bestGroup = g
                }
            }
            val g = bestGroup ?: return Result.failure(IllegalStateException("错题本里还没有错题"))
            g to (bestItem ?: return Result.failure(IllegalStateException("错题本里还没有错题")))
        }

        return Result.success(buildImport(group.name, item))
    }

    private fun buildText(subject: String, item: ZybClient.ErrItem, includeAnalysis: Boolean): String? {
        val q = item.text.questionText.trim()
        if (q.isBlank() && (!includeAnalysis || item.text.analysisText.isBlank())) return null
        val sb = StringBuilder()
        sb.append("【错题·").append(subject).append("】\n")
        if (q.isNotBlank()) sb.append(q)
        if (includeAnalysis && item.text.analysisText.isNotBlank()) {
            sb.append("\n\n【解析】\n").append(item.text.analysisText.trim())
        }
        return sb.toString()
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
