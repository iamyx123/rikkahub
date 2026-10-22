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
    @Test fun `bound values stay hidden and each user turn owns an immutable snapshot`() {
        val source="\$a=1\nroot=Col([Slider(\$a,-3,3,0.1,\"系数\")])"
        val card=UIMessage.assistant("```openui-lang\n$source\n```")
        val initial=conversation(card).withInteractiveUiState(card.id,source,buildJsonObject{put("\$a",2)})
        val snapshot=initial.captureInteractiveUiContext()!!
        val user=UIMessage.user("解释当前曲线").copy(interactiveUiContext=snapshot)
        val sent=initial.copy(messageNodes=initial.messageNodes+user.toMessageNode())
        val updated=sent.withInteractiveUiState(card.id,source,buildJsonObject{put("\$a",-3)})
        assertEquals(snapshot,updated.currentMessages.last().interactiveUiContext)
        assertEquals(2,snapshot["cards"]!!.jsonArray[0].jsonObject["values"]!!.jsonObject["\$a"]!!.jsonPrimitive.int)
        assertEquals("解释当前曲线",user.toText())
        assertEquals("解释当前曲线",user.interactiveUiPrintText())
        assertFalse(user.summaryAsText().contains("source_key"))
        val system=UIMessage.system("Original system")
        val provider=applyInteractiveUiDirectives(listOf(system,user),listOf(system,user))
        assertEquals(system,provider.first())
        assertTrue(provider.last().toText().contains("\"\$a\":2"))
        assertEquals("解释当前曲线",user.toText())
        val restored=Json.decodeFromString<Conversation>(Json.encodeToString(updated))
        assertEquals(snapshot,restored.currentMessages.last().interactiveUiContext)
        val edited=restored.editMessage(user.id,listOf(UIMessagePart.Text("继续解释")))
        assertEquals(snapshot,edited.currentMessages.last().interactiveUiContext)
    }
    @Test fun `custom prompt changes create a new boundary reminder without rewriting the prefix`() {
        val firstPrompt=interactiveUiPrompt("native syntax",null)
        val secondPrompt=interactiveUiPrompt("native syntax","请主动给参数图形")
        assertEquals("native syntax",firstPrompt)
        assertTrue(secondPrompt.startsWith(firstPrompt))
        assertTrue(secondPrompt.contains("请主动给参数图形"))
        assertNotEquals(interactiveUiKey(firstPrompt),interactiveUiKey(secondPrompt))
        val old=UIMessage.user("first").copy(interactiveUiMode=true,interactiveUiDirective=interactiveUiDirective(false,true,firstPrompt),interactiveUiPromptFingerprint=interactiveUiKey(firstPrompt))
        val next=UIMessage.user("next").copy(interactiveUiMode=true,interactiveUiDirective=interactiveUiDirective(true,true,secondPrompt,promptChanged=true),interactiveUiPromptFingerprint=interactiveUiKey(secondPrompt))
        val oldRequest=applyInteractiveUiDirectives(listOf(old),listOf(old))
        val newRequest=applyInteractiveUiDirectives(listOf(old,next),listOf(old,next))
        assertEquals(oldRequest.first(),newRequest.first())
        assertTrue(newRequest.last().toText().contains("请主动给参数图形"))
        assertNull(interactiveUiDirective(true,true,secondPrompt,promptChanged=false))
        assertEquals("native syntax",interactiveUiPrompt("native syntax","  "))
        assertThrows(IllegalArgumentException::class.java){interactiveUiPrompt("syntax","x".repeat(INTERACTIVE_UI_CUSTOM_PROMPT_LIMIT+1))}
    }
    @Test fun `snapshot includes older interacted cards alongside newer defaults and excludes obsolete sources and branches`() {
        val oldSource="\$a=1\nroot=Text(\$a)"
        val newSource="\$b=2\nroot=Text(\$b)"
        val oldCard=UIMessage.assistant("```openui-lang\n$oldSource\n```")
        val newer=UIMessage.assistant("```openui-lang\n$newSource\n```")
        val initial=conversation(oldCard,newer)
            .withInteractiveUiState(oldCard.id,oldSource,buildJsonObject{put("\$a",7)})
            .withInteractiveUiState(newer.id,newSource,buildJsonObject{put("\$b",2)})
        val original=initial.captureInteractiveUiContext()!!
        val cards=original["cards"]!!.jsonArray
        assertEquals(listOf(newer.id.toString(),oldCard.id.toString()),cards.map{it.jsonObject["message_id"]!!.jsonPrimitive.content})
        assertEquals(7,cards[1].jsonObject["values"]!!.jsonObject["\$a"]!!.jsonPrimitive.int)
        assertFalse(original.containsKey("truncated"))
        val editedOld=initial.currentMessages[0].copy(parts=listOf(UIMessagePart.Text("Edited reply without a card")))
        val alternate=UIMessage.assistant("Selected alternative without a card")
        val changed=initial.copy(messageNodes=listOf(editedOld.toMessageNode(),MessageNode(messages=listOf(initial.currentMessages[1],alternate),selectIndex=1)))
        assertNull(changed.captureInteractiveUiContext())
        assertEquals(2,original["cards"]!!.jsonArray.size)
    }
    @Test fun `snapshot limits cards and utf8 bytes and explicitly reports omitted state`() {
        val source="\$a=1\nroot=Text(\$a)"
        val many=(1..18).map{UIMessage.assistant("```openui-lang\n$source\n```").copy(interactiveUiStates=mapOf(interactiveUiKey(source) to buildJsonObject{put("\$a",it)}))}
        val bounded=conversation(*many.toTypedArray()).captureInteractiveUiContext()!!
        assertEquals(16,bounded["cards"]!!.jsonArray.size)
        assertTrue(bounded["truncated"]!!.jsonPrimitive.boolean)
        assertEquals(2,bounded["omitted_cards"]!!.jsonPrimitive.int)
        val large=many.take(3).map{it.copy(interactiveUiStates=mapOf(interactiveUiKey(source) to buildJsonObject{put("\$a","中".repeat(30000))}))}
        val bytes=conversation(*large.toTypedArray()).captureInteractiveUiContext()!!
        assertTrue(bytes.toString().toByteArray(Charsets.UTF_8).size<=INTERACTIVE_UI_CONTEXT_LIMIT)
        assertEquals(1,bytes["cards"]!!.jsonArray.size)
        assertEquals(2,bytes["omitted_cards"]!!.jsonPrimitive.int)
    }
    @Test fun `native drag event distinguishes transient repaint from final persistence commit`() {
        val drag=interactiveUiChange("root/children/0/value",JsonPrimitive(1.5),transient=true)
        val release=interactiveUiChange("root/children/0/value",JsonPrimitive(2),commit=true)
        assertEquals("change",drag["type"]!!.jsonPrimitive.content)
        assertTrue(drag["transient"]!!.jsonPrimitive.boolean)
        assertFalse(drag.containsKey("commit"))
        assertTrue(release["commit"]!!.jsonPrimitive.boolean)
        assertFalse(release.containsKey("transient"))
        assertEquals("root/children/0/value",release["target"]!!.jsonPrimitive.content)
    }
}
