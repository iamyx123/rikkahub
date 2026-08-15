package me.rerere.rikkahub.data.study

import android.content.Context
import android.content.Intent
import android.os.SystemClock

internal enum class StudyDesktopSystemPolicyDirective {
    ENFORCE,
    RESTORE,
}

internal enum class StudyDesktopSystemPolicyReason {
    STATE_RECONCILE,
    FOREGROUND_HEARTBEAT,
    BOOT_COMPLETED,
    PACKAGE_REPLACED,
    USER_UNLOCKED,
    SERVICE_START,
    SERVICE_STOP,
}

/**
 * The study app is only a policy requester. The privileged SystemUI module remains responsible for
 * reading and restoring the exact pre-study system state and must independently verify the current
 * HOME holder before enforcing anything.
 */
internal fun studyDesktopSystemPolicyDirective(
    mode: StudyDesktopMode,
    isDefaultHome: Boolean,
): StudyDesktopSystemPolicyDirective =
    if (mode == StudyDesktopMode.ACTIVE && isDefaultHome) {
        StudyDesktopSystemPolicyDirective.ENFORCE
    } else {
        StudyDesktopSystemPolicyDirective.RESTORE
    }

internal class StudyDesktopSystemPolicyBridge(context: Context) {
    private val appContext = context.applicationContext
    private var lastDirective: StudyDesktopSystemPolicyDirective? = null
    private var lastPublishedAt = 0L

    @Synchronized
    fun publish(
        mode: StudyDesktopMode,
        isDefaultHome: Boolean,
        reason: StudyDesktopSystemPolicyReason,
        force: Boolean = false,
    ) {
        val directive = studyDesktopSystemPolicyDirective(mode, isDefaultHome)
        val now = SystemClock.elapsedRealtime()
        if (!force &&
            directive == lastDirective &&
            now - lastPublishedAt < HEARTBEAT_INTERVAL_MS
        ) {
            return
        }

        val intent = Intent(ACTION_RECONCILE_STUDY_DESKTOP_POLICY).apply {
            setPackage(SYSTEM_UI_PACKAGE)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(EXTRA_SCHEMA_VERSION, PROTOCOL_VERSION)
            putExtra(EXTRA_DIRECTIVE, directive.name)
            putExtra(EXTRA_REQUESTED_ACTIVE, directive == StudyDesktopSystemPolicyDirective.ENFORCE)
            putExtra(EXTRA_STUDY_PACKAGE, appContext.packageName)
            putExtra(EXTRA_STUDY_MODE, mode.name)
            putExtra(EXTRA_DEFAULT_HOME, isDefaultHome)
            putExtra(EXTRA_REASON, reason.name)
            putExtra(EXTRA_SENT_ELAPSED_REALTIME, now)
        }
        if (runCatching { appContext.sendBroadcast(intent) }.isSuccess) {
            lastDirective = directive
            lastPublishedAt = now
        }
    }

    companion object {
        const val ACTION_RECONCILE_STUDY_DESKTOP_POLICY =
            "com.openai.oceanmultitask.action.RECONCILE_STUDY_DESKTOP_POLICY"
        const val EXTRA_SCHEMA_VERSION = "schema_version"
        const val EXTRA_DIRECTIVE = "directive"
        const val EXTRA_REQUESTED_ACTIVE = "requested_active"
        const val EXTRA_STUDY_PACKAGE = "study_package"
        const val EXTRA_STUDY_MODE = "study_mode"
        const val EXTRA_DEFAULT_HOME = "default_home"
        const val EXTRA_REASON = "reason"
        const val EXTRA_SENT_ELAPSED_REALTIME = "sent_elapsed_realtime"

        const val PROTOCOL_VERSION = 1
        const val HEARTBEAT_INTERVAL_MS = 5_000L
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}
