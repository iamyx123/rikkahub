package me.rerere.rikkahub.ui.pages.miaomiao

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BookEdit
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.miaomiao.MiaomiaoImportBus
import me.rerere.rikkahub.data.miaomiao.MiaomiaoService
import me.rerere.rikkahub.data.zyb.ZybClient
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

/**
 * 错题浏览与多选导入页（长按加号里的「喵喵机错题」进入）。
 *
 * - 顶部按科目分类切换，可翻页加载历史错题；
 * - 可跨科目多选，一键导入；
 * - 图片直接进输入框，文字按「每题一个 TXT」作为附件导入，避免污染输入框中的提问。
 */
@Composable
fun MiaomiaoErrorbookPage(
    service: MiaomiaoService = koinInject(),
    filesManager: FilesManager = koinInject(),
    importBus: MiaomiaoImportBus = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val navController = LocalNavController.current
    val settings = LocalSettings.current
    val importMode = settings.displaySetting.miaomiaoErrorbook.importMode

    val pageSize = 20

    var groups by remember { mutableStateOf<List<ZybClient.ErrGroup>>(emptyList()) }
    var selectedGroup by remember { mutableStateOf<ZybClient.ErrGroup?>(null) }
    var items by remember { mutableStateOf<List<ZybClient.ErrItem>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var page by remember { mutableStateOf(0) }
    var loadingGroups by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf("") }

    // 跨科目多选：key = tid，value = (科目名, 错题)
    val selected = remember { mutableStateMapOf<String, Pair<String, ZybClient.ErrItem>>() }

    fun keyOf(item: ZybClient.ErrItem): String = item.tid.ifBlank { item.hashCode().toString() }

    val hasMore = items.size < total

    fun loadGroups() {
        loadingGroups = true
        error = null
        scope.launch {
            service.listGroups()
                .onSuccess { gs ->
                    loadingGroups = false
                    groups = gs
                    if (selectedGroup == null) selectedGroup = gs.firstOrNull()
                    if (gs.isEmpty()) error = "错题本里还没有科目/错题"
                }
                .onFailure {
                    loadingGroups = false
                    error = it.message ?: "加载失败"
                }
        }
    }

    fun loadMore() {
        val g = selectedGroup ?: return
        if (loading || !hasMore) return
        loading = true
        scope.launch {
            service.listItems(g.groupId, page + 1, pageSize)
                .onSuccess { (t, list) ->
                    total = t
                    page += 1
                    items = items + list
                }
                .onFailure { toaster.show("加载失败: ${it.message}", type = ToastType.Error) }
            loading = false
        }
    }

    fun doImport() {
        if (selected.isEmpty() || importing) return
        importing = true
        val snapshot = selected.values.toList()
        scope.launch {
            val imageUris = mutableListOf<Uri>()
            val docs = mutableListOf<UIMessagePart.Document>()
            var textIndex = 0
            snapshot.forEachIndexed { index, (subject, item) ->
                importProgress = "导入中… ${index + 1}/${snapshot.size}"
                val imp = runCatching { service.buildImport(subject, item) }.getOrNull()
                if (imp != null) {
                    val hasImages = imp.images.isNotEmpty()
                    if (service.shouldAddImages(importMode, hasImages) && hasImages) {
                        imageUris += withContext(Dispatchers.IO) {
                            filesManager.createChatFilesByByteArrays(imp.images)
                        }
                    }
                    if (service.shouldAddText(importMode, hasImages, imp.hasText) && imp.questionText != null) {
                        textIndex++
                        docs += filesManager.createChatTextFile(imp.questionText, "错题-$subject-$textIndex")
                    }
                }
            }
            if (imageUris.isEmpty() && docs.isEmpty()) {
                importing = false
                importProgress = ""
                toaster.show("选中的错题没有可导入内容", type = ToastType.Warning)
                return@launch
            }
            importBus.send(MiaomiaoImportBus.Payload(imageUris, docs, snapshot.size))
            importing = false
            importProgress = ""
            toaster.show("已导入 ${snapshot.size} 道错题", type = ToastType.Success)
            navController.popBackStack()
        }
    }

    LaunchedEffect(Unit) { loadGroups() }

    // 切换科目：重置列表并加载第一页
    LaunchedEffect(selectedGroup?.groupId) {
        val g = selectedGroup ?: return@LaunchedEffect
        items = emptyList(); page = 0; total = 0; error = null; loading = true
        service.listItems(g.groupId, 1, pageSize)
            .onSuccess { (t, list) -> total = t; page = 1; items = list }
            .onFailure { error = it.message ?: "加载失败" }
        loading = false
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("错题浏览与导入") },
                navigationIcon = { BackButton() },
                actions = {
                    TextButton(
                        enabled = selected.isNotEmpty() && !importing,
                        onClick = { doImport() },
                    ) {
                        if (importing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(importProgress.ifBlank { "导入中…" })
                        } else {
                            Text("导入(${selected.size})")
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + 16.dp,
                    start = 12.dp,
                    end = 12.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 科目分类
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("科目分类", style = MaterialTheme.typography.labelLarge)
                    if (loadingGroups) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("加载科目…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        groups.forEach { g ->
                            EinkChip(
                                label = if (g.count > 0) "${g.name}(${g.count})" else g.name,
                                selected = selectedGroup?.groupId == g.groupId,
                            ) { if (selectedGroup?.groupId != g.groupId) selectedGroup = g }
                        }
                    }
                }
            }

            // 选择工具行
            if (items.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "已选 ${selected.size} 题（可跨科目）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            items.forEach { selected[keyOf(it)] = (selectedGroup?.name ?: "") to it }
                        }) { Text("本页全选") }
                        if (selected.isNotEmpty()) {
                            TextButton(onClick = { selected.clear() }) { Text("清空") }
                        }
                    }
                    HorizontalDivider()
                }
            }

            // 错题列表
            items(items, key = { keyOf(it) }) { item ->
                ErrorbookItemCard(
                    subject = selectedGroup?.name ?: "",
                    item = item,
                    selected = selected.containsKey(keyOf(item)),
                    onToggle = {
                        val k = keyOf(item)
                        if (selected.containsKey(k)) selected.remove(k)
                        else selected[k] = (selectedGroup?.name ?: "") to item
                    },
                )
            }

            // 加载更多 / 状态
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    when {
                        error != null && items.isEmpty() -> ErrorState(message = error!!) { loadGroups() }
                        loading && items.isEmpty() -> CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                        items.isEmpty() && !loadingGroups -> Text(
                            "该科目暂无错题",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        hasMore -> OutlinedButton(enabled = !loading, onClick = { loadMore() }) {
                            if (loading) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text("加载更多（${items.size}/$total）")
                        }
                        items.isNotEmpty() -> Text(
                            "已到底（共 $total 题）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    val navController = LocalNavController.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(16.dp),
    ) {
        Icon(HugeIcons.BookEdit, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRetry) { Text("重试") }
            Button(onClick = { navController.navigate(Screen.SettingMiaomiao) }) { Text("去登录/设置") }
        }
    }
}

@Composable
private fun ErrorbookItemCard(
    subject: String,
    item: ZybClient.ErrItem,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val thumbUrl = item.images.firstOrNull()?.second
    val q = item.text.questionText.trim()
    val preview = if (q.isNotBlank()) q
    else listOf(item.degree, item.reason).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "（图片错题）" }
    val meta = listOf(subject, item.degree, item.create).filter { it.isNotBlank() }.joinToString(" · ")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { onToggle() },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            else CustomColors.listItemColors.containerColor
        ),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.onSurface,
                    checkmarkColor = MaterialTheme.colorScheme.surface,
                ),
            )
            if (thumbUrl != null) {
                AsyncImage(
                    model = thumbUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (meta.isNotBlank()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 墨水屏高对比 chip：选中=深色实心填充+浅色文字。 */
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
