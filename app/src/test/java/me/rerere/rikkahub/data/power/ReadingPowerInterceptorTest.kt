package me.rerere.rikkahub.data.power

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingPowerInterceptorTest {
    private class Access(private val events: MutableList<String>) : FlightModeAccess {
        var root = true
        var flight = false
        var ready = true
        var flightReadFailure: IOException? = null
        var rootChecks = 0
        var flightReads = 0
        var networkReads = 0
        val writes = mutableListOf<Boolean>()

        override suspend fun hasRootAccess(): Boolean {
            rootChecks++
            return root
        }

        override suspend fun isFlightModeEnabled(): Boolean {
            flightReads++
            flightReadFailure?.let { throw it }
            return flight
        }

        override suspend fun setFlightModeEnabled(enabled: Boolean) {
            events += "airplane:$enabled"
            writes += enabled
            flight = enabled
        }

        override suspend fun isNetworkReady(): Boolean {
            networkReads++
            events += "network-ready"
            return ready
        }
    }

    private class Store(private val events: MutableList<String>) : FlightModeRecoveryStore {
        var pending = false

        override suspend fun isRestorePending() = pending

        override suspend fun setRestorePending(pending: Boolean) {
            events += "journal:$pending"
            this.pending = pending
        }
    }

    private class Fixture(scope: TestScope) {
        val events = CopyOnWriteArrayList<String>()
        val access = Access(events)
        val store = Store(events)
        val controller = ReadingPowerController(
            scope = scope.backgroundScope,
            access = access,
            recoveryStore = store,
            // Never evaluate Android SystemClock in a plain JVM test.
            elapsedRealtime = { scope.testScheduler.currentTime },
            pause = { delay(it) },
        )
        val interceptor = ReadingPowerInterceptor(controller)

        fun readCompletedAnswer() {
            controller.configure(enabled = true, delaySeconds = 0)
            controller.setAppForeground(true)
            controller.setReading("classroom-chat", hasCompletedAnswer = true, generating = false)
        }

        fun chain(body: ResponseBody, onProceed: () -> Unit = {}) = Chain { request ->
            events += "proceed"
            onProceed()
            response(request, body)
        }
    }

    /** A real in-memory response body with observable close/read failures, without a network server. */
    private class MemoryBody(text: String, private val readFailure: IOException? = null) : ResponseBody() {
        private val delegate = text.toResponseBody("text/event-stream; charset=utf-8".toMediaType())
        var closeCalls = 0
            private set
        private val buffered = object : ForwardingSource(delegate.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                readFailure?.let { throw it }
                return super.read(sink, byteCount)
            }

            override fun close() {
                closeCalls++
                super.close()
            }
        }.buffer()

        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun source(): BufferedSource = buffered
    }

    private class Chain(private val response: (Request) -> Response) : Interceptor.Chain {
        private val request = Request.Builder().url("https://classroom.example.test/api/stream").build()
        var proceedCalls = 0
            private set

        override fun request() = request
        override fun proceed(request: Request): Response {
            proceedCalls++
            assertSame(this.request, request)
            return response(request)
        }

        override fun connection(): Connection? = null
        override fun call(): Call = error("The power interceptor must not create or inspect another call")
        override fun connectTimeoutMillis() = 10_000
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis() = 10_000
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis() = 10_000
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    @Test
    fun `owned airplane mode is restored and network verified before proceeding`() = runTest {
        val fixture = Fixture(this)
        fixture.readCompletedAnswer()
        runCurrent()
        assertTrue(fixture.access.flight)
        assertTrue(fixture.store.pending)
        fixture.events.clear()

        val response = fixture.interceptor.intercept(fixture.chain(MemoryBody("answer")) {
            assertFalse(fixture.access.flight)
            assertFalse(fixture.store.pending)
            assertEquals(ReadingPowerPhase.INACTIVE, fixture.controller.state.value.phase)
        })

        assertEquals(listOf("airplane:false", "journal:false", "network-ready", "proceed"), fixture.events.toList())
        response.close()
        runCurrent()
        assertTrue(fixture.access.flight)
    }

    @Test
    fun `headers and partial stream reads keep the network lease until actual EOF`() = runTest {
        val fixture = Fixture(this)
        fixture.readCompletedAnswer()
        runCurrent()
        val original = MemoryBody("abcdef")
        val response = fixture.interceptor.intercept(fixture.chain(original))
        runCurrent()
        assertFalse("Receiving headers must not let the reading timer disable radios", fixture.access.flight)
        assertEquals(original.contentType(), response.body.contentType())
        assertEquals(original.contentLength(), response.body.contentLength())

        assertEquals("a", response.body.source().readUtf8(1))
        runCurrent()
        assertFalse("A buffered partial read is still an active stream", fixture.access.flight)
        assertEquals("bcdef", response.body.source().readUtf8())
        assertEquals(0, original.closeCalls)
        runCurrent()
        assertTrue("EOF must release even if the caller has not closed the body yet", fixture.access.flight)
        response.close()
        runCurrent()
        assertEquals(1, original.closeCalls)
        assertEquals(listOf(true, false, true), fixture.access.writes)
    }

    @Test
    fun `closing one unread stream twice cannot release a second stream lease`() = runTest {
        val fixture = Fixture(this)
        fixture.readCompletedAnswer()
        runCurrent()
        val firstBody = MemoryBody("first")
        val secondBody = MemoryBody("second")
        val first = fixture.interceptor.intercept(fixture.chain(firstBody))
        val second = fixture.interceptor.intercept(fixture.chain(secondBody))

        first.close()
        first.close()
        runCurrent()
        assertEquals(1, firstBody.closeCalls)
        assertFalse("Another open response must still hold the radios on", fixture.access.flight)
        second.close()
        runCurrent()
        assertEquals(1, secondBody.closeCalls)
        assertTrue(fixture.access.flight)
        assertEquals(listOf(true, false, true), fixture.access.writes)
    }

    @Test
    fun `proceed failure releases its lease and preserves the original exception`() = runTest {
        val fixture = Fixture(this)
        fixture.readCompletedAnswer()
        runCurrent()
        val expected = IOException("upstream connection failed")
        val chain = Chain { throw expected }

        assertSame(expected, assertThrows(IOException::class.java) { fixture.interceptor.intercept(chain) })

        assertEquals(1, chain.proceedCalls)
        runCurrent()
        assertTrue(fixture.access.flight)
        assertTrue(fixture.store.pending)
    }

    @Test
    fun `response read failure releases without waiting for caller cleanup`() = runTest {
        val fixture = Fixture(this)
        fixture.readCompletedAnswer()
        runCurrent()
        val expected = IOException("stream disconnected")
        val original = MemoryBody("partial answer", readFailure = expected)
        val response = fixture.interceptor.intercept(fixture.chain(original))

        assertSame(expected, assertThrows(IOException::class.java) { response.body.source().readUtf8() })
        assertEquals(0, original.closeCalls)
        runCurrent()
        assertTrue("A failed body must not leave a permanent network lease", fixture.access.flight)
        response.close()
        runCurrent()
        assertEquals(1, original.closeCalls)
        assertEquals(listOf(true, false, true), fixture.access.writes)
    }

    @Test
    fun `failed restoration never proceeds and a later successful request has no leaked lease`() = runTest {
        val fixture = Fixture(this)
        fixture.readCompletedAnswer()
        runCurrent()
        fixture.access.flightReadFailure = IOException("system flight-mode read failed")
        val blocked = fixture.chain(MemoryBody("must not be requested"))

        assertThrows(IOException::class.java) { fixture.interceptor.intercept(blocked) }
        assertEquals(0, blocked.proceedCalls)
        assertEquals(ReadingPowerPhase.ERROR, fixture.controller.state.value.phase)
        assertTrue(fixture.store.pending)

        fixture.access.flightReadFailure = null
        val retry = fixture.chain(MemoryBody("retry succeeded"))
        fixture.interceptor.intercept(retry).use { assertEquals("retry succeeded", it.body.string()) }
        runCurrent()
        assertEquals(1, retry.proceedCalls)
        assertTrue("The failed acquire must not keep the successful retry online forever", fixture.access.flight)
        assertEquals(ReadingPowerPhase.AIRPLANE, fixture.controller.state.value.phase)
    }

    @Test
    fun `disabled mode handles an ordinary response without probing root radios or network readiness`() = runTest {
        val fixture = Fixture(this)
        fixture.access.root = false
        fixture.access.ready = false
        runCurrent()
        val chain = fixture.chain(MemoryBody("ordinary answer"))

        fixture.interceptor.intercept(chain).use { assertEquals("ordinary answer", it.body.string()) }
        runCurrent()

        assertEquals(1, chain.proceedCalls)
        assertEquals(0, fixture.access.rootChecks)
        assertEquals(0, fixture.access.flightReads)
        assertEquals(0, fixture.access.networkReads)
        assertTrue(fixture.access.writes.isEmpty())
        assertFalse(fixture.store.pending)
        assertEquals(ReadingPowerPhase.DISABLED, fixture.controller.state.value.phase)
    }

    private companion object {
        fun response(request: Request, body: ResponseBody): Response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()
    }
}
