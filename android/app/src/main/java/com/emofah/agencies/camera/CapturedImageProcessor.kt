package com.emofah.agencies.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.emofah.agencies.data.catalog.ReferenceImageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Stage 1.2 (Actions 1.2.2 & 1.2.3):
 * Offline-first image preparation for captured product photos:
 * 1. Corrects EXIF orientation.
 * 2. Evaluates brightness (low-light warning) & edge sharpness (blur warning).
 * 3. Saves compressed preserved original (max 1600px) and lightweight recognition copy (max 640px).
 * 4. Computes dHash visual signature once per capture ID (avoids repeated processing).
 */
data class ImageQualityAssessment(
    val averageLuma: Double,
    val edgeSharpness: Double,
    val isTooDark: Boolean,
    val isTooBlurry: Boolean,
) {
    val hasWarning: Boolean
        get() = isTooDark || isTooBlurry

    val warningMessage: String?
        get() = when {
            isTooDark && isTooBlurry -> "Image looks dark and blurry. Turn on flash or hold steady."
            isTooDark -> "Lighting looks very dim. Try better lighting or flash."
            isTooBlurry -> "Image looks slightly blurry. Retake while holding steady."
            else -> null
        }
}

data class PreparedCaptureResult(
    val captureId: String,
    val originalPath: String,
    val recognitionCopyPath: String,
    val recognitionWidth: Int,
    val recognitionHeight: Int,
    val dHashHex: String,
    val quality: ImageQualityAssessment,
    val capturedAtEpochMs: Long,
)

object ImageQualityMath {
    const val LOW_LIGHT_LUMA_THRESHOLD = 48.0
    const val BLUR_SHARPNESS_THRESHOLD = 14.0
    const val ORIGINAL_MAX_DIMENSION = 1600
    const val RECOGNITION_MAX_DIMENSION = 640

    fun computeScaledDimensions(
        width: Int,
        height: Int,
        maxDimension: Int,
    ): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        val longest = max(width, height)
        if (longest <= maxDimension) return width to height
        val scale = maxDimension.toDouble() / longest.toDouble()
        val targetW = max(1, (width * scale).roundToInt())
        val targetH = max(1, (height * scale).roundToInt())
        return targetW to targetH
    }

    fun exifOrientationToDegrees(orientation: Int): Int {
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSPOSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }
    }

    fun isHorizontalFlip(orientation: Int): Boolean {
        return orientation == ExifInterface.ORIENTATION_FLIP_HORIZONTAL ||
            orientation == ExifInterface.ORIENTATION_FLIP_VERTICAL ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE
    }

    fun assessLumaGrid(lumaValues: IntArray, width: Int, height: Int): ImageQualityAssessment {
        if (lumaValues.isEmpty() || width < 3 || height < 3) {
            return ImageQualityAssessment(
                averageLuma = 0.0,
                edgeSharpness = 0.0,
                isTooDark = true,
                isTooBlurry = true,
            )
        }

        var sumLuma = 0L
        for (v in lumaValues) sumLuma += v
        val avgLuma = sumLuma.toDouble() / lumaValues.size.toDouble()

        // Discrete Laplacian mean absolute response on interior pixels
        var laplacianSum = 0L
        var count = 0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val center = lumaValues[row + x]
                val neighbors =
                    lumaValues[row + x - 1] +
                        lumaValues[row + x + 1] +
                        lumaValues[row - width + x] +
                        lumaValues[row + width + x]
                val lap = abs(4 * center - neighbors)
                laplacianSum += lap
                count++
            }
        }
        val sharpness = if (count > 0) laplacianSum.toDouble() / count.toDouble() else 0.0

        return ImageQualityAssessment(
            averageLuma = avgLuma,
            edgeSharpness = sharpness,
            isTooDark = avgLuma < LOW_LIGHT_LUMA_THRESHOLD,
            isTooBlurry = sharpness < BLUR_SHARPNESS_THRESHOLD,
        )
    }

    fun decodeUprightBitmap(file: File, maxTargetDim: Int): Bitmap? {
        if (!file.exists() || file.length() == 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var inSampleSize = 1
        while (
            bounds.outWidth / inSampleSize > maxTargetDim * 2 ||
            bounds.outHeight / inSampleSize > maxTargetDim * 2
        ) {
            inSampleSize *= 2
        }

        val opts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null

        val exif = runCatching { ExifInterface(file.absolutePath) }.getOrNull()
        val orientation = exif?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        ) ?: ExifInterface.ORIENTATION_NORMAL

        val degrees = exifOrientationToDegrees(orientation)
        val flip = isHorizontalFlip(orientation)
        if (degrees == 0 && !flip) return decoded

        val matrix = Matrix().apply {
            if (flip) postScale(-1f, 1f)
            if (degrees != 0) postRotate(degrees.toFloat())
        }
        val transformed = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (transformed != decoded) {
            decoded.recycle()
        }
        return transformed
    }
}

class CapturedImageProcessor(context: Context) {

    private val scansDir = File(context.cacheDir, "captured_scans").apply { mkdirs() }
    private val processedCache = linkedMapOf<String, PreparedCaptureResult>()

    fun createTempCaptureFile(captureId: String = UUID.randomUUID().toString()): File {
        return File(scansDir, "raw_$captureId.jpg")
    }

    suspend fun prepareCapture(
        rawFile: File,
        captureId: String = UUID.randomUUID().toString(),
    ): PreparedCaptureResult? = withContext(Dispatchers.IO) {
        processedCache[captureId]?.let { return@withContext it }
        if (!rawFile.exists() || rawFile.length() == 0L) return@withContext null

        val upright = ImageQualityMath.decodeUprightBitmap(
            file = rawFile,
            maxTargetDim = ImageQualityMath.ORIGINAL_MAX_DIMENSION,
        ) ?: return@withContext null

        val (origW, origH) = ImageQualityMath.computeScaledDimensions(
            upright.width,
            upright.height,
            ImageQualityMath.ORIGINAL_MAX_DIMENSION,
        )
        val preservedBitmap = if (origW != upright.width || origH != upright.height) {
            Bitmap.createScaledBitmap(upright, origW, origH, true).also {
                upright.recycle()
            }
        } else {
            upright
        }

        val originalOutFile = File(scansDir, "orig_$captureId.jpg")
        saveJpeg(preservedBitmap, originalOutFile, quality = 88)

        val (recW, recH) = ImageQualityMath.computeScaledDimensions(
            preservedBitmap.width,
            preservedBitmap.height,
            ImageQualityMath.RECOGNITION_MAX_DIMENSION,
        )
        val recognitionBitmap = if (recW != preservedBitmap.width || recH != preservedBitmap.height) {
            Bitmap.createScaledBitmap(preservedBitmap, recW, recH, true)
        } else {
            preservedBitmap
        }

        val recognitionOutFile = File(scansDir, "rec_$captureId.jpg")
        saveJpeg(recognitionBitmap, recognitionOutFile, quality = 80)

        val quality = evaluateBitmapQuality(recognitionBitmap)
        val dHash = ReferenceImageCache.computeDHashFromBitmap(recognitionBitmap)

        if (recognitionBitmap != preservedBitmap) {
            recognitionBitmap.recycle()
        }
        preservedBitmap.recycle()

        // Remove un-rotated raw temp file to save cache space
        if (rawFile.absolutePath != originalOutFile.absolutePath && rawFile.exists()) {
            rawFile.delete()
        }

        val result = PreparedCaptureResult(
            captureId = captureId,
            originalPath = originalOutFile.absolutePath,
            recognitionCopyPath = recognitionOutFile.absolutePath,
            recognitionWidth = recW,
            recognitionHeight = recH,
            dHashHex = dHash,
            quality = quality,
            capturedAtEpochMs = System.currentTimeMillis(),
        )
        processedCache[captureId] = result
        result
    }

    fun discardCapture(result: PreparedCaptureResult) {
        processedCache.remove(result.captureId)
        File(result.originalPath).delete()
        File(result.recognitionCopyPath).delete()
    }

    private fun decodeSampledBitmap(file: File, maxTargetDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var inSampleSize = 1
        while (
            bounds.outWidth / inSampleSize > maxTargetDim * 2 ||
            bounds.outHeight / inSampleSize > maxTargetDim * 2
        ) {
            inSampleSize *= 2
        }

        val opts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun applyExifTransform(bitmap: Bitmap, orientation: Int): Bitmap {
        val degrees = ImageQualityMath.exifOrientationToDegrees(orientation)
        val flip = ImageQualityMath.isHorizontalFlip(orientation)
        if (degrees == 0 && !flip) return bitmap

        val matrix = Matrix().apply {
            if (flip) postScale(-1f, 1f)
            if (degrees != 0) postRotate(degrees.toFloat())
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun evaluateBitmapQuality(bitmap: Bitmap): ImageQualityAssessment {
        val sample = Bitmap.createScaledBitmap(bitmap, 96, 96, true)
        val pixels = IntArray(96 * 96)
        sample.getPixels(pixels, 0, 96, 0, 0, 96, 96)
        if (sample != bitmap) sample.recycle()

        val luma = IntArray(pixels.size) { idx ->
            val argb = pixels[idx]
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            (299 * r + 587 * g + 114 * b) / 1000
        }
        return ImageQualityMath.assessLumaGrid(luma, 96, 96)
    }

    private fun saveJpeg(bitmap: Bitmap, dest: File, quality: Int) {
        FileOutputStream(dest).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
    }
}
