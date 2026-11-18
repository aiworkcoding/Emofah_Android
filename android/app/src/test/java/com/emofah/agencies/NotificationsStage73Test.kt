package com.emofah.agencies

import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.sync.SyncPassSummary
import com.emofah.agencies.notifications.NotificationEvaluator
import com.emofah.agencies.orders.StoreOrderRecord
import com.emofah.agencies.orders.StoreOrderStatus
import com.emofah.agencies.stock.LowStockLevel
import com.emofah.agencies.stock.StockAlertItem
import com.emofah.agencies.stock.StockAlertSummary
import com.emofah.agencies.ui.navigation.EmofahDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationsStage73Test {

    private fun sampleOrder(id: String, num: String, status: StoreOrderStatus): StoreOrderRecord {
        return StoreOrderRecord(
            id = id,
            displayNumber = num,
            rawStatus = status.supabaseValue,
            status = status,
            totalPrice = 500.0,
            createdAtIso = "2026-11-16T10:00:00Z",
            createdAtEpochMs = 1794823200000L,
            items = emptyList(),
            customerName = null,
            customerPhone = null,
            deliveryNote = null,
        )
    }

    private fun sampleProduct(id: Long, name: String): ProductIdentity {
        return ProductIdentity(
            id = id,
            name = name,
            baseName = name,
            variantLabel = null,
            brandHint = null,
            category = "Stationery",
            sellPrice = 100.0,
            discountPrice = 0.0,
            buyPrice = 60.0,
            stockQty = 0,
            imageUrl = null,
            description = null,
            isFeatured = false,
            createdAt = "2026-11-16T00:00:00Z",
            isArchived = false,
        )
    }

    @Test
    fun findNewPendingOrders_detectsOnlyUnseenPendingOrders() {
        val orders = listOf(
            sampleOrder("ord-1", "#1001", StoreOrderStatus.PENDING),
            sampleOrder("ord-2", "#1002", StoreOrderStatus.FULFILLED),
            sampleOrder("ord-3", "#1003", StoreOrderStatus.PENDING),
        )

        val firstPass = NotificationEvaluator.findNewPendingOrders(
            orders = orders,
            previouslySeenOrderIds = setOf("ord-1"),
        )
        assertNotNull(firstPass)
        assertEquals(setOf("ord-3"), firstPass!!.newPendingOrderIds)
        assertEquals(listOf("#1003"), firstPass.displayNumbers)
        assertEquals(2, firstPass.totalPendingCount)

        val secondPass = NotificationEvaluator.findNewPendingOrders(
            orders = orders,
            previouslySeenOrderIds = setOf("ord-1", "ord-3"),
        )
        assertNull(secondPass)
    }

    @Test
    fun evaluateSyncAttention_deduplicatesSameSignatureAndResetsWhenCleared() {
        val summaryWithConflict = SyncPassSummary(
            attemptedCount = 3,
            syncedCount = 1,
            retryableCount = 0,
            conflictCount = 2,
            authBlockedCount = 0,
            deferredCount = 0,
            skippedOffline = false,
        )

        val (firstAlert, sig1) = NotificationEvaluator.evaluateSyncAttention(
            summary = summaryWithConflict,
            lastSignature = null,
        )
        assertNotNull(firstAlert)
        assertEquals(2, firstAlert!!.conflictCount)
        assertEquals("c=2:a=0", sig1)

        val (duplicateAlert, sig2) = NotificationEvaluator.evaluateSyncAttention(
            summary = summaryWithConflict,
            lastSignature = sig1,
        )
        assertNull(duplicateAlert)
        assertEquals(sig1, sig2)

        val cleanSummary = SyncPassSummary(
            attemptedCount = 3,
            syncedCount = 3,
            retryableCount = 0,
            conflictCount = 0,
            authBlockedCount = 0,
            deferredCount = 0,
            skippedOffline = false,
        )
        val (clearedAlert, clearedSig) = NotificationEvaluator.evaluateSyncAttention(
            summary = cleanSummary,
            lastSignature = sig2,
        )
        assertNull(clearedAlert)
        assertNull(clearedSig)
    }

    @Test
    fun evaluateStockAlertDiff_detectsOnlyNewlyUrgentItems() {
        val itemOut = StockAlertItem(
            product = sampleProduct(10L, "A4 Ream Paper"),
            availableQty = 0,
            serverQty = 0,
            pendingDeltaQty = 0,
            level = LowStockLevel.OUT_OF_STOCK,
            threshold = 5,
            hasSyncConflict = false,
        )
        val itemLow = StockAlertItem(
            product = sampleProduct(11L, "Blue Biro Box"),
            availableQty = 3,
            serverQty = 3,
            pendingDeltaQty = 0,
            level = LowStockLevel.LOW_STOCK,
            threshold = 5,
            hasSyncConflict = false,
        )
        val summary = StockAlertSummary(
            threshold = 5,
            isConfiguredFromServer = false,
            activeProductCount = 20,
            outOfStockItems = listOf(itemOut),
            lowStockItems = listOf(itemLow),
            conflictCount = 0,
        )

        val (alert1, activeKeys) = NotificationEvaluator.evaluateStockAlertDiff(
            alertSummary = summary,
            previouslyAlertedKeys = setOf("OUT:10"),
        )
        assertNotNull(alert1)
        assertEquals(1, alert1!!.newlyAlertedCount)
        assertEquals("Blue Biro Box", alert1.sampleProductName)
        assertTrue("OUT:10" in activeKeys && "LOW:11" in activeKeys)

        val (alert2, _) = NotificationEvaluator.evaluateStockAlertDiff(
            alertSummary = summary,
            previouslyAlertedKeys = activeKeys,
        )
        assertNull(alert2)
    }

    @Test
    fun resolveDestinationRoute_validatesKnownRoutes() {
        assertEquals(EmofahDestination.Orders, NotificationEvaluator.resolveDestinationRoute("orders"))
        assertEquals(EmofahDestination.Activity, NotificationEvaluator.resolveDestinationRoute("ACTIVITY"))
        assertEquals(EmofahDestination.Stock, NotificationEvaluator.resolveDestinationRoute("stock"))
        assertNull(NotificationEvaluator.resolveDestinationRoute("unknown_route"))
        assertNull(NotificationEvaluator.resolveDestinationRoute(null))
    }
}
