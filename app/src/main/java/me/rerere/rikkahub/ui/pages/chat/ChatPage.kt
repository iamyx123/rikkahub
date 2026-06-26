package me.rerere.rikkahub.ui.pages.chat

import android.net.Uri
import android.util.Log
import android.view.ViewParent
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ScreenshotServerConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.screenshot.RemoteScreenshotClient
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ai.FilesPicker
import me.rerere.rikkahub.ui.components.ai.ScreenshotServerConfigDialog
import me.rerere.rikkahub.ui.components.ai.completion.WorkspaceCompletionProvider
import me.rerere.rikkahub.ui.components.ai.useCropLauncher
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionReadMediaImages
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.utils.base64Decode
import me.rerere.rikkahub.utils.isAllowedFileType
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.queryLatestPhotoGroup
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.io.File
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

@Composable
fun ChatPage(id: Uuid, text: String?, files: List<Uri>, nodeId: Uuid? = null) {
    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString())
        }
    )
    val filesManager: FilesManager = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val enableWebSearch by vm.enableWebSearch.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val softwareKeyboardController = LocalSoftwareKeyboardController.current

    // Handle back press when drawer is open
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    // Hide keyboard when drawer is open
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            softwareKeyboardController?.hide()
        }
    }

    val windowAdaptiveInfo = currentWindowDpSize()
    val isBigScreen =
        windowAdaptiveInfo.width > windowAdaptiveInfo.height && windowAdaptiveInfo.width >= 1100.dp

    // 进入大屏（永久抽屉）模式时重置抽屉状态为关闭，
    // 避免从横屏旋转回竖屏后，模态抽屉残留为打开状态且无法关闭（#1304）
    LaunchedEffect(isBigScreen) {
        if (isBigScreen && drawerState.isOpen) {
            drawerState.close()
        }
    }

    val inputState = vm.inputState

    // 初始化输入状态（处理传入的 files 和 text 参数）
    LaunchedEffect(files, text) {
        if (files.isNotEmpty()) {
            val localFiles = filesManager.createChatFilesByContents(files)
            val contentTypes = files.mapNotNull { file ->
                filesManager.getFileMimeType(file)
            }
            val parts = buildList {
                localFiles.forEachIndexed { index, file ->
                    val type = contentTypes.getOrNull(index)
                    if (type?.startsWith("image/") == true) {
                        add(UIMessagePart.Image(url = file.toString()))
                    } else if (type?.startsWith("video/") == true) {
                        add(UIMessagePart.Video(url = file.toString()))
                    } else if (type?.startsWith("audio/") == true) {
                        add(UIMessagePart.Audio(url = file.toString()))
                    }
                }
            }
            inputState.messageContent = parts
        }
        text?.base64Decode()?.let { decodedText ->
            if (decodedText.isNotEmpty()) {
                inputState.setMessageText(decodedText)
            }
        }
    }

    val chatListState = rememberLazyListState()
    // 无 nodeId：首次进入定位到底部（最新消息）
    LaunchedEffect(conversation.messageNodes.size) {
        if (!vm.chatListInitialized && conversation.messageNodes.isNotEmpty() && nodeId == null) {
            chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
            vm.chatListInitialized = true
        }
    }
    // 有 nodeId（全局搜索/收藏跳转）：精确定位到该消息节点。
    // 每个不同 nodeId 只跳一次，并等待列表完成布局后再滚动，避免「只跳到开头」的竞态。
    var jumpedToNode by remember(nodeId) { mutableStateOf(false) }
    LaunchedEffect(nodeId, conversation.messageNodes) {
        val target = nodeId ?: return@LaunchedEffect
        if (jumpedToNode || conversation.messageNodes.isEmpty()) return@LaunchedEffect
        val index = conversation.messageNodes.indexOfFirst { it.id == target }
        if (index < 0) return@LaunchedEffect
        vm.chatListInitialized = true // 抑制默认底部定位抢位
        // 等待 LazyColumn 真正挂载并布局出目标项后再滚动
        snapshotFlow { chatListState.layoutInfo.totalItemsCount }
            .filter { it > index }
            .first()
        chatListState.scrollToItem(index)
        jumpedToNode = true
    }

    when {
        isBigScreen -> {
            PermanentNavigationDrawer(
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = true,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
        }

        else -> {
            ModalNavigationDrawer(
                drawerState = drawerState,
                scrimColor = Color.Transparent,
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = false,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
            BackHandler(drawerState.isOpen) {
                scope.launch { drawerState.close() }
            }
        }
    }
}

@Composable
private fun ChatPageContent(
    inputState: ChatInputState,
    loadingJob: Job?,
    processingStatus: String? = null,
    setting: Settings,
    bigScreen: Boolean,
    conversation: Conversation,
    drawerState: DrawerState,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    enableWebSearch: Boolean,
    currentChatModel: Model?,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    var previewMode by rememberSaveable { mutableStateOf(false) }
    val hazeState = rememberHazeState()
    val assistant = setting.getCurrentAssistant()
    var showFilesSheet by remember { mutableStateOf(false) }
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()

    // 预览模式点击搜索结果跳转：先切回正常列表，待列表完成布局后，按匹配词在该条消息中的
    // 字符占比估算纵向偏移，滚动定位到匹配处附近（而非只停在消息开头）。
    var pendingJump by remember { mutableStateOf<Pair<Int, String>?>(null) }
    LaunchedEffect(pendingJump) {
        val (index, query) = pendingJump ?: return@LaunchedEffect
        // 等待从预览切回正常列表并把目标项布局出来，避免「只跳到开头」的竞态
        snapshotFlow { chatListState.layoutInfo.totalItemsCount }
            .filter { it > index }
            .first()
        chatListState.scrollToItem(index)
        val fullText = conversation.messageNodes.getOrNull(index)?.currentMessage?.toText().orEmpty()
        val matchIdx = if (query.isBlank()) -1 else fullText.indexOf(query, ignoreCase = true)
        if (matchIdx > 0 && fullText.isNotEmpty()) {
            // scrollToItem 后等该项真正出现在可见区，读取其真实高度再按占比定位
            val itemInfo = snapshotFlow {
                chatListState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            }.filterNotNull().first()
            val viewportH = chatListState.layoutInfo.viewportSize.height
            val frac = matchIdx.toFloat() / fullText.length
            // 留出约 1/6 视口高度作为上方留白，让匹配处不顶在最上沿
            val offset = (frac * itemInfo.size - viewportH / 6f).roundToInt().coerceAtLeast(0)
            if (offset > 0) chatListState.scrollToItem(index, offset)
        }
        pendingJump = null
    }

    val completionProviders = remember(assistant.workspaceId, conversation.workspaceCwd, workspaceRepository) {
        assistant.workspaceId?.let { workspaceId ->
            listOf(
                WorkspaceCompletionProvider(
                    workspaceId = workspaceId.toString(),
                    repository = workspaceRepository,
                    currentCwd = conversation.workspaceCwd,
                )
            )
        }.orEmpty()
    }

    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        AssistantBackground(setting = setting, modifier = Modifier.hazeSource(hazeState))
        Scaffold(
            topBar = {
                TopBar(
                    settings = setting,
                    conversation = conversation,
                    bigScreen = bigScreen,
                    drawerState = drawerState,
                    previewMode = previewMode,
                    onNewChat = {
                        navigateToChatPage(navController)
                    },
                    onClickMenu = {
                        previewMode = !previewMode
                    },
                    onUpdateTitle = {
                        vm.updateTitle(it)
                    }
                )
            },
            bottomBar = {
                ChatInput(
                    state = inputState,
                    loading = loadingJob != null,
                    settings = setting,
                    hazeState = hazeState,
                    completionProviders = completionProviders,
                    onCancelClick = {
                        vm.stopGeneration()
                    },
                    enableSearch = enableWebSearch,
                    onToggleSearch = {
                        vm.updateSettings(setting.copy(enableWebSearch = !enableWebSearch))
                    },
                    onSendClick = {
                        if (currentChatModel == null) {
                            toaster.show("请先选择模型", type = ToastType.Error)
                            return@ChatInput
                        }
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(inputState.getContents())
                            if (setting.displaySetting.scrollToBottomOnSend) {
                                scope.launch {
                                    chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                                }
                            }
                        }
                        inputState.clearInput()
                    },
                    onLongSendClick = {
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(content = inputState.getContents(), answer = false)
                            if (setting.displaySetting.scrollToBottomOnSend) {
                                scope.launch {
                                    chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                                }
                            }
                        }
                        inputState.clearInput()
                    },
                    onUpdateChatModel = {
                        vm.setChatModel(assistant = setting.getCurrentAssistant(), model = it)
                    },
                    onUpdateAssistant = {
                        vm.updateSettings(
                            setting.copy(
                                assistants = setting.assistants.map { assistant ->
                                    if (assistant.id == it.id) {
                                        it
                                    } else {
                                        assistant
                                    }
                                }
                            )
                        )
                    },
                    onUpdateSearchService = { index ->
                        vm.updateSettings(
                            setting.copy(
                                searchServiceSelected = index
                            )
                        )
                    },
                    onMoreClick = {
                        showFilesSheet = true
                    },
                )
            },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            ChatList(
                innerPadding = innerPadding,
                conversation = conversation,
                state = chatListState,
                loading = loadingJob != null,
                processingStatus = processingStatus,
                previewMode = previewMode,
                settings = setting,
                hazeState = hazeState,
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = {
                    vm.regenerateAtMessage(it)
                },
                onEdit = {
                    inputState.editingMessage = it.id
                    inputState.setContents(it.parts)
                },
                onForkMessage = {
                    scope.launch {
                        val fork = vm.forkMessage(message = it)
                        navigateToChatPage(navController, chatId = fork.id)
                    }
                },
                onDelete = {
                    if (loadingJob != null) {
                        vm.showDeleteBlockedWhileGeneratingError()
                    } else {
                        vm.deleteMessage(it)
                    }
                },
                onUpdateMessage = { newNode ->
                    vm.updateConversation(
                        conversation.copy(
                            messageNodes = conversation.messageNodes.map { node ->
                                if (node.id == newNode.id) {
                                    newNode
                                } else {
                                    node
                                }
                            }
                        ))
                    vm.saveConversationAsync()
                },
                onClickSuggestion = { suggestion ->
                    inputState.editingMessage = null
                    inputState.setMessageText(suggestion)
                },
                onTranslate = { message, locale ->
                    vm.translateMessage(message, locale)
                },
                onClearTranslation = { message ->
                    vm.clearTranslationField(message.id)
                },
                onJumpToMessage = { index, query ->
                    previewMode = false
                    pendingJump = index to query
                },
                onToolApproval = { toolCallId, approved, reason ->
                    vm.handleToolApproval(toolCallId, approved, reason)
                },
                onToolAnswer = { toolCallId, answer ->
                    vm.handleToolAnswer(toolCallId, answer)
                },
                onToggleFavorite = { node ->
                    vm.toggleMessageFavorite(node)
                },
                onConversationSystemPromptChange = { newPrompt ->
                    vm.updateConversation(conversation.copy(customSystemPrompt = newPrompt))
                    vm.saveConversationAsync()
                },
                bookmarks = bookmarks,
                onAddBookmark = { nodeId, scrollOffset, label ->
                    vm.addBookmark(nodeId, scrollOffset, label)
                },
                onDeleteBookmark = { bookmarkId ->
                    vm.removeBookmark(bookmarkId)
                },
                onQuote = { text ->
                    inputState.editingMessage = null
                    inputState.appendQuote(text)
                    inputState.requestInputFocus()
                },
            )
        }

        if (showFilesSheet) {
            ChatFilesPickerSheet(
                inputState = inputState,
                setting = setting,
                conversation = conversation,
                assistant = assistant,
                vm = vm,
                onDismiss = { showFilesSheet = false },
            )
        }
    }
}

/**
 * 让所在的对话框/底部弹窗的独立窗口进入沉浸全屏（隐藏系统栏）。
 * ModalBottomSheet/Dialog 都运行在自己的 Window 上，不会继承 Activity 的全屏 flag，
 * 因此需要单独对其窗口应用 WindowInsetsController。
 */
@Composable
private fun ImmersiveDialogWindowEffect(enabled: Boolean) {
    if (!enabled) return
    val view = LocalView.current
    LaunchedEffect(view) {
        var parent: ViewParent? = view.parent
        var window: Window? = null
        while (parent != null) {
            if (parent is DialogWindowProvider) {
                window = parent.window
                break
            }
            parent = parent.parent
        }
        window?.let {
            WindowCompat.setDecorFitsSystemWindows(it, false)
            val controller = WindowCompat.getInsetsController(it, it.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun ChatFilesPickerSheet(
    inputState: ChatInputState,
    setting: Settings,
    conversation: Conversation,
    assistant: Assistant,
    vm: ChatVM,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val filesManager: FilesManager = koinInject()
    val screenshotClient: RemoteScreenshotClient = koinInject()
    val scope = rememberCoroutineScope()
    var showScreenshotConfig by remember { mutableStateOf(false) }
    var showInjectionSheet by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }

    fun dismissAll() {
        showInjectionSheet = false
        showCompressDialog = false
        onDismiss()
    }

    val cameraPermission = rememberPermissionState(PermissionCamera)
    PermissionManager(permissionState = cameraPermission)

    // 读取相册（导入最新一组照片）
    val mediaPermission = rememberPermissionState(PermissionReadMediaImages)
    PermissionManager(permissionState = mediaPermission)
    var showPhotoImportConfig by remember { mutableStateOf(false) }

    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var cameraOutputFile by remember { mutableStateOf<File?>(null) }
    val (_, launchCameraCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            dismissAll()
        },
        onCleanup = {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    )
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captureSuccessful ->
        if (captureSuccessful && cameraOutputUri != null) {
            if (setting.displaySetting.skipCropImage) {
                inputState.addImages(filesManager.createChatFilesByContents(listOf(cameraOutputUri!!)))
                cameraOutputFile?.delete()
                cameraOutputFile = null
                cameraOutputUri = null
                dismissAll()
            } else {
                launchCameraCrop(cameraOutputUri!!)
            }
        } else {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    }
    val onLaunchCamera: () -> Unit = {
        if (cameraPermission.allRequiredPermissionsGranted) {
            cameraOutputFile = context.cacheDir.resolve("camera_${Uuid.random()}.jpg")
            cameraOutputUri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", cameraOutputFile!!
            )
            cameraLauncher.launch(cameraOutputUri!!)
        } else {
            cameraPermission.requestPermissions()
        }
    }

    // 墨水屏设备无相机：短按拍照按钮 -> 连接电脑端 Screenshotter 服务，截取所有屏幕并加入当前对话
    val onCaptureScreenshot: () -> Unit = {
        scope.launch {
            var config = setting.displaySetting.screenshotServer
            // 未配置 IP：先自动扫描局域网，免去手动输入
            if (config.host.isBlank()) {
                toaster.show("未配置服务器，正在自动扫描局域网...", type = ToastType.Info)
                val discovered = runCatching { screenshotClient.discoverServer(config.port) }.getOrNull()
                if (discovered.isNullOrBlank()) {
                    toaster.show("未发现电脑截图服务，请长按拍照按钮手动配置", type = ToastType.Warning)
                    showScreenshotConfig = true
                    return@launch
                }
                config = config.copy(host = discovered)
                // 记住发现的 IP，下次直接使用
                vm.updateSettings(
                    setting.copy(
                        displaySetting = setting.displaySetting.copy(screenshotServer = config)
                    )
                )
                toaster.show("已发现服务器: $discovered", type = ToastType.Success)
            }
            toaster.show("正在截取电脑屏幕...", type = ToastType.Info)
            // 网络与文件写入均在 IO 线程执行，避免大图写盘阻塞主线程导致 ANR
            val result = runCatching {
                val shots = screenshotClient.captureAll(config.host, config.port)
                val uris = withContext(Dispatchers.IO) {
                    filesManager.createChatFilesByByteArrays(shots.map { it.bytes })
                }
                shots.size to uris
            }
            result
                .onSuccess { (_, uris) ->
                    if (uris.isEmpty()) {
                        toaster.show("未截取到任何屏幕", type = ToastType.Error)
                        return@onSuccess
                    }
                    inputState.addImages(uris)
                    // 清除「正在截取…」等仍在显示的 Toast，移除其全屏浮层。
                    // 该浮层在显示期间会拦截输入框与按钮的所有点击，导致用户必须等提示消失（约 3~4 秒）才能继续输入。
                    // 截图缩略图本身即为成功反馈，因此成功时不再额外弹出会遮挡输入的提示。
                    toaster.dismissAll()
                    dismissAll()
                    // 关闭底部弹窗后立即让输入框获取焦点并尝试弹出键盘，免去用户再点一次输入框。
                    inputState.requestInputFocus()
                }
                .onFailure {
                    toaster.show("截图失败: ${it.message ?: "未知错误"}", type = ToastType.Error)
                }
        }
    }

    var preCropTempFile by remember { mutableStateOf<File?>(null) }
    val (_, launchImageCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            dismissAll()
        },
        onCleanup = {
            preCropTempFile?.delete()
            preCropTempFile = null
        }
    )
    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                Log.d("ImagePickButton", "Selected URIs: $selectedUris")
                if (setting.displaySetting.skipCropImage) {
                    inputState.addImages(filesManager.createChatFilesByContents(selectedUris))
                    dismissAll()
                } else if (selectedUris.size == 1) {
                    val tempFile = File(context.appTempFolder, "pick_temp_${System.currentTimeMillis()}.jpg")
                    runCatching {
                        context.contentResolver.openInputStream(selectedUris.first())?.use { input ->
                            tempFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        preCropTempFile = tempFile
                        launchImageCrop(tempFile.toUri())
                    }.onFailure {
                        Log.e("ImagePickButton", "Failed to copy image to temp, falling back", it)
                        launchImageCrop(selectedUris.first())
                    }
                } else {
                    inputState.addImages(filesManager.createChatFilesByContents(selectedUris))
                    dismissAll()
                }
            } else {
                Log.d("ImagePickButton", "No images selected")
            }
        }

    val videoPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                inputState.addVideos(filesManager.createChatFilesByContents(selectedUris))
                dismissAll()
            }
        }

    val audioPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                inputState.addAudios(filesManager.createChatFilesByContents(selectedUris))
                dismissAll()
            }
        }

    // 把选中的文件 uri 转换为 Document 并加入输入框（SAF 与第三方选择器共用）
    fun addDocumentsFromUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val documents = uris.mapNotNull { uri ->
            val fileName = filesManager.getFileNameFromUri(uri) ?: "file"
            val mime = filesManager.getFileMimeType(uri) ?: "text/plain"
            if (isAllowedFileType(fileName, mime)) {
                val localUri = filesManager.createChatFilesByContents(listOf(uri)).firstOrNull()
                    ?: run {
                        toaster.show(
                            context.getString(R.string.chat_input_file_read_failed, fileName),
                            type = ToastType.Error
                        )
                        return@mapNotNull null
                    }
                UIMessagePart.Document(url = localUri.toString(), fileName = fileName, mime = mime)
            } else {
                toaster.show(
                    context.getString(R.string.chat_input_unsupported_file_type, fileName),
                    type = ToastType.Error
                )
                null
            }
        }
        if (documents.isNotEmpty()) {
            inputState.addFiles(documents)
            dismissAll()
        }
    }

    // 系统文件选择器(SAF / ACTION_OPEN_DOCUMENT)，仅显示 DocumentsProvider
    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            addDocumentsFromUris(uris)
        }

    // 第三方文件选择器：用 ACTION_GET_CONTENT + createChooser 强制弹出应用选择框，
    // 这样 MT 管理器 / MiXplorer 等第三方文件管理器才会出现（Android 13/14 上 GetMultipleContents
    // 会被系统直接路由到自带 DocumentsUI / 照片选择器，看不到第三方）。
    val thirdPartyFilePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val data = result.data
                val uris = buildList {
                    val clip = data?.clipData
                    if (clip != null) {
                        for (i in 0 until clip.itemCount) {
                            clip.getItemAt(i).uri?.let { add(it) }
                        }
                    } else {
                        data?.data?.let { add(it) }
                    }
                }
                addDocumentsFromUris(uris)
            }
        }
    val onLaunchThirdPartyFilePicker: () -> Unit = {
        val getContent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        runCatching {
            thirdPartyFilePickerLauncher.launch(
                android.content.Intent.createChooser(getContent, "选择文件（可用第三方文件管理器）")
            )
        }.onFailure {
            toaster.show("无法打开文件选择器: ${it.message ?: ""}", type = ToastType.Error)
        }
    }

    // 导入相册「最新一组照片」：需要读取相册权限
    val onImportLatestPhotos: () -> Unit = {
        if (mediaPermission.allPermissionsGranted) {
            scope.launch {
                val minutes = setting.displaySetting.photoImportGroupMinutes
                val uris = withContext(Dispatchers.IO) { queryLatestPhotoGroup(context, minutes) }
                if (uris.isEmpty()) {
                    toaster.show("相册里没有找到照片", type = ToastType.Warning)
                } else {
                    val localUris = withContext(Dispatchers.IO) {
                        filesManager.createChatFilesByContents(uris)
                    }
                    if (localUris.isEmpty()) {
                        toaster.show("照片读取失败", type = ToastType.Error)
                    } else {
                        inputState.addImages(localUris)
                        toaster.show("已导入最新 ${localUris.size} 张照片", type = ToastType.Success)
                        dismissAll()
                        inputState.requestInputFocus()
                    }
                }
            }
        } else {
            mediaPermission.requestPermissions()
        }
    }

    val filesSheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    ModalBottomSheet(
        scrimColor = androidx.compose.ui.graphics.Color.Transparent,
        sheetState = filesSheetState,
        onDismissRequest = { dismissAll() },
    ) {
        // 沉浸模式下，让底部弹窗所在的独立窗口同样隐藏系统栏，
        // 避免点「+」弹出选择面板时状态栏/导航栏复现导致全屏丢失
        ImmersiveDialogWindowEffect(setting.displaySetting.enableImmersiveMode)
        FilesPicker(
            conversation = conversation,
            state = inputState,
            assistant = assistant,
            mcpManager = vm.mcpManager,
            onCompressContext = { additionalPrompt, targetTokens, keepRecentMessages ->
                vm.handleCompressContext(additionalPrompt, targetTokens, keepRecentMessages)
            },
            onUpdateAssistant = {
                vm.updateSettings(
                    setting.copy(
                        assistants = setting.assistants.map { assistant ->
                            if (assistant.id == it.id) {
                                it
                            } else {
                                assistant
                            }
                        }
                    )
                )
            },
            onUpdateConversation = {
                vm.updateConversation(it)
                vm.saveConversationAsync()
            },
            showInjectionSheet = showInjectionSheet,
            onShowInjectionSheetChange = { showInjectionSheet = it },
            showCompressDialog = showCompressDialog,
            onShowCompressDialogChange = { showCompressDialog = it },
            onDismiss = { dismissAll() },
            onTakePic = onCaptureScreenshot,
            onConfigureScreenshot = { showScreenshotConfig = true },
            onPickImage = { imagePickerLauncher.launch("image/*") },
            onPickVideo = { videoPickerLauncher.launch("video/*") },
            onPickAudio = { audioPickerLauncher.launch("audio/*") },
            onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
            onPickFileThirdParty = onLaunchThirdPartyFilePicker,
            onImportLatestPhotos = onImportLatestPhotos,
            onConfigurePhotoImport = { showPhotoImportConfig = true },
        )
    }

    // 电脑截图服务配置对话框（长按拍照按钮触发）
    if (showScreenshotConfig) {
        val screenshotConfig = setting.displaySetting.screenshotServer
        ScreenshotServerConfigDialog(
            initialHost = screenshotConfig.host,
            initialPort = screenshotConfig.port,
            onDismiss = { showScreenshotConfig = false },
            onSave = { host, port ->
                vm.updateSettings(
                    setting.copy(
                        displaySetting = setting.displaySetting.copy(
                            screenshotServer = ScreenshotServerConfig(host = host, port = port)
                        )
                    )
                )
                toaster.show("已保存电脑截图服务配置", type = ToastType.Success)
                showScreenshotConfig = false
            },
        )
    }

    // 「导入照片」同组时间阈值配置（长按导入照片按钮触发）
    if (showPhotoImportConfig) {
        var minutes by remember { mutableStateOf(setting.displaySetting.photoImportGroupMinutes.coerceIn(1, 60)) }
        AlertDialog(
            onDismissRequest = { showPhotoImportConfig = false },
            title = { Text("最新一组照片") },
            text = {
                Column {
                    Text(
                        text = "以相册最新一张照片为基准，向前 $minutes 分钟内的照片都算作同一组一并导入。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Slider(
                            value = minutes.toFloat(),
                            onValueChange = { minutes = it.toInt().coerceIn(1, 60) },
                            valueRange = 1f..60f,
                            modifier = Modifier.weight(1f),
                        )
                        Text(text = "$minutes 分钟", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateSettings(
                        setting.copy(
                            displaySetting = setting.displaySetting.copy(photoImportGroupMinutes = minutes)
                        )
                    )
                    showPhotoImportConfig = false
                }) { Text(stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showPhotoImportConfig = false }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            },
        )
    }
}

@Composable
private fun TopBar(
    settings: Settings,
    conversation: Conversation,
    drawerState: DrawerState,
    bigScreen: Boolean,
    previewMode: Boolean,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onUpdateTitle: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val titleState = useEditState<String> {
        onUpdateTitle(it)
    }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            if (!bigScreen) {
                IconButton(
                    onClick = {
                        scope.launch { drawerState.open() }
                    }
                ) {
                    Icon(HugeIcons.Menu03, "Messages")
                }
            }
        },
        title = {
            val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
            Surface(
                onClick = {
                    if (conversation.messageNodes.isNotEmpty()) {
                        titleState.open(conversation.title)
                    } else {
                        toaster.show(editTitleWarning, type = ToastType.Warning)
                    }
                },
                color = Color.Transparent,
            ) {
                Column {
                    val assistant = settings.getCurrentAssistant()
                    val model = settings.getCurrentChatModel()
                    val provider = model?.findProvider(providers = settings.providers, checkOverwrite = false)
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model != null && provider != null) {
                        Text(
                            text = "${assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }} / ${model.displayName} (${provider.name})",
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            color = LocalContentColor.current.copy(0.65f),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                            )
                        )
                    }
                }
            }
        },
        actions = {
            IconButton(
                onClick = {
                    onClickMenu()
                }
            ) {
                Icon(if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet, "Chat Options")
            }

            IconButton(
                onClick = {
                    onNewChat()
                }
            ) {
                Icon(HugeIcons.MessageAdd01, "New Message")
            }
        },
    )
    titleState.EditStateContent { title, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                titleState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_title))
            },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        titleState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        titleState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}
