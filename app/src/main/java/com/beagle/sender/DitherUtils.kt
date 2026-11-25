package com.beagle.sender

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect

object DitherUtils {
    const val BEAGLE_WIDTH = 600
    const val BEAGLE_HEIGHT = 800

    fun processPageForBeagle(src: Bitmap, autoCrop: Boolean): ByteArray {
        val cropped = if (autoCrop) cropWhiteBorders(src) else src
        val scaled = Bitmap.createBitmap(BEAGLE_WIDTH, BEAGLE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaled)
        canvas.drawColor(Color.WHITE)

        val scale = minOf(
            BEAGLE_WIDTH.toFloat() / cropped.width,
            BEAGLE_HEIGHT.toFloat() / cropped.height
        )
        val dstW = (cropped.width * scale).toInt()
        val dstH = (cropped.height * scale).toInt()
        val left = (BEAGLE_WIDTH - dstW) / 2
        val top = (BEAGLE_HEIGHT - dstH) / 2

        val dstRect = Rect(left, top, left + dstW, top + dstH)
        canvas.drawBitmap(cropped, null, dstRect, null)
        if (cropped !== src) cropped.recycle()

        val pixels = IntArray(BEAGLE_WIDTH * BEAGLE_HEIGHT)
        scaled.getPixels(pixels, 0, BEAGLE_WIDTH, 0, 0, BEAGLE_WIDTH, BEAGLE_HEIGHT)
        scaled.recycle()

        val gray = FloatArray(pixels.size) { i ->
            val c = pixels[i]
            0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
        }

        val levels = IntArray(pixels.size)
        for (y in 0 until BEAGLE_HEIGHT) {
            for (x in 0 until BEAGLE_WIDTH) {
                val idx = y * BEAGLE_WIDTH + x
                val oldPixel = gray[idx].coerceIn(0f, 255f)
                val level = ((oldPixel / 255f) * 7f + 0.5f).toInt().coerceIn(0, 7)
                levels[idx] = level
                val newPixel = (level / 7f) * 255f
                val error = oldPixel - newPixel

                if (x + 1 < BEAGLE_WIDTH) gray[idx + 1] += error * 7f / 16f
                if (y + 1 < BEAGLE_HEIGHT) {
                    if (x > 0) gray[idx + BEAGLE_WIDTH - 1] += error * 3f / 16f
                    gray[idx + BEAGLE_WIDTH] += error * 5f / 16f
                    if (x + 1 < BEAGLE_WIDTH) gray[idx + BEAGLE_WIDTH + 1] += error * 1f / 16f
                }
            }
        }

        val packed = ByteArray((BEAGLE_WIDTH * BEAGLE_HEIGHT) / 2)
        for (i in packed.indices) {
            val p1 = (levels[i * 2] and 0x07) shl 5
            val p2 = (levels[i * 2 + 1] and 0x07) shl 1
            packed[i] = (p1 or p2).toByte()
        }
        return packed
    }

    private fun cropWhiteBorders(bmp: Bitmap): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        var top = h
        var bottom = 0
        var left = w
        var right = 0

        for (y in 0 until h step 2) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w step 2) {
                val c = row[x]
                if (Color.red(c) < 245 || Color.green(c) < 245 || Color.blue(c) < 245) {
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        if (top >= bottom || left >= right) return bmp
        val pad = 12
        val l = (left - pad).coerceAtLeast(0)
        val t = (top - pad).coerceAtLeast(0)
        val r = (right + pad).coerceAtMost(w)
        val b = (bottom + pad).coerceAtMost(h)
        return Bitmap.createBitmap(bmp, l, t, r - l, b - t)
    }
}
