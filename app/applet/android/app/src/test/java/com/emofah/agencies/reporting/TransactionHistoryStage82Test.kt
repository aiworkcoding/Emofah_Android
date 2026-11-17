package com.emofah.agencies.reporting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionHistoryStage82Test {

    @Test
    fun testTransactionFilteringAndSorting() {
        val now = System.currentTimeMillis()
        val item1 = UnifiedTransactionItem(
            id = "sale_1",
            type = UnifiedTransactionType.SALE,
            title = "Sale #1",
            subtitle = "Product A",
            timestamp = now - 1000L,
            amount = 500.0,
            paymentMethod = "CASH",
            syncStatus = "SERVER_CONFIRMED",
            referenceCode = "ref_1",
            isReversedOrVoid = false,
            rawDetails = "details 1"
        )

        val item2 = UnifiedTransactionItem(
            id = "service_1",
            type = UnifiedTransactionType.SERVICE,
            title = "Service Operation — Photocopy",
            subtitle = "5 pages",
            timestamp = now - 5000L,
            amount = 100.0,
            paymentMethod = "MPESA",
            syncStatus = "LOCAL_PENDING",
            referenceCode = "ref_2",
            isReversedOrVoid = false,
            rawDetails = "details 2"
        )

        val items = listOf(item2, item1)

        // Default filter (All, last 30 days)
        val filteredAll = TransactionHistoryPolicy.filterTransactions(items, TransactionFilterOptions())
        assertEquals(2, filteredAll.size)
        // Should be sorted by descending timestamp (item1 is newer than item2)
        assertEquals("sale_1", filteredAll[0].id)

        // Type filter SALE
        val filteredSales = TransactionHistoryPolicy.filterTransactions(
            items,
            TransactionFilterOptions(selectedType = UnifiedTransactionType.SALE)
        )
        assertEquals(1, filteredSales.size)
        assertEquals("sale_1", filteredSales[0].id)

        // Sync filter PENDING
        val filteredPending = TransactionHistoryPolicy.filterTransactions(
            items,
            TransactionFilterOptions(syncFilter = TransactionSyncFilter.PENDING)
        )
        assertEquals(1, filteredPending.size)
        assertEquals("service_1", filteredPending[0].id)

        // Search query filter
        val filteredSearch = TransactionHistoryPolicy.filterTransactions(
            items,
            TransactionFilterOptions(searchQuery = "Photocopy")
        )
        assertEquals(1, filteredSearch.size)
        assertEquals("service_1", filteredSearch[0].id)
    }
}
