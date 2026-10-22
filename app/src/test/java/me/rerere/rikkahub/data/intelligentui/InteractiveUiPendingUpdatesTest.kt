package me.rerere.rikkahub.data.intelligentui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

class InteractiveUiPendingUpdatesTest {
    @Test fun `releasing slider and immediately sending waits for the exact committed snapshot`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val updates = InteractiveUiPendingUpdates(owner)
            val conversation = Uuid.random()
            val saved = AtomicInteger(1)
            val started = CompletableDeferred<Unit>()
            val allowSave = CompletableDeferred<Unit>()
            updates.enqueue(conversation, "card", interactiveUiChange("slider", JsonPrimitive(2), commit = true)) {
                started.complete(Unit)
                allowSave.await()
                saved.set(2)
            }
            withTimeout(2000) { started.await() }
            val sent = async {
                updates.awaitIdle(conversation)
                buildJsonObject { put("a", saved.get()) }
            }
            yield()
            assertFalse("Sending must wait for the actual pending save", sent.isCompleted)
            allowSave.complete(Unit)
            val immutableSnapshot = withTimeout(2000) { sent.await() }
            assertEquals(2, immutableSnapshot["a"]!!.jsonPrimitive.int)
            updates.enqueue(conversation, "card", interactiveUiChange("slider", JsonPrimitive(3), commit = true)) { saved.set(3) }
            withTimeout(2000) { updates.awaitIdle(conversation) }
            assertEquals(3, saved.get())
            assertEquals(2, immutableSnapshot["a"]!!.jsonPrimitive.int)
        } finally { owner.cancel() }
    }

    @Test fun `lazy item disposal cannot cancel its pending value or final persistence`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val component = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val updates = InteractiveUiPendingUpdates(owner)
            val conversation = Uuid.random()
            val local = AtomicInteger(1)
            val saved = AtomicInteger(1)
            val allowEvaluation = CompletableDeferred<Unit>()
            updates.enqueue(conversation, "card", interactiveUiChange("slider", JsonPrimitive(7), transient = true)) {
                allowEvaluation.await(); local.set(7)
            }
            // The actual onDispose path adds a persistence barrier to the application-owned queue.
            updates.enqueue(conversation, "card", buildJsonObject { put("type", "persist") }) { saved.set(local.get()) }
            component.cancel()
            allowEvaluation.complete(Unit)
            withTimeout(2000) { updates.awaitIdle(conversation) }
            assertEquals(7, saved.get())
        } finally { owner.cancel(); component.cancel() }
    }

    @Test fun `coalescing keeps the newest field value and respects the next action boundary`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val updates = InteractiveUiPendingUpdates(owner)
            val conversation = Uuid.random()
            val gate = CompletableDeferred<Unit>()
            val observed = mutableListOf<Int>()
            updates.enqueue(conversation, "card", null) { gate.await() }
            repeat(100) { number ->
                updates.enqueue(conversation, "card", interactiveUiChange("slider", JsonPrimitive(number))) { event ->
                    synchronized(observed) { observed.add(event!!["value"]!!.jsonPrimitive.int) }
                }
            }
            updates.enqueue(conversation, "card", buildJsonObject { put("type", "action") }) { synchronized(observed) { observed.add(-1) } }
            updates.enqueue(conversation, "card", interactiveUiChange("slider", JsonPrimitive(101), commit = true)) { synchronized(observed) { observed.add(101) } }
            gate.complete(Unit)
            withTimeout(2000) { updates.awaitIdle(conversation) }
            assertEquals(listOf(99, -1, 101), observed)
        } finally { owner.cancel() }
    }

    @Test fun `save failures block a stale snapshot instead of silently sending old values`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val updates = InteractiveUiPendingUpdates(owner)
            val conversation = Uuid.random()
            updates.enqueue(conversation, "card", JsonObject(emptyMap())) { error("disk unavailable") }
            val failure = runCatching { withTimeout(2000) { updates.awaitIdle(conversation) } }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure!!.message!!.contains("disk unavailable"))
            val second = runCatching { updates.awaitIdle(conversation) }.exceptionOrNull()
            assertTrue("A second send cannot bypass a failed card save", second is IllegalStateException)
            updates.enqueue(conversation, "other-card", JsonObject(emptyMap())) { }
            val otherCard = runCatching { withTimeout(2000) { updates.awaitIdle(conversation) } }.exceptionOrNull()
            assertTrue("Saving another card cannot clear this card's failure", otherCard is IllegalStateException)
            updates.enqueue(conversation, "card", interactiveUiChange("slider",JsonPrimitive(3),transient=true)) { }
            assertTrue(runCatching { updates.awaitIdle(conversation) }.exceptionOrNull() is IllegalStateException)
            updates.enqueue(conversation, "card", buildJsonObject { put("type", "persist") }) { }
            assertTrue("Disposal cannot hide a rejected state change", runCatching { updates.awaitIdle(conversation) }.exceptionOrNull() is IllegalStateException)
            updates.enqueue(conversation, "card", interactiveUiChange("slider",JsonPrimitive(3),commit=true)) { }
            withTimeout(2000) { updates.awaitIdle(conversation) }
        } finally { owner.cancel() }
    }

    @Test fun `removing a failed source permits a new turn without resurrecting the card`() = runBlocking {
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val updates=InteractiveUiPendingUpdates(owner)
            val conversation=Uuid.random()
            updates.enqueue(conversation,"removed-card",JsonObject(emptyMap())){error("source deleted")}
            withTimeout(2000){updates.awaitIdle(conversation){emptySet()}}
        } finally {owner.cancel()}
    }

    @Test fun `a committed value is not coalesced away by the next transient drag`() = runBlocking {
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val updates=InteractiveUiPendingUpdates(owner);val conversation=Uuid.random();val gate=CompletableDeferred<Unit>()
            val observed=mutableListOf<Int>()
            updates.enqueue(conversation,"card",null){gate.await()}
            updates.enqueue(conversation,"card",interactiveUiChange("slider",JsonPrimitive(2),commit=true)){event->observed.add(event!!["value"]!!.jsonPrimitive.int)}
            updates.enqueue(conversation,"card",interactiveUiChange("slider",JsonPrimitive(3),transient=true)){event->observed.add(event!!["value"]!!.jsonPrimitive.int)}
            gate.complete(Unit);withTimeout(2000){updates.awaitIdle(conversation)}
            assertEquals(listOf(2,3),observed)
        } finally {owner.cancel()}
    }

    @Test fun `a real persistence retry can recover a failed save of the new value`() = runBlocking {
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val updates=InteractiveUiPendingUpdates(owner);val conversation=Uuid.random()
            updates.enqueue(conversation,"card",interactiveUiChange("slider",JsonPrimitive(2),commit=true)){throw InteractiveUiPersistenceException(IllegalStateException("disk"))}
            assertTrue(runCatching{withTimeout(2000){updates.awaitIdle(conversation)}}.isFailure)
            updates.enqueue(conversation,"card",buildJsonObject{put("type","persist")}){}
            withTimeout(2000){updates.awaitIdle(conversation)}
        } finally {owner.cancel()}
    }

    @Test fun `new work registered while resolving active sources is awaited before snapshot`() = runBlocking {
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val updates=InteractiveUiPendingUpdates(owner);val conversation=Uuid.random();val saved=AtomicInteger(1)
            var registered=false
            withTimeout(2000){updates.awaitIdle(conversation){
                if(!registered){registered=true;updates.enqueue(conversation,"card",interactiveUiChange("slider",JsonPrimitive(5),commit=true)){saved.set(5)}}
                setOf("card")
            }}
            assertEquals(5,saved.get())
        } finally {owner.cancel()}
    }
}
