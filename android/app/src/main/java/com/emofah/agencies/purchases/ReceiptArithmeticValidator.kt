package com.emofah.agencies.purchases

import kotlin.math.abs
import kotlin.math.roundToInt

data class ReceiptArithmeticReport(
    val hasDiscrepancies: Boolean,
    val warnings: List<String>,
)

/**
 * Validates extracted receipt arithmetic without mutating any extracted figures:
 * - quantity * unitCost == lineTotal
 * - sum(lines) == subtotalAmount
 * - subtotal - discount + tax == extractedTotal (supporting both VAT-exclusive & VAT-inclusive receipts)
 */
object ReceiptArithmeticValidator {

    private const val TOLERANCE_KSH = 1.50

    fun validate(draft: PurchaseReceiptDraft): ReceiptArithmeticReport {
        val warnings = mutableListOf<String>()

        draft.lines.forEachIndexed { idx, line ->
            val label = line.rawName.ifBlank { "Item #${idx + 1}" }
            if (line.unitCost > 0.0 && line.lineTotal > 0.0) {
                val expected = line.quantity.coerceAtLeast(1) * line.unitCost
                if (abs(expected - line.lineTotal) > TOLERANCE_KSH) {
                    warnings += "$label: ${line.quantity} x ${fmt(line.unitCost)} = ${fmt(expected)} differs from printed line total ${fmt(line.lineTotal)}."
                }
            }
        }

        val linesSum = draft.computedLinesTotal
        if (draft.subtotalAmount > 0.0 && draft.lines.isNotEmpty() && abs(linesSum - draft.subtotalAmount) > TOLERANCE_KSH) {
            warnings += "Line items sum (${fmt(linesSum)}) differs from printed subtotal (${fmt(draft.subtotalAmount)})."
        }

        val baseForTotal = if (draft.subtotalAmount > 0.0) draft.subtotalAmount else linesSum
        if (draft.extractedTotal > 0.0 && baseForTotal > 0.0) {
            val exclusiveExpected = (baseForTotal - draft.discountAmount + draft.taxAmount).coerceAtLeast(0.0)
            val inclusiveExpected = (baseForTotal - draft.discountAmount).coerceAtLeast(0.0)
            val matchesExclusive = abs(exclusiveExpected - draft.extractedTotal) <= TOLERANCE_KSH
            val matchesInclusive = draft.taxAmount > 0.0 && abs(inclusiveExpected - draft.extractedTotal) <= TOLERANCE_KSH
            if (!matchesExclusive && !matchesInclusive) {
                warnings += "Expected total (${fmt(exclusiveExpected)}) differs from printed grand total (${fmt(draft.extractedTotal)}). Check discount, tax, or missing lines."
            }
        }

        return ReceiptArithmeticReport(
            hasDiscrepancies = warnings.isNotEmpty(),
            warnings = warnings,
        )
    }

    private fun fmt(value: Double): String {
        val rounded = (value * 100.0).roundToInt() / 100.0
        return if (rounded % 1.0 == 0.0) "Ksh ${rounded.toInt()}" else "Ksh %.2f".format(rounded)
    }
}
