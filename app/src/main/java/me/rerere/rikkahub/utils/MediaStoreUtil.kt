package me.rerere.rikkahub.utils

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * 查询相册中「最新的一组」照片。
 *
 * 规则：以相册里最新一张照片的添加时间为基准，向前回溯，凡是与它的时间间隔不超过
 * [withinMinutes] 分钟的照片都算作同一组（典型场景：连拍 / 同一时刻拍的几张）。
 *
 * 用 DATE_ADDED（秒级，写入 MediaStore 的时间）而非 DATE_TAKEN，因为后者常为 0 不可靠。
 * 返回按时间正序（旧→新）排列的 content Uri，最多 [limit] 张，避免极端情况下一次塞入过多图片。
 */
fun queryLatestPhotoGroup(
    context: Context,
    withinMinutes: Int,
    limit: Int = 50,
): List<Uri> {
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATE_ADDED,
    )
    val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

    val collected = mutableListOf<Uri>()
    runCatching {
        context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            if (!cursor.moveToFirst()) return emptyList()
            val latest = cursor.getLong(dateCol)
            val threshold = latest - withinMinutes.toLong().coerceAtLeast(0) * 60
            do {
                val date = cursor.getLong(dateCol)
                if (date < threshold) break
                val id = cursor.getLong(idCol)
                collected.add(ContentUris.withAppendedId(collection, id))
                if (collected.size >= limit) break
            } while (cursor.moveToNext())
        }
    }
    // collected 为新→旧，反转为旧→新更符合阅读/发送顺序
    return collected.asReversed().toList()
}
