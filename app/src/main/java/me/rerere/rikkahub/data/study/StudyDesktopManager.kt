package me.rerere.rikkahub.data.study

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.StudyHomeActivity
import me.rerere.rikkahub.service.StudyKeepAliveService
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class StudyDesktopMode {
    DISABLED,
    ENABLING,
    ACTIVE,
    EXITING,
    RECOVERY,
}

class StudyDesktopManager(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val appCatalog = StudyAppCatalog(appContext)
    private val json = Json { ignoreUnknownKeys = true }
    private val systemPolicyBridge = StudyDesktopSystemPolicyBridge(appContext)
    private val rootScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _enabled = MutableStateFlow(false)
    private val _guardActive = MutableStateFlow(false)
    private val _mode = MutableStateFlow(StudyDesktopMode.DISABLED)
    private val _rootAvailable = MutableStateFlow(false)
    private val _pinnedApps = MutableStateFlow(loadPinnedApps())
    private val _blockedAppCount = MutableStateFlow(
        preferences.getLong(KEY_BLOCKED_APP_COUNT, 0L),
    )
    private val _examTargetDate = MutableStateFlow(
        LocalDate.ofEpochDay(
            preferences.getLong(
                KEY_EXAM_TARGET_EPOCH_DAY,
                StudyExamCountdown.DEFAULT_TARGET_DATE.toEpochDay(),
            ),
        ),
    )

    val enabled = _enabled.asStateFlow()
    val guardActive = _guardActive.asStateFlow()
    val mode = _mode.asStateFlow()
    val rootAvailable = _rootAvailable.asStateFlow()
    val pinnedApps = _pinnedApps.asStateFlow()
    val blockedAppCount = _blockedAppCount.asStateFlow()
    val examTargetDate = _examTargetDate.asStateFlow()
    val packageName: String get() = appContext.packageName

    init {
        migrateLegacyStateIfNeeded()
        refreshPersistedStateFlows()
        // Warm the root-probe cache off the main thread so UI and the state machine never probe
        // synchronously during composition / onCreate.
        rootScope.launch { _rootAvailable.value = StudyRootBridge.isRootAvailable() }
    }

    fun isEnabled(): Boolean =
        storedMode() == StudyDesktopMode.ACTIVE && isDefaultHome()

    fun isGuardActive(): Boolean = isDefaultHome()

    fun currentMode(): StudyDesktopMode = _mode.value

    suspend fun queryLaunchableApps(): List<StudyInstalledApp> = appCatalog.queryLaunchableApps()

    suspend fun resolvePinnedApps(pins: List<StudyPinnedApp> = _pinnedApps.value): List<StudyResolvedApp> =
        appCatalog.resolve(pins)

    fun replacePinnedApps(apps: List<StudyPinnedApp>) {
        val normalized = apps
            .distinctBy { it.packageName }
        preferences.edit().putString(KEY_PINNED_APPS, json.encodeToString(normalized)).apply()
        _pinnedApps.value = normalized
    }

    fun launchPinnedApp(pin: StudyPinnedApp): Boolean {
        if (_pinnedApps.value.none { it.packageName == pin.packageName }) return false
        return appCatalog.launch(pin)
    }

    fun recordBlockedApp() {
        synchronized(preferences) {
            val current = preferences.getLong(KEY_BLOCKED_APP_COUNT, 0L)
            val next = if (current == Long.MAX_VALUE) current else current + 1L
            if (preferences.edit().putLong(KEY_BLOCKED_APP_COUNT, next).commit()) {
                _blockedAppCount.value = next
            }
        }
    }

    fun setExamTargetDate(targetDate: LocalDate) {
        preferences.edit().putLong(KEY_EXAM_TARGET_EPOCH_DAY, targetDate.toEpochDay()).apply()
        _examTargetDate.value = targetDate
    }

    fun prepareHomeComponent() = setHomeComponentEnabled(true)

    fun releaseHomeComponentIfInactive() {
        if (storedMode() == StudyDesktopMode.DISABLED && !isDefaultHome()) {
            setHomeComponentEnabled(false)
            refreshStateFlows()
        }
    }

    /**
     * Reconciles the persisted transaction with the HOME role. This is deliberately called from
     * both the setup page and the dedicated HOME activity: many Android 11 launchers start the new
     * HOME before returning the role request result to the calling activity.
     */
    fun reconcileState(): StudyDesktopMode {
        repairInvalidCredentialState()
        when (storedMode()) {
            StudyDesktopMode.ACTIVE -> {
                if (isDefaultHome()) prepareHomeComponent() else deactivateAfterHomeLoss()
            }
            StudyDesktopMode.ENABLING -> {
                prepareHomeComponent()
                if (isDefaultHome()) completePendingEnable()
            }
            StudyDesktopMode.EXITING -> {
                prepareHomeComponent()
                finalizeExitIfHomeReleased()
            }
            StudyDesktopMode.DISABLED -> {
                appContext.stopService(Intent(appContext, StudyKeepAliveService::class.java))
                if (isDefaultHome()) prepareHomeComponent() else setHomeComponentEnabled(false)
            }
            StudyDesktopMode.RECOVERY -> {
                if (isDefaultHome()) {
                    prepareHomeComponent()
                } else {
                    val committed = preferences.edit()
                        .putString(KEY_MODE_STATE, StudyDesktopMode.DISABLED.name)
                        .putBoolean(KEY_ENABLED, false)
                        .remove(KEY_PASSWORD_CREDENTIAL)
                        .remove(KEY_PENDING_PASSWORD_CREDENTIAL)
                        .remove(KEY_ROOT_GUARD)
                        .commit()
                    if (committed) setHomeComponentEnabled(false)
                }
            }
        }
        refreshStateFlows()
        return _mode.value
    }

    fun isDefaultHome(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = appContext.getSystemService(RoleManager::class.java)
            if (roleManager?.isRoleAvailable(RoleManager.ROLE_HOME) == true) {
                return roleManager.isRoleHeld(RoleManager.ROLE_HOME)
            }
        }
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = appContext.packageManager.resolveActivity(
            homeIntent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        return resolved?.activityInfo?.packageName == appContext.packageName
    }

    fun defaultHomeRequestIntent(): Intent {
        prepareHomeComponent()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = appContext.getSystemService(RoleManager::class.java)
            if (roleManager?.isRoleAvailable(RoleManager.ROLE_HOME) == true) {
                return roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
            }
        }
        return homeSettingsIntent()
    }

    fun homeSettingsIntent(): Intent = Intent(Settings.ACTION_HOME_SETTINGS)

    fun hasPendingEnable(): Boolean = storedMode() == StudyDesktopMode.ENABLING &&
        preferences.contains(KEY_PENDING_PASSWORD_CREDENTIAL)

    fun beginEnable(password: String) {
        require(password.length >= MIN_PASSWORD_LENGTH) { "密码至少需要 $MIN_PASSWORD_LENGTH 位" }
        rememberPassword(password)
        val committed = preferences.edit()
            .putString(KEY_MODE_STATE, StudyDesktopMode.ENABLING.name)
            .putBoolean(KEY_ENABLED, false)
            .putString(KEY_PENDING_PASSWORD_CREDENTIAL, StudyPasswordHasher.create(password))
            .remove(KEY_PASSWORD_CREDENTIAL)
            .commit()
        check(committed) { "无法持久化学习桌面启用事务" }
        prepareHomeComponent()
        refreshStateFlows()
    }

    fun completePendingEnable(): Boolean {
        val credential = preferences.getString(KEY_PENDING_PASSWORD_CREDENTIAL, null) ?: return false
        if (!isDefaultHome()) return false
        // With a confirmed root guard (recorded by rootSilentEnable) the battery + accessibility
        // requirements are skipped; otherwise they remain hard requirements of the manual flow.
        if (!rootGuardActive() &&
            (!isAccessibilityServiceEnabled() || !isIgnoringBatteryOptimizations())
        ) {
            return false
        }
        prepareHomeComponent()
        val committed = preferences.edit()
            .putString(KEY_MODE_STATE, StudyDesktopMode.ACTIVE.name)
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_PASSWORD_CREDENTIAL, credential)
            .remove(KEY_PENDING_PASSWORD_CREDENTIAL)
            .commit()
        if (!committed) return false
        refreshStateFlows()
        startKeepAliveIfPossible()
        return true
    }

    fun cancelPendingEnable() {
        if (storedMode() != StudyDesktopMode.ENABLING) return
        preferences.edit()
            .putString(KEY_MODE_STATE, StudyDesktopMode.DISABLED.name)
            .putBoolean(KEY_ENABLED, false)
            .remove(KEY_PENDING_PASSWORD_CREDENTIAL)
            .commit()
        releaseHomeComponentIfInactive()
        refreshStateFlows()
    }

    fun enable(password: String) {
        beginEnable(password)
        if (!completePendingEnable()) {
            cancelPendingEnable()
            error("学习桌面所需权限或默认桌面尚未完成")
        }
    }

    fun verifyPassword(password: String): Boolean {
        val credential = preferences.getString(KEY_PASSWORD_CREDENTIAL, null)
            ?: preferences.getString(KEY_PENDING_PASSWORD_CREDENTIAL, null)
            ?: return false
        return StudyPasswordHasher.verify(password, credential)
    }

    /** Starts a two-phase exit. The credential is retained until another HOME is confirmed. */
    fun disable(): Boolean {
        val wasDefaultHome = isDefaultHome()
        val credential = preferences.getString(KEY_PASSWORD_CREDENTIAL, null)
            ?: preferences.getString(KEY_PENDING_PASSWORD_CREDENTIAL, null)
        val committed = preferences.edit()
            .putString(KEY_MODE_STATE, StudyDesktopMode.EXITING.name)
            .putBoolean(KEY_ENABLED, false)
            .apply {
                if (credential != null) putString(KEY_PASSWORD_CREDENTIAL, credential)
            }
            .remove(KEY_PENDING_PASSWORD_CREDENTIAL)
            .remove(KEY_ROOT_GUARD)
            .commit()
        check(committed) { "无法持久化学习桌面退出事务" }
        appContext.stopService(Intent(appContext, StudyKeepAliveService::class.java))
        refreshStateFlows()
        if (!wasDefaultHome) finalizeExitIfHomeReleased()
        return wasDefaultHome
    }

    fun cancelExit(): Boolean {
        if (storedMode() != StudyDesktopMode.EXITING || !isDefaultHome()) return false
        val credential = preferences.getString(KEY_PASSWORD_CREDENTIAL, null) ?: return false
        val committed = preferences.edit()
            .putString(KEY_MODE_STATE, StudyDesktopMode.ACTIVE.name)
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_PASSWORD_CREDENTIAL, credential)
            .commit()
        if (!committed) return false
        refreshStateFlows()
        startKeepAliveIfPossible()
        return true
    }

    fun finalizeExitIfHomeReleased(): Boolean {
        if (storedMode() != StudyDesktopMode.EXITING || isDefaultHome()) return false
        val committed = preferences.edit()
            .putString(KEY_MODE_STATE, StudyDesktopMode.DISABLED.name)
            .putBoolean(KEY_ENABLED, false)
            .remove(KEY_PASSWORD_CREDENTIAL)
            .remove(KEY_PENDING_PASSWORD_CREDENTIAL)
            .remove(KEY_ORIGINAL_HOME)
            .remove(KEY_ROOT_GUARD)
            .commit()
        if (!committed) return false
        setHomeComponentEnabled(false)
        refreshStateFlows()
        return true
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = appContext.getSystemService(PowerManager::class.java)
        return powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true
    }

    fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(
            appContext,
            StudyDesktopAccessibilityService::class.java,
        )
        val enabledServices = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabledServices
            .split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it == expected }
    }

    // --- Password memory ---

    /** Remembers the exit password (device-scoped obfuscation) so root can re-enter silently. */
    fun rememberPassword(password: String) {
        if (password.isBlank()) return
        preferences.edit()
            .putString(KEY_REMEMBERED_PASSWORD, StudyPasswordMemory.encode(password, appContext))
            .apply()
    }

    /** Returns the last remembered exit password, or null when not set / too short. */
    fun rememberedPassword(): String? {
        val raw = preferences.getString(KEY_REMEMBERED_PASSWORD, null) ?: return null
        return StudyPasswordMemory.decode(raw, appContext)
            ?.takeIf { it.length >= MIN_PASSWORD_LENGTH }
    }

    // --- Root-priority guard ---

    /** Whether the root guard was last confirmed working (recorded by rootSilentEnable). */
    fun rootGuardActive(): Boolean = preferences.getBoolean(KEY_ROOT_GUARD, false)

    /**
     * Whether an exit should try the root path first. True when the session was started by the
     * silent root flow, or when root simply looks available. Cheap: the pref check short-circuits
     * before any probe.
     */
    fun prefersRootGuard(): Boolean = rootGuardActive() || StudyRootBridge.isRootAvailable()

    /** Re-probes root availability off the main thread and publishes it to [rootAvailable]. */
    fun refreshRootAvailability() {
        rootScope.launch { _rootAvailable.value = StudyRootBridge.isRootAvailable() }
    }

    /**
     * Silently enables the study desktop through root: whitelists battery, takes the default HOME
     * role, then activates the ACTIVE transaction. Uses the last remembered password when [password]
     * is null. Returns false on any failure so callers can fall back to the manual flow.
     */
    suspend fun rootSilentEnable(password: String? = null): Boolean = withContext(Dispatchers.IO) {
        // The long probe gives Magisk's first-time authorization dialog time to be answered.
        if (!StudyRootBridge.isRootAvailableAllowGrant()) return@withContext false
        val effectivePassword = password?.takeIf { it.length >= MIN_PASSWORD_LENGTH }
            ?: rememberedPassword()
            ?: return@withContext false

        if (storedMode() == StudyDesktopMode.ACTIVE && isDefaultHome()) {
            // Already running: make sure the guard is actually up before reporting success.
            preferences.edit().putBoolean(KEY_ROOT_GUARD, true).commit()
            startKeepAliveIfPossible()
            openStudyDesktop()
            return@withContext true
        }

        // Capture the current HOME holder before taking over, so a silent exit can restore it.
        if (preferences.getString(KEY_ORIGINAL_HOME, null).isNullOrBlank()) {
            val holder = StudyRootBridge.currentRoleHolder()?.takeIf { it != appContext.packageName }
                ?: otherLauncherPackages().firstOrNull()
            holder?.let { preferences.edit().putString(KEY_ORIGINAL_HOME, it).commit() }
        }

        // Battery whitelist via root so no system prompt is needed.
        StudyRootBridge.whitelistBattery(appContext.packageName)

        // The HOME component must be enabled before the role manager will accept this package.
        prepareHomeComponent()

        // Record the root guard *before* completePendingEnable, which consults it to waive the
        // accessibility + battery requirements of the manual flow. Committed synchronously so the
        // keep-alive service (a separate manager instance) observes it too.
        preferences.edit().putBoolean(KEY_ROOT_GUARD, true).commit()

        // Take the default HOME role via root and verify through the platform API.
        val homeSet = StudyRootBridge.setDefaultHome(appContext.packageName)
        if (!homeSet || !isDefaultHome()) {
            Log.w(TAG, "Root enable could not take the HOME role")
            preferences.edit().remove(KEY_ROOT_GUARD).commit()
            releaseHomeComponentIfInactive()
            return@withContext false
        }

        if (storedMode() != StudyDesktopMode.ACTIVE) beginEnable(effectivePassword)
        val committed = completePendingEnable()
        if (committed) {
            startKeepAliveIfPossible()
        } else {
            // Roll back the HOME takeover; leaving the role held without an active session would
            // strand the user on a desktop that has no exit password bound to it.
            Log.w(TAG, "Root enable took HOME but could not commit the session; rolling back")
            preferences.edit().remove(KEY_ROOT_GUARD).commit()
            cancelPendingEnable()
            val original = preferences.getString(KEY_ORIGINAL_HOME, null)
                ?.takeIf { it.isNotBlank() && it != appContext.packageName }
            if (original != null) StudyRootBridge.setDefaultHome(original)
            else StudyRootBridge.clearDefaultHome(appContext.packageName)
            refreshStateFlows()
        }
        committed
    }

    /**
     * Silently exits the study desktop through root: restores the previously captured HOME holder,
     * then closes the ACTIVE transaction. The exit password must already have been verified by the
     * caller — this only restores system state.
     */
    suspend fun rootSilentDisable(): Boolean = withContext(Dispatchers.IO) {
        // Exiting is the one operation that must not fail spuriously: allow the grant dialog here
        // too, in case the cached probe went stale or su was re-authorized since enabling.
        if (!StudyRootBridge.isRootAvailableAllowGrant()) return@withContext false
        if (storedMode() != StudyDesktopMode.ACTIVE) return@withContext false

        val original = preferences.getString(KEY_ORIGINAL_HOME, null)
            ?.takeIf { it.isNotBlank() && it != appContext.packageName }
            // Nothing captured (study mode was enabled before this ran, or the original holder was
            // us): fall back to any other installed launcher so the device is not left without HOME.
            ?: otherLauncherPackages().firstOrNull()

        val restored = if (original != null) {
            StudyRootBridge.setDefaultHome(original) || StudyRootBridge.clearDefaultHome(appContext.packageName)
        } else {
            StudyRootBridge.clearDefaultHome(appContext.packageName)
        }
        // The role must actually have left this package — otherwise the user would be locked in with
        // the transaction already torn down. `isDefaultHome()` is the authoritative platform check.
        if (!restored || isDefaultHome()) {
            Log.w(TAG, "Root exit could not hand the HOME role back (restored=$restored)")
            return@withContext false
        }

        appContext.stopService(Intent(appContext, StudyKeepAliveService::class.java))
        val committed = preferences.edit()
            .putString(KEY_MODE_STATE, StudyDesktopMode.DISABLED.name)
            .putBoolean(KEY_ENABLED, false)
            .remove(KEY_PASSWORD_CREDENTIAL)
            .remove(KEY_PENDING_PASSWORD_CREDENTIAL)
            .remove(KEY_ORIGINAL_HOME)
            .remove(KEY_ROOT_GUARD)
            .commit()
        if (!committed) return@withContext false
        setHomeComponentEnabled(false)
        refreshStateFlows()
        true
    }

    /** Enabled input method packages, so the root guard never yanks the IME window. */
    fun enabledInputMethodPackages(): Set<String> {
        val manager = appContext.getSystemService(InputMethodManager::class.java) ?: return emptySet()
        return runCatching {
            manager.enabledInputMethodList.mapTo(mutableSetOf()) { it.packageName }
        }.getOrDefault(emptySet())
    }

    /** Whether [packageName] is one of the apps the user allowed to open from the study desktop. */
    fun isPinned(packageName: String): Boolean =
        _pinnedApps.value.any { it.packageName == packageName }

    /**
     * Other launcher packages on the device. The root guard must not fight these: a launcher on top
     * means the HOME role changed, which [reconcileForegroundGuard] handles by closing the session.
     */
    fun otherLauncherPackages(): Set<String> {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return runCatching {
            appContext.packageManager.queryIntentActivities(homeIntent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .filterTo(mutableSetOf()) { it != appContext.packageName && it != "android" }
        }.getOrDefault(emptySet())
    }

    /**
     * System apps that a permitted study app legitimately brings to the foreground (document
     * pickers, permission dialogs, installers). Resolved from intents rather than hardcoded so OEM
     * package names are covered too.
     */
    fun essentialSystemPackages(): Set<String> {
        val intents = listOf(
            Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE),
            Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*"),
            Intent(Intent.ACTION_CREATE_DOCUMENT).setType("*/*"),
            Intent(Intent.ACTION_GET_CONTENT).setType("*/*"),
            Intent(Intent.ACTION_CHOOSER),
            Intent("android.content.pm.action.REQUEST_PERMISSIONS"),
            Intent("android.settings.MANAGE_APP_PERMISSIONS"),
        )
        val packageManager = appContext.packageManager
        val result = mutableSetOf<String>()
        intents.forEach { intent ->
            runCatching {
                @Suppress("DEPRECATION")
                val candidates = packageManager.queryIntentActivities(intent, 0) +
                    listOfNotNull(packageManager.resolveActivity(intent, 0))
                candidates.forEach { resolved ->
                    val info = resolved.activityInfo ?: return@forEach
                    val flags = info.applicationInfo?.flags ?: 0
                    val isSystem = flags and
                        (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    if (isSystem) result += info.packageName
                }
            }
        }
        return result
    }

    fun startKeepAliveIfPossible() {
        if (!reconcileForegroundGuard()) return
        val intent = Intent(appContext, StudyKeepAliveService::class.java).apply {
            action = StudyKeepAliveService.ACTION_START
        }
        runCatching { ContextCompat.startForegroundService(appContext, intent) }
    }

    fun shouldGuardForeground(): Boolean =
        storedMode() == StudyDesktopMode.ACTIVE && isDefaultHome()

    internal fun publishSystemPolicy(
        reason: StudyDesktopSystemPolicyReason,
        force: Boolean = false,
    ) {
        systemPolicyBridge.publish(
            mode = storedMode(),
            isDefaultHome = isDefaultHome(),
            reason = reason,
            force = force,
        )
    }

    /**
     * Returns whether the foreground guard may run and closes a stale ACTIVE transaction after
     * another launcher has become HOME. Call this only from the dedicated study components.
     */
    fun reconcileForegroundGuard(): Boolean {
        if (storedMode() != StudyDesktopMode.ACTIVE) {
            publishSystemPolicy(StudyDesktopSystemPolicyReason.FOREGROUND_HEARTBEAT)
            return false
        }
        if (isDefaultHome()) {
            publishSystemPolicy(StudyDesktopSystemPolicyReason.FOREGROUND_HEARTBEAT)
            return true
        }
        deactivateAfterHomeLoss()
        refreshStateFlows()
        return false
    }

    fun openStudyDesktop(): Boolean {
        prepareHomeComponent()
        val intent = Intent(appContext, StudyHomeActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_HOME)
            putExtra(EXTRA_OPEN_STUDY_DESKTOP, true)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        return runCatching {
            appContext.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun openSystemHome(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return runCatching {
            appContext.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun accessibilitySettingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun batteryOptimizationIntent(): Intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${appContext.packageName}"),
    )

    private fun migrateLegacyStateIfNeeded() {
        if (preferences.contains(KEY_MODE_STATE)) return
        val initial = when {
            preferences.getBoolean(KEY_ENABLED, false) -> StudyDesktopMode.ACTIVE
            preferences.contains(KEY_PENDING_PASSWORD_CREDENTIAL) -> StudyDesktopMode.ENABLING
            else -> StudyDesktopMode.DISABLED
        }
        preferences.edit().putString(KEY_MODE_STATE, initial.name).commit()
    }

    private fun repairInvalidCredentialState() {
        val stored = storedMode()
        val valid = when (stored) {
            StudyDesktopMode.ACTIVE,
            StudyDesktopMode.EXITING -> preferences.contains(KEY_PASSWORD_CREDENTIAL)
            StudyDesktopMode.ENABLING -> preferences.contains(KEY_PENDING_PASSWORD_CREDENTIAL)
            StudyDesktopMode.DISABLED,
            StudyDesktopMode.RECOVERY -> true
        }
        if (valid) return
        val repaired = if (isDefaultHome()) StudyDesktopMode.RECOVERY else StudyDesktopMode.DISABLED
        preferences.edit()
            .putString(KEY_MODE_STATE, repaired.name)
            .putBoolean(KEY_ENABLED, false)
            .remove(KEY_PASSWORD_CREDENTIAL)
            .remove(KEY_PENDING_PASSWORD_CREDENTIAL)
            .commit()
    }

    /** A HOME guard cannot remain active after the user has selected another launcher. */
    private fun deactivateAfterHomeLoss(): Boolean {
        appContext.stopService(Intent(appContext, StudyKeepAliveService::class.java))
        val committed = preferences.edit()
            .putString(KEY_MODE_STATE, StudyDesktopMode.DISABLED.name)
            .putBoolean(KEY_ENABLED, false)
            .remove(KEY_PASSWORD_CREDENTIAL)
            .remove(KEY_PENDING_PASSWORD_CREDENTIAL)
            .remove(KEY_ORIGINAL_HOME)
            .remove(KEY_ROOT_GUARD)
            .commit()
        if (committed) setHomeComponentEnabled(false)
        return committed
    }

    private fun storedMode(): StudyDesktopMode {
        val raw = preferences.getString(KEY_MODE_STATE, null)
        return runCatching { StudyDesktopMode.valueOf(raw.orEmpty()) }
            .getOrDefault(StudyDesktopMode.DISABLED)
    }

    private fun refreshStateFlows() {
        val stored = storedMode()
        val defaultHome = isDefaultHome()
        val validCredential = when (stored) {
            StudyDesktopMode.ACTIVE,
            StudyDesktopMode.EXITING -> preferences.contains(KEY_PASSWORD_CREDENTIAL)
            StudyDesktopMode.ENABLING -> preferences.contains(KEY_PENDING_PASSWORD_CREDENTIAL)
            StudyDesktopMode.DISABLED,
            StudyDesktopMode.RECOVERY -> true
        }
        val effective = when {
            !validCredential && defaultHome -> StudyDesktopMode.RECOVERY
            !validCredential -> StudyDesktopMode.DISABLED
            stored == StudyDesktopMode.ACTIVE && !defaultHome -> StudyDesktopMode.DISABLED
            stored == StudyDesktopMode.DISABLED && defaultHome -> StudyDesktopMode.RECOVERY
            else -> stored
        }
        _enabled.value = effective == StudyDesktopMode.ACTIVE && defaultHome
        _guardActive.value = defaultHome
        _mode.value = effective
        systemPolicyBridge.publish(
            mode = stored,
            isDefaultHome = defaultHome,
            reason = StudyDesktopSystemPolicyReason.STATE_RECONCILE,
        )
    }

    /**
     * Initializes observable state without touching HOME roles or component enabled state.
     * Ordinary RikkaHub screens may inject this singleton for setup affordances; construction must
     * therefore remain a passive preferences read. Reconciliation belongs to the dedicated HOME,
     * setup flow, boot receiver, and explicit enable/disable transactions only.
     */
    private fun refreshPersistedStateFlows() {
        val stored = storedMode()
        _enabled.value = stored == StudyDesktopMode.ACTIVE
        _guardActive.value = false
        _mode.value = stored
    }

    private fun setHomeComponentEnabled(enabled: Boolean) {
        val component = ComponentName(appContext, StudyHomeActivity::class.java)
        val newState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
        if (appContext.packageManager.getComponentEnabledSetting(component) == newState) return
        appContext.packageManager.setComponentEnabledSetting(
            component,
            newState,
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun loadPinnedApps(): List<StudyPinnedApp> {
        val raw = preferences.getString(KEY_PINNED_APPS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<StudyPinnedApp>>(raw) }
            .getOrDefault(emptyList())
            .filter { it.packageName.isNotBlank() }
            .distinctBy { it.packageName }
    }

    companion object {
        const val MIN_PASSWORD_LENGTH = 4
        const val EXTRA_OPEN_STUDY_DESKTOP = "open_study_desktop"

        private const val TAG = "StudyDesktopManager"
        private const val PREFERENCES_NAME = "study_desktop"
        private const val KEY_MODE_STATE = "mode_state"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PASSWORD_CREDENTIAL = "password_credential"
        private const val KEY_PENDING_PASSWORD_CREDENTIAL = "pending_password_credential"
        private const val KEY_REMEMBERED_PASSWORD = "remembered_password"
        private const val KEY_ORIGINAL_HOME = "original_home"
        private const val KEY_ROOT_GUARD = "root_guard"
        private const val KEY_PINNED_APPS = "pinned_apps"
        private const val KEY_BLOCKED_APP_COUNT = "blocked_app_count"
        private const val KEY_EXAM_TARGET_EPOCH_DAY = "exam_target_epoch_day"
    }
}

internal object StudyPasswordHasher {
    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16

    fun create(password: String): String {
        val salt = ByteArray(SALT_LENGTH_BYTES).also(SecureRandom()::nextBytes)
        val hash = derive(password, salt)
        return "${encode(salt)}:${encode(hash)}"
    }

    fun verify(password: String, credential: String): Boolean {
        val parts = credential.split(':', limit = 2)
        if (parts.size != 2) return false
        return runCatching {
            val salt = decode(parts[0])
            val expected = decode(parts[1])
            MessageDigest.isEqual(expected, derive(password, salt))
        }.getOrDefault(false)
    }

    private fun derive(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)
}

/**
 * Device-scoped obfuscation for the remembered exit password. This is not a security boundary — it
 * only keeps the plaintext out of the prefs file and the first few hex bytes — so a silent root
 * re-entry can reuse the last password without another prompt. The salted hash remains the sole
 * verification authority.
 */
internal object StudyPasswordMemory {
    private const val SALT_PREF_KEY = "password_memory_salt"

    fun encode(password: String, context: Context): String {
        val key = deriveKey(loadSalt(context))
        val bytes = password.toByteArray(Charsets.UTF_8)
        val xored = ByteArray(bytes.size) {
            (bytes[it].toInt() xor key[it % key.size].toInt()).toByte()
        }
        return Base64.getEncoder().withoutPadding().encodeToString(xored)
    }

    fun decode(raw: String, context: Context): String? = runCatching {
        val key = deriveKey(loadSalt(context))
        val bytes = Base64.getDecoder().decode(raw)
        val xored = ByteArray(bytes.size) {
            (bytes[it].toInt() xor key[it % key.size].toInt()).toByte()
        }
        String(xored, Charsets.UTF_8)
    }.getOrNull()

    private fun loadSalt(context: Context): ByteArray {
        val prefs = context.getSharedPreferences("study_desktop", Context.MODE_PRIVATE)
        val stored = prefs.getString(SALT_PREF_KEY, null)
        if (stored != null) return runCatching { Base64.getDecoder().decode(stored) }
            .getOrDefault(ByteArray(0))
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val encoded = Base64.getEncoder().withoutPadding().encodeToString(salt)
        if (prefs.edit().putString(SALT_PREF_KEY, encoded).commit()) return salt
        return salt
    }

    private fun deriveKey(salt: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("rikkahub.study.desktop.password.memory".toByteArray(Charsets.UTF_8))
        digest.update(salt)
        return digest.digest()
    }
}
