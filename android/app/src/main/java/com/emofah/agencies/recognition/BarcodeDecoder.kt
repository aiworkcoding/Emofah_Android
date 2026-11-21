package com.emofah.agencies.recognition

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * 100% offline ZXing barcode / QR scanner executed on the captured bitmap so
 * EAN-13/UPC/Code-128 packaging barcodes match Supabase `products.barcode` / `sku`
 * immediately without cloud requests.
 */
object BarcodeDecoder {

    private val SUPPORTED_FORMATS = listOf(
        BarcodeFormat.EAN_13,
        BarcodeFormat.EAN_8,
        BarcodeFormat.UPC_A,
        BarcodeFormat.UPC_E,
        BarcodeFormat.CODE_128,
        BarcodeFormat.CODE_39,
        BarcodeFormat.ITF,
        BarcodeFormat.QR_CODE,
    )

    private val DECODE_HINTS: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to SUPPORTED_FORMATS,
    )

    fun decodeBarcodes(bitmap: Bitmap): List<String> {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return emptyList()
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val results = linkedSetOf<String>()
        decodeFromPixels(pixels, width, height)?.let { results.add(it) }

        // If upright scan found nothing, also attempt 90-degree rotated scan for vertical barcodes.
        if (results.isEmpty()) {
            val rotated = IntArray(pixels.size)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    rotated[x * height + (height - 1 - y)] = pixels[y * width + x]
                }
            }
            decodeFromPixels(rotated, height, width)?.let { results.add(it) }
        }
        return results.toList()
    }

    private fun decodeFromPixels(pixels: IntArray, width: Int, height: Int): String? {
        return try {
            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val reader = MultiFormatReader().apply { setHints(DECODE_HINTS) }
            reader.decode(binaryBitmap).text
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }
}
