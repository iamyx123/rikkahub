package me.rerere.rikkahub.data.study

import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * Minimal root bridge for the silent study-desktop operations. Every call goes through `su` and
 * degrades to `null`/`false` on any failure so callers can always fall back to the normal
 * (accessibility + role chooser) flow.
 *
 * One verified, process-owned shell is shared by study mode and reading power. Quick probes join
 * an existing authorization attempt without cancelling it. Explicit user grants may wait longer;
 * failed/denied background probes back off. No command is replayed after an uncertain timeout.
 */
object StudyRootBridge {
    private const val TAG = "StudyRootBridge"
    private const val ROLE_HOME = "android.app.role.HOME"
    private const val HOME_ACTIVITY = "me.rerere.rikkahub.StudyHomeActivity"

    /** General command timeout for everything else. */
    private const val CMD_TIMEOUT_MS = 8_000L
    /** Role changes are applied asynchronously by the role controller; poll for confirmation. */
    private const val ROLE_SETTLE_TIMEOUT_MS = 3_000L
    private const val ROLE_SETTLE_STEP_MS = 150L

    private val sessions = StudyRootSessions(
        launch = { argv -> ProcessBuilder(*argv).redirectErrorStream(true).start() },
        elapsedRealtime = SystemClock::elapsedRealtime,
        log = { Log.w(TAG, it) },
    )

    /** Android user id this process runs as; `cmd role` otherwise defaults to user 0. */
    private val userId: Int by lazy { android.os.Process.myUid() / 100_000 }

    /**
     * Read-only check of the verified live shell or an already pending handshake. Merely opening
     * settings/resuming the app must never start su or display a superuser authorization window.
     */
    fun isRootAvailable(): Boolean = sessions.available(allowGrant = false)

    /**
     * User-initiated grant: joins one shared handshake and waits up to 60 seconds for the root
     * manager. It never runs the short probe/fallback chain first. Call on a background thread.
     */
    fun isRootAvailableAllowGrant(): Boolean = sessions.available(allowGrant = true)

    /**
     * Runs a command as root and returns its stdout ("" on success without output, null on any
     * failure or non-zero exit). Reuses one persistent `su` shell. A timed-out or interrupted
     * command is never automatically executed a second time.
     */
    fun runSu(command: String, timeoutMs: Long = CMD_TIMEOUT_MS): String? = sessions.exec(command, timeoutMs)

    /** Explicit process-level teardown; stopping one feature must not close the shared shell. */
    fun shutdown() = sessions.shutdown()

    // --- Battery whitelist ---

    fun whitelistBattery(packageName: String): Boolean {
        // `dumpsys deviceidle whitelist +pkg` is the widely supported form; `cmd deviceidle` exists
        // on newer builds. RUN_ANY_IN_BACKGROUND additionally survives aggressive OEM app standby.
        val whitelisted = runSu("dumpsys deviceidle whitelist +$packageName") != null ||
            runSu("cmd deviceidle whitelist +$packageName") != null
        runSu("cmd appops set $packageName RUN_ANY_IN_BACKGROUND allow")
        return whitelisted
    }

    fun isBatteryWhitelisted(packageName: String): Boolean {
        val output = runSu("dumpsys deviceidle whitelist") ?: return false
        return output.lineSequence().any { it.contains(packageName) }
    }

    // --- Default HOME role ---

    /** Returns the package currently holding a role, or null when unknown/none. */
    fun currentRoleHolder(role: String = ROLE_HOME): String? {
        val output = runSu("cmd role get-role-holders --user $userId $role")
            ?: runSu("cmd role get-role-holders $role")
            ?: return null
        // Output is the bare package name (possibly empty, possibly "null").
        return parseRoleHolder(output)
    }

    /**
     * Sets this package as the default HOME via the role manager, then waits for the role controller
     * to actually apply it. Returns true once the role holder matches [packageName]; callers still
     * verify with their own RoleManager query afterwards.
     */
    fun setDefaultHome(packageName: String): Boolean {
        if (currentRoleHolder() == packageName) return true
        // The HOME activity is disabled until study mode is active; the role manager only accepts
        // packages whose home component is resolvable.
        runSu("pm enable $packageName/$HOME_ACTIVITY")
        if (addRoleHolder(ROLE_HOME, packageName) && awaitRoleHolder(packageName)) return true
        // Some builds gate add-role-holder behind the qualification bypass flag.
        runSu("cmd role set-bypassing-role-qualification true")
        val ok = addRoleHolder(ROLE_HOME, packageName) && awaitRoleHolder(packageName)
        if (!ok) Log.w(TAG, "Unable to take the HOME role for $packageName")
        return ok
    }

    /** Removes this package from the HOME role (used when no original holder is known). */
    fun clearDefaultHome(packageName: String): Boolean {
        // `remove-role-holder` is the documented subcommand name; `clear-role-holders` drops every
        // holder and lets the system fall back to the only remaining launcher.
        val removed = runSu("cmd role remove-role-holder --user $userId $ROLE_HOME $packageName") != null ||
            runSu("cmd role remove-role-holder $ROLE_HOME $packageName") != null ||
            runSu("cmd role clear-role-holders --user $userId $ROLE_HOME") != null
        if (!removed) return false
        return awaitRoleHolderChange(packageName)
    }

    /**
     * `add-role-holder` is the AOSP subcommand (`RoleShellCommand`); the short `add-holder` spelling
     * only ever existed in downstream builds, so it is kept purely as a fallback.
     */
    private fun addRoleHolder(role: String, packageName: String): Boolean =
        runSu("cmd role add-role-holder --user $userId $role $packageName") != null ||
            runSu("cmd role add-role-holder $role $packageName") != null ||
            runSu("cmd role add-holder $role $packageName") != null

    /** Role changes land asynchronously; poll briefly instead of reading back immediately. */
    private fun awaitRoleHolder(packageName: String): Boolean =
        awaitRole { currentRoleHolder() == packageName }

    private fun awaitRoleHolderChange(packageName: String): Boolean =
        awaitRole { currentRoleHolder() != packageName }

    private inline fun awaitRole(predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + ROLE_SETTLE_TIMEOUT_MS
        while (true) {
            if (predicate()) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            runCatching { Thread.sleep(ROLE_SETTLE_STEP_MS) }.onFailure { return false }
        }
    }

    // --- Foreground interception ---

    /**
     * Returns the package currently in the foreground, or null when it cannot be determined.
     *
     * Both dumps are filtered with `grep` inside the root shell: `dumpsys activity activities` alone
     * is hundreds of KB, and transferring that every poll is what made the guard expensive.
     * The package is read from the `ActivityRecord{hash uUSER pkg/cls}` shape — the previous
     * implementation's pattern captured the trailing task id (`t1234`) instead of the package.
     */
    fun foregroundPackage(): String? {
        runSu(
            "dumpsys activity activities | grep -E " +
                "'topResumedActivity|mResumedActivity|ResumedActivity|mFocusedApp'",
        )?.let { out ->
            parseActivityDump(out)?.let { return it }
        }
        runSu("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")?.let { out ->
            parseWindowDump(out)?.let { return it }
        }
        return null
    }

    /** Parses a filtered `dumpsys activity activities` dump. Visible for tests. */
    internal fun parseActivityDump(dump: String): String? = firstPackage(dump, ACTIVITY_RECORD_RE)

    /** Parses a filtered `dumpsys window` dump, falling back to any ActivityRecord line. */
    internal fun parseWindowDump(dump: String): String? =
        firstPackage(dump, WINDOW_RE) ?: firstPackage(dump, ACTIVITY_RECORD_RE)

    /** Parses `cmd role get-role-holders` output. Visible for tests. */
    internal fun parseRoleHolder(output: String): String? = output.lineSequence()
        .map { it.trim() }
        .firstOrNull { line ->
            line.isNotEmpty() && line != "null" && !line.startsWith("Error") &&
                line.matches(PACKAGE_RE)
        }

    /**
     * Picks the first package from a filtered dump. `topResumedActivity` is preferred when present:
     * on multi-window/multi-display devices several resumed records can be listed at once.
     */
    private fun firstPackage(dump: String, pattern: Regex): String? {
        val lines = dump.lineSequence().filter { it.isNotBlank() }.toList()
        val preferred = lines.firstOrNull { it.contains("topResumedActivity") }
        preferred?.let { line -> pattern.find(line)?.let { return it.groupValues[1] } }
        for (line in lines) {
            pattern.find(line)?.let { return it.groupValues[1] }
        }
        return null
    }

    /** Sends a HOME key event, which the system routes to the default HOME (the study desktop). */
    fun pressHome(): Boolean = runSu("input keyevent 3") != null

    /** Brings the study desktop to the front directly (fallback when HOME injection is refused). */
    fun startStudyHome(packageName: String): Boolean =
        runSu(
            "am start -n $packageName/$HOME_ACTIVITY " +
                "-a android.intent.action.MAIN -c android.intent.category.HOME " +
                "--activity-clear-top --activity-single-top --activity-new-task",
        ) != null

    /** Force-stops a blocked app outright. Used only for repeat offenders the HOME key cannot hide. */
    fun forceStop(packageName: String): Boolean = runSu("am force-stop $packageName") != null

    private val PACKAGE_RE = Regex("""[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+""")
    /** `ActivityRecord{4d0f8b1 u0 com.example/.MainActivity t123}` -> `com.example` */
    private val ACTIVITY_RECORD_RE = Regex("""ActivityRecord\{\S+\s+u\d+\s+([A-Za-z0-9_.]+)/""")
    /** `Window{a1b2 u0 com.example/com.example.Main}` -> `com.example` (skips `NavigationBar0`) */
    private val WINDOW_RE = Regex("""Window\{\S+\s+u\d+\s+([A-Za-z0-9_.]+)/""")

}

/** JVM-testable session ownership. Android/UI callers never own individual su processes. */
internal class StudyRootSessions(
    private val launch: (Array<String>) -> Process,
    private val elapsedRealtime: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (String) -> Unit = {},
    private val fastTimeoutMs: Long = 1_500,
    private val grantTimeoutMs: Long = 60_000,
    private val failureCooldownMs: Long = 30_000,
) {
    private class Opening(deadline: Long, val grantDeadline: Long) {
        val deadline = AtomicLong(deadline)
        val result = CompletableFuture<StudyRootShell?>()
        val cancelled = AtomicBoolean()
        @Volatile var shell: StudyRootShell? = null
    }

    private val lock = Any()
    private val opener = Executors.newSingleThreadExecutor { task -> Thread(task, "study-root-open").apply { isDaemon = true } }
    private var current: StudyRootShell? = null
    private var opening: Opening? = null
    private var retryAfter = 0L

    fun available(allowGrant: Boolean): Boolean = session(allowGrant, if (allowGrant) grantTimeoutMs else fastTimeoutMs, create = allowGrant)?.alive == true

    fun exec(command: String, timeoutMs: Long): String? {
        if (timeoutMs <= 0) return null
        val deadline = elapsedRealtime() + timeoutMs
        val active = session(allowGrant = false, waitMs = minOf(timeoutMs, fastTimeoutMs)) ?: return null
        if (elapsedRealtime() >= deadline) return null
        val result = try { active.exec(command) { deadline } } catch (_: Exception) {
            active.close()
            StudyRootResult("")
        }
        if (!active.alive) synchronized(lock) {
            if (current === active) {
                current = null
                retryAfter = elapsedRealtime() + failureCooldownMs
            }
        }
        return result.output.takeIf { result.exitCode == 0 }
    }

    private fun session(allowGrant: Boolean, waitMs: Long, create: Boolean = true): StudyRootShell? {
        val attempt = synchronized(lock) {
            current?.takeIf { it.alive }?.let { return it }
            current?.close()
            current = null
            val pending = opening
            if (pending != null) {
                // A short observer may stop waiting, but cannot shorten a user's grant dialog.
                if (allowGrant) pending.deadline.accumulateAndGet(pending.grantDeadline) { previous, granted -> maxOf(previous, granted) }
                pending
            } else {
                if (!create) return null
                if (!allowGrant && elapsedRealtime() < retryAfter) return null
                val now = elapsedRealtime()
                Opening(now + if (allowGrant) grantTimeoutMs else fastTimeoutMs, now + grantTimeoutMs).also {
                    opening = it
                    opener.execute { open(it) }
                }
            }
        }
        return try {
            attempt.result.get(waitMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)?.takeIf { it.alive }
        } catch (_: TimeoutException) {
            // Only the opening worker owns cancellation and its extendable deadline.
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: ExecutionException) {
            null
        }
    }

    private fun open(attempt: Opening) {
        var verified: StudyRootShell? = null
        try {
            // Compatibility variants still create a persistent shell, never one su per command.
            for (argv in listOf(arrayOf("su"), arrayOf("su", "-c", "sh"), arrayOf("su", "0", "sh"))) {
                if (attempt.cancelled.get() || elapsedRealtime() >= attempt.deadline.get()) break
                val process = try { launch(argv) } catch (_: IOException) { break }
                val candidate = StudyRootShell(process, elapsedRealtime, log)
                attempt.shell = candidate
                if (attempt.cancelled.get()) { candidate.close(); break }
                val probe = candidate.exec("printf '\\n'; id -u") { attempt.deadline.get() }
                if (probe.exitCode == 0 && probe.output.lineSequence().lastOrNull { it.isNotBlank() }?.trim() == "0" && candidate.alive) {
                    verified = candidate
                    break
                }
                log("Root handshake failed (exit=${probe.exitCode}, timeout=${probe.timedOut}, live=${candidate.alive})")
                candidate.close()
                // Rejection, non-root uid and timeouts are definitive. Only an explicitly
                // unsupported interactive invocation qualifies for another su syntax.
                val unsupported = probe.output.contains(Regex("(?i)usage:|unknown option|unrecognized option|interactive.*not supported|must specify.*command")) ||
                    (probe.exitCode == null && probe.output.isBlank() && runCatching { process.exitValue() }.getOrNull() == 0)
                if (probe.timedOut || !unsupported || probe.output.contains(Regex("(?i)denied|not allowed|unauthorized|rejected"))) break
            }
        } catch (failure: Exception) {
            log("Root session could not be opened: ${failure.javaClass.simpleName}")
        } finally {
            synchronized(lock) {
                if (opening === attempt && !attempt.cancelled.get()) {
                    current = verified
                    opening = null
                    retryAfter = if (verified != null) 0 else elapsedRealtime() + failureCooldownMs
                    attempt.result.complete(verified)
                } else {
                    verified?.close()
                    attempt.result.complete(null)
                }
            }
            if (verified == null) attempt.shell?.close()
        }
    }

    /** Explicit shutdown invalidates pending grants too; a late authorization cannot resurrect it. */
    fun shutdown() {
        val closing = synchronized(lock) {
            val pending = opening
            pending?.cancelled?.set(true)
            pending?.result?.complete(null)
            opening = null
            val active = current
            current = null
            retryAfter = 0
            listOfNotNull(active, pending?.shell)
        }
        closing.forEach { it.close() }
    }
}

private data class StudyRootResult(val output: String, val exitCode: Int? = null, val timedOut: Boolean = false)

/** A command owns its framing and IO task. Killing a timed-out process always precedes stream close. */
private class StudyRootShell(
    private val process: Process,
    private val elapsedRealtime: () -> Long,
    private val log: (String) -> Unit,
) {
    private val stdin: BufferedWriter = process.outputStream.bufferedWriter()
    private val stdout: BufferedReader = process.inputStream.bufferedReader()
    private val io = Executors.newSingleThreadExecutor { task -> Thread(task, "study-root-io").apply { isDaemon = true } }
    private val commandLock = ReentrantLock()
    private val closed = AtomicBoolean()
    val alive: Boolean get() = !closed.get() && runCatching { process.exitValue(); false }.getOrElse { it is IllegalThreadStateException }

    fun exec(command: String, deadline: () -> Long): StudyRootResult {
        val wait = (deadline() - elapsedRealtime()).coerceAtLeast(0)
        val acquired = try { commandLock.tryLock(wait, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        if (!acquired) return StudyRootResult("") // A queue timeout did not execute or damage the shell.
        try {
            if (!alive || elapsedRealtime() >= deadline()) return StudyRootResult("")
            val marker = "__RIKKA_ROOT_${UUID.randomUUID().toString().replace("-", "")}__"
            val task = io.submit<StudyRootResult> {
                stdin.write("{\n:\n$command\n}\nprintf '\\n$marker:%s\\n' \"\$?\"\n")
                stdin.flush()
                val output = StringBuilder()
                while (true) {
                    val line = stdout.readLine() ?: return@submit StudyRootResult(output.toString().trim())
                    if (line.startsWith("$marker:")) {
                        val code = line.removePrefix("$marker:").toIntOrNull()
                        if (code != null) return@submit StudyRootResult(output.toString().trim(), code)
                    }
                    if (output.length + line.length <= 1024 * 1024) output.append(line).append('\n')
                    else throw IOException("Root command output exceeded its limit")
                }
                @Suppress("UNREACHABLE_CODE")
                StudyRootResult("")
            }
            try {
                while (true) {
                    val remaining = deadline() - elapsedRealtime()
                    if (remaining <= 0) {
                        task.cancel(true)
                        close()
                        log("Root command timed out; it will not be replayed")
                        return StudyRootResult("", timedOut = true)
                    }
                    try {
                        val result = task.get(minOf(remaining, 100), TimeUnit.MILLISECONDS)
                        if (result.exitCode == null) close()
                        return result
                    } catch (_: TimeoutException) { /* A user grant may extend this same handshake. */ }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                task.cancel(true)
                close()
                return StudyRootResult("")
            } catch (_: ExecutionException) {
                close()
                return StudyRootResult("")
            }
        } finally {
            commandLock.unlock()
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        // BufferedReader.close can wait on a blocked readLine lock. Never do that before killing
        // the process, or on the timeout caller's thread (libcore and desktop JVM both lock it).
        io.shutdownNow()
        Thread({
            // Some Process implementations also close pipe wrappers from destroy(); keep those
            // potentially blocking implementation details off the command/authorization caller.
            runCatching { process.destroyForcibly() }
            runCatching { process.destroy() }
            runCatching { stdin.close() }
            runCatching { stdout.close() }
            runCatching { process.errorStream.close() }
        }, "study-root-close").apply { isDaemon = true; start() }
    }
}
