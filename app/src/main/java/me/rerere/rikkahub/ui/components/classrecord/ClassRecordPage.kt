package me.rerere.rikkahub.ui.components.classrecord

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Tab
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiSearch02
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.FileAudio
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Login03
import me.rerere.hugeicons.stroke.Logout03
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.UserCircle
import me.rerere.rikkahub.data.classrecord.ClassRecordRepository
import me.rerere.rikkahub.data.classrecord.ClassRecordState
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ai.SheetHeader
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
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
                if (state.user != null || state.error != null) {
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
            var expanded by remember(message) { mutableStateOf(false) }
            val summary = classRecordDisplayTitle(message.lineSequence().firstOrNull().orEmpty().take(160), "操作未完成，请检查连接后重试")
            CardGroup(modifier = Modifier.padding(top = 8.dp)) {
                item(
                    onClick = if (summary != message) ({ expanded = !expanded }) else null,
                    overlineContent = { Text(if (notice == null && state.error != null) "暂时无法完成" else "提示") },
                    headlineContent = { Text(summary, style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = if (summary != message) ({ Icon(if (expanded) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01, contentDescription = if (expanded) "收起详情" else "展开详情") }) else null,
                )
                if (expanded) {
                    formItem(headlineContent = { Text("详细信息") }) {
                        SelectionContainer {
                            Text(
                                message,
                                modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
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
    var showAccountScope by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val user = state.user
        if (user == null) {
            CardGroup(title = { Text("学校账号") }) {
                item(
                    leadingContent = { CardGroupIcon(HugeIcons.UserCircle) },
                    headlineContent = { Text("登录学校空间") },
                    supportingContent = { Text("使用管理员、教师或学生账号登录") },
                )
                formItem(headlineContent = { Text("服务器") }) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        placeholder = { Text("https://学校服务器地址") },
                        singleLine = true,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }
                formItem {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("账号") },
                        singleLine = true,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        singleLine = true,
                        enabled = !state.busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                switchItem(
                    checked = rememberLogin,
                    onCheckedChange = { rememberLogin = it },
                    enabled = !state.busy,
                    headlineContent = { Text("记住登录状态") },
                    supportingContent = { Text(if (rememberLogin) "已开启，下次打开时保留登录" else "已关闭，仅在本次使用") },
                )
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
            ) {
                Icon(HugeIcons.Login03, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.busy) "正在登录…" else "登录")
            }
        } else {
            val role = user.string("role")
            CardGroup(title = { Text("学校账号") }) {
                item(
                    leadingContent = { CardGroupIcon(HugeIcons.UserCircle) },
                    headlineContent = { Text(user.string("displayName").ifBlank { user.string("username") }.ifBlank { "学校账号" }) },
                    supportingContent = { Text(roleLabel(role)) },
                    trailingContent = { CardGroupValue("已登录") },
                )
                item(
                    headlineContent = { Text("访问范围") },
                    supportingContent = {
                        Text(if (role == "system-admin") "可切换学校与班级" else "可查看当前账号获授权的学校与班级")
                    },
                )
                item(
                    headlineContent = { Text("可用功能") },
                    supportingContent = { Text("${state.tools.size} 项资料功能 · ${state.operations.size} 项管理操作") },
                )
                item(
                    onClick = { showAccountScope = !showAccountScope },
                    headlineContent = { Text("连接详情") },
                    trailingContent = { Icon(if (showAccountScope) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01, contentDescription = if (showAccountScope) "收起" else "展开") },
                )
                if (showAccountScope) {
                    item(
                        headlineContent = { Text("服务器地址") },
                        supportingContent = { SelectionContainer { Text(state.baseUrl) } },
                    )
                    item(
                        headlineContent = { Text("学校编号") },
                        supportingContent = { SelectionContainer { Text(user.string("schoolId").ifBlank { "按账号授权范围" }) } },
                    )
                    item(
                        headlineContent = { Text("班级编号") },
                        supportingContent = { SelectionContainer { Text(user["classIds"]?.let(::classRecordPlainText) ?: "按账号授权范围") } },
                    )
                }
            }
            androidx.compose.runtime.key(user.toString()) { ClassRecordDefaultClass(state, repository, onNotice) }
        }
        CardGroup(title = { Text("对话中使用") }) {
            switchItem(
                checked = assistant.enableClassRecord,
                onCheckedChange = { onUpdateAssistant(assistant.copy(enableClassRecord = it)) },
                leadingContent = { CardGroupIcon(HugeIcons.AiSearch02) },
                headlineContent = { Text("允许 AI 调用 Class Record") },
                supportingContent = {
                    Text(when {
                        !assistant.enableClassRecord -> "已关闭"
                        user == null -> "已开启，登录后生效"
                        else -> "已开启，可在当前账号权限内查找课程、阅读转写和下载资料"
                    })
                },
            )
        }
        if (user != null) {
            CardGroup {
                item(
                    onClick = if (state.busy) null else ({
                        scope.launch {
                            try { repository.logout(); password = ""; onNotice("已退出登录") }
                            catch (error: Exception) { if (error is CancellationException) throw error; onNotice(error.message) }
                        }
                    }),
                    leadingContent = { CardGroupIcon(HugeIcons.Logout03) },
                    headlineContent = { Text("退出登录") },
                )
            }
            Text(
                "重新转写、整理课程、发布资料和设备操作，请进入控制面板。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

private fun roleLabel(role: String): String = when (role) {
    "system-admin" -> "系统管理员"
    "school-admin" -> "学校管理员"
    "class-admin" -> "班级管理员"
    "teacher" -> "教师"
    "student" -> "学生"
    else -> role.takeIf { it.any { character -> character in '\u4e00'..'\u9fff' } } ?: "普通用户"
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
    var showScopeEditor by remember { mutableStateOf(false) }
    var showToolPicker by remember { mutableStateOf(false) }
    var showToolDetails by remember { mutableStateOf(false) }
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
    if (showToolPicker) {
        AlertDialog(
            onDismissRequest = { showToolPicker = false },
            title = { Text("选择资料功能") },
            text = {
                LazyColumn(
                    Modifier.heightIn(max = 420.dp).selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    itemsIndexed(state.tools, key = { _, tool -> tool.string("name") }) { index, tool ->
                        val isSelected = selected?.string("name") == tool.string("name")
                        SegmentedListItem(
                            onClick = {
                                selectedName = tool.string("name")
                                pages = emptyList()
                                next = null
                                showToolDetails = false
                                showToolPicker = false
                            },
                            enabled = !busy,
                            modifier = Modifier.semantics { role = Role.RadioButton; this.selected = isSelected },
                            shapes = ListItemDefaults.segmentedShapes(index = index, count = state.tools.size),
                            trailingContent = { RadioButton(selected = isSelected, onClick = null) },
                        ) {
                            Text(toolTitle(tool, "其他资料功能 ${index + 1}"))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showToolPicker = false }) { Text("完成") } },
        )
    }
    LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CardGroup(title = { Text("资料范围") }) {
                    item(
                        onClick = if (!busy && state.tools.any { it.string("name") == "list_classes" }) ({
                            openTool("list_classes", if (quickSchool.isBlank()) JsonObject(emptyMap()) else JsonObject(mapOf("school_id" to JsonPrimitive(quickSchool))))
                        }) else null,
                        leadingContent = { CardGroupIcon(HugeIcons.Book03) },
                        headlineContent = {
                            Text(when {
                                quickClass.isBlank() -> "选择查询班级"
                                quickClass == state.defaultClassId && quickSchool == state.defaultSchoolId -> state.defaultClassName.ifBlank { "默认班级" }
                                else -> "已指定查询班级"
                            })
                        },
                        supportingContent = { Text(if (quickClass.isBlank()) "从当前账号可访问的班级中选择" else "点此切换学校与班级") },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                    )
                    item(
                        onClick = { showScopeEditor = !showScopeEditor },
                        headlineContent = { Text("按编号指定范围") },
                        trailingContent = { Icon(if (showScopeEditor) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01, contentDescription = if (showScopeEditor) "收起" else "展开") },
                    )
                    if (showScopeEditor) {
                        formItem {
                            OutlinedTextField(quickSchool, { changeScope(school = it) }, label = { Text("学校编号（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(quickClass, { changeScope(classroom = it) }, label = { Text("班级编号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                CardGroup(title = { Text("常用资料") }) {
                    listOf(
                        Triple("list_courses", "课程目录", HugeIcons.Book03),
                        Triple("list_transcripts", "课堂转写", HugeIcons.FileAudio),
                        Triple("list_materials", "今日课件", HugeIcons.Folder01),
                        Triple("get_latest_classroom_screenshot", "教室截图", HugeIcons.Image02),
                    ).filter { (name, _, _) -> state.tools.any { it.string("name") == name } }.forEach { (name, title, icon) ->
                        item(
                            onClick = if (busy) null else ({
                                if (quickClass.isBlank()) onNotice("请先选择查询班级。")
                                else {
                                    val input = linkedMapOf<String, JsonElement>("class_id" to JsonPrimitive(quickClass))
                                    if (quickSchool.isNotBlank()) input["school_id"] = JsonPrimitive(quickSchool)
                                    if (name == "list_materials") {
                                        input["date"] = JsonPrimitive(LocalDate.now().toString())
                                        input["date_basis"] = JsonPrimitive("uploaded")
                                    }
                                    openTool(name, JsonObject(input))
                                }
                            }),
                            leadingContent = { CardGroupIcon(icon) },
                            headlineContent = { Text(title) },
                            trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                        )
                    }
                }
                if (selected == null) {
                    CardGroup {
                        item(
                            headlineContent = { Text("暂无可用资料功能") },
                            supportingContent = { Text("请在账号页刷新权限后重试") },
                        )
                    }
                } else {
                    val selectedTitle = toolTitle(selected, "其他资料功能 ${state.tools.indexOf(selected) + 1}")
                    CardGroup(title = { Text("资料功能") }) {
                        item(
                            onClick = if (busy) null else ({ showToolPicker = true }),
                            leadingContent = { CardGroupIcon(HugeIcons.Search01) },
                            headlineContent = { Text(selectedTitle) },
                            supportingContent = { Text("点此选择其他资料功能，共 ${state.tools.size} 项") },
                            trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                        )
                    }
                    CardGroup(title = { Text("查询条件") }) {
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
                    }) { Text(if (busy) "正在读取…" else "查询资料") }
                    CardGroup {
                        item(
                            onClick = { showToolDetails = !showToolDetails },
                            headlineContent = { Text("功能详情") },
                            trailingContent = { Icon(if (showToolDetails) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01, contentDescription = if (showToolDetails) "收起" else "展开") },
                        )
                        if (showToolDetails) {
                            item(
                                headlineContent = { Text("功能标识") },
                                supportingContent = { SelectionContainer { Text(selected.string("name")) } },
                            )
                            if (selected.string("description").isNotBlank()) {
                                item(
                                    headlineContent = { Text("服务器功能说明") },
                                    supportingContent = { SelectionContainer { Text(selected.string("description")) } },
                                )
                            }
                        }
                    }
                }
                if (busy) TextButton(onClick = { generation++; task?.cancel(); busy = false }) { Text("停止读取，保留已读取内容") }
            }
        }
        if (pages.isNotEmpty()) item {
            CardGroup(title = { Text("查询结果") }) {
                item(
                    onClick = { onAttachText(pages.joinToString("\n\n") { classRecordPlainText(it) }) },
                    headlineContent = { Text("将已读内容加入聊天") },
                    supportingContent = { Text("已读取 ${pages.size} 页${if (next != null) "，还有后续内容" else "，本次读取完成"}") },
                    trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                )
            }
        }
        if (combinedStructure is JsonObject || combinedStructure is kotlinx.serialization.json.JsonArray) {
            item {
                CardGroup(title = { Text("完整资料") }) {
                    formItem {
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
            CardGroup(title = { Text("第 ${index + 1} 页") }) {
                formItem {
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

private fun toolTitle(tool: JsonObject, fallback: String): String = when (tool.string("name")) {
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
    else -> classRecordDisplayTitle(tool.string("title"), fallback)
}
