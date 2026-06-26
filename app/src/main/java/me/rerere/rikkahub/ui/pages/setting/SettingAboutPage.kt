package me.rerere.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Github
import me.rerere.hugeicons.stroke.SmartPhone01
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.easteregg.EmojiBurstHost
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.plus

@Composable
fun SettingAboutPage() {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val navController = LocalNavController.current
    val emojiOptions = remember {
        listOf(
            "🎉", "✨", "🌟", "💫", "🎊", "🥳", "🎈", "🎆", "🎇", "🧨",
            "🌈", "🧧", "🎁", "🍬", "🍭", "🍉", "🍓", "🍒", "🍍", "🥭",
            "🐱", "🐶", "🦊", "🐼", "🦁", "🐯", "🐵", "🦄",
            "❤️", "🧡", "💛", "💚", "💙", "💜",
            "🇨🇳", "🌏", "🌍", "🌎",
            "🤗", "🤩", "😆", "😺", "😸", "🤡",
            "💡", "🔥", "💥", "🚀", "⭐", "🌙"
        )
    }
    var logoCenterPx by remember { mutableStateOf(Offset.Zero) }
    // 作者彩蛋：爱心 + 757520 数字 emoji
    val authorEmojis = remember {
        listOf("❤️", "🧡", "💛", "💚", "💙", "💜", "💗", "7️⃣", "5️⃣", "2️⃣", "0️⃣")
    }
    var authorAvatarCenterPx by remember { mutableStateOf(Offset.Zero) }
    var showAuthorDialog by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.about_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        EmojiBurstHost(
            modifier = Modifier.fillMaxSize(),
            emojiOptions = emojiOptions,
            burstCount = 12
        ) { onBurst ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AsyncImage(
                            model = R.mipmap.ic_launcher,
                            contentDescription = "Logo",
                            modifier = Modifier
                                .clip(CircleShape)
                                .size(150.dp)
                                .onGloballyPositioned { coordinates ->
                                    val position = coordinates.positionInParent()
                                    val size = coordinates.size
                                    logoCenterPx = Offset(
                                        position.x + size.width / 2f,
                                        position.y + size.height / 2f
                                    )
                                }
                                .clickable {
                                    onBurst(logoCenterPx, null)
                                }
                        )

                        // RikkaHub × iamyx33：本墨水屏优化版由 iamyx33 与 RikkaHub 共创
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = "RikkaHub",
                                style = MaterialTheme.typography.displaySmall,
                            )
                            Text(
                                text = "×",
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontWeight = FontWeight.Light,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // 黑色头像：点击掉落作者专属彩蛋
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black)
                                    .onGloballyPositioned { coordinates ->
                                        val position = coordinates.positionInParent()
                                        val size = coordinates.size
                                        authorAvatarCenterPx = Offset(
                                            position.x + size.width / 2f,
                                            position.y + size.height / 2f
                                        )
                                    }
                                    .clickable { onBurst(authorAvatarCenterPx, authorEmojis) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "Y",
                                    color = Color.White,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            // 作者名：点击查看开发介绍
                            Text(
                                text = "iamyx33",
                                style = MaterialTheme.typography.displaySmall,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable { showAuthorDialog = true },
                            )
                        }
                    }
                }

                item {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        item(
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = { navController.navigate(Screen.Debug) },
                            ),
                            leadingContent = { Icon(HugeIcons.Code, null) },
                            supportingContent = {
                                Text("${BuildConfig.VERSION_NAME} / ${BuildConfig.VERSION_CODE}")
                            },
                            headlineContent = { Text(stringResource(R.string.about_page_version)) },
                        )
                        item(
                            leadingContent = { Icon(HugeIcons.SmartPhone01, null) },
                            supportingContent = {
                                Text("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} / SDK ${android.os.Build.VERSION.SDK_INT}")
                            },
                            headlineContent = { Text(stringResource(R.string.about_page_system)) },
                        )
                    }
                }

                item {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        item(
                            onClick = { context.openUrl("https://rikka-ai.com/") },
                            leadingContent = { Icon(HugeIcons.Earth, null) },
                            supportingContent = { Text("https://rikka-ai.com") },
                            headlineContent = { Text(stringResource(R.string.about_page_website)) },
                        )
                        item(
                            onClick = { context.openUrl("https://github.com/rikkahub/rikkahub") },
                            leadingContent = { Icon(HugeIcons.Github, null) },
                            supportingContent = { Text("https://github.com/rikkahub/rikkahub") },
                            headlineContent = { Text(stringResource(R.string.about_page_github)) },
                        )
                        item(
                            onClick = { context.openUrl("https://github.com/rikkahub/rikkahub/blob/master/LICENSE") },
                            leadingContent = { Icon(HugeIcons.File02, null) },
                            supportingContent = { Text("https://github.com/rikkahub/rikkahub/blob/master/LICENSE") },
                            headlineContent = { Text(stringResource(R.string.about_page_license)) },
                        )
                    }
                }
            }
        }

        // 作者开发介绍（点击 iamyx33 名字弹出）
        if (showAuthorDialog) {
            AlertDialog(
                onDismissRequest = { showAuthorDialog = false },
                confirmButton = {
                    TextButton(onClick = { showAuthorDialog = false }) {
                        Text("合上")
                    }
                },
                title = { Text("iamyx33 · 共创者手记") },
                text = {
                    Text(
                        text = "在一所封闭式高中的窗内，时间被钟声切成整齐的段落。我是 iamyx33，一名高二的学生，" +
                            "也是一个习惯把世界轻轻收进心里、再慢慢感受的 INFP。\n\n" +
                            "是这块小小的电子墨水屏，让 AI 成为我求学路上的同行者——在每一个需要安静思考的夜里，" +
                            "它的流畅与稳定替我挡住了浮躁，也照亮了书页之间通往远方的小路。一路走来，受益匪浅。\n\n" +
                            "这一版，是我为墨水屏量身打磨的优化：更少的闪烁，更稳的笔触，让阅读与思考都更接近纸张本来的温度，" +
                            "愿在墨水微光里努力的人，都能被温柔以待。\n\n" +
                            "「满怀希望就会所向披靡。」——《撒野》",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
            )
        }
    }
}
