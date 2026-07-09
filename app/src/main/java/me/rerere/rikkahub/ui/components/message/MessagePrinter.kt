package me.rerere.rikkahub.ui.components.message

import android.app.Activity
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation3.runtime.NavKey
import com.dokar.sonner.ToastType
import com.dokar.sonner.rememberToasterState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateListOf
import me.rerere.ai.ui.UIMessage
import me.rerere.highlight.Highlighter
import me.rerere.highlight.LocalHighlighter
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.paperang.PaperangPrinter
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.BitmapComposer
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import me.rerere.rikkahub.utils.getActivity
import org.koin.compose.koinInject

// 字号=100% 时的渲染宽度（dp）。做法：固定字体渲染，字号倍率越大渲染越窄，最后整幅位图缩放到纸宽，
// 于是文字/标题/图片/代码/公式/分割线全部等比缩放，不依赖各元素自己的字号传递。
private const val BASE_RENDER_WIDTH_DP = 384f

private fun renderWidthDp(scale: Float): Float = (BASE_RENDER_WIDTH_DP / scale.coerceIn(0.5f, 2.0f)).coerceIn(180f, 900f)

/**
 * 打印内容渲染体：固定字体样式渲染，最终由整幅位图缩放决定纸上大小（保证所见即所得、整体等比）。
 */
@Composable
fun PrintableMessageContent(
    text: String,
    highlighter: Highlighter,
    settings: Settings,
) {
    val navBackStack = remember { mutableStateListOf<NavKey>() }
    val navigator = remember { Navigator(navBackStack) }
    val toasterState = rememberToasterState()
    // 固定字号倍率、代码自动换行，避免代码块过宽被裁
    val printSettings = remember(settings) {
        settings.copy(
            displaySetting = settings.displaySetting.copy(
                fontSizeRatio = 1.0f,
                codeBlockAutoWrap = true,
            )
        )
    }
    RikkahubTheme {
        CompositionLocalProvider(
            LocalHighlighter provides highlighter,
            LocalSettings provides printSettings,
            LocalNavController provides navigator,
            LocalToaster provides toasterState,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(10.dp)
            ) {
                MarkdownBlock(
                    content = text,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = Color.Black,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    ),
                )
            }
        }
    }
}

/** 离屏把消息按 scale 渲染为位图（scale 越大 -> 渲染越窄 -> 缩放到纸宽后整体越大）。 */
suspend fun renderMessageBitmap(
    scope: CoroutineScope,
    activity: Activity,
    density: Density,
    highlighter: Highlighter,
    settings: Settings,
    text: String,
    scale: Float,
): Bitmap {
    val composer = BitmapComposer(scope)
    return composer.composableToBitmap(
        activity = activity,
        width = renderWidthDp(scale).dp,
        screenDensity = density,
    ) {
        PrintableMessageContent(text = text, highlighter = highlighter, settings = settings)
    }
}

/** 打印预览：显示真实渲染出的位图（Image），所见即所得；scale 变化时重渲染一次（调用方在松手后才改 scale）。 */
@Composable
fun PrintPreview(text: String, scale: Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val settings = LocalSettings.current
    val highlighter: Highlighter = koinInject()
    val scope = rememberCoroutineScope()
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var rendering by remember { mutableStateOf(false) }

    LaunchedEffect(text, scale) {
        val activity = context.getActivity() ?: return@LaunchedEffect
        rendering = true
        bmp = runCatching { renderMessageBitmap(scope, activity, density, highlighter, settings, text, scale) }.getOrNull()
        rendering = false
    }

    Box(
        modifier = modifier.verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        val b = bmp
        when {
            b != null -> Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
            rendering -> CircularProgressIndicator(modifier = Modifier.padding(24.dp))
            else -> Text("渲染失败", modifier = Modifier.padding(24.dp), color = Color.Gray)
        }
    }
}

/** 短按「打印这条回复」：按默认字号直接渲染并打印。 */
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
            if (!ensurePrinterReady(printer, toaster)) return@fn
            val activity = context.getActivity() ?: run {
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
                runCatching {
                    val bitmap = renderMessageBitmap(scope, activity, density, highlighter, settings, text, cfg.printFontScale)
                    val result = printer.printBitmap(bitmap, grayscale = false, density = cfg.density, feedAfter = cfg.feedAfter)
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

/** 长按「打印这条回复」：预览窗口实时调字体后打印；结果保存为默认字号。 */
@Composable
fun MessagePrintPreviewDialog(
    message: UIMessage,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val settings = LocalSettings.current
    val toaster = LocalToaster.current
    val printer: PaperangPrinter = koinInject()
    val highlighter: Highlighter = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val scope = rememberCoroutineScope()
    val text = remember(message) { message.toText().trim() }

    val initialScale = settings.displaySetting.paperangPrinter.printFontScale
    var sliderValue by remember { mutableFloatStateOf(initialScale) }
    var previewScale by remember { mutableFloatStateOf(initialScale) }
    var printing by remember { mutableStateOf(false) }
    val status by printer.status.collectAsState()

    LaunchedEffect(Unit) { printer.refreshPaperWidth() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 4.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("打印预览", style = MaterialTheme.typography.titleMedium)
                val paperInfo = if (status.state == PaperangPrinter.ConnState.CONNECTED) {
                    val size = when (status.paperWidthPx) {
                        576 -> "2寸"; 864 -> "3寸"; else -> "${status.paperWidthPx}px"
                    }
                    "已连接 ${status.deviceName ?: ""} · 纸张 $size"
                } else "打印机未连接"
                Text(
                    paperInfo,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (status.state == PaperangPrinter.ConnState.CONNECTED) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
                Text(
                    "下方即打印效果（图片/公式/代码/分割线整体缩放）。拖动松手后预览刷新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PrintPreview(
                    text = text,
                    scale = previewScale,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp, max = 360.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .padding(4.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("字体", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = { previewScale = sliderValue },
                        valueRange = 0.5f..2.0f,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    Text("${(sliderValue * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(
                        enabled = !printing,
                        onClick = {
                            if (!ensurePrinterReady(printer, toaster)) return@TextButton
                            val activity = context.getActivity() ?: run {
                                toaster.show(message = "无法获取窗口，打印失败", type = ToastType.Error)
                                return@TextButton
                            }
                            if (text.isBlank()) {
                                toaster.show(message = "该回复没有可打印的文字", type = ToastType.Warning)
                                return@TextButton
                            }
                            printing = true
                            val chosenScale = sliderValue
                            scope.launch {
                                settingsStore.update { it.copy(displaySetting = it.displaySetting.copy(paperangPrinter = it.displaySetting.paperangPrinter.copy(printFontScale = chosenScale))) }
                                toaster.show("正在渲染并打印…")
                                val cfg = settings.displaySetting.paperangPrinter
                                runCatching {
                                    val bitmap = renderMessageBitmap(scope, activity, density, highlighter, settings, text, chosenScale)
                                    val result = printer.printBitmap(bitmap, grayscale = false, density = cfg.density, feedAfter = cfg.feedAfter)
                                    if (!bitmap.isRecycled) bitmap.recycle()
                                    result.getOrThrow()
                                }.onSuccess {
                                    toaster.dismissAll()
                                    toaster.show(message = "已发送到打印机", type = ToastType.Success)
                                    onDismiss()
                                }.onFailure {
                                    toaster.show(message = "打印失败: ${it.message ?: "未知错误"}", type = ToastType.Error)
                                }
                                printing = false
                            }
                        },
                    ) { Text(if (printing) "打印中…" else "打印") }
                }
            }
        }
    }
}

/** 检查打印机是否就绪；未就绪时按蓝牙状态给出正确提示。 */
private fun ensurePrinterReady(printer: PaperangPrinter, toaster: com.dokar.sonner.ToasterState): Boolean {
    if (printer.status.value.state == PaperangPrinter.ConnState.CONNECTED) return true
    val msg = if (!printer.isBluetoothOn()) "蓝牙未开启，请先打开蓝牙" else "打印机未连接，请长按「喵喵机错题」连接打印机"
    toaster.show(message = msg, type = ToastType.Warning)
    return false
}
