package com.emofah.agencies.financial

import com.emofah.agencies.data.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Synchronises captured M-Pesa transaction events and manual operational money events
 * to the shared `public.record_android_financial_event` Supabase RPC with idempotency
 * and duplicate-reference protection. Desktop retains final accounting classification control.
 */
class SupabaseFinancialEventClient(
    private val supabaseUrl: String = SupabaseConfig.url,
    private val anonKey: String = SupabaseConfig.anonKey,
) : FinancialEventGateway {

    override suspend fun submitFinancialEvent(
        request: AndroidFinancialEventRpcRequest,
    ): FinancialEventRpcOutcome = withContext(Dispatchers.IO) {
        if (!supabaseUrl.startsWith("https://") || anonKey.isBlank()) {
            return@withContext FinancialEventRpcOutcome.NetworkOrTransientError(
                "Supabase credentials not configured."
            )
        }

        val endpoint = "${supabaseUrl.trimEnd('/')}/rest/v1/rpc/record_android_financial_event"
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
            FinancialEventRpcOutcome.NetworkOrTransientError(
                e.message ?: "Network unreachable while syncing financial event."
            )
        } catch (e: Exception) {
            FinancialEventRpcOutcome.NetworkOrTransientError(
                e.message ?: "Unexpected transport error during financial event sync."
            )
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        fun buildRpcPayloadJson(request: AndroidFinancialEventRpcRequest): String {
            val parsedMetaObj = request.parsedMetadataJson?.let { raw ->
                runCatching { JSONObject(raw) }.getOrNull()
            }
            return JSONObject().apply {
                put("p_client_event_id", request.clientEventId)
                put("p_idempotency_key", request.idempotencyKey)
                put("p_event_type", request.eventType)
                put("p_direction", request.direction)
                put("p_amount", request.amount)
                put("p_currency", request.currency)
                put("p_reference_code", request.referenceCode ?: JSONObject.NULL)
                put("p_channel", request.channel ?: JSONObject.NULL)
                put("p_counterparty", request.counterparty ?: JSONObject.NULL)
                put("p_counterparty_account", request.counterpartyAccount ?: JSONObject.NULL)
                put("p_transaction_cost", request.transactionCost ?: JSONObject.NULL)
                put("p_new_balance", request.newBalance ?: JSONObject.NULL)
                put("p_occurred_text", request.occurredText ?: JSONObject.NULL)
                put("p_raw_evidence", request.rawEvidence ?: JSONObject.NULL)
                put("p_parsed_metadata", parsedMetaObj ?: JSONObject.NULL)
                put("p_parse_status", request.parseStatus)
                put("p_source", request.source)
                put("p_actor_id", request.actorId)
                put("p_device_id", request.deviceId)
                put("p_notes", request.notes ?: JSONObject.NULL)
                put("p_occurred_at", request.occurredAtIso)
            }.toString()
        }

        fun parseRpcResponse(httpStatus: Int, responseBody: String): FinancialEventRpcOutcome {
            if (httpStatus in 200..299) {
                return try {
                    val json = JSONObject(responseBody)
                    val status = json.optString("status", "")
                    when (status) {
                        "COMMITTED", "DUPLICATE_IGNORED" -> {
                            val serverEventId = json.optString("server_event_id", "")
                            val alreadyCommitted = json.optBoolean(
                                "already_committed",
                                status == "DUPLICATE_IGNORED",
                            )
                            FinancialEventRpcOutcome.CommittedOnline(
                                serverEventId = serverEventId,
                                alreadyCommitted = alreadyCommitted,
                            )
                        }

                        "INVALID_INPUT" -> FinancialEventRpcOutcome.Rejected(
                            code = status,
                            message = json.optString("message", "Financial event rejected ($status)."),
                        )

                        else -> FinancialEventRpcOutcome.NetworkOrTransientError(
                            "Unexpected server response status: $status"
                        )
                    }
                } catch (e: Exception) {
                    FinancialEventRpcOutcome.NetworkOrTransientError(
                        "Failed to parse financial event response: ${e.message}"
                    )
                }
            }

            val errJson = runCatching { JSONObject(responseBody) }.getOrNull()
            val errCode = errJson?.optString("code", "").orEmpty()
            val errMsg = errJson?.optString("message", responseBody).orEmpty()

            if (errCode == "PGRST202" || (httpStatus == 404 && responseBody.contains("record_android_financial_event"))) {
                return FinancialEventRpcOutcome.RpcMissing(
                    "Supabase RPC record_android_financial_event is not deployed yet. Apply supabase/migrations/20261116_record_android_financial_event_rpc.sql."
                )
            }

            if (httpStatus == 401 || httpStatus == 403) {
                return FinancialEventRpcOutcome.AuthRequired(
                    errMsg.ifBlank { "Authentication required or session expired (HTTP $httpStatus)." }
                )
            }

            if (httpStatus in 400..499 && httpStatus != 408 && httpStatus != 429) {
                return FinancialEventRpcOutcome.Rejected(
                    code = errCode.ifBlank { "HTTP_$httpStatus" },
                    message = errMsg.ifBlank { "Server rejected financial event (HTTP $httpStatus)." },
                )
            }

            return FinancialEventRpcOutcome.NetworkOrTransientError(
                errMsg.ifBlank { "Transient server error (HTTP $httpStatus)." }
            )
        }
    }
}
