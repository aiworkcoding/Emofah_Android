package com.emofah.agencies.services

import com.emofah.agencies.data.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

interface ServiceOperationGateway {
    suspend fun submitServiceOperation(request: AndroidServiceRpcRequest): ServiceRpcOutcome
}

/**
 * Section 4:
 * Submits cyber / business service operations and consumable usage to Supabase via
 * `POST /rest/v1/rpc/record_android_service_operation` using HttpURLConnection.
 */
class SupabaseServiceOperationClient(
    private val supabaseUrl: String = SupabaseConfig.url,
    private val anonKey: String = SupabaseConfig.anonKey,
) : ServiceOperationGateway {

    override suspend fun submitServiceOperation(request: AndroidServiceRpcRequest): ServiceRpcOutcome =
        withContext(Dispatchers.IO) {
            if (!SupabaseConfig.isConfigured || supabaseUrl.isBlank() || anonKey.isBlank()) {
                return@withContext ServiceRpcOutcome.NetworkOrTransientError(
                    "Supabase credentials are not configured yet."
                )
            }

            val endpoint = "${supabaseUrl.trimEnd('/')}/rest/v1/rpc/record_android_service_operation"
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
                ServiceRpcOutcome.NetworkOrTransientError(
                    e.message ?: "Network unreachable while recording service operation."
                )
            } catch (e: Exception) {
                ServiceRpcOutcome.NetworkOrTransientError(
                    e.message ?: "Unexpected error recording service operation."
                )
            } finally {
                conn?.disconnect()
            }
        }

    companion object {
        internal fun buildRpcPayloadJson(req: AndroidServiceRpcRequest): String {
            val consumablesArr = JSONArray()
            for (c in req.consumables) {
                consumablesArr.put(
                    JSONObject().apply {
                        put("consumable_name", c.consumableName)
                        put("usage_qty", c.usageQty)
                        put("usage_unit", c.usageUnit)
                        if (c.linkedProductId != null && c.linkedProductId > 0L) {
                            put("linked_product_id", c.linkedProductId)
                        } else {
                            put("linked_product_id", JSONObject.NULL)
                        }
                        put("stock_deduct_qty", c.stockDeductQty)
                    }
                )
            }

            return JSONObject().apply {
                put("p_client_service_id", req.clientServiceId)
                put("p_idempotency_key", req.idempotencyKey)
                put("p_service_type", req.serviceType.code)
                put("p_service_label", req.serviceLabel)
                put("p_quantity_or_duration", req.quantityOrDuration)
                put("p_unit_label", req.unitLabel)
                put("p_unit_price", req.unitPrice)
                put("p_total_revenue", req.totalRevenue)
                put("p_payment_method", req.paymentMethod)
                put("p_customer_note", req.customerNote ?: JSONObject.NULL)
                put("p_consumables", consumablesArr)
                put("p_actor_id", req.actorId)
                put("p_device_id", req.deviceId)
                put("p_performed_at", req.performedAtIso)
                put("p_discount_amount", req.discountAmount)
            }.toString()
        }

        internal fun parseRpcResponse(statusCode: Int, rawBody: String): ServiceRpcOutcome {
            if (statusCode == 401 || statusCode == 403) {
                return ServiceRpcOutcome.AuthRequired("Authentication required to record service (HTTP $statusCode).")
            }
            if (statusCode == 404 || rawBody.contains("PGRST202")) {
                return ServiceRpcOutcome.RpcMissing(
                    "Supabase RPC record_android_service_operation is not deployed yet; queued locally."
                )
            }
            if (statusCode >= 500) {
                return ServiceRpcOutcome.NetworkOrTransientError("Server unavailable (HTTP $statusCode).")
            }
            if (statusCode !in 200..299) {
                val msg = runCatching { JSONObject(rawBody).optString("message") }
                    .getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP $statusCode"
                return ServiceRpcOutcome.Rejected("HTTP_$statusCode", msg)
            }

            return runCatching {
                val json = JSONObject(rawBody)
                val status = json.optString("status", "").uppercase()
                when (status) {
                    "COMMITTED", "DUPLICATE_IGNORED" -> {
                        val serverId = json.optString("server_service_id", "")
                        val totalRevenue = json.optDouble("total_revenue", 0.0)
                        val alreadyCommitted = json.optBoolean("already_committed", status == "DUPLICATE_IGNORED")
                        val updatedArr = json.optJSONArray("updated_products") ?: JSONArray()
                        val updated = mutableListOf<UpdatedConsumableStockAck>()
                        for (i in 0 until updatedArr.length()) {
                            val obj = updatedArr.optJSONObject(i) ?: continue
                            val pid = obj.optLong("product_id", -1L).takeIf { it > 0L } ?: continue
                            val deducted = obj.optInt("qty_deducted", 0)
                            val qtyAfter = if (obj.has("server_qty_after") && !obj.isNull("server_qty_after")) {
                                obj.optInt("server_qty_after")
                            } else {
                                null
                            }
                            updated += UpdatedConsumableStockAck(
                                productId = pid,
                                qtyDeducted = deducted,
                                serverQtyAfter = qtyAfter,
                            )
                        }
                        ServiceRpcOutcome.CommittedOnline(
                            serverServiceId = serverId,
                            totalRevenue = totalRevenue,
                            alreadyCommitted = alreadyCommitted,
                            updatedProducts = updated,
                        )
                    }

                    "INVALID_INPUT", "PRODUCT_NOT_FOUND" -> {
                        ServiceRpcOutcome.Rejected(
                            code = status,
                            message = json.optString("message", "Service operation rejected."),
                        )
                    }

                    else -> ServiceRpcOutcome.NetworkOrTransientError("Unexpected RPC status: $status")
                }
            }.getOrElse {
                ServiceRpcOutcome.NetworkOrTransientError("Invalid JSON response from record_android_service_operation.")
            }
        }
    }
}
