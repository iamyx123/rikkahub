package me.rerere.rikkahub.ui.components.ui.permission

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

internal interface PermissionAccess {
    fun isGranted(permission: String): Boolean
    fun shouldShowRationale(permission: String): Boolean
    fun openAppSettings()
}

internal class AndroidPermissionAccess(
    private val context: Context,
    private val activity: ComponentActivity,
) : PermissionAccess {
    override fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override fun shouldShowRationale(permission: String) = activity.shouldShowRequestPermissionRationale(permission)

    override fun openAppSettings() {
        activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        })
    }
}

/** Runtime permission state always rechecks the system before starting another request. */
@Stable
class PermissionState internal constructor(
    private val permissionProvider: () -> Set<PermissionInfo>,
    private val access: PermissionAccess,
    private val requestTracker: PermissionRequestTracker,
) {
    private val permissions get() = permissionProvider()
    private val _permissionStates = mutableStateMapOf<String, PermissionStatus>()
    val permissionStates: Map<String, PermissionStatus> = _permissionStates
    val isRequestInFlight: Boolean get() = requestTracker.pending.isNotEmpty()

    var showRationaleDialog by mutableStateOf(false)
        private set
    var currentRationalePermissions by mutableStateOf<List<PermissionInfo>>(emptyList())
        private set

    private var multipleRequest: ((Array<String>) -> Unit)? = null
    private var singleRequest: ((String) -> Unit)? = null

    init {
        updatePermissionStates()
    }

    internal fun setPermissionLaunchers(
        multiplePermissionLauncher: ActivityResultLauncher<Array<String>>,
        singlePermissionLauncher: ActivityResultLauncher<String>,
    ) {
        setPermissionRequestHandlers(
            multiple = { multiplePermissionLauncher.launch(it) },
            single = { singlePermissionLauncher.launch(it) },
        )
    }

    internal fun setPermissionRequestHandlers(multiple: (Array<String>) -> Unit, single: (String) -> Unit) {
        multipleRequest = multiple
        singleRequest = single
    }

    fun updatePermissionStates() {
        permissions.forEach { info ->
            val granted = access.isGranted(info.permission)
            val rationale = !granted && access.shouldShowRationale(info.permission)
            if (granted || rationale) requestTracker.recordPermanentDenial(info.permission, false)
            _permissionStates[info.permission] = resolvePermissionStatus(
                granted, rationale, info.permission in requestTracker.permanentlyDenied,
            )
        }
    }

    val allPermissionsGranted: Boolean
        get() = permissions.all { permissionStates[it.permission] == PermissionStatus.Granted }
    val allRequiredPermissionsGranted: Boolean
        get() = permissions.filter { it.required }.all { permissionStates[it.permission] == PermissionStatus.Granted }
    val deniedPermissions: List<PermissionInfo>
        get() = permissions.filter { permissionStates[it.permission] != PermissionStatus.Granted }
    val permanentlyDeniedPermissions: List<PermissionInfo>
        get() = permissions.filter { permissionStates[it.permission] == PermissionStatus.DeniedPermanently }

    private fun needsRationale(info: PermissionInfo): Boolean = when (permissionStates[info.permission]) {
        PermissionStatus.Denied, PermissionStatus.DeniedPermanently -> true
        else -> false
    }

    fun requestPermissions() {
        refreshPermissionStates()
        if (isRequestInFlight) return
        val denied = deniedPermissions
        if (denied.isEmpty()) return
        if (denied.any(::needsRationale)) {
            // Explain/request the complete group once, including new and previously denied entries.
            currentRationalePermissions = denied
            showRationaleDialog = true
        } else launchPermissionRequest(denied)
    }

    fun requestPermission(permission: String) {
        refreshPermissionStates()
        if (isRequestInFlight) return
        val info = permissions.find { it.permission == permission } ?: return
        if (permissionStates[permission] == PermissionStatus.Granted) return
        if (needsRationale(info)) {
            currentRationalePermissions = listOf(info)
            showRationaleDialog = true
        } else launchPermissionRequest(listOf(info))
    }

    fun proceedFromRationale() {
        val explained = currentRationalePermissions
        showRationaleDialog = false
        currentRationalePermissions = emptyList()
        refreshPermissionStates()
        if (isRequestInFlight) return
        val denied = explained.filter { permissionStates[it.permission] != PermissionStatus.Granted }
        if (denied.any { permissionStates[it.permission] == PermissionStatus.DeniedPermanently }) {
            openAppSettings()
        } else launchPermissionRequest(denied)
    }

    fun cancelPermissionRequest() {
        showRationaleDialog = false
        currentRationalePermissions = emptyList()
        requestTracker.clearPending()
    }

    private fun launchPermissionRequest(infos: List<PermissionInfo>) {
        val names = infos.map { it.permission }.distinct()
        if (names.isEmpty() || isRequestInFlight) return
        val single = singleRequest
        val multiple = multipleRequest
        if (names.size == 1 && single == null || names.size > 1 && multiple == null) return
        if (!requestTracker.begin(names)) return
        try {
            if (names.size == 1) single!!.invoke(names.single()) else multiple!!.invoke(names.toTypedArray())
        } catch (failure: Exception) {
            requestTracker.clearPending()
            refreshPermissionStates()
            throw failure
        }
    }

    fun openAppSettings() = access.openAppSettings()

    fun refreshPermissionStates() = updatePermissionStates()

    internal fun handlePermissionResult(results: Map<String, Boolean>) {
        requestTracker.clearPending()
        val known = permissions.map { it.permission }.toSet()
        results.filterKeys { it in known }.forEach { (permission, granted) ->
            val rationale = !granted && access.shouldShowRationale(permission)
            requestTracker.recordPermanentDenial(permission, !granted && !rationale)
            _permissionStates[permission] = resolvePermissionStatus(granted, rationale, !granted && !rationale)
        }
        // Empty results represent cancellation, not a denial of an unrelated permission.
        if (results.isEmpty()) refreshPermissionStates()
    }

    internal fun handleSinglePermissionResult(granted: Boolean) {
        val permission = requestTracker.pending.singleOrNull()
        requestTracker.clearPending()
        if (permission == null) refreshPermissionStates()
        else handlePermissionResult(mapOf(permission to granted))
    }

    fun getPermissionResults(): MultiplePermissionResult = MultiplePermissionResult(
        results = permissions.associate { info ->
            info.permission to PermissionResult(info.permission, permissionStates[info.permission] ?: PermissionStatus.NotRequested)
        },
        allRequiredGranted = allRequiredPermissionsGranted,
    )
}
