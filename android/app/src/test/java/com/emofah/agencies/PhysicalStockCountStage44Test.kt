package com.emofah.agencies

import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueuePolicy
import com.emofah.agencies.stock.StockCountItemDraft
import com.emofah.agencies.stock.StockCountReason
import com.emofah.agencies.stock.StockCountRpcOutcome
import com.emofah.agencies.stock.StockCountSessionDraft
import com.emofah.agencies.stock.StockCountValidator
import com.emofah.agencies.stock.SupabaseStockCountClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalStockCountStage44Test {

    @Test
    fun testValidStockCountSessionPassesValidation() {
        val draft = StockCountSessionDraft(
            sessionName = "Weekly Count",
            createdBy = "Supervisor Test",
            items = listOf(
                StockCountItemDraft(
                    productId = 101,
                    productName = "Exercise Book 200pgs",
                    systemQty = 50,
                    countedQty = 48,
                    reasonCode = StockCountReason.SHORTAGE_DAMAGED.code,
                    note = "2 water damaged books",
                )
            ),
        )

        val result = StockCountValidator.validate(draft)
        assertTrue(result.isSuccess)
        assertEquals(-2, draft.netVariance)
        assertEquals(1, draft.totalDiscrepancies)
    }

    @Test
    fun testNegativeCountFailsValidation() {
        val draft = StockCountSessionDraft(
            sessionName = "Weekly Count",
            createdBy = "Supervisor Test",
            items = listOf(
                StockCountItemDraft(
                    productId = 101,
                    productName = "Exercise Book 200pgs",
                    systemQty = 50,
                    countedQty = -5,
                )
            ),
        )

        val result = StockCountValidator.validate(draft)
        assertTrue(result.isFailure)
    }

    @Test
    fun testDiscrepancyWithoutReasonOrNoteFailsValidation() {
        val draft = StockCountSessionDraft(
            sessionName = "Weekly Count",
            createdBy = "Supervisor Test",
            items = listOf(
                StockCountItemDraft(
                    productId = 101,
                    productName = "Exercise Book 200pgs",
                    systemQty = 50,
                    countedQty = 40,
                    reasonCode = StockCountReason.CORRECT.code,
                    note = "",
                )
            ),
        )

        val result = StockCountValidator.validate(draft)
        assertTrue(result.isFailure)
    }

    @Test
    fun parseQueuedStockCountPayloadRoundTripsCorrectly() {
        val payload = JSONObject().apply {
            put("p_session_name", "Shelf Check")
            put("p_created_by", "cashier-1")
            put("p_idempotency_key", "count-idem-1")
            put(
                "p_items",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("product_id", 15)
                            put("product_name", "Bic Pen Blue")
                            put("system_qty", 30)
                            put("counted_qty", 28)
                            put("reason_code", StockCountReason.SHORTAGE_MISSING.code)
                            put("note", "2 missing on shelf")
                            put("client_item_id", "item-15")
                        }
                    )
                },
            )
        }.toString()

        val evt = SyncQueueEventEntity(
            eventId = "sync-count-1",
            eventType = SyncEventType.STOCK_COUNT,
            entityId = "count-idem-1",
            payloadJson = payload,
            actorId = "cashier-1",
            deviceId = "dev-1",
            createdAtEpochMs = 1000L,
        )

        val parsed = SyncQueuePolicy.parseStockCountRpcRequest(evt)
        assertNotNull(parsed)
        assertEquals("Shelf Check", parsed!!.sessionName)
        assertEquals(1, parsed.items.size)
        assertEquals(28, parsed.items.first().countedQty)
        assertEquals(-2, parsed.items.first().variance)
    }

    @Test
    fun parsesSupabaseStockCountRpcOutcomesSafely() {
        val ok = SupabaseStockCountClient.parseRpcResponse(
            200,
            """{"success":true,"status":"COMMITTED","already_committed":false,"session_id":"sess-123"}""",
        )
        assertTrue(ok is StockCountRpcOutcome.CommittedOnline)
        assertEquals("sess-123", (ok as StockCountRpcOutcome.CommittedOnline).sessionId)

        val missing = SupabaseStockCountClient.parseRpcResponse(
            404,
            """{"code":"PGRST202","message":"Could not find the function public.submit_physical_stock_count"}""",
        )
        assertTrue(missing is StockCountRpcOutcome.RpcMissing)
    }
}
