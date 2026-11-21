package com.emofah.agencies

import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueuePolicy
import com.emofah.agencies.data.sync.SyncQueueStatus
import com.emofah.agencies.sales.PaymentMethod
import com.emofah.agencies.sales.PriceOverridePolicy
import com.emofah.agencies.sales.SaleCalculator
import com.emofah.agencies.sales.SaleTransactionRepository
import com.emofah.agencies.sales.StaffRole
import com.emofah.agencies.sales.SupabaseSaleRpcClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaleSpeedAndOverrideStage23Test {

    private fun sampleProduct() = ProductIdentity(
        id = 18L,
        name = "Masking tape (medium)",
        baseName = "Masking tape",
        variantLabel = "medium",
        brandHint = null,
        category = "Stationery",
        sellPrice = 150.0,
        discountPrice = 0.0,
        buyPrice = 95.0,
        stockQty = 10,
        imageUrl = null,
        description = null,
        isFeatured = false,
        createdAt = null,
    )

    @Test
    fun allStaffIncludingCashierCanOverridePriceWithValidReason() {
        assertTrue(PriceOverridePolicy.canOverridePrice(StaffRole.CASHIER))
        assertTrue(PriceOverridePolicy.canOverridePrice(StaffRole.SUPERVISOR))
        assertTrue(PriceOverridePolicy.canOverridePrice(StaffRole.OWNER_MANAGER))

        val cashierOverride = SaleCalculator.validateAndCalculate(
            product = sampleProduct(),
            stockProjection = null,
            rawQuantity = 2,
            overrideUnitPrice = 120.0,
            overrideReason = "Bulk discount",
            staffRole = StaffRole.CASHIER,
        )
        assertTrue(cashierOverride.isValidToRecord)
        assertTrue(cashierOverride.isPriceOverridden)
        assertEquals(240.0, cashierOverride.totalAmount, 0.001)

        // Unchanged price remains valid without an override reason
        val cashierNormal = SaleCalculator.validateAndCalculate(
            product = sampleProduct(),
            stockProjection = null,
            rawQuantity = 2,
            overrideUnitPrice = 150.0,
            overrideReason = null,
            staffRole = StaffRole.CASHIER,
        )
        assertTrue(cashierNormal.isValidToRecord)
        assertFalse(cashierNormal.isPriceOverridden)
        assertEquals(300.0, cashierNormal.totalAmount, 0.001)
    }

    @Test
    fun authorizedOverrideRequiresPositivePriceAndAuditReason() {
        val product = sampleProduct()

        val missingReason = SaleCalculator.validateAndCalculate(
            product = product,
            stockProjection = null,
            rawQuantity = 3,
            overrideUnitPrice = 130.0,
            overrideReason = "   ",
            staffRole = StaffRole.SUPERVISOR,
            requireOverrideReason = true,
        )
        assertFalse(missingReason.isValidToRecord)
        assertNotNull(missingReason.blockingReason)

        val zeroPrice = SaleCalculator.validateAndCalculate(
            product = product,
            stockProjection = null,
            rawQuantity = 3,
            overrideUnitPrice = 0.0,
            overrideReason = "Damaged box",
            staffRole = StaffRole.OWNER_MANAGER,
        )
        assertFalse(zeroPrice.isValidToRecord)

        val validOverride = SaleCalculator.validateAndCalculate(
            product = product,
            stockProjection = null,
            rawQuantity = 3,
            overrideUnitPrice = 135.0,
            overrideReason = "Bulk school order",
            staffRole = StaffRole.SUPERVISOR,
        )
        assertTrue(validOverride.isValidToRecord)
        assertTrue(validOverride.isPriceOverridden)
        assertEquals(135.0, validOverride.unitSellPrice, 0.001)
        assertEquals(150.0, validOverride.originalSellPrice, 0.001)
        assertEquals(405.0, validOverride.totalAmount, 0.001)
    }

    @Test
    fun priceOverrideArtifactsPreserveOriginalPriceReasonAuditAndRpcPayload() {
        val draft = SaleCalculator.createDraft(
            product = sampleProduct(),
            quantity = 4,
            paymentMethod = PaymentMethod.MPESA,
            existingDraftId = "sale-override-1",
            existingIdempotencyKey = "sale-idem-override-1",
            nowEpochMs = 1_763_120_000_000L,
            overrideUnitPrice = 125.0,
            priceOverrideReason = "Approved bulk discount",
        )
        assertTrue(draft.isPriceOverridden)
        assertEquals(150.0, draft.originalSellPrice, 0.001)
        assertEquals(125.0, draft.unitSellPrice, 0.001)
        assertEquals(500.0, draft.totalAmount, 0.001)

        val artifacts = SaleTransactionRepository.buildTransactionArtifacts(
            draft = draft,
            originalSellPrice = 150.0,
            actorId = "mgr_joy",
            deviceId = "android-pos-1",
            actorRole = StaffRole.OWNER_MANAGER,
        )

        assertEquals(125.0, artifacts.items[0].unitSellPrice, 0.001)
        assertEquals(150.0, artifacts.items[0].originalSellPrice, 0.001)
        assertEquals("Approved bulk discount", artifacts.items[0].priceOverrideReason)
        assertEquals("SALE_RECORDED_PRICE_OVERRIDE", artifacts.auditEvent.operation)
        assertEquals(150.0, JSONObject(artifacts.auditEvent.beforeJson!!).getDouble("original_sell_price"), 0.001)

        val rpcJson = JSONObject(SupabaseSaleRpcClient.buildRpcPayloadJson(artifacts.rpcRequest))
        assertEquals(500.0, rpcJson.getDouble("p_total"), 0.001)
        assertEquals(125.0, rpcJson.getDouble("p_unit_sell_price"), 0.001)
        assertEquals(150.0, rpcJson.getDouble("p_original_sell_price"), 0.001)
        assertEquals("Approved bulk discount", rpcJson.getString("p_price_override_reason"))
        assertEquals("OWNER_MANAGER", rpcJson.getString("p_actor_role"))

        // Queued offline override sale preserves override metadata on sync parse
        val parsedQueueReq = SyncQueuePolicy.parseSaleRpcRequest(
            SyncQueueEventEntity(
                eventId = artifacts.syncEvent.eventId,
                eventType = SyncEventType.SALE,
                entityId = artifacts.syncEvent.entityId,
                dependencyEventId = null,
                payloadJson = artifacts.syncEvent.payloadJson,
                actorId = "mgr_joy",
                deviceId = "android-pos-1",
                status = SyncQueueStatus.QUEUED,
                retryCount = 0,
                nextRetryAtEpochMs = 1_763_120_000_000L,
                createdAtEpochMs = 1_763_120_000_000L,
            )
        )
        assertNotNull(parsedQueueReq)
        assertTrue(parsedQueueReq!!.isPriceOverridden)
        assertEquals("Approved bulk discount", parsedQueueReq.priceOverrideReason)
        assertEquals("OWNER_MANAGER", parsedQueueReq.actorRole)
    }

    @Test
    fun defaultPriceSaleKeepsBackwardsCompatibleNineArgRpcPayload() {
        val draft = SaleCalculator.createDraft(
            product = sampleProduct(),
            quantity = 1,
            paymentMethod = PaymentMethod.CASH,
            existingDraftId = "sale-std-1",
            existingIdempotencyKey = "sale-idem-std-1",
        )
        val artifacts = SaleTransactionRepository.buildTransactionArtifacts(
            draft = draft,
            originalSellPrice = 150.0,
            actorId = "cashier_anne",
            deviceId = "android-pos-2",
            actorRole = StaffRole.CASHIER,
        )

        assertEquals("SALE_RECORDED", artifacts.auditEvent.operation)
        assertNull(artifacts.auditEvent.beforeJson)
        val rpcJson = JSONObject(SupabaseSaleRpcClient.buildRpcPayloadJson(artifacts.rpcRequest))
        assertFalse(rpcJson.has("p_unit_sell_price"))
        assertFalse(rpcJson.has("p_price_override_reason"))
    }
}
