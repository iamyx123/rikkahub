package me.rerere.rikkahub.ui.components.message

import android.app.Activity
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

/** 打印内容的虚拟“纸张”宽度（dp）；字体相对此宽度，密度无关，保证预览与打印一致。 */
private val PRINT_CONTENT_WIDTH = 288.dp
private const val PRINT_BASE_SP = 13f

/**
 * 打印内容渲染体：预览与实际打印共用，保证所见即所得。
 * 字号 = PRINT_BASE_SP * fontScale，宽度固定 288dp，因此打印/预览字体比例一致。
 */
@Composable
fun PrintableMessageContent(
    text: String,
    fontScale: Float,
    highlighter: Highlighter,
    settings: Settings,
) {
    // 离屏渲染需自带 Navigator/Toaster：Markdown 内的代码块/Mermaid 会读取 LocalNavController
    val navBackStack = remember { mutableStateListOf<NavKey>() }
    val navigator = remember { Navigator(navBackStack) }
    val toasterState = rememberToasterState()
    // 标题(#/##)字号走 displaySetting.fontSizeRatio，这里把它设为字号倍率，让标题也随字体一起缩放
    val printSettings = remember(settings, fontScale) {
        settings.copy(displaySetting = settings.displaySetting.copy(fontSizeRatio = fontScale))
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
                    .width(PRINT_CONTENT_WIDTH)
                    .background(Color.White)
                    .padding(10.dp)
            ) {
                // key(fontScale)：字号变化时强制重建子树，让内联公式(缓存于 remember 的 annotatedString)一起重新按新字号渲染
                key(fontScale) {
                    MarkdownBlock(
                        content = text,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = Color.Black,
                            fontSize = (PRINT_BASE_SP * fontScale).sp,
                            lineHeight = (PRINT_BASE_SP * fontScale * 1.45f).sp,
                        ),
                    )
                }
            }
        }
    }
}

/** 离屏把消息渲染为位图（供打印）。 */
private suspend fun renderMessageBitmap(
    scope: CoroutineScope,
    activity: Activity,
    density: Density,
    highlighter: Highlighter,
    settings: Settings,
    text: String,
    fontScale: Float,
): Bitmap {
    val composer = BitmapComposer(scope)
    return composer.composableToBitmap(
        activity = activity,
        width = PRINT_CONTENT_WIDTH,
        screenDensity = density,
    ) {
        PrintableMessageContent(text = text, fontScale = fontScale, highlighter = highlighter, settings = settings)
    }
}

/**
 * 短按「打印这条回复」：用用户配置好的默认字号直接渲染并黑白打印。
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
                    val result = printer.printBitmap(bitmap, grayscale = false, density = cfg.density, feedAfter = 0)
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

/**
 * 长按「打印这条回复」：打开预览窗口，实时调节字体后打印；调节结果同时保存为默认字号。
 */
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
    // sliderValue 跟随手指（轻量），previewScale 仅在松手后更新（避免逐帧重渲染公式导致卡顿/ANR）
    var sliderValue by remember { mutableFloatStateOf(initialScale) }
    var previewScale by remember { mutableFloatStateOf(initialScale) }
    var printing by remember { mutableStateOf(false) }
    val status by printer.status.collectAsState()

    // 打开预览时刷新一次纸张信息
    LaunchedEffect(Unit) { runCatching { printer.refreshPaperWidth() } }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 4.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("打印预览", style = MaterialTheme.typography.titleMedium)
                // 纸张信息（连接后自动刷新）
                val paperInfo = when {
                    status.state != PaperangPrinter.ConnState.CONNECTED -> "打印机未连接"
                    else -> {
                        val size = when (status.paperWidthPx) {
                            576 -> "2寸"
                            864 -> "3寸"
                            else -> "${status.paperWidthPx}px"
                        }
                        "已连接 ${status.deviceName ?: ""} · 纸张 $size（${status.paperWidthPx}px）"
                    }
                }
                Text(
                    paperInfo,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (status.state == PaperangPrinter.ConnState.CONNECTED) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
                Text(
                    "拖动调节字体大小，预览即打印效果。点「打印」按此字号打印（并记为默认）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 预览：白底“纸张”框，内容与打印一致
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .padding(6.dp)
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    PrintableMessageContent(text = text, fontScale = previewScale, highlighter = highlighter, settings = settings)
                }
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
                                // 记为默认字号
                                settingsStore.update { it.copy(displaySetting = it.displaySetting.copy(paperangPrinter = it.displaySetting.paperangPrinter.copy(printFontScale = chosenScale))) }
                                toaster.show("正在渲染并打印…")
                                val cfg = settings.displaySetting.paperangPrinter
                                runCatching {
                                    val bitmap = renderMessageBitmap(scope, activity, density, highlighter, settings, text, chosenScale)
                                    val result = printer.printBitmap(bitmap, grayscale = false, density = cfg.density, feedAfter = 0)
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
