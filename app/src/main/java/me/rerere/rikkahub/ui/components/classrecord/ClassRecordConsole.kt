package me.rerere.rikkahub.ui.components.classrecord

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import me.rerere.rikkahub.data.classrecord.ClassRecordRepository
import me.rerere.rikkahub.data.classrecord.ClassRecordState
import me.rerere.rikkahub.ui.components.ui.CardGroup

private data class ConsoleRequest(
    val method: String,
    val path: String,
    val query: JsonObject,
    val body: JsonElement?,
    val multipart: Boolean,
    val files: Map<String, List<Uri>>,
)

@Composable
internal fun ClassRecordConsole(
    state: ClassRecordState,
    repository: ClassRecordRepository,
    workspaceId: String?,
    onAttachText: (String) -> Unit,
    onAttachFile: (JsonObject) -> Unit,
    onNotice: (String?) -> Unit,
) {
    var group by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = state.operations.find { it.string("id") == selectedId }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var task by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf<JsonElement?>(null) }
    var parameterValues by remember(selectedId) { mutableStateOf(JsonObject(emptyMap())) }
    val bodySchema = remember(selected) { resolveClassRecordSchema(selected?.get("bodySchema").objectOrEmpty()) }
    var body by remember(selectedId) { mutableStateOf<JsonElement>(schemaDefaults(bodySchema)) }
    var bodyDraft by remember(selectedId) { mutableStateOf(ClassRecordJsonDraft.fromForm(body)) }
    val parameterValidation = remember(selectedId) { ClassRecordFormValidationState() }
    val bodyValidation = remember(selectedId) { ClassRecordFormValidationState() }
    var useRawBody by remember(selectedId) { mutableStateOf(false) }
    val editingRawBody = useRawBody || bodySchema["properties"].objectOrEmpty().isEmpty()
    var selectedFiles by remember(selectedId) { mutableStateOf<Map<String, List<Uri>>>(emptyMap()) }
    var fileField by remember(selectedId) { mutableStateOf("file") }
    var uploadField by remember { mutableStateOf("file") }
    var manualMultipart by remember(selectedId) { mutableStateOf(false) }
    var pendingRequest by remember { mutableStateOf<ConsoleRequest?>(null) }

    fun invalidateRequest() {
        generation++
        task?.cancel()
        if (busy) onNotice("已停止等待。若刚才执行了修改操作，请重新读取服务器状态。")
        busy = false
        result = null
        pendingRequest = null
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { invalidateRequest(); selectedFiles = selectedFiles + (uploadField to uris) }
    }
    val context = LocalContext.current
    val parameterSchema = remember(selected) {
        val parameters = selected?.get("parameters").arrayOrEmpty().map { it.objectOrEmpty() }
        JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(parameters.associate { it.string("name") to resolveClassRecordSchema(it["schema"].objectOrEmpty()) }),
            "required" to JsonArray(parameters.filter { (it["required"] as? JsonPrimitive)?.booleanOrNull == true }.map { JsonPrimitive(it.string("name")) }),
        ))
    }
    val groups = state.operations.map { it.string("group") }.distinct().sortedWith(compareBy({ listOf("常用", "课堂", "设备", "学校", "我的学习").indexOf(it).takeIf { index -> index >= 0 } ?: 99 }, { it }))

    fun runRequest(onSuccess: ((JsonElement) -> Unit)? = null, block: suspend () -> JsonElement) {
        task?.cancel()
        val currentGeneration = ++generation
        busy = true
        task = scope.launch {
            onNotice(null)
            try {
                val response = block()
                if (currentGeneration == generation) {
                    result = response
                    onNotice("读取 / 操作完成")
                    onSuccess?.invoke(response)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (currentGeneration == generation) onNotice(error.message ?: "操作失败")
            } finally { if (currentGeneration == generation) busy = false }
        }
    }

    fun execute(request: ConsoleRequest) = runRequest {
        val currentGeneration = generation
        if (request.multipart) repository.upload(request.method, request.path, request.query, request.body.objectOrEmpty(), request.files)
        else repository.request(request.method, request.path, request.query, request.body, onEvent = { event ->
            if (currentGeneration == generation) result = event
        })
    }

    fun prepareRequest(): ConsoleRequest? {
        val operation = selected ?: return null
        val draftError = classRecordRequestDraftError(parameterValidation.error, bodyValidation.error, bodyDraft, editingRawBody)
        if (draftError != null) { onNotice(draftError); return null }
        validateClassRecordSchema(parameterSchema, parameterValues)?.let { onNotice(it); return null }
        val multipart = manualMultipart || operation.string("contentType").contains("multipart")
        val method = operation.string("method")
        var path = operation.string("path")
        val query = linkedMapOf<String, JsonElement>()
        operation["parameters"].arrayOrEmpty().forEach { raw ->
            val parameter = raw.objectOrEmpty()
            val name = parameter.string("name")
            val value = parameterValues[name]
            if (parameter.string("location") == "path") {
                if (value != null) path = path.replace("{$name}", Uri.encode(value.stringOrEmpty()))
            } else if (value != null) query[name] = value
        }
        if (path.contains('{')) { onNotice("请填写全部路径参数"); return null }
        val requestBody = when {
            method in listOf("GET", "HEAD") -> null
            editingRawBody && bodyDraft.text.isBlank() -> null
            else -> body
        }
        if (!multipart && requestBody != null) {
            validateClassRecordSchema(bodySchema, requestBody)?.let { onNotice(it); return null }
        }
        if (multipart) {
            val requiredFiles = bodySchema["required"].arrayOrEmpty().map { it.stringOrEmpty() }
            val fileNames = fileFields(bodySchema)
            fileNames.forEach { name ->
                if (name in requiredFiles && selectedFiles[name].isNullOrEmpty()) { onNotice("请选择 ${fieldLabel(name)}"); return null }
            }
            val fieldsSchema = bodySchema
                .withValue("properties", JsonObject(bodySchema["properties"].objectOrEmpty().filterKeys { it !in fileNames }))
                .withValue("required", JsonArray(requiredFiles.filter { it !in fileNames }.map(::JsonPrimitive)))
            validateClassRecordSchema(fieldsSchema, requestBody)?.let { onNotice(it); return null }
        }
        return ConsoleRequest(method, path, JsonObject(query), requestBody, multipart, selectedFiles)
    }

    LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("学校空间控制面板", style = MaterialTheme.typography.titleLarge)
                Text("功能目录来自当前服务器，按登录角色和权限显示。", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(group == null, { if (!busy) group = null }, label = { Text("全部") })
                    groups.forEach { name -> FilterChip(group == name, { if (!busy) group = name }, label = { Text(name) }) }
                }
                OutlinedTextField(filter, { filter = it }, label = { Text("搜索面板、操作或接口") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (state.operations.isEmpty()) Text("当前账号没有控制面板操作，或功能目录尚未加载。可在账号页刷新。")
                val visible = state.operations.filter { operation ->
                    (group == null || operation.string("group") == group) &&
                        listOf("title", "path", "method").any { operation.string(it).contains(filter, ignoreCase = true) }
                }
                if (selected == null) {
                    Text("${visible.size} 个可用操作", style = MaterialTheme.typography.labelMedium)
                } else {
                    TextButton(enabled = !busy, onClick = { selectedId = null; result = null }) { Text("返回操作目录") }
                }
            }
        }
        if (selected == null) {
            val visible = state.operations.filter { operation ->
                (group == null || operation.string("group") == group) &&
                    listOf("title", "path", "method").any { operation.string(it).contains(filter, ignoreCase = true) }
            }
            visible.groupBy { it.string("group") }.forEach { (name, operations) ->
                item {
                    CardGroup(title = { Text(name) }) {
                        operations.forEach { operation ->
                            item(
                                onClick = { selectedId = operation.string("id"); result = null },
                                headlineContent = { Text(operation.string("title")) },
                                supportingContent = { Text("${operation.string("method")} ${operation.string("path")}") },
                            )
                        }
                    }
                }
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(selected.string("title"), style = MaterialTheme.typography.titleLarge)
                    SelectionContainer { Text("${selected.string("method")} ${selected.string("path")}", style = MaterialTheme.typography.bodySmall) }
                    if (parameterSchema["properties"].objectOrEmpty().isNotEmpty()) CardGroup(title = { Text("选择操作范围") }) {
                        formItem { ClassRecordSchemaForm(parameterSchema, parameterValues, { invalidateRequest(); parameterValues = it }, validationState = parameterValidation) }
                    }
                    if (selected.string("method") !in listOf("GET", "HEAD")) {
                        CardGroup(title = { Text("操作内容") }) {
                            formItem {
                                if (!editingRawBody) {
                                    val withoutFiles = bodySchema.withValue("properties", JsonObject(bodySchema["properties"].objectOrEmpty().filterKeys { it !in fileFields(bodySchema) }))
                                    ClassRecordSchemaForm(withoutFiles, body.objectOrEmpty(), {
                                        invalidateRequest()
                                        body = it
                                        bodyDraft = ClassRecordJsonDraft.fromForm(it)
                                    }, validationState = bodyValidation)
                                } else {
                                    OutlinedTextField(
                                        bodyDraft.text,
                                        { input ->
                                            invalidateRequest()
                                            val next = bodyDraft.edit(input)
                                            bodyDraft = next
                                            if (next.error == null) body = next.value ?: JsonObject(emptyMap())
                                        },
                                        label = { Text("请求内容 JSON") },
                                        minLines = 4,
                                        isError = bodyDraft.error != null,
                                        supportingText = { Text(bodyDraft.error ?: "按服务器要求填写；空白表示无额外字段") },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                if (bodySchema["properties"].objectOrEmpty().isNotEmpty()) TextButton(onClick = {
                                    invalidateRequest()
                                    useRawBody = !useRawBody
                                }) { Text(if (useRawBody) "返回原生表单" else "编辑完整 JSON") }
                            }
                        }
                        val multipart = manualMultipart || selected.string("contentType").contains("multipart")
                        if (!selected.string("contentType").contains("multipart")) TextButton(onClick = { invalidateRequest(); manualMultipart = !manualMultipart }) {
                            Text(if (manualMultipart) "切换为 JSON 请求" else "需要上传文件？切换到原生文件上传")
                        }
                        if (multipart) {
                            CardGroup(title = { Text("上传文件") }) {
                                formItem {
                                    val names = fileFields(bodySchema)
                                    if (names.isEmpty()) OutlinedTextField(fileField, { invalidateRequest(); fileField = it }, label = { Text("上传字段名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    (names.ifEmpty { listOf(fileField) }).forEach { name ->
                                        TextButton(enabled = name.isNotBlank() && !busy, onClick = { uploadField = name; filePicker.launch(arrayOf("*/*")) }) { Text("选择 ${fieldLabel(name)} 文件") }
                                        selectedFiles[name]?.forEach { uri ->
                                            val displayName = remember(uri) { runCatching {
                                                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                                                    if (cursor.moveToFirst()) cursor.getString(0) else uri.lastPathSegment
                                                }
                                            }.getOrNull() ?: uri.lastPathSegment.orEmpty() }
                                            Text(displayName, style = MaterialTheme.typography.bodySmall)
                                        }
                                        if (!selectedFiles[name].isNullOrEmpty()) TextButton(onClick = { invalidateRequest(); selectedFiles = selectedFiles - name }) { Text("清除此字段文件") }
                                    }
                                }
                            }
                        }
                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("执行前检查", style = MaterialTheme.typography.titleSmall)
                                Text("${selected.string("method")} ${selected.string("path")}")
                                Text("范围：${classRecordPlainText(parameterValues)}", style = MaterialTheme.typography.bodySmall)
                                if (body != JsonObject(emptyMap())) Text("内容：${classRecordPlainText(body)}", style = MaterialTheme.typography.bodySmall)
                                if (selectedFiles.isNotEmpty()) Text("上传 ${selectedFiles.values.sumOf { it.size }} 个文件")
                            }
                        }
                    }
                    val draftError = classRecordRequestDraftError(parameterValidation.error, bodyValidation.error, bodyDraft, editingRawBody)
                    draftError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(enabled = !busy && draftError == null, modifier = Modifier.fillMaxWidth(), onClick = {
                        prepareRequest()?.let { request ->
                            if (riskyOperation(selected)) pendingRequest = request else execute(request)
                        }
                    }) { Text(if (busy) "正在执行…" else if (selected.string("method") in listOf("GET", "HEAD")) "读取" else "执行：${selected.string("title")}") }
                    if (busy) TextButton(onClick = { invalidateRequest() }) { Text("停止等待") }
                }
            }
        }
        result?.let { current ->
            item {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("操作结果", style = MaterialTheme.typography.titleMedium)
                        ClassRecordResult(
                            current, onAttachText,
                            onTool = { name, arguments ->
                                if (!busy) runRequest { repository.call(name, arguments) }
                            },
                            onDownload = { id, entry, toWorkspace ->
                                if (!busy) runRequest(onSuccess = { response ->
                                        val file = response.objectOrEmpty()
                                        if (toWorkspace) {
                                            if (file["workspace_imported"] == JsonPrimitive(true)) onNotice("文件已保存到工作区：${file.string("path")}")
                                            else onNotice("文件已保存在本机，工作区导入失败：${file.string("workspace_error").ifBlank { "请检查工作区状态后重试" }}")
                                        } else onAttachFile(file)
                                }) { repository.download(id, entry, if (toWorkspace) workspaceId else null) }
                            },
                            onAttachLocal = onAttachFile,
                            workspaceAvailable = workspaceId != null,
                        )
                        val page = current.objectOrEmpty()
                        val rows = (page["items"] ?: page["results"] ?: page["rows"]).arrayOrEmpty()
                        val limit = (page["limit"] as? JsonPrimitive)?.longOrNull ?: (parameterValues["limit"] as? JsonPrimitive)?.longOrNull ?: 50
                        val offset = (page["offset"] as? JsonPrimitive)?.longOrNull ?: (parameterValues["offset"] as? JsonPrimitive)?.longOrNull ?: 0
                        val total = (page["total"] as? JsonPrimitive)?.longOrNull
                        if (selected?.string("method") == "GET" && parameterSchema["properties"].objectOrEmpty().containsKey("offset")) {
                            Row {
                                TextButton(enabled = !busy && offset > 0, onClick = {
                                    parameterValues = parameterValues.withValue("offset", JsonPrimitive((offset - limit).coerceAtLeast(0)))
                                    prepareRequest()?.let(::execute)
                                }) { Text("上一页") }
                                TextButton(enabled = !busy && if (total != null) offset + limit < total else rows.size >= limit, onClick = {
                                    parameterValues = parameterValues.withValue("offset", JsonPrimitive(offset + limit))
                                    prepareRequest()?.let(::execute)
                                }) { Text("下一页") }
                            }
                        }
                    }
                }
            }
        }
        item { Column(Modifier.heightIn(min = 16.dp)) {} }
    }
    pendingRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { pendingRequest = null },
            title = { Text(selected?.string("title") ?: "确认操作") },
            text = { SelectionContainer { Text("${request.method} ${request.path}\n\n范围：${classRecordPlainText(request.query)}\n\n内容：${request.body?.let(::classRecordPlainText) ?: "无"}", modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton(onClick = { pendingRequest = null; execute(request) }) { Text("确认执行") } },
            dismissButton = { TextButton(onClick = { pendingRequest = null }) { Text("取消") } },
        )
    }
}

private fun fileFields(schema: JsonObject): List<String> = schema["properties"].objectOrEmpty().filter { (_, raw) ->
    val field = raw.objectOrEmpty()
    field.string("format") == "binary" || field["items"].objectOrEmpty().string("format") == "binary"
}.keys.toList()

private fun riskyOperation(operation: JsonObject): Boolean = operation.string("method") == "DELETE" ||
    listOf("删除", "部署", "覆盖", "恢复", "重置").any { operation.string("title").contains(it) } ||
    listOf("/deploy", "/restore", "/reset", "/revoke", "/terminate").any { operation.string("path").contains(it) }
