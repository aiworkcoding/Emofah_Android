package com.emofah.agencies.data.catalog

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

sealed class ProductRegistrationRpcOutcome {
    data class CommittedOnline(
        val serverProductId: Long,
        val canonicalName: String,
        val qty: Int,
        val sellPrice: Double,
        val imageUrl: String?,
        val alreadyCommitted: Boolean,
        val matchedExistingDuplicate: Boolean,
    ) : ProductRegistrationRpcOutcome()

    data class Rejected(val message: String) : ProductRegistrationRpcOutcome()
    data class RpcMissing(val message: String) : ProductRegistrationRpcOutcome()
    data class AuthRequired(val message: String) : ProductRegistrationRpcOutcome()
    data class NetworkOrTransientError(val message: String) : ProductRegistrationRpcOutcome()
}

data class RegisterProductRpcRequest(
    val draftId: String,
    val idempotencyKey: String,
    val name: String,
    val category: String,
    val sellPrice: Double,
    val buyPrice: Double,
    val initialQty: Int,
    val description: String?,
    val localPhotoPath: String?,
    val actorId: String,
    val deviceId: String,
)

interface ProductRegistrationGateway {
    suspend fun registerProduct(request: RegisterProductRpcRequest): ProductRegistrationRpcOutcome
}

/**
 * Stage 1.5 (Action 1.5.1):
 * Registers a confirmed new product through the atomic idempotent Supabase RPC
 * `public.register_android_product`, optionally uploading a compact reference photo
 * to Supabase Storage `product-images` when permitted.
 */
class SupabaseProductRegistrationClient(
    private val supabaseUrl: String = SupabaseConfig.url,
    private val anonKey: String = SupabaseConfig.anonKey,
) : ProductRegistrationGateway {

    override suspend fun registerProduct(
        request: RegisterProductRpcRequest,
    ): ProductRegistrationRpcOutcome = withContext(Dispatchers.IO) {
        if (!supabaseUrl.startsWith("https://") || anonKey.isBlank()) {
            return@withContext ProductRegistrationRpcOutcome.NetworkOrTransientError(
                "Supabase credentials not configured in local.properties."
            )
        }

        // Best-effort reference photo upload to shared product-images bucket;
        // registration still proceeds safely if storage policy disallows anon uploads.
        val uploadedImageUrl = request.localPhotoPath?.let { path ->
            uploadReferencePhotoBestEffort(request.draftId, path)
        }

        val endpoint = "${supabaseUrl.trimEnd('/')}/rest/v1/rpc/register_android_product"
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
            ProductRegistrationRpcOutcome.NetworkOrTransientError(
                e.message ?: "Network unreachable while registering product."
            )
        } catch (e: Exception) {
            ProductRegistrationRpcOutcome.NetworkOrTransientError(
                e.message ?: "Unexpected transport error."
            )
        } finally {
            conn?.disconnect()
        }
    }

    private fun uploadReferencePhotoBestEffort(draftId: String, localPath: String): String? {
        val file = File(localPath)
        if (!file.exists() || file.length() == 0L) return null
        val jpegBytes = encodeUploadJpeg(file) ?: return null

        val safeObjectPath = "android-refs/${draftId.replace(Regex("""[^a-zA-Z0-9_-]"""), "_")}.jpg"
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

    private fun encodeUploadJpeg(file: File): ByteArray? {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        return try {
            val (w, h) = ImageQualityMath.computeScaledDimensions(bitmap.width, bitmap.height, 800)
            val scaled = if (w != bitmap.width || h != bitmap.height) {
                Bitmap.createScaledBitmap(bitmap, w, h, true)
            } else {
                bitmap
            }
            val bytes = ByteArrayOutputStream().use { baos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, baos)
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
            request: RegisterProductRpcRequest,
            imageUrl: String?,
        ): String {
            return JSONObject().apply {
                put("p_draft_id", request.draftId)
                put("p_idempotency_key", request.idempotencyKey)
                put("p_name", request.name)
                put("p_category", request.category)
                put("p_sell_price", request.sellPrice)
                put("p_buy_price", request.buyPrice)
                put("p_initial_qty", request.initialQty)
                put("p_description", request.description ?: JSONObject.NULL)
                put("p_image_url", imageUrl ?: JSONObject.NULL)
                put("p_actor_id", request.actorId)
                put("p_device_id", request.deviceId)
            }.toString()
        }

        fun parseRpcResponse(
            httpStatus: Int,
            responseBody: String,
            imageUrl: String? = null,
        ): ProductRegistrationRpcOutcome {
            if (httpStatus in 200..299) {
                return try {
                    val json = JSONObject(responseBody)
                    when (val status = json.optString("status", "")) {
                        "COMMITTED", "DUPLICATE_IGNORED", "DUPLICATE_EXISTING" -> {
                            val serverId = json.optLong("server_product_id", -1L)
                            if (serverId <= 0L) {
                                ProductRegistrationRpcOutcome.NetworkOrTransientError(
                                    "Server returned incomplete product registration response."
                                )
                            } else {
                                ProductRegistrationRpcOutcome.CommittedOnline(
                                    serverProductId = serverId,
                                    canonicalName = json.optString("name", ""),
                                    qty = json.optInt("qty", 0),
                                    sellPrice = json.optDouble("sell_price", 0.0),
                                    imageUrl = imageUrl,
                                    alreadyCommitted = status != "COMMITTED",
                                    matchedExistingDuplicate = status == "DUPLICATE_EXISTING",
                                )
                            }
                        }

                        "INVALID_INPUT" -> ProductRegistrationRpcOutcome.Rejected(
                            json.optString("message", "Product details rejected by server.")
                        )

                        else -> ProductRegistrationRpcOutcome.NetworkOrTransientError(
                            "Unexpected server response status: $status"
                        )
                    }
                } catch (e: Exception) {
                    ProductRegistrationRpcOutcome.NetworkOrTransientError(
                        "Failed to parse product registration response: ${e.message}"
                    )
                }
            }

            val errJson = runCatching { JSONObject(responseBody) }.getOrNull()
            val errCode = errJson?.optString("code", "").orEmpty()
            val errMsg = errJson?.optString("message", responseBody).orEmpty()

            if (errCode == "PGRST202" || (httpStatus == 404 && responseBody.contains("register_android_product"))) {
                return ProductRegistrationRpcOutcome.RpcMissing(
                    "Supabase RPC register_android_product is not deployed yet. Apply supabase/migrations/20261114_register_android_product_rpc.sql."
                )
            }

            if (httpStatus == 401 || httpStatus == 403) {
                return ProductRegistrationRpcOutcome.AuthRequired(
                    errMsg.ifBlank { "Authentication required (HTTP $httpStatus)." }
                )
            }

            if (httpStatus in 400..499 && httpStatus != 408 && httpStatus != 429) {
                return ProductRegistrationRpcOutcome.Rejected(
                    errMsg.ifBlank { "Server rejected product registration (HTTP $httpStatus)." }
                )
            }

            return ProductRegistrationRpcOutcome.NetworkOrTransientError(
                errMsg.ifBlank { "Transient server error (HTTP $httpStatus)." }
            )
        }
    }
}
