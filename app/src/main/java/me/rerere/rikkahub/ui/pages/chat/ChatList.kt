package me.rerere.rikkahub.ui.pages.chat

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ArrowDownDouble
import me.rerere.hugeicons.stroke.ArrowUpDouble
import me.rerere.hugeicons.stroke.Bookmark01
import me.rerere.hugeicons.stroke.BookmarkAdd01
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Cancel01
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalScrollCaptureInProgress
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageBookmark
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.ui.components.message.ChatMessage
import me.rerere.rikkahub.ui.components.ui.ErrorCardsDisplay
import me.rerere.rikkahub.ui.components.ui.ListSelectableItem
import me.rerere.rikkahub.ui.components.ui.RabbitLoadingIndicator
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ImeLazyListAutoScroller
import me.rerere.rikkahub.ui.theme.ChatFontProvider
import me.rerere.rikkahub.utils.plus
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

private const val TAG = "ChatList"
private const val LoadingIndicatorKey = "LoadingIndicator"
private const val ScrollBottomKey = "ScrollBottomKey"

@Composable
fun ChatList(
    innerPadding: PaddingValues,
    conversation: Conversation,
    state: LazyListState,
    loading: Boolean,
    processingStatus: String? = null,
    previewMode: Boolean,
    settings: Settings,
    hazeState: HazeState,
    errors: List<ChatError> = emptyList(),
    onDismissError: (Uuid) -> Unit = {},
    onClearAllErrors: () -> Unit = {},
    onRegenerate: (UIMessage) -> Unit = {},
    onEdit: (UIMessage) -> Unit = {},
    onForkMessage: (UIMessage) -> Unit = {},
    onDelete: (UIMessage) -> Unit = {},
    onUpdateMessage: (MessageNode) -> Unit = {},
    onClickSuggestion: (String) -> Unit = {},
    onTranslate: ((UIMessage, java.util.Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    onJumpToMessage: (index: Int, query: String) -> Unit = { _, _ -> },
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToggleFavorite: ((MessageNode) -> Unit)? = null,
    onConversationSystemPromptChange: ((String?) -> Unit)? = null,
    bookmarks: List<MessageBookmark> = emptyList(),
    onAddBookmark: (nodeId: Uuid, scrollOffset: Int, label: String) -> Unit = { _, _, _ -> },
    onDeleteBookmark: (Uuid) -> Unit = {},
    onQuote: (String) -> Unit = {},
) {
    AnimatedContent(
        targetState = previewMode,
        label = "ChatListMode",
        transitionSpec = {
            (fadeIn() + scaleIn(initialScale = 0.8f) togetherWith fadeOut() + scaleOut(targetScale = 0.8f))
        }
    ) { target ->
        if (target) {
            ChatListPreview(
                innerPadding = innerPadding,
                conversation = conversation,
                settings = settings,
                hazeState = hazeState,
                onJumpToMessage = onJumpToMessage,
                animatedVisibilityScope = this@AnimatedContent,
            )
        } else {
            ChatListNormal(
                innerPadding = innerPadding,
                conversation = conversation,
                state = state,
                loading = loading,
                processingStatus = processingStatus,
                settings = settings,
                hazeState = hazeState,
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = onRegenerate,
                onEdit = onEdit,
                onForkMessage = onForkMessage,
                onDelete = onDelete,
                onUpdateMessage = onUpdateMessage,
                onClickSuggestion = onClickSuggestion,
                onTranslate = onTranslate,
                onClearTranslation = onClearTranslation,
                animatedVisibilityScope = this@AnimatedContent,
                onToolApproval = onToolApproval,
                onToolAnswer = onToolAnswer,
                onToggleFavorite = onToggleFavorite,
                onConversationSystemPromptChange = onConversationSystemPromptChange,
                bookmarks = bookmarks,
                onAddBookmark = onAddBookmark,
                onDeleteBookmark = onDeleteBookmark,
                onQuote = onQuote,
            )
        }
    }
}

@Composable
private fun ChatListNormal(
    innerPadding: PaddingValues,
    conversation: Conversation,
    state: LazyListState,
    loading: Boolean,
    processingStatus: String? = null,
    settings: Settings,
    hazeState: HazeState,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
    onRegenerate: (UIMessage) -> Unit,
    onEdit: (UIMessage) -> Unit,
    onForkMessage: (UIMessage) -> Unit,
    onDelete: (UIMessage) -> Unit,
    onUpdateMessage: (MessageNode) -> Unit,
    onClickSuggestion: (String) -> Unit,
    onTranslate: ((UIMessage, java.util.Locale) -> Unit)?,
    onClearTranslation: (UIMessage) -> Unit,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToggleFavorite: ((MessageNode) -> Unit)? = null,
    onConversationSystemPromptChange: ((String?) -> Unit)? = null,
    bookmarks: List<MessageBookmark> = emptyList(),
    onAddBookmark: (nodeId: Uuid, scrollOffset: Int, label: String) -> Unit = { _, _, _ -> },
    onDeleteBookmark: (Uuid) -> Unit = {},
    onQuote: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val loadingState by rememberUpdatedState(loading)
    val conversationUpdated by rememberUpdatedState(conversation)
    val density = LocalDensity.current
    val activity = LocalContext.current as? me.rerere.rikkahub.RouteActivity

    // 用 rememberUpdatedState 保证监听器读取最新值: DisposableEffect(Unit) 的 lambda
    // 只捕获首次组合时的对象, 冷启动恢复会话时设置尚未加载完成(翻页开关为默认关闭),
    // 否则监听器会永远返回 false, 导致音量键翻页失效, 必须切换对话才能恢复
    val settingsUpdated by rememberUpdatedState(settings)
    val innerPaddingUpdated by rememberUpdatedState(innerPadding)
    DisposableEffect(Unit) {
        val listener: (Boolean) -> Boolean = { isVolumeUp ->
            if (settingsUpdated.displaySetting.enableVolumeKeyScroll) {
                val bottomPaddingPx = with(density) {
                    (32.dp + innerPaddingUpdated.calculateBottomPadding()).toPx()
                }
                val scrollAmount = (state.layoutInfo.viewportSize.height - bottomPaddingPx) *
                    settingsUpdated.displaySetting.volumeKeyScrollRatio
                scope.launch { state.scrollBy(if (isVolumeUp) -scrollAmount else scrollAmount) }
                true
            } else false
        }
        activity?.volumeKeyListeners?.add(listener)
        onDispose {
            activity?.volumeKeyListeners?.remove(listener)
        }
    }

    fun List<LazyListItemInfo>.isAtBottom(): Boolean {
        val lastItem = lastOrNull() ?: return false
        val inputBarHeight = with(density) { innerPadding.calculateBottomPadding().toPx() }
        val lastPos = lastItem.offset + lastItem.size
        val inputPos = (state.layoutInfo.viewportEndOffset - inputBarHeight.roundToInt())
        // println("lastPos = $lastPos, inputPos = $inputPos  | ${lastPos <= inputPos - 8}")
        return lastPos <= inputPos - 8
    }

    // 聊天选择
    val selectedItems = remember { mutableStateListOf<Uuid>() }
    var selecting by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }

    // 自动跟随键盘滚动
    ImeLazyListAutoScroller(lazyListState = state)

    // 对话大小警告对话框
    val sizeInfo = rememberConversationSizeInfo(conversation)
    var showSizeWarningDialog by rememberSaveable(conversation.id) { mutableStateOf(true) }
    if (sizeInfo.showWarning && showSizeWarningDialog) {
        ConversationSizeWarningDialog(
            sizeInfo = sizeInfo,
            onDismiss = { showSizeWarningDialog = false }
        )
    }

    val assistant = remember(settings.assistants, conversation.assistantId) {
        settings.getAssistantById(conversation.assistantId)
    }
    val modelById = remember(settings.providers) {
        settings.providers
            .flatMap { it.models }
            .associateBy { it.id }
    }
    val lastMessageIndex = conversation.messageNodes.lastIndex

    Box(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        // 自动滚动到底部
        if (settings.displaySetting.enableAutoScroll) {
            LaunchedEffect(state) {
                snapshotFlow { state.layoutInfo.visibleItemsInfo }.collect { visibleItemsInfo ->
                    // println("is bottom = ${visibleItemsInfo.isAtBottom()}, scroll = ${state.isScrollInProgress}, can_scroll = ${state.canScrollForward}, loading = $loading")
                    if (!state.isScrollInProgress && loadingState) {
                        if (visibleItemsInfo.isAtBottom()) {
                            state.requestScrollToItem(conversationUpdated.messageNodes.lastIndex + 10)
                            // Log.i(TAG, "ChatList: scroll to ${conversationUpdated.messageNodes.lastIndex}")
                        }
                    }
                }
            }
        }

        ChatFontProvider(displaySetting = settings.displaySetting) {
            LazyColumn(
                state = state,
                contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 32.dp + innerPadding.calculateBottomPadding()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState)
                    .padding(top = innerPadding.calculateTopPadding()),
            ) {
            itemsIndexed(
                items = conversation.messageNodes,
                key = { index, item -> item.id },
            ) { index, node ->
                Column {
                    ListSelectableItem(
                        key = node.id,
                        onSelectChange = {
                            if (!selectedItems.contains(node.id)) {
                                selectedItems.add(node.id)
                            } else {
                                selectedItems.remove(node.id)
                            }
                        },
                        selectedKeys = selectedItems,
                        enabled = selecting,
                    ) {
                        ChatMessage(
                            node = node,
                            model = node.currentMessage.modelId?.let(modelById::get),
                            assistant = assistant,
                            loading = loading && index == lastMessageIndex,
                            onRegenerate = {
                                onRegenerate(node.currentMessage)
                            },
                            onEdit = {
                                onEdit(node.currentMessage)
                            },
                            onFork = {
                                onForkMessage(node.currentMessage)
                            },
                            onDelete = {
                                onDelete(node.currentMessage)
                            },
                            onShare = {
                                selecting = true  // 使用 CoroutineScope 延迟状态更新
                                selectedItems.clear()
                                selectedItems.addAll(conversation.messageNodes.map { it.id }
                                    .subList(0, conversation.messageNodes.indexOf(node) + 1))
                            },
                            onUpdate = {
                                onUpdateMessage(it)
                            },
                            isFavorite = node.isFavorite,
                            onToggleFavorite = {
                                onToggleFavorite?.invoke(node)
                            },
                            onTranslate = onTranslate,
                            onClearTranslation = onClearTranslation,
                            onToolApproval = onToolApproval,
                            onToolAnswer = onToolAnswer,
                            onQuote = onQuote,
                            lastMessage = index == lastMessageIndex,
                        )
                    }
                }
            }

            if (!loading && assistant?.allowConversationSystemPrompt == true && onConversationSystemPromptChange != null) {
                item(key = "ConversationSystemPrompt") {
                    ConversationSystemPromptButton(
                        customSystemPrompt = conversation.customSystemPrompt,
                        onSystemPromptChange = onConversationSystemPromptChange,
                    )
                }
            }

            if (loading) {
                item(LoadingIndicatorKey) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RabbitLoadingIndicator(
                            modifier = Modifier.size(28.dp)
                        )
                        AnimatedVisibility(
                            visible = processingStatus != null,
                        ) {
                            Text(
                                text = processingStatus ?: "",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // 为了能正确滚动到这
            item(ScrollBottomKey) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                )
            }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 错误消息卡片
            ErrorCardsDisplay(
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(5f)
            )

            // 完成选择
            AnimatedVisibility(
                visible = selecting,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -(48).dp),
                enter = slideInVertically(
                    initialOffsetY = { it * 2 },
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it * 2 },
                ),
            ) {
                HorizontalFloatingToolbar(
                    expanded = true,
                ) {
                    Tooltip(
                        tooltip = {
                            Text("Clear selection")
                        }
                    ) {
                        IconButton(
                            onClick = {
                                selecting = false
                                selectedItems.clear()
                            }
                        ) {
                            Icon(HugeIcons.Cancel01, null)
                        }
                    }
                    Tooltip(
                        tooltip = {
                            Text("Select all")
                        }
                    ) {
                        IconButton(
                            onClick = {
                                if (selectedItems.isNotEmpty()) {
                                    selectedItems.clear()
                                } else {
                                    selectedItems.addAll(conversation.messageNodes.map { it.id })
                                }
                            }
                        ) {
                            Icon(HugeIcons.CursorPointer01, null)
                        }
                    }
                    Tooltip(
                        tooltip = {
                            Text("Confirm")
                        }
                    ) {
                        FilledIconButton(
                            onClick = {
                                selecting = false
                                val messages = conversation.messageNodes.filter { it.id in selectedItems }
                                if (messages.isNotEmpty()) {
                                    showExportSheet = true
                                }
                            }
                        ) {
                            Icon(HugeIcons.Tick01, null)
                        }
                    }
                }
            }

            // 导出对话框
            ChatExportSheet(
                visible = showExportSheet,
                onDismissRequest = {
                    showExportSheet = false
                    selectedItems.clear()
                },
                conversation = conversation,
                selectedMessages = conversation.messageNodes.filter { it.id in selectedItems }
                    .map { it.currentMessage }
            )

            val captureProgress = LocalScrollCaptureInProgress.current

            // 消息导航 + 阅读书签（常驻按钮，点击展开，无动画）
            MessageNavigator(
                show = settings.displaySetting.showMessageJumper && !captureProgress,
                onLeft = settings.displaySetting.messageJumperOnLeft,
                scope = scope,
                state = state,
                conversation = conversation,
                bookmarks = bookmarks,
                onAddBookmark = onAddBookmark,
                onDeleteBookmark = onDeleteBookmark,
                dragSensitivity = settings.displaySetting.bookmarkDragSensitivity,
                dragRange = settings.displaySetting.bookmarkDragRange,
            )

            // Suggestion
            if (conversation.chatSuggestions.isNotEmpty() && !captureProgress) {
                ChatSuggestionsRow(
                    conversation = conversation,
                    onClickSuggestion = onClickSuggestion,
                    opacity = settings.displaySetting.inputOpacity,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

/**
 * 提取包含搜索词的文本片段，确保匹配词在开头可见
 */
private fun extractMatchingSnippet(
    text: String,
    query: String
): String {
    if (query.isBlank()) {
        return text
    }

    val matchIndex = text.indexOf(query, ignoreCase = true)
    if (matchIndex == -1) {
        return text
    }

    // 直接从匹配词开始显示，确保匹配词在最前面
    val snippet = text.substring(matchIndex)

    // 只在前面有内容时添加省略号
    return if (matchIndex > 0) {
        "...$snippet"
    } else {
        snippet
    }
}

private fun buildHighlightedText(
    text: String,
    query: String,
): AnnotatedString {
    if (query.isBlank()) {
        return AnnotatedString(text)
    }

    return buildAnnotatedString {
        var startIndex = 0
        var index = text.indexOf(query, startIndex, ignoreCase = true)

        while (index >= 0) {
            // 添加高亮前的文本
            append(text.substring(startIndex, index))

            // 墨水屏优化：用加粗代替背景色高亮，既醒目又不会遮挡文字
            withStyle(
                style = SpanStyle(
                    fontWeight = FontWeight.Bold
                )
            ) {
                append(text.substring(index, index + query.length))
            }

            startIndex = index + query.length
            index = text.indexOf(query, startIndex, ignoreCase = true)
        }

        // 添加剩余文本
        if (startIndex < text.length) {
            append(text.substring(startIndex))
        }
    }
}

@Composable
private fun ChatListPreview(
    innerPadding: PaddingValues,
    conversation: Conversation,
    settings: Settings,
    hazeState: HazeState,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onJumpToMessage: (index: Int, query: String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    // 过滤消息，同时保留原始 index 避免后续 O(n) indexOf 查找
    val filteredMessages = remember(conversation.messageNodes, searchQuery) {
        if (searchQuery.isBlank()) {
            conversation.messageNodes.mapIndexed { index, node -> index to node }
        } else {
            conversation.messageNodes.mapIndexed { index, node -> index to node }
                .filter { (_, node) -> node.currentMessage.toText().contains(searchQuery, ignoreCase = true) }
        }
    }

    Column(
        modifier = Modifier
            .padding(top = innerPadding.calculateTopPadding())
            .fillMaxSize()
            .hazeSource(state = hazeState),
    ) {
        // 搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.history_page_search)) },
            leadingIcon = {
                Icon(
                    imageVector = HugeIcons.Search01,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = "Clear",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            singleLine = true,
            shape = CircleShape,
            maxLines = 1,
        )

        // 消息预览
        LazyColumn(
            contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 32.dp + innerPadding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            itemsIndexed(
                items = filteredMessages,
                key = { index, item -> item.second.id },
            ) { _, (originalIndex, node) ->
                val message = node.currentMessage
                val isUser = message.role == me.rerere.ai.core.MessageRole.USER
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (!isUser) Modifier.padding(end = 24.dp) else Modifier
                        ),
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable {
                                    onJumpToMessage(originalIndex, searchQuery)
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val highlightedText = remember(searchQuery, message) {
                                val fullText = message.toText().trim().ifBlank { "[...]" }
                                val messageText = extractMatchingSnippet(
                                    text = fullText,
                                    query = searchQuery
                                )
                                buildHighlightedText(
                                    text = messageText,
                                    query = searchQuery,
                                )
                            }
                            Text(
                                text = highlightedText,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatSuggestionsRow(
    modifier: Modifier = Modifier,
    conversation: Conversation,
    onClickSuggestion: (String) -> Unit,
    opacity: Float = 1f,
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(conversation.chatSuggestions) { suggestion ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable {
                        onClickSuggestion(suggestion)
                    }
                    .background(MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp).copy(alpha = opacity))
                    .padding(vertical = 4.dp, horizontal = 8.dp),
            ) {
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun BoxScope.MessageNavigator(
    show: Boolean,
    onLeft: Boolean,
    scope: CoroutineScope,
    state: LazyListState,
    conversation: Conversation,
    bookmarks: List<MessageBookmark>,
    onAddBookmark: (nodeId: Uuid, scrollOffset: Int, label: String) -> Unit,
    onDeleteBookmark: (Uuid) -> Unit,
    dragSensitivity: Float = 1f,
    dragRange: Float = 1f,
) {
    if (!show) return
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val toaster = LocalToaster.current
    var expanded by rememberSaveable(conversation.id) { mutableStateOf(false) }

    val messageNodes = conversation.messageNodes
    val totalNodes = messageNodes.size
    val nodeIndexById = remember(messageNodes) {
        buildMap { messageNodes.forEachIndexed { i, n -> put(n.id, i) } }
    }
    val validBookmarks = remember(bookmarks, nodeIndexById) {
        bookmarks.filter { it.nodeId in nodeIndexById }
    }

    BoxWithConstraints(modifier = Modifier.matchParentSize()) {
        val maxWpx = constraints.maxWidth.toFloat()
        val maxHpx = constraints.maxHeight.toFloat()
        val buttonSize = 46.dp
        val buttonSizePx = with(density) { buttonSize.toPx() }
        val marginPx = with(density) { 6.dp.toPx() }

        // 每「档」拖动距离：灵敏度越高距离越短；并根据可用高度自动收缩，保证 ±3 档都放得下
        val sens = dragSensitivity.coerceIn(0.5f, 2f)
        val baseStepPx = with(density) { 58.dp.toPx() } / sens
        val maxStepForFit = (maxHpx - 2 * marginPx - buttonSizePx) / 6f
        val stepPx = baseStepPx.coerceIn(with(density) { 24.dp.toPx() }, maxStepForFit.fastCoerceAtLeast(with(density) { 24.dp.toPx() }))

        // 按钮可放置的纵向安全区（上下各留 3 档），再按「范围」设置在其内收缩
        val safeMinY = 3 * stepPx + marginPx
        val safeMaxY = (maxHpx - buttonSizePx - 3 * stepPx - marginPx).fastCoerceAtLeast(safeMinY)
        val centerY = (safeMinY + safeMaxY) / 2f
        val halfRange = (safeMaxY - safeMinY) / 2f * dragRange.coerceIn(0.4f, 1f)
        val minY = centerY - halfRange
        val maxY = centerY + halfRange
        var buttonYpx by remember(conversation.id) { mutableStateOf(Float.NaN) }
        val defaultY = centerY.coerceIn(minY, maxY)
        val anchorY = (if (buttonYpx.isNaN()) defaultY else buttonYpx).coerceIn(minY, maxY)
        val anchorX = if (onLeft) marginPx else (maxWpx - buttonSizePx - marginPx)
        val buttonCenterY = anchorY + buttonSizePx / 2f

        // 拖动导航当前档位：-3顶 -2 -1 0中 1 2 3底；null=未拖动
        var navDetent by remember { mutableStateOf<Int?>(null) }
        var repositioning by remember { mutableStateOf(false) }

        // 展开的书签面板：用对齐方式固定在按钮一侧、垂直居中（不依赖测量 -> 打开即到位，无跳动、无二次重刷）
        if (expanded) {
            Surface(
                modifier = Modifier
                    .align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(
                        start = if (onLeft) buttonSize + 10.dp else 0.dp,
                        end = if (onLeft) 0.dp else buttonSize + 10.dp,
                    )
                    .widthIn(min = 160.dp, max = 240.dp)
                    .heightIn(max = with(density) { (maxHpx - 2 * marginPx).toDp() }),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val cur = state.firstVisibleItemIndex.coerceIn(0, (totalNodes - 1).fastCoerceAtLeast(0))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("书签", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "${cur + 1}/${totalNodes.fastCoerceAtLeast(1)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 在当前阅读位置新增书签（标签带「第几条 + 百分比 + 片段」，反映实际定位而非回答开头）
                    Surface(
                        onClick = {
                            val idx = state.firstVisibleItemIndex.coerceIn(0, (totalNodes - 1).fastCoerceAtLeast(0))
                            val node = messageNodes.getOrNull(idx)
                            if (node != null) {
                                val offset = state.firstVisibleItemScrollOffset
                                val itemSize = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == idx }?.size ?: 0
                                val pct = if (itemSize > 0) ((offset.toFloat() / itemSize).coerceIn(0f, 1f) * 100).roundToInt() else 0
                                val snippet = runCatching { node.currentMessage.toText() }.getOrNull()
                                    ?.trim()?.replace('\n', ' ')?.take(16)?.ifBlank { null }
                                val label = buildString {
                                    append("第${idx + 1}条")
                                    if (pct > 2) append(" ·${pct}%")
                                    if (snippet != null) append("  ").append(snippet)
                                }
                                onAddBookmark(node.id, offset, label)
                                toaster.show("已添加书签", type = ToastType.Success)
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(HugeIcons.BookmarkAdd01, null, modifier = Modifier.size(16.dp))
                            Text(
                                text = "在当前位置新增书签",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.fillMaxWidth())
                    if (validBookmarks.isEmpty()) {
                        Text(
                            text = "暂无书签（按住按钮上下拖动可快速翻页）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            validBookmarks.forEach { bm ->
                                val idx = nodeIndexById[bm.nodeId] ?: 0
                                BookmarkRow(
                                    label = bm.label.ifBlank { "第 ${idx + 1} 条" },
                                    onClick = { scope.launch { state.scrollToItem(idx, bm.scrollOffset) } },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onDeleteBookmark(bm.id)
                                        toaster.show("已删除书签", type = ToastType.Normal)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // 拖动导航：显示完整的 7 档「目标梯」（顶/上2/上1/中/下1/下2/底），当前档高亮，
        // 让用户一眼看清拖到哪触发哪个动作、便于形成肌肉记忆。仅在卡到新档位时整体更新一次（不逐帧刷新）。
        navDetent?.let { active ->
            val chipW = 112.dp
            val chipH = 38.dp
            val chipWpx = with(density) { chipW.toPx() }
            val chipHpx = with(density) { chipH.toPx() }
            val gap = with(density) { 10.dp.toPx() }
            val ladderX = if (onLeft) (anchorX + buttonSizePx + gap) else (anchorX - chipWpx - gap)
            for (d in -3..3) {
                val cy = (buttonCenterY + d * stepPx - chipHpx / 2f)
                    .coerceIn(marginPx, (maxHpx - chipHpx - marginPx).fastCoerceAtLeast(marginPx))
                val (icon, label) = detentLabel(d)
                val isActive = d == active
                Surface(
                    modifier = Modifier
                        .offset { IntOffset(ladderX.roundToInt(), cy.roundToInt()) }
                        .size(width = chipW, height = chipH),
                    shape = RoundedCornerShape(10.dp),
                    color = if (isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
                    contentColor = if (isActive) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    border = if (isActive) BorderStroke(2.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)) else null,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (icon != null) Icon(icon, null, modifier = Modifier.size(15.dp))
                        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
        }

        // 长按≥3 秒进入「移动模式」时的提示：明确告诉用户现在拖动可调整按钮位置
        if (repositioning) {
            val hintW = 132.dp
            val hintH = 36.dp
            val hintWpx = with(density) { hintW.toPx() }
            val hintHpx = with(density) { hintH.toPx() }
            val gap = with(density) { 10.dp.toPx() }
            val hx = if (onLeft) (anchorX + buttonSizePx + gap) else (anchorX - hintWpx - gap)
            val hy = (buttonCenterY - hintHpx / 2f)
                .coerceIn(marginPx, (maxHpx - hintHpx - marginPx).fastCoerceAtLeast(marginPx))
            Surface(
                modifier = Modifier
                    .offset { IntOffset(hx.roundToInt(), hy.roundToInt()) }
                    .size(width = hintW, height = hintH),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("请拖动调整位置", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }

        // 常驻按钮：位置固定（收起即在原位）。轻点=展开/收起书签面板；按住上下拖=定格快速翻页；长按≥3秒=进入移动模式
        Surface(
            modifier = Modifier
                .offset { IntOffset(anchorX.roundToInt(), anchorY.roundToInt()) }
                .size(buttonSize)
                .pointerInput(maxWpx, maxHpx, onLeft) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var mode = 0 // 0=未定 1=导航 2=移动
                        val startY = down.position.y
                        // 手指一按下就显示完整 7 档「目标梯」，让用户立刻看清拖到哪触发哪个动作
                        // （不必等拖过 touchSlop 才出现），便于形成肌肉记忆。
                        navDetent = 0
                        // 长按满 3 秒（其间几乎不动）才进入「移动按钮」模式，避免误触
                        val longPress = scope.launch {
                            delay(3000)
                            if (mode == 0) {
                                mode = 2
                                repositioning = true
                                navDetent = null // 进入移动模式：收起目标梯，改为显示「请拖动调整位置」
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        }
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUp()) break
                                val dy = change.position.y - startY
                                if (mode == 0 && kotlin.math.abs(dy) > slop) {
                                    mode = 1
                                    longPress.cancel()
                                    haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                }
                                if (mode == 1) {
                                    val d = (dy / stepPx).roundToInt().coerceIn(-3, 3)
                                    if (d != navDetent) {
                                        navDetent = d
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    change.consume() // 屏蔽底层列表滚动，避免拖动书签时画面意外滚动
                                } else if (mode == 2) {
                                    val curY = if (buttonYpx.isNaN()) defaultY else buttonYpx
                                    buttonYpx = (curY + change.positionChange().y).coerceIn(minY, maxY)
                                    change.consume()
                                }
                            }
                        } finally {
                            longPress.cancel()
                            when (mode) {
                                1 -> {
                                    val d = navDetent ?: 0
                                    navDetent = null
                                    performDetentNav(d, scope, state)
                                }

                                2 -> repositioning = false
                                else -> {
                                    // 快速轻点（未拖动也未长按）：收起目标梯并切换书签面板
                                    navDetent = null
                                    expanded = !expanded
                                }
                            }
                        }
                    }
                },
            shape = CircleShape,
            // 静止/展开态：背景完全透明，只露出一个灰色图标（不遮挡正文）；
            // 仅在「移动模式」下用实色填充+描边，提示按钮已被抓起可拖动。
            color = if (repositioning) MaterialTheme.colorScheme.primary else Color.Transparent,
            contentColor = if (repositioning) MaterialTheme.colorScheme.onPrimary else Color.Gray,
            border = if (repositioning) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (expanded) HugeIcons.Cancel01 else HugeIcons.Bookmark01,
                    contentDescription = "消息导航",
                    modifier = Modifier.padding(11.dp),
                )
            }
        }
    }
}

private fun detentLabel(d: Int): Pair<androidx.compose.ui.graphics.vector.ImageVector?, String> = when (d) {
    -3 -> HugeIcons.ArrowUpDouble to "回到顶部"
    -2 -> HugeIcons.ArrowUp01 to "上两条"
    -1 -> HugeIcons.ArrowUp01 to "上一条"
    1 -> HugeIcons.ArrowDown01 to "下一条"
    2 -> HugeIcons.ArrowDown01 to "下两条"
    3 -> HugeIcons.ArrowDownDouble to "回到底部"
    else -> null to "松手取消"
}

private fun performDetentNav(d: Int, scope: CoroutineScope, state: LazyListState) {
    scope.launch {
        val first = state.firstVisibleItemIndex
        val total = state.layoutInfo.totalItemsCount
        when (d) {
            -3 -> state.animateScrollToItem(0)
            -2 -> state.animateScrollToItem((first - 2).coerceAtLeast(0))
            -1 -> state.animateScrollToItem((first - 1).coerceAtLeast(0))
            1 -> state.animateScrollToItem(first + 1)
            2 -> state.animateScrollToItem(first + 2)
            3 -> state.animateScrollToItem((total - 1).coerceAtLeast(0))
            else -> {}
        }
    }
}

@Composable
private fun BookmarkRow(
    label: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Bookmark01,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
