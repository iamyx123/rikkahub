package me.rerere.rikkahub.data.study

/**
 * Counts one interception per attempted foreground transition while allowing the accessibility
 * service to repeat the actual HOME action for every event. Repeating the action is intentional:
 * OEM task managers can emit several different window events before the blocked task is hidden.
 */
internal class StudyInterceptionTracker {
    private var activeBlockedPackage: String? = null

    fun onAllowedForeground() {
        activeBlockedPackage = null
    }

    fun onBlockedForeground(packageName: String): Boolean {
        val shouldCount = activeBlockedPackage != packageName
        activeBlockedPackage = packageName
        return shouldCount
    }
}
