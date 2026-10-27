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
import kotlinx.coroutines.flow.update
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
    // System readback; null means unknown. Configuration/ownership never imply radio state.
    val flightModeEnabled: Boolean? = null,
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
 *
 * 切换会话不算「不再阅读」：倒计时和已开启的飞行模式都保持不变，只有生成、请求、打开输入法、
 * 页面不可见、退到后台、关闭功能或真正离开聊天页才恢复联网。输入法本身也常要联网（云输入、语音），
 * 所以键盘一弹出就恢复，收起后重新倒计时。
 */
private const val LEAVE_CHAT_GRACE_MILLIS = 1_500L

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
        val pageVisible: Boolean = true,
        val imeVisible: Boolean = false,
    )

    private val inputLock = Any()
    private var inputs = Inputs()
    private val mutex = Mutex()
    private val networkLeases = AtomicInteger()
    private val inputRevision = AtomicLong()
    private var reconciledRevision = -1L
    private var recoveryLoaded = false
    private var ownsFlightMode = false
    private var networkNeedsSettle = false
    private var timer: Job? = null
    private var countdownDelay: Int? = null
    private var leaveGrace: Job? = null
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

    fun setReading(
        conversationId: String?,
        hasCompletedAnswer: Boolean,
        generating: Boolean,
        pageVisible: Boolean = true,
    ) {
        updateInputs {
            copy(
                conversationId = conversationId,
                completed = hasCompletedAnswer,
                generating = generating,
                pageVisible = pageVisible,
            )
        }
    }

    /** 键盘弹出说明用户要接着输入，输入法需要网络 */
    fun setImeVisible(visible: Boolean) {
        updateInputs { copy(imeVisible = visible) }
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

    /** Settings observer and foreground resume refresh the indicator without toggling any radio. */
    fun refreshSystemFlightMode() {
        scope.launch {
            mutex.withLock {
                try { readFlightMode() }
                catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    // The indicator shows unknown; reading a public setting never requests Root.
                }
            }
        }
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
            mutex.withLock { readFlightMode() }
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

    private suspend fun acquireNetwork(
        resumeReadingOnRelease: Boolean,
        waitForNetwork: Boolean = true,
    ): ReadingPowerNetworkLease {
        // Register before waiting for the mutation lock: an in-flight enable rechecks this count.
        networkLeases.incrementAndGet()
        try {
            mutex.withLock {
                cancelTimer()
                if (!loadAndRecoverLocked() || !restoreOwnedLocked()) {
                    throw IOException(state.value.error ?: "飞行模式尚未恢复，已暂停联网。")
                }
                if (waitForNetwork && networkNeedsSettle) awaitNetworkLocked()
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

    /** Bluetooth/local wireless work needs the radios restored, without requiring an IP route. */
    suspend fun <T> withRadios(block: suspend () -> T): T {
        val lease = acquireNetwork(resumeReadingOnRelease = true, waitForNetwork = false)
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

    /** 能不能保持离线；不看是哪个会话，所以切换会话不会打断倒计时 */
    private fun offlineAllowed(current: Inputs = snapshot()): Boolean =
        current.enabled && current.foreground && current.pageVisible && !current.generating &&
            !current.imeVisible && networkLeases.get() == 0

    private fun onChatPage(current: Inputs = snapshot()): Boolean = offlineAllowed(current) && current.conversationId != null

    private suspend fun reconcileLocked() {
        if (!loadAndRecoverLocked()) return
        val current = snapshot()
        if (!offlineAllowed(current)) {
            cancelLeaveGrace()
            goOnlineLocked()
            return
        }
        if (current.conversationId == null) {
            leaveChatLocked()
            return
        }
        cancelLeaveGrace()
        if (timer != null && countdownDelay == current.delaySeconds) return
        if (timer == null && state.value.phase == ReadingPowerPhase.AIRPLANE) return
        cancelTimer()
        // 没有可读的回答（新会话、最后一条是自己发的）时不开始倒计时，但已经离线的状态照旧保持
        if (!current.completed) {
            publish(ReadingPowerPhase.INACTIVE)
            return
        }
        if (!restoreOwnedLocked()) return
        if (current.delaySeconds == 0) {
            enableLocked()
        } else {
            countdownDelay = current.delaySeconds
            publish(ReadingPowerPhase.COUNTDOWN, deadline = elapsedRealtime() + current.delaySeconds * 1_000L)
            timer = scope.launch {
                pause(current.delaySeconds * 1_000L)
                mutex.withLock {
                    timer = null
                    countdownDelay = null
                    if (onChatPage()) enableLocked() else reconcileLocked()
                }
            }
        }
    }

    private suspend fun goOnlineLocked() {
        cancelTimer()
        if (restoreOwnedLocked()) {
            publish(if (snapshot().enabled) ReadingPowerPhase.INACTIVE else ReadingPowerPhase.DISABLED)
        }
    }

    /**
     * 切换会话时旧页面先销毁、新页面再接上，中间会短暂没有会话。等一小会儿再恢复联网，
     * 新页面接上了就什么都不用改。
     */
    private fun leaveChatLocked() {
        if (leaveGrace != null) return
        leaveGrace = scope.launch {
            pause(LEAVE_CHAT_GRACE_MILLIS)
            mutex.withLock {
                leaveGrace = null
                if (snapshot().conversationId == null) goOnlineLocked() else reconcileLocked()
            }
        }
    }

    private fun cancelLeaveGrace() {
        leaveGrace?.cancel()
        leaveGrace = null
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

    private suspend fun enableLocked() = withContext(NonCancellable) {
        try {
            if (!onChatPage()) return@withContext
            if (readFlightMode()) {
                // A pre-existing user choice is never owned and is never turned off by this feature.
                publish(ReadingPowerPhase.AIRPLANE)
                return@withContext
            }
            if (!access.hasRootAccess()) throw IOException("阅读省电需要 Root 权限，未更改飞行模式。")
            if (!onChatPage()) return@withContext
            recoveryStore.setRestorePending(true)
            ownsFlightMode = true
            publish(ReadingPowerPhase.ENABLING)
            val commandError = runCatching { access.setFlightModeEnabled(true) }.exceptionOrNull()
            if (!verifyFlightMode(true)) {
                throw IOException("系统未确认开启飞行模式。", commandError)
            }
            if (onChatPage()) publish(ReadingPowerPhase.AIRPLANE)
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
            val wasEnabled = readFlightMode()
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
            if (readFlightMode() == expected) return true
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
        countdownDelay = null
    }

    private fun publish(phase: ReadingPowerPhase, deadline: Long? = null, error: String? = null) {
        _state.update { it.copy(phase = phase, deadlineElapsedMs = deadline, ownsFlightMode = ownsFlightMode, error = error) }
    }

    private suspend fun readFlightMode(): Boolean = try {
        access.isFlightModeEnabled().also { actual -> _state.update { it.copy(flightModeEnabled = actual) } }
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        _state.update { it.copy(flightModeEnabled = null) }
        throw failure
    }

    private fun Exception.asPowerError(prefix: String): IOException =
        if (this is IOException) this else IOException("$prefix：${message ?: javaClass.simpleName}", this)
}
