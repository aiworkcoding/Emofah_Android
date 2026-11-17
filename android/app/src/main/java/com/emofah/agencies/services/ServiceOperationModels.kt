package com.emofah.agencies.services

import com.emofah.agencies.sales.PaymentMethod
import java.util.UUID

enum class ServiceOperationType(
    val code: String,
    val label: String,
    val unitLabel: String,
    val suggestedConsumables: List<String>,
) {
    PRINTING(
        code = "PRINTING",
        label = "Printing",
        unitLabel = "pages",
        suggestedConsumables = listOf("A4 Paper", "Printer Ink / Toner"),
    ),
    PHOTOCOPYING(
        code = "PHOTOCOPYING",
        label = "Photocopying",
        unitLabel = "copies",
        suggestedConsumables = listOf("A4 Paper", "Copier Toner"),
    ),
    SCANNING(
        code = "SCANNING",
        label = "Scanning",
        unitLabel = "pages",
        suggestedConsumables = emptyList(),
    ),
    INTERNET_BROWSING(
        code = "INTERNET_BROWSING",
        label = "Internet Browsing",
        unitLabel = "minutes",
        suggestedConsumables = emptyList(),
    ),
    EGOV_ASSISTANCE(
        code = "EGOV_ASSISTANCE",
        label = "E-Gov Assistance",
        unitLabel = "tasks",
        suggestedConsumables = listOf("A4 Paper"),
    ),
    ;

    companion object {
        fun fromCode(raw: String?): ServiceOperationType {
            val clean = raw?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.code == clean } ?: PRINTING
        }
    }
}

data class ServiceConsumableDraft(
    val localLineId: String = UUID.randomUUID().toString(),
    val consumableName: String,
    val usageQty: Double = 1.0,
    val usageUnit: String = "sheets",
    val linkedProductId: Long? = null,
    val linkedProductName: String? = null,
    val stockDeductQty: Int = 0,
)

data class ServiceOperationDraft(
    val clientServiceId: String = UUID.randomUUID().toString(),
    val idempotencyKey: String = "svc-${UUID.randomUUID()}",
    val serviceType: ServiceOperationType = ServiceOperationType.PRINTING,
    val quantityOrDuration: Int = 1,
    val unitPrice: Double = 0.0,
    val customTotalRevenue: Double? = null,
    val paymentMethod: PaymentMethod = PaymentMethod.CASH,
    val customerNote: String = "",
    val consumables: List<ServiceConsumableDraft> = emptyList(),
) {
    val computedRevenue: Double
        get() = (quantityOrDuration.coerceAtLeast(1) * unitPrice.coerceAtLeast(0.0))

    val effectiveRevenue: Double
        get() = customTotalRevenue?.coerceAtLeast(0.0) ?: computedRevenue
}

data class ServiceValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
)

object ServiceOperationValidator {
    fun validate(draft: ServiceOperationDraft): ServiceValidationResult {
        val errors = mutableListOf<String>()
        if (draft.quantityOrDuration <= 0) {
            errors += "Quantity or duration must be at least 1 ${draft.serviceType.unitLabel}."
        }
        if (draft.unitPrice < 0.0 || draft.unitPrice.isNaN()) {
            errors += "Unit price cannot be negative."
        }
        if (draft.effectiveRevenue <= 0.0 || draft.effectiveRevenue.isNaN()) {
            errors += "Enter a price / service revenue greater than Ksh 0."
        }
        draft.consumables.forEachIndexed { idx, cons ->
            if (cons.consumableName.trim().length < 2) {
                errors += "Consumable #${idx + 1}: Name is required."
            }
            if (cons.usageQty < 0.0 || cons.usageQty.isNaN()) {
                errors += "${cons.consumableName.ifBlank { "Consumable #${idx + 1}" }}: Usage quantity cannot be negative."
            }
            if (cons.stockDeductQty > 0 && (cons.linkedProductId == null || cons.linkedProductId <= 0L)) {
                errors += "${cons.consumableName.ifBlank { "Consumable #${idx + 1}" }}: Select a catalogue product before deducting stock."
            }
            if (cons.stockDeductQty < 0) {
                errors += "${cons.consumableName}: Stock deduction quantity cannot be negative."
            }
        }
        return ServiceValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
        )
    }
}

data class ServiceConsumableRpcLine(
    val consumableName: String,
    val usageQty: Double,
    val usageUnit: String,
    val linkedProductId: Long? = null,
    val stockDeductQty: Int = 0,
)

data class AndroidServiceRpcRequest(
    val clientServiceId: String,
    val idempotencyKey: String,
    val serviceType: ServiceOperationType,
    val serviceLabel: String,
    val quantityOrDuration: Int,
    val unitLabel: String,
    val unitPrice: Double,
    val totalRevenue: Double,
    val paymentMethod: String,
    val customerNote: String?,
    val consumables: List<ServiceConsumableRpcLine>,
    val actorId: String,
    val deviceId: String,
    val performedAtIso: String,
)

data class UpdatedConsumableStockAck(
    val productId: Long,
    val qtyDeducted: Int,
    val serverQtyAfter: Int?,
)

sealed class ServiceRpcOutcome {
    data class CommittedOnline(
        val serverServiceId: String,
        val totalRevenue: Double,
        val alreadyCommitted: Boolean,
        val updatedProducts: List<UpdatedConsumableStockAck>,
    ) : ServiceRpcOutcome()

    data class Rejected(
        val code: String,
        val message: String,
    ) : ServiceRpcOutcome()

    data class AuthRequired(val message: String) : ServiceRpcOutcome()

    data class RpcMissing(val message: String) : ServiceRpcOutcome()

    data class NetworkOrTransientError(val message: String) : ServiceRpcOutcome()
}

sealed class ServiceOperationResult {
    data class SuccessOnline(
        val serverServiceId: String,
        val serviceLabel: String,
        val totalRevenue: Double,
        val stockDeductedCount: Int,
        val alreadyCommitted: Boolean,
    ) : ServiceOperationResult()

    data class SavedOffline(
        val clientServiceId: String,
        val serviceLabel: String,
        val totalRevenue: Double,
        val stockDeductedCount: Int,
        val reason: String,
    ) : ServiceOperationResult()

    data class DuplicateIgnored(
        val clientServiceId: String,
        val syncStatus: String,
    ) : ServiceOperationResult()

    data class Rejected(
        val message: String,
    ) : ServiceOperationResult()
}
