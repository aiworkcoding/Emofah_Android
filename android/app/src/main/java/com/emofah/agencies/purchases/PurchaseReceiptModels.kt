package com.emofah.agencies.purchases

import com.emofah.agencies.data.catalog.ProductIdentity
import java.util.UUID

enum class PurchaseItemCategory(val code: String, val label: String) {
    RESALE_PRODUCT("RESALE_PRODUCT", "Resale Product"),
    CONSUMABLE("CONSUMABLE", "Consumable"),
    EQUIPMENT("EQUIPMENT", "Equipment"),
    ;

    companion object {
        fun fromCode(raw: String?): PurchaseItemCategory {
            val clean = raw?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.code == clean } ?: RESALE_PRODUCT
        }
    }
}

enum class ReceiptExtractionSource(val label: String) {
    CLOUD_AI_AND_OCR("AI + OCR Draft"),
    OFFLINE_OCR("Offline OCR Draft"),
    MANUAL_ENTRY("Manual Entry Draft"),
}

data class ExtractedReceiptLineDraft(
    val localLineId: String = UUID.randomUUID().toString(),
    val rawName: String,
    val matchedProductId: Long? = null,
    val matchedProductName: String? = null,
    val itemCategory: PurchaseItemCategory = PurchaseItemCategory.RESALE_PRODUCT,
    val quantity: Int = 1,
    val unitCost: Double = 0.0,
    val lineTotal: Double = 0.0,
    val registerAsNewProduct: Boolean = false,
    val newProductSellPrice: Double? = null,
    val newProductCategory: String = "General",
    val confidenceNote: String? = null,
) {
    val effectiveLineTotal: Double
        get() = if (lineTotal > 0.0) lineTotal else (unitCost.coerceAtLeast(0.0) * quantity.coerceAtLeast(1))
}

data class PurchaseReceiptDraft(
    val clientPurchaseId: String = UUID.randomUUID().toString(),
    val idempotencyKey: String = "purchase-${UUID.randomUUID()}",
    val supplierName: String = "",
    val invoiceRef: String = "",
    val receiptDateText: String = "",
    val extractedTotal: Double = 0.0,
    val subtotalAmount: Double = 0.0,
    val discountAmount: Double = 0.0,
    val taxAmount: Double = 0.0,
    val paymentMethod: String = "",
    val paymentReference: String = "",
    val lines: List<ExtractedReceiptLineDraft> = emptyList(),
    val rawOcrText: String = "",
    val photoPath: String? = null,
    val extractionSource: ReceiptExtractionSource = ReceiptExtractionSource.MANUAL_ENTRY,
    val warnings: List<String> = emptyList(),
    val isHumanConfirmed: Boolean = false,
) {
    val computedLinesTotal: Double
        get() = lines.sumOf { it.effectiveLineTotal }

    val effectiveTotal: Double
        get() = if (extractedTotal > 0.0) extractedTotal else (computedLinesTotal - discountAmount + taxAmount).coerceAtLeast(0.0)
}

data class ReceiptValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
)

/**
 * Enforces strict business & accuracy rules on purchase receipts:
 * - NEVER allows silent confirmation (isHumanConfirmed must be explicitly checked by human operator).
 * - Requires at least one valid line item with quantity >= 1 and unitCost >= 0.
 * - For RESALE_PRODUCT lines: must either reference a real matchedProductId OR opt into
 *   registerAsNewProduct with a positive selling price.
 */
object ReceiptDraftValidator {
    fun validate(draft: PurchaseReceiptDraft): ReceiptValidationResult {
        val errors = mutableListOf<String>()

        if (!draft.isHumanConfirmed) {
            errors += "Human review confirmation is required before submitting receipt."
        }
        if (draft.lines.isEmpty()) {
            errors += "Add at least one purchased item."
        }

        draft.lines.forEachIndexed { idx, line ->
            val label = line.rawName.ifBlank { "Item #${idx + 1}" }
            if (line.rawName.trim().length < 2) {
                errors += "Line ${idx + 1}: Item description is required."
            }
            if (line.quantity <= 0) {
                errors += "$label: Quantity must be at least 1."
            }
            if (line.unitCost < 0.0 || line.unitCost.isNaN()) {
                errors += "$label: Unit cost cannot be negative."
            }
            if (line.itemCategory == PurchaseItemCategory.RESALE_PRODUCT) {
                if (line.registerAsNewProduct) {
                    val sell = line.newProductSellPrice ?: 0.0
                    if (sell <= 0.0) {
                        errors += "$label: Selling price (Ksh > 0) is required to register a new resale product."
                    }
                } else if (line.matchedProductId == null || line.matchedProductId <= 0L) {
                    errors += "$label: Match an existing product or toggle 'Register New Resale Product'."
                }
            }
        }

        return ReceiptValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
        )
    }
}

data class AndroidPurchaseLineRequest(
    val rawName: String,
    val itemCategory: PurchaseItemCategory,
    val matchedProductId: Long? = null,
    val localTempProductId: Long? = null,
    val registerAsNewProduct: Boolean = false,
    val newProductSellPrice: Double? = null,
    val newProductCategory: String = "General",
    val quantity: Int,
    val unitCost: Double,
    val lineTotal: Double,
)

data class AndroidPurchaseRpcRequest(
    val clientPurchaseId: String,
    val idempotencyKey: String,
    val supplierName: String?,
    val invoiceRef: String?,
    val receiptDateText: String?,
    val totalAmount: Double,
    val extractionSource: String,
    val isHumanConfirmed: Boolean,
    val items: List<AndroidPurchaseLineRequest>,
    val actorId: String,
    val deviceId: String,
    val recordedAtIso: String,
    val recordFinancialOutflow: Boolean = true,
)

data class ResolvedPurchaseProductAck(
    val lineIndex: Int,
    val itemCategory: PurchaseItemCategory,
    val localTempProductId: Long?,
    val serverProductId: Long?,
    val productName: String,
    val category: String,
    val sellPrice: Double,
    val buyPrice: Double,
    val qty: Int,
    val serverQtyAfter: Int?,
    val registeredNewProduct: Boolean,
)

sealed class PurchaseRpcOutcome {
    data class CommittedOnline(
        val serverPurchaseId: String,
        val totalAmount: Double,
        val alreadyCommitted: Boolean,
        val resolvedProducts: List<ResolvedPurchaseProductAck>,
    ) : PurchaseRpcOutcome()

    data class Rejected(
        val code: String,
        val message: String,
    ) : PurchaseRpcOutcome()

    data class AuthRequired(val message: String) : PurchaseRpcOutcome()

    data class RpcMissing(val message: String) : PurchaseRpcOutcome()

    data class NetworkOrTransientError(val message: String) : PurchaseRpcOutcome()
}

sealed class PurchaseSubmissionResult {
    data class SuccessOnline(
        val serverPurchaseId: String,
        val totalAmount: Double,
        val resaleUnitsUpdated: Int,
        val newlyRegisteredCount: Int,
        val alreadyCommitted: Boolean,
    ) : PurchaseSubmissionResult()

    data class SavedOffline(
        val clientPurchaseId: String,
        val totalAmount: Double,
        val resaleUnitsQueued: Int,
        val newlyRegisteredCount: Int,
        val reason: String,
    ) : PurchaseSubmissionResult()

    data class DuplicateIgnored(
        val clientPurchaseId: String,
        val syncStatus: String,
    ) : PurchaseSubmissionResult()

    data class Rejected(
        val message: String,
    ) : PurchaseSubmissionResult()
}

/**
 * Pure Kotlin deterministic offline receipt OCR fallback parser.
 * Extracts supplier, receipt reference, date, item lines, and total from raw ML Kit OCR text
 * without inventing unprinted numbers, and marks every draft unconfirmed for human review.
 */
object ReceiptOcrParser {
    fun parse(
        rawOcrText: String,
        catalogueProducts: List<ProductIdentity>,
        photoPath: String? = null,
    ): PurchaseReceiptDraft {
        return ReceiptLayoutParser.parseRawText(
            rawOcrText = rawOcrText,
            catalogueProducts = catalogueProducts,
            photoPath = photoPath,
        )
    }

    internal fun inferCategoryHint(name: String): PurchaseItemCategory {
        val lower = name.lowercase()
        return when {
            listOf("printer", "scanner", "monitor", "laptop", "laminator", "router", "ups", "desk", "chair")
                .any { lower.contains(it) } -> PurchaseItemCategory.EQUIPMENT
            listOf("toner", "cartridge", "ink", "ream", "rim", "staple", "laminating pouch", "binding", "ribbon", "cleaning")
                .any { lower.contains(it) } -> PurchaseItemCategory.CONSUMABLE
            else -> PurchaseItemCategory.RESALE_PRODUCT
        }
    }

    internal fun findBestCatalogMatch(
        rawName: String,
        catalogue: List<ProductIdentity>,
    ): ProductIdentity? = ReceiptLayoutParser.findConservativeCatalogMatch(rawName, catalogue)
}
