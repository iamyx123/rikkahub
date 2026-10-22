package me.rerere.rikkahub.ui.components.classrecord

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.classrecord.ClassRecordRepository
import me.rerere.rikkahub.data.classrecord.ClassRecordState
import me.rerere.rikkahub.ui.components.ui.CardGroup

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
    var reload by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val current = classes.find { it.string("school_id") == state.defaultSchoolId && it.string("class_id") == state.defaultClassId }
    CardGroup(title = { Text("AI 默认班级") }) {
        item(
            onClick = { open = true },
            headlineContent = { Text(state.defaultClassName.ifBlank { current?.string("class_name").orEmpty().ifBlank { state.defaultClassId.ifBlank { "选择默认班级" } } }) },
            supportingContent = {
                Text(if (state.defaultClassId.isBlank()) "选择学校和班级，AI 会用这个范围查找最新教室截图、课程与资料。" else "学校：${state.defaultSchoolId} · 本机当前账号的默认范围")
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
                    OutlinedTextField(filter, { filter = it }, label = { Text("搜索学校、班名或编号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (loading) Text("正在读取授权班级…")
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { reload++ }) { Text("重新读取") } }
                    if (!loading && error == null && classes.isEmpty()) Text("当前账号没有可选择的班级。")
                    LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(classes.filter { record ->
                            listOf("school_id", "school_name", "class_id", "class_name", "class_number").any { record.string(it).contains(filter, ignoreCase = true) }
                        }, key = { it.string("school_id") + "/" + it.string("class_id") }) { record ->
                            Surface(
                                onClick = { selected = record },
                                enabled = !loading,
                                shape = MaterialTheme.shapes.large,
                                color = if (selected == record) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                border = BorderStroke(if (selected == record) 2.dp else 1.dp, MaterialTheme.colorScheme.outline),
                            ) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = selected == record, onClick = null)
                                    Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(record.string("class_name").ifBlank { record.string("class_id") }, style = MaterialTheme.typography.titleSmall)
                                        Text("${record.string("school_name").ifBlank { record.string("school_id") }} · ${record.string("class_id")}", style = MaterialTheme.typography.bodySmall)
                                        if (selected == record) Text("已选择", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
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
