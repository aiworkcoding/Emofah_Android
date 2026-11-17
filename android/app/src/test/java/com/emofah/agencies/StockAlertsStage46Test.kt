package com.emofah.agencies

import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.local.entities.LocalStockProjectionEntity
import com.emofah.agencies.stock.LowStockLevel
import com.emofah.agencies.stock.StockAlertPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StockAlertsStage46Test {

    private fun product(
        id: Long,
        name: String,
        stockQty: Int,
        isArchived: Boolean = false,
    ) = ProductIdentity(
        id = id,
        name = name,
        baseName = name,
        variantLabel = null,
        brandHint = null,
        category = "Stationery",
        sellPrice = 100.0,
        discountPrice = 0.0,
        buyPrice = 60.0,
        stockQty = stockQty,
        imageUrl = null,
        description = null,
        isFeatured = false,
        createdAt = null,
        isArchived = isArchived,
    )

    @Test
    fun evaluatesOutOfStockAndLowStockUsingProvisionalProjectionsAndExcludesArchived() {
        val products = listOf(
            product(id = 1L, name = "Blue Pen", stockQty = 10), // Offline sales drop it to 0
            product(id = 2L, name = "A4 Envelope", stockQty = 4),
            product(id = 3L, name = "Marker Black", stockQty = 2),
            product(id = 4L, name = "Counter Book 4Q", stockQty = 20),
            product(id = 5L, name = "Old Archived Item", stockQty = 0, isArchived = true),
        )
        val projections = listOf(
            LocalStockProjectionEntity(
                productId = 1L,
                serverQty = 10,
                serverUpdatedAtEpochMs = 1000L,
                pendingDeltaQty = -10,
                provisionalAvailableQty = 0,
                hasConflict = true,
            ),
            LocalStockProjectionEntity(
                productId = 2L,
                serverQty = 4,
                serverUpdatedAtEpochMs = 1000L,
                pendingDeltaQty = 0,
                provisionalAvailableQty = 4,
            ),
            LocalStockProjectionEntity(
                productId = 3L,
                serverQty = 2,
                serverUpdatedAtEpochMs = 1000L,
                pendingDeltaQty = 0,
                provisionalAvailableQty = 2,
            ),
        )

        val summary = StockAlertPolicy.buildSummary(
            products = products,
            projections = projections,
            threshold = 5,
            isConfiguredFromServer = true,
        )

        assertTrue(summary.isConfiguredFromServer)
        assertEquals(4, summary.activeProductCount)
        assertEquals(1, summary.outOfStockItems.size)
        assertEquals(2, summary.lowStockItems.size)
        assertEquals(3, summary.totalAlertsCount)
        assertEquals(1, summary.conflictCount)

        assertEquals(1L, summary.outOfStockItems[0].product.id)
        assertEquals(LowStockLevel.OUT_OF_STOCK, summary.outOfStockItems[0].level)
        assertTrue(summary.outOfStockItems[0].hasSyncConflict)

        // Most urgent low-stock item sorted ascending by quantity
        assertEquals("Marker Black", summary.lowStockItems[0].product.name)
        assertEquals(2, summary.lowStockItems[0].availableQty)
        assertEquals("A4 Envelope", summary.lowStockItems[1].product.name)
        assertEquals(4, summary.lowStockItems[1].availableQty)
    }

    @Test
    fun respectsConfiguredHigherThresholdFromServerSettings() {
        val products = listOf(
            product(id = 10L, name = "Ream Paper", stockQty = 8),
            product(id = 11L, name = "Stapler Pins", stockQty = 15),
        )

        val summaryDefault = StockAlertPolicy.buildSummary(products, emptyList(), threshold = 5)
        assertEquals(0, summaryDefault.totalAlertsCount)

        val summaryCustom = StockAlertPolicy.buildSummary(products, emptyList(), threshold = 10, isConfiguredFromServer = true)
        assertEquals(1, summaryCustom.lowStockItems.size)
        assertEquals("Ream Paper", summaryCustom.lowStockItems[0].product.name)
        assertEquals(10, summaryCustom.threshold)
    }
}
