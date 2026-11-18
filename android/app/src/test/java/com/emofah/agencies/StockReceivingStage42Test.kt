package com.emofah.agencies

import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueuePolicy
import com.emofah.agencies.data.sync.SyncQueueStatus
import com.emofah.agencies.stock.AndroidStockReceiptRpcRequest
import com.emofah.agencies.stock.StockReceiptRpcOutcome
import com.emofah.agencies.stock.StockReceivingValidator
import com.emofah.agencies.stock.SupabaseStockReceivingClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 4.2 — Unit Tests for Stock Receiving and Movements.
 * Tests input validation, RPC payload serialization, response parsing, and sync queue relinking.
 */
class StockReceivingStage42Test {

    private fun sampleProduct(id: Long = 101L, name: String = "Bamburi Cement 50kg") = ProductIdentity(
        id = id,
        name = name,
        baseName = name,
        variantLabel = "50kg",
        brandHint = null,
        category = "Hardware",
        sellPrice = 750.0,
        discountPrice = 0.0,
        buyPrice = 600.0,
        stockQty = 10,
        imageUrl = null,
        description = null,
        isFeatured = false,
        createdAt = "2026-11-14T00:00:00Z",
        isArchived = false,
    )

    @Test
    fun validatorRejectsInvalidProductAndQuantity() {
        val sample = sampleProduct()

        val v1 = StockReceivingValidator.validateDraft(
            product = null,
            quantity = 5,
            unitCost = null,
            supplier = null,
            invoiceRef = null,
        )
        assertFalse(v1.isValid)
        assertEquals("Select an existing product from the catalogue.", v1.errorMessage)

        val v2 = StockReceivingValidator.validateDraft(
            product = sample,
            quantity = 0,
            unitCost = null,
            supplier = null,
            invoiceRef = null,
        )
        assertFalse(v2.isValid)
        assertEquals("Received quantity must be at least 1 unit.", v2.errorMessage)

        val v3 = StockReceivingValidator.validateDraft(
            product = sample,
            quantity = 10,
            unitCost = -50.0,
            supplier = null,
            invoiceRef = null,
        )
        assertFalse(v3.isValid)
        assertEquals("Unit purchase cost cannot be negative.", v3.errorMessage)
    }

    @Test
    fun validatorSanitizesAndAcceptsCleanDraft() {
        val product = sampleProduct(id = 42L, name = "Crown Weatherguard 4L")
        val validation = StockReceivingValidator.validateDraft(
            product = product,
            quantity = 20,
            unitCost = 150.0,
            supplier = "  Crown Paints Ltd  ",
            invoiceRef = "  INV-2026-9901  ",
        )
        assertTrue(validation.isValid)
        assertEquals(20, validation.sanitizedQuantity)
        assertEquals(150.0, validation.sanitizedUnitCost!!, 0.001)
        assertEquals("Crown Paints Ltd", validation.sanitizedSupplier)
        assertEquals("INV-2026-9901", validation.sanitizedInvoiceRef)

        val sanitizedDraft = StockReceivingValidator.createDraft(
            product = product,
            quantity = 20,
            unitCost = 150.0,
            supplier = "  Crown Paints Ltd  ",
            invoiceRef = "  INV-2026-9901  ",
            updateBuyPrice = true,
        )
        assertEquals(42L, sanitizedDraft.productId)
        assertEquals(20, sanitizedDraft.quantityReceived)
        assertEquals(150.0, sanitizedDraft.unitCost!!, 0.001)
        assertEquals("Crown Paints Ltd", sanitizedDraft.supplier)
        assertEquals("INV-2026-9901", sanitizedDraft.invoiceRef)
        assertTrue(sanitizedDraft.updateBuyPrice)
        assertTrue(sanitizedDraft.clientReceiptId.startsWith("rcpt-"))
        assertTrue(sanitizedDraft.idempotencyKey.startsWith("receipt-"))
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
            invoiceRef = "BB-8899",
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
                "status": "COMMITTED",
                "server_qty": 35,
                "already_committed": false
            }
        """.trimIndent()

        val outcome = SupabaseStockReceivingClient.parseRpcResponse(200, successJson)
        assertTrue(outcome is StockReceiptRpcOutcome.CommittedOnline)
        val committed = outcome as StockReceiptRpcOutcome.CommittedOnline
        assertFalse(committed.alreadyCommitted)
        assertEquals(35, committed.serverQty)

        // Idempotent duplicate ACK
        val duplicateJson = """
            {
                "status": "DUPLICATE_IGNORED",
                "server_qty": 35,
                "already_committed": true
            }
        """.trimIndent()
        val dupOutcome = SupabaseStockReceivingClient.parseRpcResponse(200, duplicateJson)
        assertTrue(dupOutcome is StockReceiptRpcOutcome.CommittedOnline)
        val dupCommitted = dupOutcome as StockReceiptRpcOutcome.CommittedOnline
        assertTrue(dupCommitted.alreadyCommitted)
        assertEquals(35, dupCommitted.serverQty)

        // Error response
        val errorJson = """
            {
                "status": "PRODUCT_NOT_FOUND",
                "message": "Product 9999 not found"
            }
        """.trimIndent()
        val errorOutcome = SupabaseStockReceivingClient.parseRpcResponse(200, errorJson)
        assertTrue(errorOutcome is StockReceiptRpcOutcome.Rejected)
        val rejected = errorOutcome as StockReceiptRpcOutcome.Rejected
        assertEquals("PRODUCT_NOT_FOUND", rejected.code)
        assertEquals("Product 9999 not found", rejected.message)
    }

    @Test
    fun syncQueueRelinkingReplacesOfflineTemporaryProductIdInStockMovementPayload() {
        val originalPayload = """
            {
                "product_id": -1763114400000,
                "qty": 10,
                "client_receipt_id": "test-receipt-1",
                "idempotency_key": "idemp-1",
                "unit_cost": 500.0,
                "supplier": "Local Supplier",
                "invoice_ref": "INV-1",
                "invoice_photo_url": null,
                "actor_id": "staff-1",
                "device_id": "dev-1",
                "received_at": "2026-11-14T08:00:00Z",
                "update_buy_price": true
            }
        """.trimIndent()

        val originalEvent = SyncQueueEventEntity(
            eventId = "sync-stock-test-receipt-1",
            eventType = SyncEventType.STOCK_MOVEMENT,
            entityId = "test-receipt-1",
            dependencyEventId = "sync-draft-1",
            payloadJson = originalPayload,
            actorId = "staff-1",
            deviceId = "dev-1",
            status = SyncQueueStatus.QUEUED,
            createdAtEpochMs = 1_763_114_400_000L,
        )

        val relinkedPayload = SyncQueuePolicy.relinkStockMovementPayloadProductId(originalPayload, 777L)
        val relinkedReq = SyncQueuePolicy.parseStockMovementRpcRequest(
            originalEvent.copy(payloadJson = relinkedPayload)
        )
        assertNotNull(relinkedReq)
        assertEquals(777L, relinkedReq?.productId)
        assertEquals(10, relinkedReq?.quantity)
        assertEquals("test-receipt-1", relinkedReq?.clientReceiptId)
        assertEquals("idemp-1", relinkedReq?.idempotencyKey)
    }
}
