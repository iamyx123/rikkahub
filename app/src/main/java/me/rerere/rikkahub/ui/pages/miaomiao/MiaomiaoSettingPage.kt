package me.rerere.rikkahub.ui.pages.miaomiao

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bluetooth
import me.rerere.hugeicons.stroke.BluetoothSearch
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Printer
import me.rerere.hugeicons.stroke.TextFont
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.hugeicons.stroke.View
import me.rerere.highlight.Highlighter
import me.rerere.rikkahub.data.datastore.MiaomiaoImportMode
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.miaomiao.MiaomiaoService
import me.rerere.rikkahub.data.paperang.PaperangPrinter
import me.rerere.rikkahub.data.zyb.ZybClient
import me.rerere.rikkahub.ui.components.message.PrintableMessageContent
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

@Composable
fun MiaomiaoSettingPage(
    settingsStore: SettingsStore = koinInject(),
    miaomiaoService: MiaomiaoService = koinInject(),
    printer: PaperangPrinter = koinInject(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val settings = LocalSettings.current
    val ebCfg = settings.displaySetting.miaomiaoErrorbook
    val prtCfg = settings.displaySetting.paperangPrinter

    fun updateErrorbook(transform: (me.rerere.rikkahub.data.datastore.MiaomiaoErrorbookConfig) -> me.rerere.rikkahub.data.datastore.MiaomiaoErrorbookConfig) {
        scope.launch {
            settingsStore.update { it.copy(displaySetting = it.displaySetting.copy(miaomiaoErrorbook = transform(it.displaySetting.miaomiaoErrorbook))) }
        }
    }

    fun updatePrinter(transform: (me.rerere.rikkahub.data.datastore.PaperangPrinterConfig) -> me.rerere.rikkahub.data.datastore.PaperangPrinterConfig) {
        scope.launch {
            settingsStore.update { it.copy(displaySetting = it.displaySetting.copy(paperangPrinter = transform(it.displaySetting.paperangPrinter))) }
        }
    }

    // 让打印机的自动重连开关与配置保持一致
    LaunchedEffect(prtCfg.autoReconnect) { printer.setAutoReconnect(prtCfg.autoReconnect) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("喵喵机 · 错题本与打印") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + 24.dp,
                    start = 16.dp,
                    end = 16.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ErrorbookAccountCard(
                phone = ebCfg.phone,
                password = ebCfg.password,
                importMode = ebCfg.importMode,
                includeAnalysis = ebCfg.includeAnalysis,
                lastGroupName = ebCfg.lastGroupName,
                miaomiaoService = miaomiaoService,
                onSavePhone = { p -> updateErrorbook { it.copy(phone = p) } },
                onSavePassword = { p -> updateErrorbook { it.copy(password = p) } },
                onSelectMode = { m -> updateErrorbook { it.copy(importMode = m) } },
                onToggleAnalysis = { v -> updateErrorbook { it.copy(includeAnalysis = v) } },
                onSelectGroup = { id, name -> updateErrorbook { it.copy(lastGroupId = id, lastGroupName = name) } },
            )

            PrinterCard(
                printer = printer,
                density = prtCfg.density,
                grayscale = prtCfg.grayscale,
                autoReconnect = prtCfg.autoReconnect,
                onConnect = { addr, name ->
                    updatePrinter { it.copy(deviceAddress = addr, deviceName = name) }
                    printer.setAutoReconnect(prtCfg.autoReconnect)
                    scope.launch {
                        val ok = printer.connect(addr)
                        toaster.show(
                            message = if (ok) "打印机已连接" else (printer.status.value.message ?: "连接失败，请重试"),
                            type = if (ok) ToastType.Success else ToastType.Error,
                        )
                    }
                },
                onDisconnect = { printer.disconnect() },
                onDensityChange = { d -> updatePrinter { it.copy(density = d) } },
                onToggleGray = { v -> updatePrinter { it.copy(grayscale = v) } },
                onToggleAutoReconnect = { v ->
                    updatePrinter { it.copy(autoReconnect = v) }
                    printer.setAutoReconnect(v)
                },
            )

            PrintFontCard(
                fontScale = prtCfg.printFontScale,
                onScaleChange = { s -> updatePrinter { it.copy(printFontScale = s) } },
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
private fun ErrorbookAccountCard(
    phone: String,
    password: String,
    importMode: MiaomiaoImportMode,
    includeAnalysis: Boolean,
    lastGroupName: String,
    miaomiaoService: MiaomiaoService,
    onSavePhone: (String) -> Unit,
    onSavePassword: (String) -> Unit,
    onSelectMode: (MiaomiaoImportMode) -> Unit,
    onToggleAnalysis: (Boolean) -> Unit,
    onSelectGroup: (Int, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var phoneText by remember(phone) { mutableStateOf(phone) }
    var passwordText by remember(password) { mutableStateOf(password) }
    var showPassword by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var loadingGroups by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf<List<ZybClient.ErrGroup>>(emptyList()) }

    SectionCard(title = "作业帮账号", icon = HugeIcons.Link01) {
        Text(
            "登录作业帮账号后，短按加号里的「喵喵机错题」即可把最新错题（拍照原图 + 搜题清晰图）导入对话框。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = phoneText,
            onValueChange = { phoneText = it.trim(); onSavePhone(phoneText) },
            label = { Text("手机号") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = passwordText,
            onValueChange = { passwordText = it; onSavePassword(passwordText) },
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(if (showPassword) HugeIcons.ViewOff else HugeIcons.View, null)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !testing && phoneText.isNotBlank() && passwordText.isNotBlank(),
                onClick = {
                    testing = true
                    scope.launch {
                        val result = miaomiaoService.ensureLogin()
                        testing = false
                        result
                            .onSuccess {
                                val nick = miaomiaoService.client.nickName.ifBlank { "已登录" }
                                toaster.show(message = "登录成功：$nick", type = ToastType.Success)
                            }
                            .onFailure { toaster.show(message = "登录失败: ${it.message}", type = ToastType.Error) }
                    }
                },
            ) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (testing) "登录中…" else "登录测试")
            }
            OutlinedButton(
                enabled = !loadingGroups,
                onClick = {
                    loadingGroups = true
                    scope.launch {
                        miaomiaoService.ensureLogin()
                            .onFailure { toaster.show(message = "请先登录: ${it.message}", type = ToastType.Error) }
                        val r = miaomiaoService.client.getErrGroups()
                        loadingGroups = false
                        r.onSuccess { groups = it }
                            .onFailure { toaster.show(message = "获取科目失败: ${it.message}", type = ToastType.Error) }
                    }
                },
            ) {
                Text(if (loadingGroups) "加载中…" else "刷新科目")
            }
        }

        // 科目选择
        Text("默认科目（不选则自动取最新拍摄的一题）", style = MaterialTheme.typography.labelLarge)
        if (lastGroupName.isNotBlank() || groups.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EinkChip(label = "自动", selected = lastGroupName.isBlank()) { onSelectGroup(0, "") }
                groups.forEach { g ->
                    EinkChip(
                        label = if (g.count > 0) "${g.name}(${g.count})" else g.name,
                        selected = lastGroupName == g.name,
                    ) { onSelectGroup(g.groupId, g.name) }
                }
                if (groups.isEmpty() && lastGroupName.isNotBlank()) {
                    EinkChip(label = lastGroupName, selected = true) { }
                }
            }
        }

        HorizontalDivider()

        // 导入内容模式
        Text("导入内容", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip("仅图片", importMode == MiaomiaoImportMode.IMAGE_ONLY) { onSelectMode(MiaomiaoImportMode.IMAGE_ONLY) }
            ModeChip("仅文字", importMode == MiaomiaoImportMode.TEXT_ONLY) { onSelectMode(MiaomiaoImportMode.TEXT_ONLY) }
            ModeChip("图片+文字", importMode == MiaomiaoImportMode.BOTH) { onSelectMode(MiaomiaoImportMode.BOTH) }
        }
        Text(
            "纯文字（LaTeX）题目会以带公式的文本形式进入输入框；「仅图片」时若某题没有图片，会自动改用文字。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("附带官方解析", style = MaterialTheme.typography.bodyLarge)
                Text("默认关闭（解析交给 AI）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = includeAnalysis, onCheckedChange = onToggleAnalysis)
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    EinkChip(label = label, selected = selected, onClick = onClick)
}

/** 墨水屏高对比 chip：选中=深色实心填充+浅色文字（无彩色屏也能清晰分辨选中态）。 */
@Composable
private fun EinkChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = MaterialTheme.colorScheme.onSurface,
            selectedContainerColor = MaterialTheme.colorScheme.onSurface,
            selectedLabelColor = MaterialTheme.colorScheme.surface,
            selectedLeadingIconColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

/** AI 回复打印字体大小设置 + 实时预览（所见即打印）。 */
@Composable
private fun PrintFontCard(
    fontScale: Float,
    onScaleChange: (Float) -> Unit,
) {
    val highlighter: Highlighter = koinInject()
    val settings = LocalSettings.current
    // sliderValue 跟随手指，previewScale 仅松手后更新，避免逐帧重渲染公式卡顿
    var sliderValue by remember(fontScale) { mutableStateOf(fontScale) }
    var previewScale by remember(fontScale) { mutableStateOf(fontScale) }
    val sample = "### 解题示例\n\n" +
        "已知抛物线 \${y}^{2}=2px\\,(p>0)\$ 过点 \$A(2,\\,2)\$，求 \$p\$ 的值与准线方程。\n\n" +
        "**解：** 将点 \$A\$ 代入得 \$4=4p\$，故 \$p=1\$，抛物线方程为\n\n" +
        "\$\$y^{2}=2x\$\$\n\n" +
        "于是准线为 \$x=-\\dfrac{1}{2}\$。要点：\n\n" +
        "1. 顶点在原点，开口向右；\n" +
        "2. 焦点坐标为 \$\\left(\\dfrac{p}{2},\\,0\\right)\$；\n" +
        "3. 抛物线离心率恒为 \$1\$。"

    SectionCard(title = "AI 回复打印字体", icon = HugeIcons.TextFont) {
        Text(
            "调节后下方预览即为打印效果；聊天里短按「打印这条回复」按此字号，长按可临时再调。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("字体", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = {
                    previewScale = sliderValue
                    onScaleChange(sliderValue)
                },
                valueRange = 0.5f..2.0f,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Text("${(sliderValue * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .background(Color.White, RoundedCornerShape(8.dp))
                .padding(6.dp)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            PrintableMessageContent(text = sample, fontScale = previewScale, highlighter = highlighter, settings = settings)
        }
    }
}

@Composable
private fun PrinterCard(
    printer: PaperangPrinter,
    density: Int,
    grayscale: Boolean,
    autoReconnect: Boolean,
    onConnect: (String, String) -> Unit,
    onDisconnect: () -> Unit,
    onDensityChange: (Int) -> Unit,
    onToggleGray: (Boolean) -> Unit,
    onToggleAutoReconnect: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val status by printer.status.collectAsState()
    val scanResults by printer.scanResults.collectAsState()
    var densityLocal by remember(density) { mutableStateOf(density.toFloat()) }

    val blePermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            scope.launch { printer.startScan() }
        } else {
            toaster.show(message = "需要蓝牙权限才能扫描打印机", type = ToastType.Warning)
        }
    }

    SectionCard(title = "喵喵机 N2 打印机", icon = HugeIcons.Printer) {
        // 连接状态
        val stateText = when (status.state) {
            PaperangPrinter.ConnState.CONNECTED -> "已连接：${status.deviceName ?: status.deviceAddress}（纸宽 ${status.paperWidthPx}px）"
            PaperangPrinter.ConnState.CONNECTING -> status.message ?: "连接中…"
            PaperangPrinter.ConnState.SCANNING -> "扫描中…"
            PaperangPrinter.ConnState.DISCONNECTED -> status.message ?: "未连接"
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                HugeIcons.Bluetooth, null,
                tint = if (status.state == PaperangPrinter.ConnState.CONNECTED) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stateText, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = status.state != PaperangPrinter.ConnState.SCANNING,
                onClick = { permissionLauncher.launch(blePermissions) },
            ) {
                Icon(HugeIcons.BluetoothSearch, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (status.state == PaperangPrinter.ConnState.SCANNING) "扫描中…" else "扫描打印机")
            }
            if (status.state == PaperangPrinter.ConnState.CONNECTED) {
                OutlinedButton(onClick = onDisconnect) { Text("断开") }
            }
        }

        // 扫描结果
        if (scanResults.isNotEmpty()) {
            HorizontalDivider()
            Text("点击连接：", style = MaterialTheme.typography.labelLarge)
            scanResults.forEach { dev ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(HugeIcons.Printer, null, modifier = Modifier.size(18.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(dev.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        Text("${dev.address}  信号 ${dev.rssi}dBm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        enabled = status.deviceAddress != dev.address || status.state != PaperangPrinter.ConnState.CONNECTED,
                        onClick = { onConnect(dev.address, dev.name) },
                    ) { Text("连接") }
                }
            }
        }

        HorizontalDivider()

        // 打印浓度
        Text("打印浓度：${densityLocal.toInt()}", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = densityLocal,
            onValueChange = { densityLocal = it },
            onValueChangeFinished = { onDensityChange(densityLocal.toInt().coerceIn(1, 255)) },
            valueRange = 1f..255f,
        )

        // 灰度/黑白
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("灰度打印（照片）", style = MaterialTheme.typography.bodyLarge)
                Text("文字/题目建议关闭（黑白更清晰）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = grayscale, onCheckedChange = onToggleGray)
        }

        // 自动重连
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("断线自动重连", style = MaterialTheme.typography.bodyLarge)
            }
            Switch(checked = autoReconnect, onCheckedChange = onToggleAutoReconnect)
        }

        // 测试
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = status.state == PaperangPrinter.ConnState.CONNECTED,
                onClick = { scope.launch { printer.selfTest() } },
            ) { Text("打印自检页") }
            OutlinedButton(
                enabled = status.state == PaperangPrinter.ConnState.CONNECTED,
                onClick = { scope.launch { printer.feed(60) } },
            ) { Text("走纸") }
        }
    }
}
