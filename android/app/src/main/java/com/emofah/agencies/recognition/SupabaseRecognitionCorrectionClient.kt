package com.emofah.agencies.recognition

import android.graphics.Bitmap
import com.emofah.agencies.camera.ImageQualityMath
import com.emofah.agencies.data.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed class RecognitionCorrectionRpcOutcome {
    data class CommittedOnline(
        val serverCorrectionId: Long,
        val confirmedProductId: Long,
        val uploadedImageUrl: String?,
        val alreadyCommitted: Boolean,
    ) : RecognitionCorrectionRpcOutcome()

    data class Rejected(val message: String) : RecognitionCorrectionRpcOutcome()
    data class RpcMissing(val message: String) : RecognitionCorrectionRpcOutcome()
    data class AuthRequired(val message: String) : RecognitionCorrectionRpcOutcome()
    data class NetworkOrTransientError(val message: String) : RecognitionCorrectionRpcOutcome()
}

data class RecognitionCorrectionRpcRequest(
    val correctionId: String,
    val idempotencyKey: String,
    val suggestedProductId: Long?,
    val suggestedProductName: String?,
    val confirmedProductId: Long,
    val matchStatus: String,
    val selectionKind: String,
    val usedCloudAssist: Boolean,
    val learnedAlias: String?,
    val ocrSnippet: String?,
    val visualDHashHex: String?,
    val localPhotoPath: String?,
    val actorId: String,
    val deviceId: String,
)

interface RecognitionCorrectionGateway {
    suspend fun submitCorrection(request: RecognitionCorrectionRpcRequest): RecognitionCorrectionRpcOutcome
}

/**
 * Stage 1.6 (Action 1.6.1):
 * Synchronises confirmed recognition corrections, learned aliases, and optional visual
 * reference photos to shared Supabase via `record_android_recognition_correction`.
 */
class SupabaseRecognitionCorrectionClient(
    private val supabaseUrl: String = SupabaseConfig.url,
    private val anonKey: String = SupabaseConfig.anonKey,
) : RecognitionCorrectionGateway {

    override suspend fun submitCorrection(
        request: RecognitionCorrectionRpcRequest,
    ): RecognitionCorrectionRpcOutcome = withContext(Dispatchers.IO) {
        if (!supabaseUrl.startsWith("https://") || anonKey.isBlank()) {
            return@withContext RecognitionCorrectionRpcOutcome.NetworkOrTransientError(
                "Supabase credentials not configured in local.properties."
            )
        }

        val uploadedImageUrl = request.localPhotoPath?.let { path ->
            uploadCorrectionPhotoBestEffort(request.correctionId, path)
        }

        val endpoint = "${supabaseUrl.trimEnd('/')}/rest/v1/rpc/record_android_recognition_correction"
        val payload = buildRpcPayloadJson(request, uploadedImageUrl)

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 12_000
                doOutput = true
                setRequestProperty("apikey", anonKey)
                setRequestProperty("Authorization", "Bearer ${SupabaseConfig.bearerToken(anonKey)}")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            conn.outputStream.use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            parseRpcResponse(code, body, uploadedImageUrl)
        } catch (e: IOException) {
            RecognitionCorrectionRpcOutcome.NetworkOrTransientError(
                e.message ?: "Network unreachable while syncing recognition correction."
            )
        } catch (e: Exception) {
            RecognitionCorrectionRpcOutcome.NetworkOrTransientError(
                e.message ?: "Unexpected transport error."
            )
        } finally {
            conn?.disconnect()
        }
    }

    private fun uploadCorrectionPhotoBestEffort(correctionId: String, localPath: String): String? {
        val file = File(localPath)
        if (!file.exists() || file.length() == 0L) return null
        val jpegBytes = encodeCompactJpeg(file) ?: return null

        val safeObjectPath = "android-corrections/${correctionId.replace(Regex("""[^a-zA-Z0-9_-]"""), "_")}.jpg"
        val base = supabaseUrl.trimEnd('/')
        val uploadUrl = "$base/storage/v1/object/product-images/$safeObjectPath"

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("apikey", anonKey)
                setRequestProperty("Authorization", "Bearer ${SupabaseConfig.bearerToken(anonKey)}")
                setRequestProperty("Content-Type", "image/jpeg")
                setRequestProperty("x-upsert", "true")
            }
            conn.outputStream.use { it.write(jpegBytes) }
            if (conn.responseCode in 200..299) {
                "$base/storage/v1/object/public/product-images/$safeObjectPath"
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun encodeCompactJpeg(file: File): ByteArray? {
        val bitmap = ImageQualityMath.decodeUprightBitmap(file, 1024) ?: return null
        return try {
            val (w, h) = ImageQualityMath.computeScaledDimensions(bitmap.width, bitmap.height, 640)
            val scaled = if (w != bitmap.width || h != bitmap.height) {
                Bitmap.createScaledBitmap(bitmap, w, h, true)
            } else {
                bitmap
            }
            val bytes = ByteArrayOutputStream().use { baos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 78, baos)
                baos.toByteArray()
            }
            if (scaled != bitmap) scaled.recycle()
            bytes
        } catch (_: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    companion object {
        fun buildRpcPayloadJson(
            request: RecognitionCorrectionRpcRequest,
            referenceImageUrl: String?,
        ): String {
            return JSONObject().apply {
                put("p_correction_id", request.correctionId)
                put("p_idempotency_key", request.idempotencyKey)
                put("p_suggested_product_id", request.suggestedProductId ?: JSONObject.NULL)
                put("p_suggested_product_name", request.suggestedProductName ?: JSONObject.NULL)
                put("p_confirmed_product_id", request.confirmedProductId)
                put("p_match_status", request.matchStatus)
                put("p_selection_kind", request.selectionKind)
                put("p_used_cloud_assist", request.usedCloudAssist)
                put("p_learned_alias", request.learnedAlias ?: JSONObject.NULL)
                put("p_ocr_snippet", request.ocrSnippet ?: JSONObject.NULL)
                put("p_visual_dhash_hex", request.visualDHashHex ?: JSONObject.NULL)
                put("p_reference_image_url", referenceImageUrl ?: JSONObject.NULL)
                put("p_actor_id", request.actorId)
                put("p_device_id", request.deviceId)
            }.toString()
        }

        fun parseRpcResponse(
            httpStatus: Int,
            responseBody: String,
            uploadedImageUrl: String? = null,
        ): RecognitionCorrectionRpcOutcome {
            if (httpStatus in 200..299) {
                return try {
                    val json = JSONObject(responseBody)
                    when (val status = json.optString("status", "")) {
                        "COMMITTED", "DUPLICATE_IGNORED" -> {
                            val serverId = json.optLong("server_correction_id", -1L)
                            val productId = json.optLong("confirmed_product_id", -1L)
                            if (serverId <= 0L || productId <= 0L) {
                                RecognitionCorrectionRpcOutcome.NetworkOrTransientError(
                                    "Server returned incomplete recognition correction acknowledgment."
                                )
                            } else {
                                RecognitionCorrectionRpcOutcome.CommittedOnline(
                                    serverCorrectionId = serverId,
                                    confirmedProductId = productId,
                                    uploadedImageUrl = uploadedImageUrl,
                                    alreadyCommitted = status == "DUPLICATE_IGNORED",
                                )
                            }
                        }

                        "PRODUCT_NOT_FOUND", "INVALID_INPUT" -> {
                            RecognitionCorrectionRpcOutcome.Rejected(
                                json.optString("message", "Recognition correction rejected ($status).")
                            )
                        }

                        else -> RecognitionCorrectionRpcOutcome.NetworkOrTransientError(
                            "Unexpected server response status: $status"
                        )
                    }
                } catch (e: Exception) {
                    RecognitionCorrectionRpcOutcome.NetworkOrTransientError(
                        "Failed to parse correction response: ${e.message}"
                    )
                }
            }

            val errJson = runCatching { JSONObject(responseBody) }.getOrNull()
            val errCode = errJson?.optString("code", "").orEmpty()
            val errMsg = errJson?.optString("message", responseBody).orEmpty()

            if (errCode == "PGRST202" || (httpStatus == 404 && responseBody.contains("record_android_recognition_correction"))) {
                return RecognitionCorrectionRpcOutcome.RpcMissing(
                    "Supabase RPC record_android_recognition_correction is not deployed yet. Apply supabase/migrations/20261114_record_recognition_correction_rpc.sql."
                )
            }

            if (httpStatus == 401 || httpStatus == 403) {
                return RecognitionCorrectionRpcOutcome.AuthRequired(
                    errMsg.ifBlank { "Authentication required (HTTP $httpStatus)." }
                )
            }

            if (httpStatus in 400..499 && httpStatus != 408 && httpStatus != 429) {
                return RecognitionCorrectionRpcOutcome.Rejected(
                    errMsg.ifBlank { "Server rejected recognition correction (HTTP $httpStatus)." }
                )
            }

            return RecognitionCorrectionRpcOutcome.NetworkOrTransientError(
                errMsg.ifBlank { "Transient server error (HTTP $httpStatus)." }
            )
        }
    }
}
