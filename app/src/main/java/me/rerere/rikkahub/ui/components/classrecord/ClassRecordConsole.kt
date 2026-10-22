package me.rerere.rikkahub.ui.components.classrecord

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.data.classrecord.ClassRecordRepository
import me.rerere.rikkahub.data.classrecord.ClassRecordState
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem

private data class ConsoleRequest(
    val method: String,
    val path: String,
    val query: JsonObject,
    val scope: JsonObject,
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
    var showGroupPicker by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = state.operations.find { it.string("id") == selectedId }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var task by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf<JsonElement?>(null) }
    var failureDetails by remember(selectedId) { mutableStateOf<String?>(null) }
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
    var advancedExpanded by remember(selectedId) { mutableStateOf(false) }
    var previewExpanded by remember(selectedId) { mutableStateOf(false) }
    var pendingRequest by remember { mutableStateOf<ConsoleRequest?>(null) }

    fun invalidateRequest() {
        generation++
        task?.cancel()
        if (busy) onNotice("已停止等待。若刚才执行了修改操作，请重新读取服务器状态。")
        busy = false
        result = null
        failureDetails = null
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
    val operationTitles = remember(state.operations) {
        state.operations.mapIndexed { index, operation ->
            operation.string("id") to classRecordDisplayTitle(operation.string("title"), "其他操作 ${index + 1}")
        }.toMap()
    }
    fun operationTitle(operation: JsonObject): String = operationTitles[operation.string("id")] ?: "其他操作"
    fun groupTitle(name: String): String = classRecordDisplayTitle(name, when (name.trim().lowercase()) {
        "" -> "其他"
        "common", "frequent" -> "常用"
        "classroom", "classrooms", "class", "lessons" -> "课堂"
        "device", "devices" -> "设备"
        "school", "schools", "admin" -> "学校"
        "study", "learning", "my-learning", "my_learning" -> "我的学习"
        else -> "其他分类 ${groups.indexOf(name) + 1}"
    })

    fun runRequest(onSuccess: ((JsonElement) -> Unit)? = null, block: suspend () -> JsonElement) {
        task?.cancel()
        val currentGeneration = ++generation
        busy = true
        task = scope.launch {
            onNotice(null)
            failureDetails = null
            try {
                val response = block()
                if (currentGeneration == generation) {
                    result = response
                    onNotice("操作完成")
                    onSuccess?.invoke(response)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (currentGeneration == generation) {
                    failureDetails = error.message
                    onNotice(classRecordDisplayTitle(error.message.orEmpty(), "操作失败，请在高级选项中查看详情。"))
                }
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
                if (name in requiredFiles && selectedFiles[name].isNullOrEmpty()) {
                    onNotice("请选择${classRecordFieldTitle(name, bodySchema["properties"].objectOrEmpty()[name].objectOrEmpty(), "上传文件")}")
                    return null
                }
            }
            val fieldsSchema = bodySchema
                .withValue("properties", JsonObject(bodySchema["properties"].objectOrEmpty().filterKeys { it !in fileNames }))
                .withValue("required", JsonArray(requiredFiles.filter { it !in fileNames }.map(::JsonPrimitive)))
            validateClassRecordSchema(fieldsSchema, requestBody)?.let { onNotice(it); return null }
        }
        return ConsoleRequest(method, path, JsonObject(query), parameterValues, requestBody, multipart, selectedFiles)
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (selected == null) {
            val visible = state.operations.filter { operation ->
                (group == null || operation.string("group") == group) &&
                    (operationTitle(operation).contains(filter, ignoreCase = true) ||
                        listOf("title", "path", "method").any { operation.string(it).contains(filter, ignoreCase = true) })
            }
            item {
                CardGroup {
                    formItem {
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            label = { Text("搜索功能") },
                            leadingIcon = { Icon(HugeIcons.Search01, null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item(
                        onClick = { showGroupPicker = true },
                        headlineContent = { Text("功能分类") },
                        supportingContent = { Text(group?.let(::groupTitle) ?: "全部功能") },
                        trailingContent = { Icon(HugeIcons.ArrowDown01, "选择分类") },
                    )
                }
            }
            if (visible.isEmpty()) {
                item {
                    CardGroup {
                        item(
                            headlineContent = { Text(if (state.operations.isEmpty()) "暂无可用功能" else "未找到匹配功能") },
                            supportingContent = {
                                Text(if (state.operations.isEmpty()) "点击右上角刷新，重新加载当前账号的功能。" else "试试其他关键词或功能分类。")
                            },
                        )
                    }
                }
            }
            visible.groupBy { it.string("group") }.forEach { (name, operations) ->
                item {
                    CardGroup(title = { Text(groupTitle(name)) }) {
                        operations.forEach { operation ->
                            item(
                                onClick = { selectedId = operation.string("id"); result = null },
                                headlineContent = { Text(operationTitle(operation)) },
                                supportingContent = if (riskyOperation(operation)) {
                                    { Text("执行前需要确认") }
                                } else null,
                                trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
                            )
                        }
                    }
                }
            }
        } else {
            item {
                // Keep all inputs and their validation alive together until this operation is left.
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !busy, onClick = { selectedId = null; result = null }) {
                            Icon(HugeIcons.ArrowLeft01, null)
                            Text("返回功能列表", modifier = Modifier.padding(start = 8.dp))
                        }
                        Text(operationTitle(selected), style = MaterialTheme.typography.titleMediumEmphasized)
                    }
                    if (parameterSchema["properties"].objectOrEmpty().isNotEmpty()) {
                        CardGroup(title = { Text("选择操作范围") }) {
                            formItem { ClassRecordSchemaForm(parameterSchema, parameterValues, { invalidateRequest(); parameterValues = it }, validationState = parameterValidation, showTechnicalDetails = advancedExpanded) }
                        }
                    }
                    val writesData = selected.string("method") !in listOf("GET", "HEAD")
                    val multipart = manualMultipart || selected.string("contentType").contains("multipart")
                    val withoutFiles = bodySchema.withValue("properties", JsonObject(bodySchema["properties"].objectOrEmpty().filterKeys { it !in fileFields(bodySchema) }))
                    if (writesData && !editingRawBody && withoutFiles["properties"].objectOrEmpty().isNotEmpty()) {
                        CardGroup(title = { Text("操作内容") }) {
                            formItem {
                                ClassRecordSchemaForm(withoutFiles, body.objectOrEmpty(), {
                                    invalidateRequest()
                                    body = it
                                    bodyDraft = ClassRecordJsonDraft.fromForm(it)
                                }, validationState = bodyValidation, showTechnicalDetails = advancedExpanded)
                            }
                        }
                    }
                    if (writesData && multipart) {
                        CardGroup(title = { Text("上传文件") }) {
                            val names = fileFields(bodySchema)
                            (names.ifEmpty { listOf(fileField) }).forEachIndexed { index, name ->
                                formItem(headlineContent = {
                                    Text(classRecordFieldTitle(name, bodySchema["properties"].objectOrEmpty()[name].objectOrEmpty(), "上传文件 ${index + 1}"))
                                }) {
                                    OutlinedButton(
                                        enabled = name.isNotBlank() && !busy,
                                        onClick = { uploadField = name; filePicker.launch(arrayOf("*/*")) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text(if (selectedFiles[name].isNullOrEmpty()) "选择文件" else "重新选择文件") }
                                    selectedFiles[name]?.forEach { uri ->
                                        val displayName = remember(uri) { runCatching {
                                            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                                                if (cursor.moveToFirst()) cursor.getString(0) else uri.lastPathSegment
                                            }
                                        }.getOrNull() ?: uri.lastPathSegment.orEmpty() }
                                        Text("已选择：$displayName", style = MaterialTheme.typography.bodyMedium)
                                    }
                                    if (!selectedFiles[name].isNullOrEmpty()) TextButton(onClick = { invalidateRequest(); selectedFiles = selectedFiles - name }) { Text("清除文件") }
                                }
                            }
                        }
                    }
                    CardGroup {
                        item(
                            onClick = { advancedExpanded = !advancedExpanded },
                            headlineContent = { Text("高级选项") },
                            supportingContent = {
                                Text(if (writesData && useRawBody) "正在使用完整内容编辑" else "查看详细设置")
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(if (advancedExpanded) "收起" else "展开", style = MaterialTheme.typography.labelMedium)
                                    Icon(if (advancedExpanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01, null)
                                }
                            },
                        )
                        if (advancedExpanded) {
                            formItem(headlineContent = { Text("接口信息") }) {
                                SelectionContainer {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("${selected.string("method")} ${selected.string("path")}", style = MaterialTheme.typography.bodyMedium)
                                        Text("操作标识：${selected.string("id")}", style = MaterialTheme.typography.bodySmall)
                                        Text("原始标题：${selected.string("title")}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            failureDetails?.let { details ->
                                formItem(headlineContent = { Text("错误详情") }) {
                                    SelectionContainer { Text(details, style = MaterialTheme.typography.bodyMedium) }
                                }
                            }
                            if (writesData) {
                                if (bodySchema["properties"].objectOrEmpty().isNotEmpty()) {
                                    switchItem(
                                        checked = useRawBody,
                                        onCheckedChange = { invalidateRequest(); useRawBody = it },
                                        headlineContent = { Text("编辑完整 JSON") },
                                        supportingContent = { Text(if (useRawBody) "已开启，关闭后使用原生表单" else "按服务器要求直接填写完整内容") },
                                    )
                                }
                                if (editingRawBody) {
                                    formItem {
                                        OutlinedTextField(
                                            value = bodyDraft.text,
                                            onValueChange = { input ->
                                                invalidateRequest()
                                                val next = bodyDraft.edit(input)
                                                bodyDraft = next
                                                if (next.error == null) body = next.value ?: JsonObject(emptyMap())
                                            },
                                            label = { Text("完整内容 JSON") },
                                            minLines = 4,
                                            isError = bodyDraft.error != null,
                                            supportingText = { Text(bodyDraft.error ?: "按服务器要求填写；空白表示无额外字段") },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                                if (!selected.string("contentType").contains("multipart")) {
                                    switchItem(
                                        checked = manualMultipart,
                                        onCheckedChange = { invalidateRequest(); manualMultipart = it },
                                        headlineContent = { Text("附带文件上传") },
                                        supportingContent = { Text(if (manualMultipart) "已开启，请在上传文件中选择文件" else "需要随操作上传文件时开启") },
                                    )
                                }
                                if (multipart && fileFields(bodySchema).isEmpty()) {
                                    formItem {
                                        OutlinedTextField(
                                            value = fileField,
                                            onValueChange = { invalidateRequest(); fileField = it },
                                            label = { Text("上传字段名") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (writesData) {
                        CardGroup(title = { Text("执行前检查") }) {
                            item(
                                headlineContent = { Text(operationTitle(selected)) },
                                supportingContent = {
                                    Text(if (parameterValues.isEmpty()) "按上方填写的内容执行。" else "范围：${classRecordPlainText(parameterValues)}")
                                },
                            )
                            if (body != JsonObject(emptyMap())) {
                                item(
                                    onClick = { previewExpanded = !previewExpanded },
                                    headlineContent = { Text("预览填写内容") },
                                    trailingContent = { Icon(if (previewExpanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01, if (previewExpanded) "收起" else "展开") },
                                )
                                if (previewExpanded) formItem {
                                    SelectionContainer { Text(classRecordPlainText(body), style = MaterialTheme.typography.bodyMedium) }
                                }
                            }
                            if (multipart && selectedFiles.isNotEmpty()) item(headlineContent = { Text("已选择 ${selectedFiles.values.sumOf { it.size }} 个文件") })
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val draftError = classRecordRequestDraftError(parameterValidation.error, bodyValidation.error, bodyDraft, editingRawBody)
                        draftError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        Button(enabled = !busy && draftError == null, modifier = Modifier.fillMaxWidth(), onClick = {
                            prepareRequest()?.let { request ->
                                if (riskyOperation(selected)) pendingRequest = request else execute(request)
                            }
                        }) { Text(if (busy) "正在执行…" else if (!writesData) "读取" else "执行操作") }
                        if (busy) TextButton(onClick = { invalidateRequest() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("停止等待") }
                    }
                }
            }
        }
        result?.let { current ->
            item {
                CardGroup(title = { Text("操作结果") }) {
                    formItem {
                        ClassRecordResult(
                            current, onAttachText,
                            onTool = { name, arguments ->
                                if (!busy) runRequest { repository.call(name, arguments) }
                            },
                            onDownload = { id, entry, toWorkspace ->
                                if (!busy) runRequest(onSuccess = { response ->
                                    val file = response.objectOrEmpty()
                                    if (toWorkspace) {
                                        if (file["workspace_imported"] == JsonPrimitive(true)) {
                                            onNotice("文件已保存到工作区：${file.string("path")}")
                                        } else {
                                            failureDetails = file.string("workspace_error")
                                            val message = classRecordDisplayTitle(file.string("workspace_error"), "请检查工作区状态后重试")
                                            onNotice("文件已保存在本机，工作区导入失败：$message")
                                        }
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
                            Text(
                                when {
                                    rows.isEmpty() -> if (total != null) "本页没有记录，共 $total 条" else "本页没有记录"
                                    total != null -> "${offset + 1}–${(offset + rows.size).coerceAtMost(total)}，共 $total 条"
                                    else -> "从第 ${offset + 1} 条开始"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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
    }
    if (showGroupPicker) {
        AlertDialog(
            onDismissRequest = { showGroupPicker = false },
            title = { Text("功能分类") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    CardGroup {
                        (listOf<String?>(null) + groups).forEach { name ->
                            item(
                                onClick = { group = name; showGroupPicker = false },
                                headlineContent = { Text(name?.let(::groupTitle) ?: "全部功能") },
                                leadingContent = { RadioButton(selected = group == name, onClick = null) },
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showGroupPicker = false }) { Text("完成") } },
        )
    }
    pendingRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { pendingRequest = null },
            title = { Text(selected?.let(::operationTitle) ?: "确认操作") },
            text = {
                SelectionContainer {
                    Column(
                        modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("请确认操作范围和内容。")
                        Text("范围：${classRecordPlainText(request.scope).ifBlank { "当前操作" }}")
                        Text("内容：${request.body?.let(::classRecordPlainText) ?: "无"}")
                        if (request.multipart && request.files.isNotEmpty()) Text("已选择 ${request.files.values.sumOf { it.size }} 个文件")
                    }
                }
            },
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
