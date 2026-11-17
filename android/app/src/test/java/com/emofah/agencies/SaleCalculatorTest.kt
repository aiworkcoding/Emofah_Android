package com.emofah.agencies

import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.local.entities.LocalStockProjectionEntity
import com.emofah.agencies.sales.PaymentMethod
import com.emofah.agencies.sales.SaleCalculator
import com.emofah.agencies.sales.StockPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaleCalculatorTest {

    private fun sampleProduct(sellPrice: Double = 150.0, stock: Int = 5) = ProductIdentity(
        id = 18L,
        name = "Masking tape (medium)",
        baseName = "Masking tape",
        variantLabel = "medium",
        brandHint = null,
        category = "Stationery",
        sellPrice = sellPrice,
        discountPrice = 0.0,
        buyPrice = 115.0,
        stockQty = stock,
        imageUrl = null,
        description = null,
        isFeatured = false,
        createdAt = null,
    )

    @Test
    fun rejectsZeroOrNegativeQuantityAndZeroPrice() {
        val product = sampleProduct()
        val zeroQty = SaleCalculator.validateAndCalculate(product, null, 0)
        assertFalse(zeroQty.isValidToRecord)
        assertNotNull(zeroQty.blockingReason)

        val negativeQty = SaleCalculator.validateAndCalculate(product, null, -2)
        assertFalse(negativeQty.isValidToRecord)

        val zeroPrice = SaleCalculator.validateAndCalculate(sampleProduct(sellPrice = 0.0), null, 1)
        assertFalse(zeroPrice.isValidToRecord)
    }

    @Test
    fun calculatesTotalAndEnforcesStockPolicies() {
        val product = sampleProduct(sellPrice = 150.0, stock = 3)
        val projection = LocalStockProjectionEntity(
            productId = 18L,
            serverQty = 5,
            serverUpdatedAtEpochMs = 1_000L,
            pendingDeltaQty = -2,
            provisionalAvailableQty = 3,
        )

        val valid = SaleCalculator.validateAndCalculate(product, projection, 2, StockPolicy.STRICT_BLOCK)
        assertTrue(valid.isValidToRecord)
        assertEquals(300.0, valid.totalAmount, 0.001)
        assertNull(valid.stockWarning)

        val blocked = SaleCalculator.validateAndCalculate(product, projection, 4, StockPolicy.STRICT_BLOCK)
        assertFalse(blocked.isValidToRecord)
        assertNotNull(blocked.blockingReason)

        val warned = SaleCalculator.validateAndCalculate(product, projection, 4, StockPolicy.ALLOW_WITH_WARNING)
        assertTrue(warned.isValidToRecord)
        assertEquals(600.0, warned.totalAmount, 0.001)
        assertNotNull(warned.stockWarning)
    }

    @Test
    fun draftReusesStableIdAndIdempotencyKeyAcrossRetries() {
        val product = sampleProduct()
        val first = SaleCalculator.createDraft(product, 2, PaymentMethod.MPESA)
        val retry = SaleCalculator.createDraft(
            product = product,
            quantity = 2,
            paymentMethod = PaymentMethod.MPESA,
            existingDraftId = first.clientSaleId,
            existingIdempotencyKey = first.idempotencyKey,
        )
        assertEquals(first.clientSaleId, retry.clientSaleId)
        assertEquals(first.idempotencyKey, retry.idempotencyKey)
        assertEquals(300.0, retry.totalAmount, 0.001)
    }
}
