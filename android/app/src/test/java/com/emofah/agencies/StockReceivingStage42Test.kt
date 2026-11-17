package com.emofah.agencies

import com.emofah.agencies.data.sync.SyncQueuePolicy
import com.emofah.agencies.stock.AndroidStockReceiptRpcRequest
import com.emofah.agencies.stock.StockReceiptDraft
import com.emofah.agencies.stock.StockReceiptRpcOutcome
import com.emofah.agencies.stock.StockReceivingValidation
import com.emofah.agencies.stock.StockReceivingValidator
import com.emofah.agencies.stock.SupabaseStockReceivingClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 4.2 — Unit Tests for Stock Receiving and Movements.
 * Tests input validation, RPC payload serialization, response parsing, and sync queue relinking.
 */
class StockReceivingStage42Test {

    @Test
    fun validatorRejectsInvalidProductAndQuantity() {
        val noProductDraft = StockReceiptDraft(
            productId = 0L,
            quantityReceived = 5,
        )
        val v1 = StockReceivingValidator.validateDraft(noProductDraft)
        assertTrue(v1 is StockReceivingValidation.Invalid)
        assertEquals("A valid product must be selected to receive stock.", (v1 as StockReceivingValidation.Invalid).message)

        val zeroQtyDraft = StockReceiptDraft(
            productId = 101L,
            quantityReceived = 0,
        )
        val v2 = StockReceivingValidator.validateDraft(zeroQtyDraft)
        assertTrue(v2 is StockReceivingValidation.Invalid)
        assertEquals("Quantity received must be at least 1 unit.", (v2 as StockReceivingValidation.Invalid).message)

        val negativeCostDraft = StockReceiptDraft(
            productId = 101L,
            quantityReceived = 10,
            unitCost = -50.0,
        )
        val v3 = StockReceivingValidator.validateDraft(negativeCostDraft)
        assertTrue(v3 is StockReceivingValidation.Invalid)
        assertEquals("Unit cost cannot be negative.", (v3 as StockReceivingValidation.Invalid).message)
    }

    @Test
    fun validatorSanitizesAndAcceptsCleanDraft() {
        val rawDraft = StockReceiptDraft(
            productId = 42L,
            quantityReceived = 20,
            unitCost = 150.0,
            supplier = "  Crown Paints Ltd  ",
            invoiceReference = "  INV-2026-9901  ",
            updateBuyPrice = true,
        )
        val validation = StockReceivingValidator.validateDraft(rawDraft)
        assertTrue(validation is StockReceivingValidation.Valid)
        val sanitized = (validation as StockReceivingValidation.Valid).sanitizedDraft

        assertEquals(42L, sanitized.productId)
        assertEquals(20, sanitized.quantityReceived)
        assertEquals(150.0, sanitized.unitCost)
        assertEquals("Crown Paints Ltd", sanitized.supplier)
        assertEquals("INV-2026-9901", sanitized.invoiceReference)
        assertTrue(sanitized.updateBuyPrice)
    }

    @Test
    fun buildRpcPayloadContainsAllIdempotencyAndAuditFields() {
        val request = AndroidStockReceiptRpcRequest(
            productId = 88L,
            quantity = 25,
            clientReceiptId = "rcpt-uuid-1234",
            idempotencyKey = "idemp-key-5678",
            unitCost = 280.0,
            supplier = "Bamburi Cement",
            invoiceReference = "BB-8899",
            invoicePhotoUrl = "https://example.com/inv.jpg",
            actorId = "staff-peter",
            deviceId = "android-device-99",
            receivedAtIso = "2026-11-14T12:00:00Z",
            updateBuyPrice = true,
        )

        val jsonString = SupabaseStockReceivingClient.buildRpcPayloadJson(request)
        val json = JSONObject(jsonString)

        assertEquals(88L, json.getLong("p_product_id"))
        assertEquals(25, json.getInt("p_qty"))
        assertEquals("rcpt-uuid-1234", json.getString("p_client_receipt_id"))
        assertEquals("idemp-key-5678", json.getString("p_idempotency_key"))
        assertEquals(280.0, json.getDouble("p_unit_cost"), 0.001)
        assertEquals("Bamburi Cement", json.getString("p_supplier"))
        assertEquals("BB-8899", json.getString("p_invoice_ref"))
        assertEquals("https://example.com/inv.jpg", json.getString("p_invoice_photo_url"))
        assertEquals("staff-peter", json.getString("p_actor_id"))
        assertEquals("android-device-99", json.getString("p_device_id"))
        assertEquals("2026-11-14T12:00:00Z", json.getString("p_received_at"))
        assertTrue(json.getBoolean("p_update_buy_price"))
    }

    @Test
    fun parseRpcResponseHandlesSuccessIdempotentReplayAndErrors() {
        val successJson = """
            {
                "success": true,
                "already_committed": false,
                "product_id": 88,
                "quantity_received": 25,
                "previous_server_qty": 10,
                "updated_server_qty": 35,
                "receipt_id": "rcpt-uuid-1234",
                "movement_id": 1024
            }
        """.trimIndent()

        val outcome = SupabaseStockReceivingClient.parseRpcResponse(successJson)
        assertTrue(outcome is StockReceiptRpcOutcome.CommittedOnline)
        val committed = outcome as StockReceiptRpcOutcome.CommittedOnline
        assertFalse(committed.alreadyCommitted)
        assertEquals(88L, committed.productId)
        assertEquals(25, committed.quantityReceived)
        assertEquals(10, committed.previousServerQty)
        assertEquals(35, committed.updatedServerQty)
        assertEquals("rcpt-uuid-1234", committed.receiptId)
        assertEquals(1024L, committed.movementId)

        // Idempotent duplicate ACK
        val duplicateJson = """
            {
                "success": true,
                "already_committed": true,
                "product_id": 88,
                "quantity_received": 25,
                "previous_server_qty": 35,
                "updated_server_qty": 35,
                "receipt_id": "rcpt-uuid-1234",
                "movement_id": 1024
            }
        """.trimIndent()
        val dupOutcome = SupabaseStockReceivingClient.parseRpcResponse(duplicateJson)
        assertTrue(dupOutcome is StockReceiptRpcOutcome.CommittedOnline)
        assertTrue((dupOutcome as StockReceiptRpcOutcome.CommittedOnline).alreadyCommitted)

        // Error response
        val errorJson = """
            {
                "success": false,
                "error": "Product 9999 not found"
            }
        """.trimIndent()
        val errorOutcome = SupabaseStockReceivingClient.parseRpcResponse(errorJson)
        assertTrue(errorOutcome is StockReceiptRpcOutcome.Failed)
        assertEquals("Product 9999 not found", (errorOutcome as StockReceiptRpcOutcome.Failed).message)
    }

    @Test
    fun syncQueueRelinkingReplacesOfflineTemporaryProductIdInStockMovementPayload() {
        val originalPayload = """
            {
                "p_product_id": -1763114400000,
                "p_qty": 10,
                "p_client_receipt_id": "test-receipt-1",
                "p_idempotency_key": "idemp-1",
                "p_unit_cost": 500.0,
                "p_supplier": "Local Supplier",
                "p_invoice_ref": "INV-1",
                "p_invoice_photo_url": null,
                "p_actor_id": "staff-1",
                "p_device_id": "dev-1",
                "p_received_at": "2026-11-14T08:00:00Z",
                "p_update_buy_price": true
            }
        """.trimIndent()

        val parsedReq = SyncQueuePolicy.parseStockMovementRpcRequest(originalPayload)
        assertNotNull(parsedReq)
        assertEquals(-1763114400000L, parsedReq?.productId)
        assertEquals(10, parsedReq?.quantity)

        val relinkedPayload = SyncQueuePolicy.relinkStockMovementPayloadProductId(originalPayload, 777L)
        val relinkedReq = SyncQueuePolicy.parseStockMovementRpcRequest(relinkedPayload)
        assertNotNull(relinkedReq)
        assertEquals(777L, relinkedReq?.productId)
        assertEquals(10, relinkedReq?.quantity)
        assertEquals("test-receipt-1", relinkedReq?.clientReceiptId)
    }
}
