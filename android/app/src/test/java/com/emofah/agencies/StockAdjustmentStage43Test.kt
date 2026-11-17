package com.emofah.agencies

import com.emofah.agencies.data.sync.SyncQueuePolicy
import com.emofah.agencies.sales.StaffRole
import com.emofah.agencies.stock.AndroidStockAdjustmentRpcRequest
import com.emofah.agencies.stock.StockAdjustmentDraft
import com.emofah.agencies.stock.StockAdjustmentReason
import com.emofah.agencies.stock.StockAdjustmentRpcOutcome
import com.emofah.agencies.stock.StockAdjustmentValidation
import com.emofah.agencies.stock.StockAdjustmentValidator
import com.emofah.agencies.stock.SupabaseStockAdjustmentClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 4.3 — Unit Tests for Stock Adjustments and Discrepancies.
 * Tests reason code validation, mandatory notes, supervisor role checks, RPC serialization,
 * response parsing, and sync queue payload relinking.
 */
class StockAdjustmentStage43Test {

    @Test
    fun validatorEnforcesReasonDirectionAndSupervisorAuth() {
        // Damaged stock requires negative delta
        val invalidDamagedPositive = StockAdjustmentDraft(
            productId = 101L,
            productName = "Paint 4L",
            currentAvailableQty = 10,
            deltaQty = 3, // should be negative for DAMAGED
            reason = StockAdjustmentReason.DAMAGED,
            notes = "Lid dented during delivery",
        )
        val v1 = StockAdjustmentValidator.validateDraft(invalidDamagedPositive, StaffRole.SUPERVISOR)
        assertTrue(v1 is StockAdjustmentValidation.Invalid)

        // Cashier role without supervisor approval requires supervisor sign-off
        val validDraft = StockAdjustmentDraft(
            productId = 101L,
            productName = "Paint 4L",
            currentAvailableQty = 10,
            deltaQty = -2,
            reason = StockAdjustmentReason.DAMAGED,
            notes = "Lid dented during delivery",
        )
        val cashierValidation = StockAdjustmentValidator.validateDraft(validDraft, StaffRole.CASHIER)
        assertTrue(cashierValidation is StockAdjustmentValidation.Invalid)
        assertEquals(
            "Stock adjustments require Supervisor or Owner-Manager authorization.",
            (cashierValidation as StockAdjustmentValidation.Invalid).message
        )

        // Supervisor role with valid negative delta and notes
        val supervisorValidation = StockAdjustmentValidator.validateDraft(validDraft, StaffRole.SUPERVISOR)
        assertTrue(supervisorValidation is StockAdjustmentValidation.Valid)
        val sanitized = (supervisorValidation as StockAdjustmentValidation.Valid).sanitizedDraft
        assertEquals(-2, sanitized.deltaQty)
        assertEquals("DAMAGED", sanitized.reason.code)
    }

    @Test
    fun validatorRejectsShortNotes() {
        val shortNotesDraft = StockAdjustmentDraft(
            productId = 101L,
            productName = "Paint 4L",
            currentAvailableQty = 10,
            deltaQty = 1,
            reason = StockAdjustmentReason.COUNT_CORRECTION_INCREASE,
            notes = "ok", // < 3 chars
        )
        val v = StockAdjustmentValidator.validateDraft(shortNotesDraft, StaffRole.OWNER_MANAGER)
        assertTrue(v is StockAdjustmentValidation.Invalid)
    }

    @Test
    fun buildRpcPayloadContainsAllIdempotencyAndAuditFields() {
        val request = AndroidStockAdjustmentRpcRequest(
            productId = 44L,
            deltaQty = -5,
            reasonCode = "EXPIRED",
            notes = "Chemicals past shelf life date",
            clientAdjustmentId = "adj-uuid-9999",
            idempotencyKey = "idemp-adj-9999",
            actorId = "supervisor-jane",
            actorRole = "SUPERVISOR",
            deviceId = "android-dev-1",
            adjustedAtIso = "2026-11-14T14:00:00Z",
        )

        val jsonString = SupabaseStockAdjustmentClient.buildRpcPayloadJson(request)
        val json = JSONObject(jsonString)

        assertEquals(44L, json.getLong("p_product_id"))
        assertEquals(-5, json.getInt("p_delta_qty"))
        assertEquals("EXPIRED", json.getString("p_reason_code"))
        assertEquals("Chemicals past shelf life date", json.getString("p_notes"))
        assertEquals("adj-uuid-9999", json.getString("p_client_adjustment_id"))
        assertEquals("idemp-adj-9999", json.getString("p_idempotency_key"))
        assertEquals("supervisor-jane", json.getString("p_actor_id"))
        assertEquals("SUPERVISOR", json.getString("p_actor_role"))
        assertEquals("android-dev-1", json.getString("p_device_id"))
        assertEquals("2026-11-14T14:00:00Z", json.getString("p_adjusted_at"))
    }

    @Test
    fun parseRpcResponseHandlesSuccessAndClamping() {
        val successJson = """
            {
                "status": "COMMITTED",
                "server_qty": 0,
                "previous_qty": 3,
                "delta_qty": -10,
                "already_committed": false,
                "is_clamped": true
            }
        """.trimIndent()

        val outcome = SupabaseStockAdjustmentClient.parseRpcResponse(200, successJson)
        assertTrue(outcome is StockAdjustmentRpcOutcome.CommittedOnline)
        val committed = outcome as StockAdjustmentRpcOutcome.CommittedOnline
        assertEquals(0, committed.serverQty)
        assertEquals(3, committed.previousQty)
        assertEquals(-10, committed.deltaQty)
        assertTrue(committed.isClamped)
        assertFalse(committed.alreadyCommitted)
    }

    @Test
    fun syncQueueRelinkingReplacesOfflineTemporaryProductIdInStockAdjustmentPayload() {
        val originalPayload = """
            {
                "p_product_id": -1763114400000,
                "p_delta_qty": -3,
                "p_reason_code": "DAMAGED",
                "p_notes": "Broken seal",
                "p_client_adjustment_id": "adj-1",
                "p_idempotency_key": "idemp-adj-1",
                "p_actor_id": "staff-1",
                "p_actor_role": "SUPERVISOR",
                "p_device_id": "dev-1",
                "p_adjusted_at": "2026-11-14T15:00:00Z"
            }
        """.trimIndent()

        val relinkedPayload = SyncQueuePolicy.relinkStockAdjustmentPayloadProductId(originalPayload, 999L)
        val relinkedReq = SyncQueuePolicy.parseStockAdjustmentRpcRequest(
            com.emofah.agencies.data.local.entities.SyncQueueEventEntity(
                eventId = "e1",
                eventType = "STOCK_ADJUSTMENT",
                entityId = "adj-1",
                idempotencyKey = "idemp-adj-1",
                payloadJson = relinkedPayload,
                status = "QUEUED",
                retryCount = 0,
                nextRetryEpochMs = 0L,
                actorId = "staff-1",
                deviceId = "dev-1",
                createdAtEpochMs = 0L,
            )
        )

        assertNotNull(relinkedReq)
        assertEquals(999L, relinkedReq?.productId)
        assertEquals(-3, relinkedReq?.deltaQty)
        assertEquals("DAMAGED", relinkedReq?.reasonCode)
    }
}
