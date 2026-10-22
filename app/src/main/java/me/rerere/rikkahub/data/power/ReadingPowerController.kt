package me.rerere.rikkahub.data.power

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class ReadingPowerPhase { DISABLED, INACTIVE, COUNTDOWN, ENABLING, AIRPLANE, RESTORING, ERROR }

data class ReadingPowerState(
    val phase: ReadingPowerPhase = ReadingPowerPhase.DISABLED,
    val deadlineElapsedMs: Long? = null,
    val ownsFlightMode: Boolean = false,
    val error: String? = null,
)

interface FlightModeAccess {
    suspend fun hasRootAccess(): Boolean
    suspend fun isFlightModeEnabled(): Boolean
    suspend fun setFlightModeEnabled(enabled: Boolean)
    suspend fun isNetworkReady(): Boolean
}

/** The marker is durable before changing the system, and remains set until recovery is verified. */
interface FlightModeRecoveryStore {
    suspend fun isRestorePending(): Boolean
    suspend fun setRestorePending(pending: Boolean)
}

class ReadingPowerNetworkLease internal constructor(
    private val scope: CoroutineScope,
    private val onRelease: suspend () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    suspend fun release() {
        if (closed.compareAndSet(false, true)) withContext(NonCancellable) { onRelease() }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) scope.launch(NonCancellable) { onRelease() }
    }
}

/**
 * Turns radios off only while a completed answer is being read in the foreground. All changes are
 * serialized, verified against the system, and journaled for recovery after a process restart.
 * Requests hold a lease until their response body/stream is closed so a reading timer cannot
 * interrupt downloads, MCP calls, or another conversation's generation.
 */
class ReadingPowerController(
    private val scope: CoroutineScope,
    private val access: FlightModeAccess,
    private val recoveryStore: FlightModeRecoveryStore,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val verifyTimeoutMillis: Long = 2_000,
    private val networkReadyTimeoutMillis: Long = 12_000,
) {
    private data class Inputs(
        val enabled: Boolean = false,
        val delaySeconds: Int = 30,
        val foreground: Boolean = false,
        val conversationId: String? = null,
        val completed: Boolean = false,
        val generating: Boolean = false,
    )

    private data class ReadingKey(val conversationId: String, val delaySeconds: Int)

    private val inputLock = Any()
    private var inputs = Inputs()
    private val mutex = Mutex()
    private val networkLeases = AtomicInteger()
    private val inputRevision = AtomicLong()
    private var reconciledRevision = -1L
    private var recoveryLoaded = false
    private var ownsFlightMode = false
    private var networkNeedsSettle = false
    private var activeReading: ReadingKey? = null
    private var timer: Job? = null
    private val _state = MutableStateFlow(ReadingPowerState())
    val state: StateFlow<ReadingPowerState> = _state.asStateFlow()

    init {
        requestReconcile()
    }

    fun configure(enabled: Boolean, delaySeconds: Int) {
        updateInputs { copy(enabled = enabled, delaySeconds = delaySeconds.coerceIn(0, 3600)) }
    }

    fun setAppForeground(foreground: Boolean) {
        updateInputs { copy(foreground = foreground) }
    }

    fun setReading(conversationId: String?, hasCompletedAnswer: Boolean, generating: Boolean) {
        updateInputs {
            copy(conversationId = conversationId, completed = hasCompletedAnswer, generating = generating)
        }
    }

    fun clearReading(conversationId: String) {
        val changed = synchronized(inputLock) {
            if (inputs.conversationId != conversationId) false else {
                inputs = inputs.copy(conversationId = null, completed = false, generating = false)
                true
            }
        }
        if (changed) requestReconcile()
    }

    suspend fun checkAccess(): Boolean = try {
        requireAccess()
        true
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        false
    }

    suspend fun requireAccess() {
        try {
            if (!access.hasRootAccess()) throw IOException("阅读省电需要 Root 权限，请先授权 RikkaHub。")
            access.isFlightModeEnabled()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val failure = error.asPowerError("无法验证飞行模式权限")
            publish(ReadingPowerPhase.ERROR, error = failure.message)
            throw failure
        }
    }

    /** For a send action followed immediately by generating=true; requests should hold a lease. */
    suspend fun beforeNetwork() {
        acquireNetwork(resumeReadingOnRelease = false).release()
    }

    suspend fun acquireNetwork(): ReadingPowerNetworkLease = acquireNetwork(resumeReadingOnRelease = true)

    private suspend fun acquireNetwork(resumeReadingOnRelease: Boolean): ReadingPowerNetworkLease {
        // Register before waiting for the mutation lock: an in-flight enable rechecks this count.
        networkLeases.incrementAndGet()
        try {
            mutex.withLock {
                cancelTimer()
                activeReading = null
                if (!loadAndRecoverLocked() || !restoreOwnedLocked()) {
                    throw IOException(state.value.error ?: "飞行模式尚未恢复，已暂停联网。")
                }
                if (networkNeedsSettle) awaitNetworkLocked()
                publish(if (snapshot().enabled) ReadingPowerPhase.INACTIVE else ReadingPowerPhase.DISABLED)
            }
        } catch (error: Throwable) {
            networkLeases.decrementAndGet()
            // A failed request must leave its error visible, rather than immediately going offline again.
            if (error !is CancellationException && state.value.phase != ReadingPowerPhase.ERROR) {
                publish(ReadingPowerPhase.ERROR, error = error.message ?: "恢复联网失败。")
            }
            throw error
        }
        return ReadingPowerNetworkLease(scope) {
            networkLeases.decrementAndGet()
            if (resumeReadingOnRelease) mutex.withLock { reconcileLocked() }
        }
    }

    suspend fun <T> withNetwork(block: suspend () -> T): T {
        val lease = acquireNetwork()
        return try {
            block()
        } finally {
            lease.release()
        }
    }

    private fun snapshot(): Inputs = synchronized(inputLock) { inputs }

    private fun updateInputs(change: Inputs.() -> Inputs) {
        synchronized(inputLock) { inputs = inputs.change() }
        // Even an unchanged event retries recovery after root permission or a system command failed.
        requestReconcile()
    }

    private fun requestReconcile() {
        inputRevision.incrementAndGet()
        scope.launch {
            mutex.withLock {
                val revision = inputRevision.get()
                // Several UI/lifecycle updates in one frame describe one latest state, not retries.
                if (revision != reconciledRevision) {
                    reconciledRevision = revision
                    reconcileLocked()
                }
            }
        }
    }

    private fun readingKey(): ReadingKey? = snapshot().let { current ->
        if (current.enabled && current.foreground && current.conversationId != null &&
            current.completed && !current.generating && networkLeases.get() == 0
        ) ReadingKey(current.conversationId, current.delaySeconds) else null
    }

    private suspend fun reconcileLocked() {
        if (!loadAndRecoverLocked()) return
        val reading = readingKey()
        if (reading == null) {
            cancelTimer()
            activeReading = null
            if (restoreOwnedLocked()) {
                publish(if (snapshot().enabled) ReadingPowerPhase.INACTIVE else ReadingPowerPhase.DISABLED)
            }
            return
        }
        if (activeReading == reading && (timer != null || state.value.phase == ReadingPowerPhase.AIRPLANE)) return
        cancelTimer()
        if (!restoreOwnedLocked()) return
        activeReading = reading
        if (reading.delaySeconds == 0) {
            enableLocked(reading)
        } else {
            publish(ReadingPowerPhase.COUNTDOWN, deadline = elapsedRealtime() + reading.delaySeconds * 1_000L)
            timer = scope.launch {
                pause(reading.delaySeconds * 1_000L)
                mutex.withLock {
                    timer = null
                    if (readingKey() == reading && activeReading == reading) enableLocked(reading)
                    else reconcileLocked()
                }
            }
        }
    }

    private suspend fun loadAndRecoverLocked(): Boolean {
        if (recoveryLoaded) return true
        return try {
            ownsFlightMode = recoveryStore.isRestorePending()
            recoveryLoaded = true
            restoreOwnedLocked()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            publish(ReadingPowerPhase.ERROR, error = error.asPowerError("无法读取飞行模式恢复记录").message)
            false
        }
    }

    private suspend fun enableLocked(reading: ReadingKey) = withContext(NonCancellable) {
        try {
            if (readingKey() != reading) return@withContext
            if (access.isFlightModeEnabled()) {
                // A pre-existing user choice is never owned and is never turned off by this feature.
                publish(ReadingPowerPhase.AIRPLANE)
                return@withContext
            }
            if (!access.hasRootAccess()) throw IOException("阅读省电需要 Root 权限，未更改飞行模式。")
            if (readingKey() != reading) return@withContext
            recoveryStore.setRestorePending(true)
            ownsFlightMode = true
            publish(ReadingPowerPhase.ENABLING)
            val commandError = runCatching { access.setFlightModeEnabled(true) }.exceptionOrNull()
            if (!verifyFlightMode(true)) {
                throw IOException("系统未确认开启飞行模式。", commandError)
            }
            if (readingKey() == reading) publish(ReadingPowerPhase.AIRPLANE)
            else if (restoreOwnedLocked()) {
                publish(if (snapshot().enabled) ReadingPowerPhase.INACTIVE else ReadingPowerPhase.DISABLED)
            }
        } catch (error: Exception) {
            // A timed-out command may still have changed the system. Keep/restore its durable marker.
            val message = error.asPowerError("开启飞行模式失败").message
            if (!ownsFlightMode || restoreOwnedLocked()) publish(ReadingPowerPhase.ERROR, error = message)
        }
    }

    private suspend fun restoreOwnedLocked(): Boolean = withContext(NonCancellable) {
        if (!ownsFlightMode) return@withContext true
        publish(ReadingPowerPhase.RESTORING)
        try {
            val wasEnabled = access.isFlightModeEnabled()
            if (wasEnabled) {
                val commandError = runCatching { access.setFlightModeEnabled(false) }.exceptionOrNull()
                if (!verifyFlightMode(false)) throw IOException("系统未确认恢复联网，恢复记录已保留。", commandError)
                networkNeedsSettle = true
            }
            recoveryStore.setRestorePending(false)
            ownsFlightMode = false
            true
        } catch (error: Exception) {
            publish(ReadingPowerPhase.ERROR, error = error.asPowerError("恢复飞行模式失败").message)
            false
        }
    }

    private suspend fun verifyFlightMode(expected: Boolean): Boolean {
        val deadline = elapsedRealtime() + verifyTimeoutMillis
        while (true) {
            if (access.isFlightModeEnabled() == expected) return true
            if (elapsedRealtime() >= deadline) return false
            pause(100)
        }
    }

    private suspend fun awaitNetworkLocked() {
        val deadline = elapsedRealtime() + networkReadyTimeoutMillis
        while (!access.isNetworkReady()) {
            if (elapsedRealtime() >= deadline) {
                val failure = IOException("飞行模式已关闭，但网络尚未就绪，请稍后重试。")
                publish(ReadingPowerPhase.ERROR, error = failure.message)
                throw failure
            }
            pause(200)
        }
        networkNeedsSettle = false
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
    }

    private fun publish(phase: ReadingPowerPhase, deadline: Long? = null, error: String? = null) {
        _state.value = ReadingPowerState(phase, deadline, ownsFlightMode, error)
    }

    private fun Exception.asPowerError(prefix: String): IOException =
        if (this is IOException) this else IOException("$prefix：${message ?: javaClass.simpleName}", this)
}
