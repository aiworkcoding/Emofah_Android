package com.emofah.agencies.auth

import com.emofah.agencies.audit.AuditAccountabilityPolicy
import com.emofah.agencies.audit.AuditCategory
import com.emofah.agencies.audit.AuditSourceBadge
import com.emofah.agencies.audit.AuditSyncFilter
import com.emofah.agencies.audit.AuditSyncOutcome
import com.emofah.agencies.audit.RemoteAuditFetchOutcome
import com.emofah.agencies.audit.SupabaseAuditClient
import com.emofah.agencies.data.local.entities.LocalAuditEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.sales.StaffRole
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditAccountabilityStage93Test {

    private fun sampleEntities(): List<LocalAuditEventEntity> = listOf(
        LocalAuditEventEntity(
            auditId = "aud-1",
            actorId = "cashier1@emofah.co.ke",
            deviceId = "DEV-01",
            operation = "SALE_RECORDED",
            entityType = "SALE",
            entityId = "sale-001",
            beforeJson = null,
            afterJson = """{"qty":2,"total":450,"actor_role":"CASHIER"}""",
            reason = null,
            syncStatus = SyncState.SERVER_CONFIRMED,
            occurredAtEpochMs = 1_700_000_010_000L,
        ),
        LocalAuditEventEntity(
            auditId = "aud-2",
            actorId = "supervisor@emofah.co.ke",
            deviceId = "DEV-01",
            operation = "SALE_REVERSED_ONLINE",
            entityType = "SALE",
            entityId = "sale-001",
            beforeJson = """{"is_reversed":false}""",
            afterJson = """{"is_reversed":true,"actor_role":"SUPERVISOR"}""",
            reason = "Customer returned damaged item",
            syncStatus = SyncState.LOCAL_PENDING,
            occurredAtEpochMs = 1_700_000_020_000L,
        ),
        LocalAuditEventEntity(
            auditId = "aud-3",
            actorId = "supervisor@emofah.co.ke",
            deviceId = "DEV-02",
            operation = "STOCK_ADJUSTMENT",
            entityType = "STOCK",
            entityId = "prod-42",
            beforeJson = """{"qty":10}""",
            afterJson = """{"qty":8,"actor_role":"SUPERVISOR"}""",
            reason = "Damaged in store",
            syncStatus = SyncState.LOCAL_PENDING,
            occurredAtEpochMs = 1_700_000_030_000L,
        ),
        LocalAuditEventEntity(
            auditId = "aud-4",
            actorId = "cashier1@emofah.co.ke",
            deviceId = "DEV-01",
            operation = "AUTH_SIGN_IN",
            entityType = "AUTH_SESSION",
            entityId = "u-1",
            beforeJson = null,
            afterJson = """{"staff_role":"CASHIER"}""",
            reason = "Staff sign-in",
            syncStatus = SyncState.LOCAL_PENDING,
            occurredAtEpochMs = 1_700_000_040_000L,
        ),
    )

    @Test
    fun auditAccountabilityPolicy_scopesByRoleAndFiltersCorrectly() {
        val mapped = AuditAccountabilityPolicy.mapLocalEntities(sampleEntities())

        // Supervisor sees full device audit trail
        val supervisorVisible = AuditAccountabilityPolicy.scopeForRole(
            records = mapped,
            staffRole = StaffRole.SUPERVISOR,
            currentActorId = "supervisor@emofah.co.ke",
        )
        assertEquals(4, supervisorVisible.size)

        // Cashier sees only their own attributed entries
        val cashierVisible = AuditAccountabilityPolicy.scopeForRole(
            records = mapped,
            staffRole = StaffRole.CASHIER,
            currentActorId = "cashier1@emofah.co.ke",
        )
        assertEquals(2, cashierVisible.size)
        assertTrue(cashierVisible.all { it.actorId == "cashier1@emofah.co.ke" })

        // Category + Sync + Search filtering
        val pendingStock = AuditAccountabilityPolicy.applyFilters(
            records = supervisorVisible,
            category = AuditCategory.STOCK,
            syncFilter = AuditSyncFilter.PENDING_ONLY,
            searchQuery = "damaged",
        )
        assertEquals(1, pendingStock.size)
        assertEquals("aud-3", pendingStock.first().auditId)
        assertTrue(pendingStock.first().isSensitive)

        val metrics = AuditAccountabilityPolicy.computeMetrics(supervisorVisible)
        assertEquals(4, metrics.totalVisible)
        assertEquals(3, metrics.pendingUploadCount)
        assertEquals(1, metrics.confirmedCount)
        assertEquals(3, metrics.sensitiveActionsCount) // SALE_REVERSED_ONLINE, STOCK_ADJUSTMENT, AUTH_SIGN_IN
        assertEquals(2, metrics.uniqueActorsCount)
    }

    @Test
    fun auditAccountabilityPolicy_mergesLocalAndRemoteWithoutDuplicates() {
        val local = AuditAccountabilityPolicy.mapLocalEntities(sampleEntities().take(2))
        val remoteOnly = local.first().copy(
            auditId = "aud-remote-99",
            actorId = "other_device_staff@emofah.co.ke",
            deviceId = "DEV-99",
            syncStatus = SyncState.SERVER_CONFIRMED,
            sourceBadge = AuditSourceBadge.CLOUD_CONFIRMED,
            occurredAtEpochMs = 1_700_000_099_000L,
        )
        val updatedLocalMatchingRemote = local[1].copy(
            syncStatus = SyncState.SERVER_CONFIRMED,
            sourceBadge = AuditSourceBadge.CLOUD_CONFIRMED,
            actorRoleLabel = "SUPERVISOR",
        )

        val merged = AuditAccountabilityPolicy.mergeLocalAndRemote(
            localRecords = local,
            remoteRecords = listOf(remoteOnly, updatedLocalMatchingRemote),
        )

        assertEquals(3, merged.size)
        assertEquals("aud-remote-99", merged.first().auditId)
        assertEquals(SyncState.SERVER_CONFIRMED, merged.first { it.auditId == "aud-2" }.syncStatus)
    }

    @Test
    fun supabaseAuditClient_buildsBatchPayloadAndParsesRpcResponses() {
        val entities = sampleEntities().take(2)
        val payloadJson = SupabaseAuditClient.buildBatchPayloadJson(entities, StaffRole.SUPERVISOR)
        val root = JSONObject(payloadJson)
        val arr = root.getJSONArray("p_events")
        assertEquals(2, arr.length())
        assertEquals("aud-1", arr.getJSONObject(0).getString("audit_id"))
        assertEquals("SUPERVISOR", arr.getJSONObject(0).getString("actor_role"))
        assertEquals("Customer returned damaged item", arr.getJSONObject(1).getString("reason"))

        // Committed online with duplicate count
        val committed = SupabaseAuditClient.parseRpcResponse(
            httpStatus = 200,
            responseBody = """
                {
                  "status": "COMMITTED",
                  "accepted_count": 1,
                  "duplicate_count": 1,
                  "confirmed_audit_ids": ["aud-1", "aud-2"]
                }
            """.trimIndent(),
        )
        assertTrue(committed is AuditSyncOutcome.CommittedOnline)
        val ok = committed as AuditSyncOutcome.CommittedOnline
        assertEquals(listOf("aud-1", "aud-2"), ok.confirmedAuditIds)
        assertEquals(1, ok.acceptedCount)
        assertEquals(1, ok.duplicateCount)

        // Missing migration PGRST202
        val missing = SupabaseAuditClient.parseRpcResponse(
            httpStatus = 404,
            responseBody = """{"code":"PGRST202","message":"Could not find the function public.record_android_audit_events"}""",
        )
        assertTrue(missing is AuditSyncOutcome.RpcMissing)

        // Auth required 401
        val authErr = SupabaseAuditClient.parseRpcResponse(
            httpStatus = 401,
            responseBody = """{"message":"JWT expired"}""",
        )
        assertTrue(authErr is AuditSyncOutcome.AuthRequired)

        // Remote fetch parsing
        val remoteFetch = SupabaseAuditClient.parseRemoteFetchResponse(
            httpStatus = 200,
            responseBody = """
                {
                  "status": "OK",
                  "scope": "STORE_WIDE",
                  "events": [
                    {
                      "audit_id": "aud-srv-1",
                      "actor_id": "owner@emofah.co.ke",
                      "actor_role": "OWNER_MANAGER",
                      "device_id": "DEV-OWNER",
                      "operation": "PHYSICAL_COUNT_RECONCILED",
                      "entity_type": "STOCK_COUNT",
                      "entity_id": "sess-12",
                      "reason": "Monthly audit",
                      "occurred_at_epoch_ms": 1700000123000
                    }
                  ]
                }
            """.trimIndent(),
        )
        assertTrue(remoteFetch is RemoteAuditFetchOutcome.Success)
        val fetched = remoteFetch as RemoteAuditFetchOutcome.Success
        assertEquals("STORE_WIDE", fetched.scope)
        assertEquals(1, fetched.records.size)
        assertFalse(fetched.records.first().syncStatus == SyncState.LOCAL_PENDING)
        assertTrue(fetched.records.first().isSensitive)
    }
}
