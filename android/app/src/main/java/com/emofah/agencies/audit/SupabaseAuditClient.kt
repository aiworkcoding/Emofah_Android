package com.emofah.agencies.audit

import com.emofah.agencies.data.SupabaseConfig
import com.emofah.agencies.data.local.entities.LocalAuditEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.sales.StaffRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed class AuditSyncOutcome {
    data class CommittedOnline(
        val confirmedAuditIds: List<String>,
        val acceptedCount: Int,
        val duplicateCount: Int,
    ) : AuditSyncOutcome()

    data class Rejected(
        val code: String,
        val message: String,
    ) : AuditSyncOutcome()

    data class RpcMissing(val message: String) : AuditSyncOutcome()

    data class AuthRequired(val message: String) : AuditSyncOutcome()

    data class NetworkOrTransientError(val message: String) : AuditSyncOutcome()
}

sealed class RemoteAuditFetchOutcome {
    data class Success(
        val scope: String,
        val records: List<AuditRecordUi>,
    ) : RemoteAuditFetchOutcome()

    data class AuthRequired(val message: String) : RemoteAuditFetchOutcome()

    data class RpcMissing(val message: String) : RemoteAuditFetchOutcome()

    data class Error(val message: String) : RemoteAuditFetchOutcome()
}

interface AuditGateway {
    suspend fun uploadAuditBatch(
        events: List<LocalAuditEventEntity>,
        actorRole: StaffRole,
    ): AuditSyncOutcome

    suspend fun fetchRemoteAudits(limit: Int = 100): RemoteAuditFetchOutcome
}

/**
 * Stage 9.3 — Supabase HTTP RPC client for immutable audit trail ingestion and role-scoped review.
 */
class SupabaseAuditClient(
    private val supabaseUrl: String = SupabaseConfig.url,
    private val anonKey: String = SupabaseConfig.anonKey,
) : AuditGateway {

    override suspend fun uploadAuditBatch(
        events: List<LocalAuditEventEntity>,
        actorRole: StaffRole,
    ): AuditSyncOutcome = withContext(Dispatchers.IO) {
        if (events.isEmpty()) {
            return@withContext AuditSyncOutcome.CommittedOnline(
                confirmedAuditIds = emptyList(),
                acceptedCount = 0,
                duplicateCount = 0,
            )
        }
        if (!supabaseUrl.startsWith("https://") || anonKey.isBlank()) {
            return@withContext AuditSyncOutcome.NetworkOrTransientError(
                "Supabase credentials not configured."
            )
        }

        val endpoint = "${supabaseUrl.trimEnd('/')}/rest/v1/rpc/record_android_audit_events"
        val payload = buildBatchPayloadJson(events, actorRole)

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 12000
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
            AuditSyncOutcome.NetworkOrTransientError(
                e.message ?: "Network unreachable while syncing audit trail."
            )
        } catch (e: Exception) {
            AuditSyncOutcome.NetworkOrTransientError(
                e.message ?: "Unexpected error syncing audit trail."
            )
        } finally {
            conn?.disconnect()
        }
    }

    override suspend fun fetchRemoteAudits(limit: Int): RemoteAuditFetchOutcome =
        withContext(Dispatchers.IO) {
            if (!supabaseUrl.startsWith("https://") || anonKey.isBlank()) {
                return@withContext RemoteAuditFetchOutcome.Error("Supabase credentials not configured.")
            }

            val endpoint = "${supabaseUrl.trimEnd('/')}/rest/v1/rpc/get_android_audit_events"
            val payload = JSONObject().apply {
                put("p_limit", limit.coerceIn(1, 300))
            }.toString()

            var conn: HttpURLConnection? = null
            try {
                conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 12000
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

                parseRemoteFetchResponse(httpStatus = code, responseBody = body)
            } catch (e: IOException) {
                RemoteAuditFetchOutcome.Error(e.message ?: "Network unreachable fetching cloud audits.")
            } catch (e: Exception) {
                RemoteAuditFetchOutcome.Error(e.message ?: "Unexpected error fetching cloud audits.")
            } finally {
                conn?.disconnect()
            }
        }

    companion object {
        fun buildBatchPayloadJson(
            events: List<LocalAuditEventEntity>,
            actorRole: StaffRole,
        ): String {
            val arr = JSONArray()
            events.forEach { event ->
                arr.put(
                    JSONObject().apply {
                        put("audit_id", event.auditId)
                        put("actor_id", event.actorId)
                        put("actor_role", actorRole.name)
                        put("device_id", event.deviceId)
                        put("operation", event.operation)
                        put("entity_type", event.entityType)
                        put("entity_id", event.entityId)
                        put("before_json", event.beforeJson ?: JSONObject.NULL)
                        put("after_json", event.afterJson ?: JSONObject.NULL)
                        put("reason", event.reason ?: JSONObject.NULL)
                        put("occurred_at_epoch_ms", event.occurredAtEpochMs)
                    }
                )
            }
            return JSONObject().apply {
                put("p_events", arr)
            }.toString()
        }

        fun parseRpcResponse(httpStatus: Int, responseBody: String): AuditSyncOutcome {
            if (httpStatus in 200..299) {
                return try {
                    val json = JSONObject(responseBody)
                    when (val status = json.optString("status", "")) {
                        "COMMITTED" -> {
                            val confirmedArr = json.optJSONArray("confirmed_audit_ids") ?: JSONArray()
                            val confirmedIds = buildList {
                                for (i in 0 until confirmedArr.length()) {
                                    val id = confirmedArr.optString(i, "")
                                    if (id.isNotBlank()) add(id)
                                }
                            }
                            AuditSyncOutcome.CommittedOnline(
                                confirmedAuditIds = confirmedIds,
                                acceptedCount = json.optInt("accepted_count", confirmedIds.size),
                                duplicateCount = json.optInt("duplicate_count", 0),
                            )
                        }
                        "UNAUTHORIZED_ROLE", "INVALID_INPUT" -> {
                            AuditSyncOutcome.Rejected(
                                code = status,
                                message = json.optString("message", "Audit sync rejected ($status)."),
                            )
                        }
                        else -> AuditSyncOutcome.NetworkOrTransientError(
                            "Unexpected audit RPC status: $status"
                        )
                    }
                } catch (e: Exception) {
                    AuditSyncOutcome.NetworkOrTransientError(
                        "Failed to parse audit acknowledgement: ${e.message}"
                    )
                }
            }

            val errJson = runCatching { JSONObject(responseBody) }.getOrNull()
            val errCode = errJson?.optString("code", "").orEmpty()
            val errMsg = errJson?.optString("message", responseBody).orEmpty()

            if (errCode == "PGRST202" || (httpStatus == 404 && responseBody.contains("record_android_audit_events"))) {
                return AuditSyncOutcome.RpcMissing(
                    "Supabase RPC record_android_audit_events is not deployed yet. Apply supabase/migrations/20261116_android_audit_events_rpc.sql."
                )
            }
            if (httpStatus == 401 || httpStatus == 403) {
                return AuditSyncOutcome.AuthRequired(
                    errMsg.ifBlank { "Authentication required for audit sync (HTTP $httpStatus)." }
                )
            }
            if (httpStatus in 400..499 && httpStatus != 408 && httpStatus != 429) {
                return AuditSyncOutcome.Rejected(
                    code = errCode.ifBlank { "HTTP_$httpStatus" },
                    message = errMsg.ifBlank { "Audit batch rejected (HTTP $httpStatus)." },
                )
            }
            return AuditSyncOutcome.NetworkOrTransientError(
                errMsg.ifBlank { "Transient error syncing audit events (HTTP $httpStatus)." }
            )
        }

        fun parseRemoteFetchResponse(httpStatus: Int, responseBody: String): RemoteAuditFetchOutcome {
            if (httpStatus in 200..299) {
                return try {
                    val json = JSONObject(responseBody)
                    when (val status = json.optString("status", "")) {
                        "OK" -> {
                            val scope = json.optString("scope", "SELF_ONLY")
                            val arr = json.optJSONArray("events") ?: JSONArray()
                            val items = buildList {
                                for (i in 0 until arr.length()) {
                                    val row = arr.optJSONObject(i) ?: continue
                                    val operation = row.optString("operation", "")
                                    val entityType = row.optString("entity_type", "")
                                    val auditId = row.optString("audit_id", "")
                                    if (auditId.isBlank()) continue
                                    add(
                                        AuditRecordUi(
                                            auditId = auditId,
                                            actorId = row.optString("actor_id", "android_staff"),
                                            actorRoleLabel = row.optString("actor_role", "")
                                                .takeIf { it.isNotBlank() && it != "null" },
                                            deviceId = row.optString("device_id", "android"),
                                            operation = operation,
                                            entityType = entityType,
                                            entityId = row.optString("entity_id", ""),
                                            beforeJson = row.optString("before_json", "")
                                                .takeIf { it.isNotBlank() && it != "null" },
                                            afterJson = row.optString("after_json", "")
                                                .takeIf { it.isNotBlank() && it != "null" },
                                            reason = row.optString("reason", "")
                                                .takeIf { it.isNotBlank() && it != "null" },
                                            syncStatus = SyncState.SERVER_CONFIRMED,
                                            occurredAtEpochMs = row.optLong("occurred_at_epoch_ms", 0L),
                                            category = AuditAccountabilityPolicy.classifyCategory(operation, entityType),
                                            isSensitive = AuditAccountabilityPolicy.isSensitiveOperation(operation),
                                            sourceBadge = AuditSourceBadge.CLOUD_CONFIRMED,
                                        )
                                    )
                                }
                            }
                            RemoteAuditFetchOutcome.Success(scope = scope, records = items)
                        }
                        "UNAUTHORIZED_ROLE" -> RemoteAuditFetchOutcome.AuthRequired(
                            json.optString("message", "Sign in required to inspect cloud audit records.")
                        )
                        else -> RemoteAuditFetchOutcome.Error("Unexpected audit fetch status: $status")
                    }
                } catch (e: Exception) {
                    RemoteAuditFetchOutcome.Error("Failed to parse cloud audits: ${e.message}")
                }
            }

            val errJson = runCatching { JSONObject(responseBody) }.getOrNull()
            val errCode = errJson?.optString("code", "").orEmpty()
            val errMsg = errJson?.optString("message", responseBody).orEmpty()

            if (errCode == "PGRST202" || (httpStatus == 404 && responseBody.contains("get_android_audit_events"))) {
                return RemoteAuditFetchOutcome.RpcMissing(
                    "Apply supabase/migrations/20261116_android_audit_events_rpc.sql to query cloud audit logs."
                )
            }
            if (httpStatus == 401 || httpStatus == 403) {
                return RemoteAuditFetchOutcome.AuthRequired(
                    errMsg.ifBlank { "Sign in required (HTTP $httpStatus)." }
                )
            }
            return RemoteAuditFetchOutcome.Error(
                errMsg.ifBlank { "Cloud audit fetch failed (HTTP $httpStatus)." }
            )
        }
    }
}
