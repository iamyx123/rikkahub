package me.rerere.rikkahub.ui.components.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.net.toFile
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.paperang.PaperangPrinter
import me.rerere.rikkahub.data.paperang.decodeBitmapFromSource
import me.rerere.rikkahub.data.paperang.ensureLocalImageUri
import me.rerere.rikkahub.data.paperang.printScaledBitmap
import me.rerere.rikkahub.ui.components.ai.useCropLauncher
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import okhttp3.OkHttpClient
import org.koin.compose.koinInject

/**
 * 图片打印预览：长按图片预览页的打印按钮打开。
 * 支持裁切图片、调节图片在纸张上的整体大小（占纸宽比例：填满做宣传 / 缩小省纸），所见即打印。
 */
@Composable
fun ImagePrintPreviewDialog(
    source: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val printer: PaperangPrinter = koinInject()
    val okHttpClient: OkHttpClient = koinInject()
    val settings = LocalSettings.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val status by printer.status.collectAsState()

    var baseBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1.0f) }
    var printing by remember { mutableStateOf(false) }

    LaunchedEffect(source) {
        loading = true
        baseBitmap = decodeBitmapFromSource(context, source, okHttpClient)
        loading = false
        runCatching { printer.refreshPaperWidth() }
    }

    // 裁切：裁切文件回调后会被删除，需同步读入内存
    val (_, launchCrop) = useCropLauncher(
        onCroppedImageReady = { uri ->
            runCatching { BitmapFactory.decodeFile(uri.toFile().path) }.getOrNull()?.let { baseBitmap = it }
        },
    )
    fun startCrop() {
        scope.launch {
            val local = ensureLocalImageUri(context, source, okHttpClient)
            if (local == null) {
                toaster.show(message = "图片加载失败", type = ToastType.Error)
                return@launch
            }
            launchCrop(local)
        }
    }

    fun ready(): Boolean {
        if (status.state == PaperangPrinter.ConnState.CONNECTED) return true
        val msg = if (!printer.isBluetoothOn()) "蓝牙未开启，请先打开蓝牙"
        else "打印机未连接，请长按「喵喵机错题」连接打印机"
        toaster.show(message = msg, type = ToastType.Warning)
        return false
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 4.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("打印预览", style = MaterialTheme.typography.titleMedium)
                val paperInfo = if (status.state == PaperangPrinter.ConnState.CONNECTED) {
                    val size = when (status.paperWidthPx) {
                        576 -> "2寸"; 864 -> "3寸"; else -> "${status.paperWidthPx}px"
                    }
                    "已连接 ${status.deviceName ?: ""} · 纸张 $size"
                } else "打印机未连接"
                Text(
                    paperInfo,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (status.state == PaperangPrinter.ConnState.CONNECTED) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
                Text(
                    "白框代表纸张宽度，拖动调节图片占纸比例：100% 填满纸张，越小图越小、走纸越短越省纸。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 预览：白底“纸张”框，图片按比例居中
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp, max = 320.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .padding(4.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    val bmp = baseBitmap
                    when {
                        loading -> CircularProgressIndicator(modifier = Modifier.padding(24.dp))
                        bmp != null -> Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth(scale.coerceIn(0.05f, 1.0f)),
                        )
                        else -> Text("图片加载失败", modifier = Modifier.padding(24.dp), color = Color.Gray)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("大小", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = scale,
                        onValueChange = { scale = it },
                        valueRange = 0.1f..1.0f,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    Text("${(scale * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { startCrop() }, enabled = baseBitmap != null) { Text("裁切") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(
                        enabled = !printing && baseBitmap != null,
                        onClick = {
                            if (!ready()) return@TextButton
                            val bmp = baseBitmap ?: return@TextButton
                            printing = true
                            val chosenScale = scale
                            scope.launch {
                                toaster.show("正在打印…")
                                printer.printScaledBitmap(bmp, settings.displaySetting.paperangPrinter, chosenScale)
                                    .onSuccess {
                                        toaster.dismissAll()
                                        toaster.show(message = "已发送到打印机", type = ToastType.Success)
                                        onDismiss()
                                    }
                                    .onFailure { toaster.show(message = "打印失败: ${it.message}", type = ToastType.Error) }
                                printing = false
                            }
                        },
                    ) { Text(if (printing) "打印中…" else "打印") }
                }
            }
        }
    }
}
