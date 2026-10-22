package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.stroke.BookEdit
import me.rerere.hugeicons.stroke.ImageDownload
import me.rerere.rikkahub.data.screenshot.RemoteScreenshotClient
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.ui.toast.ToastType
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Badge
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import kotlin.uuid.Uuid
import kotlinx.coroutines.Job
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.PaintBoard
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.Video01
import me.rerere.hugeicons.stroke.Voice
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.lastContextTokens
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.files.FilesManager
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.ui.components.ui.ExtensionSelector
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.components.classrecord.ClassRecordPage
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.utils.formatNumber
import me.rerere.workspace.WorkspaceShellStatus
import org.koin.compose.koinInject

private enum class FilesPickerPage {
    MAIN,
    MCP,
    CLASS_RECORD,
    EXTENSIONS,
}

@Composable
internal fun FilesPicker(
    conversation: Conversation,
    // 会话视角下的助手和模型：会话开始后以会话上固定的配置为准
    assistant: Assistant,
    chatModel: Model?,
    state: ChatInputState,
    mcpManager: McpManager,
    onCompressContext: (additionalPrompt: String, keepRecentMessages: Int) -> Job,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateWorkspaceCwd: (String?) -> Unit,
    showCompressDialog: Boolean,
    onShowCompressDialogChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onCaptureScreenshot: () -> Unit,
    onLaunchCamera: () -> Unit,
    onConfigureScreenshot: () -> Unit,
    captureEnabled: Boolean = true,
    captureBusy: Boolean = false,
    onPickFileThirdParty: () -> Unit = {},
    onImportLatestPhotos: () -> Unit = {},
    onConfigurePhotoImport: () -> Unit = {},
    onImportErrorbook: () -> Unit = {},
    onOpenErrorbook: () -> Unit = {},
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onPickAudio: () -> Unit,
    onPickFile: () -> Unit,
    onSketch: () -> Unit,
    onStartVoiceMode: (() -> Unit)? = null,
) {
    val settings = LocalSettings.current
    val provider = chatModel?.findProvider(providers = settings.providers)
    val navController = LocalNavController.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    val classRecordFilesManager: FilesManager = koinInject()
    val attachmentContext = LocalContext.current
    val attachmentScope = rememberCoroutineScope()
    val attachmentToaster = LocalToaster.current
    val workspaces by workspaceRepository.listFlow().collectAsState(initial = emptyList())

    val boundWorkspace = remember(workspaces, assistant.workspaceId) {
        workspaces.find { it.id == assistant.workspaceId?.toString() }
    }
    val showCwd = boundWorkspace != null && boundWorkspace.shellStatus == WorkspaceShellStatus.READY.name
    var showCwdSheet by remember { mutableStateOf(false) }

    var page by remember { mutableStateOf(FilesPickerPage.MAIN) }
    // 在子页面时，返回键回到主页面而不是关闭 sheet
    BackHandler(enabled = page != FilesPickerPage.MAIN) {
        page = FilesPickerPage.MAIN
    }
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            if (settings.displaySetting.disableCursorBlink) {
                androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
            } else if (targetState != FilesPickerPage.MAIN) {
                slideInHorizontally { it } + fadeIn() togetherWith
                    slideOutHorizontally { -it } + fadeOut()
            } else {
                slideInHorizontally { -it } + fadeIn() togetherWith
                    slideOutHorizontally { it } + fadeOut()
            }
        },
        label = "FilesPickerPage"
    ) { currentPage ->
        when (currentPage) {
            FilesPickerPage.MCP -> McpPickerPage(
                assistant = assistant,
                servers = settings.mcpServers,
                mcpManager = mcpManager,
                onUpdateAssistant = onUpdateAssistant,
                onBack = { page = FilesPickerPage.MAIN },
            )

            FilesPickerPage.CLASS_RECORD -> ClassRecordPage(
                assistant = assistant,
                onUpdateAssistant = onUpdateAssistant,
                onBack = { page = FilesPickerPage.MAIN },
                onAttachText = { text ->
                    state.appendText("\n\n$text\n\n")
                    state.requestInputFocus()
                    onDismiss()
                },
                onAttachFile = { file ->
                    attachmentScope.launch {
                        try {
                            val uri = android.net.Uri.parse((file["local_uri"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                            require(uri.scheme == "file") { "请先下载文件到本机，再加入聊天。" }
                            val localFile = java.io.File(requireNotNull(uri.path)).canonicalFile
                            val uploadDirectory = java.io.File(attachmentContext.filesDir, "upload").canonicalFile
                            require(localFile.parentFile == uploadDirectory && localFile.isFile) { "只能附加已经下载到本机的课堂文件。" }
                            val managed = classRecordFilesManager.getByRelativePath("upload/${localFile.name}")
                                ?: error("文件没有登记为聊天附件，请重新下载。")
                            val mime = managed.mimeType
                            when {
                                mime.startsWith("image/") -> state.addImages(listOf(uri))
                                mime.startsWith("audio/") -> state.addAudios(listOf(uri))
                                mime.startsWith("video/") -> state.addVideos(listOf(uri))
                                else -> state.addFiles(listOf(UIMessagePart.Document(uri.toString(), managed.displayName, mime)))
                            }
                            state.requestInputFocus()
                            onDismiss()
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            attachmentToaster.show(error.message ?: "无法加入聊天", type = ToastType.Error)
                        }
                    }
                },
            )

            FilesPickerPage.EXTENSIONS -> ExtensionPickerPage(
                assistant = assistant,
                settings = settings,
                onUpdateAssistant = onUpdateAssistant,
                onBack = { page = FilesPickerPage.MAIN },
                onDismissAll = onDismiss,
            )

            FilesPickerPage.MAIN -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.8f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                AttachmentGrid(
                    actions = buildList {
                        add(AttachmentAction(
                            HugeIcons.Camera01, R.string.take_picture, onCaptureScreenshot,
                            text = "截图 / 拍照", subtitle = "单击截图 · 双击拍照",
                            onDoubleClick = onLaunchCamera, onLongClick = onConfigureScreenshot,
                            enabled = captureEnabled, busy = captureBusy,
                        ))
                        add(AttachmentAction(HugeIcons.Image02, R.string.photo, onPickImage))
                        add(AttachmentAction(HugeIcons.PaintBoard, R.string.sketch, onSketch))
                        if (provider != null && provider is ProviderSetting.Google) {
                            add(AttachmentAction(HugeIcons.Video01, R.string.video, onPickVideo))
                            add(AttachmentAction(HugeIcons.MusicNote03, R.string.audio, onPickAudio))
                        }
                        add(AttachmentAction(HugeIcons.Files02, R.string.upload_file, onPickFile))
                        add(AttachmentAction(HugeIcons.Files02, R.string.upload_file, onPickFileThirdParty, text = "第三方文件"))
                        add(AttachmentAction(HugeIcons.ImageDownload, R.string.photo, onImportLatestPhotos,
                            text = "导入照片", onLongClick = onConfigurePhotoImport))
                        add(AttachmentAction(HugeIcons.BookEdit, R.string.photo, onImportErrorbook,
                            text = "喵喵机错题", onLongClick = onOpenErrorbook))
                        onStartVoiceMode?.let { start ->
                            add(AttachmentAction(HugeIcons.Voice, R.string.chat_page_voice_title, start))
                        }
                    }
                )

                // 分段列表：各项按显示条件依次占位，首尾项才有大圆角
                val showWorkspace = workspaces.isNotEmpty()
                val showMcp = settings.mcpServers.isNotEmpty()
                // 限制了上下文消息数就是滚动上下文，旧消息会自动移出，不需要压缩
                val showCompress = assistant.contextMessageLimit <= 0
                val itemCount = 2 + listOf(showWorkspace, showWorkspace && showCwd, showMcp, showCompress).count { it }
                var itemIndex = 0
                Column(
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    if (showWorkspace) {
                        WorkspacePickerListItem(
                            assistant = assistant,
                            workspaces = workspaces,
                            shapes = ListItemDefaults.segmentedShapes(index = itemIndex++, count = itemCount),
                            onUpdateAssistant = onUpdateAssistant,
                            onNavigateToDetail = { id ->
                                onDismiss()
                                navController.navigate(Screen.WorkspaceDetail(id))
                            },
                            onNavigateToTerminal = { id ->
                                onDismiss()
                                navController.navigate(Screen.WorkspaceTerminal(id))
                            },
                            onNavigateToManage = {
                                onDismiss()
                                navController.navigate(Screen.Workspaces)
                            },
                        )

                        // Workspace CWD
                        if (showCwd) {
                            SegmentedListItem(
                                onClick = { showCwdSheet = true },
                                shapes = ListItemDefaults.segmentedShapes(index = itemIndex++, count = itemCount),
                                leadingContent = {
                                    Icon(
                                        imageVector = HugeIcons.Folder01,
                                        contentDescription = stringResource(R.string.workspace_cwd_select_directory),
                                    )
                                },
                                trailingContent = {
                                    Icon(HugeIcons.ArrowRight01, contentDescription = null)
                                },
                            ) {
                                Text(
                                    text = conversation.workspaceCwd ?: boundWorkspace?.workspaceDir().orEmpty(),
                                    // 路径的末尾更有辨识度，放不下时省略开头
                                    maxLines = 1,
                                    overflow = TextOverflow.StartEllipsis,
                                )
                            }
                        }
                    }

                    if (showMcp) {
                        McpPickerListItem(
                            assistant = assistant,
                            servers = settings.mcpServers,
                            mcpManager = mcpManager,
                            shapes = ListItemDefaults.segmentedShapes(index = itemIndex++, count = itemCount),
                            onClick = { page = FilesPickerPage.MCP },
                        )
                    }

                    SegmentedListItem(
                        onClick = { page = FilesPickerPage.CLASS_RECORD },
                        shapes = ListItemDefaults.segmentedShapes(index = itemIndex++, count = itemCount),
                        leadingContent = { Icon(HugeIcons.BookEdit, contentDescription = null) },
                        trailingContent = {
                            Text(if (assistant.enableClassRecord) "AI 已启用" else "登录与控制面板", style = MaterialTheme.typography.labelSmall)
                        },
                    ) {
                        Text("Class Record")
                    }

                    // Extensions (Quick Messages + Prompt Injections + Skills)
                    val activeCount =
                        assistant.quickMessageIds.size +
                            assistant.modeInjectionIds.size +
                            assistant.lorebookIds.size +
                            assistant.enabledSkills.size
                    SegmentedListItem(
                        onClick = { page = FilesPickerPage.EXTENSIONS },
                        shapes = ListItemDefaults.segmentedShapes(index = itemIndex++, count = itemCount),
                        leadingContent = {
                            Icon(
                                imageVector = HugeIcons.Package,
                                contentDescription = null,
                            )
                        },
                        trailingContent = {
                            if (activeCount > 0) {
                                CountBadge(count = activeCount)
                            }
                        },
                    ) {
                        Text(stringResource(R.string.assistant_page_tab_extensions))
                    }

                    // Compress History Button
                    if (showCompress) {
                        SegmentedListItem(
                            onClick = { onShowCompressDialogChange(true) },
                            shapes = ListItemDefaults.segmentedShapes(index = itemIndex++, count = itemCount),
                            leadingContent = {
                                Icon(
                                    imageVector = HugeIcons.Package01,
                                    contentDescription = null,
                                )
                            },
                            trailingContent = {
                                val contextTokens = remember(conversation.messageNodes) {
                                    conversation.lastContextTokens()
                                }
                                val contextLength = chatModel?.contextLength?.takeIf { it > 0 }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Column(horizontalAlignment = Alignment.End) {
                                        if (conversation.messageNodes.isNotEmpty()) {
                                            Text(
                                                text = stringResource(R.string.chat_page_message_count, conversation.messageNodes.size),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (contextTokens != null && contextLength != null) {
                                            Text(
                                                text = "${contextTokens.formatNumber()} / ${contextLength.formatNumber()}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    if (contextTokens != null && contextLength != null) {
                                        ContextUsageRing(fraction = contextTokens.toFloat() / contextLength)
                                    }
                                }
                            },
                        ) {
                            Text(stringResource(R.string.chat_page_compress_context))
                        }
                    }
                }
            }
        }
    }

    if (showCwdSheet && boundWorkspace != null) {
        WorkspaceCwdPickerSheet(
            workspaceId = boundWorkspace.id,
            workspaceDir = boundWorkspace.workspaceDir(),
            currentCwd = conversation.workspaceCwd,
            onSelectCwd = { newCwd ->
                onUpdateWorkspaceCwd(newCwd)
            },
            onDismiss = { showCwdSheet = false },
        )
    }

    // Compress Context Dialog
    if (showCompressDialog) {
        CompressContextDialog(onDismiss = {
            onShowCompressDialogChange(false)
            onDismiss()
        }, onConfirm = onCompressContext)
    }
}

@Composable
private fun WorkspacePickerListItem(
    assistant: Assistant,
    workspaces: List<WorkspaceEntity>,
    shapes: ListItemShapes,
    onUpdateAssistant: (Assistant) -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToTerminal: (String) -> Unit,
    onNavigateToManage: () -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }
    val boundWorkspace = remember(workspaces, assistant.workspaceId) {
        workspaces.find { it.id == assistant.workspaceId?.toString() }
    }

    SegmentedListItem(
        onClick = { showSheet = true },
        shapes = shapes,
        leadingContent = {
            Icon(
                imageVector = HugeIcons.Codesandbox,
                contentDescription = null,
            )
        },
        supportingContent = {
            Text(
                text = boundWorkspace?.name ?: stringResource(R.string.assistant_page_workspace_unbound),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (boundWorkspace != null) {
                    IconButton(onClick = { onNavigateToDetail(boundWorkspace.id) }) {
                        Icon(
                            imageVector = HugeIcons.Settings02,
                            contentDescription = stringResource(R.string.workspace_detail),
                        )
                    }
                    if (boundWorkspace.supportsTerminal()) {
                        IconButton(onClick = { onNavigateToTerminal(boundWorkspace.id) }) {
                            Icon(
                                imageVector = HugeIcons.ComputerTerminal01,
                                contentDescription = stringResource(R.string.workspace_terminal),
                            )
                        }
                    }
                }
            }
        },
    ) {
        Text(stringResource(R.string.assistant_page_workspace))
    }

    if (showSheet) {
        WorkspaceSelectSheet(
            assistant = assistant,
            workspaces = workspaces,
            onSelect = { workspaceId ->
                val newId = workspaceId?.let { Uuid.parse(it) }
                if (newId != assistant.workspaceId) {
                    // 会话的工作目录随工作区一并重置
                    onUpdateAssistant(assistant.copy(workspaceId = newId))
                }
                showSheet = false
            },
            onManage = {
                showSheet = false
                onNavigateToManage()
            },
            onDismiss = { showSheet = false },
        )
    }
}

// 扩展选择页，作为子页面嵌在加号 sheet 里
@Composable
private fun ExtensionPickerPage(
    assistant: Assistant,
    settings: Settings,
    onUpdateAssistant: (Assistant) -> Unit,
    onBack: () -> Unit,
    onDismissAll: () -> Unit,
) {
    val navController = LocalNavController.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.75f)
            .padding(horizontal = 16.dp),
    ) {
        SheetHeader(
            title = stringResource(R.string.assistant_page_tab_extensions),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))
                }
            },
        )
        ExtensionSelector(
            assistant = assistant,
            settings = settings,
            onUpdate = onUpdateAssistant,
            modifier = Modifier.weight(1f),
            onNavigateToQuickMessages = {
                onDismissAll()
                navController.navigate(Screen.QuickMessages)
            },
            onNavigateToPrompts = {
                onDismissAll()
                navController.navigate(Screen.Prompts)
            },
            onNavigateToSkills = {
                onDismissAll()
                navController.navigate(Screen.Skills)
            })

        Spacer(modifier = Modifier.height(16.dp))
    }
}

private class AttachmentAction(
    val icon: ImageVector,
    @param:StringRes val label: Int,
    val onClick: () -> Unit,
    val text: String? = null,
    val subtitle: String? = null,
    val onDoubleClick: (() -> Unit)? = null,
    val onLongClick: (() -> Unit)? = null,
    val enabled: Boolean = true,
    val busy: Boolean = false,
)

// 附件入口：一行最多四个，再多就均分成两行
@Composable
private fun AttachmentGrid(
    actions: List<AttachmentAction>,
    modifier: Modifier = Modifier,
) {
    val perRow = if (actions.size <= 4) actions.size else if (actions.size <= 8) (actions.size + 1) / 2 else 3
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        actions.chunked(perRow).fastForEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.fastForEach { action ->
                    AttachmentTile(
                        action = action,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentTile(
    action: AttachmentAction,
    modifier: Modifier = Modifier,
) {
    // One gesture owner: a double tap must never also send a computer screenshot request.
    Column(
        modifier = modifier
            .heightIn(min = 100.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .combinedClickable(
                enabled = action.enabled,
                role = Role.Button,
                onClickLabel = if (action.onDoubleClick != null) "截取电脑屏幕" else null,
                onLongClickLabel = if (action.onDoubleClick != null) "配置电脑截图服务" else null,
                onClick = action.onClick,
                onDoubleClick = action.onDoubleClick,
                onLongClick = action.onLongClick,
            )
            .semantics {
                customActions = if (action.enabled && action.onDoubleClick != null) listOf(
                    CustomAccessibilityAction("调用相机拍照") { action.onDoubleClick.invoke(); true }
                ) else emptyList()
            }
            .padding(horizontal = 8.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        if (action.busy) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
        } else {
            Icon(action.icon, contentDescription = null, modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text(
            text = action.text ?: stringResource(action.label),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 14.sp),
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        )
        action.subtitle?.let {
            Text(it, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 2)
        }
    }
}

@Composable
fun TakePicButton(
    onCaptureScreenshot: () -> Unit = {},
    onLaunchCamera: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    AttachmentTile(AttachmentAction(
        HugeIcons.Camera01, R.string.take_picture, onCaptureScreenshot,
        text = "截图 / 拍照", subtitle = "单击截图 · 双击拍照",
        onDoubleClick = onLaunchCamera, onLongClick = onLongClick, enabled = enabled,
    ))
}

// 列表项尾部的已启用数量
@Composable
internal fun CountBadge(count: Int) {
    Badge(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(count.toString())
    }
}

// 上下文使用量进度环，接近上限时变色提醒压缩
@Composable
private fun ContextUsageRing(fraction: Float) {
    val color = when {
        fraction >= 0.9f -> MaterialTheme.colorScheme.error
        fraction >= 0.7f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    CircularProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = Modifier.size(24.dp),
        color = color,
        strokeWidth = 3.dp,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
}

@Preview(showBackground = true)
@Composable
private fun AttachmentGridPreview() {
    AttachmentGrid(
        actions = listOf(
            AttachmentAction(HugeIcons.Camera01, R.string.take_picture, {}),
            AttachmentAction(HugeIcons.Image02, R.string.photo, {}),
            AttachmentAction(HugeIcons.Video01, R.string.video, {}),
            AttachmentAction(HugeIcons.MusicNote03, R.string.audio, {}),
            AttachmentAction(HugeIcons.Files02, R.string.upload_file, {}),
        ),
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
fun ScreenshotServerConfigDialog(
    initialHost: String,
    initialPort: Int,
    onDismiss: () -> Unit,
    onSave: (host: String, port: Int) -> Unit,
) {
    val screenshotClient: RemoteScreenshotClient = koinInject()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf(initialHost) }
    var portText by remember { mutableStateOf(initialPort.toString()) }
    var testing by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }

    fun currentPort(): Int = portText.toIntOrNull() ?: 5000

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("电脑截图服务") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "在电脑上运行 Screenshotter，点「自动扫描」即可发现同一局域网内的服务（无需手动输入 IP），也可手动填写其显示的 IP 与端口（默认 5000）。单击「截图 / 拍照」截取电脑所有屏幕，双击调用设备相机拍照，长按配置截图服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text("服务器 IP") },
                    placeholder = { Text("例如 192.168.1.100") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { v -> portText = v.filter { it.isDigit() }.take(5) },
                    label = { Text("端口") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        enabled = !scanning && !testing,
                        onClick = {
                            scanning = true
                            scope.launch {
                                toaster.show("正在扫描局域网...", type = ToastType.Info)
                                val result = runCatching { screenshotClient.discoverServer(currentPort()) }
                                scanning = false
                                result
                                    .onSuccess { ip ->
                                        if (ip != null) {
                                            host = ip
                                            toaster.show("发现服务器: $ip", type = ToastType.Success)
                                        } else {
                                            toaster.show(
                                                "未发现服务器，请确认电脑端已运行且处于同一局域网",
                                                type = ToastType.Warning
                                            )
                                        }
                                    }
                                    .onFailure { toaster.show("扫描失败: ${it.message}", type = ToastType.Error) }
                            }
                        },
                    ) {
                        Text(if (scanning) "扫描中..." else "自动扫描")
                    }
                    TextButton(
                        enabled = !testing && !scanning && host.isNotBlank(),
                        onClick = {
                            testing = true
                            scope.launch {
                                val result = runCatching { screenshotClient.testConnection(host, currentPort()) }
                                testing = false
                                result
                                    .onSuccess { toaster.show("连接成功", type = ToastType.Success) }
                                    .onFailure { toaster.show("连接失败: ${it.message}", type = ToastType.Error) }
                            }
                        },
                    ) {
                        Text(if (testing) "测试中..." else "测试连接")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = host.isNotBlank(),
                onClick = { onSave(host.trim(), currentPort()) },
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
