package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.rerere.rikkahub.data.study.StudyDesktopManager
import me.rerere.rikkahub.data.study.StudyInstalledApp
import me.rerere.rikkahub.data.study.StudyPinnedApp
import me.rerere.rikkahub.ui.pages.study.StudyAppIcon
import org.koin.compose.koinInject

@Composable
fun StudyAppPickerDialog(
    currentPins: List<StudyPinnedApp>,
    onDismiss: () -> Unit,
    onConfirm: (List<StudyPinnedApp>) -> Unit,
) {
    val manager: StudyDesktopManager = koinInject()
    val appResult by produceState<Result<List<StudyInstalledApp>>?>(null) {
        value = runCatching { manager.queryLaunchableApps() }
    }
    val apps = appResult?.getOrNull()
    var query by remember { mutableStateOf("") }
    var selectedPackages by remember(currentPins) {
        mutableStateOf(currentPins.mapTo(linkedSetOf()) { it.packageName }.toSet())
    }
    val filtered = remember(apps, query) {
        val needle = query.trim()
        apps.orEmpty().filter {
            needle.isEmpty() ||
                it.label.contains(needle, ignoreCase = true) ||
                it.packageName.contains(needle, ignoreCase = true)
        }
    }

    val unavailablePins = remember(apps, currentPins) {
        val availablePackages = apps?.mapTo(hashSetOf()) { it.packageName }
        if (availablePackages == null) emptyList()
        else currentPins.filter { it.packageName !in availablePackages }
    }
    val filteredUnavailable = remember(unavailablePins, query) {
        val needle = query.trim()
        unavailablePins.filter {
            needle.isEmpty() ||
                it.labelSnapshot.contains(needle, ignoreCase = true) ||
                it.packageName.contains(needle, ignoreCase = true)
        }
    }

    fun togglePackage(packageName: String) {
        selectedPackages = if (packageName in selectedPackages) {
            selectedPackages - packageName
        } else {
            selectedPackages + packageName
        }
    }

    fun toggle(app: StudyInstalledApp) = togglePackage(app.packageName)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 32.dp)
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .heightIn(min = 420.dp, max = 720.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color.White,
            contentColor = Color.Black,
            border = BorderStroke(1.dp, Color.Black),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("选择可打开的应用", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "已选 ${selectedPackages.size} 个",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.Black.copy(alpha = 0.62f),
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(
                        onClick = {
                            val available = apps.orEmpty()
                            val availablePackages = available.mapTo(hashSetOf()) { it.packageName }
                            val selectedInstalled = available
                                .filter { it.packageName in selectedPackages }
                                .associateBy({ it.packageName }, { it.toPinnedApp() })
                            val result = buildList {
                                currentPins.forEach { pin ->
                                    val refreshed = selectedInstalled[pin.packageName]
                                    when {
                                        refreshed != null -> add(refreshed)
                                        pin.packageName !in availablePackages && pin.packageName in selectedPackages -> add(pin)
                                    }
                                }
                                available.forEach { app ->
                                    if (app.packageName in selectedPackages &&
                                        currentPins.none { it.packageName == app.packageName }
                                    ) {
                                        add(app.toPinnedApp())
                                    }
                                }
                            }
                            onConfirm(result)
                        },
                        enabled = appResult?.isSuccess == true,
                    ) { Text("保存") }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索应用或包名") },
                    singleLine = true,
                )
                HorizontalDivider(color = Color.Black)
                when {
                    appResult == null -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(color = Color.Black)
                    }

                    appResult?.isFailure == true -> PickerMessage("读取应用列表失败，请关闭后重试")

                    filtered.isEmpty() && filteredUnavailable.isEmpty() -> PickerMessage(
                        if (query.isBlank()) "未发现可启动的外部应用" else "没有匹配的应用",
                    )

                    else -> LazyColumn(modifier = Modifier.weight(1f)) {
                        if (filteredUnavailable.isNotEmpty()) {
                            item {
                                Text(
                                    text = "已选但不可用",
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.Black.copy(alpha = 0.62f),
                                )
                            }
                            items(filteredUnavailable, key = { "missing:${it.packageName}" }) { pin ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { togglePackage(pin.packageName) }
                                        .padding(vertical = 9.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    StudyAppIcon(
                                        component = null,
                                        contentDescription = null,
                                        modifier = Modifier.size(42.dp),
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = pin.labelSnapshot,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "未安装或已停用 · ${pin.packageName}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.Black.copy(alpha = 0.55f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Checkbox(
                                        checked = pin.packageName in selectedPackages,
                                        onCheckedChange = { togglePackage(pin.packageName) },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = Color.Black,
                                            checkmarkColor = Color.White,
                                            uncheckedColor = Color.Black,
                                        ),
                                    )
                                }
                            }
                            if (filtered.isNotEmpty()) {
                                item { HorizontalDivider(color = Color.Black.copy(alpha = 0.35f)) }
                            }
                        }
                        items(filtered, key = { it.packageName }) { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { toggle(app) }
                                    .padding(vertical = 9.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                StudyAppIcon(
                                    component = app.component,
                                    contentDescription = null,
                                    modifier = Modifier.size(42.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Black.copy(alpha = 0.55f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Checkbox(
                                    checked = app.packageName in selectedPackages,
                                    onCheckedChange = { toggle(app) },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = Color.Black,
                                        checkmarkColor = Color.White,
                                        uncheckedColor = Color.Black,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerMessage(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Black.copy(alpha = 0.62f),
        )
    }
}
