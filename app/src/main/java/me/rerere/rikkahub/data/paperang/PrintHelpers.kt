package me.rerere.rikkahub.data.paperang

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.data.datastore.PaperangPrinterConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.math.roundToInt

/** 把图片来源（http URL / file: / content: uri）解码为 Bitmap。 */
suspend fun decodeBitmapFromSource(context: Context, source: String, client: OkHttpClient): Bitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            if (source.startsWith("http")) {
                client.newCall(Request.Builder().url(source).get().build()).execute().use { resp ->
                    val bytes = resp.body?.bytes() ?: return@use null
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            } else {
                context.contentResolver.openInputStream(source.toUri()).use { input ->
                    BitmapFactory.decodeStream(input)
                }
            }
        }.getOrNull()
    }

/**
 * 把图片来源准备成本地 Uri（供 UCrop 裁切使用）。
 * http 图先下载到临时文件；file/content uri 直接返回。
 */
suspend fun ensureLocalImageUri(context: Context, source: String, client: OkHttpClient): Uri? =
    withContext(Dispatchers.IO) {
        runCatching {
            if (source.startsWith("http")) {
                client.newCall(Request.Builder().url(source).get().build()).execute().use { resp ->
                    val bytes = resp.body?.bytes() ?: return@use null
                    val file = File(context.appTempFolder, "print_src_${bytes.size}_${bytes.hashCode()}.png")
                    file.outputStream().use { it.write(bytes) }
                    Uri.fromFile(file)
                }
            } else {
                source.toUri()
            }
        }.getOrNull()
    }

/**
 * 把图片按 scale 比例（占纸张宽度的比例，1.0=填满）居中合成到「纸张宽度」的白底画布上。
 * 打印时打印机按纸宽 1:1 输出：scale=1.0 填满纸张；更小则图更小、走纸更短，省纸。
 */
fun composeImageOnPaper(bitmap: Bitmap, paperWidthPx: Int, scale: Float): Bitmap {
    val s = scale.coerceIn(0.05f, 1.0f)
    val imgW = (paperWidthPx * s).roundToInt().coerceIn(8, paperWidthPx)
    val imgH = (bitmap.height.toFloat() * imgW / bitmap.width).roundToInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(bitmap, imgW, imgH, true)
    val canvasBmp = Bitmap.createBitmap(paperWidthPx, imgH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(canvasBmp)
    canvas.drawColor(Color.WHITE)
    canvas.drawBitmap(scaled, ((paperWidthPx - imgW) / 2).toFloat(), 0f, null)
    if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
    return canvasBmp
}

/** 从图片来源打印（下载/解码后黑白或灰度打印，不走纸）。scale=图片占纸张宽度比例（1.0=填满）。 */
suspend fun PaperangPrinter.printImageSource(
    context: Context,
    source: String,
    client: OkHttpClient,
    cfg: PaperangPrinterConfig,
    scale: Float = 1.0f,
): Result<Unit> {
    val bitmap = decodeBitmapFromSource(context, source, client)
        ?: return Result.failure(IllegalStateException("图片解码失败"))
    return printScaledBitmap(bitmap, cfg, scale).also {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

/** 打印一张已解码的位图，按 scale 合成到纸张宽度后黑白/灰度打印（不走纸）。 */
suspend fun PaperangPrinter.printScaledBitmap(
    bitmap: Bitmap,
    cfg: PaperangPrinterConfig,
    scale: Float = 1.0f,
): Result<Unit> {
    val paperWidth = effectivePaperWidth()
    val canvas = composeImageOnPaper(bitmap, paperWidth, scale)
    val r = printBitmap(canvas, grayscale = cfg.grayscale, density = cfg.density, feedAfter = cfg.feedAfter)
    if (!canvas.isRecycled) canvas.recycle()
    return r
}
