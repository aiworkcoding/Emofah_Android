package com.emofah.agencies.stock

import com.emofah.agencies.data.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Stage 4.4:
 * Submits physical stock count sessions to the atomic `public.submit_physical_stock_count` Supabase RPC
 * using HttpURLConnection (matching all other Supabase RPC clients in the project).
 */
class SupabaseStockCountClient(
    private val supabaseUrl: String = SupabaseConfig.url,
    private val anonKey: String = SupabaseConfig.anonKey,
) : StockCountGateway {

    override suspend fun submitStockCount(draft: StockCountSessionDraft): StockCountRpcOutcome =
        withContext(Dispatchers.IO) {
            if (!supabaseUrl.startsWith("https://") || anonKey.isBlank()) {
                return@withContext StockCountRpcOutcome.NetworkOrTransientError(
                    "Supabase credentials not configured."
                )
            }

            val endpoint = "${supabaseUrl.trimEnd('/')}/rest/v1/rpc/submit_physical_stock_count"
            val payload = buildRpcPayloadJson(draft)

            var conn: HttpURLConnection? = null
            try {
                conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 15000
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
                StockCountRpcOutcome.NetworkOrTransientError(
                    e.message ?: "Network unreachable while submitting physical stock count."
                )
            } catch (e: Exception) {
                StockCountRpcOutcome.NetworkOrTransientError(
                    e.message ?: "Unexpected transport error during stock count."
                )
            } finally {
                conn?.disconnect()
            }
        }

    companion object {
        fun buildRpcPayloadJson(draft: StockCountSessionDraft): String {
            val jsonItems = JSONArray().apply {
                draft.items.forEach { item ->
                    put(
                        JSONObject().apply {
                            put("product_id", item.productId)
                            put("system_qty", item.systemQty)
                            put("counted_qty", item.countedQty)
                            put("reason_code", item.reasonCode)
                            put("note", item.note)
                            put("client_item_id", item.clientItemId)
                        }
                    )
                }
            }

            return JSONObject().apply {
                put("p_session_name", draft.sessionName)
                put("p_created_by", draft.createdBy)
                put("p_items", jsonItems)
                put("p_idempotency_key", draft.idempotencyKey)
            }.toString()
        }

        fun parseRpcResponse(httpStatus: Int, responseBody: String): StockCountRpcOutcome {
            if (httpStatus in 200..299) {
                return try {
                    val json = JSONObject(responseBody)
                    val success = json.optBoolean("success", false)
                    val status = json.optString("status", "")
                    if (success || status == "COMMITTED" || status == "DUPLICATE_IGNORED") {
                        val sessionId = json.optString("session_id", "")
                        val alreadyCommitted = json.optBoolean(
                            "already_committed",
                            status == "DUPLICATE_IGNORED",
                        )
                        StockCountRpcOutcome.CommittedOnline(
                            sessionId = sessionId,
                            alreadyCommitted = alreadyCommitted,
                        )
                    } else {
                        StockCountRpcOutcome.Rejected(
                            json.optString("message", "Server rejected physical stock count.")
                        )
                    }
                } catch (e: Exception) {
                    StockCountRpcOutcome.NetworkOrTransientError(
                        "Failed to parse stock count response: ${e.message}"
                    )
                }
            }

            val errJson = runCatching { JSONObject(responseBody) }.getOrNull()
            val errCode = errJson?.optString("code", "").orEmpty()
            val errMsg = errJson?.optString("message", responseBody).orEmpty()

            if (errCode == "PGRST202" || (httpStatus == 404 && responseBody.contains("submit_physical_stock_count"))) {
                return StockCountRpcOutcome.RpcMissing(
                    "Supabase RPC submit_physical_stock_count is not deployed yet. Apply supabase/migrations/20261114_physical_stock_count_rpc.sql."
                )
            }

            if (httpStatus == 401 || httpStatus == 403) {
                return StockCountRpcOutcome.AuthRequired(
                    errMsg.ifBlank { "Authentication required or session expired (HTTP $httpStatus)." }
                )
            }

            if (httpStatus in 400..499 && httpStatus != 408 && httpStatus != 429) {
                return StockCountRpcOutcome.Rejected(
                    errMsg.ifBlank { "Server rejected stock count (HTTP $httpStatus)." }
                )
            }

            return StockCountRpcOutcome.NetworkOrTransientError(
                errMsg.ifBlank { "Transient server error (HTTP $httpStatus)." }
            )
        }
    }
}
