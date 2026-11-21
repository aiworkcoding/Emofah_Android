package com.emofah.agencies.stock

import com.emofah.agencies.data.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

interface StockAdjustmentGateway {
    suspend fun submitStockAdjustment(request: AndroidStockAdjustmentRpcRequest): StockAdjustmentRpcOutcome
}

/**
 * Stage 4.3:
 * Submits inventory adjustments to the atomic `public.adjust_android_stock` Supabase RPC.
 */
class SupabaseStockAdjustmentClient(
    private val supabaseUrl: String = SupabaseConfig.url,
    private val anonKey: String = SupabaseConfig.anonKey,
) : StockAdjustmentGateway {

    override suspend fun submitStockAdjustment(
        request: AndroidStockAdjustmentRpcRequest,
    ): StockAdjustmentRpcOutcome = withContext(Dispatchers.IO) {
        if (!supabaseUrl.startsWith("https://") || anonKey.isBlank()) {
            return@withContext StockAdjustmentRpcOutcome.NetworkOrTransientError(
                "Supabase configuration missing in BuildConfig."
            )
        }

        val endpoint = "${supabaseUrl.trimEnd('/')}/rest/v1/rpc/adjust_android_stock"
        val payload = buildRpcPayloadJson(request)

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 10000
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

            val outcome = parseRpcResponse(httpStatus = code, responseBody = body)
            if (outcome is StockAdjustmentRpcOutcome.RpcMissing) {
                adjustViaProductsTableFallback(request)
            } else {
                outcome
            }
        } catch (e: IOException) {
            StockAdjustmentRpcOutcome.NetworkOrTransientError(
                e.message ?: "Network unreachable while submitting stock adjustment."
            )
        } catch (e: Exception) {
            StockAdjustmentRpcOutcome.NetworkOrTransientError(
                e.message ?: "Unexpected transport error."
            )
        } finally {
            conn?.disconnect()
        }
    }

    private fun adjustViaProductsTableFallback(request: AndroidStockAdjustmentRpcRequest): StockAdjustmentRpcOutcome {
        if (request.productId <= 0L || request.deltaQty == 0) {
            return StockAdjustmentRpcOutcome.Rejected("INVALID_INPUT", "Valid product ID and non-zero delta required.")
        }
        val baseUrl = supabaseUrl.trimEnd('/')
        var getConn: HttpURLConnection? = null
        val currentQty: Int
        try {
            getConn = (URL("$baseUrl/rest/v1/products?id=eq.${request.productId}&select=id,qty&limit=1").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 7000
                readTimeout = 9000
                setRequestProperty("apikey", anonKey)
                setRequestProperty("Authorization", "Bearer ${SupabaseConfig.bearerToken(anonKey)}")
                setRequestProperty("Accept", "application/json")
            }
            val code = getConn.responseCode
            if (code == 401 || code == 403) {
                return StockAdjustmentRpcOutcome.AuthRequired("Authentication required (HTTP $code).")
            }
            if (code !in 200..299) {
                return StockAdjustmentRpcOutcome.NetworkOrTransientError("Could not read product stock (HTTP $code).")
            }
            val arr = org.json.JSONArray(getConn.inputStream.bufferedReader().use { it.readText() })
            if (arr.length() == 0) {
                return StockAdjustmentRpcOutcome.Rejected("PRODUCT_NOT_FOUND", "Product #${request.productId} not found.")
            }
            currentQty = arr.getJSONObject(0).optInt("qty", 0)
        } catch (e: IOException) {
            return StockAdjustmentRpcOutcome.NetworkOrTransientError(e.message ?: "Network unreachable.")
        } finally {
            getConn?.disconnect()
        }

        val rawTarget = currentQty + request.deltaQty
        val isClamped = rawTarget < 0
        val newQty = rawTarget.coerceAtLeast(0)
        val patchPayload = JSONObject().put("qty", newQty).toString()

        var patchConn: HttpURLConnection? = null
        return try {
            patchConn = (URL("$baseUrl/rest/v1/products?id=eq.${request.productId}").openConnection() as HttpURLConnection).apply {
                requestMethod = "PATCH"
                connectTimeout = 8000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("apikey", anonKey)
                setRequestProperty("Authorization", "Bearer ${SupabaseConfig.bearerToken(anonKey)}")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Prefer", "return=representation")
            }
            patchConn.outputStream.use { it.write(patchPayload.toByteArray(Charsets.UTF_8)) }

            val code = patchConn.responseCode
            val stream = if (code in 200..299) patchConn.inputStream else patchConn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code in 200..299) {
                val arr = runCatching { org.json.JSONArray(body) }.getOrNull()
                val serverQty = if (arr != null && arr.length() > 0) arr.getJSONObject(0).optInt("qty", newQty) else newQty
                StockAdjustmentRpcOutcome.CommittedOnline(
                    serverQty = serverQty,
                    previousQty = currentQty,
                    deltaQty = request.deltaQty,
                    isClamped = isClamped,
                    alreadyCommitted = false,
                )
            } else if (code == 401 || code == 403) {
                StockAdjustmentRpcOutcome.AuthRequired("Authentication required (HTTP $code).")
            } else if (code in 400..499 && code != 408 && code != 429) {
                StockAdjustmentRpcOutcome.Rejected("HTTP_$code", body.ifBlank { "Rejected (HTTP $code)." })
            } else {
                StockAdjustmentRpcOutcome.NetworkOrTransientError("Transient error adjusting stock (HTTP $code).")
            }
        } catch (e: IOException) {
            StockAdjustmentRpcOutcome.NetworkOrTransientError(e.message ?: "Network unreachable.")
        } finally {
            patchConn?.disconnect()
        }
    }

    companion object {
        fun buildRpcPayloadJson(request: AndroidStockAdjustmentRpcRequest): String {
            return JSONObject().apply {
                put("p_product_id", request.productId)
                put("p_delta_qty", request.deltaQty)
                put("p_reason_code", request.reasonCode)
                put("p_notes", request.notes)
                put("p_client_adjustment_id", request.clientAdjustmentId)
                put("p_idempotency_key", request.idempotencyKey)
                put("p_actor_id", request.actorId)
                put("p_actor_role", request.actorRole)
                put("p_device_id", request.deviceId)
                put("p_adjusted_at", request.adjustedAtIso)
            }.toString()
        }

        fun parseRpcResponse(httpStatus: Int, responseBody: String): StockAdjustmentRpcOutcome {
            if (httpStatus in 200..299) {
                return try {
                    val json = JSONObject(responseBody)
                    val status = json.optString("status", "")
                    when (status) {
                        "COMMITTED", "DUPLICATE_IGNORED" -> {
                            val serverQty = json.optInt("server_qty", 0)
                            val previousQty = json.optInt("previous_qty", serverQty)
                            val deltaQty = json.optInt("delta_qty", 0)
                            val isClamped = json.optBoolean("is_clamped", false)
                            val alreadyCommitted = json.optBoolean(
                                "already_committed",
                                status == "DUPLICATE_IGNORED"
                            )
                            StockAdjustmentRpcOutcome.CommittedOnline(
                                serverQty = serverQty,
                                previousQty = previousQty,
                                deltaQty = deltaQty,
                                isClamped = isClamped,
                                alreadyCommitted = alreadyCommitted,
                            )
                        }

                        "INVALID_INPUT", "PRODUCT_NOT_FOUND", "UNAUTHORIZED_ROLE" -> {
                            StockAdjustmentRpcOutcome.Rejected(
                                code = status,
                                message = json.optString("message", "Stock adjustment rejected ($status)."),
                            )
                        }

                        else -> StockAdjustmentRpcOutcome.NetworkOrTransientError(
                            "Unexpected server response status: $status"
                        )
                    }
                } catch (e: Exception) {
                    StockAdjustmentRpcOutcome.NetworkOrTransientError(
                        "Failed to parse stock adjustment response: ${e.message}"
                    )
                }
            }

            val errJson = runCatching { JSONObject(responseBody) }.getOrNull()
            val errCode = errJson?.optString("code", "").orEmpty()
            val errMsg = errJson?.optString("message", responseBody).orEmpty()

            if (errCode == "PGRST202" || (httpStatus == 404 && responseBody.contains("adjust_android_stock"))) {
                return StockAdjustmentRpcOutcome.RpcMissing(
                    "Supabase RPC adjust_android_stock is not deployed yet. Apply supabase/migrations/20261114_adjust_android_stock_rpc.sql."
                )
            }

            if (httpStatus == 401 || httpStatus == 403) {
                return StockAdjustmentRpcOutcome.AuthRequired(
                    errMsg.ifBlank { "Authentication required or session expired (HTTP $httpStatus)." }
                )
            }

            if (httpStatus in 400..499 && httpStatus != 408 && httpStatus != 429) {
                return StockAdjustmentRpcOutcome.Rejected(
                    code = errCode.ifBlank { "HTTP_$httpStatus" },
                    message = errMsg.ifBlank { "Server rejected stock adjustment (HTTP $httpStatus)." },
                )
            }

            return StockAdjustmentRpcOutcome.NetworkOrTransientError(
                errMsg.ifBlank { "Transient server error (HTTP $httpStatus)." }
            )
        }
    }
}
