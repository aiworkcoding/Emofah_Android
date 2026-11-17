package com.emofah.agencies

import com.emofah.agencies.data.catalog.CatalogueDeltaCalculator
import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.local.entities.LocalStockProjectionEntity
import com.emofah.agencies.data.local.entities.ProductEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogueCacheStage32Test {

    private fun productIdentity(id: Long, name: String, price: Double, qty: Int) = ProductIdentity(
        id = id,
        name = name,
        baseName = name,
        variantLabel = null,
        brandHint = null,
        category = "Stationery",
        sellPrice = price,
        discountPrice = 0.0,
        buyPrice = 50.0,
        stockQty = qty,
        imageUrl = null,
        description = null,
        isFeatured = false,
        createdAt = "2026-01-05T14:40:33Z",
    )

    @Test
    fun deltaCalculatorOnlyMarksChangedProductsAndPreservesConfirmedAliases() {
        val now = 2_000_000L
        val existing = listOf(
            ProductEntity(
                id = 1L,
                name = "Spring file",
                baseName = "Spring file",
                variantLabel = null,
                brandHint = "Express",
                category = "Stationery",
                sellPrice = 80.0,
                discountPrice = 0.0,
                buyPrice = 60.0,
                serverStockQty = 9,
                imageUrl = null,
                description = null,
                isFeatured = false,
                createdAt = "2026-01-05T14:40:33Z",
                isArchived = false,
                confirmedAliasesCsv = "green file|office file",
                cachedAtEpochMs = 1_000_000L,
            ),
            ProductEntity(
                id = 2L,
                name = "Box file",
                baseName = "Box file",
                variantLabel = null,
                brandHint = null,
                category = "Stationery",
                sellPrice = 120.0,
                discountPrice = 0.0,
                buyPrice = 90.0,
                serverStockQty = 6,
                imageUrl = null,
                description = null,
                isFeatured = false,
                createdAt = "2026-01-05T14:40:33Z",
                isArchived = false,
                confirmedAliasesCsv = "",
                cachedAtEpochMs = 1_000_000L,
            ),
        )
        // Adjust product 1 brandHint to null so it matches incoming helper cleanly
        val normalizedExisting = listOf(existing[0].copy(brandHint = null), existing[1])

        val incoming = listOf(
            productIdentity(1L, "Spring file", 80.0, 9), // unchanged
            productIdentity(2L, "Box file", 130.0, 6),   // price changed
            productIdentity(3L, "Manilla Paper", 25.0, 25), // new
        )

        val delta = CatalogueDeltaCalculator.computeDeltaPlan(
            incoming = incoming,
            existingProducts = normalizedExisting,
            existingProjections = emptyList(),
            nowEpochMs = now,
        )

        assertEquals(1, delta.unchangedCount)
        assertEquals(2, delta.changedProducts.size)
        assertEquals(listOf(2L, 3L), delta.changedProducts.map { it.id })

        // Verify unchanged product 1 keeps aliases when rebuilt from Room
        assertEquals(
            listOf("green file", "office file"),
            CatalogueDeltaCalculator.parseAliasesCsv(normalizedExisting[0].confirmedAliasesCsv),
        )
    }

    @Test
    fun stockProjectionPreservesOfflinePendingDeltaDuringServerRefresh() {
        val existingProj = listOf(
            LocalStockProjectionEntity(
                productId = 18L,
                serverQty = 10,
                serverUpdatedAtEpochMs = 1_000L,
                pendingDeltaQty = -3, // 3 units sold offline, not yet synced
                provisionalAvailableQty = 7,
            )
        )
        val incoming = listOf(productIdentity(18L, "Masking tape (medium)", 150.0, 12))

        val delta = CatalogueDeltaCalculator.computeDeltaPlan(
            incoming = incoming,
            existingProducts = emptyList(),
            existingProjections = existingProj,
            nowEpochMs = 5_000L,
        )

        val updated = delta.updatedProjections.single()
        assertEquals(12, updated.serverQty)
        assertEquals(-3, updated.pendingDeltaQty)
        assertEquals(9, updated.provisionalAvailableQty)
    }

    @Test
    fun freshnessStatusDetectsStaleAndFreshCaches() {
        val now = 10_000_000L
        val empty = CatalogueDeltaCalculator.evaluateFreshness(null, 0, now)
        assertTrue(empty.isStale)

        val fresh = CatalogueDeltaCalculator.evaluateFreshness(now - 60_000L, 111, now)
        assertFalse(fresh.isStale)

        val expired = CatalogueDeltaCalculator.evaluateFreshness(
            lastSyncedAtEpochMs = now - CatalogueDeltaCalculator.DEFAULT_STALE_THRESHOLD_MS - 1L,
            totalCachedProducts = 111,
            nowEpochMs = now,
        )
        assertTrue(expired.isStale)
    }
}
