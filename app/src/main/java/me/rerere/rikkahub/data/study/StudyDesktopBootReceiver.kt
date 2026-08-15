package me.rerere.rikkahub.data.study

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StudyDesktopBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val reason = when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED -> StudyDesktopSystemPolicyReason.BOOT_COMPLETED
            Intent.ACTION_MY_PACKAGE_REPLACED -> StudyDesktopSystemPolicyReason.PACKAGE_REPLACED
            Intent.ACTION_USER_UNLOCKED -> StudyDesktopSystemPolicyReason.USER_UNLOCKED
            else -> return
        }
        val manager = StudyDesktopManager(context)
        val mode = manager.reconcileState()
        manager.publishSystemPolicy(reason, force = true)
        if (mode == StudyDesktopMode.ACTIVE) {
            manager.startKeepAliveIfPossible()
        }
    }
}
