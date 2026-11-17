package com.emofah.agencies.stock

import com.emofah.agencies.data.catalog.ProductIdentity
import java.util.UUID

data class StockReceiptDraft(
    val clientReceiptId: String,
    val idempotencyKey: String,
    val productId: Long,
    val productName: String,
    val quantityReceived: Int,
    val unitCost: Double? = null,
    val supplier: String? = null,
    val invoiceRef: String? = null,
    val invoicePhotoPath: String? = null,
    val updateBuyPrice: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

data class StockReceivingValidation(
    val isValid: Boolean,
    val errorMessage: String? = null,
    val sanitizedQuantity: Int = 1,
    val sanitizedUnitCost: Double? = null,
    val sanitizedSupplier: String? = null,
    val sanitizedInvoiceRef: String? = null,
)

object StockReceivingValidator {

    fun validateDraft(
        product: ProductIdentity?,
        quantity: Int,
        unitCost: Double?,
        supplier: String?,
        invoiceRef: String?,
    ): StockReceivingValidation {
        if (product == null) {
            return StockReceivingValidation(
                isValid = false,
                errorMessage = "Select an existing product from the catalogue.",
            )
        }

        if (quantity <= 0) {
            return StockReceivingValidation(
                isValid = false,
                errorMessage = "Received quantity must be at least 1 unit.",
                sanitizedQuantity = quantity.coerceAtLeast(1),
            )
        }

        if (unitCost != null && unitCost < 0.0) {
            return StockReceivingValidation(
                isValid = false,
                errorMessage = "Unit purchase cost cannot be negative.",
            )
        }

        val cleanSupplier = supplier?.trim()?.takeIf { it.isNotEmpty() }
        val cleanInvoice = invoiceRef?.trim()?.takeIf { it.isNotEmpty() }

        return StockReceivingValidation(
            isValid = true,
            errorMessage = null,
            sanitizedQuantity = quantity,
            sanitizedUnitCost = unitCost?.takeIf { it > 0.0 },
            sanitizedSupplier = cleanSupplier,
            sanitizedInvoiceRef = cleanInvoice,
        )
    }

    fun createDraft(
        product: ProductIdentity,
        quantity: Int,
        unitCost: Double? = null,
        supplier: String? = null,
        invoiceRef: String? = null,
        invoicePhotoPath: String? = null,
        updateBuyPrice: Boolean = false,
        existingReceiptId: String? = null,
        existingIdempotencyKey: String? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): StockReceiptDraft {
        val stableId = existingReceiptId ?: "rcpt-${UUID.randomUUID()}"
        val stableIdem = existingIdempotencyKey ?: "receipt-$stableId"

        return StockReceiptDraft(
            clientReceiptId = stableId,
            idempotencyKey = stableIdem,
            productId = product.id,
            productName = product.name,
            quantityReceived = quantity.coerceAtLeast(1),
            unitCost = unitCost?.takeIf { it > 0.0 },
            supplier = supplier?.trim()?.takeIf { it.isNotEmpty() },
            invoiceRef = invoiceRef?.trim()?.takeIf { it.isNotEmpty() },
            invoicePhotoPath = invoicePhotoPath?.takeIf { it.isNotBlank() },
            updateBuyPrice = updateBuyPrice && unitCost != null && unitCost > 0.0,
            createdAtEpochMs = nowEpochMs,
        )
    }
}

data class AndroidStockReceiptRpcRequest(
    val productId: Long,
    val quantity: Int,
    val clientReceiptId: String,
    val idempotencyKey: String,
    val unitCost: Double? = null,
    val supplier: String? = null,
    val invoiceRef: String? = null,
    val invoicePhotoUrl: String? = null,
    val actorId: String = "android_staff",
    val deviceId: String = "android",
    val receivedAtIso: String = "",
    val updateBuyPrice: Boolean = false,
)

sealed class StockReceiptRpcOutcome {
    data class CommittedOnline(
        val serverQty: Int,
        val alreadyCommitted: Boolean,
    ) : StockReceiptRpcOutcome()

    data class Rejected(
        val code: String,
        val message: String,
    ) : StockReceiptRpcOutcome()

    data class RpcMissing(val message: String) : StockReceiptRpcOutcome()

    data class AuthRequired(val message: String) : StockReceiptRpcOutcome()

    data class NetworkOrTransientError(val message: String) : StockReceiptRpcOutcome()
}

sealed class StockReceivingResult {
    data class SuccessOnline(
        val clientReceiptId: String,
        val updatedServerQty: Int,
        val quantityReceived: Int,
        val alreadyCommitted: Boolean,
    ) : StockReceivingResult()

    data class SavedOffline(
        val clientReceiptId: String,
        val queuedEventId: String,
        val provisionalRemainingQty: Int,
        val quantityReceived: Int,
        val reason: String,
    ) : StockReceivingResult()

    data class DuplicateIgnored(
        val clientReceiptId: String,
        val serverQty: Int,
    ) : StockReceivingResult()

    data class Rejected(
        val message: String,
    ) : StockReceivingResult()
}
