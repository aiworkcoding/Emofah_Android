package com.emofah.agencies.purchases

import com.emofah.agencies.data.catalog.ProductIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptParserAndValidatorStageTest {

    private fun sampleCatalogue(): List<ProductIdentity> = listOf(
        ProductIdentity(
            id = 501L,
            name = "A4 Printing Paper Ream 80gsm",
            baseName = "A4 Printing Paper Ream",
            variantLabel = "80gsm",
            brandHint = null,
            category = "Stationery",
            sellPrice = 850.0,
            discountPrice = 0.0,
            buyPrice = 650.0,
            stockQty = 12,
            imageUrl = null,
            description = null,
            isFeatured = false,
            createdAt = null,
        ),
        ProductIdentity(
            id = 502L,
            name = "Masking Tape 24mm Medium",
            baseName = "Masking Tape 24mm",
            variantLabel = "Medium",
            brandHint = null,
            category = "Stationery",
            sellPrice = 150.0,
            discountPrice = 0.0,
            buyPrice = 95.0,
            stockQty = 20,
            imageUrl = null,
            description = null,
            isFeatured = false,
            createdAt = null,
        ),
        ProductIdentity(
            id = 503L,
            name = "Masking Tape 48mm Large",
            baseName = "Masking Tape 48mm",
            variantLabel = "Large",
            brandHint = null,
            category = "Stationery",
            sellPrice = 240.0,
            discountPrice = 0.0,
            buyPrice = 160.0,
            stockQty = 10,
            imageUrl = null,
            description = null,
            isFeatured = false,
            createdAt = null,
        ),
    )

    @Test
    fun parseSpatialLines_reconstructsMultiColumnInvoiceAndValidatesTotals() {
        val spatialLines = listOf(
            OcrSpatialLine("CROWN STATIONERY WHOLESALERS", left = 40, top = 20, right = 420, bottom = 50),
            OcrSpatialLine("TAX INVOICE NO: INV-8821", left = 40, top = 65, right = 300, bottom = 90),
            OcrSpatialLine("Date: 19/11/2026", left = 320, top = 66, right = 480, bottom = 91),
            // Split 4-column row
            OcrSpatialLine("A4 Printing Paper Ream 80gsm", left = 20, top = 130, right = 260, bottom = 155),
            OcrSpatialLine("3", left = 280, top = 132, right = 305, bottom = 156),
            OcrSpatialLine("650.00", left = 330, top = 131, right = 400, bottom = 155),
            OcrSpatialLine("1950.00", left = 420, top = 130, right = 500, bottom = 156),
            // Second row
            OcrSpatialLine("Masking Tape 24mm Medium 4 95.00 380.00", left = 20, top = 175, right = 500, bottom = 200),
            // Totals + payment
            OcrSpatialLine("Subtotal: Ksh 2,330.00", left = 20, top = 230, right = 480, bottom = 255),
            OcrSpatialLine("Discount: Ksh 30.00", left = 20, top = 265, right = 480, bottom = 290),
            OcrSpatialLine("VAT 16%: Ksh 368.00", left = 20, top = 300, right = 480, bottom = 325),
            OcrSpatialLine("Grand Total: Ksh 2,668.00", left = 20, top = 335, right = 480, bottom = 365),
            OcrSpatialLine("Paid via M-Pesa Ref SK89XYZ120", left = 20, top = 380, right = 480, bottom = 405),
        )

        val draft = ReceiptLayoutParser.parseSpatialLines(
            spatialLines = spatialLines,
            catalogueProducts = sampleCatalogue(),
        )

        assertEquals("CROWN STATIONERY WHOLESALERS", draft.supplierName)
        assertEquals("INV-8821", draft.invoiceRef)
        assertEquals("19/11/2026", draft.receiptDateText)
        assertEquals(2, draft.lines.size)
        assertEquals(501L, draft.lines[0].matchedProductId)
        assertEquals(3, draft.lines[0].quantity)
        assertEquals(650.0, draft.lines[0].unitCost, 0.01)
        assertEquals(1950.0, draft.lines[0].lineTotal, 0.01)
        assertEquals(502L, draft.lines[1].matchedProductId)

        assertEquals(2330.0, draft.subtotalAmount, 0.01)
        assertEquals(30.0, draft.discountAmount, 0.01)
        assertEquals(368.0, draft.taxAmount, 0.01)
        assertEquals(2668.0, draft.extractedTotal, 0.01)
        assertEquals("M-Pesa", draft.paymentMethod)
        assertEquals("SK89XYZ120", draft.paymentReference)

        val report = ReceiptArithmeticValidator.validate(draft)
        assertFalse("Valid receipt arithmetic should report no discrepancies", report.hasDiscrepancies)
    }

    @Test
    fun parseRawText_handlesWrapped58mmThermalLinesAndConservativeMatching() {
        val rawThermal = """
            NAIROBI HARDWARE MART
            Receipt # RCPT-409
            2026-11-20
            Masking Tape
            2 x 120.00 = 240.00
            Unknown Imported Drill Bit Set 1 450.00
            Total: Ksh 690.00
            Paid Cash
        """.trimIndent()

        val draft = ReceiptLayoutParser.parseRawText(
            rawOcrText = rawThermal,
            catalogueProducts = sampleCatalogue(),
        )

        assertEquals("NAIROBI HARDWARE MART", draft.supplierName)
        assertEquals("RCPT-409", draft.invoiceRef)
        assertEquals("Cash", draft.paymentMethod)
        assertEquals(2, draft.lines.size)

        // Ambiguous "Masking Tape" could be 24mm or 48mm -> must NOT auto-pick one blindly
        assertNull(draft.lines[0].matchedProductId)
        assertFalse(draft.lines[0].registerAsNewProduct)
        assertEquals(2, draft.lines[0].quantity)
        assertEquals(120.0, draft.lines[0].unitCost, 0.01)
        assertEquals(240.0, draft.lines[0].lineTotal, 0.01)

        // Unknown product stays unmatched and never auto-registers
        assertNull(draft.lines[1].matchedProductId)
        assertFalse(draft.lines[1].registerAsNewProduct)
    }

    @Test
    fun arithmeticValidator_flagsDiscrepanciesWithoutMutatingExtractedFigures() {
        val draft = PurchaseReceiptDraft(
            supplierName = "Supplier X",
            extractedTotal = 999.0,
            subtotalAmount = 500.0,
            discountAmount = 0.0,
            taxAmount = 0.0,
            lines = listOf(
                ExtractedReceiptLineDraft(
                    rawName = "Binder Clips Box",
                    quantity = 2,
                    unitCost = 100.0,
                    lineTotal = 250.0, // mismatch: 2 * 100 != 250
                )
            ),
        )

        val report = ReceiptArithmeticValidator.validate(draft)
        assertTrue(report.hasDiscrepancies)
        assertTrue("Expected line item mismatch warning", report.warnings.any { it.contains("differs from printed line total") })
        assertTrue("Expected subtotal mismatch warning", report.warnings.any { it.contains("differs from printed subtotal") })
        assertTrue("Expected total mismatch warning", report.warnings.any { it.contains("differs from printed grand total") })

        // Values remain strictly unmutated
        assertEquals(250.0, draft.lines[0].lineTotal, 0.001)
        assertEquals(500.0, draft.subtotalAmount, 0.001)
        assertEquals(999.0, draft.extractedTotal, 0.001)
    }

    @Test
    fun parseRawText_matchesCatalogResaleDespiteConsumableKeyword() {
        val rawText = """
            WHOLESALE STATIONERS
            Invoice: INV-991
            A4 Printing Paper Ream 80gsm 2 650.00 1300.00
            Total: Ksh 1300.00
        """.trimIndent()

        val draft = ReceiptLayoutParser.parseRawText(
            rawOcrText = rawText,
            catalogueProducts = sampleCatalogue(),
        )

        assertEquals(1, draft.lines.size)
        assertEquals(501L, draft.lines[0].matchedProductId)
        assertEquals(PurchaseItemCategory.RESALE_PRODUCT, draft.lines[0].itemCategory)
    }

    @Test
    fun parseRawText_rejectsFalsePositiveOnUnrelatedConsumables() {
        val rawText = """
            RANDOM SUPPLIERS
            Invoice: INV-992
            Box of Heavy Duty Staples 2 150.00 300.00
            Total: Ksh 300.00
        """.trimIndent()

        val draft = ReceiptLayoutParser.parseRawText(
            rawOcrText = rawText,
            catalogueProducts = sampleCatalogue(),
        )

        assertEquals(1, draft.lines.size)
        assertNull(draft.lines[0].matchedProductId)
    }
}
