package me.rerere.rikkahub.data.study

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Root-based replacement for the accessibility foreground guard. A polling loop reads the current
 * foreground package over `su` and, when a non-whitelisted app is on top, sends a HOME key event so
 * the study desktop (the default HOME) returns. Slower to react than accessibility events by design,
 * but requires no accessibility permission at all.
 *
 * Polling cadence adapts to what is on screen: while the study desktop or a permitted app is in the
 * foreground the loop idles at [IDLE_POLL_MS]; right after a block it tightens to [ACTIVE_POLL_MS]
 * so an app that survives the first HOME press is caught quickly.
 */
class StudyRootForegroundGuard(private val manager: StudyDesktopManager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var lastInterceptAt = 0L
    /** Consecutive blocks of the same package, used to escalate from HOME press to force-stop. */
    private var repeatOffender: String? = null
    private var repeatCount = 0
    /** Cached so the hot loop does not query PackageManager/IMM on every tick. */
    private var allowCache: AllowList? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
        allowCache = null
        repeatOffender = null
        repeatCount = 0
        StudyRootBridge.shutdown()
    }

    private suspend fun run() {
        var poll = ACTIVE_POLL_MS
        while (coroutineContext.isActive) {
            if (!manager.reconcileForegroundGuard()) break
            val foreground = withContext(Dispatchers.IO) { StudyRootBridge.foregroundPackage() }
            poll = when {
                foreground.isNullOrBlank() -> {
                    // Could not read the foreground; stay responsive rather than idling.
                    ACTIVE_POLL_MS
                }
                isAllowed(foreground) -> {
                    if (foreground == manager.packageName) resetOffender()
                    IDLE_POLL_MS
                }
                else -> {
                    intercept(foreground)
                    ACTIVE_POLL_MS
                }
            }
            delay(poll)
        }
    }

    private suspend fun intercept(foreground: String) {
        val now = SystemClock.elapsedRealtime()
        if (foreground == repeatOffender) repeatCount++ else {
            repeatOffender = foreground
            repeatCount = 1
            // Count one interception per distinct app that reaches the foreground, matching the
            // accessibility guard's tracker semantics.
            manager.recordBlockedApp()
        }
        if (now - lastInterceptAt < INTERCEPT_COOLDOWN_MS) return
        lastInterceptAt = now

        withContext(Dispatchers.IO) {
            // HOME usually routes to the study desktop (the default HOME); fall back to a direct
            // launch only when HOME injection was refused.
            if (!StudyRootBridge.pressHome()) {
                StudyRootBridge.startStudyHome(manager.packageName)
            }
            // Some apps (and OEM task managers) immediately re-resume themselves. After several
            // failed HOME presses, stop the app outright — root's advantage over accessibility.
            if (repeatCount >= FORCE_STOP_AFTER_ATTEMPTS && !manager.isPinned(foreground)) {
                Log.w(TAG, "Force-stopping persistent foreground app: $foreground")
                StudyRootBridge.forceStop(foreground)
                resetOffender()
            }
        }
    }

    private fun resetOffender() {
        repeatOffender = null
        repeatCount = 0
    }

    private fun isAllowed(packageName: String): Boolean {
        if (packageName == manager.packageName) return true
        if (packageName in CORE_SYSTEM_PACKAGES) return true
        if (packageName in EXTRA_ALLOWED_PACKAGES) return true
        if (manager.isPinned(packageName)) return true
        val cache = allowList()
        // Another launcher on top means the user is switching HOME; that is handled by
        // reconcileForegroundGuard, not by fighting it here.
        if (packageName in cache.launchers) return false
        // Never yank the IME window — the accessibility guard treated it as essential too.
        if (packageName in cache.inputMethods) return true
        if (packageName in cache.essentialSystemApps) return true
        return false
    }

    private fun allowList(): AllowList {
        val cached = allowCache
        val now = SystemClock.elapsedRealtime()
        if (cached != null && now - cached.builtAt < ALLOW_CACHE_TTL_MS) return cached
        val fresh = AllowList(
            builtAt = now,
            launchers = manager.otherLauncherPackages(),
            inputMethods = manager.enabledInputMethodPackages(),
            essentialSystemApps = manager.essentialSystemPackages(),
        )
        allowCache = fresh
        return fresh
    }

    private class AllowList(
        val builtAt: Long,
        val launchers: Set<String>,
        val inputMethods: Set<String>,
        val essentialSystemApps: Set<String>,
    )

    companion object {
        private const val TAG = "StudyRootGuard"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        /** Cadence while a permitted app / the desktop is on screen. */
        private const val IDLE_POLL_MS = 1_200L
        /** Cadence right after a block, to catch apps that survive the first HOME press. */
        private const val ACTIVE_POLL_MS = 450L
        private const val INTERCEPT_COOLDOWN_MS = 900L
        private const val FORCE_STOP_AFTER_ATTEMPTS = 4
        private const val ALLOW_CACHE_TTL_MS = 30_000L
        private val CORE_SYSTEM_PACKAGES = setOf(
            "android",
            SYSTEM_UI_PACKAGE,
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        )
        // System activities a whitelisted study app legitimately opens (file pickers, installers,
        // download UI) must not be treated as foreign apps.
        private val EXTRA_ALLOWED_PACKAGES = setOf(
            "com.android.documentsui",
            "com.google.android.documentsui",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.providers.downloads",
            "com.android.providers.downloads.ui",
            "com.android.externalstorage",
            "com.android.providers.media.module",
            "com.google.android.providers.media.module",
            "com.android.inputmethod.latin",
            "com.google.android.inputmethod.latin",
        )
    }
}
