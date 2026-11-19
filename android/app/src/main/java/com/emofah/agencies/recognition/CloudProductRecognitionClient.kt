package com.emofah.agencies.recognition

import android.graphics.Bitmap
import android.util.Base64
import com.emofah.agencies.camera.ImageQualityMath
import com.emofah.agencies.camera.PreparedCaptureResult
import com.emofah.agencies.data.SupabaseConfig
import com.emofah.agencies.data.catalog.ProductIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed class CloudRecognitionFetchResult {
    data class Success(
        val suggestions: List<CloudCandidateSuggestion>,
        val extractedLabel: String?,
        val fromCache: Boolean,
    ) : CloudRecognitionFetchResult()

    data class Unavailable(val reason: String) : CloudRecognitionFetchResult()
}

interface CloudRecognitionGateway {
    suspend fun recognize(
        capture: PreparedCaptureResult,
        ocrText: String,
        catalogueProducts: List<ProductIdentity>,
    ): CloudRecognitionFetchResult
}

/**
 * Stage 1.4 (Actions 1.4.1 & 1.4.2):
 * Submits a compact resized JPEG + compact catalogue context to the configured
 * server-side recognition endpoint when local recognition is uncertain.
 * Never touches capture.originalPath and never embeds AI provider keys.
 */
class CloudProductRecognitionClient(
    private val telemetryStore: RecognitionTelemetryStore,
    private val endpointUrl: String = SupabaseConfig.cloudRecognitionEndpoint,
    private val anonKey: String = SupabaseConfig.anonKey,
) : CloudRecognitionGateway {

    override suspend fun recognize(
        capture: PreparedCaptureResult,
        ocrText: String,
        catalogueProducts: List<ProductIdentity>,
    ): CloudRecognitionFetchResult = withContext(Dispatchers.IO) {
        val imageKey = computeImageDedupKey(capture)

        // 1. Check deduplicated cache before any network/AI cost.
        telemetryStore.getCachedLookup(imageKey)?.let { cached ->
            telemetryStore.recordCloudCacheHit()
            return@withContext CloudRecognitionFetchResult.Success(
                suggestions = cached.productIds.map { pid ->
                    CloudCandidateSuggestion(
                        productId = pid,
                        reason = "Cached cloud recognition match",
                    )
                },
                extractedLabel = cached.extractedLabel,
                fromCache = true,
            )
        }

        if (endpointUrl.isBlank()) {
            return@withContext CloudRecognitionFetchResult.Unavailable(
                "Cloud recognition service is not configured."
            )
        }

        // 2. Resize & compress recognition copy into a lightweight JPEG payload.
        val jpegBase64 = encodeCompactJpegBase64(capture.recognitionCopyPath)
            ?: return@withContext CloudRecognitionFetchResult.Unavailable(
                "Could not prepare image for cloud recognition."
            )

        val contextItems = CloudCostControlPolicy.buildCompactCatalogueContext(
            products = catalogueProducts,
            ocrText = ocrText,
        )
        if (contextItems.isEmpty()) {
            return@withContext CloudRecognitionFetchResult.Unavailable(
                "Local product catalogue is empty."
            )
        }

        telemetryStore.recordCloudAttempt()

        val payloadJson = buildRequestPayload(
            imageBase64 = jpegBase64,
            ocrText = ocrText,
            contextItems = contextItems,
        )

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                if (anonKey.isNotBlank()) {
                    setRequestProperty("apikey", anonKey)
                    setRequestProperty("Authorization", "Bearer ${SupabaseConfig.bearerToken(anonKey)}")
                }
            }

            conn.outputStream.use { out ->
                out.write(payloadJson.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                val msg = extractErrorMessage(body, code)
                telemetryStore.recordCloudFailure(msg)
                return@withContext CloudRecognitionFetchResult.Unavailable(msg)
            }

            val parsed = parseResponseJson(body)
            if (parsed.suggestions.isNotEmpty()) {
                val validIds = catalogueProducts.filter { !it.isArchived }.map { it.id }.toSet()
                val validSuggestionIds = parsed.suggestions
                    .mapNotNull { it.productId }
                    .filter { it in validIds }
                if (validSuggestionIds.isNotEmpty()) {
                    telemetryStore.putCachedLookup(
                        imageKey = imageKey,
                        productIds = validSuggestionIds,
                        extractedLabel = parsed.extractedLabel,
                    )
                }
            }

            CloudRecognitionFetchResult.Success(
                suggestions = parsed.suggestions,
                extractedLabel = parsed.extractedLabel,
                fromCache = false,
            )
        } catch (e: IOException) {
            val msg = "Cloud recognition unreachable (${e.message ?: "network error"})."
            telemetryStore.recordCloudFailure(msg)
            CloudRecognitionFetchResult.Unavailable(msg)
        } catch (e: Exception) {
            val msg = e.message ?: "Cloud recognition failed."
            telemetryStore.recordCloudFailure(msg)
            CloudRecognitionFetchResult.Unavailable(msg)
        } finally {
            conn?.disconnect()
        }
    }

    private fun computeImageDedupKey(capture: PreparedCaptureResult): String {
        val file = File(capture.recognitionCopyPath)
        if (!file.exists()) return capture.dHashHex.ifBlank { capture.captureId }
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                var read = input.read(buf)
                while (read > 0) {
                    digest.update(buf, 0, read)
                    read = input.read(buf)
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            "${capture.dHashHex}_${sha.take(16)}"
        } catch (_: Exception) {
            capture.dHashHex.ifBlank { capture.captureId }
        }
    }

    private fun encodeCompactJpegBase64(imagePath: String): String? {
        val file = File(imagePath)
        if (!file.exists()) return null
        val bitmap = ImageQualityMath.decodeUprightBitmap(
            file,
            CloudCostControlPolicy.CLOUD_IMAGE_MAX_DIMENSION,
        ) ?: return null
        return try {
            val (w, h) = ImageQualityMath.computeScaledDimensions(
                bitmap.width,
                bitmap.height,
                CloudCostControlPolicy.CLOUD_IMAGE_MAX_DIMENSION,
            )
            val scaled = if (w != bitmap.width || h != bitmap.height) {
                Bitmap.createScaledBitmap(bitmap, w, h, true)
            } else {
                bitmap
            }
            val bytes = ByteArrayOutputStream().use { baos ->
                scaled.compress(
                    Bitmap.CompressFormat.JPEG,
                    CloudCostControlPolicy.CLOUD_IMAGE_JPEG_QUALITY,
                    baos,
                )
                baos.toByteArray()
            }
            if (scaled != bitmap) {
                scaled.recycle()
            }
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    companion object {
        internal data class ParsedCloudPayload(
            val suggestions: List<CloudCandidateSuggestion>,
            val extractedLabel: String?,
        )

        internal fun buildRequestPayload(
            imageBase64: String,
            ocrText: String,
            contextItems: List<CloudCatalogContextItem>,
        ): String {
            val catalogArray = JSONArray()
            for (item in contextItems) {
                catalogArray.put(
                    JSONObject().apply {
                        put("id", item.id)
                        put("name", item.name)
                        put("category", item.category)
                        put("variant_label", item.variantLabel ?: JSONObject.NULL)
                        put("brand_hint", item.brandHint ?: JSONObject.NULL)
                    }
                )
            }
            return JSONObject().apply {
                put("image_base64", imageBase64)
                put("mime_type", "image/jpeg")
                put("ocr_text", ocrText)
                put("catalog_context", catalogArray)
            }.toString()
        }

        internal fun parseResponseJson(rawJson: String): ParsedCloudPayload {
            val root = JSONObject(rawJson)
            val label = root.optString("extracted_label")
                .takeIf { it.isNotEmpty() && it != "null" }
            val arr = root.optJSONArray("candidates") ?: JSONArray()
            val list = mutableListOf<CloudCandidateSuggestion>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val pid = if (obj.has("product_id") && !obj.isNull("product_id")) {
                    obj.optLong("product_id", -1L).takeIf { it > 0L }
                } else {
                    null
                }
                val reason = obj.optString("reason")
                    .takeIf { it.isNotEmpty() && it != "null" }
                list.add(
                    CloudCandidateSuggestion(
                        productId = pid,
                        reason = reason,
                    )
                )
            }
            return ParsedCloudPayload(
                suggestions = list,
                extractedLabel = label,
            )
        }

        private fun extractErrorMessage(body: String, httpCode: Int): String {
            val jsonMsg = runCatching {
                JSONObject(body).optString("error").takeIf { it.isNotBlank() }
            }.getOrNull()
            return jsonMsg ?: when (httpCode) {
                404 -> "Cloud recognition function not deployed yet (HTTP 404)."
                429 -> "Cloud recognition rate limit reached (HTTP 429)."
                503 -> "Cloud recognition service is not configured on backend."
                else -> "Cloud recognition error (HTTP $httpCode)."
            }
        }
    }
}
