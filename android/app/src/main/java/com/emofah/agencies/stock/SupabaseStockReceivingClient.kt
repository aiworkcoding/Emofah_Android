package com.emofah.agencies.stock

import com.emofah.agencies.data.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

interface StockReceivingGateway {
    suspend fun submitStockReceipt(request: AndroidStockReceiptRpcRequest): StockReceiptRpcOutcome
}

/**
 * Stage 4.2:
 * Submits stock receiving transactions to the atomic `public.receive_android_stock` Supabase RPC.
 */
class SupabaseStockReceivingClient(
    private val supabaseUrl: String = SupabaseConfig.url,
    private val anonKey: String = SupabaseConfig.anonKey,
) : StockReceivingGateway {

    override suspend fun submitStockReceipt(
        request: AndroidStockReceiptRpcRequest,
    ): StockReceiptRpcOutcome = withContext(Dispatchers.IO) {
        if (!supabaseUrl.startsWith("https://") || anonKey.isBlank()) {
            return@withContext StockReceiptRpcOutcome.NetworkOrTransientError(
                "Supabase credentials not configured in local.properties."
            )
        }

        val endpoint = "${supabaseUrl.trimEnd('/')}/rest/v1/rpc/receive_android_stock"
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
            StockReceiptRpcOutcome.NetworkOrTransientError(
                e.message ?: "Network unreachable while submitting stock receipt."
            )
        } catch (e: Exception) {
            StockReceiptRpcOutcome.NetworkOrTransientError(
                e.message ?: "Unexpected transport error."
            )
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        fun buildRpcPayloadJson(request: AndroidStockReceiptRpcRequest): String {
            return JSONObject().apply {
                put("p_product_id", request.productId)
                put("p_qty", request.quantity)
                put("p_client_receipt_id", request.clientReceiptId)
                put("p_idempotency_key", request.idempotencyKey)
                put("p_unit_cost", request.unitCost ?: JSONObject.NULL)
                put("p_supplier", request.supplier ?: JSONObject.NULL)
                put("p_invoice_ref", request.invoiceRef ?: JSONObject.NULL)
                put("p_invoice_photo_url", request.invoicePhotoUrl ?: JSONObject.NULL)
                put("p_actor_id", request.actorId)
                put("p_device_id", request.deviceId)
                put("p_received_at", request.receivedAtIso)
                put("p_update_buy_price", request.updateBuyPrice)
            }.toString()
        }

        fun parseRpcResponse(httpStatus: Int, responseBody: String): StockReceiptRpcOutcome {
            if (httpStatus in 200..299) {
                return try {
                    val json = JSONObject(responseBody)
                    val status = json.optString("status", "")
                    when (status) {
                        "COMMITTED", "DUPLICATE_IGNORED" -> {
                            val serverQty = json.optInt("server_qty", 0)
                            val alreadyCommitted = json.optBoolean(
                                "already_committed",
                                status == "DUPLICATE_IGNORED"
                            )
                            StockReceiptRpcOutcome.CommittedOnline(
                                serverQty = serverQty,
                                alreadyCommitted = alreadyCommitted,
                            )
                        }

                        "INVALID_INPUT", "PRODUCT_NOT_FOUND" -> {
                            StockReceiptRpcOutcome.Rejected(
                                code = status,
                                message = json.optString("message", "Stock receipt rejected ($status)."),
                            )
                        }

                        else -> StockReceiptRpcOutcome.NetworkOrTransientError(
                            "Unexpected server response status: $status"
                        )
                    }
                } catch (e: Exception) {
                    StockReceiptRpcOutcome.NetworkOrTransientError(
                        "Failed to parse stock receipt response: ${e.message}"
                    )
                }
            }

            val errJson = runCatching { JSONObject(responseBody) }.getOrNull()
            val errCode = errJson?.optString("code", "").orEmpty()
            val errMsg = errJson?.optString("message", responseBody).orEmpty()

            if (errCode == "PGRST202" || (httpStatus == 404 && responseBody.contains("receive_android_stock"))) {
                return StockReceiptRpcOutcome.RpcMissing(
                    "Supabase RPC receive_android_stock is not deployed yet. Apply supabase/migrations/20261114_receive_android_stock_rpc.sql."
                )
            }

            if (httpStatus == 401 || httpStatus == 403) {
                return StockReceiptRpcOutcome.AuthRequired(
                    errMsg.ifBlank { "Authentication required or session expired (HTTP $httpStatus)." }
                )
            }

            if (httpStatus in 400..499 && httpStatus != 408 && httpStatus != 429) {
                return StockReceiptRpcOutcome.Rejected(
                    code = errCode.ifBlank { "HTTP_$httpStatus" },
                    message = errMsg.ifBlank { "Server rejected stock receipt (HTTP $httpStatus)." },
                )
            }

            return StockReceiptRpcOutcome.NetworkOrTransientError(
                errMsg.ifBlank { "Transient server error (HTTP $httpStatus)." }
            )
        }
    }
}
