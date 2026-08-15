package me.rerere.rikkahub.data.study

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.text.Collator
import java.util.Locale

@Serializable
data class StudyPinnedApp(
    val packageName: String,
    val preferredComponent: String? = null,
    val labelSnapshot: String,
)

data class StudyInstalledApp(
    val packageName: String,
    val component: ComponentName,
    val label: String,
) {
    fun toPinnedApp(): StudyPinnedApp = StudyPinnedApp(
        packageName = packageName,
        preferredComponent = component.flattenToString(),
        labelSnapshot = label,
    )
}

data class StudyResolvedApp(
    val pin: StudyPinnedApp,
    val label: String,
    val component: ComponentName?,
) {
    val installed: Boolean get() = component != null
}

class StudyAppCatalog(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    suspend fun queryLaunchableApps(): List<StudyInstalledApp> = withContext(Dispatchers.IO) {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val chineseCollator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)
        queryIntentActivities(launcherIntent)
            .asSequence()
            .filter { it.activityInfo.packageName != appContext.packageName }
            .map { info ->
                StudyInstalledApp(
                    packageName = info.activityInfo.packageName,
                    component = ComponentName(info.activityInfo.packageName, info.activityInfo.name),
                    label = info.loadLabel(packageManager).toString()
                        .ifBlank { info.activityInfo.packageName },
                )
            }
            .distinctBy { it.packageName }
            .sortedWith { left, right -> chineseCollator.compare(left.label, right.label) }
            .toList()
    }

    suspend fun resolve(pins: List<StudyPinnedApp>): List<StudyResolvedApp> = withContext(Dispatchers.IO) {
        pins.map { pin ->
            runCatching { resolveBlocking(pin) }.getOrElse {
                StudyResolvedApp(pin = pin, label = pin.labelSnapshot, component = null)
            }
        }
    }

    fun launch(pin: StudyPinnedApp): Boolean {
        val component = runCatching { resolveBlocking(pin) }.getOrNull()?.component ?: return false
        val intent = Intent.makeMainActivity(component).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        return runCatching {
            appContext.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun resolveBlocking(pin: StudyPinnedApp): StudyResolvedApp {
        val preferred = pin.preferredComponent
            ?.let(ComponentName::unflattenFromString)
            ?.takeIf(::isActivityAvailable)
        val fallback = preferred ?: findLauncherComponent(pin.packageName)
        val currentLabel = fallback?.let { component ->
            runCatching {
                packageManager.getActivityInfo(component, 0).loadLabel(packageManager).toString()
            }.getOrNull()
        }
        return StudyResolvedApp(
            pin = pin,
            label = currentLabel?.ifBlank { null } ?: pin.labelSnapshot.ifBlank { pin.packageName },
            component = fallback,
        )
    }

    private fun findLauncherComponent(packageName: String): ComponentName? {
        val launcherIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)
        val info = queryIntentActivities(launcherIntent).firstOrNull()?.activityInfo
        if (info != null) return ComponentName(info.packageName, info.name)
        return packageManager.getLaunchIntentForPackage(packageName)?.component
    }

    private fun isActivityAvailable(component: ComponentName): Boolean = runCatching {
        packageManager.getActivityInfo(component, 0)
        true
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun queryIntentActivities(intent: Intent) =
        packageManager.queryIntentActivities(intent, 0)
}
