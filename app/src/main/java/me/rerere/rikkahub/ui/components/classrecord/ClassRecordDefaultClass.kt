package me.rerere.rikkahub.ui.components.classrecord

import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.BookEdit
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.data.classrecord.ClassRecordRepository
import me.rerere.rikkahub.data.classrecord.ClassRecordState
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon

/** Only directory records returned by the current account can become a default classroom. */
@Composable
internal fun ClassRecordDefaultClass(
    state: ClassRecordState,
    repository: ClassRecordRepository,
    onNotice: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var classes by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var selected by remember { mutableStateOf<JsonObject?>(null) }
    var filter by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showErrorDetails by remember(error) { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val current = classes.find { it.string("school_id") == state.defaultSchoolId && it.string("class_id") == state.defaultClassId }
    CardGroup(title = { Text("AI 默认班级") }) {
        item(
            onClick = { open = true },
            leadingContent = { CardGroupIcon(HugeIcons.BookEdit) },
            trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
            headlineContent = { Text(state.defaultClassName.ifBlank { current?.string("class_name").orEmpty().ifBlank { state.defaultClassId.ifBlank { "选择默认班级" } } }) },
            supportingContent = {
                Text(if (state.defaultClassId.isBlank()) "选择 AI 查询截图、课程与资料时使用的班级" else "AI 默认查询这个班级的截图、课程与资料")
            },
        )
    }
    if (open) {
        LaunchedEffect(reload) {
            loading = true
            error = null
            selected = null
            try {
                val found = mutableListOf<JsonObject>()
                var arguments = JsonObject(emptyMap())
                val seen = mutableSetOf<String>()
                do {
                    val response = repository.call("list_classes", arguments)
                    found += response.objectOrEmpty()["results"].arrayOrEmpty().mapNotNull { it as? JsonObject }
                        .filter { it.string("school_id").isNotBlank() && it.string("class_id").isNotBlank() }
                    val next = classRecordContinuation("list_classes", arguments, response)
                    if (next != null) {
                        check(seen.add(next.marker)) { "班级目录重复返回分页标记，请重试。" }
                        arguments = next.arguments
                    }
                } while (next != null)
                classes = found.distinctBy { it.string("school_id") to it.string("class_id") }
                selected = classes.find { it.string("school_id") == state.defaultSchoolId && it.string("class_id") == state.defaultClassId }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "班级目录读取失败"
            } finally { loading = false }
        }
        AlertDialog(
            onDismissRequest = { if (!loading) open = false },
            title = { Text("选择默认班级") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        filter, { filter = it },
                        label = { Text("搜索班级") },
                        leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (loading) Text("正在读取授权班级…")
                    error?.let { message ->
                        val summary = classRecordDisplayTitle(message, "班级目录暂时无法读取，请重试")
                        Text(summary, color = MaterialTheme.colorScheme.error)
                        if (summary != message) {
                            TextButton(onClick = { showErrorDetails = !showErrorDetails }) { Text(if (showErrorDetails) "收起错误详情" else "查看错误详情") }
                            if (showErrorDetails) SelectionContainer {
                                Text(message, modifier = Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        TextButton(onClick = { reload++ }) { Text("重新读取") }
                    }
                    if (!loading && error == null && classes.isEmpty()) Text("当前账号没有可选择的班级。")
                    val visibleClasses = classes.filter { record ->
                        listOf("school_id", "school_name", "class_id", "class_name", "class_number").any { record.string(it).contains(filter, ignoreCase = true) }
                    }
                    if (!loading && error == null && classes.isNotEmpty() && visibleClasses.isEmpty()) Text("没有匹配的班级")
                    LazyColumn(
                        Modifier.heightIn(max = 340.dp).selectableGroup(),
                        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                    ) {
                        itemsIndexed(visibleClasses, key = { _, record -> record.string("school_id") + "/" + record.string("class_id") }) { index, record ->
                            val isSelected = selected == record
                            SegmentedListItem(
                                onClick = { selected = record },
                                enabled = !loading,
                                modifier = Modifier.semantics { role = Role.RadioButton; this.selected = isSelected },
                                shapes = ListItemDefaults.segmentedShapes(index = index, count = visibleClasses.size),
                                supportingContent = { Text(record.string("school_name").ifBlank { record.string("school_id") }) },
                                trailingContent = { RadioButton(selected = isSelected, onClick = null) },
                            ) {
                                Text(record.string("class_name").ifBlank { record.string("class_id") })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = selected != null && !loading && error == null, onClick = {
                    val record = selected ?: return@TextButton
                    scope.launch {
                        loading = true
                        try {
                            repository.setDefaultClass(record.string("school_id"), record.string("class_id"))
                            open = false
                            onNotice("默认班级已设为 ${record.string("class_name").ifBlank { record.string("class_id") }}")
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            error = failure.message
                        } finally { loading = false }
                    }
                }) { Text("设为默认班级") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } },
        )
    }
}
