package me.rerere.rikkahub.ui.components.intelligentui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.intelligentui.INTERACTIVE_UI_CUSTOM_PROMPT_LIMIT
import me.rerere.rikkahub.data.intelligentui.InteractiveUiRuntime
import me.rerere.rikkahub.ui.context.LocalSettings
import org.koin.compose.koinInject

@Composable
fun InteractiveUiButton(enabled: Boolean, loading: Boolean, onEnabledChange: (Boolean) -> Unit) {
    val settings = LocalSettings.current
    val store = koinInject<SettingsStore>()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = enabled, onClick = { onEnabledChange(!enabled) }, enabled = !loading, label = { Text(if (enabled) "交互界面 · 开" else "交互界面") })
        IconButton(onClick = { show = true }) { Icon(HugeIcons.Settings02, contentDescription = "交互界面提示词") }
    }
    if (show) {
        var custom by remember { mutableStateOf(settings.interactiveUiPrompt.orEmpty()) }
        var showDefault by remember { mutableStateOf(false) }
        var saving by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!saving) show = false },
            title = { Text("交互界面提示词") },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("开启后，AI 会在适合的学习、比较和参数探索中主动使用原生控件。下方偏好追加到默认语法说明，修改从下一条消息生效。", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = custom, onValueChange = { custom = it; error = null },
                        label = { Text("自定义偏好（留空使用默认）") },
                        placeholder = { Text("例如：讲函数时优先提供可拖动参数和实时变化的图形，先给简短解释。") },
                        minLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                        isError = custom.length > INTERACTIVE_UI_CUSTOM_PROMPT_LIMIT,
                        supportingText = { Text("${custom.length} / $INTERACTIVE_UI_CUSTOM_PROMPT_LIMIT") },
                    )
                    TextButton(onClick = { showDefault = !showDefault }) { Text(if (showDefault) "收起默认说明" else "查看默认提示词与组件语法") }
                    if (showDefault) SelectionContainer {
                        Text(remember { InteractiveUiRuntime.prompt(context) }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
                    }
                    TextButton(enabled = !saving, onClick = {
                        scope.launch {
                            saving = true
                            try { store.update { it.copy(interactiveUiPrompt = null) }; custom = ""; error = null }
                            catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message }
                            finally { saving = false }
                        }
                    }) { Text("恢复默认") }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(enabled = !saving && custom.length <= INTERACTIVE_UI_CUSTOM_PROMPT_LIMIT, onClick = {
                scope.launch {
                    saving = true
                    try { store.update { it.copy(interactiveUiPrompt = custom.trim().ifBlank { null }) }; show = false }
                    catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message }
                    finally { saving = false }
                }
            }) { Text(if (saving) "保存中…" else "保存") } },
            dismissButton = { TextButton(enabled = !saving, onClick = { show = false }) { Text("取消") } },
        )
    }
}
