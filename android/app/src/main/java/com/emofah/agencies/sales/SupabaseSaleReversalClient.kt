package com.emofah.agencies.sales

import com.emofah.agencies.data.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class AndroidSaleReversalRpcRequest(
    val clientSaleId: String,
    val serverSaleId: Long?,
    val productId: Long,
    val quantity: Int,
    val totalAmount: Double,
    val idempotencyKey: String,
    val reason: String,
    val paymentMethod: String,
    val actorId: String,
    val actorRole: String,
    val deviceId: String,
    val reversedAtIso: String,
)

sealed class SaleReversalRpcOutcome {
    data class CommittedOnline(
        val serverSaleId: Long?,
        val remainingQty: Int,
        val alreadyReversed: Boolean,
    ) : SaleReversalRpcOutcome()

    data class Rejected(
        val code: String,
        val message: String,
    ) : SaleReversalRpcOutcome()

    data class RpcMissing(val message: String) : SaleReversalRpcOutcome()

    data class AuthRequired(val message: String) : SaleReversalRpcOutcome()

    data class NetworkOrTransientError(val message: String) : SaleReversalRpcOutcome()
}

interface SaleReversalRpcGateway {
    suspend fun submitReversal(request: AndroidSaleReversalRpcRequest): SaleReversalRpcOutcome
}

/**
 * Stage 2.4 (Action 2.4.2):
 * Calls the additive idempotent PostgreSQL RPC `public.reverse_android_sale` over Supabase REST.
 * Preserves public.sales and financial auditability while atomically restoring product stock.
 */
class SupabaseSaleReversalClient(
    private val supabaseUrl: String = SupabaseConfig.url,
    private val anonKey: String = SupabaseConfig.anonKey,
) : SaleReversalRpcGateway {

    override suspend fun submitReversal(
        request: AndroidSaleReversalRpcRequest,
    ): SaleReversalRpcOutcome = withContext(Dispatchers.IO) {
        if (!supabaseUrl.startsWith("https://") || anonKey.isBlank()) {
            return@withContext SaleReversalRpcOutcome.NetworkOrTransientError(
                "Supabase credentials not configured in local.properties."
            )
        }

        val endpoint = "${supabaseUrl.trimEnd('/')}/rest/v1/rpc/reverse_android_sale"
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
            SaleReversalRpcOutcome.NetworkOrTransientError(
                e.message ?: "Network unreachable while reversing sale."
            )
        } catch (e: Exception) {
            SaleReversalRpcOutcome.NetworkOrTransientError(
                e.message ?: "Unexpected transport error during sale reversal."
            )
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        fun buildRpcPayloadJson(request: AndroidSaleReversalRpcRequest): String {
            return JSONObject().apply {
                put("p_client_sale_id", request.clientSaleId)
                put("p_server_sale_id", request.serverSaleId ?: JSONObject.NULL)
                put("p_product_id", request.productId)
                put("p_qty", request.quantity)
                put("p_total", request.totalAmount)
                put("p_idempotency_key", request.idempotencyKey)
                put("p_reason", request.reason)
                put("p_payment_method", request.paymentMethod)
                put("p_actor_id", request.actorId)
                put("p_actor_role", request.actorRole)
                put("p_device_id", request.deviceId)
                put("p_reversed_at", request.reversedAtIso)
            }.toString()
        }

        fun parseRpcResponse(httpStatus: Int, responseBody: String): SaleReversalRpcOutcome {
            if (httpStatus in 200..299) {
                return try {
                    val json = JSONObject(responseBody)
                    val status = json.optString("status", "")
                    when (status) {
                        "COMMITTED", "ALREADY_REVERSED" -> {
                            val serverSaleId = if (json.has("server_sale_id") && !json.isNull("server_sale_id")) {
                                json.optLong("server_sale_id").takeIf { it > 0L }
                            } else {
                                null
                            }
                            val remainingQty = json.optInt("remaining_qty", 0)
                            val alreadyReversed = json.optBoolean(
                                "already_reversed",
                                status == "ALREADY_REVERSED",
                            )
                            SaleReversalRpcOutcome.CommittedOnline(
                                serverSaleId = serverSaleId,
                                remainingQty = remainingQty,
                                alreadyReversed = alreadyReversed,
                            )
                        }

                        "UNAUTHORIZED_ROLE", "INVALID_INPUT", "PRODUCT_NOT_FOUND" -> {
                            SaleReversalRpcOutcome.Rejected(
                                code = status,
                                message = json.optString(
                                    "message",
                                    "Reversal rejected by server ($status)."
                                ),
                            )
                        }

                        else -> SaleReversalRpcOutcome.NetworkOrTransientError(
                            "Unexpected server reversal status: $status"
                        )
                    }
                } catch (e: Exception) {
                    SaleReversalRpcOutcome.NetworkOrTransientError(
                        "Failed to parse reversal acknowledgement: ${e.message}"
                    )
                }
            }

            val errJson = runCatching { JSONObject(responseBody) }.getOrNull()
            val errCode = errJson?.optString("code", "").orEmpty()
            val errMsg = errJson?.optString("message", responseBody).orEmpty()

            if (errCode == "PGRST202" || (httpStatus == 404 && responseBody.contains("reverse_android_sale"))) {
                return SaleReversalRpcOutcome.RpcMissing(
                    "Supabase RPC reverse_android_sale is not deployed yet. Apply supabase/migrations/20261114_reverse_android_sale_rpc.sql."
                )
            }

            if (httpStatus == 401 || httpStatus == 403) {
                return SaleReversalRpcOutcome.AuthRequired(
                    errMsg.ifBlank { "Authentication required or session expired (HTTP $httpStatus)." }
                )
            }

            if (httpStatus in 400..499 && httpStatus != 408 && httpStatus != 429) {
                return SaleReversalRpcOutcome.Rejected(
                    code = errCode.ifBlank { "HTTP_$httpStatus" },
                    message = errMsg.ifBlank { "Server rejected sale reversal (HTTP $httpStatus)." },
                )
            }

            return SaleReversalRpcOutcome.NetworkOrTransientError(
                errMsg.ifBlank { "Transient server error during reversal (HTTP $httpStatus)." }
            )
        }
    }
}
