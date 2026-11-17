package com.emofah.agencies.orders

import com.emofah.agencies.data.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

interface OrderConversionGateway {
    suspend fun convertOrderToSale(request: AndroidOrderConversionRpcRequest): OrderConversionRpcOutcome
}

/**
 * Stage 6.3 — Supabase Order-to-Sale Conversion RPC Client.
 * Atomically converts an online order into `public.sales` rows + `public.products.qty` deductions
 * via `POST /rest/v1/rpc/convert_android_order_to_sale` using HttpURLConnection.
 */
class SupabaseOrderConversionClient(
    private val supabaseUrl: String = SupabaseConfig.url,
    private val anonKey: String = SupabaseConfig.anonKey,
) : OrderConversionGateway {

    override suspend fun convertOrderToSale(
        request: AndroidOrderConversionRpcRequest,
    ): OrderConversionRpcOutcome = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured || supabaseUrl.isBlank() || anonKey.isBlank()) {
            return@withContext OrderConversionRpcOutcome.NetworkOrTransientError(
                "Supabase credentials are not configured yet."
            )
        }

        val endpoint = "${supabaseUrl.trimEnd('/')}/rest/v1/rpc/convert_android_order_to_sale"
        val payload = buildRpcPayloadJson(request)
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("apikey", anonKey)
                setRequestProperty("Authorization", "Bearer ${SupabaseConfig.bearerToken(anonKey)}")
            }

            conn.outputStream.use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            parseRpcResponse(code, body)
        } catch (e: IOException) {
            OrderConversionRpcOutcome.NetworkOrTransientError(
                e.message ?: "Network unreachable while converting order to sale."
            )
        } catch (e: Exception) {
            OrderConversionRpcOutcome.NetworkOrTransientError(
                e.message ?: "Unexpected error converting order to sale."
            )
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        internal fun buildRpcPayloadJson(req: AndroidOrderConversionRpcRequest): String {
            val itemsArr = JSONArray()
            for (line in req.items) {
                itemsArr.put(
                    JSONObject().apply {
                        put("product_id", line.productId)
                        put("qty", line.quantity)
                        put("line_total", line.lineTotal)
                    }
                )
            }
            return JSONObject().apply {
                put("p_client_conversion_id", req.clientConversionId)
                put("p_idempotency_key", req.idempotencyKey)
                put("p_order_id", req.orderId)
                put("p_items", itemsArr)
                put("p_payment_method", req.paymentMethod)
                put("p_actor_id", req.actorId)
                put("p_device_id", req.deviceId)
                put("p_converted_at", req.convertedAtIso)
            }.toString()
        }

        internal fun parseRpcResponse(statusCode: Int, rawBody: String): OrderConversionRpcOutcome {
            if (statusCode == 401 || statusCode == 403) {
                return OrderConversionRpcOutcome.AuthRequired(
                    "Authentication required to convert order to sale (HTTP $statusCode)."
                )
            }
            if (statusCode == 404 || rawBody.contains("PGRST202")) {
                return OrderConversionRpcOutcome.RpcMissing(
                    "Supabase RPC convert_android_order_to_sale is not deployed yet; queued locally."
                )
            }
            if (statusCode >= 500) {
                return OrderConversionRpcOutcome.NetworkOrTransientError("Server unavailable (HTTP $statusCode).")
            }
            if (statusCode !in 200..299) {
                val msg = runCatching { JSONObject(rawBody).optString("message") }
                    .getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP $statusCode"
                return OrderConversionRpcOutcome.Rejected("HTTP_$statusCode", msg)
            }

            return runCatching {
                val json = JSONObject(rawBody)
                val status = json.optString("status", "").uppercase()
                when (status) {
                    "COMMITTED", "DUPLICATE_IGNORED" -> {
                        val updatedArr = json.optJSONArray("updated_products") ?: JSONArray()
                        val updated = mutableListOf<ConvertedOrderProductAck>()
                        for (i in 0 until updatedArr.length()) {
                            val obj = updatedArr.optJSONObject(i) ?: continue
                            val pid = obj.optLong("product_id", -1L).takeIf { it > 0L } ?: continue
                            val deducted = obj.optInt("qty_deducted", 0)
                            val qtyAfter = obj.optInt("server_qty_after", 0)
                            val saleId = if (obj.has("server_sale_id") && !obj.isNull("server_sale_id")) {
                                obj.optLong("server_sale_id", -1L).takeIf { it > 0L }
                            } else {
                                null
                            }
                            updated += ConvertedOrderProductAck(
                                productId = pid,
                                qtyDeducted = deducted,
                                serverQtyAfter = qtyAfter,
                                serverSaleId = saleId,
                            )
                        }
                        OrderConversionRpcOutcome.CommittedOnline(
                            serverConversionId = json.optString("server_conversion_id", ""),
                            orderId = json.optString("order_id", ""),
                            totalAmount = json.optDouble("total_amount", 0.0),
                            updatedProducts = updated,
                            alreadyCommitted = json.optBoolean("already_committed", status == "DUPLICATE_IGNORED"),
                        )
                    }

                    "ALREADY_CONVERTED" -> {
                        OrderConversionRpcOutcome.Rejected(
                            code = status,
                            message = json.optString("message", "Order has already been converted to a sale."),
                            alreadyConverted = true,
                        )
                    }

                    "INSUFFICIENT_STOCK", "PRODUCT_NOT_FOUND", "ORDER_NOT_FOUND", "INVALID_TRANSITION", "INVALID_INPUT" -> {
                        OrderConversionRpcOutcome.Rejected(
                            code = status,
                            message = json.optString("message", "Order conversion rejected."),
                            alreadyConverted = false,
                        )
                    }

                    else -> OrderConversionRpcOutcome.NetworkOrTransientError("Unexpected RPC status: $status")
                }
            }.getOrElse {
                OrderConversionRpcOutcome.NetworkOrTransientError("Invalid JSON response from convert_android_order_to_sale.")
            }
        }
    }
}
