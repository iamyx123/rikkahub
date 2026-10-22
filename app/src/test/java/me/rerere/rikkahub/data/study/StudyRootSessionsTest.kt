package me.rerere.rikkahub.data.study

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real host shell processes exercise pipe framing, process death and concurrent callers.
 * The fixture overrides only `id -u`; no test invokes su or obtains real administrator access.
 */
class StudyRootSessionsTest {
    @get:Rule val temporary = TemporaryFolder()
    @get:Rule val timeout: Timeout = Timeout.seconds(20)
    private val sessions = mutableListOf<StudyRootSessions>()
    private val processes = Collections.synchronizedList(mutableListOf<Process>())
    private val workers = Executors.newCachedThreadPool()

    @After fun close() {
        sessions.forEach { it.shutdown() }
        synchronized(processes) { processes.forEach { it.destroyForcibly() } }
        workers.shutdownNow()
    }

    private fun shell(idScript: String = "printf '0\\n'", startupScript: String = ""): Process {
        val executable = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) {
            listOf("C:/Program Files/Git/bin/bash.exe", "D:/Program Files/Git/bin/bash.exe")
                .firstOrNull { File(it).isFile } ?: error("The Windows test fixture requires Git Bash")
        } else "/bin/sh"
        return ProcessBuilder(executable).redirectErrorStream(true).start().also { process ->
            processes += process
            // Bootstrap is written before the bridge owns the pipe; subsequent commands/framing
            // are the exact production implementation, not a mocked command parser.
            process.outputStream.write("$startupScript\nid() { $idScript; }\n".toByteArray())
            process.outputStream.flush()
        }
    }

    private fun pool(
        fast: Long = 150,
        grant: Long = 8_000,
        cooldown: Long = 3_000,
        launch: (Array<String>) -> Process,
    ) = StudyRootSessions(launch, fastTimeoutMs = fast, grantTimeoutMs = grant, failureCooldownMs = cooldown).also { sessions += it }

    private fun quoted(file: File) = "'${file.absolutePath.replace('\\', '/').replace("'", "'\"'\"'")}'"

    private fun awaitFile(file: File) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!file.exists() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("command did not start", file.exists())
    }

    private fun assertGranted(granted: Boolean) {
        if (!granted) {
            val traces = Thread.getAllStackTraces().filterKeys { it.name.startsWith("study-root") }
                .entries.joinToString("\n") { (thread, stack) -> "${thread.name} ${thread.state}\n${stack.joinToString("\n")}" }
            assertTrue(traces, granted)
        }
    }

    @Test fun `parallel grants share one process and a fast observer cannot cancel its slow authorization`() {
        val launches = AtomicInteger()
        val started = CountDownLatch(1)
        val pool = pool(fast = 80) {
            launches.incrementAndGet()
            shell("sleep 0.3; printf '0\\n'").also { started.countDown() }
        }
        val grant = workers.submit<Boolean> { pool.available(allowGrant = true) }
        assertTrue(started.await(3, TimeUnit.SECONDS))
        val others = (1..8).map { workers.submit<Boolean> { pool.available(allowGrant = true) } }
        assertFalse(pool.available(allowGrant = false))
        assertGranted(grant.get(10, TimeUnit.SECONDS))
        assertTrue(others.all { it.get(10, TimeUnit.SECONDS) })
        assertEquals(1, launches.get())
        assertEquals("ready", pool.exec("printf ready", 1000))
        assertEquals(1, launches.get())
    }

    @Test fun `a user grant extends an already pending fast handshake without opening another su`() {
        val launches = AtomicInteger()
        val started = CountDownLatch(1)
        val beginHandshake = CountDownLatch(1)
        val pool = pool(fast = 200) {
            launches.incrementAndGet()
            started.countDown()
            assertTrue(beginHandshake.await(3, TimeUnit.SECONDS))
            shell("sleep 0.4; printf '0\\n'")
        }
        val fast = workers.submit<String?> { pool.exec("printf must-not-run", 200) }
        assertTrue(started.await(3, TimeUnit.SECONDS))
        val grant = workers.submit<Boolean> { pool.available(allowGrant = true) }
        assertNull(fast.get(2, TimeUnit.SECONDS))
        // The quick caller has left while process launch is pending. Its completion must not
        // cancel the same opening attempt that the explicit grant is now waiting on.
        beginHandshake.countDown()
        assertGranted(grant.get(10, TimeUnit.SECONDS))
        assertEquals(1, launches.get())
    }

    @Test fun `no newline output old sentinels failures and parallel commands preserve the same session`() {
        val launches = AtomicInteger()
        val pool = pool { launches.incrementAndGet(); shell() }
        assertGranted(pool.available(allowGrant = true))
        assertEquals("no-newline", pool.exec("printf no-newline", 1000))
        assertEquals("__RIKKA_STUDY_EOC__0", pool.exec("printf __RIKKA_STUDY_EOC__0", 1000))
        assertNull(pool.exec("false", 1000))
        val commands = (1..12).map { number -> workers.submit<String?> { pool.exec("printf command-$number", 3000) } }
        assertEquals((1..12).map { "command-$it" }, commands.map { it.get(4, TimeUnit.SECONDS) })
        assertEquals(1, launches.get())
    }

    @Test fun `a root manager banner without a newline cannot corrupt verified uid output`() {
        val pool = pool { shell(startupScript = "printf 'manager ready'") }
        assertTrue(pool.available(allowGrant = true))
        assertEquals("clean", pool.exec("printf clean", 1000))
    }

    @Test fun `a timed out command is not replayed and a child holding stdout cannot block teardown`() {
        val launches = AtomicInteger()
        val marker = File(temporary.root, "executed")
        val pool = pool { launches.incrementAndGet(); shell() }
        assertGranted(pool.available(allowGrant = true))
        val begin = System.nanoTime()
        assertNull(pool.exec("printf x >> ${quoted(marker)}; sleep 1; printf late", 120))
        assertTrue("closing BufferedReader blocked the timeout caller", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 800)
        awaitFile(marker)
        assertEquals("x", marker.readText())
        assertEquals(1, launches.get())
        repeat(3) { assertFalse(pool.available(allowGrant = false)) }
        assertEquals(1, launches.get())
        assertGranted(pool.available(allowGrant = true))
        assertEquals("new", pool.exec("printf new", 1000))
        assertEquals(2, launches.get())
        assertEquals("x", marker.readText())
    }

    @Test fun `a command expiring in the queue does not kill another callers active shell`() {
        val launches = AtomicInteger()
        val marker = File(temporary.root, "started")
        val pool = pool { launches.incrementAndGet(); shell() }
        assertTrue(pool.available(allowGrant = true))
        val first = workers.submit<String?> { pool.exec("printf started > ${quoted(marker)}; sleep 0.3; printf first", 1500) }
        awaitFile(marker)
        assertNull(pool.exec("printf must-not-run >> ${quoted(marker)}", 40))
        assertEquals("first", first.get(2, TimeUnit.SECONDS))
        assertEquals("started", marker.readText())
        assertEquals("healthy", pool.exec("printf healthy", 1000))
        assertEquals(1, launches.get())
    }

    @Test fun `denial and non root uid fail quickly without trying two more authorization syntaxes`() {
        val rejected = AtomicInteger()
        val denied = pool { rejected.incrementAndGet(); shell("printf 'permission denied\\n'; return 1") }
        assertFalse(denied.available(allowGrant = true))
        repeat(3) { assertFalse(denied.available(allowGrant = false)) }
        assertEquals(1, rejected.get())
        val nonRoot = AtomicInteger()
        val ordinary = pool { nonRoot.incrementAndGet(); shell("printf '2000\\n'") }
        assertFalse(ordinary.available(allowGrant = true))
        assertEquals(1, nonRoot.get())
    }

    @Test fun `unsupported interactive su selects one persistent compatibility variant for all commands`() {
        val arguments = Collections.synchronizedList(mutableListOf<List<String>>())
        val pool = pool {
            arguments += it.toList()
            if (it.size == 1) shell("printf 'usage: su -c command\\n'; return 1") else shell()
        }
        assertTrue(pool.available(allowGrant = true))
        assertEquals("one", pool.exec("printf one", 1000))
        assertEquals("two", pool.exec("printf two", 1000))
        assertEquals(listOf(listOf("su"), listOf("su", "-c", "sh")), arguments.toList())
    }

    @Test fun `missing su is cooled down for background polling but an explicit retry is allowed`() {
        val launches = AtomicInteger()
        val pool = pool {
            if (launches.incrementAndGet() == 1) throw IOException("no su")
            shell()
        }
        assertNull(pool.exec("printf must-not-run", 1000))
        repeat(4) { assertFalse(pool.available(allowGrant = false)) }
        assertNull(pool.exec("printf must-not-run", 1000))
        assertEquals(1, launches.get())
        assertTrue(pool.available(allowGrant = true))
        assertEquals(2, launches.get())
    }

    @Test fun `cold status checks and resumed settings never start su or ask for authorization`() {
        val launches = AtomicInteger()
        val pool = pool { launches.incrementAndGet(); shell() }
        repeat(12) { assertFalse(pool.available(allowGrant = false)) }
        assertEquals(0, launches.get())
        assertTrue(pool.available(allowGrant = true))
        repeat(12) { assertTrue(pool.available(allowGrant = false)) }
        assertEquals(1, launches.get())
        pool.shutdown()
        assertFalse(pool.available(allowGrant = false))
        assertEquals(1, launches.get())
    }

    @Test fun `process death invalidates a previous grant instead of caching permanent root`() {
        val launches = AtomicInteger()
        val pool = pool {
            launches.incrementAndGet()
            shell()
        }
        assertTrue(pool.available(allowGrant = true))
        processes.first().destroyForcibly().waitFor(2, TimeUnit.SECONDS)
        assertTrue(pool.available(allowGrant = true))
        assertEquals(2, launches.get())
    }

    @Test fun `explicit shutdown closes the process and a late grant cannot resurrect it`() {
        val launched = CountDownLatch(1)
        val permit = CountDownLatch(1)
        val launches = AtomicInteger()
        val pool = pool {
            if (launches.incrementAndGet() == 1) {
                launched.countDown()
                assertTrue(permit.await(3, TimeUnit.SECONDS))
            }
            shell()
        }
        val pending = workers.submit<Boolean> { pool.available(allowGrant = true) }
        assertTrue(launched.await(3, TimeUnit.SECONDS))
        pool.shutdown()
        assertFalse(pending.get(1, TimeUnit.SECONDS))
        permit.countDown()
        assertTrue(pool.available(allowGrant = true))
        assertEquals(2, launches.get())
        pool.shutdown()
        assertTrue(processes.all { it.waitFor(2, TimeUnit.SECONDS) })
    }
}
