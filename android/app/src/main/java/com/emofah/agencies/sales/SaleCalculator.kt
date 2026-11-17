package com.emofah.agencies.sales

import com.emofah.agencies.auth.EmofahPermission
import com.emofah.agencies.auth.RolePermissionPolicy
import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.local.entities.LocalStockProjectionEntity
import java.util.UUID
import kotlin.math.abs

/**
 * Stage 2.1 + Stage 2.3 (Actions 2.1.1 – 2.1.3 & 2.3.1 – 2.3.2):
 * Pure, deterministic quantity, stock-policy, authorized price-exception,
 * and sale-total calculator for frictionless single-product sales capture.
 */
enum class StockPolicy {
    ALLOW_WITH_WARNING,
    STRICT_BLOCK,
}

enum class PaymentMethod(val label: String) {
    CASH("Cash"),
    MPESA("M-Pesa"),
    OTHER("Other"),
}

enum class StaffRole(val label: String) {
    CASHIER("Cashier"),
    SUPERVISOR("Supervisor"),
    OWNER_MANAGER("Owner / Manager"),
}

data class PriceExceptionValidation(
    val effectiveUnitPrice: Double,
    val originalSellPrice: Double,
    val isOverridden: Boolean,
    val cleanReason: String?,
    val isAllowed: Boolean,
    val errorMessage: String? = null,
)

object PriceOverridePolicy {

    const val PRICE_EPSILON = 0.009

    fun canOverridePrice(role: StaffRole): Boolean {
        return RolePermissionPolicy.hasPermission(role, EmofahPermission.OVERRIDE_SALE_PRICE)
    }

    fun validatePriceException(
        originalPrice: Double,
        requestedPrice: Double?,
        role: StaffRole,
        overrideReason: String?,
        requireReason: Boolean = true,
    ): PriceExceptionValidation {
        if (requestedPrice == null) {
            return PriceExceptionValidation(
                effectiveUnitPrice = originalPrice,
                originalSellPrice = originalPrice,
                isOverridden = false,
                cleanReason = null,
                isAllowed = true,
                errorMessage = null,
            )
        }

        if (!requestedPrice.isFinite()) {
            return PriceExceptionValidation(
                effectiveUnitPrice = originalPrice,
                originalSellPrice = originalPrice,
                isOverridden = true,
                cleanReason = null,
                isAllowed = false,
                errorMessage = "Enter a valid finite selling price.",
            )
        }

        if (abs(requestedPrice - originalPrice) <= PRICE_EPSILON) {
            return PriceExceptionValidation(
                effectiveUnitPrice = originalPrice,
                originalSellPrice = originalPrice,
                isOverridden = false,
                cleanReason = null,
                isAllowed = true,
                errorMessage = null,
            )
        }

        if (!canOverridePrice(role)) {
            return PriceExceptionValidation(
                effectiveUnitPrice = requestedPrice,
                originalSellPrice = originalPrice,
                isOverridden = true,
                cleanReason = null,
                isAllowed = false,
                errorMessage = "Price override requires Supervisor or Owner/Manager authorization.",
            )
        }

        if (requestedPrice <= 0.0) {
            return PriceExceptionValidation(
                effectiveUnitPrice = requestedPrice,
                originalSellPrice = originalPrice,
                isOverridden = true,
                cleanReason = null,
                isAllowed = false,
                errorMessage = "Overridden selling price must be greater than zero.",
            )
        }

        val cleanReason = overrideReason?.trim()?.takeIf { it.isNotEmpty() }
        if (requireReason && cleanReason == null) {
            return PriceExceptionValidation(
                effectiveUnitPrice = requestedPrice,
                originalSellPrice = originalPrice,
                isOverridden = true,
                cleanReason = null,
                isAllowed = false,
                errorMessage = "Enter a reason for the price change.",
            )
        }

        return PriceExceptionValidation(
            effectiveUnitPrice = requestedPrice,
            originalSellPrice = originalPrice,
            isOverridden = true,
            cleanReason = cleanReason,
            isAllowed = true,
            errorMessage = null,
        )
    }
}

data class SaleValidationResult(
    val quantity: Int,
    val unitSellPrice: Double,
    val originalSellPrice: Double = unitSellPrice,
    val totalAmount: Double,
    val isValidToRecord: Boolean,
    val isPriceOverridden: Boolean = false,
    val blockingReason: String? = null,
    val stockWarning: String? = null,
)

data class PreparedSaleDraft(
    val clientSaleId: String,
    val idempotencyKey: String,
    val productId: Long,
    val productName: String,
    val quantity: Int,
    val unitSellPrice: Double,
    val totalAmount: Double,
    val paymentMethod: PaymentMethod,
    val createdAtEpochMs: Long,
    val originalSellPrice: Double = unitSellPrice,
    val priceOverrideReason: String? = null,
) {
    val isPriceOverridden: Boolean
        get() = abs(unitSellPrice - originalSellPrice) > PriceOverridePolicy.PRICE_EPSILON
}

object SaleCalculator {

    fun validateAndCalculate(
        product: ProductIdentity,
        stockProjection: LocalStockProjectionEntity?,
        rawQuantity: Int,
        stockPolicy: StockPolicy = StockPolicy.ALLOW_WITH_WARNING,
        overrideUnitPrice: Double? = null,
        overrideReason: String? = null,
        staffRole: StaffRole = StaffRole.CASHIER,
        requireOverrideReason: Boolean = true,
    ): SaleValidationResult {
        val registeredPrice = product.effectiveSellPrice
        if (!registeredPrice.isFinite() || registeredPrice <= 0.0) {
            return SaleValidationResult(
                quantity = rawQuantity.coerceAtLeast(1),
                unitSellPrice = if (registeredPrice.isFinite()) registeredPrice else 0.0,
                originalSellPrice = if (registeredPrice.isFinite()) registeredPrice else 0.0,
                totalAmount = 0.0,
                isValidToRecord = false,
                blockingReason = "Selling price is missing or zero.",
            )
        }

        val priceCheck = PriceOverridePolicy.validatePriceException(
            originalPrice = registeredPrice,
            requestedPrice = overrideUnitPrice,
            role = staffRole,
            overrideReason = overrideReason,
            requireReason = requireOverrideReason,
        )
        if (!priceCheck.isAllowed) {
            return SaleValidationResult(
                quantity = rawQuantity.coerceAtLeast(1),
                unitSellPrice = priceCheck.effectiveUnitPrice,
                originalSellPrice = registeredPrice,
                totalAmount = rawQuantity.coerceAtLeast(1) * priceCheck.effectiveUnitPrice,
                isValidToRecord = false,
                isPriceOverridden = priceCheck.isOverridden,
                blockingReason = priceCheck.errorMessage,
            )
        }

        val unitPrice = priceCheck.effectiveUnitPrice
        if (rawQuantity <= 0) {
            return SaleValidationResult(
                quantity = 0,
                unitSellPrice = unitPrice,
                originalSellPrice = registeredPrice,
                totalAmount = 0.0,
                isValidToRecord = false,
                isPriceOverridden = priceCheck.isOverridden,
                blockingReason = "Quantity must be at least 1.",
            )
        }

        val availableStock = stockProjection?.provisionalAvailableQty ?: product.stockQty
        val exceedsStock = rawQuantity > availableStock

        if (exceedsStock && stockPolicy == StockPolicy.STRICT_BLOCK) {
            return SaleValidationResult(
                quantity = rawQuantity,
                unitSellPrice = unitPrice,
                originalSellPrice = registeredPrice,
                totalAmount = rawQuantity * unitPrice,
                isValidToRecord = false,
                isPriceOverridden = priceCheck.isOverridden,
                blockingReason = "Only $availableStock unit(s) available in stock.",
            )
        }

        val warning = if (exceedsStock) {
            "Quantity ($rawQuantity) exceeds cached stock ($availableStock). Server will verify on sync."
        } else {
            null
        }

        return SaleValidationResult(
            quantity = rawQuantity,
            unitSellPrice = unitPrice,
            originalSellPrice = registeredPrice,
            totalAmount = rawQuantity * unitPrice,
            isValidToRecord = true,
            isPriceOverridden = priceCheck.isOverridden,
            blockingReason = null,
            stockWarning = warning,
        )
    }

    fun parseQuantityInput(input: String): Int {
        val digitsOnly = input.filter { it.isDigit() }
        return digitsOnly.toIntOrNull() ?: 0
    }

    fun parsePriceInput(input: String): Double? {
        val cleaned = input.trim()
        if (cleaned.isEmpty()) return null
        return cleaned.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    fun createDraft(
        product: ProductIdentity,
        quantity: Int,
        paymentMethod: PaymentMethod,
        existingDraftId: String? = null,
        existingIdempotencyKey: String? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
        overrideUnitPrice: Double? = null,
        priceOverrideReason: String? = null,
    ): PreparedSaleDraft {
        val stableId = existingDraftId ?: UUID.randomUUID().toString()
        val stableIdem = existingIdempotencyKey ?: "sale-$stableId"
        val originalPrice = product.effectiveSellPrice
        val effectiveUnitPrice = overrideUnitPrice?.takeIf {
            it.isFinite() && it > 0.0 && abs(it - originalPrice) > PriceOverridePolicy.PRICE_EPSILON
        } ?: originalPrice
        val isOverridden = abs(effectiveUnitPrice - originalPrice) > PriceOverridePolicy.PRICE_EPSILON

        return PreparedSaleDraft(
            clientSaleId = stableId,
            idempotencyKey = stableIdem,
            productId = product.id,
            productName = product.name,
            quantity = quantity,
            unitSellPrice = effectiveUnitPrice,
            totalAmount = quantity * effectiveUnitPrice,
            paymentMethod = paymentMethod,
            createdAtEpochMs = nowEpochMs,
            originalSellPrice = originalPrice,
            priceOverrideReason = if (isOverridden) priceOverrideReason?.trim()?.takeIf { it.isNotEmpty() } else null,
        )
    }
}
