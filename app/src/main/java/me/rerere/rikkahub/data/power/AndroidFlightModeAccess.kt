package me.rerere.rikkahub.data.power

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.study.StudyRootBridge
import java.io.File
import java.io.IOException

class AndroidFlightModeAccess(context: Context) : FlightModeAccess {
    private val context = context.applicationContext

    override suspend fun hasRootAccess(): Boolean = withContext(Dispatchers.IO) {
        StudyRootBridge.isRootAvailableAllowGrant()
    }

    override suspend fun isFlightModeEnabled(): Boolean = withContext(Dispatchers.IO) {
        // No default of zero: a failed/unknown read must not be mistaken for radios being restored.
        when (val value = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON)) {
            0 -> false
            1 -> true
            else -> throw IOException("无法确认系统飞行模式状态：$value")
        }
    }

    override suspend fun setFlightModeEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        // Both commands are fixed; no user text is interpolated into a privileged shell command.
        val command = if (enabled) "cmd connectivity airplane-mode enable" else "cmd connectivity airplane-mode disable"
        if (StudyRootBridge.runSu(command, timeoutMs = 4_000) == null) {
            throw IOException("Root 飞行模式命令失败或未授权。")
        }
    }

    override suspend fun isNetworkReady(): Boolean = withContext(Dispatchers.IO) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return@withContext false
        val network = connectivity.activeNetwork ?: return@withContext false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return@withContext false
        // Do not make an unrelated public HTTP probe or reject a usable private classroom network.
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

class AndroidFlightModeRecoveryStore(context: Context) : FlightModeRecoveryStore {
    // Device-local ownership must never be restored from another device's application backup.
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "reading-power-recovery"))

    override suspend fun isRestorePending(): Boolean = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists()) return@withContext false
        if (file.openRead().bufferedReader().use { it.readText() } != "v1:original-flight-mode=0\n") {
            throw IOException("飞行模式恢复记录无法读取，请检查设备状态。")
        }
        true
    }

    override suspend fun setRestorePending(pending: Boolean) = withContext(Dispatchers.IO) {
        if (!pending) {
            file.delete()
            if (file.baseFile.exists()) throw IOException("无法清除飞行模式恢复记录。")
        } else {
            val output = file.startWrite()
            try {
                output.write("v1:original-flight-mode=0\n".toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw IOException("无法保存飞行模式恢复记录，未继续更改系统状态。", error)
            }
        }
    }
}
