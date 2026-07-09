package me.rerere.rikkahub.data.paperang

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.PaperangPrinterConfig
import okhttp3.OkHttpClient
import okhttp3.Request

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

/** 从图片来源打印（下载/解码后按配置黑白或灰度打印）。 */
suspend fun PaperangPrinter.printImageSource(
    context: Context,
    source: String,
    client: OkHttpClient,
    cfg: PaperangPrinterConfig,
): Result<Unit> {
    val bitmap = decodeBitmapFromSource(context, source, client)
        ?: return Result.failure(IllegalStateException("图片解码失败"))
    val r = printBitmap(bitmap, grayscale = cfg.grayscale, density = cfg.density, feedAfter = cfg.feedAfter)
    if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
    return r
}
