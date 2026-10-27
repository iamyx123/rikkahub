package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

@Serializable
data class Settings(
    @Transient
    val init: Boolean = false,
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val customThemes: List<CustomTheme> = emptyList(),
    val developerMode: Boolean = false,
    val readingPowerEnabled: Boolean = false,
    val readingPowerDelaySeconds: Int = 30,
    // Acknowledges the app explanation only; actual Root access is always checked separately.
    val readingPowerNoticeAcknowledged: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val networkSetting: NetworkSetting = NetworkSetting(),
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val fastModelId: Uuid = Uuid.random(),
    val fastModelReasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    // 记忆整理用的模型，没设置时用快速模型
    val memoryModelId: Uuid? = null,
    val memoryModelReasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val imageGenerationModelId: Uuid = Uuid.random(),
    val enableAutoTitle: Boolean = true,
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    // 翻译页的目标语言，存 BCP 47 语言标签
    val translateTargetLanguage: String = "zh-CN",
    val enableSuggestion: Boolean = true,
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    // null uses the bundled native UI instructions; a custom value adds user preferences.
    val interactiveUiPrompt: String? = null,
    val ocrModelId: Uuid = Uuid.random(),
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    // RemoteFileStore 上传临时素材用的桶，和备份的 s3Config 互不影响；items 字段在这里不使用
    val uploadS3Config: S3Config = S3Config(),
    val mediaGenerationProviders: List<MediaGenerationProviderSetting> = emptyList(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val defaultTTSPlaybackSpeed: Float = 1.0f,
    val asrProviders: List<ASRProviderSetting> = emptyList(),
    val selectedASRProviderId: Uuid? = null,
    val modeInjections: List<PromptInjection.ModeInjection> = DEFAULT_MODE_INJECTIONS,
    val lorebooks: List<Lorebook> = emptyList(),
    val quickMessages: List<QuickMessage> = emptyList(),
    val webServerEnabled: Boolean = false,
    val webServerPort: Int = 8080,
    val webServerJwtEnabled: Boolean = false,
    val webServerAccessPassword: String = "",
    val webServerLocalhostOnly: Boolean = false,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val sponsorAlertDismissedAt: Int = 0,
) {
    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

@Serializable
data class NetworkSetting(
    val userAgent: String = "",
    val proxyUrl: String = "",
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val enableAutoRetry: Boolean = true,
)

@Serializable
enum class ChatFontFamily {
    @SerialName("default")
    DEFAULT,

    @SerialName("serif")
    SERIF,

    @SerialName("monospace")
    MONOSPACE,

    @SerialName("custom")
    CUSTOM,
}

@Serializable
enum class BackgroundEffectType {
    @SerialName("blur")
    BLUR,

    @SerialName("glass")
    GLASS,
}

@Serializable
enum class ConversationSortOrder {
    @SerialName("update_time")
    UPDATE_TIME,

    @SerialName("create_time")
    CREATE_TIME,
}

@Serializable
data class DisplaySetting(
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val bubbleOpacity: Float = 1.0f,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateTimeInMessage: Boolean = false,
    val showTokenUsage: Boolean = true,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    val updateCheckDisabledUntilEpochMillis: Long = 0L,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val skipCropImage: Boolean = true,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableLiveUpdateNotification: Boolean = false,
    val enableFloatingChatWindow: Boolean = false,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    val showLineNumbers: Boolean = false,
    val ttsOnlyReadQuoted: Boolean = false,
    val ttsOnlyReadOutsideBrackets: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val voiceModeListenWhileReplying: Boolean = true,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableQuoteHighlight: Boolean = false,
    val enableBlurEffect: Boolean = false,
    val backgroundEffectType: BackgroundEffectType = BackgroundEffectType.BLUR,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val chatCustomFontPath: String = "",
    val chatCustomFontName: String = "",
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
    val scrollToBottomOnSend: Boolean = true,
    val enableImmersiveMode: Boolean = false,
    // 单击连接电脑端 Screenshotter 服务截图，双击调用设备相机，长按配置服务
    val screenshotServer: ScreenshotServerConfig = ScreenshotServerConfig(),
    // 墨水屏优化：禁用界面动画与文本光标闪烁，减少屏幕刷新、降低耗电
    val disableCursorBlink: Boolean = false,
    // 输入框（及回答建议）普通透明度，仅在未启用毛玻璃时生效，1.0=不透明
    val inputOpacity: Float = 1.0f,
    // 导入相册「最新一组照片」的时间阈值（分钟）：最新一张到与其间隔不超过该值的照片为同一组
    val photoImportGroupMinutes: Int = 2,
    // 导出 Markdown 时不包含图片（普通 Markdown 解析器不支持内嵌 base64 图片）
    val exportMarkdownExcludeImages: Boolean = false,
    // 书签导航拖动灵敏度：越大每「档」需要拖动的距离越短（越灵敏）。0.5(迟钝)~2.0(灵敏)
    val bookmarkDragSensitivity: Float = 1.0f,
    // 书签按钮可上下移动的范围（占可用高度比例）。0.4~1.0
    val bookmarkDragRange: Float = 1.0f,
    // 喵喵机错题本（作业帮账号）配置
    val miaomiaoErrorbook: MiaomiaoErrorbookConfig = MiaomiaoErrorbookConfig(),
    // 喵喵机 N2 蓝牙打印机配置
    val paperangPrinter: PaperangPrinterConfig = PaperangPrinterConfig(),
    val conversationSortOrder: ConversationSortOrder = ConversationSortOrder.UPDATE_TIME,
)

// 喵喵机错题本导入内容模式
@Serializable
enum class MiaomiaoImportMode {
    IMAGE_ONLY,   // 仅导入图片
    TEXT_ONLY,    // 仅导入文字(LaTeX)
    BOTH,         // 图片 + 文字都导入
}

// 作业帮错题本账号与导入偏好（密码仅存于本地 DataStore）
@Serializable
data class MiaomiaoErrorbookConfig(
    val phone: String = "",
    val password: String = "",
    val lastGroupId: Int = 0,        // 上次选择的科目
    val lastGroupName: String = "",
    val importMode: MiaomiaoImportMode = MiaomiaoImportMode.IMAGE_ONLY,
    // 图片优先标签：题干/答案/解析/裁剪/自拍；导入时按此优先，找不到用第一张
    val preferImageLabel: String = "题干",
    // ── 导入哪些内容（图片按来源分类，文字按小节分类）──
    // 官方题库清晰图（题干）
    val includeOfficialQuestionImage: Boolean = true,
    // 官方答案/解析图
    val includeOfficialAnswerImage: Boolean = true,
    // 自己拍的原始照片（含 App 裁剪/增强图）
    val includePhotoImage: Boolean = true,
    // 官方题干文字（HTML/LaTeX 转纯文本）
    val includeQuestionText: Boolean = true,
    // 官方答案文字
    val includeAnswerText: Boolean = false,
    // 官方 AI 逐步解析文字（默认关，解析交给 AI）
    val includeAnalysis: Boolean = false,
    // 没有官方内容（isQuery=false，云端只有照片）的题：导入时跳过
    val skipWithoutOfficial: Boolean = false,
)

// 喵喵机 N2 蓝牙打印机配置
@Serializable
data class PaperangPrinterConfig(
    val deviceAddress: String = "",
    val deviceName: String = "",
    val density: Int = 90,           // 打印浓度 1-255
    val grayscale: Boolean = false,  // 照片用灰度，文字/题目用黑白
    val autoReconnect: Boolean = true,
    val feedAfter: Int = 0,          // 打印后走纸行数（0=不走纸，N2 有自动走纸机制，避免浪费纸张）
    val printFontScale: Float = 1.0f,// AI 回复打印字体大小倍率（0.5~2.0）
    // 纸张宽度手动覆盖（px）：0=自动检测，576=2寸，864=3寸；检测不准时手动指定
    val paperWidthOverride: Int = 0,
)

// 电脑端 Screenshotter 服务端配置（仅本地局域网使用，IP+端口持久化）
@Serializable
data class ScreenshotServerConfig(
    val host: String = "",
    val port: Int = 5000,
)

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "rikkahub_backups",
    val items: List<BackupItem> = listOf(
        BackupItem.DATABASE,
        BackupItem.FILES
    ),
) {
    @Serializable
    enum class BackupItem {
        DATABASE,
        FILES,
    }
}

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
)
