package me.rerere.rikkahub.data.study

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val _enabled = MutableStateFlow(false)
    private val _guardActive = MutableStateFlow(false)
    private val _mode = MutableStateFlow(StudyDesktopMode.DISABLED)
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
    val pinnedApps = _pinnedApps.asStateFlow()
    val blockedAppCount = _blockedAppCount.asStateFlow()
    val examTargetDate = _examTargetDate.asStateFlow()

    init {
        migrateLegacyStateIfNeeded()
        refreshPersistedStateFlows()
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
        if (!isDefaultHome() ||
            !isAccessibilityServiceEnabled() || !isIgnoringBatteryOptimizations()
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

        private const val PREFERENCES_NAME = "study_desktop"
        private const val KEY_MODE_STATE = "mode_state"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PASSWORD_CREDENTIAL = "password_credential"
        private const val KEY_PENDING_PASSWORD_CREDENTIAL = "pending_password_credential"
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
