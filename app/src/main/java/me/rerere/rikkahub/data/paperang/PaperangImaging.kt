package me.rerere.rikkahub.data.paperang

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 图片 -> 打印机位图编码（复刻 paperang_n2.py 的 image_to_1bpp / image_to_gray4）。
 * 黑白适合文字/线条，灰度适合照片。
 */
object PaperangImaging {

    /** 官方 rank16 灰阶锚点，索引 0=白(255) … 15=黑(0)。 */
    private val GRAY16_LEVELS = intArrayOf(255, 249, 226, 215, 195, 175, 160, 128, 119, 100, 81, 70, 45, 30, 20, 0)

    /** 把 Bitmap 缩放到目标宽度（只在需要时），返回灰度矩阵与尺寸。 */
    private fun toGrayMatrix(src: Bitmap, devWidth: Int, onlyShrink: Boolean): Triple<FloatArray, Int, Int> {
        val targetW = if (onlyShrink) minOf(devWidth, src.width) else devWidth
        val scaled = if (src.width != targetW) {
            val h = max(1, (src.height.toLong() * targetW / src.width).toInt())
            Bitmap.createScaledBitmap(src, targetW, h, true)
        } else src
        val w = scaled.width
        val h = scaled.height
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            // 亮度加权（与逆向一致）
            gray[i] = (r * 0.29891f + g * 0.58661f + b * 0.11448f)
        }
        if (scaled !== src) scaled.recycle()
        return Triple(gray, w, h)
    }

    /**
     * 黑白 1bpp：MSB-first，黑=1，宽度缩放到 devWidth。
     * @return 每行 devWidth/8 字节，共 height 行的连续字节。
     */
    fun imageTo1bpp(src: Bitmap, devWidth: Int, dither: Boolean = true, threshold: Int = 128): Bw {
        val (gray, w, h) = toGrayMatrix(src, devWidth, onlyShrink = false)
        val lineBytes = devWidth / 8
        val out = ByteArray(lineBytes * h)
        if (dither) {
            // Floyd-Steinberg 抖动到 1bit
            val buf = gray.copyOf()
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val idx = y * w + x
                    val old = buf[idx]
                    val newV = if (old < threshold) 0f else 255f
                    val err = old - newV
                    if (newV < 128f) { // 黑点
                        val bytePos = y * lineBytes + (x ushr 3)
                        out[bytePos] = (out[bytePos].toInt() or (1 shl (7 - (x and 7)))).toByte()
                    }
                    if (x + 1 < w) buf[idx + 1] += err * 7f / 16f
                    if (y + 1 < h) {
                        if (x > 0) buf[idx + w - 1] += err * 3f / 16f
                        buf[idx + w] += err * 5f / 16f
                        if (x + 1 < w) buf[idx + w + 1] += err * 1f / 16f
                    }
                }
            }
        } else {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (gray[y * w + x] < threshold) {
                        val bytePos = y * lineBytes + (x ushr 3)
                        out[bytePos] = (out[bytePos].toInt() or (1 shl (7 - (x and 7)))).toByte()
                    }
                }
            }
        }
        return Bw(out, devWidth, h)
    }

    /**
     * 灰度 4bit（rank16，2像素/字节）。
     * 字节: 低半字节=偶数列, 高半字节=奇数列; 值 0=白 15=黑。
     */
    fun imageToGray4(src: Bitmap, devWidth: Int, contrast: Float = 0.8f, gamma: Float = 1.2f): Gray {
        var outWidth = devWidth - (devWidth and 1) // 偶数
        if (outWidth < 2) outWidth = 2
        val (grayIn, w, h) = toGrayMatrix(src, outWidth, onlyShrink = true)

        // 对比度: newC = (c-mean)*(0.4*contrast+1)+mean
        val f3 = 0.4f * contrast + 1.0f
        var mean = 0f
        for (v in grayIn) mean += v
        mean /= grayIn.size.coerceAtLeast(1)
        val gamInv = 1.0f / gamma
        val adjusted = FloatArray(w * h)
        for (i in grayIn.indices) {
            val c = ((grayIn[i] - mean) * f3 + mean).coerceIn(0f, 255f)
            // gamma 色调曲线
            adjusted[i] = Math.pow((c / 255.0), gamInv.toDouble()).toFloat() * 255f
        }

        // 居中铺到整幅打印宽度，空白=白(255)
        val canvas = FloatArray(outWidth * h) { 255f }
        val x0 = (outWidth - w) / 2
        for (y in 0 until h) {
            for (x in 0 until w) {
                canvas[y * outWidth + x0 + x] = adjusted[y * w + x]
            }
        }

        // Floyd-Steinberg 蛇形抖动到 16 级
        val idxMat = fsDitherGray4(canvas, outWidth, h)

        // 打包: 偶列->低半字节, 奇列->高半字节
        val lineBytes = outWidth / 2
        val packed = ByteArray(lineBytes * h)
        for (y in 0 until h) {
            for (xb in 0 until lineBytes) {
                val lo = idxMat[y * outWidth + xb * 2]
                val hi = idxMat[y * outWidth + xb * 2 + 1]
                packed[y * lineBytes + xb] = (lo or (hi shl 4)).toByte()
            }
        }
        return Gray(packed, outWidth, h)
    }

    private fun nearestLevelIndex(value: Float): Int {
        val v = value.roundToInt().coerceIn(0, 255)
        var best = 0
        var bestDiff = Int.MAX_VALUE
        for (k in GRAY16_LEVELS.indices) {
            val d = kotlin.math.abs(GRAY16_LEVELS[k] - v)
            if (d < bestDiff) { bestDiff = d; best = k }
        }
        return best
    }

    private fun fsDitherGray4(gray: FloatArray, w: Int, h: Int): IntArray {
        val buf = gray.copyOf()
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val serp = (y and 1) == 1
            val xs = if (serp) (w - 1) downTo 0 else 0 until w
            for (x in xs) {
                val idx = y * w + x
                val old = buf[idx]
                val k = nearestLevelIndex(old)
                out[idx] = k
                val err = old - GRAY16_LEVELS[k]
                if (!serp) {
                    if (x + 1 < w) buf[idx + 1] += err * 0.4375f
                    if (y + 1 < h) {
                        if (x > 0) buf[idx + w - 1] += err * 0.1875f
                        buf[idx + w] += err * 0.3125f
                        if (x + 1 < w) buf[idx + w + 1] += err * 0.0625f
                    }
                } else {
                    if (x - 1 >= 0) buf[idx - 1] += err * 0.4375f
                    if (y + 1 < h) {
                        if (x + 1 < w) buf[idx + w + 1] += err * 0.1875f
                        buf[idx + w] += err * 0.3125f
                        if (x - 1 >= 0) buf[idx + w - 1] += err * 0.0625f
                    }
                }
            }
        }
        return out
    }

    data class Bw(val data: ByteArray, val width: Int, val lines: Int)
    data class Gray(val data: ByteArray, val width: Int, val lines: Int)
}
