package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessage
import me.rerere.highlight.Highlighter
import me.rerere.highlight.LocalHighlighter
import me.rerere.rikkahub.data.paperang.PaperangPrinter
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.BitmapComposer
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import me.rerere.rikkahub.utils.getActivity
import org.koin.compose.koinInject

/**
 * 返回一个「把 AI 回复渲染成图片并一键紧凑打印」的函数。
 *
 * 复用应用的 [MarkdownBlock]（原生渲染 LaTeX 公式），通过 [BitmapComposer] 离屏渲染为位图，
 * 再用 [PaperangPrinter] 黑白打印（文字/公式黑白最清晰）。
 */
@Composable
fun rememberMessagePrinter(): (UIMessage) -> Unit {
    val context = LocalContext.current
    val density = LocalDensity.current
    val settings = LocalSettings.current
    val toaster = LocalToaster.current
    val printer: PaperangPrinter = koinInject()
    val highlighter: Highlighter = koinInject()
    val scope = rememberCoroutineScope()

    return remember(settings) {
        fn@{ message: UIMessage ->
            val cfg = settings.displaySetting.paperangPrinter
            if (printer.status.value.state != PaperangPrinter.ConnState.CONNECTED) {
                toaster.show(message = "打印机未连接，请长按「喵喵机错题」连接打印机", type = ToastType.Warning)
                return@fn
            }
            val activity = context.getActivity()
            if (activity == null) {
                toaster.show(message = "无法获取窗口，打印失败", type = ToastType.Error)
                return@fn
            }
            val text = message.toText().trim()
            if (text.isBlank()) {
                toaster.show(message = "该回复没有可打印的文字", type = ToastType.Warning)
                return@fn
            }
            scope.launch {
                toaster.show("正在渲染并打印…")
                val composer = BitmapComposer(scope)
                runCatching {
                    val bitmap = composer.composableToBitmap(
                        activity = activity,
                        width = 384.dp,
                        screenDensity = density,
                    ) {
                        RikkahubTheme {
                            CompositionLocalProvider(
                                LocalHighlighter provides highlighter,
                                LocalSettings provides settings,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(384.dp)
                                        .background(Color.White)
                                        .padding(12.dp)
                                ) {
                                    MarkdownBlock(content = text)
                                }
                            }
                        }
                    }
                    val result = printer.printBitmap(
                        bitmap = bitmap,
                        grayscale = false,
                        density = cfg.density,
                        feedAfter = cfg.feedAfter,
                    )
                    if (!bitmap.isRecycled) bitmap.recycle()
                    result.getOrThrow()
                }.onSuccess {
                    toaster.dismissAll()
                    toaster.show(message = "已发送到打印机", type = ToastType.Success)
                }.onFailure {
                    toaster.show(message = "打印失败: ${it.message ?: "未知错误"}", type = ToastType.Error)
                }
            }
        }
    }
}
