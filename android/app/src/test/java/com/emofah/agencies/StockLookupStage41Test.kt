package com.emofah.agencies

import com.emofah.agencies.data.local.dao.ProductSaleMovementRow
import com.emofah.agencies.stock.LowStockLevel
import com.emofah.agencies.stock.RemoteServerSaleRow
import com.emofah.agencies.stock.StockLookupPolicy
import com.emofah.agencies.stock.StockLookupRepository
import com.emofah.agencies.stock.StockMovementKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class StockLookupStage41Test {

    @Test
    fun evaluateLowStockClassifiesOutOfStockLowAndHealthy() {
        assertEquals(LowStockLevel.OUT_OF_STOCK, StockLookupPolicy.evaluateLowStock(0))
        assertEquals(LowStockLevel.OUT_OF_STOCK, StockLookupPolicy.evaluateLowStock(-2))
        assertEquals(LowStockLevel.LOW_STOCK, StockLookupPolicy.evaluateLowStock(1))
        assertEquals(LowStockLevel.LOW_STOCK, StockLookupPolicy.evaluateLowStock(5))
        assertEquals(LowStockLevel.IN_STOCK, StockLookupPolicy.evaluateLowStock(6))
        assertEquals(LowStockLevel.LOW_STOCK, StockLookupPolicy.evaluateLowStock(8, threshold = 10))
    }

    @Test
    fun extractSupportedLocationReadsExplicitLocationOnlyWithoutFabrication() {
        assertNull(StockLookupPolicy.extractSupportedLocation(null))
        assertNull(StockLookupPolicy.extractSupportedLocation("Standard masking tape roll"))
        assertEquals(
            "Shelf B2",
            StockLookupPolicy.extractSupportedLocation("Masking tape\nLocation: Shelf B2"),
        )
        assertEquals(
            "Aisle 4 Rack 1",
            StockLookupPolicy.extractSupportedLocation("Shelf: Aisle 4 Rack 1"),
        )
    }

    @Test
    fun formatFreshnessLabelReportsAgeAndPendingOfflineDelta() {
        val now = 1_763_200_000_000L
        assertEquals(
            "Server updated 15m ago · -2 pending sale(s)",
            StockLookupPolicy.formatFreshnessLabel(
                serverUpdatedAtEpochMs = now - 15 * 60_000L,
                nowEpochMs = now,
                pendingDeltaQty = -2,
                locale = Locale.US,
            ),
        )
        assertEquals(
            "Server updated just now",
            StockLookupPolicy.formatFreshnessLabel(
                serverUpdatedAtEpochMs = now - 10_000L,
                nowEpochMs = now,
                pendingDeltaQty = 0,
                locale = Locale.US,
            ),
        )
    }

    @Test
    fun mergeMovementsDeduplicatesSyncedLocalSalesAndSplitsReversals() {
        val localRows = listOf(
            ProductSaleMovementRow(
                clientSaleId = "local-1",
                serverSaleId = 501L,
                quantity = 2,
                lineTotal = 300.0,
                saleDateIso = "2026-11-14T10:00:00Z",
                createdAtEpochMs = 1_763_114_400_000L,
                syncStatus = "SERVER_CONFIRMED",
                isReversed = true,
                reversalReason = "Wrong variant",
                actorId = "sup_anne",
            ),
            ProductSaleMovementRow(
                clientSaleId = "local-2",
                serverSaleId = null,
                quantity = 1,
                lineTotal = 150.0,
                saleDateIso = "2026-11-14T11:00:00Z",
                createdAtEpochMs = 1_763_118_000_000L,
                syncStatus = "LOCAL_PENDING",
                isReversed = false,
                reversalReason = null,
                actorId = "cashier_ben",
            ),
        )
        val remoteRows = listOf(
            // Duplicate of serverSaleId 501 -> must be deduplicated
            RemoteServerSaleRow(
                serverSaleId = 501L,
                qtySold = 2,
                totalPrice = 300.0,
                saleDateIso = "2026-11-14T10:00:00Z",
            ),
            // Separate desktop sale -> must be included
            RemoteServerSaleRow(
                serverSaleId = 499L,
                qtySold = 4,
                totalPrice = 600.0,
                saleDateIso = "2026-11-14T08:00:00Z",
            ),
        )

        val merged = StockLookupPolicy.mergeMovements(
            localRows = localRows,
            remoteSales = remoteRows,
            locale = Locale.US,
        )

        assertEquals(4, merged.size)
        assertTrue(merged.any { it.kind == StockMovementKind.LOCAL_REVERSAL && it.deltaQty == 2 })
        assertTrue(merged.any { it.kind == StockMovementKind.SERVER_SALE && it.deltaQty == -4 })
        assertEquals(1, merged.count { it.detail.contains("#501") && it.kind == StockMovementKind.LOCAL_SALE })
    }

    @Test
    fun parseRemoteSalesJsonSkipsInvalidRows() {
        val raw = """
            [
              {"id": 910, "qty_sold": 3, "total_price": 450.0, "sale_date": "2026-11-14T09:15:00Z"},
              {"id": 911, "qty_sold": 0, "total_price": 0.0, "sale_date": "2026-11-14T09:16:00Z"}
            ]
        """.trimIndent()
        val parsed = StockLookupRepository.parseRemoteSalesJson(raw)
        assertEquals(1, parsed.size)
        assertEquals(910L, parsed[0].serverSaleId)
        assertEquals(3, parsed[0].qtySold)
    }
}
