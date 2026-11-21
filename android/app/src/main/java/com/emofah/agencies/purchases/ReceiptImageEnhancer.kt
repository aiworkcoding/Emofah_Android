package com.emofah.agencies.purchases

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * Lightweight on-device bitmap enhancement for faded, thermal, or low-contrast
 * paper receipts before running ML Kit OCR. Never modifies the preserved original photo.
 */
object ReceiptImageEnhancer {

    fun enhanceForOcr(source: Bitmap): Bitmap {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return source
        val width = source.width
        val height = source.height
        val count = width * height
        val pixels = IntArray(count)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val luma = IntArray(count)
        val hist = IntArray(256)
        for (i in 0 until count) {
            val argb = pixels[i]
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            val y = ((299 * r + 587 * g + 114 * b) / 1000).coerceIn(0, 255)
            luma[i] = y
            hist[y]++
        }

        // Compute 3rd and 97th percentile luminance bounds for robust contrast stretching
        val lowTarget = (count * 0.03).toInt()
        val highTarget = (count * 0.97).toInt()
        var cumulative = 0
        var minL = 0
        var maxL = 255
        for (v in 0..255) {
            cumulative += hist[v]
            if (cumulative >= lowTarget) {
                minL = v
                break
            }
        }
        cumulative = 0
        for (v in 0..255) {
            cumulative += hist[v]
            if (cumulative >= highTarget) {
                maxL = v
                break
            }
        }

        val span = (maxL - minL).coerceAtLeast(32)
        val enhancedPixels = IntArray(count)
        for (i in 0 until count) {
            val stretched = (((luma[i] - minL) * 255f) / span.toFloat()).roundToInt().coerceIn(0, 255)
            // Mild S-curve push to darken ink and brighten paper background for crisp OCR
            val normalized = stretched / 255f
            val contrasted = ((normalized * normalized * (3f - 2f * normalized)) * 255f)
                .roundToInt()
                .coerceIn(0, 255)
            enhancedPixels[i] = (0xFF shl 24) or (contrasted shl 16) or (contrasted shl 8) or contrasted
        }

        return Bitmap.createBitmap(enhancedPixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
