package me.rerere.rikkahub.ui.components.classrecord

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
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
) {
    fun edit(input: String, required: Boolean = false): ClassRecordJsonDraft = try {
        copy(text = input, value = if (input.isBlank() && !required) null else Json.parseToJsonElement(input), error = null)
    } catch (failure: Exception) {
        copy(text = input, error = failure.message ?: "JSON 格式错误")
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
) {
    val inheritedValidation = LocalClassRecordFormValidation.current
    val ownValidation = remember(schema) { ClassRecordFormValidationState() }
    val validation = validationState ?: inheritedValidation ?: ownValidation
    val required = schema["required"].arrayOrEmpty().map { it.stringOrEmpty() }.toSet()
    CompositionLocalProvider(LocalClassRecordFormValidation provides validation) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            schema["properties"].objectOrEmpty().forEach { (name, raw) ->
                val field = raw.objectOrEmpty()
                SchemaField(
                    label = field.string("title").ifBlank { fieldLabel(name) } + if (name in required) " *" else "",
                    schema = field,
                    value = value[name],
                    required = name in required,
                    onChange = { onChange(value.withValue(name, it)) },
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
    val validationOwner = remember(validation) { Any() }
    fun reportDraftError(error: String?) = validation?.update(validationOwner, error?.let { "$label：$it" })
    DisposableEffect(validation) {
        onDispose { validation?.update(validationOwner, null) }
    }
    LaunchedEffect(validation, showJson, jsonDraft.error) { reportDraftError(if (showJson) jsonDraft.error else null) }
    LaunchedEffect(value) {
        if (!showJson && jsonDraft.error == null) jsonDraft = ClassRecordJsonDraft.fromForm(value)
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (variants.isNotEmpty()) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            variants.forEachIndexed { index, variant ->
                FilterChip(
                    selected = variantIndex == index,
                    onClick = { variantIndex = index; onChange(if (variant.objectOrEmpty().string("type") == "null") JsonNull else null) },
                    label = { Text(variant.objectOrEmpty().string("title").ifBlank { "格式 ${index + 1}" }) },
                )
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
                    label = { Text("$label · JSON") },
                    minLines = 3,
                    isError = jsonDraft.error != null,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(jsonDraft.error ?: "按服务器 JSON Schema 输入完整结构") },
                )
                TextButton(onClick = { showJson = false; reportDraftError(null) }) { Text("返回表单") }
            }
            choices.isNotEmpty() -> {
                Text(label, style = MaterialTheme.typography.labelLarge)
                choices.forEach { choice ->
                    FilterChip(
                        selected = value == choice,
                        onClick = { onChange(if (value == choice && !required) null else choice) },
                        label = { Text(choice.stringOrEmpty().ifBlank { choice.toString() }) },
                    )
                }
            }
            type == "null" -> Text("$label：空值", style = MaterialTheme.typography.bodyMedium)
            type == "boolean" -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label)
                    Text(
                        if (value == null) "未设置，使用服务器默认值" else if (value == JsonPrimitive(true)) "是" else "否",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = (value as? JsonPrimitive)?.booleanOrNull == true,
                    onCheckedChange = { onChange(JsonPrimitive(it)) },
                )
                if (!required && value != null) TextButton(onClick = { onChange(null) }) { Text("清除") }
            }
            type == "object" -> {
                Text(label, style = MaterialTheme.typography.titleSmall)
                if (effective["properties"].objectOrEmpty().isNotEmpty()) {
                    ClassRecordSchemaForm(effective, value.objectOrEmpty(), { onChange(it) }, Modifier.padding(start = 12.dp))
                }
                TextButton(onClick = { showJson = true }) { Text("编辑完整 JSON / 自定义字段") }
            }
            type == "array" -> {
                Text(label, style = MaterialTheme.typography.labelLarge)
                val items = value.arrayOrEmpty()
                items.forEachIndexed { index, item ->
                    Column(Modifier.padding(start = 12.dp)) {
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
                    TextButton(onClick = { showJson = true }) { Text("编辑 JSON") }
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
        effective.string("description").takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
)
