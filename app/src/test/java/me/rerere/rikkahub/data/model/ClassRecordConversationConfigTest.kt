package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.*
import org.junit.Test

class ClassRecordConversationConfigTest {
    @Test fun `old backups leave Class Record disabled`() {
        assertFalse(Json.decodeFromString<Assistant>("{}").enableClassRecord)
        assertFalse(Json.decodeFromString<ConversationConfig>("{}").enableClassRecord)
    }

    @Test fun `Class Record snapshots and remains isolated to each conversation`() {
        val assistant = Assistant(enableClassRecord = true)
        val settings = Settings(assistantId = assistant.id, assistants = listOf(assistant))
        val bound = Conversation(assistantId = assistant.id, messageNodes = emptyList()).bindConfig(settings)
        assertTrue(bound.config!!.enableClassRecord)
        val changedAssistant = assistant.copy(enableClassRecord = false)
        val changedSettings = settings.copy(assistants = listOf(changedAssistant))
        assertTrue(changedSettings.getAssistantOf(bound).enableClassRecord)
        val changedConversation = bound.withAssistantUpdate(changedAssistant, settings)
        assertFalse(changedConversation.config!!.enableClassRecord)
        assertTrue(changedAssistant.withoutConversationFields(bound, assistant).enableClassRecord)
        assertTrue(settings.getAssistantOf(bound).enableClassRecord)
    }
}
