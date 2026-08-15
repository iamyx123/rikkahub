package me.rerere.rikkahub.ui.pages.study

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.AiSearch02
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.DatabaseRestore
import me.rerere.hugeicons.stroke.Favourite
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.QuillWrite01
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.TransactionHistory
import me.rerere.hugeicons.stroke.Translate
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalNavController
import kotlin.uuid.Uuid

private data class StudyShortcut(
    val title: String,
    val subtitle: String,
    val icon: StudyIconKind,
    val destination: () -> Screen,
)

internal enum class StudyIconKind {
    CHAT,
    HISTORY,
    FAVORITE,
    ASSISTANT,
    TRANSLATE,
    IMAGE,
    SEARCH,
    EXTENSIONS,
    QUICK_MESSAGE,
    PROMPT,
    SKILLS,
    WORKSPACE,
    STATS,
    BACKUP,
    SETTINGS,
}

@Composable
fun StudyDesktopPage() {
    val navigator = LocalNavController.current
    val shortcuts = remember {
        listOf(
            StudyShortcut("AI 对话", "开始新的学习对话", StudyIconKind.CHAT) {
                Screen.Chat(Uuid.random().toString())
            },
            StudyShortcut("历史记录", "继续过去的对话", StudyIconKind.HISTORY) { Screen.History },
            StudyShortcut("收藏", "查看收藏内容", StudyIconKind.FAVORITE) { Screen.Favorite },
            StudyShortcut("助手", "选择与配置助手", StudyIconKind.ASSISTANT) { Screen.Assistant },
            StudyShortcut("翻译", "文本翻译工具", StudyIconKind.TRANSLATE) { Screen.Translator },
            StudyShortcut("图像生成", "生成学习插图", StudyIconKind.IMAGE) { Screen.ImageGen },
            StudyShortcut("消息搜索", "检索对话内容", StudyIconKind.SEARCH) { Screen.MessageSearch },
            StudyShortcut("扩展", "管理扩展能力", StudyIconKind.EXTENSIONS) { Screen.Extensions },
            StudyShortcut("快捷消息", "常用学习指令", StudyIconKind.QUICK_MESSAGE) { Screen.QuickMessages },
            StudyShortcut("提示词", "管理提示词模板", StudyIconKind.PROMPT) { Screen.Prompts },
            StudyShortcut("技能", "管理 AI 技能", StudyIconKind.SKILLS) { Screen.Skills },
            StudyShortcut("工作区", "文件与终端工具", StudyIconKind.WORKSPACE) { Screen.Workspaces },
            StudyShortcut("统计", "查看使用统计", StudyIconKind.STATS) { Screen.Stats },
            StudyShortcut("备份", "导入与备份数据", StudyIconKind.BACKUP) { Screen.Backup },
            StudyShortcut("设置", "全部项目设置", StudyIconKind.SETTINGS) { Screen.Setting },
        )
    }

    BackHandler { /* 学习桌面是该模式的根页面，不退出到系统桌面。 */ }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.White,
        contentColor = Color.Black,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(
                text = "学习桌面",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.Black,
            )
            Text(
                text = "RikkaHub · 专注于学习本身",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Black.copy(alpha = 0.72f),
            )
            Spacer(Modifier.height(18.dp))
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val columns = when {
                    maxWidth >= 840.dp -> 5
                    maxWidth >= 600.dp -> 4
                    else -> 3
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(shortcuts, key = { it.title }) { shortcut ->
                        StudyShortcutCard(
                            shortcut = shortcut,
                            onClick = { navigator.navigate(shortcut.destination()) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StudyShortcutCard(shortcut: StudyShortcut, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.9f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
        contentColor = Color.Black,
        border = BorderStroke(1.dp, Color.Black),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            StudyLineIcon(shortcut.icon, shortcut.title)
            Spacer(Modifier.height(10.dp))
            Text(
                text = shortcut.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = Color.Black,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = shortcut.subtitle,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = Color.Black.copy(alpha = 0.68f),
                maxLines = 2,
            )
        }
    }
}

@Composable
internal fun StudyLineIcon(
    kind: StudyIconKind,
    description: String,
    modifier: Modifier = Modifier.size(42.dp),
    tint: Color = LocalContentColor.current,
) {
    Icon(
        imageVector = kind.imageVector,
        contentDescription = description,
        modifier = modifier,
        tint = tint,
    )
}

private val StudyIconKind.imageVector: ImageVector
    get() = when (this) {
        StudyIconKind.CHAT -> HugeIcons.MessageAdd01
        StudyIconKind.HISTORY -> HugeIcons.TransactionHistory
        StudyIconKind.FAVORITE -> HugeIcons.Favourite
        StudyIconKind.ASSISTANT -> HugeIcons.AiBrain01
        StudyIconKind.TRANSLATE -> HugeIcons.Translate
        StudyIconKind.IMAGE -> HugeIcons.Image02
        StudyIconKind.SEARCH -> HugeIcons.AiSearch02
        StudyIconKind.EXTENSIONS -> HugeIcons.Puzzle
        StudyIconKind.QUICK_MESSAGE -> HugeIcons.Zap
        StudyIconKind.PROMPT -> HugeIcons.QuillWrite01
        StudyIconKind.SKILLS -> HugeIcons.MagicWand01
        StudyIconKind.WORKSPACE -> HugeIcons.Folder01
        StudyIconKind.STATS -> HugeIcons.ChartColumn
        StudyIconKind.BACKUP -> HugeIcons.DatabaseRestore
        StudyIconKind.SETTINGS -> HugeIcons.Settings02
    }
