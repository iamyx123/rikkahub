package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.util.InstantSerializer
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * 阅读书签：锚定到某条消息节点（MessageNode.id），用于在长对话里快速定位关键点。
 *
 * 之所以锚定 [nodeId] 而非列表下标，是为了让书签位置「绝对」：
 * - 生成新回答时旧节点 id 不变，书签不漂移
 * - 编辑/重新生成被书签的回答时节点 id 仍保留，书签自动跟随
 * - 节点被删除时，UI 层按 id 找不到即视为失效（自动清理）
 *
 * [scrollOffset] 仅作为跳转时的像素微调提示（同一会话内精确，跨字号/重启可能轻微偏移），
 * 真正的durable锚点是 [nodeId]。
 */
@Serializable
data class MessageBookmark(
    val id: Uuid = Uuid.random(),
    val nodeId: Uuid,
    val label: String = "",
    val scrollOffset: Int = 0,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
)
