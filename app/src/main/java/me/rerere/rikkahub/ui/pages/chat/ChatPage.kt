package me.rerere.rikkahub.ui.pages.chat

import android.net.Uri
import android.util.Log
import android.view.ViewParent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import me.rerere.ui.toast.ToastType
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.core.MessageRole
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ScreenshotServerConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.power.ReadingPowerController
import me.rerere.rikkahub.ui.components.ReadingPowerButton
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.screenshot.RemoteScreenshotClient
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.study.StudyDesktopManager
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ai.FilesPicker
import me.rerere.rikkahub.ui.components.ai.ScreenshotServerConfigDialog
import me.rerere.rikkahub.ui.components.ai.SearchMode
import me.rerere.rikkahub.ui.components.ai.completion.WorkspaceCompletionProvider
import me.rerere.rikkahub.ui.components.ai.useCropLauncher
import me.rerere.rikkahub.ui.components.ai.ChatAttachmentPickerActions
import me.rerere.rikkahub.ui.components.ai.rememberChatAttachmentPickerActions
import me.rerere.rikkahub.ui.components.ai.rememberScreenshotCaptureAction
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionReadMediaImages
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.miaomiao.MiaomiaoImportBus
import me.rerere.rikkahub.data.miaomiao.MiaomiaoService
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.base64Decode
import me.rerere.rikkahub.utils.isAllowedFileType
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.queryLatestPhotoGroup
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.io.File
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
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
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()

    val readingPower = koinInject<ReadingPowerController>()
    val pageLifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val voiceForPower by vm.voiceSession.state.collectAsStateWithLifecycle()
    val dictationForPower by LocalASRState.current.state.collectAsStateWithLifecycle()
    val speakingForPower by LocalTTSState.current.isSpeaking.collectAsStateWithLifecycle()
    val readingWindowFocused = LocalWindowInfo.current.isWindowFocused
    val latestAnswer = conversation.currentMessages.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }
    val readingToken = "${conversation.id}/${latestAnswer?.id ?: "none"}"
    val canReadAnswer = latestAnswer != null && latestAnswer.parts.isNotEmpty() &&
        latestAnswer.parts.none { it is UIMessagePart.Tool && it.isPending } &&
        pageLifecycle.isAtLeast(Lifecycle.State.RESUMED) && readingWindowFocused
    LaunchedEffect(readingToken, canReadAnswer, loadingJob, voiceForPower.isActive, dictationForPower.isRecording, speakingForPower) {
        readingPower.setReading(readingToken, canReadAnswer, loadingJob != null || voiceForPower.isActive || dictationForPower.isRecording || speakingForPower)
    }
    DisposableEffect(readingPower, readingToken) {
        onDispose { readingPower.clearReading(readingToken) }
    }

    // 新会话发出第一条消息前，跟随抽屉当前选中的文件夹（#2133）
    val drawerVm: ChatDrawerVM = koinViewModel(viewModelStoreOwner = LocalActivity.current as ComponentActivity)
    val selectedFolderId by drawerVm.selectedFolderId.collectAsStateWithLifecycle()
    LaunchedEffect(selectedFolderId) {
        vm.updateDraftFolder(selectedFolderId)
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Handle back press when drawer is open
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    // Clear input focus so popup transitions cannot reopen the keyboard.
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            focusManager.clearFocus(force = true)
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

    val startVoiceMode = rememberVoiceModeStarter(vm, setting)

    val inputState = vm.inputState

    // 错题浏览页多选导入 -> 加入当前输入框（图片直接进图，文字为每题一个 TXT 附件）。
    // 用带缓冲的 Channel 投递，聊天页在导航期间被销毁再返回也不会丢失结果。
    val miaomiaoImportBus: MiaomiaoImportBus = koinInject()
    LaunchedEffect(Unit) {
        miaomiaoImportBus.imports.collect { payload ->
            if (payload.images.isNotEmpty()) inputState.addImages(payload.images)
            if (payload.documents.isNotEmpty()) inputState.addFiles(payload.documents)
            inputState.requestInputFocus()
        }
    }

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
                    onStartVoiceMode = startVoiceMode,
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    assistant = assistant,
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
                    onStartVoiceMode = startVoiceMode,
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    assistant = assistant,
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
    onStartVoiceMode: () -> Unit,
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
    assistant: Assistant,
    currentChatModel: Model?,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    val studyManager: StudyDesktopManager = koinInject()
    var previewMode by rememberSaveable { mutableStateOf(false) }
    val hazeState = rememberHazeState()
    var showFilesSheet by rememberSaveable { mutableStateOf(false) }
    var showStudyRootPasswordDialog by remember { mutableStateOf(false) }
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()

    // 顶部「新建对话」旁的按钮：单击切换预览，长按通过 Root 静默开启学习桌面。
    fun triggerStudyRootEnable() {
        // 还没有记住过退出密码时先让用户设一个，否则 rootSilentEnable 必然失败。
        if (studyManager.rememberedPassword() == null) {
            showStudyRootPasswordDialog = true
            return
        }
        scope.launch {
            toaster.show("正在开启学习桌面…", type = ToastType.Info)
            val enabled = withContext(Dispatchers.IO) { studyManager.rootSilentEnable() }
            studyManager.refreshRootAvailability()
            if (enabled) {
                toaster.dismissAll()
                toaster.show("学习桌面已开启", type = ToastType.Success)
            } else {
                // 静默开启未成功（无 root 或系统未接受）时回退到设置页，保证流程可用。
                toaster.dismissAll()
                toaster.show("未完成静默开启，已打开设置页", type = ToastType.Warning)
                navController.navigate(Screen.SettingStudyDesktop)
            }
        }
    }

    fun onStudyRootPasswordConfirm(password: String) {
        if (password.length < StudyDesktopManager.MIN_PASSWORD_LENGTH) {
            toaster.show(
                "密码至少 ${StudyDesktopManager.MIN_PASSWORD_LENGTH} 位",
                type = ToastType.Warning,
            )
            return
        }
        showStudyRootPasswordDialog = false
        scope.launch {
            toaster.show("正在开启学习桌面…", type = ToastType.Info)
            val enabled = withContext(Dispatchers.IO) { studyManager.rootSilentEnable(password) }
            studyManager.refreshRootAvailability()
            toaster.dismissAll()
            if (enabled) {
                toaster.show("学习桌面已开启", type = ToastType.Success)
            } else {
                toaster.show("静默开启未完成，已打开设置页", type = ToastType.Warning)
                navController.navigate(Screen.SettingStudyDesktop)
            }
        }
    }

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
    val attachmentPickerActions = rememberChatAttachmentPickerActions(
        inputState = inputState,
        setting = setting,
        onAttachmentAdded = { showFilesSheet = false; inputState.requestInputFocus() },
    )
    val allowAudioVideoAttachments =
        currentChatModel?.findProvider(setting.providers) is ProviderSetting.Google

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
                    assistant = assistant,
                    chatModel = currentChatModel,
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
                    onRootSilentEnable = ::triggerStudyRootEnable,
                    onUpdateTitle = {
                        vm.updateTitle(it)
                    }
                )
            },
            bottomBar = {
                val messageQueue by vm.messageQueue.collectAsStateWithLifecycle()
                val voiceState by vm.voiceSession.state.collectAsStateWithLifecycle()
                ChatInput(
                    interactiveUiEnabled = conversation.config?.enableInteractiveUi == true,
                    onInteractiveUiEnabledChange = vm::setInteractiveUiEnabled,
                    onStartVoiceMode = onStartVoiceMode,
                    voiceState = voiceState,
                    onStopVoiceMode = vm.voiceSession::stop,
                    state = inputState,
                    messageQueue = messageQueue,
                    onRemoveQueuedMessage = vm::removeQueuedMessage,
                    onBeginEditQueuedMessage = vm::beginEditQueuedMessage,
                    onFinishEditQueuedMessage = vm::finishEditQueuedMessage,
                    onResumeMessageQueue = vm::resumeMessageQueue,
                    loading = loadingJob != null,
                    settings = setting,
                    assistant = assistant,
                    chatModel = currentChatModel,
                    hazeState = hazeState,
                    completionProviders = completionProviders,
                    onCancelClick = {
                        vm.stopGeneration()
                    },
                    onUpdateSearchMode = { mode ->
                        vm.updateSearch(
                            enableWebSearch = mode == SearchMode.LOCAL,
                            builtInSearch = mode == SearchMode.BUILT_IN,
                        )
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
                                    delay(100.milliseconds)
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
                                    delay(100.milliseconds)
                                    chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                                }
                            }
                        }
                        inputState.clearInput()
                    },
                    onUpdateChatModel = vm::setChatModel,
                    onUpdateAssistant = vm::updateAssistant,
                    onUpdateSearchService = { index ->
                        vm.updateSettings { it.copy(searchServiceSelected = index) }
                    },
                    onMoreClick = {
                        showFilesSheet = true
                    },
                )
            },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            ChatList(
                onInteractiveDraft = { text ->
                    inputState.editingMessage = null
                    inputState.appendText(text)
                    inputState.requestInputFocus()
                },
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
                    vm.selectMessageNode(newNode.id, newNode.selectIndex)
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
                    vm.updateConversationSystemPrompt(newPrompt)
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

        // Keep result launchers and imports alive while the sheet is closed.
        ChatFilesPickerSheet(
                inputState = inputState,
                setting = setting,
                conversation = conversation,
                assistant = assistant,
                chatModel = currentChatModel,
                vm = vm,
                onStartVoiceMode = onStartVoiceMode,
                onDismiss = { showFilesSheet = false },
                visible = showFilesSheet,
                attachmentPickerActions = attachmentPickerActions,
            )
    }

    if (showStudyRootPasswordDialog) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showStudyRootPasswordDialog = false },
            title = { Text("设置退出密码") },
            text = {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("退出密码（至少 4 位）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            },
            confirmButton = {
                TextButton(onClick = { onStudyRootPasswordConfirm(password) }) { Text("开启") }
            },
            dismissButton = {
                TextButton(onClick = { showStudyRootPasswordDialog = false }) { Text("取消") }
            },
        )
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
    visible: Boolean,
    attachmentPickerActions: ChatAttachmentPickerActions,
    inputState: ChatInputState,
    setting: Settings,
    conversation: Conversation,
    assistant: Assistant,
    chatModel: Model?,
    vm: ChatVM,
    onStartVoiceMode: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    val filesManager: FilesManager = koinInject()
    val screenshotClient: RemoteScreenshotClient = koinInject()
    val miaomiaoService: MiaomiaoService = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    var showScreenshotConfig by remember { mutableStateOf(false) }
    val voiceState by vm.voiceSession.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var showCompressDialog by remember { mutableStateOf(false) }

    fun dismissAll() {
        showCompressDialog = false
        onDismiss()
    }

    // 读取相册（导入最新一组照片）
    val mediaPermission = rememberPermissionState(PermissionReadMediaImages)
    PermissionManager(permissionState = mediaPermission)
    var showPhotoImportConfig by remember { mutableStateOf(false) }

    val screenshotCapture = rememberScreenshotCaptureAction(
        inputState = inputState,
        config = setting.displaySetting.screenshotServer,
        onSaveConfig = { config ->
            vm.updateSettings { latest -> latest.copy(displaySetting = latest.displaySetting.copy(screenshotServer = config)) }
        },
        onConfigure = { showScreenshotConfig = true },
        onAttachmentAdded = { dismissAll() },
    )

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
                            resources.getString(R.string.chat_input_file_read_failed, fileName),
                            type = ToastType.Error
                        )
                        return@mapNotNull null
                    }
                UIMessagePart.Document(url = localUri.toString(), fileName = fileName, mime = mime)
            } else {
                toaster.show(
                    resources.getString(R.string.chat_input_unsupported_file_type, fileName),
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

    // 喵喵机错题：短按导入作业帮最新错题；长按打开错题浏览页（多选/翻历史）
    val onImportErrorbook: () -> Unit = {
        scope.launch {
            toaster.show("正在获取最新错题…", type = ToastType.Info)
            val result = miaomiaoService.fetchLatest()
            result.onSuccess { imp ->
                val cfg = setting.displaySetting.miaomiaoErrorbook
                val hasImages = imp.images.isNotEmpty()
                var added = false
                if (miaomiaoService.shouldAddImages(cfg.importMode, hasImages)) {
                    val uris = withContext(Dispatchers.IO) { filesManager.createChatFilesByByteArrays(imp.images) }
                    if (uris.isNotEmpty()) {
                        inputState.addImages(uris)
                        added = true
                    }
                }
                // 文字以「一题一个 TXT」的附件形式导入，避免污染输入框中的提问
                if (miaomiaoService.shouldAddText(cfg.importMode, hasImages, imp.hasText) && imp.questionText != null) {
                    val doc = filesManager.createChatTextFile(imp.questionText, "错题-${imp.subjectName}")
                    inputState.addFiles(listOf(doc))
                    added = true
                }
                if (added) {
                    toaster.dismissAll()
                    toaster.show(message = "已导入「${imp.subjectName}」最新错题", type = ToastType.Success)
                    dismissAll()
                    inputState.requestInputFocus()
                } else {
                    toaster.show(message = "该错题没有可导入的内容", type = ToastType.Warning)
                }
            }.onFailure {
                toaster.show(message = "获取错题失败: ${it.message ?: "未知错误"}", type = ToastType.Error)
            }
        }
    }
    val onOpenErrorbook: () -> Unit = {
        dismissAll()
        navController.navigate(Screen.MiaomiaoErrorbook)
    }

    val filesSheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    if (visible) ModalBottomSheet(
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
            chatModel = chatModel,
            mcpManager = vm.mcpManager,
            onCompressContext = vm::handleCompressContext,
            onUpdateAssistant = vm::updateAssistant,
            onUpdateWorkspaceCwd = vm::updateWorkspaceCwd,
            showCompressDialog = showCompressDialog,
            onShowCompressDialogChange = { showCompressDialog = it },
            onDismiss = { dismissAll() },
            onCaptureScreenshot = { if (!attachmentPickerActions.cameraBusy) screenshotCapture.launch() },
            onLaunchCamera = { if (!screenshotCapture.isBusy) attachmentPickerActions.onTakePicture() },
            captureEnabled = !screenshotCapture.isBusy && !attachmentPickerActions.cameraBusy,
            captureBusy = screenshotCapture.isBusy || attachmentPickerActions.cameraBusy,
            onConfigureScreenshot = { showScreenshotConfig = true },
            onPickImage = attachmentPickerActions.onPickImage,
            onPickVideo = attachmentPickerActions.onPickVideo,
            onPickAudio = attachmentPickerActions.onPickAudio,
            onPickFile = attachmentPickerActions.onPickFile,
            onPickFileThirdParty = onLaunchThirdPartyFilePicker,
            onImportLatestPhotos = onImportLatestPhotos,
            onConfigurePhotoImport = { showPhotoImportConfig = true },
            onImportErrorbook = onImportErrorbook,
            onOpenErrorbook = onOpenErrorbook,
            onSketch = attachmentPickerActions.onSketch,
            onStartVoiceMode = if (
                setting.getSelectedASRProvider()?.supportsServerVadVoiceMode == true &&
                voiceState.phase == VoicePhase.Off
            ) {
                {
                    dismissAll()
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    onStartVoiceMode()
                }
            } else null,
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
                vm.updateSettings { latest ->
                    latest.copy(
                        displaySetting = latest.displaySetting.copy(
                            screenshotServer = ScreenshotServerConfig(host = host, port = port)
                        )
                    )
                }
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
                    vm.updateSettings { latest ->
                    latest.copy(
                            displaySetting = latest.displaySetting.copy(photoImportGroupMinutes = minutes)
                        )
                }
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
    assistant: Assistant,
    chatModel: Model?,
    conversation: Conversation,
    drawerState: DrawerState,
    bigScreen: Boolean,
    previewMode: Boolean,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onRootSilentEnable: () -> Unit,
    onUpdateTitle: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val readingPower = koinInject<ReadingPowerController>()
    val readingState by readingPower.state.collectAsStateWithLifecycle()
    val settingsStore = koinInject<SettingsStore>()
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
                    val provider = chatModel?.findProvider(providers = settings.providers, checkOverwrite = false)
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (chatModel != null && provider != null) {
                        Text(
                            text = "${assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }} / ${chatModel.displayName} (${provider.name})",
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
            ReadingPowerButton(
                enabled = settings.readingPowerEnabled,
                delaySeconds = settings.readingPowerDelaySeconds,
                state = readingState,
                onEnabledChange = { enabled ->
                    if (enabled) readingPower.requireAccess()
                    settingsStore.update { it.copy(readingPowerEnabled = enabled) }
                    readingPower.configure(enabled, settingsStore.settingsFlow.value.readingPowerDelaySeconds)
                    if (!enabled) readingPower.beforeNetwork()
                },
                onDelaySecondsChange = { seconds ->
                    settingsStore.update { it.copy(readingPowerDelaySeconds = seconds) }
                    readingPower.configure(settingsStore.settingsFlow.value.readingPowerEnabled, seconds)
                },
            )
            // 新建对话旁的按钮：单击切换预览，长按通过 Root 静默开启学习桌面
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .combinedClickable(
                        onClick = { onClickMenu() },
                        onLongClick = { onRootSilentEnable() },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet,
                    contentDescription = "Chat Options",
                    modifier = Modifier.size(24.dp),
                )
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
