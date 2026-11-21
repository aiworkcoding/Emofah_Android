package com.emofah.agencies.purchases

import com.emofah.agencies.data.catalog.ProductIdentity
import kotlin.math.abs

data class OcrSpatialLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerY: Double get() = (top + bottom) / 2.0
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

/**
 * Layout-agnostic receipt and invoice parser supporting both 2D ML Kit bounding-box lines
 * (reconstructing multi-column horizontal rows and wrapped 58mm thermal lines) and raw OCR text.
 *
 * - Never fabricates catalogue IDs or auto-creates products from uncertain OCR.
 * - Flags arithmetic discrepancies cleanly via [ReceiptArithmeticValidator] without mutating values.
 */
object ReceiptLayoutParser {

    private val TOTAL_LINE_REGEX = Regex(
        """^(?!.*(?:sub[\s\-]*total|subtotal)).*(?:grand\s+total|total\s+due|amount\s+due|amount\s+payable|net\s+total|net\s+amount|total)\s*[:\-=]?\s*(?:ksh|kes|sh)?\.?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE,
    )
    private val SUBTOTAL_LINE_REGEX = Regex(
        """(?:sub[\s\-]*total|subtotal|goods\s+total|taxable\s+amt)\s*[:\-=]?\s*(?:ksh|kes|sh)?\.?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE,
    )
    private val DISCOUNT_LINE_REGEX = Regex(
        """(?:discount|disc\.?|less\s+disc)\s*[:\-=]?\s*-?\s*(?:ksh|kes|sh)?\.?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE,
    )
    private val TAX_LINE_REGEX = Regex(
        """(?:vat(?:\s*@?\s*\d+%?)?|tax(?:\s*@?\s*\d+%?)?|v\.a\.t)\s*[:\-=]?\s*(?:ksh|kes|sh)?\.?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE,
    )
    private val INVOICE_REF_REGEX = Regex(
        """(?:receipt|tax\s+invoice|invoice|inv|rcpt|ref|doc|serial)\s*(?:no\.?|#|id)?\s*[:\-]?\s*([A-Z0-9\-\/]{3,24})""",
        RegexOption.IGNORE_CASE,
    )
    private val DATE_REGEX = Regex(
        """\b(\d{1,2}[\/\-]\d{1,2}[\/\-]\d{2,4}|\d{4}-\d{2}-\d{2}|\d{1,2}\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{2,4})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val MPESA_REF_REGEX = Regex("""\b([A-Z][A-Z0-9]{9})\b""")

    // Patterns for item rows
    private val FOUR_COL_REGEX = Regex(
        """^([A-Za-z][A-Za-z0-9\s.&'\/\-()]{1,56}?)\s+(\d{1,4})\s+(?:ksh|kes)?\.?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s+(?:ksh|kes)?\.?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)$""",
        RegexOption.IGNORE_CASE,
    )
    private val QTY_PREFIX_FOUR_COL_REGEX = Regex(
        """^(\d{1,4})\s*[xX*]?\s+([A-Za-z][A-Za-z0-9\s.&'\/\-()]{1,56}?)\s+@?\s*(?:ksh|kes)?\.?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s+(?:ksh|kes)?\.?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)$""",
        RegexOption.IGNORE_CASE,
    )
    private val QTY_PREFIX_ITEM_REGEX = Regex(
        """^(\d{1,4})\s*[xX*]?\s+([A-Za-z][A-Za-z0-9\s.&'\/\-()]{2,56}?)\s+(?:ksh|kes)?\.?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)$""",
        RegexOption.IGNORE_CASE,
    )
    private val NAME_QTY_AT_UNIT_TOTAL_REGEX = Regex(
        """^([A-Za-z][A-Za-z0-9\s.&'\/\-()]{2,56}?)\s+(\d{1,4})\s*[xX*]\s*@?\s*(?:ksh|kes)?\.?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*=?\s*(?:ksh|kes)?\.?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)$""",
        RegexOption.IGNORE_CASE,
    )
    private val NAME_AMT_LINE_REGEX = Regex(
        """^([A-Za-z][A-Za-z0-9\s.&'\/\-()]{2,56}?)\s+(?:(\d{1,4})\s*[xX*]?\s+@?\s*)?(?:ksh|kes)?\.?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)$""",
        RegexOption.IGNORE_CASE,
    )
    private val WRAPPED_QTY_UNIT_TOTAL_REGEX = Regex(
        """^(\d{1,4})\s*[xX*]\s*(?:ksh|kes)?\.?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*(?:=|->|\s)\s*(?:ksh|kes)?\.?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)$""",
        RegexOption.IGNORE_CASE,
    )

    private val NON_ITEM_PREFIXES = listOf(
        "subtotal", "sub-total", "sub total", "vat", "v.a.t", "tax", "total", "grand total",
        "net total", "discount", "cash", "mpesa", "m-pesa", "card", "bank", "change", "balance",
        "thank you", "served by", "cashier", "tel:", "phone:", "pin:", "kra", "till", "paybill",
        "p.o. box", "po box", "invoice", "receipt", "date:", "time:",
    )

    private val SUPPLIER_SKIP_TOKENS = listOf(
        "receipt", "tax invoice", "invoice", "kra", "pin:", "tel:", "phone", "date", "time",
        "till", "paybill", "p.o. box", "po box", "cashier", "welcome", "etr",
    )

    fun parseSpatialLines(
        spatialLines: List<OcrSpatialLine>,
        catalogueProducts: List<ProductIdentity>,
        photoPath: String? = null,
    ): PurchaseReceiptDraft {
        val reconstructedText = reconstructRowsFromBoundingBoxes(spatialLines)
        return parseRawText(
            rawOcrText = reconstructedText,
            catalogueProducts = catalogueProducts,
            photoPath = photoPath,
        )
    }

    internal fun reconstructRowsFromBoundingBoxes(spatialLines: List<OcrSpatialLine>): String {
        val cleanLines = spatialLines
            .map { it.copy(text = it.text.trim()) }
            .filter { it.text.isNotBlank() }
        if (cleanLines.isEmpty()) return ""

        val sortedHeights = cleanLines.map { it.height }.sorted()
        val medianHeight = sortedHeights[sortedHeights.size / 2].toDouble().coerceAtLeast(10.0)
        val rowToleranceY = (medianHeight * 0.58).coerceAtLeast(8.0)

        val sortedByY = cleanLines.sortedBy { it.centerY }
        val rows = mutableListOf<MutableList<OcrSpatialLine>>()
        val rowAnchorY = mutableListOf<Double>()

        for (line in sortedByY) {
            var targetRowIdx = -1
            var bestDist = Double.MAX_VALUE
            for (idx in rows.indices) {
                val dist = abs(line.centerY - rowAnchorY[idx])
                if (dist <= rowToleranceY && dist < bestDist) {
                    bestDist = dist
                    targetRowIdx = idx
                }
            }
            if (targetRowIdx >= 0) {
                rows[targetRowIdx].add(line)
                rowAnchorY[targetRowIdx] = rows[targetRowIdx].map { it.centerY }.average()
            } else {
                rows.add(mutableListOf(line))
                rowAnchorY.add(line.centerY)
            }
        }

        return rows.joinToString("\n") { row ->
            row.sortedBy { it.left }.joinToString(" ") { it.text }
        }
    }

    fun parseRawText(
        rawOcrText: String,
        catalogueProducts: List<ProductIdentity>,
        photoPath: String? = null,
    ): PurchaseReceiptDraft {
        val textLines = rawOcrText.lines()
            .map { it.replace(Regex("""\s+"""), " ").trim() }
            .filter { it.isNotBlank() }

        val supplier = textLines.firstOrNull { line ->
            val lower = line.lowercase()
            line.length in 3..56 &&
                line.any { it.isLetter() } &&
                SUPPLIER_SKIP_TOKENS.none { lower.contains(it) } &&
                !DATE_REGEX.containsMatchIn(line) &&
                !line.all { it.isDigit() || it in setOf('-', '/', '.', ',', ' ') }
        }.orEmpty()

        val invoiceRef = INVOICE_REF_REGEX.find(rawOcrText)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val receiptDate = DATE_REGEX.find(rawOcrText)?.groupValues?.getOrNull(1)?.trim().orEmpty()

        val subtotalAmount = SUBTOTAL_LINE_REGEX.findAll(rawOcrText)
            .lastOrNull()?.groupValues?.getOrNull(1)?.toCleanAmount() ?: 0.0
        val discountAmount = DISCOUNT_LINE_REGEX.findAll(rawOcrText)
            .lastOrNull()?.groupValues?.getOrNull(1)?.toCleanAmount() ?: 0.0
        val taxAmount = TAX_LINE_REGEX.findAll(rawOcrText)
            .lastOrNull()?.groupValues?.getOrNull(1)?.toCleanAmount() ?: 0.0
        val extractedTotal = textLines.mapNotNull { line ->
            TOTAL_LINE_REGEX.find(line)?.groupValues?.getOrNull(1)?.toCleanAmount()
        }.lastOrNull() ?: 0.0

        val (paymentMethod, paymentRef) = detectPaymentDetails(textLines)

        val activeProducts = catalogueProducts.filter { !it.isArchived && it.id > 0L }
        val extractedItems = mutableListOf<ExtractedReceiptLineDraft>()

        // Pre-merge 2-line wrapped thermal receipt items where line i is description and line i+1 is "2 x 150.00 = 300.00"
        val mergedLines = mergeWrappedThermalItemLines(textLines)

        for (line in mergedLines) {
            val lower = line.lowercase()
            if (isNonItemSummaryOrHeader(lower)) continue

            val parsed = parseLineItemRow(line) ?: continue
            val (rawName, qty, unitCost, lineTotal) = parsed
            if (rawName.length < 2 || (lineTotal <= 0.0 && unitCost <= 0.0)) continue

            val category = ReceiptOcrParser.inferCategoryHint(rawName)
            val matchedProduct = if (category == PurchaseItemCategory.RESALE_PRODUCT) {
                findConservativeCatalogMatch(rawName, activeProducts)
            } else {
                null
            }

            val note = when {
                matchedProduct != null -> "Matched locally via OCR — verify qty & cost"
                category == PurchaseItemCategory.RESALE_PRODUCT -> "Uncertain catalogue match — pick item or register New"
                else -> "Extracted offline via OCR — verify qty & cost"
            }

            extractedItems += ExtractedReceiptLineDraft(
                rawName = rawName,
                matchedProductId = matchedProduct?.id,
                matchedProductName = matchedProduct?.name,
                itemCategory = category,
                quantity = qty,
                unitCost = unitCost,
                lineTotal = lineTotal,
                registerAsNewProduct = false,
                confidenceNote = note,
            )
        }

        val baseDraft = PurchaseReceiptDraft(
            supplierName = supplier,
            invoiceRef = invoiceRef,
            receiptDateText = receiptDate,
            extractedTotal = extractedTotal,
            subtotalAmount = subtotalAmount,
            discountAmount = discountAmount,
            taxAmount = taxAmount,
            paymentMethod = paymentMethod,
            paymentReference = paymentRef,
            lines = extractedItems,
            rawOcrText = rawOcrText,
            photoPath = photoPath,
            extractionSource = ReceiptExtractionSource.OFFLINE_OCR,
            warnings = emptyList(),
            isHumanConfirmed = false,
        )

        val arithmeticReport = ReceiptArithmeticValidator.validate(baseDraft)
        val warnings = buildList {
            add("OCR draft requires human verification before saving.")
            if (extractedItems.isEmpty()) {
                add("No clear line items detected automatically; add or edit items below.")
            }
            addAll(arithmeticReport.warnings)
        }.distinct()

        return baseDraft.copy(warnings = warnings)
    }

    private data class ParsedItemTuple(
        val rawName: String,
        val quantity: Int,
        val unitCost: Double,
        val lineTotal: Double,
    )

    private fun parseLineItemRow(line: String): ParsedItemTuple? {
        FOUR_COL_REGEX.matchEntire(line)?.let { m ->
            val name = m.groupValues[1].trim()
            val qty = m.groupValues[2].toIntOrNull()?.coerceAtLeast(1) ?: 1
            val unit = m.groupValues[3].toCleanAmount() ?: 0.0
            val total = m.groupValues[4].toCleanAmount() ?: 0.0
            return ParsedItemTuple(name, qty, unit, total)
        }
        QTY_PREFIX_FOUR_COL_REGEX.matchEntire(line)?.let { m ->
            val qty = m.groupValues[1].toIntOrNull()?.coerceAtLeast(1) ?: 1
            val name = m.groupValues[2].trim()
            val unit = m.groupValues[3].toCleanAmount() ?: 0.0
            val total = m.groupValues[4].toCleanAmount() ?: 0.0
            return ParsedItemTuple(name, qty, unit, total)
        }
        NAME_QTY_AT_UNIT_TOTAL_REGEX.matchEntire(line)?.let { m ->
            val name = m.groupValues[1].trim()
            val qty = m.groupValues[2].toIntOrNull()?.coerceAtLeast(1) ?: 1
            val unit = m.groupValues[3].toCleanAmount() ?: 0.0
            val total = m.groupValues[4].toCleanAmount() ?: 0.0
            return ParsedItemTuple(name, qty, unit, total)
        }
        QTY_PREFIX_ITEM_REGEX.matchEntire(line)?.let { m ->
            val qty = m.groupValues[1].toIntOrNull()?.coerceAtLeast(1) ?: 1
            val name = m.groupValues[2].trim()
            val total = m.groupValues[3].toCleanAmount() ?: 0.0
            val unit = if (qty > 0) total / qty else total
            return ParsedItemTuple(name, qty, unit, total)
        }
        NAME_AMT_LINE_REGEX.matchEntire(line)?.let { m ->
            val name = m.groupValues[1].trim()
            val qty = m.groupValues[2].toIntOrNull()?.coerceAtLeast(1) ?: 1
            val total = m.groupValues[3].toCleanAmount() ?: 0.0
            val unit = if (qty > 0) total / qty else total
            return ParsedItemTuple(name, qty, unit, total)
        }
        return null
    }

    private fun mergeWrappedThermalItemLines(lines: List<String>): List<String> {
        if (lines.size < 2) return lines
        val merged = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val current = lines[i]
            val next = lines.getOrNull(i + 1)
            if (
                next != null &&
                current.any { it.isLetter() } &&
                !isNonItemSummaryOrHeader(current.lowercase()) &&
                parseLineItemRow(current) == null &&
                WRAPPED_QTY_UNIT_TOTAL_REGEX.matches(next)
            ) {
                val m = WRAPPED_QTY_UNIT_TOTAL_REGEX.matchEntire(next)!!
                val qty = m.groupValues[1]
                val unit = m.groupValues[2]
                val total = m.groupValues[3]
                merged += "$current $qty $unit $total"
                i += 2
            } else {
                merged += current
                i++
            }
        }
        return merged
    }

    private fun isNonItemSummaryOrHeader(lower: String): Boolean {
        return NON_ITEM_PREFIXES.any { prefix ->
            lower == prefix ||
                lower.startsWith("$prefix:") ||
                lower.startsWith("$prefix ") ||
                lower.contains(" $prefix ")
        }
    }

    private fun detectPaymentDetails(lines: List<String>): Pair<String, String> {
        var method = ""
        var ref = ""
        for (line in lines) {
            val lower = line.lowercase()
            when {
                lower.contains("m-pesa") || lower.contains("mpesa") || lower.contains("paybill") || lower.contains("till") -> {
                    if (method.isEmpty()) method = "M-Pesa"
                    if (ref.isEmpty()) {
                        ref = MPESA_REF_REGEX.find(line)?.groupValues?.getOrNull(1).orEmpty()
                    }
                }
                lower.contains("bank") || lower.contains("eft") || lower.contains("pesalink") -> {
                    if (method.isEmpty()) method = "Bank Transfer"
                }
                lower.contains("visa") || lower.contains("mastercard") || lower.contains("card") -> {
                    if (method.isEmpty()) method = "Card"
                }
                lower.startsWith("cash") || lower.contains("cash paid") || lower.contains("paid cash") -> {
                    if (method.isEmpty()) method = "Cash"
                }
            }
        }
        return method to ref
    }

    /**
     * Conservative matcher: returns a product ONLY if token overlap >= 0.50 AND
     * either there is no close runner-up or variant tokens distinguish the winner.
     * Uncertain/ambiguous OCR names return null so staff confirms manually.
     */
    internal fun findConservativeCatalogMatch(
        rawName: String,
        catalogue: List<ProductIdentity>,
    ): ProductIdentity? {
        val tokens = rawName.lowercase()
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .split(Regex("""\s+"""))
            .map { it.trim() }
            .filter { it.length >= 2 }
        if (tokens.isEmpty()) return null

        val scored = mutableListOf<Pair<ProductIdentity, Double>>()
        for (prod in catalogue) {
            val hay = "${prod.name} ${prod.baseName} ${prod.variantLabel.orEmpty()} ${prod.brandHint.orEmpty()} ${prod.sku.orEmpty()} ${prod.barcode.orEmpty()}"
                .lowercase()
            val hits = tokens.count { hay.contains(it) }
            if (hits == 0) continue
            val coverage = hits.toDouble() / tokens.size.toDouble()
            // Exact or numeric-variant match bonus
            val variantTokens = prod.variantLabel?.lowercase()?.split(Regex("""\s+"""))?.filter { it.length >= 2 }.orEmpty()
            val variantHit = variantTokens.isNotEmpty() && variantTokens.all { rawName.lowercase().contains(it) }
            val score = coverage + if (variantHit) 0.15 else 0.0
            if (coverage >= 0.50) {
                scored += prod to score
            }
        }
        if (scored.isEmpty()) return null
        val sorted = scored.sortedByDescending { it.second }
        val top = sorted[0]
        val second = sorted.getOrNull(1)
        if (second != null && abs(top.second - second.second) < 0.08) {
            return null
        }
        return top.first
    }

    private fun String.toCleanAmount(): Double? =
        replace(",", "").toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
}
