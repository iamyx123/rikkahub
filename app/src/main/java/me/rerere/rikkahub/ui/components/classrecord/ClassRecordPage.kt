package me.rerere.rikkahub.ui.components.classrecord

import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.rikkahub.data.classrecord.ClassRecordRepository
import me.rerere.rikkahub.data.classrecord.ClassRecordState
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ai.SheetHeader
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import org.koin.compose.koinInject
import java.time.LocalDate

@Composable
internal fun ClassRecordPage(
    assistant: Assistant,
    onUpdateAssistant: (Assistant) -> Unit,
    onBack: () -> Unit,
    onAttachText: (String) -> Unit,
    onAttachFile: (JsonObject) -> Unit,
) {
    val repository = koinInject<ClassRecordRepository>()
    val state by repository.state.collectAsState()
    var tab by remember { mutableStateOf(if (state.user == null) 0 else 1) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val userKey = state.user?.toString()
    LaunchedEffect(userKey) {
        tab = if (state.user == null) 0 else 1
        notice = null
    }
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.86f).padding(horizontal = 16.dp)) {
        SheetHeader(
            title = "Class Record",
            navigationIcon = { IconButton(onClick = onBack) { Icon(HugeIcons.ArrowLeft01, "返回") } },
            actions = {
                if (state.user != null) {
                    TextButton(enabled = !state.busy, onClick = {
                        scope.launch {
                            try { repository.refresh(); notice = "账号权限与功能目录已刷新" }
                            catch (error: Exception) { if (error is CancellationException) throw error; notice = error.message }
                        }
                    }) { Text("刷新") }
                }
            },
        )
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf("账号", "课程与资料", "控制面板").forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) }, enabled = index == 0 || state.user != null)
            }
        }
        (notice ?: state.error)?.let { message ->
            Text(message, modifier = Modifier.padding(vertical = 8.dp), color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        when (tab) {
            0 -> ClassRecordAccount(assistant, onUpdateAssistant, state, repository) { notice = it }
            1 -> androidx.compose.runtime.key(userKey) {
                ClassRecordLibrary(
                    state, repository, assistant.workspaceId?.toString(), onAttachText, onAttachFile,
                    onNotice = { notice = it },
                )
            }
            2 -> androidx.compose.runtime.key(userKey) {
                ClassRecordConsole(state, repository, assistant.workspaceId?.toString(), onAttachText, onAttachFile) { notice = it }
            }
        }
    }
}

@Composable
private fun ClassRecordAccount(
    assistant: Assistant,
    onUpdateAssistant: (Assistant) -> Unit,
    state: ClassRecordState,
    repository: ClassRecordRepository,
    onNotice: (String?) -> Unit,
) {
    var baseUrl by remember(state.baseUrl) { mutableStateOf(state.baseUrl) }
    var username by remember { mutableStateOf("") }
    // Passwords only live in the current composition, never saved in a Bundle or assistant settings.
    var password by remember { mutableStateOf("") }
    var rememberLogin by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CardGroup {
            switchItem(
                checked = assistant.enableClassRecord,
                onCheckedChange = { onUpdateAssistant(assistant.copy(enableClassRecord = it)) },
                headlineContent = { Text("允许 AI 调用 Class Record") },
                supportingContent = { Text("登录后，AI 可在当前账号权限内检索课程、读取完整转写和下载原件。") },
            )
        }
        val user = state.user
        if (user == null) {
            Text("登录学校空间", style = MaterialTheme.typography.titleLarge)
            Text("使用现有管理员、教师或学生账号登录。权限由服务器校验。", style = MaterialTheme.typography.bodyMedium)
            CardGroup {
                formItem {
                    OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("服务器 HTTPS 地址") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    OutlinedTextField(username, { username = it }, label = { Text("账号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(password, { password = it }, label = { Text("密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(rememberLogin, { rememberLogin = it })
                        Text("记住登录状态")
                    }
                }
            }
            Button(
                enabled = !state.busy && username.isNotBlank() && password.isNotEmpty(),
                onClick = {
                    scope.launch {
                        try {
                            repository.login(baseUrl, username, password, rememberLogin)
                            password = ""
                            onNotice("登录成功")
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            onNotice(error.message ?: "登录失败")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.busy) "正在登录…" else "登录") }
        } else {
            Text(user.string("displayName").ifBlank { user.string("username") }.ifBlank { "已登录" }, style = MaterialTheme.typography.titleLarge)
            val role = user.string("role")
            CardGroup(title = { Text("账号与权限范围") }) {
                item(headlineContent = { Text("角色") }, supportingContent = { Text(roleLabel(role)) })
                item(headlineContent = { Text("服务器") }, supportingContent = { SelectionContainer { Text(state.baseUrl) } })
                item(headlineContent = { Text("学校") }, supportingContent = { Text(user.string("schoolId").ifBlank { if (role == "system-admin") "系统范围，可按学校筛选" else "按账号权限" }) })
                item(headlineContent = { Text("班级") }, supportingContent = { Text(user["classIds"]?.let(::classRecordPlainText) ?: "按服务器授权范围") })
                item(headlineContent = { Text("可用功能") }, supportingContent = { Text("${state.tools.size} 个资料工具 · ${state.operations.size} 个控制面板操作") })
            }
            androidx.compose.runtime.key(user.toString()) { ClassRecordDefaultClass(state, repository, onNotice) }
            Text("资料页面读取已存内容。重新转写、AI 整理、发布和设备操作需在控制面板中选择具体操作后执行。", style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !state.busy, onClick = {
                scope.launch {
                    try { repository.logout(); password = ""; onNotice("已退出登录") }
                    catch (error: Exception) { if (error is CancellationException) throw error; onNotice(error.message) }
                }
            }) { Text("退出登录") }
        }
    }
}

private fun roleLabel(role: String): String = when (role) {
    "system-admin" -> "系统管理员"
    "school-admin" -> "学校管理员"
    "class-admin" -> "班级管理员"
    "teacher" -> "教师"
    "student" -> "学生"
    else -> role.ifBlank { "普通用户" }
}

internal data class ClassRecordContinuation(val name: String, val arguments: JsonObject, val marker: String)

internal fun classRecordContinuation(name: String, arguments: JsonObject, result: JsonElement): ClassRecordContinuation? {
    val objectResult = result.objectOrEmpty()
    val nextId = objectResult["metadata"].objectOrEmpty().string("next_id")
    if (nextId.isNotBlank()) return ClassRecordContinuation(name, arguments.withValue("id", JsonPrimitive(nextId)), nextId)
    val cursor = objectResult.string("next_cursor")
    if (cursor.isNotBlank()) return ClassRecordContinuation(name, arguments.withValue("cursor", JsonPrimitive(cursor)), cursor)
    return null
}

@Composable
private fun ClassRecordLibrary(
    state: ClassRecordState,
    repository: ClassRecordRepository,
    workspaceId: String?,
    onAttachText: (String) -> Unit,
    onAttachFile: (JsonObject) -> Unit,
    onNotice: (String?) -> Unit,
) {
    var selectedName by remember { mutableStateOf("list_courses") }
    val selected = state.tools.find { it.string("name") == selectedName } ?: state.tools.firstOrNull()
    val schema = remember(selected) { resolveClassRecordSchema(selected?.get("inputSchema").objectOrEmpty()) }
    val formValidation = remember(selected?.string("name")) { ClassRecordFormValidationState() }
    var selectedArguments by remember { mutableStateOf<Pair<String, JsonObject>?>(null) }
    var pages by remember { mutableStateOf<List<JsonElement>>(emptyList()) }
    var next by remember { mutableStateOf<ClassRecordContinuation?>(null) }
    var busy by remember { mutableStateOf(false) }
    var task by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableStateOf(0) }
    var quickSchool by remember(state.defaultSchoolId, state.defaultClassId) { mutableStateOf(state.defaultSchoolId.ifBlank { state.user?.string("schoolId").orEmpty() }) }
    var quickClass by remember(state.defaultSchoolId, state.defaultClassId) { mutableStateOf(state.defaultClassId) }
    var scopeExplicit by remember(state.defaultSchoolId, state.defaultClassId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scopedDefaults = JsonObject(schemaDefaults(schema) + buildMap {
        val properties = schema["properties"].objectOrEmpty()
        if ("school_id" in properties && quickSchool.isNotBlank()) put("school_id", JsonPrimitive(quickSchool))
        if ("class_id" in properties && quickClass.isNotBlank()) put("class_id", JsonPrimitive(quickClass))
    })
    val arguments = selectedArguments?.takeIf { it.first == selected?.string("name") }?.second ?: scopedDefaults

    LaunchedEffect(state.defaultSchoolId, state.defaultClassId) {
        generation++
        task?.cancel()
        busy = false
        pages = emptyList()
        next = null
        selectedArguments = null
    }

    fun runTool(name: String, input: JsonObject, append: Boolean = false, readAll: Boolean = false) {
        task?.cancel()
        val readGeneration = ++generation
        busy = true
        task = scope.launch {
            onNotice(null)
            if (!append) { pages = emptyList(); next = null }
            try {
                var currentArguments = input
                val seen = mutableSetOf<String>()
                do {
                    val result = repository.call(name, currentArguments)
                    if (readGeneration != generation) return@launch
                    pages = pages + result
                    next = classRecordContinuation(name, currentArguments, result)
                    val continuation = next
                    if (readAll && continuation != null) {
                        check(seen.add(continuation.marker)) { "服务器重复返回相同分页标记，请刷新后继续。" }
                        currentArguments = continuation.arguments
                    }
                } while (readAll && next != null)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (readGeneration == generation) onNotice(error.message ?: "读取失败")
            } finally { if (readGeneration == generation) busy = false }
        }
    }

    val download: (String, String?, Boolean) -> Unit = { resourceId, entryPath, toWorkspace ->
        task?.cancel()
        val readGeneration = ++generation
        busy = true
        task = scope.launch {
            try {
                val result = repository.download(resourceId, entryPath, if (toWorkspace) workspaceId else null)
                if (readGeneration != generation) return@launch
                if (toWorkspace) {
                    if (result["workspace_imported"] == JsonPrimitive(true)) onNotice("文件已保存到工作区：${result.string("path")}")
                    else {
                        pages = pages + result
                        onNotice("文件已保存在本机，工作区导入失败：${result.string("workspace_error").ifBlank { "请检查工作区状态后重试" }}")
                    }
                } else onAttachFile(result)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (readGeneration == generation) onNotice(error.message ?: "下载失败")
            } finally { if (readGeneration == generation) busy = false }
        }
    }
    fun openTool(name: String, input: JsonObject) {
        if (busy) return
        input.string("class_id").takeIf { it.isNotBlank() }?.let { quickClass = it }
        input.string("school_id").takeIf { it.isNotBlank() }?.let { quickSchool = it }
        if (input.string("class_id").isNotBlank() || input.string("school_id").isNotBlank()) scopeExplicit = true
        selectedName = name
        selectedArguments = name to input
        runTool(name, input)
    }
    fun changeScope(school: String = quickSchool, classroom: String = quickClass) {
        generation++
        task?.cancel()
        busy = false
        pages = emptyList()
        next = null
        quickSchool = school
        quickClass = classroom
        scopeExplicit = true
        selectedArguments = null
    }
    val combinedStructure = remember(pages, next) {
        if (pages.size > 1 && next == null) runCatching {
            Json.parseToJsonElement(pages.joinToString("") { it.objectOrEmpty().string("text") })
        }.getOrNull() else null
    }
    LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("课程、课件与原始转写", style = MaterialTheme.typography.titleLarge)
                Text("先用班级目录确认学校与班级编号，再检索资料。学生只会读到已授权发布的内容。", style = MaterialTheme.typography.bodySmall)
                CardGroup(title = { Text("快捷查找") }) {
                    formItem {
                        if (state.defaultClassId.isNotBlank()) Text("AI 默认班级：${state.defaultClassName.ifBlank { state.defaultClassId }} · ${state.defaultSchoolId}", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(quickSchool, { changeScope(school = it) }, label = { Text("学校编号（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(quickClass, { changeScope(classroom = it) }, label = { Text("班级编号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        TextButton(enabled = !busy && state.tools.any { it.string("name") == "list_classes" }, onClick = {
                            openTool("list_classes", if (quickSchool.isBlank()) JsonObject(emptyMap()) else JsonObject(mapOf("school_id" to JsonPrimitive(quickSchool))))
                        }) { Text("选择学校与班级") }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "get_latest_classroom_screenshot" to "最新教室截图",
                                "list_courses" to "课程目录",
                                "list_transcripts" to "课堂转写",
                                "list_materials" to "今日课件",
                            ).filter { (name, _) -> state.tools.any { it.string("name") == name } }.forEach { (name, title) ->
                                TextButton(enabled = !busy, onClick = {
                                    if (quickClass.isBlank()) onNotice("请先填写班级编号，或打开班级目录选择一个班级。")
                                    else {
                                        val input = linkedMapOf<String, JsonElement>("class_id" to JsonPrimitive(quickClass))
                                        if (quickSchool.isNotBlank()) input["school_id"] = JsonPrimitive(quickSchool)
                                        if (name == "list_materials") {
                                            input["date"] = JsonPrimitive(LocalDate.now().toString())
                                            input["date_basis"] = JsonPrimitive("uploaded")
                                        }
                                        openTool(name, JsonObject(input))
                                    }
                                }) { Text(title) }
                            }
                        }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.tools.forEach { tool ->
                        FilterChip(
                            selected = selected?.string("name") == tool.string("name"),
                            onClick = { if (!busy) { selectedName = tool.string("name"); pages = emptyList(); next = null } },
                            label = { Text(toolTitle(tool)) },
                            enabled = !busy,
                        )
                    }
                }
                if (selected == null) Text("没有可用资料工具，请在账号页刷新。")
                else {
                    Text(selected.string("description"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CardGroup {
                        formItem { ClassRecordSchemaForm(schema, arguments, {
                            generation++
                            task?.cancel()
                            busy = false
                            pages = emptyList()
                            next = null
                            if (it.string("school_id") != arguments.string("school_id") || it.string("class_id") != arguments.string("class_id")) scopeExplicit = true
                            val exactTarget = listOf("lesson_id", "resource_id", "id").any { key -> it.string(key).isNotBlank() }
                            val updated = if (exactTarget && !scopeExplicit) it.withValue("school_id", null).withValue("class_id", null) else it
                            if ("school_id" in schema["properties"].objectOrEmpty()) quickSchool = updated.string("school_id")
                            if ("class_id" in schema["properties"].objectOrEmpty()) quickClass = updated.string("class_id")
                            selectedArguments = selected.string("name") to updated
                        }, validationState = formValidation) }
                    }
                    Button(enabled = !busy && formValidation.error == null, modifier = Modifier.fillMaxWidth(), onClick = {
                        val failure = formValidation.error ?: validateClassRecordSchema(schema, arguments)
                        if (failure != null) onNotice(failure) else runTool(selected.string("name"), arguments)
                    }) { Text(if (busy) "正在读取…" else toolTitle(selected)) }
                }
                if (busy) TextButton(onClick = { generation++; task?.cancel(); busy = false }) { Text("停止读取，保留已读取内容") }
            }
        }
        if (pages.isNotEmpty()) item {
            Column {
                Text("已读取 ${pages.size} 页${if (next != null) "，仍有后续内容" else "，本次读取完成"}", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { onAttachText(pages.joinToString("\n\n") { classRecordPlainText(it) }) }) { Text("将已读取的全部内容加入聊天") }
            }
        }
        if (combinedStructure is JsonObject || combinedStructure is kotlinx.serialization.json.JsonArray) {
                item {
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text("合并后的完整资料结构", style = MaterialTheme.typography.titleMedium)
                            ClassRecordResult(
                                combinedStructure, onAttachText,
                                onTool = ::openTool,
                                onDownload = { id, entry, workspace -> if (!busy) download(id, entry, workspace) },
                                onAttachLocal = onAttachFile,
                                workspaceAvailable = workspaceId != null,
                                inheritedResourceId = pages.firstOrNull().objectOrEmpty()["metadata"].objectOrEmpty().string("resource_id").ifBlank { null },
                            )
                        }
                    }
                }
        }
        itemsIndexed(pages) { index, result ->
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("第 ${index + 1} 页", style = MaterialTheme.typography.labelLarge)
                    ClassRecordResult(
                        result, onAttachText,
                        onTool = ::openTool,
                        onDownload = { id, entry, workspace -> if (!busy) download(id, entry, workspace) },
                        onAttachLocal = onAttachFile, workspaceAvailable = workspaceId != null,
                    )
                }
            }
        }
        next?.let { continuation ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = { runTool(continuation.name, continuation.arguments, append = true) }) { Text("下一页") }
                    TextButton(enabled = !busy, onClick = { runTool(continuation.name, continuation.arguments, append = true, readAll = true) }) { Text("读取全部后续页") }
                }
            }
        }
        item { Column(Modifier.heightIn(min = 16.dp)) {} }
    }
}

private fun toolTitle(tool: JsonObject): String = when (tool.string("name")) {
    "list_classes" -> "学校与班级"
    "list_courses" -> "课程列表"
    "search" -> "搜索课程"
    "list_materials" -> "查找课件"
    "browse_materials" -> "原始目录"
    "fetch" -> "读取课程"
    "fetch_material" -> "读取资料"
    "get_material_download" -> "下载原件"
    "list_transcripts" -> "完整转写"
    "search_batch" -> "批量检索"
    "fetch_batch" -> "批量阅读"
    "get_latest_classroom_screenshot" -> "最新教室截图"
    else -> tool.string("title").ifBlank { tool.string("name") }
}
