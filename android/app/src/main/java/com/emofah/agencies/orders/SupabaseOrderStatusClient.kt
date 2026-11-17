package com.emofah.agencies.orders

import com.emofah.agencies.data.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

interface OrderStatusGateway {
    suspend fun updateOrderStatus(request: AndroidOrderStatusRpcRequest): OrderStatusRpcOutcome
}

/**
 * Stage 6.2 — Supabase Order Status RPC Client.
 * Transitions online orders in shared `public.orders` via idempotent RPC
 * `POST /rest/v1/rpc/update_android_order_status` using HttpURLConnection.
 */
class SupabaseOrderStatusClient(
    private val supabaseUrl: String = SupabaseConfig.url,
    private val anonKey: String = SupabaseConfig.anonKey,
) : OrderStatusGateway {

    override suspend fun updateOrderStatus(request: AndroidOrderStatusRpcRequest): OrderStatusRpcOutcome =
        withContext(Dispatchers.IO) {
            if (!SupabaseConfig.isConfigured || supabaseUrl.isBlank() || anonKey.isBlank()) {
                return@withContext OrderStatusRpcOutcome.NetworkOrTransientError(
                    "Supabase credentials are not configured yet."
                )
            }

            val endpoint = "${supabaseUrl.trimEnd('/')}/rest/v1/rpc/update_android_order_status"
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
                OrderStatusRpcOutcome.NetworkOrTransientError(
                    e.message ?: "Network unreachable while updating order status."
                )
            } catch (e: Exception) {
                OrderStatusRpcOutcome.NetworkOrTransientError(
                    e.message ?: "Unexpected error updating order status."
                )
            } finally {
                conn?.disconnect()
            }
        }

    companion object {
        internal fun buildRpcPayloadJson(req: AndroidOrderStatusRpcRequest): String {
            return JSONObject().apply {
                put("p_client_update_id", req.clientUpdateId)
                put("p_idempotency_key", req.idempotencyKey)
                put("p_order_id", req.orderId)
                put("p_target_status", req.targetStatus.supabaseValue)
                put("p_reason", req.reason ?: JSONObject.NULL)
                put("p_actor_id", req.actorId)
                put("p_device_id", req.deviceId)
                put("p_updated_at", req.updatedAtIso)
            }.toString()
        }

        internal fun parseRpcResponse(statusCode: Int, rawBody: String): OrderStatusRpcOutcome {
            if (statusCode == 401 || statusCode == 403) {
                return OrderStatusRpcOutcome.AuthRequired(
                    "Authentication required to update orders (HTTP $statusCode)."
                )
            }
            if (statusCode == 404 || rawBody.contains("PGRST202")) {
                return OrderStatusRpcOutcome.RpcMissing(
                    "Supabase RPC update_android_order_status is not deployed yet; queued locally."
                )
            }
            if (statusCode >= 500) {
                return OrderStatusRpcOutcome.NetworkOrTransientError("Server unavailable (HTTP $statusCode).")
            }
            if (statusCode !in 200..299) {
                val msg = runCatching { JSONObject(rawBody).optString("message") }
                    .getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP $statusCode"
                return OrderStatusRpcOutcome.Rejected("HTTP_$statusCode", msg)
            }

            return runCatching {
                val json = JSONObject(rawBody)
                val status = json.optString("status", "").uppercase()
                when (status) {
                    "COMMITTED", "DUPLICATE_IGNORED" -> {
                        OrderStatusRpcOutcome.CommittedOnline(
                            serverEventId = json.optString("server_event_id", ""),
                            orderId = json.optString("order_id", ""),
                            newRawStatus = json.optString("new_status", "").ifBlank { "confirmed" },
                            alreadyCommitted = json.optBoolean("already_committed", status == "DUPLICATE_IGNORED"),
                        )
                    }

                    "INVALID_TRANSITION", "ORDER_NOT_FOUND", "INVALID_INPUT" -> {
                        val currentServerStatus = json.optString("current_status")
                            .takeIf { !json.isNull("current_status") && it.isNotBlank() && it != "null" }
                        OrderStatusRpcOutcome.Rejected(
                            code = status,
                            message = json.optString("message", "Order status update rejected."),
                            currentServerRawStatus = currentServerStatus,
                        )
                    }

                    else -> OrderStatusRpcOutcome.NetworkOrTransientError("Unexpected RPC status: $status")
                }
            }.getOrElse {
                OrderStatusRpcOutcome.NetworkOrTransientError("Invalid JSON response from update_android_order_status.")
            }
        }
    }
}
