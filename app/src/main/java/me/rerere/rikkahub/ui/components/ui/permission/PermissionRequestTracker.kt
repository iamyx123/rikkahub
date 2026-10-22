package me.rerere.rikkahub.ui.components.ui.permission

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue

/** Activity-result callbacks do not carry the requested name for a single permission. */
internal class PermissionRequestTracker(
    pending: List<String> = emptyList(),
    permanentlyDenied: Set<String> = emptySet(),
) {
    var pending by mutableStateOf(pending)
        private set
    var permanentlyDenied by mutableStateOf(permanentlyDenied)
        private set

    fun begin(permissions: List<String>): Boolean {
        if (pending.isNotEmpty() || permissions.isEmpty()) return false
        pending = permissions.distinct()
        return true
    }

    fun clearPending() {
        pending = emptyList()
    }

    fun recordPermanentDenial(permission: String, denied: Boolean) {
        permanentlyDenied = if (denied) permanentlyDenied + permission else permanentlyDenied - permission
    }

    companion object {
        // Save identity, not launcher/activity objects, for delivery after Activity recreation.
        val Saver = listSaver<PermissionRequestTracker, String>(
            save = { listOf(it.pending.joinToString("|"), it.permanentlyDenied.joinToString("|")) },
            restore = { values ->
                PermissionRequestTracker(
                    pending = values[0].split('|').filter(String::isNotEmpty),
                    permanentlyDenied = values[1].split('|').filter(String::isNotEmpty).toSet(),
                )
            },
        )
    }
}

/** A revoked one-time grant is requestable again, not evidence of permanent denial. */
internal fun resolvePermissionStatus(
    granted: Boolean,
    showRationale: Boolean,
    permanentlyDenied: Boolean,
): PermissionStatus = when {
    granted -> PermissionStatus.Granted
    showRationale -> PermissionStatus.Denied
    permanentlyDenied -> PermissionStatus.DeniedPermanently
    else -> PermissionStatus.NotRequested
}
