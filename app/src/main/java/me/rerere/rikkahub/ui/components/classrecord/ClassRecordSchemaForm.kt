package me.rerere.rikkahub.ui.components.classrecord

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal val classRecordJson = Json { prettyPrint = true }

/** Keep an invalid draft visible while the caller continues to hold its last valid structure. */
internal data class ClassRecordJsonDraft(
    val text: String,
    val value: JsonElement?,
    val error: String? = null,
    val technicalError: String? = null,
) {
    fun edit(input: String, required: Boolean = false): ClassRecordJsonDraft = try {
        copy(text = input, value = if (input.isBlank() && !required) null else Json.parseToJsonElement(input), error = null, technicalError = null)
    } catch (failure: Exception) {
        copy(text = input, error = "格式无效，请检查括号、引号和逗号。", technicalError = failure.message)
    }

    companion object {
        fun fromForm(value: JsonElement?): ClassRecordJsonDraft = ClassRecordJsonDraft(
            text = value?.let { classRecordJson.encodeToString(JsonElement.serializer(), it) }.orEmpty(),
            value = value,
        )
    }
}

/** Shared by every nested field, so request preparation can synchronously reject invalid drafts. */
internal class ClassRecordFormValidationState {
    private var errors by mutableStateOf<Map<Any, String>>(emptyMap())
    val error: String? get() = errors.values.firstOrNull()

    fun update(owner: Any, error: String?) {
        errors = if (error == null) errors - owner else errors + (owner to error)
    }
}

internal fun classRecordRequestDraftError(
    parameterError: String?,
    bodyFormError: String?,
    bodyDraft: ClassRecordJsonDraft,
    editingRawBody: Boolean,
): String? = parameterError ?: if (editingRawBody) bodyDraft.error else bodyFormError

private val LocalClassRecordFormValidation = staticCompositionLocalOf<ClassRecordFormValidationState?> { null }
private val LocalClassRecordTechnicalDetails = staticCompositionLocalOf { false }
internal fun JsonElement?.objectOrEmpty() = this as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonElement?.arrayOrEmpty() = this as? JsonArray ?: JsonArray(emptyList())
internal fun JsonElement?.stringOrEmpty() = (this as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.string(key: String) = this[key].stringOrEmpty()
internal fun JsonObject.withValue(key: String, value: JsonElement?): JsonObject =
    JsonObject(toMutableMap().apply { if (value == null) remove(key) else put(key, value) })

internal fun schemaDefaults(schema: JsonObject): JsonObject = JsonObject(
    schema["properties"].objectOrEmpty().mapNotNull { (name, raw) ->
        raw.objectOrEmpty()["default"]?.let { name to it }
    }.toMap()
)

/** System.Text.Json schema exports use local references and nullable type arrays. */
internal fun resolveClassRecordSchema(schema: JsonObject): JsonObject {
    val schemaKeywords = setOf("items", "prefixItems", "additionalProperties", "patternProperties", "allOf", "anyOf", "oneOf", "not", "if", "then", "else", "contains")
    val schemaMaps = setOf("properties", "patternProperties", "dependentSchemas")
    fun resolve(element: JsonElement, depth: Int, ancestors: Set<String>): JsonElement {
        if (depth > 20) return JsonObject(mapOf("type" to JsonPrimitive("object")))
        if (element is JsonArray) return JsonArray(element.map { resolve(it, depth + 1, ancestors) })
        if (element !is JsonObject) return element
        val reference = element.string("\$ref")
        val source = if (reference.startsWith("#/")) {
            val referenced = reference.removePrefix("#/").split('/').fold(schema as JsonElement?) { node, key ->
                (node as? JsonObject)?.get(key.replace("~1", "/").replace("~0", "~"))
            }.objectOrEmpty()
            JsonObject((if (reference in ancestors) referenced.filterKeys { it == "type" } else referenced) + element.filterKeys { it != "\$ref" })
        } else element
        val resolved = source.mapValues { (key, child) ->
            val resolving = if (reference.isNotBlank()) ancestors + reference else ancestors
            when {
                key in schemaMaps && child is JsonObject -> JsonObject(child.mapValues { resolve(it.value, depth + 1, resolving) })
                key in schemaKeywords -> resolve(child, depth + 1, resolving)
                else -> child
            }
        }.toMutableMap()
        val types = resolved["type"] as? JsonArray
        if (types != null) {
            resolved["type"] = types.firstOrNull { it.stringOrEmpty() != "null" } ?: JsonPrimitive("null")
            if (types.any { it.stringOrEmpty() == "null" }) resolved["x-classrecord-nullable"] = JsonPrimitive(true)
        }
        val inherited = resolved["allOf"].arrayOrEmpty().map { it.objectOrEmpty() }
        if (inherited.isNotEmpty()) {
            val properties = inherited.fold(emptyMap<String, JsonElement>()) { acc, item -> acc + item["properties"].objectOrEmpty() } + resolved["properties"].objectOrEmpty()
            if (properties.isNotEmpty()) {
                resolved["type"] = JsonPrimitive("object")
                resolved["properties"] = JsonObject(properties)
                resolved["required"] = JsonArray((inherited.flatMap { it["required"].arrayOrEmpty() } + resolved["required"].arrayOrEmpty()).distinct())
            }
        }
        return JsonObject(resolved.filterKeys { it != "\$defs" && it != "definitions" })
    }
    return resolve(schema, 0, emptySet()).objectOrEmpty()
}

/** JSON Schema controls share the exact schema advertised by the authenticated server. */
@Composable
internal fun ClassRecordSchemaForm(
    schema: JsonObject,
    value: JsonObject,
    onChange: (JsonObject) -> Unit,
    modifier: Modifier = Modifier,
    validationState: ClassRecordFormValidationState? = null,
    showTechnicalDetails: Boolean = false,
) {
    val inheritedValidation = LocalClassRecordFormValidation.current
    val technicalDetails = LocalClassRecordTechnicalDetails.current || showTechnicalDetails
    val ownValidation = remember(schema) { ClassRecordFormValidationState() }
    val validation = validationState ?: inheritedValidation ?: ownValidation
    val required = schema["required"].arrayOrEmpty().map { it.stringOrEmpty() }.toSet()
    CompositionLocalProvider(
        LocalClassRecordFormValidation provides validation,
        LocalClassRecordTechnicalDetails provides technicalDetails,
    ) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            schema["properties"].objectOrEmpty().entries.forEachIndexed { index, (name, raw) ->
                val field = raw.objectOrEmpty()
                SchemaField(
                    label = classRecordFieldTitle(name, field, "参数 ${index + 1}") + if (name in required) " *" else "",
                    schema = field,
                    value = value[name],
                    required = name in required,
                    onChange = { onChange(value.withValue(name, it)) },
                    name = name,
                )
            }
        }
    }
}

@Composable
private fun SchemaField(
    label: String,
    schema: JsonObject,
    value: JsonElement?,
    required: Boolean,
    onChange: (JsonElement?) -> Unit,
    name: String? = null,
) {
    val validation = LocalClassRecordFormValidation.current
    val variants = (schema["oneOf"] ?: schema["anyOf"]).arrayOrEmpty()
    var variantIndex by remember(schema, validation) { mutableStateOf(0) }
    val effective = if (variants.isNotEmpty()) variants.getOrNull(variantIndex).objectOrEmpty() else schema
    val type = effective.string("type").ifBlank {
        when {
            effective["properties"] is JsonObject -> "object"
            effective["items"] != null -> "array"
            else -> "string"
        }
    }
    val choices = effective["enum"].arrayOrEmpty()
    var showJson by remember(schema, validation) { mutableStateOf(false) }
    var jsonDraft by remember(schema, validation) { mutableStateOf(ClassRecordJsonDraft.fromForm(value)) }
    val showTechnicalDetails = LocalClassRecordTechnicalDetails.current
    val validationOwner = remember(validation) { Any() }
    fun reportDraftError(error: String?) = validation?.update(validationOwner, error?.let { "$label：$it" })
    DisposableEffect(validation) {
        onDispose { validation?.update(validationOwner, null) }
    }
    LaunchedEffect(validation, showJson, jsonDraft.error) { reportDraftError(if (showJson) jsonDraft.error else null) }
    LaunchedEffect(value) {
        if (!showJson && jsonDraft.error == null) jsonDraft = ClassRecordJsonDraft.fromForm(value)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (variants.isNotEmpty()) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                variants.forEachIndexed { index, variant ->
                    ClassRecordChoiceChip(
                        selected = variantIndex == index,
                        onClick = { variantIndex = index; onChange(if (variant.objectOrEmpty().string("type") == "null") JsonNull else null) },
                        label = { Text(classRecordDisplayTitle(variant.objectOrEmpty().string("title"), "格式 ${index + 1}")) },
                    )
                }
            }
        }
        when {
            showJson -> {
                OutlinedTextField(
                    value = jsonDraft.text,
                    onValueChange = { input ->
                        val next = jsonDraft.edit(input, required)
                        jsonDraft = next
                        reportDraftError(next.error)
                        if (next.error == null) onChange(next.value)
                    },
                    label = { Text("$label（高级编辑）") },
                    minLines = 3,
                    isError = jsonDraft.error != null,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(jsonDraft.error ?: "输入完整 JSON；返回表单可继续逐项编辑") },
                )
                TextButton(onClick = { showJson = false; reportDraftError(null) }) { Text("返回表单") }
            }
            choices.isNotEmpty() -> {
                Text(label, style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEachIndexed { index, choice ->
                        ClassRecordChoiceChip(
                            selected = value == choice,
                            onClick = { onChange(if (value == choice && !required) null else choice) },
                            label = { Text(schemaChoiceLabel(choice, index)) },
                        )
                    }
                }
            }
            type == "null" -> Text("$label：空值", style = MaterialTheme.typography.bodyMedium)
            type == "boolean" -> Column {
                Row(
                    modifier = Modifier.fillMaxWidth().toggleable(
                        value = (value as? JsonPrimitive)?.booleanOrNull == true,
                        role = Role.Switch,
                        onValueChange = { onChange(JsonPrimitive(it)) },
                    ).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (value == null) "未设置，使用服务器默认值" else if (value == JsonPrimitive(true)) "是" else "否",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = (value as? JsonPrimitive)?.booleanOrNull == true,
                        onCheckedChange = null,
                    )
                }
                if (!required && value != null) TextButton(onClick = { onChange(null) }) { Text("恢复默认") }
            }
            type == "object" -> {
                Text(label, style = MaterialTheme.typography.titleSmallEmphasized)
                if (effective["properties"].objectOrEmpty().isNotEmpty()) {
                    ClassRecordSchemaForm(effective, value.objectOrEmpty(), { onChange(it) }, Modifier.padding(start = 12.dp))
                }
                TextButton(onClick = { showJson = true }) { Text("高级编辑") }
            }
            type == "array" -> {
                Text(label, style = MaterialTheme.typography.labelLarge)
                val items = value.arrayOrEmpty()
                items.forEachIndexed { index, item ->
                    Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SchemaField(
                            label = "第 ${index + 1} 项",
                            schema = effective["items"].objectOrEmpty(),
                            value = item,
                            required = true,
                            onChange = { updated -> onChange(JsonArray(items.toMutableList().apply { set(index, updated ?: JsonNull) })) },
                        )
                        TextButton(onClick = { onChange(JsonArray(items.filterIndexed { i, _ -> i != index })) }) { Text("移除此项") }
                    }
                }
                Row {
                    TextButton(onClick = {
                        val itemSchema = effective["items"].objectOrEmpty()
                        val item = when (itemSchema.string("type")) {
                            "object" -> schemaDefaults(itemSchema)
                            "boolean" -> JsonPrimitive(false)
                            "array" -> JsonArray(emptyList())
                            else -> itemSchema["default"] ?: JsonPrimitive("")
                        }
                        onChange(JsonArray(items + item))
                    }) { Text("添加一项") }
                    TextButton(onClick = { showJson = true }) { Text("高级编辑") }
                }
            }
            effective.string("format") == "binary" -> Text("$label：在下方选择上传文件", style = MaterialTheme.typography.bodySmall)
            else -> OutlinedTextField(
                value = value.stringOrEmpty(),
                onValueChange = { input ->
                    onChange(
                        when {
                            input.isEmpty() && !required -> null
                            type == "integer" -> input.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(input)
                            type == "number" -> input.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(input)
                            else -> JsonPrimitive(input)
                        }
                    )
                },
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
                minLines = if (effective.string("format") == "multiline" || label.contains("文本") || label.contains("提示词")) 3 else 1,
                keyboardOptions = KeyboardOptions(keyboardType = if (type in listOf("integer", "number")) KeyboardType.Decimal else KeyboardType.Text),
            )
        }
        val description = effective.string("description")
        val help = classRecordDisplayTitle(description, schemaFieldHelp[name].orEmpty())
        if (help.isNotBlank()) {
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val details = buildList {
            name?.let { add("字段：$it") }
            if (description.isNotBlank() && description != help) add("服务器说明：$description")
            if (choices.isNotEmpty()) add("选项对应值：" + choices.mapIndexed { index, choice -> "${schemaChoiceLabel(choice, index)} = $choice" }.joinToString("；"))
            if (showJson) jsonDraft.technicalError?.let { add("格式检查详情：$it") }
        }
        if (showTechnicalDetails && details.isNotEmpty()) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(details.joinToString("\n\n"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Return the first useful validation failure before sending an authenticated mutation. */
internal fun validateClassRecordSchema(schema: JsonObject, value: JsonElement?, path: String = "表单"): String? {
    if (value == null) return null
    val variants = (schema["oneOf"] ?: schema["anyOf"]).arrayOrEmpty()
    if (variants.isNotEmpty()) {
        if (variants.any { validateClassRecordSchema(it.objectOrEmpty(), value, path) == null }) return null
        return "$path 不符合任何一种允许的格式"
    }
    schema["allOf"].arrayOrEmpty().forEach { branch ->
        validateClassRecordSchema(branch.objectOrEmpty(), value, path)?.let { return it }
    }
    if (value == JsonNull) {
        return if (schema.string("type") == "null" || (schema["x-classrecord-nullable"] as? JsonPrimitive)?.booleanOrNull == true ||
            (schema["type"] is JsonArray && schema["type"].arrayOrEmpty().any { it.stringOrEmpty() == "null" }) || schema.isEmpty()) null
        else "$path 不允许为空值"
    }
    val primitive = value as? JsonPrimitive
    when (schema.string("type").ifBlank { if (schema["properties"] is JsonObject) "object" else "" }) {
        "null" -> return "$path 必须为空值"
        "object" -> {
            val objectValue = value as? JsonObject ?: return "$path 必须是对象"
            schema["required"].arrayOrEmpty().forEach { required ->
                val key = required.stringOrEmpty()
                if (objectValue[key] == null) return "$path / ${fieldLabel(key)} 为必填项"
            }
            schema["properties"].objectOrEmpty().forEach { (key, field) ->
                validateClassRecordSchema(field.objectOrEmpty(), objectValue[key], "$path / ${fieldLabel(key)}")?.let { return it }
            }
            if (schema["additionalProperties"] == JsonPrimitive(false)) {
                val unknown = objectValue.keys - schema["properties"].objectOrEmpty().keys
                if (unknown.isNotEmpty()) return "$path 包含未定义字段：${unknown.joinToString()}"
            }
        }
        "array" -> {
            val array = value as? JsonArray ?: return "$path 必须是数组"
            val min = (schema["minItems"] as? JsonPrimitive)?.longOrNull
            val max = (schema["maxItems"] as? JsonPrimitive)?.longOrNull
            if (min != null && array.size < min || max != null && array.size > max) return "$path 项数超出允许范围"
            array.forEachIndexed { index, element ->
                validateClassRecordSchema(schema["items"].objectOrEmpty(), element, "$path [${index + 1}]")?.let { return it }
            }
        }
        "boolean" -> if (primitive?.booleanOrNull == null || primitive.isString) return "$path 必须为是或否"
        "integer", "number" -> {
            if (primitive == null || primitive.isString || primitive.doubleOrNull == null) return "$path 必须是数字"
            if (schema.string("type") == "integer" && primitive.longOrNull == null) return "$path 必须是整数"
            val number = primitive.doubleOrNull!!
            val min = (schema["minimum"] as? JsonPrimitive)?.doubleOrNull
            val max = (schema["maximum"] as? JsonPrimitive)?.doubleOrNull
            if (min != null && number < min || max != null && number > max) return "$path 超出允许范围"
        }
        "string" -> {
            if (primitive?.isString != true) return "$path 必须是文本"
            val min = (schema["minLength"] as? JsonPrimitive)?.longOrNull
            val max = (schema["maxLength"] as? JsonPrimitive)?.longOrNull
            if (min != null && primitive.content.length < min || max != null && primitive.content.length > max) return "$path 文本长度超出允许范围"
        }
    }
    val choices = schema["enum"].arrayOrEmpty()
    if (choices.isNotEmpty() && value !in choices) return "$path 不在允许的选项中"
    schema["const"]?.let { if (value != it) return "$path 不符合固定值要求" }
    return null
}

internal fun fieldLabel(name: String): String = fieldLabels[name] ?: name

/** Presentation only: server identifiers and original content remain unchanged. */
internal fun classRecordDisplayTitle(title: String, fallback: String): String {
    val text = title.trim()
    val chinese = text.count { it in '\u3400'..'\u9fff' }
    val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
    return text.takeIf { chinese > 0 && chinese * 2 >= latin } ?: fallback
}

internal fun classRecordFieldTitle(
    name: String,
    schema: JsonObject = JsonObject(emptyMap()),
    fallback: String = "其他参数",
): String = classRecordDisplayTitle(
    schema.string("title"),
    normalizedFieldLabels[name.filter { it != '_' && it != '-' }.lowercase()]
        ?: classRecordDisplayTitle(name, fallback),
)

internal fun classRecordDisplayValue(name: String, value: JsonElement): String {
    val primitive = value as? JsonPrimitive ?: return classRecordPlainText(value)
    if (primitive == JsonNull) return "无"
    if (!primitive.isString) return when (primitive.booleanOrNull) { true -> "是"; false -> "否"; null -> primitive.content }
    if (name in classRecordCodeFields) {
        if (name == "origin" && primitive.content == "uploaded") return "课堂上传"
        return schemaChoiceLabels[primitive.content] ?: classRecordDisplayTitle(primitive.content, "其他状态或类型")
    }
    if (name == "error") return classRecordDisplayTitle(primitive.content, "操作未完成，请展开技术信息查看详情。")
    return primitive.content
}

internal val classRecordCodeFields = setOf("role", "status", "state", "mode", "kind", "format", "origin", "date_basis", "change_type")

private fun schemaChoiceLabel(choice: JsonElement, index: Int): String {
    val value = choice.stringOrEmpty()
    return when {
        choice == JsonNull -> "空值"
        (choice as? JsonPrimitive)?.isString == false -> when (value) { "true" -> "是"; "false" -> "否"; else -> value }
        else -> schemaChoiceLabels[value] ?: classRecordDisplayTitle(value, "选项 ${index + 1}")
    }
}

private val schemaFieldHelp = mapOf(
    "query" to "在标题、学科、正文和转写中查找；留空可查看全部。",
    "cursor" to "继续上次查询时填写下一页标记，其他筛选条件保持不变。",
    "school_id" to "可留空；学校账号只能访问已授权的学校。",
    "class_id" to "填写班级编号或班号；同名班级需要先选择学校。",
    "subject" to "填写准确的学科名称，例如数学。",
    "state" to "按课程状态筛选；留空查看全部。",
    "updated_since" to "只查询指定时间之后的变更，需包含时区。",
    "from" to "查询开始日期或时间；留空不限历史范围。",
    "to" to "查询结束日期或时间；留空不限结束时间。",
    "limit" to "每页最多显示的条数，可继续加载后续页。",
    "filename_date" to "只按原始文件名中的日期查找，不使用上传或课堂日期。",
    "date_basis" to "选择日期对应上传时间、课堂使用时间还是原始文件名。",
    "source_subject" to "按原始资料所在文件夹或存储盘的学科名称查找。",
    "source" to "指定资料来源；留空查询全部已授权来源。",
    "path" to "可指定文件夹；查询会包含其下的所有子文件夹。",
    "mode" to "选择当前功能使用的读取或处理方式。",
    "resource_id" to "填写查询结果中的文件编号。",
    "entry_path" to "选择压缩包成员时填写内部路径；留空下载整个原文件。",
)

private val schemaChoiceLabels = mapOf(
    "body" to "课程正文", "full" to "完整档案", "uploaded" to "上传时间", "activity" to "课堂使用时间", "filename" to "原始文件名",
    "courseware" to "课件资料", "screenshots" to "课堂截图", "auxiliary" to "辅助文件", "mounted" to "挂载资料库",
    "board-snapshot" to "展台图片", "presentation-annotation" to "课件批注", "classroom-screen" to "教室屏幕",
    "presentation" to "演示文稿", "pdf" to "便携文档", "document" to "文本文档", "spreadsheet" to "电子表格",
    "image" to "图片", "video" to "视频", "audio" to "音频", "archive" to "压缩包", "other" to "其他",
    "ready" to "已整理", "processing" to "处理中", "attention" to "待检查", "published" to "已发布", "candidate" to "待确认",
    "name" to "按名称", "latest" to "最新在前", "oldest" to "最早在前", "uploaded-oldest" to "最早上传在前",
    "admin" to "管理员", "system-admin" to "系统管理员", "school-admin" to "学校管理员", "class-admin" to "班级管理员", "teacher" to "教师", "student" to "学生",
    "regular" to "普通班", "special" to "特长班", "key" to "重点班", "fast" to "快速识别", "realtime" to "实时识别",
    "staff" to "教职工", "pending" to "待处理", "running" to "运行中", "completed" to "已完成", "done" to "已完成",
    "failed" to "失败", "error" to "异常", "cancelled" to "已取消", "canceled" to "已取消", "queued" to "排队中",
    "success" to "成功", "active" to "有效", "inactive" to "未激活", "disabled" to "已停用", "enabled" to "已启用",
    "available" to "可获取", "no_screenshot" to "暂无截图", "registered" to "已登记", "deleted" to "已删除",
    "created" to "已新增", "updated" to "已更新", "withdrawn" to "已撤回", "revision" to "修订", "readonly" to "只读",
    "course-body-json" to "课程正文", "course-and-verified-originals-json" to "课程与原始资料",
)

private val fieldLabels = mapOf(
    "school_id" to "学校", "schoolId" to "学校", "class_id" to "班级", "classId" to "班级",
    "classIds" to "班级权限", "roomId" to "教室", "lesson_id" to "课程编号", "lessonId" to "课程编号",
    "query" to "搜索词", "subject" to "学科", "source_subject" to "来源学科", "date" to "日期",
    "from" to "开始时间", "to" to "结束时间", "limit" to "每页条数", "offset" to "起始位置",
    "cursor" to "续页标记", "id" to "编号", "resource_id" to "文件编号", "entry_path" to "压缩包内路径",
    "source_key" to "原始来源", "path" to "路径", "mode" to "读取模式", "title" to "标题",
    "name" to "名称", "username" to "账号", "role" to "角色", "state" to "状态", "text" to "正文",
    "corrected_text" to "人工修订转写", "asr_text" to "语音识别原文", "results" to "结果", "items" to "条目",
    "starts_at" to "开始时间", "ends_at" to "结束时间", "createdAt" to "创建时间", "updatedAt" to "更新时间",
    "file_name" to "文件名", "content_type" to "文件类型", "bytes" to "字节数", "total" to "总数",
    "has_more" to "有后续页", "next_cursor" to "下一页", "next_id" to "后续正文", "metadata" to "资料信息",
    "published" to "已发布", "enabled" to "启用", "description" to "说明", "password" to "密码",
    "source_manifest" to "原始资料", "knowledgePoints" to "知识点", "article" to "课程文章",
    "captured_at" to "截图时间", "available" to "可获取", "status" to "状态", "device_id" to "设备编号",
    "school_name" to "学校名称", "class_name" to "班级名称", "class_number" to "班号",
    "workspace_imported" to "已导入工作区", "workspace_error" to "工作区导入错误", "local_uri" to "本机附件位置",
    "q" to "搜索词", "sort" to "排序方式", "kind" to "资料类型", "format" to "文件格式", "origin" to "来源类型",
    "category" to "资料分组", "source" to "资料来源", "ids" to "所选编号", "ppt" to "关联课件", "view" to "查看方式", "snapshotAt" to "截图时间",
    "filename_date" to "原文件名日期", "date_basis" to "日期依据", "updated_since" to "变更起始时间",
    "tier" to "班级类型", "grade" to "年级", "timeZoneId" to "时区", "ttlHours" to "有效小时数",
    "rememberMe" to "保持登录", "currentPassword" to "当前密码", "newPassword" to "新密码",
    "ai" to "智能处理", "ocr" to "文字识别", "organizer" to "课程整理", "vision" to "图片理解", "transcription" to "录音转写",
    "aiDailyBatches" to "每日智能处理批次", "aiEnabled" to "启用智能处理", "aiNamingEnabled" to "智能命名",
    "apiKey" to "访问密钥", "endpoint" to "服务地址", "protocol" to "服务协议", "model" to "模型", "modelName" to "模型名称",
    "clear" to "清除设置", "clearAi" to "清除智能处理设置", "clearApiKey" to "清除访问密钥",
    "clearOcr" to "清除文字识别设置", "clearOrganizer" to "清除课程整理设置", "clearTranscription" to "清除转写设置", "clearVision" to "清除图片理解设置",
    "useExistingForOcr" to "文字识别沿用现有服务", "ocrDaily" to "每日文字识别额度", "organizerDaily" to "每日课程整理额度", "todayOnly" to "仅今日生效",
    "prompt" to "提示词", "promptTemplate" to "提示词模板", "message" to "消息", "answer" to "回答", "learningGoal" to "学习目标",
    "retrievalRounds" to "资料检索轮数", "request" to "请求内容", "arguments" to "调用参数", "payload" to "操作内容",
    "requestId" to "请求编号", "requestKey" to "请求标记", "commandId" to "指令编号", "cancelCommandId" to "待取消指令",
    "releaseId" to "版本编号", "clientId" to "客户端编号", "channelId" to "通道编号", "adapterId" to "网络适配器",
    "providerId" to "处理服务", "providerIds" to "处理服务顺序", "resourceIds" to "资料编号", "materialId" to "资料编号",
    "publicationId" to "发布编号", "lessonIds" to "课程列表", "relatedLessonIds" to "关联课程", "specialLessonId" to "特长班课程", "keyLessonId" to "重点班课程",
    "lessonRevision" to "课程修订版本", "version" to "版本", "expectedVersion" to "当前版本", "expectedRevision" to "当前修订版本",
    "expectedPolicyVersion" to "当前策略版本", "expectedBodySha256" to "正文校验值", "expectedEvidenceSha256" to "证据校验值",
    "expectedEvidenceFingerprint" to "证据确认标记", "expectedPlanFingerprint" to "处理方案确认标记", "expectedSourceFingerprint" to "来源确认标记",
    "manifestSha256" to "清单校验值", "reviewManifestSha256" to "审核清单校验值", "failedAttemptId" to "失败尝试编号",
    "reason" to "原因", "confirmedMinimumAttempts" to "已确认最少尝试次数", "maximumContinuationTurns" to "最大继续处理轮数",
    "paused" to "暂停处理", "pausedPolicy" to "暂停时策略", "pausedPolicyVersion" to "暂停时策略版本", "pausedDevicePolicyRevision" to "暂停时设备策略版本",
    "originalPolicy" to "原始策略", "policy" to "课堂策略", "autoPublish" to "自动发布",
    "startsAtUtc" to "开始时间", "endsAtUtc" to "结束时间", "expiresAtUtc" to "到期时间", "atUtc" to "拆分时间", "plannedEndUtc" to "计划结束时间",
    "expiresInMinutes" to "有效分钟数", "storagePath" to "存储路径", "rootDirectory" to "根文件夹", "mountedOnly" to "仅挂载资料", "sources" to "资料来源",
    "schemaVersion" to "结构版本", "policyVersion" to "策略版本", "classTier" to "班级类型", "notes" to "板书保存", "recording" to "课堂录音",
    "saveBeforeClear" to "清空前保存", "localUserCanChangeSave" to "允许本机调整保存设置", "exportDirectory" to "导出文件夹",
    "internalCacheDirectory" to "内部缓存文件夹", "pptNotesDirectory" to "课件批注文件夹", "cacheDirectory" to "缓存文件夹",
    "maxSingleFileBytes" to "单文件大小上限", "maxLocalCacheBytes" to "本地缓存大小上限", "minFreeDiskBytes" to "最少可用磁盘空间",
    "maxPerMediaBytes" to "单介质大小上限", "maxDailyBytes" to "每日上传大小上限", "chunkBytes" to "分块大小", "maxDirectoryDepth" to "最大文件夹层级",
    "maxConcurrentMedia" to "同时读取介质数", "managedTeachingDirectories" to "教学资料文件夹", "copyBytesPerSecond" to "后台复制速度",
    "mountedSyncSources" to "挂载同步来源", "mountedReadBytesPerSecond" to "挂载读取速度", "root" to "根路径", "displayName" to "显示名称",
    "identity" to "来源身份", "reconnectRevision" to "重新连接版本", "sourceEndpointId" to "录音输入设备", "apiChunkTargetSeconds" to "转写分段秒数",
    "localRetentionHours" to "本地录音保留小时数", "transcriptionConcurrencyPerDevice" to "每台设备并行转写数", "uploadRawAudioByDefault" to "默认上传原始录音",
    "teachingWindows" to "上课时段", "excludedDates" to "排除日期", "continuous" to "连续录音", "recognitionMode" to "识别模式",
    "days" to "每周上课日", "start" to "开始时间", "end" to "结束时间", "bufferBefore" to "课前缓冲", "bufferAfter" to "课后缓冲",
    "requestTimeoutSeconds" to "请求超时秒数", "maxRequestBytes" to "请求大小上限", "maxChunkSeconds" to "分段时长上限",
    "maxConcurrentRequests" to "最大并发请求数", "supportedFormats" to "支持的文件格式",
    "url" to "资料链接", "download_url" to "下载链接", "sha256" to "文件校验值", "supports_range" to "支持断点下载", "requires_session" to "需要登录会话",
    "method" to "请求方式", "note" to "说明", "chapter" to "章节", "revision" to "修订版本", "latest_revision" to "最新修订版本",
    "publication_id" to "发布编号", "updated_at" to "更新时间", "change_type" to "变更类型", "uploaded_at" to "上传时间", "activity_at" to "课堂使用时间",
    "source_filename" to "原始文件名", "source_path" to "原始路径", "directory_path" to "所在文件夹", "sync_checkpoint" to "同步检查点",
    "search_scope" to "查询范围", "suggested_queries" to "建议查询", "aliases" to "班级别名", "error" to "错误", "detail" to "详情",
    "sequence_number" to "片段序号", "session_id" to "录音会话", "attempt_count" to "尝试次数", "error_code" to "错误代码",
    "total_bytes" to "文件总大小", "count" to "数量", "scanned" to "已检索数量", "matched" to "匹配数量", "revision_count" to "修订数量",
)

private val normalizedFieldLabels = fieldLabels.mapKeys { (key, _) -> key.filter { it != '_' && it != '-' }.lowercase() }
