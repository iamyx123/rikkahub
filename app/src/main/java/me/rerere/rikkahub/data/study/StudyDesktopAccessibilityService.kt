package me.rerere.rikkahub.data.study

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import me.rerere.rikkahub.StudyHomeActivity
import org.koin.android.ext.android.inject

class StudyDesktopAccessibilityService : AccessibilityService() {
    private val manager by inject<StudyDesktopManager>()
    private var lastReturnAt = 0L
    private val interceptionTracker = StudyInterceptionTracker()
    private val essentialSystemActivities by lazy { resolveEssentialSystemActivities() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        runCatching { manager.startKeepAliveIfPossible() }
            .onFailure { Log.e(TAG, "Unable to initialize study desktop guard", it) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        runCatching { handleAccessibilityEvent(event) }
            .onFailure { Log.e(TAG, "Study desktop guard ignored a malformed event", it) }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent?) {
        val guardedEvent = event ?: return
        if (guardedEvent.eventType !in GUARDED_EVENT_TYPES ||
            !manager.reconcileForegroundGuard()
        ) return
        val eventPackage = guardedEvent.packageName?.toString() ?: return
        if (eventPackage == packageName) {
            interceptionTracker.onAllowedForeground()
            return
        }
        val eventClass = guardedEvent.className?.toString().orEmpty()
        if (isAllowedForegroundPackage(eventPackage, eventClass)) {
            if (eventPackage in manager.pinnedApps.value.map { it.packageName }) {
                interceptionTracker.onAllowedForeground()
            }
            return
        }

        if (interceptionTracker.onBlockedForeground(eventPackage)) {
            manager.recordBlockedApp()
        }
        // Never debounce the block itself. GLOBAL_ACTION_HOME hides the foreign task at the
        // accessibility layer; the explicit activity launch is a fallback for OEM launchers.
        performGlobalAction(GLOBAL_ACTION_HOME)
        val now = SystemClock.elapsedRealtime()
        if (now - lastReturnAt < RETURN_DEBOUNCE_MS) return
        lastReturnAt = now

        val intent = Intent(this, StudyHomeActivity::class.java).apply {
            putExtra(StudyDesktopManager.EXTRA_OPEN_STUDY_DESKTOP, true)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        runCatching { startActivity(intent) }
    }

    override fun onInterrupt() = Unit

    private fun isAllowedForegroundPackage(eventPackage: String, eventClass: String): Boolean {
        // HOME/Overview gestures already dispatch to the selected HOME activity. Pulling another
        // activity from SystemUI at the same time caused duplicate launches, task flicker and loops.
        if (eventPackage == SYSTEM_UI_PACKAGE) return true
        if (eventPackage in resolveLauncherPackages() || eventPackage == SETTINGS_PACKAGE) return false
        if (eventPackage in manager.pinnedApps.value.map { it.packageName }) return true
        if (eventPackage in CORE_SYSTEM_PACKAGES) return true
        if (ComponentName(eventPackage, eventClass) in essentialSystemActivities) return true
        if (isEnabledInputMethodWindow(eventPackage, eventClass)) return true
        return false
    }

    private fun isEnabledInputMethodWindow(eventPackage: String, eventClass: String): Boolean {
        val enabled = getSystemService(InputMethodManager::class.java)
            ?.enabledInputMethodList
            .orEmpty()
        val isInputWindowClass = eventClass.startsWith("android.inputmethodservice.")
        return enabled.any { inputMethod ->
            inputMethod.packageName == eventPackage &&
                (inputMethod.serviceName == eventClass || isInputWindowClass)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveEssentialSystemActivities(): Set<ComponentName> {
        val intents = listOf(
            Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*"),
            Intent(Intent.ACTION_CREATE_DOCUMENT).setType("*/*"),
            Intent(Intent.ACTION_GET_CONTENT).setType("*/*"),
            Intent("android.content.pm.action.REQUEST_PERMISSIONS"),
            Intent("android.settings.MANAGE_APP_PERMISSIONS"),
        )
        val result = mutableSetOf<ComponentName>()
        intents.forEach { intent ->
            val candidates = packageManager.queryIntentActivities(intent, 0) +
                listOfNotNull(packageManager.resolveActivity(intent, 0))
            candidates.forEach { resolved ->
                val info = resolved.activityInfo
                val flags = info.applicationInfo.flags
                if (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                    result += ComponentName(info.packageName, info.name)
                    info.targetActivity?.let { target ->
                        result += ComponentName(info.packageName, target)
                    }
                }
            }
        }
        return result
    }

    private fun resolveLauncherPackages(): Set<String> {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolvedPackage = packageManager.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
        if (!resolvedPackage.isNullOrBlank() &&
            resolvedPackage != "android" &&
            resolvedPackage != packageName
        ) {
            return setOf(resolvedPackage)
        }
        return packageManager.queryIntentActivities(homeIntent, 0)
            .map { it.activityInfo.packageName }
            .filterTo(mutableSetOf()) { it != packageName }
    }

    companion object {
        private const val TAG = "StudyDesktopGuard"
        private val GUARDED_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        )
        private const val RETURN_DEBOUNCE_MS = 150L
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private val CORE_SYSTEM_PACKAGES = setOf(
            "android",
            SYSTEM_UI_PACKAGE,
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        )
    }
}
