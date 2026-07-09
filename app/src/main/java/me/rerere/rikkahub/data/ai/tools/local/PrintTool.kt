package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.highlight.Highlighter
import me.rerere.rikkahub.RikkaHubApp
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.paperang.PaperangPrinter
import me.rerere.rikkahub.ui.components.message.renderMessageBitmap

/**
 * AI 打印工具：模型输出 Markdown，自动渲染为图片并按用户默认字号（或自定义 fontScale）通过喵喵机打印。
 */
internal fun buildPrintTool(
    context: Context,
    printer: PaperangPrinter,
    highlighter: Highlighter,
    settingsStore: SettingsStore,
): Tool = Tool(
    name = "print",
    description = ("把 Markdown 内容渲染成图片并通过蓝牙热敏打印机(喵喵机)打印出来。" +
        "支持标题/列表/代码/表格与 LaTeX 公式(\$...\$)。当用户明确要求打印某些内容时使用。" +
        "可选参数 fontScale(0.5~2.0)控制字号，不传则用用户设置的默认字号。").replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("markdown", buildJsonObject {
                    put("type", "string")
                    put("description", "要打印的 Markdown 文本，可包含 \$...\$ LaTeX 公式")
                })
                put("fontScale", buildJsonObject {
                    put("type", "number")
                    put("description", "可选，字号倍率 0.5~2.0，不传则用用户默认字号")
                })
            },
            required = listOf("markdown")
        )
    },
    execute = { input ->
        val params = input.jsonObject
        val markdown = params["markdown"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("markdown is required")
        val cfg = settingsStore.settingsFlow.value.displaySetting.paperangPrinter
        val fontScale = params["fontScale"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: cfg.printFontScale
        if (printer.status.value.state != PaperangPrinter.ConnState.CONNECTED) {
            error("打印机未连接：请让用户长按输入框加号里的「喵喵机错题」连接喵喵机打印机后再试")
        }
        val activity = RikkaHubApp.currentActivity ?: error("应用当前不在前台，无法渲染打印内容")
        val density = Density(
            density = context.resources.displayMetrics.density,
            fontScale = context.resources.configuration.fontScale,
        )
        val result = withContext(Dispatchers.Main) {
            val bitmap = renderMessageBitmap(
                scope = this,
                activity = activity,
                density = density,
                highlighter = highlighter,
                settings = settingsStore.settingsFlow.value,
                text = markdown,
                scale = fontScale,
            )
            val r = printer.printBitmap(bitmap, grayscale = false, density = cfg.density, feedAfter = cfg.feedAfter)
            if (!bitmap.isRecycled) bitmap.recycle()
            r
        }
        result.getOrThrow()
        listOf(UIMessagePart.Text(buildJsonObject { put("success", true); put("message", "已发送到打印机") }.toString()))
    }
)
