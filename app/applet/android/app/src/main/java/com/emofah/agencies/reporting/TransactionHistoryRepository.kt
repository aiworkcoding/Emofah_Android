package com.emofah.agencies.reporting

import android.content.Context
import com.emofah.agencies.data.local.EmofahDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TransactionHistoryRepository(context: Context) {
    private val database = EmofahDatabase.getDatabase(context.applicationContext)
    private val saleDao = database.saleDao()
    private val productDao = database.productDao()
    private val syncDao = database.syncQueueDao()

    suspend fun fetchAllTransactions(): List<UnifiedTransactionItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<UnifiedTransactionItem>()

        try {
            // 1. Sales from Room
            val sales = saleDao.getAllSales()
            for (sale in sales) {
                list.add(
                    UnifiedTransactionItem(
                        id = "sale_${sale.saleId}",
                        type = UnifiedTransactionType.SALE,
                        title = "Sale #${sale.saleId} (Qty: ${sale.qtySold})",
                        subtitle = "Product ID: ${sale.productId}",
                        timestamp = sale.saleDateMs,
                        amount = sale.totalPrice,
                        paymentMethod = sale.paymentMethod,
                        syncStatus = sale.syncStatus,
                        referenceCode = sale.idempotencyKey,
                        isReversedOrVoid = sale.syncStatus.contains("REVERSED", true) || sale.syncStatus.contains("VOID", true),
                        rawDetails = "Sale ID: ${sale.saleId}, Product ID: ${sale.productId}, Qty: ${sale.qtySold}, Unit Price: KES ${sale.unitSellPrice}, Total: KES ${sale.totalPrice}, Payment: ${sale.paymentMethod}, Status: ${sale.syncStatus}"
                    )
                )
            }
        } catch (e: Exception) {
            // Ignore if tables are empty/missing
        }

        try {
            // 2. Sync queue events (purchases, service ops, orders, stock movements, financial events)
            val events = syncDao.getAllEvents()
            for (evt in events) {
                val tType = when (evt.eventType) {
                    "SALE", "SALE_REVERSAL" -> UnifiedTransactionType.SALE
                    "SERVICE_OPERATION" -> UnifiedTransactionType.SERVICE
                    "PURCHASE_RECEIPT" -> UnifiedTransactionType.PURCHASE
                    "STOCK_MOVEMENT", "STOCK_ADJUSTMENT", "STOCK_COUNT", "PRODUCT_DRAFT" -> UnifiedTransactionType.STOCK
                    "FINANCIAL_EVENT" -> UnifiedTransactionType.MONEY
                    "ORDER_UPDATE", "ORDER_TO_SALE" -> UnifiedTransactionType.ORDER
                    else -> UnifiedTransactionType.ALL
                }

                if (tType == UnifiedTransactionType.SALE) continue // already captured from sales table if recorded locally

                list.add(
                    UnifiedTransactionItem(
                        id = "sync_${evt.eventId}",
                        type = tType,
                        title = "${evt.eventType.replace('_', ' ')} (${evt.status})",
                        subtitle = "Queue ID: ${evt.eventId} (Attempts: ${evt.attempts})",
                        timestamp = evt.createdAtMs,
                        amount = 0.0, // default if payload doesn't specify
                        paymentMethod = "System",
                        syncStatus = evt.status,
                        referenceCode = evt.eventId,
                        isReversedOrVoid = evt.status.contains("CONFLICT", true),
                        rawDetails = "Sync Event ID: ${evt.eventId}, Type: ${evt.eventType}, Status: ${evt.status}, Payload: ${evt.payloadJson}"
                    )
                )
            }
        } catch (e: Exception) {
            // Ignore
        }

        list.sortedByDescending { it.timestamp }
    }
}
