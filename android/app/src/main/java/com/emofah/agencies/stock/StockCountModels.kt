package com.emofah.agencies.stock

import java.util.UUID

enum class StockCountReason(val code: String, val label: String) {
    CORRECT("CORRECT", "Stock matches system quantity"),
    SHORTAGE_MISSING("SHORTAGE_MISSING", "Shortage / Unexplained missing"),
    SHORTAGE_DAMAGED("SHORTAGE_DAMAGED", "Shortage / Damaged goods"),
    OVERAGE_FOUND("OVERAGE_FOUND", "Overage / Found stock"),
    INVENTORY_AUDIT("INVENTORY_AUDIT", "Periodic inventory audit count")
}

data class StockCountItemDraft(
    val productId: Int,
    val productName: String,
    val systemQty: Int,
    var countedQty: Int,
    var reasonCode: String = StockCountReason.CORRECT.code,
    var note: String = "",
    val clientItemId: String = UUID.randomUUID().toString()
) {
    val variance: Int
        get() = countedQty - systemQty

    val isDiscrepancy: Boolean
        get() = variance != 0
}

data class StockCountSessionDraft(
    val sessionName: String = "Physical Stock Count - " + java.time.LocalDate.now(),
    val createdBy: String = "Android Cashier",
    val items: List<StockCountItemDraft> = emptyList(),
    val idempotencyKey: String = UUID.randomUUID().toString()
) {
    val totalItemsCounted: Int
        get() = items.size

    val totalDiscrepancies: Int
        get() = items.count { it.isDiscrepancy }

    val netVariance: Int
        get() = items.sumOf { it.variance }
}

object StockCountValidator {
    fun validate(draft: StockCountSessionDraft): Result<Unit> {
        if (draft.sessionName.isBlank()) {
            return Result.failure(IllegalArgumentException("Session name cannot be blank."))
        }
        if (draft.items.isEmpty()) {
            return Result.failure(IllegalArgumentException("Cannot submit empty stock count session."))
        }
        for (item in draft.items) {
            if (item.countedQty < 0) {
                return Result.failure(IllegalArgumentException("Counted quantity for '${item.productName}' cannot be negative."))
            }
            if (item.isDiscrepancy && item.reasonCode == StockCountReason.CORRECT.code) {
                return Result.failure(IllegalArgumentException("Discrepancy found for '${item.productName}' but reason is set to match. Please select a reason."))
            }
            if (item.isDiscrepancy && item.reasonCode != StockCountReason.CORRECT.code && item.note.trim().length < 3) {
                return Result.failure(IllegalArgumentException("Please provide an explanation note (at least 3 characters) for variance in '${item.productName}'."))
            }
        }
        return Result.success(Unit)
    }
}
