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
        get() = if (extractedTotal > 0.0) extractedTotal else computedLinesTotal
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
    private val TOTAL_LINE_REGEX = Regex(
        """(?:grand\s+total|total\s+due|net\s+total|total)\s*[:\-]?\s*(?:ksh|kes)?\.?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE,
    )
    private val INVOICE_REF_REGEX = Regex(
        """(?:receipt|invoice|inv|rcpt|ref|serial)\s*(?:no\.?|#|id)?\s*[:\-]?\s*([A-Z0-9\-\/]{3,24})""",
        RegexOption.IGNORE_CASE,
    )
    private val DATE_REGEX = Regex("""\b(\d{1,2}[\/\-]\d{1,2}[\/\-]\d{2,4}|\d{4}-\d{2}-\d{2})\b""")
    private val QTY_PREFIX_ITEM_REGEX = Regex(
        """^(\d{1,4})\s*[xX*]?\s+([A-Za-z][A-Za-z0-9\s.&'\/\-]{2,48}?)\s+(?:ksh|kes)?\.?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)$""",
        RegexOption.IGNORE_CASE,
    )
    private val NAME_AMT_LINE_REGEX = Regex(
        """^([A-Za-z][A-Za-z0-9\s.&'\/\-]{2,48}?)\s+(?:(\d{1,4})\s*[xX*]?\s+@?\s*)?(?:ksh|kes)?\.?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)$""",
        RegexOption.IGNORE_CASE,
    )

    private val NON_ITEM_KEYWORDS = listOf(
        "subtotal", "sub-total", "vat", "tax", "total", "cash", "mpesa", "m-pesa",
        "change", "balance", "thank you", "served by", "tel:", "pin:", "kra", "till",
    )

    fun parse(
        rawOcrText: String,
        catalogueProducts: List<ProductIdentity>,
        photoPath: String? = null,
    ): PurchaseReceiptDraft {
        val textLines = rawOcrText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val supplier = textLines.firstOrNull { line ->
            val lower = line.lowercase()
            line.length in 3..48 &&
                line.any { it.isLetter() } &&
                !lower.contains("receipt") &&
                !lower.contains("invoice") &&
                !lower.contains("kra") &&
                !lower.contains("pin:") &&
                !DATE_REGEX.containsMatchIn(line)
        }.orEmpty()

        val invoiceRef = INVOICE_REF_REGEX.find(rawOcrText)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val receiptDate = DATE_REGEX.find(rawOcrText)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val extractedTotal = TOTAL_LINE_REGEX.findAll(rawOcrText)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(",", "")
            ?.toDoubleOrNull()
            ?: 0.0

        val activeProducts = catalogueProducts.filter { !it.isArchived && it.id > 0L }
        val extractedItems = mutableListOf<ExtractedReceiptLineDraft>()

        for (line in textLines) {
            val lower = line.lowercase()
            if (NON_ITEM_KEYWORDS.any { lower.startsWith(it) || lower.contains(" $it ") || lower == it }) {
                continue
            }

            val qtyFirstMatch = QTY_PREFIX_ITEM_REGEX.matchEntire(line)
            val nameFirstMatch = if (qtyFirstMatch == null) NAME_AMT_LINE_REGEX.matchEntire(line) else null

            val rawName: String
            val qty: Int
            val amountVal: Double

            if (qtyFirstMatch != null) {
                qty = qtyFirstMatch.groupValues[1].toIntOrNull()?.coerceAtLeast(1) ?: 1
                rawName = qtyFirstMatch.groupValues[2].trim()
                amountVal = qtyFirstMatch.groupValues[3].replace(",", "").toDoubleOrNull() ?: 0.0
            } else if (nameFirstMatch != null) {
                rawName = nameFirstMatch.groupValues[1].trim()
                qty = nameFirstMatch.groupValues[2].toIntOrNull()?.coerceAtLeast(1) ?: 1
                amountVal = nameFirstMatch.groupValues[3].replace(",", "").toDoubleOrNull() ?: 0.0
            } else {
                continue
            }

            if (rawName.length < 2 || amountVal <= 0.0) continue
            val unitCost = if (qty > 0) (amountVal / qty) else amountVal
            val category = inferCategoryHint(rawName)
            val matchedProduct = if (category == PurchaseItemCategory.RESALE_PRODUCT) {
                findBestCatalogMatch(rawName, activeProducts)
            } else {
                null
            }

            extractedItems += ExtractedReceiptLineDraft(
                rawName = rawName,
                matchedProductId = matchedProduct?.id,
                matchedProductName = matchedProduct?.name,
                itemCategory = category,
                quantity = qty,
                unitCost = unitCost,
                lineTotal = amountVal,
                registerAsNewProduct = false,
                confidenceNote = "Extracted offline via OCR — verify qty & cost",
            )
        }

        val warnings = mutableListOf(
            "OCR/AI draft requires human verification before saving."
        )
        if (extractedItems.isEmpty()) {
            warnings += "No clear line items detected automatically; add or edit items below."
        }

        return PurchaseReceiptDraft(
            supplierName = supplier,
            invoiceRef = invoiceRef,
            receiptDateText = receiptDate,
            extractedTotal = extractedTotal,
            lines = extractedItems,
            rawOcrText = rawOcrText,
            photoPath = photoPath,
            extractionSource = ReceiptExtractionSource.OFFLINE_OCR,
            warnings = warnings,
            isHumanConfirmed = false,
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
    ): ProductIdentity? {
        val tokens = rawName.lowercase()
            .split(Regex("""\s+"""))
            .map { it.trim() }
            .filter { it.length >= 3 }
        if (tokens.isEmpty()) return null

        var best: ProductIdentity? = null
        var bestScore = 0.0
        for (prod in catalogue) {
            val hay = "${prod.name} ${prod.baseName} ${prod.variantLabel.orEmpty()} ${prod.brandHint.orEmpty()}".lowercase()
            val hits = tokens.count { hay.contains(it) }
            if (hits == 0) continue
            val score = hits.toDouble() / tokens.size.toDouble()
            if (score > bestScore && score >= 0.5) {
                bestScore = score
                best = prod
            }
        }
        return best
    }
}
