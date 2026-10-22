package me.rerere.rikkahub.ui.components.ui.permission

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PermissionStateTest {
    private val camera = PermissionInfo("camera", {}, {}, required = true)
    private val microphone = PermissionInfo("microphone", {}, {}, required = true)

    private class Access : PermissionAccess {
        val granted = mutableSetOf<String>()
        val rationale = mutableSetOf<String>()
        var settingsOpened = 0
        override fun isGranted(permission: String) = permission in granted
        override fun shouldShowRationale(permission: String) = permission in rationale
        override fun openAppSettings() { settingsOpened++ }
    }

    private fun state(
        access: Access,
        requests: MutableList<List<String>>,
        tracker: PermissionRequestTracker = PermissionRequestTracker(),
        infos: Set<PermissionInfo> = linkedSetOf(camera, microphone),
    ) = PermissionState({ infos }, access, tracker).apply {
        setPermissionRequestHandlers(
            multiple = { requests += it.toList() },
            single = { requests += listOf(it) },
        )
    }

    private fun recreate(tracker: PermissionRequestTracker): PermissionRequestTracker {
        val scope = object : SaverScope {
            override fun canBeSaved(value: Any) = true
        }
        val saved = with(PermissionRequestTracker.Saver) { scope.save(tracker) }
        return requireNotNull(PermissionRequestTracker.Saver.restore(requireNotNull(saved)))
    }

    @Test
    fun `single result belongs to its exact requested permission and never the first denied entry`() {
        val access = Access()
        val requests = mutableListOf<List<String>>()
        val state = state(access, requests)
        state.requestPermission("microphone")
        state.handleSinglePermissionResult(true)
        assertEquals(listOf(listOf("microphone")), requests)
        assertEquals(PermissionStatus.Granted, state.permissionStates["microphone"])
        assertEquals(PermissionStatus.NotRequested, state.permissionStates["camera"])
        assertFalse(state.isRequestInFlight)
    }

    @Test
    fun `requests recheck system grants rather than asking again from stale UI state`() {
        val access = Access()
        val requests = mutableListOf<List<String>>()
        val state = state(access, requests)
        access.granted += "microphone"
        state.requestPermissions()
        assertEquals(listOf(listOf("camera")), requests)
        assertEquals(PermissionStatus.Granted, state.permissionStates["microphone"])
    }

    @Test
    fun `already granted single permission never launches a request`() {
        val access = Access()
        val requests = mutableListOf<List<String>>()
        val state = state(access, requests)
        access.granted += "camera"
        state.requestPermission("camera")
        assertTrue(requests.isEmpty())
        assertFalse(state.isRequestInFlight)
    }

    @Test
    fun `repeat taps and lifecycle refreshes cannot open another request while one is pending`() {
        val access = Access()
        val requests = mutableListOf<List<String>>()
        val state = state(access, requests)
        state.requestPermission("microphone")
        state.refreshPermissionStates()
        state.requestPermission("camera")
        state.requestPermissions()
        assertEquals(listOf(listOf("microphone")), requests)
        assertTrue(state.isRequestInFlight)
    }

    @Test
    fun `one time permission revoked in background is requestable rather than permanently denied`() {
        val access = Access().apply { granted += "camera" }
        val requests = mutableListOf<List<String>>()
        val state = state(access, requests)
        assertEquals(PermissionStatus.Granted, state.permissionStates["camera"])
        access.granted -= "camera"
        state.refreshPermissionStates()
        assertEquals(PermissionStatus.NotRequested, state.permissionStates["camera"])
        assertTrue(state.permanentlyDeniedPermissions.isEmpty())
        state.requestPermission("camera")
        assertEquals(listOf(listOf("camera")), requests)
    }

    @Test
    fun `an actual permanent denial still offers app settings rather than a silent request loop`() {
        val access = Access()
        val requests = mutableListOf<List<String>>()
        val state = state(access, requests)
        state.requestPermission("camera")
        state.handleSinglePermissionResult(false)
        assertEquals(PermissionStatus.DeniedPermanently, state.permissionStates["camera"])
        state.requestPermission("camera")
        assertTrue(state.showRationaleDialog)
        state.proceedFromRationale()
        assertEquals(1, access.settingsOpened)
        assertEquals(1, requests.size)
    }

    @Test
    fun `new and previously denied permissions are requested in one explained group`() {
        val access = Access().apply { rationale += "camera" }
        val requests = mutableListOf<List<String>>()
        val state = state(access, requests)
        state.requestPermissions()
        assertTrue(state.showRationaleDialog)
        assertEquals(setOf("camera", "microphone"), state.currentRationalePermissions.map { it.permission }.toSet())
        state.proceedFromRationale()
        assertEquals(listOf(listOf("camera", "microphone")), requests)
    }

    @Test
    fun `granting in settings while a rationale is open removes that permission from the request`() {
        val access = Access().apply { rationale += "camera" }
        val requests = mutableListOf<List<String>>()
        val state = state(access, requests)
        state.requestPermissions()
        access.granted += "camera"
        state.proceedFromRationale()
        assertEquals(listOf(listOf("microphone")), requests)
    }

    @Test
    fun `empty cancelled result clears pending without permanently denying either permission`() {
        val access = Access()
        val requests = mutableListOf<List<String>>()
        val state = state(access, requests)
        state.requestPermissions()
        state.handlePermissionResult(emptyMap())
        assertFalse(state.isRequestInFlight)
        assertTrue(state.permanentlyDeniedPermissions.isEmpty())
        state.requestPermissions()
        assertEquals(2, requests.size)
    }

    @Test
    fun `cancel clears pending and allows a fresh request`() {
        val access = Access()
        val requests = mutableListOf<List<String>>()
        val state = state(access, requests)
        state.requestPermission("camera")
        state.cancelPermissionRequest()
        assertFalse(state.isRequestInFlight)
        state.requestPermission("microphone")
        assertEquals(listOf(listOf("camera"), listOf("microphone")), requests)
    }

    @Test
    fun `launcher exception releases pending before propagating the error`() {
        val access = Access()
        val requests = mutableListOf<List<String>>()
        val state = state(access, requests)
        state.setPermissionRequestHandlers(multiple = {}, single = { error("launcher unavailable") })
        assertThrows(IllegalStateException::class.java) { state.requestPermission("camera") }
        assertFalse(state.isRequestInFlight)
        state.setPermissionRequestHandlers(multiple = {}, single = { requests += listOf(it) })
        state.requestPermission("camera")
        assertEquals(listOf(listOf("camera")), requests)
    }

    @Test
    fun `saved pending target survives recreation and collection order changes`() {
        val access = Access()
        val requests = mutableListOf<List<String>>()
        val tracker = PermissionRequestTracker()
        state(access, requests, tracker).requestPermission("microphone")
        val restored = state(access, requests, recreate(tracker), linkedSetOf(microphone, camera))
        assertTrue(restored.isRequestInFlight)
        restored.handleSinglePermissionResult(true)
        assertEquals(PermissionStatus.Granted, restored.permissionStates["microphone"])
        assertEquals(PermissionStatus.NotRequested, restored.permissionStates["camera"])
    }

    @Test
    fun `missing pending identity only refreshes actual system state without guessing`() {
        val access = Access()
        val requests = mutableListOf<List<String>>()
        val state = state(access, requests)
        access.granted += "microphone"
        state.handleSinglePermissionResult(true)
        assertEquals(PermissionStatus.Granted, state.permissionStates["microphone"])
        assertEquals(PermissionStatus.NotRequested, state.permissionStates["camera"])
    }

    @Test
    fun `restored permanent denial is cleared once the actual system grants permission`() {
        val access = Access()
        val requests = mutableListOf<List<String>>()
        val tracker = PermissionRequestTracker(permanentlyDenied = setOf("camera"))
        val state = state(access, requests, recreate(tracker))
        assertEquals(PermissionStatus.DeniedPermanently, state.permissionStates["camera"])
        access.granted += "camera"
        state.refreshPermissionStates()
        assertEquals(PermissionStatus.Granted, state.permissionStates["camera"])
    }
}
