package me.rerere.rikkahub.ui.pages.setting

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.data.study.StudyDesktopManager
import me.rerere.rikkahub.data.study.StudyDesktopMode
import me.rerere.rikkahub.data.study.StudyPinnedApp
import me.rerere.rikkahub.data.study.StudyResolvedApp
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.pages.study.StudyAppIcon
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

@Composable
fun StudyDesktopSetupPage() {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val manager: StudyDesktopManager = koinInject()
    val enabled by manager.enabled.collectAsStateWithLifecycle()
    val pinnedApps by manager.pinnedApps.collectAsStateWithLifecycle()
    var permissionRefresh by remember { mutableIntStateOf(0) }
    var showEnableDialog by remember { mutableStateOf(false) }
    var showRootEnableDialog by remember { mutableStateOf(false) }
    var rootBusy by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRefresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(permissionRefresh) { manager.refreshRootAvailability() }

    if (enabled) {
        LaunchedEffect(Unit) { manager.openStudyDesktop() }
        return
    }

    val resolvedApps by produceState<List<StudyResolvedApp>?>(null, pinnedApps, permissionRefresh) {
        value = manager.resolvePinnedApps(pinnedApps)
    }
    val resolvedByPackage = remember(resolvedApps) {
        resolvedApps.orEmpty().associateBy { it.pin.packageName }
    }
    val defaultHomeGranted = remember(permissionRefresh) { manager.isDefaultHome() }
    val accessibilityGranted = remember(permissionRefresh) { manager.isAccessibilityServiceEnabled() }
    val batteryGranted = remember(permissionRefresh) { manager.isIgnoringBatteryOptimizations() }
    val rootAvailable by manager.rootAvailable.collectAsStateWithLifecycle()
    val manualReady = accessibilityGranted && batteryGranted

    fun onRootEnableClick() {
        if (rootBusy) return
        // No remembered password yet: ask for one first instead of letting rootSilentEnable fail.
        if (manager.rememberedPassword() == null) {
            showRootEnableDialog = true
            return
        }
        rootBusy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { manager.rootSilentEnable() }
            rootBusy = false
            permissionRefresh++
            if (ok) {
                manager.openStudyDesktop()
            } else {
                Toast.makeText(
                    context,
                    "Root 静默开启失败，可改用下方「设置退出密码并开启」",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    fun openSettings(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "当前系统没有对应的设置页面", Toast.LENGTH_SHORT).show()
        }
    }

    fun activatePendingStudyDesktop() {
        val reconciled = manager.reconcileState()
        if (reconciled == StudyDesktopMode.ACTIVE || manager.completePendingEnable()) {
            manager.openStudyDesktop()
        } else {
            Toast.makeText(context, "启用条件已变化，请重新检查权限", Toast.LENGTH_SHORT).show()
        }
    }

    val homeRoleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        permissionRefresh++
        if (manager.isDefaultHome()) {
            activatePendingStudyDesktop()
        } else {
            manager.cancelPendingEnable()
            Toast.makeText(context, "未设为默认桌面，学习模式没有开启", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestDefaultHome() {
        runCatching {
            homeRoleLauncher.launch(manager.defaultHomeRequestIntent())
        }.onFailure {
            manager.cancelPendingEnable()
            Toast.makeText(context, "无法打开默认桌面选择", Toast.LENGTH_SHORT).show()
        }
    }

    fun movePin(from: Int, delta: Int) {
        if (from !in pinnedApps.indices) return
        val to = (from + delta).coerceIn(0, pinnedApps.lastIndex)
        if (from == to) return
        val reordered = pinnedApps.toMutableList()
        val item = reordered.removeAt(from)
        reordered.add(to, item)
        manager.replacePinnedApps(reordered)
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("学习桌面") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 900.dp)
                    .fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    CardGroup(title = { Text("启用前检查") }) {
                        item(
                            leadingContent = { Icon(HugeIcons.Book03, null) },
                            trailingContent = {
                                SetupStatus(defaultHomeGranted, "已设为默认", "开启时确认")
                            },
                            headlineContent = { Text("默认桌面") },
                            supportingContent = {
                                Text("最后开启时由 Android 11 显示系统确认页；应用不会静默替换桌面。")
                            },
                        )
                        item(
                            leadingContent = { Icon(HugeIcons.Settings03, null) },
                            trailingContent = { SetupStatus(rootAvailable, "可用", "不可用") },
                            headlineContent = { Text("Root 静默开启") },
                            supportingContent = { Text("有 root 时无需系统确认弹窗，自动设为默认桌面、加入电池白名单并以 root 轮询接管前台拦截。") },
                        )
                        // Shown even when root is available: root can fail on a specific ROM, and
                        // these two are the fallback path's hard requirements.
                        item(
                            onClick = { openSettings(manager.accessibilitySettingsIntent()) },
                            leadingContent = { Icon(HugeIcons.View, null) },
                            trailingContent = { SetupStatus(accessibilityGranted) },
                            headlineContent = { Text("无障碍返回守护") },
                            supportingContent = {
                                Text(
                                    if (rootAvailable) "有 root 时不需要；作为 root 失败时的备用方式。"
                                    else "无 root 时使用；仅检测前台应用包名和窗口类型，不读取窗口内容。",
                                )
                            },
                        )
                        item(
                            onClick = { openSettings(manager.batteryOptimizationIntent()) },
                            leadingContent = { Icon(HugeIcons.Zap, null) },
                            trailingContent = { SetupStatus(batteryGranted, "已加入", "去授权") },
                            headlineContent = { Text("电池优化白名单") },
                            supportingContent = {
                                Text(
                                    if (rootAvailable) "Root 静默开启会自动加入，无需手动授权。"
                                    else "无 root 时的必要步骤，可降低厂商系统终止后台守护的概率。",
                                )
                            },
                        )
                    }
                }

                item {
                    CardGroup(title = { Text("学习应用") }) {
                        item(
                            onClick = { showAppPicker = true },
                            leadingContent = { Icon(HugeIcons.Settings03, null) },
                            trailingContent = { Text("已选 ${pinnedApps.size} 个") },
                            headlineContent = { Text("选择可从桌面打开的应用") },
                            supportingContent = { Text("例如番茄 ToDo、词典或阅读器；选择结果会持久保存。") },
                        )
                        if (pinnedApps.isEmpty()) {
                            item(
                                leadingContent = { Icon(HugeIcons.AlertCircle, null) },
                                headlineContent = { Text("尚未选择外部应用") },
                                supportingContent = { Text("这不会阻止启用，之后需退出学习模式才能修改。") },
                            )
                        } else {
                            pinnedApps.forEachIndexed { index, pin ->
                                val resolved = resolvedByPackage[pin.packageName]
                                item(
                                    leadingContent = {
                                        StudyAppIcon(
                                            component = resolved?.component,
                                            contentDescription = null,
                                            modifier = Modifier.size(42.dp),
                                            isLoading = resolvedApps == null,
                                        )
                                    },
                                    headlineContent = { Text(resolved?.label ?: pin.labelSnapshot) },
                                    supportingContent = {
                                        Text(
                                            when {
                                                resolvedApps == null -> "正在检查应用状态"
                                                resolved?.installed == false -> "应用未安装或已停用"
                                                else -> pin.packageName
                                            },
                                        )
                                    },
                                    trailingContent = {
                                        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                                            TextButton(
                                                onClick = { movePin(index, -1) },
                                                enabled = index > 0,
                                                contentPadding = PaddingValues(6.dp),
                                            ) { Text("上移") }
                                            TextButton(
                                                onClick = { movePin(index, 1) },
                                                enabled = index < pinnedApps.lastIndex,
                                                contentPadding = PaddingValues(6.dp),
                                            ) { Text("下移") }
                                            TextButton(
                                                onClick = {
                                                    manager.replacePinnedApps(
                                                        pinnedApps.filterNot { it.packageName == pin.packageName },
                                                    )
                                                },
                                                contentPadding = PaddingValues(6.dp),
                                            ) { Text("移除") }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                item {
                    CardGroup(title = { Text("安全边界") }) {
                        item(
                            leadingContent = { Icon(HugeIcons.AlertCircle, null) },
                            headlineContent = { Text("默认桌面不是设备管控模式") },
                            supportingContent = {
                                Text("它能可靠接管 HOME 键与桌面手势，但普通 APK 不能阻止强行停止、卸载、系统设置或最近任务；学校统一设备需另配 Device Owner / 锁定任务模式。")
                            },
                        )
                        item(
                            headlineContent = { Text("隐藏与退出") },
                            supportingContent = {
                                Text("开启后，本设置入口会隐藏。退出请到 设置 → 关于，双击版本号后在 8 秒内长按并输入密码；有 root 时可直接恢复原桌面。")
                            },
                        )
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (rootAvailable) {
                            Button(
                                onClick = { onRootEnableClick() },
                                enabled = !rootBusy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (rootBusy) "正在开启…" else "Root 静默开启（推荐）")
                            }
                            Text(
                                text = "无需系统弹窗；失败时可继续用下面的普通方式。",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                        // Always available: root can fail on a specific ROM, and the manual flow must
                        // stay reachable rather than leaving a dead-end root-only button.
                        Button(
                            onClick = { showEnableDialog = true },
                            enabled = manualReady && !rootBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("设置退出密码并开启")
                        }
                        if (!manualReady) {
                            Text(
                                text = "普通方式需先完成无障碍和电池优化白名单；默认桌面会在下一步确认。",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAppPicker) {
        StudyAppPickerDialog(
            currentPins = pinnedApps,
            onDismiss = { showAppPicker = false },
            onConfirm = {
                manager.replacePinnedApps(it)
                showAppPicker = false
            },
        )
    }

    if (showEnableDialog) {
        StudyEnableDialog(
            appCount = pinnedApps.size,
            onDismiss = { showEnableDialog = false },
            onEnable = { password ->
                showEnableDialog = false
                runCatching { manager.beginEnable(password) }
                    .onSuccess {
                        if (manager.isDefaultHome()) activatePendingStudyDesktop()
                        else requestDefaultHome()
                    }
                    .onFailure {
                        manager.cancelPendingEnable()
                        Toast.makeText(context, "无法准备学习桌面", Toast.LENGTH_SHORT).show()
                    }
            },
        )
    }

    if (showRootEnableDialog) {
        StudyEnableDialog(
            appCount = pinnedApps.size,
            onDismiss = { showRootEnableDialog = false },
            onEnable = { password ->
                showRootEnableDialog = false
                rootBusy = true
                scope.launch {
                    val enabled = withContext(Dispatchers.IO) {
                        manager.rootSilentEnable(password)
                    }
                    rootBusy = false
                    permissionRefresh++
                    if (enabled) {
                        manager.openStudyDesktop()
                    } else {
                        Toast.makeText(
                            context,
                            "Root 静默开启失败，可改用「设置退出密码并开启」",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
        )
    }
}

@Composable
private fun SetupStatus(
    granted: Boolean,
    grantedText: String = "已授权",
    missingText: String = "去授权",
) {
    Text(
        text = if (granted) grantedText else missingText,
        color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun StudyEnableDialog(
    appCount: Int,
    onDismiss: () -> Unit,
    onEnable: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    val validLength = password.length >= StudyDesktopManager.MIN_PASSWORD_LENGTH
    val passwordsMatch = password == confirmation

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开启学习桌面") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("已配置 $appCount 个外部应用。下一步系统会确认默认桌面；成功后设置入口将隐藏，请牢记退出密码。")
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("退出密码（至少 4 位）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = submitted && !validLength,
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("再次输入密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = submitted && !passwordsMatch,
                    supportingText = {
                        if (submitted && !passwordsMatch) Text("两次输入的密码不一致")
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    submitted = true
                    if (validLength && passwordsMatch) onEnable(password)
                },
            ) { Text("开启") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
