package com.emofah.agencies.sales

import com.emofah.agencies.auth.EmofahPermission
import com.emofah.agencies.auth.RolePermissionPolicy
import com.emofah.agencies.data.local.dao.LocalSaleWithItems
import com.emofah.agencies.data.local.entities.LocalSaleEntity
import com.emofah.agencies.data.local.entities.LocalSaleItemEntity
import com.emofah.agencies.data.local.entities.SyncState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

enum class SaleHistorySyncBadge(val label: String) {
    SERVER_CONFIRMED("Server Confirmed"),
    LOCAL_PENDING("Local Pending"),
    CONFLICT("Conflict · Review"),
    REVERSED("Reversed"),
    FAILED("Sync Attention"),
}

data class SaleHistoryRecordUi(
    val saleWithItems: LocalSaleWithItems,
    val primaryProductName: String,
    val totalQuantity: Int,
    val totalAmount: Double,
    val formattedTime: String,
    val formattedDateTime: String,
    val paymentLabel: String,
    val syncBadge: SaleHistorySyncBadge,
    val hasPriceOverride: Boolean,
    val isToday: Boolean,
    val canReverse: Boolean,
)

data class SaleReversalValidation(
    val isAllowed: Boolean,
    val cleanReason: String? = null,
    val errorMessage: String? = null,
)

sealed class ReverseSaleOutcome {
    data class ReversedOnline(
        val clientSaleId: String,
        val serverSaleId: Long?,
        val restoredQty: Int,
        val remainingServerQty: Int,
        val alreadyReversed: Boolean,
    ) : ReverseSaleOutcome()

    data class ReversedOfflineQueued(
        val clientSaleId: String,
        val queuedEventId: String,
        val restoredQty: Int,
        val provisionalRemainingQty: Int,
        val reason: String,
    ) : ReverseSaleOutcome()

    data class ReversedUnsyncedLocally(
        val clientSaleId: String,
        val restoredQty: Int,
        val provisionalRemainingQty: Int,
        val reason: String,
    ) : ReverseSaleOutcome()

    data class AlreadyReversed(
        val clientSaleId: String,
        val existingReason: String?,
    ) : ReverseSaleOutcome()

    data class Rejected(
        val message: String,
    ) : ReverseSaleOutcome()
}

/**
 * Stage 2.4 (Action 2.4.2):
 * Pure authorization & double-reversal policy guards for safe sale corrections.
 */
object SaleReversalPolicy {

    const val MIN_REASON_LENGTH = 3

    fun canReverseSales(role: StaffRole): Boolean {
        return RolePermissionPolicy.hasPermission(role, EmofahPermission.REVERSE_SALE)
    }

    fun buildReversalIdempotencyKey(clientSaleId: String): String {
        return "reversal-$clientSaleId"
    }

    fun validateReversalRequest(
        sale: LocalSaleEntity,
        role: StaffRole,
        reason: String?,
    ): SaleReversalValidation {
        if (sale.isReversed) {
            return SaleReversalValidation(
                isAllowed = false,
                errorMessage = "This sale has already been reversed and cannot be reversed twice.",
            )
        }

        if (!canReverseSales(role)) {
            return SaleReversalValidation(
                isAllowed = false,
                errorMessage = "Sale reversal requires Supervisor or Owner/Manager authorization.",
            )
        }

        val cleanReason = reason?.trim().orEmpty()
        if (cleanReason.length < MIN_REASON_LENGTH) {
            return SaleReversalValidation(
                isAllowed = false,
                errorMessage = "Enter a reason (at least $MIN_REASON_LENGTH characters) for reversing this sale.",
            )
        }

        return SaleReversalValidation(
            isAllowed = true,
            cleanReason = cleanReason,
            errorMessage = null,
        )
    }
}

/**
 * Stage 2.4 (Action 2.4.1):
 * Deterministic view mapping for today's recorded sales and transaction history.
 */
object SaleHistoryMapper {

    fun toSyncBadge(sale: LocalSaleEntity): SaleHistorySyncBadge {
        return when {
            sale.isReversed -> SaleHistorySyncBadge.REVERSED
            sale.syncStatus == SyncState.SERVER_CONFIRMED -> SaleHistorySyncBadge.SERVER_CONFIRMED
            sale.syncStatus == SyncState.CONFLICT -> SaleHistorySyncBadge.CONFLICT
            sale.syncStatus == SyncState.FAILED -> SaleHistorySyncBadge.FAILED
            else -> SaleHistorySyncBadge.LOCAL_PENDING
        }
    }

    fun formatPaymentMethod(raw: String): String {
        return PaymentMethod.entries.firstOrNull {
            it.name.equals(raw.trim(), ignoreCase = true)
        }?.label ?: raw.ifBlank { "Cash" }
    }

    fun hasPriceOverride(items: List<LocalSaleItemEntity>): Boolean {
        return items.any { item ->
            item.originalSellPrice > 0.0 &&
                abs(item.unitSellPrice - item.originalSellPrice) > PriceOverridePolicy.PRICE_EPSILON
        }
    }

    fun toUiModel(
        saleWithItems: LocalSaleWithItems,
        startOfTodayEpochMs: Long,
        locale: Locale = Locale.getDefault(),
    ): SaleHistoryRecordUi {
        val sale = saleWithItems.sale
        val items = saleWithItems.items
        val firstItem = items.firstOrNull()
        val extraCount = (items.size - 1).coerceAtLeast(0)
        val productName = when {
            firstItem == null -> "Recorded Sale"
            extraCount == 0 -> firstItem.productNameSnapshot
            else -> "${firstItem.productNameSnapshot} (+$extraCount more)"
        }
        val totalQty = items.sumOf { it.quantity }.coerceAtLeast(if (items.isEmpty()) 0 else 1)
        val timeFmt = SimpleDateFormat("HH:mm", locale)
        val dateTimeFmt = SimpleDateFormat("dd MMM · HH:mm", locale)
        val timestamp = Date(sale.createdAtEpochMs)

        return SaleHistoryRecordUi(
            saleWithItems = saleWithItems,
            primaryProductName = productName,
            totalQuantity = totalQty,
            totalAmount = sale.totalAmount,
            formattedTime = timeFmt.format(timestamp),
            formattedDateTime = dateTimeFmt.format(timestamp),
            paymentLabel = formatPaymentMethod(sale.paymentMethod),
            syncBadge = toSyncBadge(sale),
            hasPriceOverride = hasPriceOverride(items),
            isToday = sale.createdAtEpochMs >= startOfTodayEpochMs,
            canReverse = !sale.isReversed,
        )
    }

    fun partitionTodayAndEarlier(
        records: List<LocalSaleWithItems>,
        startOfTodayEpochMs: Long,
        locale: Locale = Locale.getDefault(),
    ): Pair<List<SaleHistoryRecordUi>, List<SaleHistoryRecordUi>> {
        val mapped = records.map { toUiModel(it, startOfTodayEpochMs, locale) }
        val today = mapped.filter { it.isToday }
        val earlier = mapped.filterNot { it.isToday }
        return today to earlier
    }
}
