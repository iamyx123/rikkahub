package me.rerere.rikkahub.ui.components.intelligentui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.intelligentui.*

val LocalInteractiveUiSnapshots = staticCompositionLocalOf<Map<String, JsonObject>> { emptyMap() }

/** Evaluate saved state before BitmapComposer starts its synchronous layout/capture. No actions/network. */
suspend fun prepareInteractiveUiSnapshots(context: Context, messages: List<UIMessage>): Map<String, JsonObject> {
    val snapshots = mutableMapOf<String, JsonObject>()
    var bytes = 0
    for (message in messages) for (part in message.parts.filterIsInstance<UIMessagePart.Text>()) {
        for (source in interactiveUiSources(part.text)) {
            val key = interactiveUiKey(source)
            if (bytes >= 8 * 1024 * 1024) continue // Remaining cards retain their source/values fallback.
            try {
                val result = InteractiveUiRuntime.render(context, source, message.interactiveUiStates[key] ?: buildJsonObject {})
                if (result["root"] == null || result["root"] == JsonNull || (result["errors"] as? JsonArray)?.isNotEmpty() == true) continue
                val size = result.toString().length * 2
                if (bytes + size > 8 * 1024 * 1024) continue
                snapshots["${message.id}/$key"] = result
                bytes += size
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { /* Keep the original source rather than drop a failed card. */ }
        }
    }
    return snapshots
}

@Composable
internal fun NativeUiSnapshot(value: JsonElement?) {
    if(value is JsonArray) { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { value.forEach { NativeUiSnapshot(it) } }; return }
    val node = value as? JsonObject ?: run { if(value != null && value != JsonNull) Text((value as? JsonPrimitive)?.contentOrNull ?: value.toString()); return }
    val p = node["props"] as? JsonObject ?: return
    val type = node.text("type")
    when(type) {
        "Col","Row","Grid","Box","Form","Carousel","Pressable" -> NativeUiSnapshot(p["children"])
        "Card" -> Surface(shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(p.text("title"), style = MaterialTheme.typography.titleSmall); NativeUiSnapshot(p["children"])
            }
        }
        "Popover" -> Column { NativeUiSnapshot(p["trigger"]); NativeUiSnapshot(p["content"]) }
        "Flashcard" -> Column { NativeUiSnapshot(p["front"]); Text("答案",style = MaterialTheme.typography.labelSmall); NativeUiSnapshot(p["back"]) }
        "Accordion" -> Column { Text(p.text("title"), style = MaterialTheme.typography.titleSmall); NativeUiSnapshot(p["children"]) }
        "Text" -> if(p["text"] is JsonArray) NativeUiSnapshot(p["text"]) else NativeUiNode(node, false, {})
        "List" -> NativeUiSnapshot(p["items"])
        "ListItem" -> Column { if(p.text("label").isNotBlank()) Text(p.text("label")); NativeUiSnapshot(p["content"]) }
        "Input","TextArea","NumberInput","Slider","Select","RadioGroup","SegmentedControl" -> Text("${p.text("label")}：${p.text("value")}")
        "Checkbox","Switch" -> Text((if((p["value"] as? JsonPrimitive)?.booleanOrNull == true) "☑ " else "□ ") + p.text("label"))
        "DatePicker" -> Text("${p.text("label")}：${p.text("value")}")
        "Timer" -> Text("${p.text("label", "倒计时")}：${p.number("seconds").toInt()} 秒")
        "Button","IconButton" -> Text(p.text("label"), style = MaterialTheme.typography.labelSmall)
        "Icon" -> if(p.text("label").isNotBlank()) Text(p.text("label"), style = MaterialTheme.typography.labelSmall)
        "Link" -> Text("${p.text("text")} (${p.text("url")})", style = MaterialTheme.typography.bodySmall)
        "Image" -> Text("${p.text("alt")}\n${p.text("url")}", style = MaterialTheme.typography.bodySmall)
        "SearchImage" -> Text("${p.text("alt")} · 图片搜索：${p.text("query")}", style = MaterialTheme.typography.bodySmall)
        "Map","MapMarker" -> Column {
            Text(p.text("title", "地点"), style = MaterialTheme.typography.titleSmall)
            markerPoints(if(type == "MapMarker") JsonArray(listOf(p)) else p.list("markers")).forEachIndexed { i, marker ->
                Text("${i+1}. ${marker.label} (${marker.lat}, ${marker.lon})", style = MaterialTheme.typography.bodySmall)
            }
        }
        "Table" -> Column {
            Text(p.list("columns").joinToString(" · ") { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }, style=MaterialTheme.typography.labelMedium)
            p.list("rows").take(100).forEach { row -> Row(Modifier.fillMaxWidth()) {
                (row as? JsonArray)?.forEach { cell -> Box(Modifier.weight(1f).padding(3.dp)) { NativeUiSnapshot(cell) } }
            } }
        }
        else -> NativeUiNode(node, false, {}) // Static chart, SVG, math and text share their native renderer.
    }
}
