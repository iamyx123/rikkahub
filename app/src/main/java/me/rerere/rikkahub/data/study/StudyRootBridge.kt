package me.rerere.rikkahub.data.study

import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Minimal root bridge for the silent study-desktop operations. Every call goes through `su` and
 * degrades to `null`/`false` on any failure so callers can always fall back to the normal
 * (accessibility + role chooser) flow.
 *
 * Three properties keep it usable on real devices:
 *  - A **persistent** `su` shell ([RootShell]) serves every command. Spawning a fresh `su` per
 *    command made the 1.5s foreground poll re-enter the superuser gate constantly, which on Magisk
 *    means repeated prompt/log churn and ~100ms of process setup per poll.
 *  - Commands are sentinel-delimited and read on a dedicated thread, so a hung `dumpsys` cannot
 *    block the caller past its timeout and cannot deadlock on a full pipe.
 *  - [isRootAvailable] caches the probe with a short TTL instead of forever, so a first-time Magisk
 *    authorization prompt that times out does not permanently mark the device as unrooted.
 */
object StudyRootBridge {
    private const val TAG = "StudyRootBridge"
    private const val ROLE_HOME = "android.app.role.HOME"
    private const val HOME_ACTIVITY = "me.rerere.rikkahub.StudyHomeActivity"

    /** Marks the end of one command's output inside the persistent shell. */
    private const val SENTINEL = "__RIKKA_STUDY_EOC__"

    /** Short probe used for UI checks and the state machine. */
    private const val FAST_PROBE_TIMEOUT_MS = 1_500L
    /** Long probe that gives Magisk's first-time grant dialog time to answer. */
    private const val GRANT_PROBE_TIMEOUT_MS = 12_000L
    /** General command timeout for everything else. */
    private const val CMD_TIMEOUT_MS = 8_000L
    /** How long a cached root result stays fresh before a re-probe. */
    private const val ROOT_REFRESH_MS = 30_000L
    /** Role changes are applied asynchronously by the role controller; poll for confirmation. */
    private const val ROLE_SETTLE_TIMEOUT_MS = 3_000L
    private const val ROLE_SETTLE_STEP_MS = 150L

    @Volatile
    private var rootAvailable = false

    @Volatile
    private var rootCheckedAt = 0L

    @Volatile
    private var probeInFlight = false

    private val probeLock = Any()
    private val shellLock = Any()

    @Volatile
    private var shell: RootShell? = null

    /** Android user id this process runs as; `cmd role` otherwise defaults to user 0. */
    private val userId: Int by lazy { android.os.Process.myUid() / 100_000 }

    /**
     * Fast cached root check. On a cold cache it probes for at most [FAST_PROBE_TIMEOUT_MS]; the
     * result is reused for [ROOT_REFRESH_MS]. Safe to call from any thread.
     */
    fun isRootAvailable(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - rootCheckedAt < ROOT_REFRESH_MS) return rootAvailable
        if (probeInFlight) return rootAvailable
        synchronized(probeLock) {
            if (probeInFlight) return rootAvailable
            probeInFlight = true
            try {
                rootAvailable = probeRoot(FAST_PROBE_TIMEOUT_MS)
                rootCheckedAt = SystemClock.elapsedRealtime()
            } finally {
                probeInFlight = false
            }
        }
        return rootAvailable
    }

    /**
     * Long probe for the silent-enable path: when the fast probe is negative it waits up to
     * [GRANT_PROBE_TIMEOUT_MS] so Magisk's first-time authorization dialog can be answered. Call on
     * a background thread. Warms the fast cache on success.
     */
    fun isRootAvailableAllowGrant(): Boolean {
        if (isRootAvailable()) return true
        val granted = probeRoot(GRANT_PROBE_TIMEOUT_MS)
        rootAvailable = granted
        rootCheckedAt = SystemClock.elapsedRealtime()
        return granted
    }

    private fun probeRoot(timeoutMs: Long): Boolean {
        val output = runSu("id", timeoutMs)
        val ok = output?.contains("uid=0") == true
        if (!ok) Log.w(TAG, "Root probe failed (timeout=${timeoutMs}ms): ${output ?: "no output"}")
        return ok
    }

    /**
     * Runs a command as root and returns its stdout ("" on success without output, null on any
     * failure or non-zero exit). Reuses one persistent `su` shell; if that shell is unusable the
     * call falls back to a one-shot `su -c`.
     */
    fun runSu(command: String, timeoutMs: Long = CMD_TIMEOUT_MS): String? {
        session(timeoutMs)?.let { active ->
            val result = active.exec(command, timeoutMs)
            if (result != null) return result
            // A dead session yields null for reasons unrelated to the command; retry once on a
            // freshly spawned shell before giving up on the persistent path.
            if (!active.alive) {
                dropSession(active)
                session(timeoutMs)?.exec(command, timeoutMs)?.let { return it }
            }
            return null
        }
        return runSuOneShot(command, timeoutMs)
    }

    private fun session(timeoutMs: Long): RootShell? {
        shell?.takeIf { it.alive }?.let { return it }
        synchronized(shellLock) {
            shell?.takeIf { it.alive }?.let { return it }
            shell?.close()
            shell = RootShell.open(timeoutMs)
            return shell
        }
    }

    private fun dropSession(dead: RootShell) {
        synchronized(shellLock) {
            if (shell === dead) {
                shell?.close()
                shell = null
            }
        }
    }

    /** Releases the persistent shell (called when the guard stops). */
    fun shutdown() {
        synchronized(shellLock) {
            shell?.close()
            shell = null
        }
    }

    /** One-shot fallback for devices whose `su` refuses an interactive session. */
    private fun runSuOneShot(command: String, timeoutMs: Long): String? {
        val attempts = listOf(
            arrayOf("su", "-c", command),
            arrayOf("su", "0", "sh", "-c", command),
        )
        for (argv in attempts) {
            execOnce(argv, timeoutMs)?.let { return it }
        }
        return null
    }

    private fun execOnce(argv: Array<String>, timeoutMs: Long): String? = runCatching {
        val process = ProcessBuilder(*argv).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { output.append(it).append('\n') }
            }
        }
        reader.isDaemon = true
        reader.start()
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            runCatching { process.destroyForcibly() }
            reader.join(500)
            Log.w(TAG, "Command timed out (${timeoutMs}ms): ${argv.joinToString(" ")}")
            return null
        }
        reader.join(500)
        if (process.exitValue() != 0) return null
        output.toString().trim()
    }.getOrNull()

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

    /**
     * One long-lived `su` shell. Commands are written to stdin and terminated by an echoed sentinel
     * carrying the exit status, so a single stream can serve any number of commands.
     */
    private class RootShell private constructor(
        private val process: Process,
        private val stdin: BufferedWriter,
        private val stdout: BufferedReader,
    ) {
        private val readerPool = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "study-root-shell").apply { isDaemon = true }
        }

        @Volatile
        private var broken = false

        val alive: Boolean get() = !broken && process.isAlive

        /** Serialized: one command at a time per shell. */
        @Synchronized
        fun exec(command: String, timeoutMs: Long): String? {
            if (!alive) return null
            return runCatching {
                stdin.write(command)
                stdin.write("\n")
                // `$?` is the exit status of the command above.
                stdin.write("echo $SENTINEL\$?")
                stdin.write("\n")
                stdin.flush()
                val future = readerPool.submit<String?> { readUntilSentinel() }
                try {
                    future.get(timeoutMs, TimeUnit.MILLISECONDS)
                } catch (timeout: Exception) {
                    // The reader thread is still parked on this command's output; the stream can no
                    // longer be trusted for subsequent commands.
                    future.cancel(true)
                    Log.w(TAG, "Root command timed out (${timeoutMs}ms): $command")
                    close()
                    null
                }
            }.getOrElse {
                close()
                null
            }
        }

        private fun readUntilSentinel(): String? {
            val output = StringBuilder()
            while (true) {
                val line = stdout.readLine() ?: run {
                    broken = true
                    return null
                }
                if (line.startsWith(SENTINEL)) {
                    val code = line.removePrefix(SENTINEL).trim().toIntOrNull() ?: -1
                    return if (code == 0) output.toString().trim() else null
                }
                output.append(line).append('\n')
            }
        }

        fun close() {
            broken = true
            runCatching { stdin.close() }
            runCatching { stdout.close() }
            runCatching { process.destroyForcibly() }
            runCatching { readerPool.shutdownNow() }
        }

        companion object {
            fun open(timeoutMs: Long): RootShell? = runCatching {
                val process = ProcessBuilder("su").redirectErrorStream(true).start()
                val shell = RootShell(
                    process = process,
                    stdin = process.outputStream.bufferedWriter(),
                    stdout = process.inputStream.bufferedReader(),
                )
                // Confirm the shell really is root before handing it out; `su` on a non-rooted
                // device may start and immediately die, or start as the unprivileged user.
                val id = shell.exec("id", timeoutMs)
                if (id?.contains("uid=0") != true) {
                    shell.close()
                    null
                } else {
                    shell
                }
            }.getOrNull()
        }
    }
}
