package me.rerere.rikkahub.ui.pages.setting

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.data.study.StudyDesktopContentRepository
import me.rerere.rikkahub.data.study.StudyDesktopManager
import me.rerere.rikkahub.data.study.StudyQuoteCategory
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

@Composable
fun SettingStudyDesktopPage() {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val manager: StudyDesktopManager = koinInject()
    val contentRepository: StudyDesktopContentRepository = koinInject()
    val enabled by manager.enabled.collectAsStateWithLifecycle()
    val quoteCategoryCodes by contentRepository.quoteCategoryCodes.collectAsStateWithLifecycle()
    var permissionRefresh by remember { mutableIntStateOf(0) }
    var showEnableDialog by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, enabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionRefresh++
                manager.startKeepAliveIfPossible()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val accessibilityGranted = remember(permissionRefresh, enabled) {
        manager.isAccessibilityServiceEnabled()
    }
    val batteryGranted = remember(permissionRefresh, enabled) {
        manager.isIgnoringBatteryOptimizations()
    }

    fun openSettings(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "当前系统没有对应的权限页面", Toast.LENGTH_SHORT).show()
        }
    }

    fun updateQuoteCategory(category: StudyQuoteCategory, checked: Boolean) {
        if (!contentRepository.setQuoteCategory(category, checked)) {
            Toast.makeText(context, "至少保留一种一言类型", Toast.LENGTH_SHORT).show()
        }
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
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("模式状态") },
                ) {
                    item(
                        leadingContent = {
                            Icon(if (enabled) HugeIcons.Tick01 else HugeIcons.Settings03, null)
                        },
                        headlineContent = { Text(if (enabled) "学习桌面已开启" else "学习桌面未开启") },
                        supportingContent = {
                            Text(
                                if (enabled) {
                                    "返回系统桌面时会自动回到应用内的学习桌面"
                                } else {
                                    "设置退出密码后，再按提示授予两项系统权限"
                                },
                            )
                        },
                    )
                }
            }

            if (!enabled) {
                item {
                    Button(
                        onClick = { showEnableDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                    ) {
                        Text("设置密码并开启")
                    }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("桌面内容") },
                ) {
                    item(
                        headlineContent = { Text("深圳天气") },
                        supportingContent = {
                            Text("首页显示深圳实时天气与三日预报，每 30 分钟刷新并保留离线缓存。")
                        },
                    )
                    StudyQuoteCategory.entries.forEach { category ->
                        item(
                            onClick = {
                                updateQuoteCategory(
                                    category,
                                    category.code !in quoteCategoryCodes,
                                )
                            },
                            headlineContent = { Text("一言 · ${category.displayName}") },
                            supportingContent = {
                                if (category == StudyQuoteCategory.LITERATURE) {
                                    Text("选择首页随机语录的来源类型；至少保留一种。")
                                }
                            },
                            trailingContent = {
                                Switch(
                                    checked = category.code in quoteCategoryCodes,
                                    onCheckedChange = { updateQuoteCategory(category, it) },
                                )
                            },
                        )
                    }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("系统权限") },
                ) {
                    item(
                        onClick = { openSettings(manager.accessibilitySettingsIntent()) },
                        leadingContent = { Icon(HugeIcons.View, null) },
                        trailingContent = { PermissionStatus(accessibilityGranted) },
                        headlineContent = { Text("无障碍返回守护") },
                        supportingContent = { Text("只识别默认桌面是否出现，不读取屏幕内容") },
                    )
                    item(
                        onClick = { openSettings(manager.batteryOptimizationIntent()) },
                        leadingContent = { Icon(HugeIcons.Zap, null) },
                        trailingContent = { PermissionStatus(batteryGranted) },
                        headlineContent = { Text("电池优化白名单") },
                        supportingContent = { Text("降低系统休眠时终止守护进程的概率") },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("说明") },
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.AlertCircle, null) },
                        headlineContent = { Text("退出方式") },
                        supportingContent = {
                            Text("进入 设置 → 关于，先双击版本号，再长按版本号并输入开启时设置的密码。")
                        },
                    )
                    item(
                        headlineContent = { Text("Android 11 适配") },
                        supportingContent = {
                            Text("两项权限均需用户在系统界面确认；系统设置、重启和应用更新后会重新检查守护状态。")
                        },
                    )
                }
            }
        }
    }

    if (showEnableDialog) {
        EnableStudyDesktopDialog(
            onDismiss = { showEnableDialog = false },
            onEnable = { password ->
                manager.enable(password)
                showEnableDialog = false
                openSettings(manager.accessibilitySettingsIntent())
            },
        )
    }
}

@Composable
private fun PermissionStatus(granted: Boolean) {
    Text(
        text = if (granted) "已授权" else "去授权",
        color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun EnableStudyDesktopDialog(
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
                Text("请设置退出密码。密码只以加盐哈希保存在本机，开启后无法在设置页直接关闭。")
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
            ) {
                Text("开启并授权")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
