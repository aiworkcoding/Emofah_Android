package com.emofah.agencies.reporting

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class UnifiedTransactionType {
    ALL,
    SALE,
    SERVICE,
    PURCHASE,
    STOCK,
    MONEY,
    ORDER
}

enum class TransactionSyncFilter {
    ALL,
    CONFIRMED,
    PENDING,
    CONFLICT
}

data class TransactionFilterOptions(
    val searchQuery: String = "",
    val selectedType: UnifiedTransactionType = UnifiedTransactionType.ALL,
    val syncFilter: TransactionSyncFilter = TransactionSyncFilter.ALL,
    val dateFilterDays: Int = 30 // e.g. last 7, 30, or 90 days
)

data class UnifiedTransactionItem(
    val id: String,
    val type: UnifiedTransactionType,
    val title: String,
    val subtitle: String,
    val timestamp: Long,
    val amount: Double,
    val paymentMethod: String,
    val syncStatus: String, // SERVER_CONFIRMED, LOCAL_PENDING, CONFLICT, etc.
    val referenceCode: String,
    val isReversedOrVoid: Boolean,
    val rawDetails: String
) {
    val formattedDate: String
        get() = try {
            val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
            sdf.format(Date(timestamp))
        } catch (e: Exception) {
            "Recent"
        }

    val formattedAmount: String
        get() = String.format(Locale.getDefault(), "KES %,.2f", amount)
}

object TransactionHistoryPolicy {
    fun filterTransactions(
        items: List<UnifiedTransactionItem>,
        options: TransactionFilterOptions
    ): List<UnifiedTransactionItem> {
        val now = System.currentTimeMillis()
        val cutoffMillis = if (options.dateFilterDays > 0) {
            now - (options.dateFilterDays.toLong() * 24L * 60L * 60L * 1000L)
        } else {
            0L
        }

        return items.filter { item ->
            // Date filter
            if (item.timestamp < cutoffMillis) return@filter false

            // Type filter
            if (options.selectedType != UnifiedTransactionType.ALL && item.type != options.selectedType) {
                return@filter false
            }

            // Sync filter
            when (options.syncFilter) {
                TransactionSyncFilter.CONFIRMED -> {
                    if (!item.syncStatus.contains("CONFIRMED", ignoreCase = true) && !item.syncStatus.contains("SYNCED", ignoreCase = true)) {
                        return@filter false
                    }
                }
                TransactionSyncFilter.PENDING -> {
                    if (!item.syncStatus.contains("PENDING", ignoreCase = true) && !item.syncStatus.contains("LOCAL", ignoreCase = true)) {
                        return@filter false
                    }
                }
                TransactionSyncFilter.CONFLICT -> {
                    if (!item.syncStatus.contains("CONFLICT", ignoreCase = true)) {
                        return@filter false
                    }
                }
                TransactionSyncFilter.ALL -> {}
            }

            // Search query filter
            if (options.searchQuery.isNotBlank()) {
                val q = options.searchQuery.trim().lowercase(Locale.getDefault())
                val matchesTitle = item.title.lowercase(Locale.getDefault()).contains(q)
                val matchesSubtitle = item.subtitle.lowercase(Locale.getDefault()).contains(q)
                val matchesRef = item.referenceCode.lowercase(Locale.getDefault()).contains(q)
                val matchesId = item.id.lowercase(Locale.getDefault()).contains(q)
                if (!matchesTitle && !matchesSubtitle && !matchesRef && !matchesId) {
                    return@filter false
                }
            }

            true
        }.sortedByDescending { it.timestamp }
    }
}
