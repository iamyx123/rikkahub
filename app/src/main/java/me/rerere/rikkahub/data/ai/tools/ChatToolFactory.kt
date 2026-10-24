package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.serverUrl
import me.rerere.rikkahub.data.classrecord.CLASS_RECORD_READING_PROMPT
import me.rerere.rikkahub.data.classrecord.ClassRecordReadSession
import me.rerere.rikkahub.data.classrecord.efficientClassRecordArguments
import me.rerere.rikkahub.data.classrecord.readClassRecordPages
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.workspace.WorkspaceShellStatus
import java.io.File
import kotlin.uuid.Uuid

private const val TAG = "ChatToolFactory"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

// 搜索结果的体积由用户设置的结果数决定，且结果列表 UI 与引用跳转都依赖完整的 JSON 结构，不参与截断
private val TOOLS_WITHOUT_OUTPUT_TRUNCATION = setOf("search_web")

internal fun isPagedClassRecordTool(name: String): Boolean = name.startsWith("classrecord__") &&
    name.substringAfter("classrecord__") in setOf("get_lesson_context", "fetch", "fetch_material")

internal fun isClassRecordMcpUrl(url: String): Boolean = runCatching {
    val uri = java.net.URI(url)
    uri.scheme == "https" && uri.host == "classroom.iamyx.co" && uri.path.trimEnd('/') == "/mcp"
}.getOrDefault(false)

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

class InvalidMcpServerNamesException(val names: List<String>) :
    IllegalStateException("Invalid MCP server names: ${names.joinToString(", ")}")

/** Creates the complete tool set for one generation run, including approval resumption. */
class ChatToolFactory(
    private val context: Context,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val classRecordRepository: me.rerere.rikkahub.data.classrecord.ClassRecordRepository,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
) {
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        workspaceCwd: String? = null,
    ): List<Tool> {
        val pagedClassroomMcpNames = mutableSetOf<String>()
        val workspaceTools = createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), workspaceCwd)
        val tools = buildList {
            if (assistant.enableMemory) {
                addAll(buildMemoryTools(memoryRepository, assistant.memoryId))
            }
            if (shouldUseExternalWebSearch(assistant, model)) {
                addAll(createSearchTools(settings))
            }
            addAll(localTools.getTools(assistant.localTools))
            if (assistant.enableRecentChatsReference) {
                addAll(createConversationTools(conversationRepository, assistant.id))
            }
            addAll(workspaceTools)
            if (assistant.enableClassRecord) {
                addAll(createClassRecordTools(classRecordRepository, assistant.workspaceId?.toString()))
            }
            if (assistant.enabledSkills.isNotEmpty()) {
                addAll(
                    createSkillTools(
                        enabledSkills = assistant.enabledSkills,
                        allSkills = skillManager.listSkills(),
                    )
                )
            }

            val mcpTools = mcpManager.getAllAvailableTools(assistant)
            val invalidNames = mcpTools
                .map { it.second }
                .distinct()
                .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
            if (invalidNames.isNotEmpty()) {
                throw InvalidMcpServerNamesException(invalidNames)
            }
            val classroomReadSessions = mutableMapOf<Uuid, ClassRecordReadSession>()
            val classroomPromptServers = mutableSetOf<Uuid>()
            mcpTools.forEach { (serverId, serverName, tool) ->
                val classroom = settings.mcpServers.firstOrNull { it.id == serverId }?.serverUrl?.let(::isClassRecordMcpUrl) == true
                val toolName = "mcp__${serverName}__${tool.name}"
                val session = if (classroom) classroomReadSessions.getOrPut(serverId) { ClassRecordReadSession() } else null
                if (classroom && tool.name in setOf("get_lesson_context", "fetch", "fetch_material")) pagedClassroomMcpNames.add(toolName)
                val prompt = if (classroom && classroomPromptServers.add(serverId)) CLASS_RECORD_READING_PROMPT else ""
                add(
                    Tool(
                        name = toolName,
                        description = tool.description ?: "",
                        parameters = { tool.inputSchema },
                        systemPrompt = { _, _ -> prompt },
                        needsApproval = { tool.needsApproval },
                        execute = { input ->
                            if (session == null || tool.needsApproval || tool.name in setOf("get_material_download", "get_latest_classroom_screenshot")) {
                                mcpManager.callTool(serverId, tool.name, input.jsonObject)
                            } else classRecordResult {
                                val schema = buildJsonObject { put("properties", (tool.inputSchema as? InputSchema.Obj)?.properties ?: buildJsonObject {}) }
                                val arguments = efficientClassRecordArguments(tool.name, input.jsonObject, schema)
                                session.read(tool.name, arguments) {
                                    readClassRecordPages(tool.name, arguments, { name, args ->
                                        val parts = mcpManager.callTool(serverId, name, args)
                                        val text = parts.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text ?: error("课堂工具未返回文字结果。")
                                        runCatching { Json.parseToJsonElement(text) }.getOrElse { buildJsonObject { put("isError", true); put("detail", text) } }
                                    })
                                }
                            }
                        },
                    )
                )
            }
        }
        // 过长的输出存成文件让模型用 shell 按需读取；没有 shell 时模型读不到文件，只能原样返回。
        // 文件放在本机, 只有本地 Rootfs 挂载了 /tool_outputs, 远程工作区同样读不到
        val canReadToolOutputs = workspaceTools.isNotEmpty() &&
            assistant.workspaceId?.let { workspaceRepository.getById(it.toString()) }?.capabilities()?.rootfs == true
        return if (canReadToolOutputs) tools.map { if (it.name in pagedClassroomMcpNames) it else withOutputTruncation(it) } else tools
    }

    private fun withOutputTruncation(tool: Tool): Tool {
        if (tool.name in TOOLS_WITHOUT_OUTPUT_TRUNCATION || isPagedClassRecordTool(tool.name)) return tool
        val execute = tool.execute
        return tool.copy(execute = { truncateOutput(execute(it)) })
    }

    private fun truncateOutput(output: List<UIMessagePart>): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val totalChars = textParts.sumOf { it.text.length }
        if (totalChars <= MAX_TOOL_OUTPUT_CHARS) return output

        val fileName = "${Uuid.random()}.txt"
        Log.i(TAG, "truncateOutput: saving $totalChars chars to $fileName")
        val fullText = textParts.joinToString("\n") { it.text }
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(fullText.take(TOOL_OUTPUT_PREVIEW_CHARS))
                }
            )
        ) + output.filter { it !is UIMessagePart.Text }
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String?): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }
}
