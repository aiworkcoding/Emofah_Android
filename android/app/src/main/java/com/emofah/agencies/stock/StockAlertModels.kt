package com.emofah.agencies.stock

import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.local.entities.LocalStockProjectionEntity

data class StockAlertItem(
    val product: ProductIdentity,
    val availableQty: Int,
    val serverQty: Int,
    val pendingDeltaQty: Int,
    val level: LowStockLevel,
    val threshold: Int,
    val hasSyncConflict: Boolean,
)

data class StockAlertSummary(
    val threshold: Int = StockLookupPolicy.DEFAULT_LOW_STOCK_THRESHOLD,
    val isConfiguredFromServer: Boolean = false,
    val activeProductCount: Int = 0,
    val outOfStockItems: List<StockAlertItem> = emptyList(),
    val lowStockItems: List<StockAlertItem> = emptyList(),
    val conflictCount: Int = 0,
) {
    val totalAlertsCount: Int
        get() = outOfStockItems.size + lowStockItems.size

    val urgentItems: List<StockAlertItem>
        get() = outOfStockItems + lowStockItems
}

/**
 * Stage 4.6 — Stock Alerts Policy.
 * Evaluates low-stock and out-of-stock alerts strictly from real shared catalogue items
 * combined with Room provisional stock projections (no fabricated alerts or placeholder stock).
 */
object StockAlertPolicy {

    fun buildSummary(
        products: List<ProductIdentity>,
        projections: List<LocalStockProjectionEntity>,
        threshold: Int = StockLookupPolicy.DEFAULT_LOW_STOCK_THRESHOLD,
        isConfiguredFromServer: Boolean = false,
    ): StockAlertSummary {
        val safeThreshold = threshold.coerceAtLeast(1)
        val projectionsByProductId = projections.associateBy { it.productId }

        val activeProducts = products.filter { !it.isArchived }
        val outOfStock = mutableListOf<StockAlertItem>()
        val lowStock = mutableListOf<StockAlertItem>()
        var conflictCount = 0

        for (product in activeProducts) {
            val proj = projectionsByProductId[product.id]
            val serverQty = proj?.serverQty ?: product.stockQty
            val pendingDelta = proj?.pendingDeltaQty ?: 0
            val availableQty = proj?.provisionalAvailableQty ?: product.stockQty
            val hasConflict = proj?.hasConflict == true
            if (hasConflict) conflictCount += 1

            val level = StockLookupPolicy.evaluateLowStock(
                availableQty = availableQty,
                threshold = safeThreshold,
            )
            if (level == LowStockLevel.IN_STOCK) continue

            val item = StockAlertItem(
                product = product,
                availableQty = availableQty,
                serverQty = serverQty,
                pendingDeltaQty = pendingDelta,
                level = level,
                threshold = safeThreshold,
                hasSyncConflict = hasConflict,
            )
            when (level) {
                LowStockLevel.OUT_OF_STOCK -> outOfStock.add(item)
                LowStockLevel.LOW_STOCK -> lowStock.add(item)
                LowStockLevel.IN_STOCK -> Unit
            }
        }

        val sortedOut = outOfStock.sortedWith(
            compareBy<StockAlertItem> { it.availableQty }.thenBy { it.product.name.lowercase() }
        )
        val sortedLow = lowStock.sortedWith(
            compareBy<StockAlertItem> { it.availableQty }.thenBy { it.product.name.lowercase() }
        )

        return StockAlertSummary(
            threshold = safeThreshold,
            isConfiguredFromServer = isConfiguredFromServer,
            activeProductCount = activeProducts.size,
            outOfStockItems = sortedOut,
            lowStockItems = sortedLow,
            conflictCount = conflictCount,
        )
    }
}
