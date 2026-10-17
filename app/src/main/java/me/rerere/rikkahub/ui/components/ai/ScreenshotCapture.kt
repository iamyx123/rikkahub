package me.rerere.rikkahub.ui.components.ai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.ScreenshotServerConfig
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.screenshot.RemoteScreenshotClient
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.ui.toast.ToastType
import org.koin.compose.koinInject

internal data class ScreenshotCaptureAction(val launch: () -> Unit, val isBusy: Boolean)

/** Hosted outside the attachment sheet so dismissing the sheet does not lose an in-flight capture. */
@Composable
internal fun rememberScreenshotCaptureAction(
    inputState: ChatInputState,
    config: ScreenshotServerConfig,
    onSaveConfig: (ScreenshotServerConfig) -> Unit,
    onConfigure: () -> Unit,
    onAttachmentAdded: () -> Unit,
): ScreenshotCaptureAction {
    val client: RemoteScreenshotClient = koinInject()
    val files: FilesManager = koinInject()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val currentConfig by rememberUpdatedState(config)
    val saveConfig by rememberUpdatedState(onSaveConfig)
    val configure by rememberUpdatedState(onConfigure)
    val attachmentAdded by rememberUpdatedState(onAttachmentAdded)
    var busy by remember { mutableStateOf(false) }

    return ScreenshotCaptureAction(
        isBusy = busy,
        launch = capture@{
            if (busy) return@capture
            busy = true
            scope.launch {
                try {
                    var endpoint = currentConfig
                    if (endpoint.host.isBlank()) {
                        val host = client.discoverServer(endpoint.port)
                        if (host.isNullOrBlank()) {
                            toaster.show("未发现电脑截图服务，请配置服务器地址", ToastType.Warning)
                            configure()
                            return@launch
                        }
                        endpoint = endpoint.copy(host = host)
                        saveConfig(endpoint)
                    }
                    val shots = client.captureAll(endpoint.host, endpoint.port)
                    val uris = withContext(Dispatchers.IO) {
                        files.createChatFilesByByteArrays(shots.map { it.bytes })
                    }
                    check(uris.isNotEmpty()) { "未截取到任何屏幕" }
                    inputState.addImages(uris)
                    attachmentAdded()
                    inputState.requestInputFocus()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    toaster.show("截图失败: ${error.message ?: "未知错误"}", ToastType.Error)
                } finally {
                    busy = false
                }
            }
        },
    )
}
