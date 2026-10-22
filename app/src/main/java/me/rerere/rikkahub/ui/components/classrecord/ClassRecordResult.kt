package me.rerere.rikkahub.ui.components.classrecord

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import coil3.compose.AsyncImage
import me.rerere.rikkahub.data.classrecord.ClassRecordRepository
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import org.koin.compose.koinInject

internal fun classRecordPlainText(value: JsonElement): String = when (value) {
    JsonNull -> "无"
    is JsonPrimitive -> value.content
    is JsonArray -> value.mapIndexed { index, item -> "${index + 1}. ${classRecordPlainText(item)}" }.joinToString("\n\n")
    is JsonObject -> value.entries.joinToString("\n\n") { (key, item) -> "${fieldLabel(key)}：${classRecordPlainText(item)}" }
}

/** All strings are selectable and untruncated. Nested values open in place without a WebView. */
@Composable
internal fun ClassRecordResult(
    value: JsonElement,
    onAttachText: (String) -> Unit,
    onTool: (String, JsonObject) -> Unit,
    onDownload: (String, String?, Boolean) -> Unit,
    onAttachLocal: (JsonObject) -> Unit,
    workspaceAvailable: Boolean,
    inheritedResourceId: String? = null,
    depth: Int = 0,
) {
    var showRaw by remember(value) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val obj = value as? JsonObject
    val resourceId = obj?.string("resource_id")?.ifBlank { null }
        ?: obj?.get("metadata").objectOrEmpty().string("resource_id").ifBlank { null }
        ?: inheritedResourceId
    val title = obj?.string("title")?.ifBlank { null }
        ?: obj?.string("file_name")?.ifBlank { null }
        ?: obj?.string("name")?.ifBlank { null }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (depth == 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onAttachText(classRecordPlainText(value)) }) { Text("加入聊天") }
                TextButton(onClick = { clipboard.setText(AnnotatedString(classRecordPlainText(value))) }) { Text("复制") }
                TextButton(onClick = { showRaw = true }) { Text("原始 JSON") }
            }
        }
        when (value) {
            is JsonObject -> {
                title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                if (value.string("local_uri").isNotBlank()) {
                    TextButton(onClick = { onAttachLocal(value) }) { Text("将本地文件加入聊天") }
                }
                if (resourceId != null && (value["resource_id"] != null || value["metadata"].objectOrEmpty()["resource_id"] != null || value["entry_path"] != null)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (value.string("entry_path").isBlank()) {
                            TextButton(onClick = { onTool("fetch_material", JsonObject(mapOf("id" to JsonPrimitive(resourceId)))) }) { Text("读取文件 / 压缩包目录") }
                        }
                        TextButton(onClick = { onDownload(resourceId, value.string("entry_path").ifBlank { null }, false) }) { Text(if (resourceId.startsWith("screen/")) "给 AI 查看" else "下载并加入聊天") }
                    }
                    if (workspaceAvailable) {
                        TextButton(onClick = { onDownload(resourceId, value.string("entry_path").ifBlank { null }, true) }) { Text("下载到当前工作区") }
                    }
                    if (resourceId.startsWith("screen/") || value.string("content_type").startsWith("image/")) {
                        ClassRecordImagePreview(resourceId, value.string("entry_path").ifBlank { null }, onAttachLocal)
                    }
                }
                if (value.string("class_id").isNotBlank() && value.string("class_name").isNotBlank()) {
                    val filters = JsonObject(mapOf("school_id" to JsonPrimitive(value.string("school_id")), "class_id" to JsonPrimitive(value.string("class_id"))))
                    Row {
                        TextButton(onClick = { onTool("list_courses", filters) }) { Text("查看该班课程") }
                        TextButton(onClick = { onTool("list_materials", filters) }) { Text("查看该班资料") }
                    }
                }
                if (value.string("id").startsWith("lesson/")) {
                    Row {
                        TextButton(onClick = { onTool("fetch", JsonObject(mapOf("id" to value.getValue("id"), "mode" to JsonPrimitive("body")))) }) { Text("阅读课程正文") }
                        TextButton(onClick = { onTool("fetch", JsonObject(mapOf("id" to value.getValue("id"), "mode" to JsonPrimitive("full")))) }) { Text("完整课程档案") }
                    }
                }
                value.forEach { (name, element) ->
                    if (name == "title" && title == element.stringOrEmpty()) return@forEach
                    if (element is JsonPrimitive || element == JsonNull) {
                        Column {
                            Text(fieldLabel(name), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            SelectionContainer { Text(classRecordPlainText(element), style = MaterialTheme.typography.bodyMedium) }
                        }
                        // Extracted archive listings can be encoded inside the source text.
                        if (name == "text" && resourceId != null) {
                            val parsed = remember(element) { runCatching { Json.parseToJsonElement(element.stringOrEmpty()) }.getOrNull() }
                            if (parsed is JsonObject || parsed is JsonArray) {
                                ResultBranch("解析资料结构 / 压缩包成员", parsed, onAttachText, onTool, onDownload, onAttachLocal, workspaceAvailable, resourceId, depth + 1)
                            }
                        }
                    } else {
                        ResultBranch(fieldLabel(name), element, onAttachText, onTool, onDownload, onAttachLocal, workspaceAvailable, resourceId, depth + 1)
                    }
                }
            }
            is JsonArray -> value.forEachIndexed { index, item ->
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${index + 1}", style = MaterialTheme.typography.labelSmall)
                        ClassRecordResult(item, onAttachText, onTool, onDownload, onAttachLocal, workspaceAvailable, resourceId, depth + 1)
                        TextButton(onClick = { onAttachText(classRecordPlainText(item)) }) { Text("将此条加入聊天") }
                    }
                }
            }
            else -> SelectionContainer { Text(classRecordPlainText(value)) }
        }
    }
    if (showRaw) {
        AlertDialog(
            onDismissRequest = { showRaw = false },
            title = { Text("完整原始结果") },
            text = {
                SelectionContainer {
                    Text(
                        classRecordJson.encodeToString(JsonElement.serializer(), value),
                        modifier = Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showRaw = false }) { Text("关闭") } },
            dismissButton = {
                TextButton(onClick = { clipboard.setText(AnnotatedString(classRecordJson.encodeToString(JsonElement.serializer(), value))) }) { Text("复制全部") }
            },
        )
    }
}

@Composable
private fun ClassRecordImagePreview(resourceId: String, entryPath: String?, onAttach: (JsonObject) -> Unit) {
    val repository = koinInject<ClassRecordRepository>()
    val scope = rememberCoroutineScope()
    var file by remember(resourceId, entryPath) { mutableStateOf<JsonObject?>(null) }
    var loading by remember(resourceId, entryPath) { mutableStateOf(false) }
    var error by remember(resourceId, entryPath) { mutableStateOf<String?>(null) }
    var showFull by remember(resourceId, entryPath) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val local = file
        if (local == null) TextButton(enabled = !loading, onClick = {
            scope.launch {
                loading = true
                error = null
                try { file = repository.download(resourceId, entryPath) }
                catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message }
                finally { loading = false }
            }
        }) { Text(if (loading) "正在读取图片…" else "预览图片") }
        else {
            AsyncImage(
                model = local.string("local_uri"),
                contentDescription = local.string("file_name").ifBlank { "教室截图" },
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp).clickable { showFull = true },
            )
            Text("轻点图片可放大查看", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onAttach(local) }) { Text("将这张图片加入聊天") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    if (showFull && file != null) ImagePreviewDialog(images = listOf(file!!.string("local_uri")), onDismissRequest = { showFull = false })
}

@Composable
private fun ResultBranch(
    label: String,
    value: JsonElement,
    onAttachText: (String) -> Unit,
    onTool: (String, JsonObject) -> Unit,
    onDownload: (String, String?, Boolean) -> Unit,
    onAttachLocal: (JsonObject) -> Unit,
    workspaceAvailable: Boolean,
    resourceId: String?,
    depth: Int,
) {
    var expanded by remember(value) { mutableStateOf(depth <= 1 && value is JsonArray) }
    Column {
        TextButton(onClick = { expanded = !expanded }) {
            val count = when (value) { is JsonArray -> " · ${value.size} 项"; is JsonObject -> " · ${value.size} 个字段"; else -> "" }
            Text("${if (expanded) "收起" else "展开"} $label$count")
        }
        if (expanded) {
            ClassRecordResult(
                value, onAttachText, onTool, onDownload, onAttachLocal, workspaceAvailable,
                resourceId, depth,
            )
        }
    }
}
