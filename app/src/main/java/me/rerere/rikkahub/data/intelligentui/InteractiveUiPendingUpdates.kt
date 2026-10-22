package me.rerere.rikkahub.data.intelligentui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.uuid.Uuid

/** Application-owned writes outlive a lazy-list item. Sending waits on real completion, not a timer. */
internal class InteractiveUiPendingUpdates(private val scope: CoroutineScope) {
    private data class Failure(val message: String, val retryPersist: Boolean)
    private class Work(val card: String, var event: JsonObject?, var apply: suspend (JsonObject?) -> Unit)
    private class Entry {
        val queue = ArrayDeque<Work>()
        val complete = CompletableDeferred<Unit>()
    }
    private val lock = Any()
    private val entries = mutableMapOf<Uuid, Entry>()
    private val failures = mutableMapOf<Uuid, MutableMap<String, Failure>>()

    fun enqueue(conversationId: Uuid, card: String, event: JsonObject?, apply: suspend (JsonObject?) -> Unit) {
        synchronized(lock) {
            val existing = entries[conversationId]
            val entry = existing ?: Entry().also { entries[conversationId] = it }
            val last = entry.queue.lastOrNull()
            if (last != null && sameInput(last, card, event)) {
                last.event = event
                last.apply = apply
            } else entry.queue.addLast(Work(card, event, apply))
            if (existing == null) scope.launch { drain(conversationId, entry) }
        }
    }

    private fun sameInput(work: Work, card: String, event: JsonObject?): Boolean = work.card == card &&
        event.text("type") == "change" && work.event.text("type") == "change" &&
        work.event.text("commit") != "true" &&
        event.text("target") == work.event.text("target")

    private suspend fun drain(conversationId: Uuid, entry: Entry) {
        var currentCard: String? = null
        try {
            while (true) {
                var work = synchronized(lock) {
                    if (entry.queue.isEmpty()) {
                        entries.remove(conversationId)
                        entry.complete.complete(Unit)
                        null
                    } else entry.queue.removeFirst()
                } ?: break
                currentCard = work.card
                if (work.event.text("type") == "change" && work.event.text("commit") != "true") {
                    delay(60)
                    synchronized(lock) {
                        while (entry.queue.firstOrNull()?.let { sameInput(work, it.card, it.event) } == true) work = entry.queue.removeFirst()
                    }
                }
                try {
                    work.apply(work.event)
                    if (work.event.text("transient") != "true") synchronized(lock) {
                        if (work.event.text("type") != "persist" || failures[conversationId]?.get(work.card)?.retryPersist == true) failures[conversationId]?.remove(work.card)
                        if (failures[conversationId]?.isEmpty() == true) failures.remove(conversationId)
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    synchronized(lock) {
                        val existing = failures[conversationId]?.get(work.card)
                        if (work.event.text("type") != "persist" || existing?.retryPersist != false) {
                            failures.getOrPut(conversationId) { mutableMapOf() }[work.card] = Failure(failure.message ?: "交互参数尚未保存", failure is InteractiveUiPersistenceException)
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            synchronized(lock) {
                if (entries[conversationId] === entry) entries.remove(conversationId)
                val interrupted = entry.queue.map { it.card } + listOfNotNull(currentCard)
                interrupted.forEach { card -> failures.getOrPut(conversationId) { mutableMapOf() }[card] = Failure("交互参数保存已中断，请重新操作后发送。", false) }
                entry.complete.complete(Unit)
            }
            if (failure is CancellationException) throw failure
        }
    }

    suspend fun awaitIdle(conversationId: Uuid, activeCards: (() -> Set<String>)? = null) {
        while (true) {
            val pending = synchronized(lock) { entries[conversationId]?.complete }
            if (pending != null) pending.await()
            else {
                val current = activeCards?.invoke()
                var failure: String? = null
                val finished = synchronized(lock) {
                    if (entries.containsKey(conversationId)) false
                    else {
                        if (current != null) failures[conversationId]?.keys?.retainAll(current)
                        failure = failures[conversationId]?.values?.firstOrNull()?.message
                        true
                    }
                }
                if (!finished) continue
                check(failure == null) { "交互参数未能保存，请重试：$failure" }
                return
            }
        }
    }

    private fun JsonObject?.text(key: String) = (this?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}

internal class InteractiveUiPersistenceException(cause: Exception) : IllegalStateException(cause.message, cause)

internal val interactiveUiPendingUpdates = InteractiveUiPendingUpdates(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
