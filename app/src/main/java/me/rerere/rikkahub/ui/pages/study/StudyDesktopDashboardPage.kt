package me.rerere.rikkahub.ui.pages.study

import android.widget.Toast
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.study.StudyDesktopManager
import me.rerere.rikkahub.data.study.StudyPinnedApp
import me.rerere.rikkahub.data.study.StudyResolvedApp
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.compose.koinInject
import java.time.LocalDate
import kotlin.uuid.Uuid

private data class DashboardShortcut(
    val title: String,
    val subtitle: String,
    val icon: StudyIconKind,
    val featured: Boolean = false,
    val destination: () -> Screen,
)

@Composable
fun StudyDesktopDashboardPage(
    onOpenScreen: ((Screen) -> Unit)? = null,
    onRequestExit: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val navigator = if (onOpenScreen == null) LocalNavController.current else null
    val openScreen = onOpenScreen ?: { screen: Screen -> navigator!!.navigate(screen) }
    val manager: StudyDesktopManager = koinInject()
    val pinnedApps by manager.pinnedApps.collectAsStateWithLifecycle()
    val examTargetDate by manager.examTargetDate.collectAsStateWithLifecycle()
    var appRefresh by remember { mutableIntStateOf(0) }
    val resolvedApps by produceState<List<StudyResolvedApp>?>(null, pinnedApps, appRefresh) {
        value = manager.resolvePinnedApps(pinnedApps)
    }
    val shortcuts = rememberDashboardShortcuts()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) appRefresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler { /* 默认 HOME 的根页面不向系统桌面退出。 */ }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .widthIn(max = 1440.dp)
                    .fillMaxSize(),
            ) {
                val ratio = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else 1f
                val twoPane = maxWidth >= 900.dp && ratio >= 1.20f
                if (twoPane) {
                    val railWidth = (maxWidth * 0.29f).coerceIn(300.dp, 370.dp)
                    val gridWidth = maxWidth - railWidth - 18.dp
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        StudyAmbientPanel(
                            sideRail = true,
                            modifier = Modifier
                                .width(railWidth)
                                .fillMaxHeight(),
                        )
                        StudyDashboardGrid(
                            resolvedApps = resolvedApps,
                            pinnedApps = pinnedApps,
                            shortcuts = shortcuts,
                            examTargetDate = examTargetDate,
                            onExamTargetDateChange = manager::setExamTargetDate,
                            columns = dashboardColumns(gridWidth),
                            includeTodayPanel = false,
                            onRequestExit = onRequestExit,
                            onLaunchApp = { app ->
                                if (!manager.launchPinnedApp(app.pin)) {
                                    Toast.makeText(
                                        context,
                                        "${app.label} 未安装或已停用",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            onOpenShortcut = { openScreen(it.destination()) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    StudyDashboardGrid(
                        resolvedApps = resolvedApps,
                        pinnedApps = pinnedApps,
                        shortcuts = shortcuts,
                        examTargetDate = examTargetDate,
                        onExamTargetDateChange = manager::setExamTargetDate,
                        columns = dashboardColumns(maxWidth),
                        includeTodayPanel = true,
                        onRequestExit = onRequestExit,
                        onLaunchApp = { app ->
                            if (!manager.launchPinnedApp(app.pin)) {
                                Toast.makeText(
                                    context,
                                    "${app.label} 未安装或已停用",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        onOpenShortcut = { openScreen(it.destination()) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun StudyDashboardGrid(
    resolvedApps: List<StudyResolvedApp>?,
    pinnedApps: List<StudyPinnedApp>,
    shortcuts: List<DashboardShortcut>,
    examTargetDate: LocalDate,
    onExamTargetDateChange: (LocalDate) -> Unit,
    columns: Int,
    includeTodayPanel: Boolean,
    onRequestExit: (() -> Unit)?,
    onLaunchApp: (StudyResolvedApp) -> Unit,
    onOpenShortcut: (DashboardShortcut) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val featuredShortcuts = shortcuts.filter { it.featured }
        val utilityShortcuts = shortcuts.filterNot { it.featured }

        item(span = { GridItemSpan(maxLineSpan) }) {
            DashboardMasthead(onRequestExit)
        }
        if (includeTodayPanel) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                StudyAmbientPanel(sideRail = false)
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            StudyExamCountdownCard(
                targetDate = examTargetDate,
                onTargetDateChange = onExamTargetDateChange,
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            DashboardSectionHeader(
                title = "AI 学习",
                detail = "核心能力，为主要任务保留更大的空间",
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            DashboardFeatureDeck(
                shortcuts = featuredShortcuts,
                onOpenShortcut = onOpenShortcut,
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            DashboardSectionHeader(
                title = "学习应用",
                detail = if (pinnedApps.isEmpty()) "启用前可在设置中选择" else "${pinnedApps.size} 个已配置入口",
            )
        }
        if (pinnedApps.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                DashboardEmptyApps()
            }
        } else {
            val isResolving = resolvedApps == null
            val resolvedByPackage = resolvedApps.orEmpty().associateBy { it.pin.packageName }
            items(pinnedApps, key = { "app:${it.packageName}" }) { pin ->
                val app = resolvedByPackage[pin.packageName] ?: StudyResolvedApp(
                    pin = pin,
                    label = pin.labelSnapshot,
                    component = null,
                )
                ExternalAppTile(
                    app = app,
                    isLoading = isResolving,
                    onClick = { onLaunchApp(app) },
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            DashboardSectionHeader(
                title = "更多工具",
                detail = "整理、创作与系统能力",
            )
        }
        items(utilityShortcuts, key = { "internal:${it.title}" }) { shortcut ->
            InternalShortcutTile(shortcut = shortcut, onClick = { onOpenShortcut(shortcut) })
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = "学习桌面 · HOME 键返回此页",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.52f),
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun DashboardMasthead(onRequestExit: (() -> Unit)?) {
    var exitArmedUntil by remember { mutableLongStateOf(0L) }
    Column(
        modifier = Modifier.combinedClickable(
            enabled = onRequestExit != null,
            onClick = {},
            onDoubleClick = {
                exitArmedUntil = SystemClock.elapsedRealtime() + 8_000L
            },
            onLongClick = {
                if (SystemClock.elapsedRealtime() <= exitArmedUntil) {
                    exitArmedUntil = 0L
                    onRequestExit?.invoke()
                }
            },
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "学习桌面",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "RIKKAHUB · STUDY DESK",
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                )
            }
            Text(
                text = "专注 / 阅读 / 思考",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
    }
}

@Composable
private fun DashboardSectionHeader(title: String, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DashboardFeatureDeck(
    shortcuts: List<DashboardShortcut>,
    onOpenShortcut: (DashboardShortcut) -> Unit,
) {
    val primary = shortcuts.firstOrNull { it.title == "AI 对话" } ?: shortcuts.firstOrNull() ?: return
    val secondary = shortcuts.firstOrNull { it != primary }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth >= 620.dp && secondary != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(184.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FeaturedShortcutCard(
                    shortcut = primary,
                    prominent = true,
                    onClick = { onOpenShortcut(primary) },
                    modifier = Modifier.weight(1.35f),
                )
                FeaturedShortcutCard(
                    shortcut = secondary,
                    prominent = false,
                    onClick = { onOpenShortcut(secondary) },
                    modifier = Modifier.weight(0.85f),
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FeaturedShortcutCard(
                    shortcut = primary,
                    prominent = true,
                    onClick = { onOpenShortcut(primary) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(176.dp),
                )
                secondary?.let { shortcut ->
                    FeaturedShortcutCard(
                        shortcut = shortcut,
                        prominent = false,
                        onClick = { onOpenShortcut(shortcut) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(144.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FeaturedShortcutCard(
    shortcut: DashboardShortcut,
    prominent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (prominent) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val contentColor = if (prominent) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    Surface(
        modifier = modifier
            .semantics { contentDescription = shortcut.title }
            .clickable(onClick = onClick),
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.16f)),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            StudyLineIcon(
                kind = shortcut.icon,
                description = shortcut.title,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 6.dp)
                    .size(if (prominent) 124.dp else 100.dp),
                tint = contentColor.copy(alpha = 0.09f),
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
            ) {
                Surface(
                    color = contentColor.copy(alpha = 0.10f),
                    contentColor = contentColor,
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        text = if (prominent) "AI 学习中枢" else "专属学习角色",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = shortcut.title,
                    style = if (prominent) {
                        MaterialTheme.typography.headlineMedium
                    } else {
                        MaterialTheme.typography.headlineSmall
                    },
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = shortcut.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (prominent) "开始学习" else "选择助手",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = HugeIcons.ArrowRight01,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardEmptyApps() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 18.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = "未配置外部应用。退出学习模式后，可在设置中添加番茄 ToDo、词典或阅读器。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExternalAppTile(
    app: StudyResolvedApp,
    isLoading: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp)
            .semantics { contentDescription = app.label }
            .clickable(enabled = !isLoading, onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StudyAppIcon(
                component = app.component,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                isLoading = isLoading,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        isLoading -> "正在检查"
                        !app.installed -> "应用缺失"
                        else -> "学习应用"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = HugeIcons.ArrowRight01,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InternalShortcutTile(shortcut: DashboardShortcut, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp)
            .semantics { contentDescription = shortcut.title }
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(46.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(14.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    StudyLineIcon(
                        kind = shortcut.icon,
                        description = shortcut.title,
                        modifier = Modifier.size(25.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = shortcut.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = shortcut.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun rememberDashboardShortcuts(): List<DashboardShortcut> = remember {
    listOf(
        DashboardShortcut(
            "AI 对话",
            "解题、梳理和继续深度思考",
            StudyIconKind.CHAT,
            featured = true,
        ) { Screen.Chat(Uuid.random().toString()) },
        DashboardShortcut("历史记录", "继续过去的对话", StudyIconKind.HISTORY) { Screen.History },
        DashboardShortcut("收藏", "查看收藏内容", StudyIconKind.FAVORITE) { Screen.Favorite },
        DashboardShortcut(
            "助手",
            "切换你的专属学习角色",
            StudyIconKind.ASSISTANT,
            featured = true,
        ) { Screen.Assistant },
        DashboardShortcut("翻译", "快速理解外语内容", StudyIconKind.TRANSLATE) { Screen.Translator },
        DashboardShortcut("图像生成", "生成学习插图", StudyIconKind.IMAGE) { Screen.ImageGen },
        DashboardShortcut("消息搜索", "检索对话内容", StudyIconKind.SEARCH) { Screen.MessageSearch },
        DashboardShortcut("扩展", "管理扩展能力", StudyIconKind.EXTENSIONS) { Screen.Extensions },
        DashboardShortcut("快捷消息", "常用学习指令", StudyIconKind.QUICK_MESSAGE) { Screen.QuickMessages },
        DashboardShortcut("提示词", "管理提示词模板", StudyIconKind.PROMPT) { Screen.Prompts },
        DashboardShortcut("技能", "管理 AI 技能", StudyIconKind.SKILLS) { Screen.Skills },
        DashboardShortcut("工作区", "文件与终端工具", StudyIconKind.WORKSPACE) { Screen.Workspaces },
        DashboardShortcut("统计", "查看使用统计", StudyIconKind.STATS) { Screen.Stats },
        DashboardShortcut("备份", "导入与备份数据", StudyIconKind.BACKUP) { Screen.Backup },
        DashboardShortcut("设置", "全部项目设置", StudyIconKind.SETTINGS) { Screen.Setting },
    )
}

private fun dashboardColumns(width: Dp): Int = when {
    width >= 1180.dp -> 4
    width >= 860.dp -> 3
    width >= 560.dp -> 2
    else -> 1
}
