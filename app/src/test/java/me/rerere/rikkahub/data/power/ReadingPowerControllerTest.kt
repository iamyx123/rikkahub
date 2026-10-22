package me.rerere.rikkahub.data.power

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ReadingPowerControllerTest {
    private class Store(var pending: Boolean = false) : FlightModeRecoveryStore {
        var failSave = false
        var failClear = false

        override suspend fun isRestorePending() = pending

        override suspend fun setRestorePending(pending: Boolean) {
            if (pending && failSave || !pending && failClear) throw IOException("journal unavailable")
            this.pending = pending
        }
    }

    private class Access : FlightModeAccess {
        var root = true
        var flight = false
        var ready = true
        var applyEnable = true
        var applyDisable = true
        var rootChecks = 0
        var flightReads = 0
        var networkReads = 0
        val writes = mutableListOf<Boolean>()
        var onWrite: suspend (Boolean) -> Unit = {}

        override suspend fun hasRootAccess(): Boolean {
            rootChecks++
            return root
        }

        override suspend fun isFlightModeEnabled(): Boolean {
            flightReads++
            return flight
        }

        override suspend fun setFlightModeEnabled(enabled: Boolean) {
            writes += enabled
            onWrite(enabled)
            if (enabled && applyEnable || !enabled && applyDisable) flight = enabled
        }

        override suspend fun isNetworkReady(): Boolean {
            networkReads++
            return ready
        }
    }

    private fun TestScope.controller(access: Access = Access(), store: Store = Store()) = ReadingPowerController(
        scope = backgroundScope,
        access = access,
        recoveryStore = store,
        elapsedRealtime = { testScheduler.currentTime },
        pause = { delay(it) },
    )

    private fun ReadingPowerController.read(delaySeconds: Int = 30) {
        configure(true, delaySeconds)
        setAppForeground(true)
        setReading("classroom-chat", hasCompletedAnswer = true, generating = false)
    }

    @Test
    fun `disabled feature never probes root or network during an ordinary request`() = runTest {
        val access = Access().apply { root = false; ready = false }
        val controller = controller(access)
        runCurrent()
        assertEquals("response", controller.withNetwork { "response" })
        assertEquals(0, access.rootChecks)
        assertEquals(0, access.flightReads)
        assertEquals(0, access.networkReads)
        assertEquals(ReadingPowerPhase.DISABLED, controller.state.value.phase)
    }

    @Test
    fun `only foreground completed reading starts a single deadline without ticker updates`() = runTest {
        val access = Access()
        val controller = controller(access)
        controller.configure(true, 30)
        controller.setReading("chat", true, false)
        runCurrent()
        assertEquals(ReadingPowerPhase.INACTIVE, controller.state.value.phase)
        controller.setAppForeground(true)
        runCurrent()
        val countdown = controller.state.value
        assertEquals(30_000L, countdown.deadlineElapsedMs)
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(countdown, controller.state.value)
        assertFalse(access.flight)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(access.flight)
        assertTrue(controller.state.value.ownsFlightMode)
        assertEquals(ReadingPowerPhase.AIRPLANE, controller.state.value.phase)
    }

    @Test
    fun `zero delay applies immediately and journal is durable before the command`() = runTest {
        val access = Access()
        val store = Store()
        access.onWrite = { enabled -> if (enabled) assertTrue(store.pending) }
        val controller = controller(access, store)
        controller.read(0)
        runCurrent()
        assertEquals(listOf(true), access.writes)
        assertTrue(store.pending)
    }

    @Test
    fun `backgrounding restores only the owned mode and cancels its countdown`() = runTest {
        val access = Access()
        val store = Store()
        val controller = controller(access, store)
        controller.read(0)
        runCurrent()
        controller.setAppForeground(false)
        runCurrent()
        assertFalse(access.flight)
        assertFalse(store.pending)
        assertEquals(ReadingPowerPhase.INACTIVE, controller.state.value.phase)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf(true, false), access.writes)
    }

    @Test
    fun `leaving reading disabling or beginning generation each restores the radio state`() = runTest {
        val access = Access()
        val controller = controller(access)
        controller.read(0)
        runCurrent()
        controller.setReading(null, false, false)
        runCurrent()
        assertFalse(access.flight)
        controller.setReading("chat", true, false)
        runCurrent()
        assertTrue(access.flight)
        controller.setReading("chat", true, true)
        runCurrent()
        assertFalse(access.flight)
        controller.setReading("chat", true, false)
        runCurrent()
        assertTrue(access.flight)
        controller.configure(false, 0)
        runCurrent()
        assertFalse(access.flight)
        assertEquals(ReadingPowerPhase.DISABLED, controller.state.value.phase)
    }

    @Test
    fun `generation in progress and incomplete answers never arm a timer`() = runTest {
        val access = Access()
        val controller = controller(access)
        controller.read(0)
        controller.setReading("chat", true, true)
        runCurrent()
        assertFalse(access.flight)
        controller.setReading("chat", false, false)
        runCurrent()
        assertFalse(access.flight)
        assertTrue(access.writes.isEmpty())
    }

    @Test
    fun `preexisting airplane mode is never claimed or disabled even before network`() = runTest {
        val access = Access().apply { flight = true; ready = false }
        val store = Store()
        val controller = controller(access, store)
        controller.read(0)
        runCurrent()
        assertEquals(ReadingPowerPhase.AIRPLANE, controller.state.value.phase)
        assertFalse(controller.state.value.ownsFlightMode)
        assertFalse(store.pending)
        controller.beforeNetwork()
        controller.setAppForeground(false)
        runCurrent()
        assertTrue(access.flight)
        assertTrue(access.writes.isEmpty())
        assertEquals(0, access.networkReads)
    }

    @Test
    fun `unrooted devices report error without touching radios or the recovery journal`() = runTest {
        val access = Access().apply { root = false }
        val store = Store()
        val controller = controller(access, store)
        assertFalse(controller.checkAccess())
        assertTrue(controller.state.value.error.orEmpty().contains("Root"))
        controller.read(0)
        runCurrent()
        assertEquals(ReadingPowerPhase.ERROR, controller.state.value.phase)
        assertFalse(access.flight)
        assertFalse(store.pending)
        assertTrue(access.writes.isEmpty())
    }

    @Test
    fun `a successful command with unchanged system readback is not reported as success`() = runTest {
        val access = Access().apply { applyEnable = false }
        val store = Store()
        val controller = controller(access, store)
        controller.read(0)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertFalse(access.flight)
        assertFalse(store.pending)
        assertEquals(ReadingPowerPhase.ERROR, controller.state.value.phase)
    }

    @Test
    fun `journal write failure prevents a system mutation`() = runTest {
        val access = Access()
        val store = Store().apply { failSave = true }
        val controller = controller(access, store)
        controller.read(0)
        runCurrent()
        assertTrue(access.writes.isEmpty())
        assertEquals(ReadingPowerPhase.ERROR, controller.state.value.phase)
    }

    @Test
    fun `failed restoration retains ownership for a later retry and blocks the request`() = runTest {
        val access = Access()
        val store = Store()
        val controller = controller(access, store)
        controller.read(0)
        runCurrent()
        access.applyDisable = false
        val failed = async { runCatching { controller.acquireNetwork() } }
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(failed.await().exceptionOrNull() is IOException)
        assertTrue(store.pending)
        assertTrue(controller.state.value.ownsFlightMode)
        access.applyDisable = true
        controller.beforeNetwork()
        assertFalse(access.flight)
        assertFalse(store.pending)
    }

    @Test
    fun `a pending recovery is restored on a new process even when the setting is disabled`() = runTest {
        val access = Access().apply { flight = true }
        val store = Store(pending = true)
        val controller = controller(access, store)
        runCurrent()
        assertEquals(listOf(false), access.writes)
        assertFalse(access.flight)
        assertFalse(store.pending)
        assertEquals(ReadingPowerPhase.DISABLED, controller.state.value.phase)
    }

    @Test
    fun `already restored crash marker is cleared without changing a user setting`() = runTest {
        val access = Access()
        val store = Store(pending = true)
        controller(access, store)
        runCurrent()
        assertFalse(store.pending)
        assertTrue(access.writes.isEmpty())
    }

    @Test
    fun `clear failure keeps the recovery marker until durable cleanup succeeds`() = runTest {
        val access = Access().apply { flight = true }
        val store = Store(pending = true).apply { failClear = true }
        val controller = controller(access, store)
        runCurrent()
        assertFalse(access.flight)
        assertTrue(store.pending)
        assertEquals(ReadingPowerPhase.ERROR, controller.state.value.phase)
        store.failClear = false
        controller.beforeNetwork()
        assertFalse(store.pending)
        assertEquals(listOf(false), access.writes)
    }

    @Test
    fun `a network request waits for restoration and network readiness before running`() = runTest {
        val access = Access()
        val controller = controller(access)
        controller.read(0)
        runCurrent()
        access.ready = false
        var requestRan = false
        val request = async { controller.withNetwork { requestRan = true } }
        runCurrent()
        assertFalse(access.flight)
        assertFalse(requestRan)
        advanceTimeBy(2_000)
        runCurrent()
        assertFalse(requestRan)
        access.ready = true
        advanceTimeBy(200)
        runCurrent()
        request.await()
        assertTrue(requestRan)
    }

    @Test
    fun `network readiness has a bounded timeout and does not rerun the failed request`() = runTest {
        val access = Access()
        val controller = controller(access)
        controller.read(0)
        runCurrent()
        access.ready = false
        var requestRan = false
        val request = async { runCatching { controller.withNetwork { requestRan = true } } }
        runCurrent()
        advanceTimeBy(12_000)
        runCurrent()
        assertTrue(request.await().exceptionOrNull() is IOException)
        assertFalse(requestRan)
        assertFalse(access.flight)
        assertEquals(ReadingPowerPhase.ERROR, controller.state.value.phase)
        assertNotNull(controller.state.value.error)
    }

    @Test
    fun `concurrent streaming leases prevent airplane mode until the last close and close is idempotent`() = runTest {
        val access = Access()
        val controller = controller(access)
        controller.read(0)
        runCurrent()
        val first = controller.acquireNetwork()
        val second = controller.acquireNetwork()
        advanceTimeBy(120_000)
        runCurrent()
        assertFalse(access.flight)
        first.close()
        first.close()
        runCurrent()
        assertFalse(access.flight)
        second.release()
        assertTrue(access.flight)
        assertEquals(listOf(true, false, true), access.writes)
    }

    @Test
    fun `a request arriving while enable is in flight is restored before the request executes`() = runTest {
        val access = Access()
        val gate = CompletableDeferred<Unit>()
        access.onWrite = { enabled -> if (enabled) gate.await() }
        val controller = controller(access)
        controller.read(0)
        runCurrent()
        var requestFlight: Boolean? = null
        val request = launch { controller.withNetwork { requestFlight = access.flight } }
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        request.join()
        assertEquals(false, requestFlight)
        assertTrue(access.writes.take(2) == listOf(true, false))
    }

    @Test
    fun `beforeNetwork does not immediately reenable zero delay before a caller starts generation`() = runTest {
        val access = Access()
        val controller = controller(access)
        controller.read(0)
        runCurrent()
        controller.beforeNetwork()
        runCurrent()
        assertFalse(access.flight)
    }

    @Test
    fun `changing conversation or the configured delay starts a new countdown`() = runTest {
        val access = Access()
        val controller = controller(access)
        controller.read(30)
        runCurrent()
        advanceTimeBy(10_000)
        controller.setReading("another-chat", true, false)
        runCurrent()
        assertEquals(40_000L, controller.state.value.deadlineElapsedMs)
        controller.configure(true, 5)
        runCurrent()
        assertEquals(15_000L, controller.state.value.deadlineElapsedMs)
    }

    @Test
    fun `disposing a stale reading token cannot clear the new answer timer`() = runTest {
        val controller = controller()
        controller.read(30)
        runCurrent()
        advanceTimeBy(5_000)
        controller.setReading("classroom-chat/new-answer", true, false)
        controller.clearReading("classroom-chat")
        runCurrent()
        assertEquals(35_000L, controller.state.value.deadlineElapsedMs)
        controller.clearReading("classroom-chat/new-answer")
        runCurrent()
        assertEquals(ReadingPowerPhase.INACTIVE, controller.state.value.phase)
    }

    @Test
    fun `offline bluetooth work restores radios without waiting for an IP network`() = runTest {
        val access = Access().apply { ready = false }
        val controller = controller(access)
        controller.read(0)
        runCurrent()
        var operated = false
        controller.withRadios {
            assertFalse(access.flight)
            operated = true
            advanceTimeBy(20_000)
            runCurrent()
            assertFalse(access.flight)
        }
        assertTrue(operated)
        assertEquals(0, access.networkReads)
        assertEquals(listOf(true, false, true), access.writes)
    }

    @Test
    fun `wireless operation keeps pending network settling for the following HTTP request`() = runTest {
        val access = Access().apply { ready = false }
        val controller = controller(access)
        controller.read(0)
        runCurrent()
        controller.setAppForeground(false)
        runCurrent()
        controller.withRadios { assertFalse(access.flight) }
        assertEquals(0, access.networkReads)
        var requested = false
        val request = async { controller.withNetwork { requested = true } }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(requested)
        access.ready = true
        advanceTimeBy(200)
        runCurrent()
        request.await()
        assertTrue(requested)
    }
}
