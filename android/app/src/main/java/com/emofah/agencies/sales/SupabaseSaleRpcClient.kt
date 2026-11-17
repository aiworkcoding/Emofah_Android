package com.emofah.agencies.sales

import com.emofah.agencies.data.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed class SaleRpcOutcome {
    data class CommittedOnline(
        val serverSaleId: Long,
        val remainingQty: Int,
        val alreadyCommitted: Boolean,
    ) : SaleRpcOutcome()

    data class Rejected(
        val code: String,
        val message: String,
        val serverAvailableQty: Int? = null,
    ) : SaleRpcOutcome()

    data class RpcMissing(val message: String) : SaleRpcOutcome()

    data class AuthRequired(val message: String) : SaleRpcOutcome()

    data class NetworkOrTransientError(val message: String) : SaleRpcOutcome()
}

data class AndroidSaleRpcRequest(
    val productId: Long,
    val quantity: Int,
    val totalAmount: Double,
    val clientSaleId: String,
    val idempotencyKey: String,
    val paymentMethod: String,
    val actorId: String,
    val deviceId: String,
    val saleDateIso: String,
    val unitSellPrice: Double? = null,
    val originalSellPrice: Double? = null,
    val priceOverrideReason: String? = null,
    val actorRole: String = StaffRole.CASHIER.name,
) {
    val isPriceOverridden: Boolean
        get() = unitSellPrice != null &&
            originalSellPrice != null &&
            kotlin.math.abs(unitSellPrice - originalSellPrice) > PriceOverridePolicy.PRICE_EPSILON
}

interface SaleRpcGateway {
    suspend fun submitSale(request: AndroidSaleRpcRequest): SaleRpcOutcome
}

/**
 * Stage 2.2 (Action 2.2.2 & 2.2.4):
 * Calls the additive atomic PostgreSQL RPC `public.record_android_sale` over Supabase REST.
 * Preserves idempotency via `p_idempotency_key` & `p_client_sale_id`.
 */
class SupabaseSaleRpcClient(
    private val supabaseUrl: String = SupabaseConfig.url,
    private val anonKey: String = SupabaseConfig.anonKey,
) : SaleRpcGateway {

    override suspend fun submitSale(request: AndroidSaleRpcRequest): SaleRpcOutcome =
        withContext(Dispatchers.IO) {
            if (!supabaseUrl.startsWith("https://") || anonKey.isBlank()) {
                return@withContext SaleRpcOutcome.NetworkOrTransientError(
                    "Supabase credentials not configured in local.properties."
                )
            }

            val endpoint = "${supabaseUrl.trimEnd('/')}/rest/v1/rpc/record_android_sale"
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

                parseRpcResponse(httpStatus = code, responseBody = body)
            } catch (e: IOException) {
                SaleRpcOutcome.NetworkOrTransientError(
                    e.message ?: "Network unreachable while recording sale."
                )
            } catch (e: Exception) {
                SaleRpcOutcome.NetworkOrTransientError(
                    e.message ?: "Unexpected transport error."
                )
            } finally {
                conn?.disconnect()
            }
        }

    companion object {
        fun buildRpcPayloadJson(request: AndroidSaleRpcRequest): String {
            return JSONObject().apply {
                put("p_product_id", request.productId)
                put("p_qty", request.quantity)
                put("p_total", request.totalAmount)
                put("p_client_sale_id", request.clientSaleId)
                put("p_idempotency_key", request.idempotencyKey)
                put("p_payment_method", request.paymentMethod)
                put("p_actor_id", request.actorId)
                put("p_device_id", request.deviceId)
                put("p_sale_date", request.saleDateIso)
                if (request.isPriceOverridden) {
                    put("p_unit_sell_price", request.unitSellPrice)
                    put("p_original_sell_price", request.originalSellPrice)
                    put("p_price_override_reason", request.priceOverrideReason ?: JSONObject.NULL)
                    put("p_actor_role", request.actorRole)
                }
            }.toString()
        }

        fun parseRpcResponse(httpStatus: Int, responseBody: String): SaleRpcOutcome {
            if (httpStatus in 200..299) {
                return try {
                    val json = JSONObject(responseBody)
                    val status = json.optString("status", "")
                    when (status) {
                        "COMMITTED", "DUPLICATE_IGNORED" -> {
                            val saleId = json.optLong("server_sale_id", -1L)
                            val remainingQty = json.optInt("remaining_qty", 0)
                            val alreadyCommitted =
                                json.optBoolean("already_committed", status == "DUPLICATE_IGNORED")
                            if (saleId > 0L) {
                                SaleRpcOutcome.CommittedOnline(
                                    serverSaleId = saleId,
                                    remainingQty = remainingQty,
                                    alreadyCommitted = alreadyCommitted,
                                )
                            } else {
                                SaleRpcOutcome.NetworkOrTransientError(
                                    "Server returned incomplete sale acknowledgement."
                                )
                            }
                        }

                        "INSUFFICIENT_STOCK", "PRODUCT_NOT_FOUND", "INVALID_INPUT", "UNAUTHORIZED_ROLE" -> {
                            val available = if (json.has("available_qty")) {
                                json.optInt("available_qty")
                            } else {
                                null
                            }
                            SaleRpcOutcome.Rejected(
                                code = status,
                                message = json.optString(
                                    "message",
                                    "Sale rejected by server ($status)."
                                ),
                                serverAvailableQty = available,
                            )
                        }

                        else -> SaleRpcOutcome.NetworkOrTransientError(
                            "Unexpected server response status: $status"
                        )
                    }
                } catch (e: Exception) {
                    SaleRpcOutcome.NetworkOrTransientError(
                        "Failed to parse sale acknowledgement: ${e.message}"
                    )
                }
            }

            // Detect missing record_android_sale SQL migration (PostgREST PGRST202)
            val errJson = runCatching { JSONObject(responseBody) }.getOrNull()
            val errCode = errJson?.optString("code", "").orEmpty()
            val errMsg = errJson?.optString("message", responseBody).orEmpty()

            if (errCode == "PGRST202" || (httpStatus == 404 && responseBody.contains("record_android_sale"))) {
                return SaleRpcOutcome.RpcMissing(
                    "Supabase RPC record_android_sale is not deployed yet. Apply supabase/migrations/20261114_record_android_sale_rpc.sql."
                )
            }

            if (httpStatus == 401 || httpStatus == 403) {
                return SaleRpcOutcome.AuthRequired(
                    errMsg.ifBlank { "Authentication required or session expired (HTTP $httpStatus)." }
                )
            }

            if (httpStatus in 400..499 && httpStatus != 408 && httpStatus != 429) {
                return SaleRpcOutcome.Rejected(
                    code = errCode.ifBlank { "HTTP_$httpStatus" },
                    message = errMsg.ifBlank { "Server rejected sale request (HTTP $httpStatus)." },
                )
            }

            return SaleRpcOutcome.NetworkOrTransientError(
                errMsg.ifBlank { "Transient server error (HTTP $httpStatus)." }
            )
        }
    }
}
