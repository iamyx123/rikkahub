package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil3.compose.rememberAsyncImagePainter
import com.dokar.sonner.ToastType
import com.jvziyaoyao.scale.image.pager.ImagePager
import com.jvziyaoyao.scale.zoomable.pager.rememberZoomablePagerState
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Printer
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.paperang.PaperangPrinter
import me.rerere.rikkahub.data.paperang.printImageSource
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import okhttp3.OkHttpClient
import org.koin.compose.koinInject

@Composable
fun ImagePreviewDialog(
    images: List<String>,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val printer: PaperangPrinter = koinInject()
    val okHttpClient: OkHttpClient = koinInject()
    val settings = LocalSettings.current
    val state = rememberZoomablePagerState { images.size }
    val toaster = LocalToaster.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 检查打印机是否就绪（按蓝牙状态给出正确提示）
    fun ready(): Boolean {
        if (printer.status.value.state == PaperangPrinter.ConnState.CONNECTED) return true
        val msg = if (!printer.isBluetoothOn()) "蓝牙未开启，请先打开蓝牙"
        else "打印机未连接，请长按「喵喵机错题」连接打印机"
        toaster.show(message = msg, type = ToastType.Warning)
        return false
    }

    fun printSource(source: String) {
        lifecycleOwner.lifecycleScope.launch {
            toaster.show("正在打印…")
            printer.printImageSource(context, source, okHttpClient, settings.displaySetting.paperangPrinter)
                .onSuccess { toaster.show(message = "已发送到打印机", type = ToastType.Success) }
                .onFailure { toaster.show(message = "打印失败: ${it.message}", type = ToastType.Error) }
        }
    }

    // 长按打印 -> 打开打印预览窗口（可裁切、调节图片在纸上大小）
    var printPreviewSource by remember { mutableStateOf<String?>(null) }
    printPreviewSource?.let { src ->
        ImagePrintPreviewDialog(source = src, onDismiss = { printPreviewSource = null })
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box {
            ImagePager(
                modifier = Modifier.fillMaxSize(),
                pagerState = state,
                imageLoader = { index ->
                    val painter = rememberAsyncImagePainter(images[index])
                    return@ImagePager Pair(painter, painter.intrinsicSize)
                },
            )

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(1f)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = {
                        lifecycleOwner.lifecycleScope.launch {
                            runCatching {
                                toaster.show("正在保存")
                                val imgUrl = images[state.currentPage]
                                filesManager.saveMessageImage(context, imgUrl)
                                toaster.show(message = "已保存图片", type = ToastType.Success)
                            }.onFailure {
                                it.printStackTrace()
                                toaster.show(
                                    message = it.toString(),
                                    type = ToastType.Error
                                )
                            }
                        }
                    }
                ) {
                    Icon(HugeIcons.Download01, null, tint = Color.White)
                }

                // 打印：短按打印整图，长按裁切后只打印小范围（省纸）
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .combinedClickable(
                            onClick = {
                                if (ready()) printSource(images[state.currentPage])
                            },
                            onLongClick = { printPreviewSource = images[state.currentPage] },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(HugeIcons.Printer, null, tint = Color.White)
                }
            }
        }
    }
}
