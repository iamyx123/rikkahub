package me.rerere.rikkahub.data.intelligentui

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import java.security.MessageDigest
import kotlin.uuid.Uuid

const val INTERACTIVE_UI_SOURCE_LIMIT = 64 * 1024
const val INTERACTIVE_UI_STATE_LIMIT = 32 * 1024

fun interactiveUiKey(source: String): String = MessageDigest.getInstance("SHA-256")
    .digest(source.trimEnd().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Keep mode changes at the user-turn boundary; never rewrite the leading system message. */
internal fun interactiveUiDirective(previous: Boolean, enabled: Boolean, prompt: String): String? = when {
    previous == enabled -> null
    enabled -> "[RikkaHub system reminder: interactive UI enabled for subsequent replies]\n$prompt"
    else -> "[RikkaHub system reminder: interactive UI disabled for subsequent replies]\n" +
        "From this message onward, answer in ordinary Markdown. Do not generate new openui or openui-lang blocks. " +
        "Previously generated interactive cards remain available. Other instructions and tools are unchanged."
}

/**
 * Prepend immutable application reminders to their own user turn. Google/Claude discard mid-thread
 * SYSTEM messages, so the reminder is a text part of that user turn on every provider.
 * It is added after other input transforms, keeping templates/regexes from rewriting stored directives.
 */
internal fun applyInteractiveUiDirectives(messages: List<UIMessage>, fullHistory: List<UIMessage>): List<UIMessage> {
    val firstUser = messages.indexOfFirst { it.role == MessageRole.USER }
    if (firstUser < 0) return messages
    val originalIndex = fullHistory.indexOfFirst { it.id == messages[firstUser].id }
    val boundaryDirective = if (originalIndex > 0 && messages[firstUser].interactiveUiDirective == null) {
        fullHistory.take(originalIndex).lastOrNull { it.interactiveUiDirective != null }?.interactiveUiDirective
    } else {
        null
    }
    return messages.mapIndexed { index, message ->
        val directive = message.interactiveUiDirective ?: boundaryDirective.takeIf { index == firstUser }
        if (directive == null || message.role != MessageRole.USER) {
            message
        } else {
            message.copy(parts = listOf(UIMessagePart.Text(directive)) + message.parts)
        }
    }
}

internal fun Conversation.withInteractiveUiState(messageId: Uuid, source: String, state: JsonObject): Conversation {
    require(source.isNotBlank() && source.length <= INTERACTIVE_UI_SOURCE_LIMIT)
    require(state.toString().length <= INTERACTIVE_UI_STATE_LIMIT && state.size <= 32)
    require(state.keys.all { it.matches(Regex("\\$[A-Za-z_][A-Za-z0-9_]{0,63}")) })
    val node = getMessageNodeByMessageId(messageId) ?: return this
    val message = node.messages.first { it.id == messageId }
    if (message.role != MessageRole.ASSISTANT || !message.parts.filterIsInstance<UIMessagePart.Text>()
            .any { part -> interactiveUiSources(part.text).any { it.trimEnd() == source.trimEnd() } }) {
        return this
    }
    val key = interactiveUiKey(source)
    val states = message.interactiveUiStates + (key to state)
    require(states.size <= 16 && states.values.sumOf { it.toString().length } <= 128 * 1024)
    return upsertMessage(node.id, message.copy(interactiveUiStates = states))
}

internal fun interactiveUiSources(text: String): Sequence<String> =
    Regex("(?ms)^ {0,3}(`{3,}|~{3,})[ \\t]*openui(?:-lang)?[ \\t]*\\r?\\n(.*?)^ {0,3}\\1[ \\t]*(?:\\r?\\n|$)")
        .findAll(text).map { it.groupValues[2] }

fun UIMessage.interactiveUiPrintText(): String = buildString {
    append(toText())
    if (interactiveUiStates.isNotEmpty()) {
        append("\n\n交互卡片保存的变量\n")
        interactiveUiStates.values.forEach { state -> state.forEach { (name, value) -> append("\n$name = $value\n") } }
    }
}
