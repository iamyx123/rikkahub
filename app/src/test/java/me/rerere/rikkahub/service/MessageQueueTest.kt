package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import me.rerere.rikkahub.data.intelligentui.InteractiveUiPendingUpdates
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class MessageQueueTest {
    private fun text(value: String) = listOf(UIMessagePart.Text(value))

    @Test
    fun `failed interactive state save retains dispatched text and attachments until explicit resume`() = runBlocking {
        val queue = MessageQueue()
        val updates = InteractiveUiPendingUpdates(this)
        val conversationId = Uuid.random()
        val parts = text("解释当前曲线") + UIMessagePart.Image("file:///upload/example.png")
        queue.enqueue(parts, answer = false)
        queue.enqueue(text("later"))
        val dispatched = queue.takeNext()!!
        updates.enqueue(conversationId, "card", buildJsonObject {}) { throw IllegalStateException("disk failed") }
        val failure = runCatching { updates.awaitIdle(conversationId) }.exceptionOrNull() as Exception
        queue.pauseAfterSubmissionFailure(dispatched, failure, userMessageCreated = false)

        assertTrue(queue.state.value.paused)
        assertNull(queue.takeNext())
        assertEquals(parts, queue.state.value.messages.first().parts)
        assertEquals(emptySet<String>(), unreferencedQueuedAttachmentUrls(dispatched, emptyList(), queue.state.value.messages))
        // Failure notification or retry must never duplicate the same pending item.
        queue.pauseAfterSubmissionFailure(dispatched, failure, userMessageCreated = false)
        assertEquals(2, queue.state.value.messages.size)
        queue.resume()
        val retry = queue.takeNext()!!
        assertEquals(dispatched.id, retry.id)
        assertEquals(parts, retry.parts)
        assertFalse(retry.answer)
        assertEquals(text("later"), queue.takeNext()!!.parts)
        assertNull(queue.takeNext())
    }

    @Test
    fun `failed preparation resolves voice observers and manual retry detaches completed playback`() = runBlocking {
        val queue = MessageQueue()
        val reply = CompletableDeferred<String?>()
        val laterReply = CompletableDeferred<String?>()
        queue.enqueue(text("voice"), reply = reply)
        queue.enqueue(text("later voice"), reply = laterReply)
        val dispatched = queue.takeNext()!!
        val failure = IllegalStateException("state not saved")

        queue.pauseAfterSubmissionFailure(dispatched, failure, userMessageCreated = false)

        assertTrue(reply.isCompleted)
        val observedFailure = runCatching { reply.await() }.exceptionOrNull()
        assertTrue(observedFailure is IllegalStateException)
        assertEquals(failure.message, observedFailure?.message)
        assertTrue(laterReply.isCompleted)
        assertNull(queue.takeNext())
        queue.resume()
        assertNull(queue.takeNext()!!.reply)
    }

    @Test
    fun `failure after user message creation pauses remaining input without queuing a duplicate user turn`() {
        val queue = MessageQueue()
        queue.enqueue(text("already in history"))
        queue.enqueue(text("later"))
        val dispatched = queue.takeNext()!!

        queue.pauseAfterSubmissionFailure(dispatched, IllegalStateException("generation failed"), userMessageCreated = true)

        assertTrue(queue.state.value.paused)
        assertEquals(listOf(text("later")), queue.state.value.messages.map { it.parts })
        queue.resume()
        assertEquals(text("later"), queue.takeNext()!!.parts)
        assertNull(queue.takeNext())
    }

    @Test
    fun `editing a voice message preserves its reply observer and queue position`() {
        val queue = MessageQueue()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("original"), reply = reply)
        queue.enqueue(text("later"))
        val id = queue.state.value.messages.first().id
        queue.beginEdit(id)
        assertNull(queue.takeNext())
        queue.finishEdit(id, text("edited"))
        val dispatched = queue.takeNext()!!
        assertEquals(text("edited"), dispatched.parts)
        assertTrue(dispatched.reply === reply)
        assertFalse(reply.isCompleted)
        assertEquals(text("later"), queue.takeNext()!!.parts)
    }

    @Test
    fun `pausing resolves voice observers but retains queued content for manual resume`() = runBlocking {
        val queue = MessageQueue()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("keep me"), reply = reply)
        queue.pause()
        assertTrue(reply.isCompleted)
        assertTrue(runCatching { reply.await() }.exceptionOrNull() is IllegalStateException)
        assertEquals(text("keep me"), queue.state.value.messages.single().parts)
        queue.resume()
        assertEquals(text("keep me"), queue.takeNext()!!.parts)
    }

    @Test
    fun `tool approval can release reply observers without removing or resuming messages`() = runBlocking {
        val queue = MessageQueue()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("pending"), reply = reply)
        queue.failReplyWaiters("approval required")
        assertEquals("approval required", runCatching { reply.await() }.exceptionOrNull()?.message)
        assertEquals(1, queue.state.value.messages.size)
        assertFalse(queue.state.value.paused)
    }

    @Test
    fun `dispatches in submission order and preserves send without answer`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"), answer = false)

        assertEquals(text("first"), queue.takeNext()!!.parts)
        val second = queue.takeNext()!!
        assertEquals(text("second"), second.parts)
        assertFalse(second.answer)
        assertNull(queue.takeNext())
    }

    @Test
    fun `editing the head blocks later messages and keeps its position`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        val id = queue.state.value.messages.first().id

        queue.beginEdit(id)
        assertNull(queue.takeNext())
        queue.finishEdit(id, text("edited"))

        val first = queue.takeNext()!!
        assertEquals(id, first.id)
        assertEquals(text("edited"), first.parts)
        assertEquals(text("second"), queue.takeNext()!!.parts)
    }

    @Test
    fun `editing a later message does not block earlier messages`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        queue.beginEdit(queue.state.value.messages.last().id)

        assertEquals(text("first"), queue.takeNext()!!.parts)
        assertNull(queue.takeNext())
    }

    @Test
    fun `cancelling an edit restores the original input and attachments`() {
        val queue = MessageQueue()
        val parts = text("question") + UIMessagePart.Image("file:///test.png")
        queue.enqueue(parts)
        val id = queue.state.value.messages.single().id
        queue.beginEdit(id)
        queue.finishEdit(id)

        assertEquals(parts, queue.takeNext()!!.parts)
    }

    @Test
    fun `removing a message skips it without changing following input`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        queue.remove(queue.state.value.messages.first().id)

        assertEquals(text("second"), queue.takeNext()!!.parts)
        assertNull(queue.takeNext())
    }

    @Test
    fun `new input and edits cannot silently resume a paused queue`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.pause()
        queue.enqueue(text("second"))
        val id = queue.state.value.messages.first().id
        queue.beginEdit(id)
        queue.finishEdit(id, text("edited"))

        assertTrue(queue.state.value.paused)
        assertNull(queue.takeNext())
        queue.resume()
        assertEquals(text("edited"), queue.takeNext()!!.parts)
        assertEquals(text("second"), queue.takeNext()!!.parts)
    }

    @Test
    fun `late edit cannot recreate a removed or dispatched message`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        val id = queue.takeNext()!!.id

        assertNull(queue.beginEdit(id))
        queue.finishEdit(id, text("late"))
        assertTrue(queue.state.value.messages.isEmpty())
    }

    @Test
    fun `rejects empty input but accepts attachment only input`() {
        val queue = MessageQueue()
        queue.enqueue(text("  "))
        queue.enqueue(emptyList())
        assertNull(queue.takeNext())

        val parts = listOf(UIMessagePart.Image("file:///test.png"))
        queue.enqueue(parts)
        assertEquals(parts, queue.takeNext()!!.parts)
    }

    @Test
    fun `submission snapshots caller owned list`() {
        val queue = MessageQueue()
        val parts = mutableListOf<UIMessagePart>(UIMessagePart.Text("original"))
        queue.enqueue(parts)
        parts.clear()

        assertEquals(text("original"), queue.takeNext()!!.parts)
    }

    @Test
    fun `queues are isolated per conversation`() {
        val first = MessageQueue()
        val second = MessageQueue()
        first.enqueue(text("first"))
        second.enqueue(text("second"))
        first.pause()

        assertNull(first.takeNext())
        assertEquals(text("second"), second.takeNext()!!.parts)
    }
}
