package com.emofah.agencies.stock

import com.emofah.agencies.sales.StaffRole
import java.util.UUID

/**
 * Stage 4.3 — Stock Adjustment Reason Codes (Blueprint Part F).
 * Enforces structured accounting reasons for inventory discrepancies.
 */
enum class StockAdjustmentReason(
    val code: String,
    val label: String,
    val isDecrease: Boolean,
    val description: String,
) {
    DAMAGED(
        code = "DAMAGED",
        label = "Damaged Stock",
        isDecrease = true,
        description = "Damaged packaging, broken seals, dented paint cans, or torn bags",
    ),
    EXPIRED(
        code = "EXPIRED",
        label = "Expired / Spoiled",
        isDecrease = true,
        description = "Perishable paint chemicals, sealants, or adhesives past expiry",
    ),
    THEFT_OR_SHRINKAGE(
        code = "THEFT_OR_SHRINKAGE",
        label = "Theft / Loss / Shrinkage",
        isDecrease = true,
        description = "Missing inventory, unexplained shop shrinkage, or verified theft",
    ),
    INTERNAL_USE(
        code = "INTERNAL_USE",
        label = "Store / Shop Use",
        isDecrease = true,
        description = "Supplies consumed for store maintenance, demonstration, or customer sample",
    ),
    COUNT_CORRECTION_DECREASE(
        code = "COUNT_CORRECTION_DECREASE",
        label = "Count Discrepancy (-)",
        isDecrease = true,
        description = "Physical count revealed fewer items on shelf than registered in system",
    ),
    COUNT_CORRECTION_INCREASE(
        code = "COUNT_CORRECTION_INCREASE",
        label = "Count Discrepancy (+)",
        isDecrease = false,
        description = "Physical count revealed surplus stock or misplaced items found",
    );

    companion object {
        fun fromCode(code: String): StockAdjustmentReason? {
            val upper = code.trim().uppercase()
            return entries.firstOrNull { it.code == upper }
        }
    }
}

/**
 * Stage 4.3 Draft for user input.
 */
data class StockAdjustmentDraft(
    val productId: Long,
    val productName: String,
    val currentAvailableQty: Int,
    val deltaQty: Int,
    val reason: StockAdjustmentReason,
    val notes: String,
    val clientAdjustmentId: String = "adj-${UUID.randomUUID()}",
    val idempotencyKey: String = "idemp-adj-$clientAdjustmentId",
    val supervisorApprovedBy: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

sealed interface StockAdjustmentValidation {
    data class Valid(val sanitizedDraft: StockAdjustmentDraft) : StockAdjustmentValidation
    data class Invalid(val message: String) : StockAdjustmentValidation
}

object StockAdjustmentValidator {
    fun validateDraft(
        draft: StockAdjustmentDraft,
        staffRole: StaffRole = StaffRole.SUPERVISOR,
    ): StockAdjustmentValidation {
        if (draft.productId <= 0L) {
            return StockAdjustmentValidation.Invalid("A valid product must be selected for stock adjustment.")
        }
        if (draft.deltaQty == 0) {
            return StockAdjustmentValidation.Invalid("Quantity adjustment cannot be zero.")
        }

        // Direction check
        if (draft.reason.isDecrease && draft.deltaQty > 0) {
            return StockAdjustmentValidation.Invalid(
                "Reason '${draft.reason.label}' requires a negative stock adjustment (e.g. -${draft.deltaQty})."
            )
        }
        if (!draft.reason.isDecrease && draft.deltaQty < 0) {
            return StockAdjustmentValidation.Invalid(
                "Reason '${draft.reason.label}' requires a positive stock adjustment (e.g. +${-draft.deltaQty})."
            )
        }

        val cleanNotes = draft.notes.trim()
        if (cleanNotes.length < 3) {
            return StockAdjustmentValidation.Invalid(
                "An explanation note (at least 3 characters) is required for audit compliance."
            )
        }

        // Cashiers require supervisor sign-off for discretionary adjustments
        if (staffRole == StaffRole.CASHIER && draft.supervisorApprovedBy.isNullOrBlank()) {
            return StockAdjustmentValidation.Invalid(
                "Stock adjustments require Supervisor or Owner-Manager authorization."
            )
        }

        val sanitized = draft.copy(
            notes = cleanNotes,
            supervisorApprovedBy = draft.supervisorApprovedBy?.trim()?.takeIf { it.isNotBlank() },
        )
        return StockAdjustmentValidation.Valid(sanitized)
    }
}

data class AndroidStockAdjustmentRpcRequest(
    val productId: Long,
    val deltaQty: Int,
    val reasonCode: String,
    val notes: String,
    val clientAdjustmentId: String,
    val idempotencyKey: String,
    val actorId: String = "android_staff",
    val actorRole: String = "SUPERVISOR",
    val deviceId: String = "android",
    val adjustedAtIso: String = "",
)

sealed class StockAdjustmentRpcOutcome {
    data class CommittedOnline(
        val serverQty: Int,
        val previousQty: Int,
        val deltaQty: Int,
        val isClamped: Boolean,
        val alreadyCommitted: Boolean,
    ) : StockAdjustmentRpcOutcome()

    data class Rejected(
        val code: String,
        val message: String,
    ) : StockAdjustmentRpcOutcome()

    data class RpcMissing(val message: String) : StockAdjustmentRpcOutcome()

    data class AuthRequired(val message: String) : StockAdjustmentRpcOutcome()

    data class NetworkOrTransientError(val message: String) : StockAdjustmentRpcOutcome()
}

sealed class StockAdjustmentResult {
    data class SuccessOnline(
        val clientAdjustmentId: String,
        val updatedServerQty: Int,
        val previousServerQty: Int,
        val quantityDelta: Int,
        val reason: StockAdjustmentReason,
        val isClamped: Boolean,
        val alreadyCommitted: Boolean,
    ) : StockAdjustmentResult()

    data class SavedOffline(
        val clientAdjustmentId: String,
        val queuedEventId: String,
        val provisionalRemainingQty: Int,
        val quantityDelta: Int,
        val reason: StockAdjustmentReason,
        val reasonMessage: String,
    ) : StockAdjustmentResult()

    data class DuplicateIgnored(
        val clientAdjustmentId: String,
        val serverQty: Int,
    ) : StockAdjustmentResult()

    data class Rejected(
        val message: String,
    ) : StockAdjustmentResult()
}
