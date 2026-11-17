package com.emofah.agencies.data.catalog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant

/**
 * Action 1.1.3 & 1.1.5:
 * Progressive, on-demand reference photograph cache + lightweight perceptual dHash generator.
 * Avoids eagerly downloading the entire image library; verifies decoded image validity.
 */
class ReferenceImageCache(context: Context) {

    private val cacheDir = File(context.filesDir, "reference_images").apply { mkdirs() }

    suspend fun getOrFetchReference(
        productId: Long,
        remoteUrl: String?,
        angle: ReferenceAngle = ReferenceAngle.FRONT,
    ): ProductReferencePhoto = withContext(Dispatchers.IO) {
        if (remoteUrl.isNullOrBlank()) {
            return@withContext ProductReferencePhoto(
                id = "ref_${productId}_${angle.name.lowercase()}",
                productId = productId,
                angle = angle,
                remoteUrl = null,
                localCachePath = null,
                widthPx = null,
                heightPx = null,
                isValid = false,
                lastVerifiedAtIso = Instant.now().toString(),
            )
        }

        val targetFile = fileFor(productId, remoteUrl)
        if (targetFile.exists() && targetFile.length() > 0L) {
            val bounds = decodeBounds(targetFile)
            if (bounds != null) {
                return@withContext ProductReferencePhoto(
                    id = "ref_${productId}_${angle.name.lowercase()}",
                    productId = productId,
                    angle = angle,
                    remoteUrl = remoteUrl,
                    localCachePath = targetFile.absolutePath,
                    widthPx = bounds.first,
                    heightPx = bounds.second,
                    isValid = true,
                    lastVerifiedAtIso = Instant.now().toString(),
                )
            } else {
                targetFile.delete()
            }
        }

        val downloaded = downloadToFile(remoteUrl, targetFile)
        if (!downloaded) {
            return@withContext ProductReferencePhoto(
                id = "ref_${productId}_${angle.name.lowercase()}",
                productId = productId,
                angle = angle,
                remoteUrl = remoteUrl,
                localCachePath = null,
                widthPx = null,
                heightPx = null,
                isValid = false,
                lastVerifiedAtIso = Instant.now().toString(),
            )
        }

        val bounds = decodeBounds(targetFile)
        val valid = bounds != null
        if (!valid) {
            targetFile.delete()
        } else {
            targetFile.setLastModified(System.currentTimeMillis())
            enforceStorageBudget()
        }

        ProductReferencePhoto(
            id = "ref_${productId}_${angle.name.lowercase()}",
            productId = productId,
            angle = angle,
            remoteUrl = remoteUrl,
            localCachePath = if (valid) targetFile.absolutePath else null,
            widthPx = bounds?.first,
            heightPx = bounds?.second,
            isValid = valid,
            lastVerifiedAtIso = Instant.now().toString(),
        )
    }

    /**
     * Progressively caches a small bounded batch of reference images without
     * eagerly downloading the entire catalogue library.
     */
    suspend fun prefetchProgressiveBatch(
        items: List<Pair<Long, String?>>,
        maxNewDownloads: Int = 6,
    ): List<ProductReferencePhoto> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ProductReferencePhoto>()
        var downloadedCount = 0
        for ((productId, url) in items) {
            if (url.isNullOrBlank()) continue
            val existing = fileFor(productId, url)
            val alreadyCached = existing.exists() && existing.length() > 0L
            if (!alreadyCached && downloadedCount >= maxNewDownloads) {
                continue
            }
            val photo = getOrFetchReference(productId, url, ReferenceAngle.FRONT)
            results.add(photo)
            if (!alreadyCached && photo.isValid) {
                downloadedCount++
            }
        }
        results
    }

    fun currentCacheBytes(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    fun enforceStorageBudget(maxBytes: Long = MAX_CACHE_BYTES) {
        val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= maxBytes) break
            val size = file.length()
            if (file.delete()) {
                total -= size
            }
        }
    }

    suspend fun computeDHashHex(localFilePath: String): String? = withContext(Dispatchers.IO) {
        val file = File(localFilePath)
        if (!file.exists()) return@withContext null
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext null
        return@withContext try {
            computeDHashFromBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun fileFor(productId: Long, url: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir, "prod_${productId}_$digest.img")
    }

    private fun decodeBounds(file: File): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return if (opts.outWidth > 0 && opts.outHeight > 0) {
            opts.outWidth to opts.outHeight
        } else {
            null
        }
    }

    private fun downloadToFile(remoteUrl: String, dest: File): Boolean {
        return try {
            val conn = (URL(remoteUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
                requestMethod = "GET"
            }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return false
            }
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            conn.disconnect()
            dest.length() > 0L
        } catch (_: Exception) {
            if (dest.exists()) dest.delete()
            false
        }
    }

    companion object {
        const val MAX_CACHE_BYTES = 25L * 1024L * 1024L // 25 MB image cache cap

        /**
         * Computes a 64-bit difference hash (9x8 grayscale comparison) for fast on-device similarity checks.
         */
        fun computeDHashFromBitmap(source: Bitmap): String {
            val scaled = Bitmap.createScaledBitmap(source, 9, 8, true)
            var hash = 0UL
            var bit = 0
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val left = luminance(scaled.getPixel(x, y))
                    val right = luminance(scaled.getPixel(x + 1, y))
                    if (left > right) {
                        hash = hash or (1UL shl bit)
                    }
                    bit++
                }
            }
            if (scaled != source) {
                scaled.recycle()
            }
            return hash.toString(16).padStart(16, '0')
        }

        private fun luminance(argb: Int): Int {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return (299 * r + 587 * g + 114 * b) / 1000
        }
    }
}
