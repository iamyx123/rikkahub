package me.rerere.rikkahub.ui.components.ai

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import me.rerere.ui.toast.ToastType
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ChatInputState
import org.koin.compose.koinInject
import java.io.File

/** Explicit URI grants also support OEM camera apps on e-ink devices. */
internal class CameraCaptureContract : ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context, input: Uri): Intent =
        super.createIntent(context, input).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData = ClipData.newRawUri("camera-output", input)
        }
}

internal data class CameraCaptureAction(val launch: () -> Unit, val isBusy: Boolean)

@Composable
internal fun rememberCameraCaptureAction(
    inputState: ChatInputState,
    skipCrop: Boolean,
    onAttachmentAdded: () -> Unit,
): CameraCaptureAction {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val filesManager: FilesManager = koinInject()
    // A system camera can rotate or recreate the caller while it is in the background.
    var outputPath by rememberSaveable { mutableStateOf<String?>(null) }
    var requestingPermission by rememberSaveable { mutableStateOf(false) }

    fun cleanup() {
        outputPath?.let { File(it).delete() }
        outputPath = null
    }

    fun addPhoto(uri: Uri) {
        val images = filesManager.createChatFilesByContents(listOf(uri))
        check(images.isNotEmpty()) { "无法读取照片，请重试" }
        inputState.addImages(images)
        onAttachmentAdded()
    }

    fun reportFailure(error: Exception) {
        val message = when (error) {
            is ActivityNotFoundException -> "未找到可用的相机应用，请安装或启用相机后重试"
            is SecurityException -> "无法访问相机，请检查相机权限后重试"
            else -> "拍照失败：${error.message ?: "请重试"}"
        }
        toaster.show(message, type = ToastType.Error)
    }

    val (_, launchCrop) = useCropLauncher(
        onCroppedImageReady = { uri ->
            try {
                addPhoto(uri)
            } catch (error: Exception) {
                reportFailure(error)
            }
        },
        onCleanup = { cleanup() },
    )
    val cameraLauncher = rememberLauncherForActivityResult(CameraCaptureContract()) { success ->
        if (!success) {
            cleanup()
        } else {
            try {
                val file = outputPath?.let(::File)
                check(file != null && file.length() > 0) { "相机未返回照片，请重试" }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                check(bounds.outWidth > 0 && bounds.outHeight > 0) { "相机返回的照片无法读取，请重试" }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                if (skipCrop) {
                    addPhoto(uri)
                    cleanup()
                } else {
                    launchCrop(uri)
                }
            } catch (error: Exception) {
                cleanup()
                reportFailure(error)
            }
        }
    }

    fun launchCamera() {
        if (outputPath != null) return
        try {
            val file = File.createTempFile("camera_", ".jpg", context.cacheDir)
            outputPath = file.absolutePath
            cameraLauncher.launch(
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            )
        } catch (error: Exception) {
            cleanup()
            reportFailure(error)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        requestingPermission = false
        if (granted) {
            launchCamera()
        } else {
            toaster.show("未授予相机权限，请在系统设置中允许相机权限后重试", type = ToastType.Warning)
        }
    }

    return CameraCaptureAction(
        isBusy = requestingPermission || outputPath != null,
        launch = launch@{
            if (requestingPermission || outputPath != null) return@launch
            // Do not require a rear camera: some readers only have a front camera, and OEM
            // feature declarations can be incomplete. Let the installed camera handle capture.
            if (Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(context.packageManager) == null) {
                reportFailure(ActivityNotFoundException())
            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCamera()
            } else {
                try {
                    requestingPermission = true
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                } catch (error: Exception) {
                    requestingPermission = false
                    reportFailure(error)
                }
            }
        },
    )
}
