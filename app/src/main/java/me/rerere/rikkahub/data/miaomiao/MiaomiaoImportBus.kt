package me.rerere.rikkahub.data.miaomiao

import android.net.Uri
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import me.rerere.ai.ui.UIMessagePart

/**
 * 错题浏览页 -> 聊天输入框 的一次性投递通道。
 *
 * 浏览页（多选导入）与聊天页是两个不同的导航目的地，二者不共享同一个 ChatVM/输入状态；
 * 用 Channel（带缓冲）而非 SharedFlow，保证即使聊天页在导航期间被销毁、
 * 待返回后重新收集时也能拿到导入结果，不会丢失。
 */
class MiaomiaoImportBus {
    data class Payload(
        /** 已落地到本地的图片文件 uri（可直接加入输入框） */
        val images: List<Uri>,
        /** 每题一个的 TXT 文档附件（避免文字污染输入框） */
        val documents: List<UIMessagePart.Document>,
        /** 导入题数，用于提示 */
        val itemCount: Int,
    )

    private val channel = Channel<Payload>(Channel.BUFFERED)
    val imports = channel.receiveAsFlow()

    suspend fun send(payload: Payload) {
        channel.send(payload)
    }
}
