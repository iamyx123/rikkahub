package me.rerere.rikkahub.data.intelligentui

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class InteractiveUiProtocolTest {
    private fun conversation(vararg messages: UIMessage) = Conversation(assistantId=Uuid.random(),messageNodes=messages.map{it.toMessageNode()})
    @Test fun `toggle affects next turn and leaves exact prior request prefix intact`() {
        val system=UIMessage.system("Original system")
        val first=UIMessage.user("first").copy(interactiveUiMode=false)
        val enabled=UIMessage.user("show quiz").copy(interactiveUiMode=true,interactiveUiDirective=interactiveUiDirective(false,true,"library-v1"))
        val continued=UIMessage.user("more").copy(interactiveUiMode=true,interactiveUiDirective=interactiveUiDirective(true,true,"library-v2"))
        val disabled=UIMessage.user("plain").copy(interactiveUiMode=false,interactiveUiDirective=interactiveUiDirective(true,false,""))
        val history=listOf(system,first,enabled,continued,disabled)
        val prefix=applyInteractiveUiDirectives(history.take(3),history.take(3))
        val all=applyInteractiveUiDirectives(history,history)
        assertEquals(prefix,all.take(3));assertEquals(system,all.first());assertEquals(continued,all[3])
        assertTrue((all[2].parts.first() as UIMessagePart.Text).text.contains("library-v1"))
        assertTrue((all[4].parts.first() as UIMessagePart.Text).text.contains("disabled"))
        assertNull(interactiveUiDirective(false,false,""));assertNull(interactiveUiDirective(true,true,"changed"))
        assertEquals("show quiz",enabled.toText())
    }
    @Test fun `truncation and checkpoints carry last retained mode without moving leading system`() {
        val enabled=UIMessage.user("show").copy(interactiveUiMode=true,interactiveUiDirective="frozen-library")
        val next=UIMessage.user("summary").copy(isContextCheckpoint=true)
        val system=UIMessage.system("Original")
        val reduced=applyInteractiveUiDirectives(listOf(system,next),listOf(enabled,next))
        assertEquals(system,reduced.first());assertEquals("frozen-library",(reduced[1].parts.first() as UIMessagePart.Text).text)
    }
    @Test fun `old cards survive config off serialization and editing user branch`() {
        val code="root = Col([Text(\"中文\")])"
        val card=UIMessage.assistant("```openui-lang\n$code\n```\n")
        val user=UIMessage.user("hello").copy(interactiveUiMode=true,interactiveUiDirective="frozen")
        val state=buildJsonObject{put("\$n",4)}
        val c=conversation(user,card).copy(config=ConversationConfig(enableInteractiveUi=false)).withInteractiveUiState(card.id,code,state)
        val restored=Json.decodeFromString<Conversation>(Json.encodeToString(c))
        assertEquals(state,restored.currentMessages[1].interactiveUiStates[interactiveUiKey(code)])
        assertFalse(restored.config!!.enableInteractiveUi)
        val edited=restored.editMessage(user.id,listOf(UIMessagePart.Text("edited"))).currentMessages.first()
        assertEquals("frozen",edited.interactiveUiDirective);assertEquals(true,edited.interactiveUiMode)
        assertEquals(interactiveUiKey(code),interactiveUiKey(code+"\n"))
    }
    @Test fun `stale unknown user and non fenced blocks never create state or resurrect messages`() {
        val code="root = Col([])";val state=buildJsonObject{put("\$n",1)}
        val user=UIMessage.user("```openui-lang\n$code\n```")
        val plain=UIMessage.assistant(code)
        val c=conversation(user,plain)
        assertSame(c,c.withInteractiveUiState(Uuid.random(),code,state))
        assertSame(c,c.withInteractiveUiState(user.id,code,state))
        assertSame(c,c.withInteractiveUiState(plain.id,code,state))
        assertThrows(IllegalArgumentException::class.java){c.withInteractiveUiState(plain.id,"",state)}
        assertThrows(IllegalArgumentException::class.java){c.withInteractiveUiState(plain.id,code,buildJsonObject{put("unsafe",1)})}
    }
}
