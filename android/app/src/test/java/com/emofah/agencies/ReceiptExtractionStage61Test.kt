package com.emofah.agencies

import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.purchases.ExtractedReceiptLineDraft
import com.emofah.agencies.purchases.PurchaseItemCategory
import com.emofah.agencies.purchases.PurchaseReceiptDraft
import com.emofah.agencies.purchases.ReceiptDraftValidator
import com.emofah.agencies.purchases.ReceiptExtractionSource
import com.emofah.agencies.purchases.ReceiptOcrParser
import com.emofah.agencies.purchases.SupabaseReceiptExtractionClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptExtractionStage61Test {

    private fun sampleCatalog(): List<ProductIdentity> = listOf(
        ProductIdentity(
            id = 12L,
            name = "A4 Exercise Book 200 Pages",
            baseName = "A4 Exercise Book",
            variantLabel = "200 Pages",
            brandHint = "Kartasi",
            category = "Books",
            sellPrice = 120.0,
            discountPrice = 0.0,
            buyPrice = 85.0,
            stockQty = 14,
            imageUrl = null,
            description = null,
            isFeatured = false,
            createdAt = null,
            isArchived = false,
        ),
        ProductIdentity(
            id = 45L,
            name = "Bic Ball Pen Blue",
            baseName = "Bic Ball Pen",
            variantLabel = "Blue",
            brandHint = "Bic",
            category = "Stationery",
            sellPrice = 25.0,
            discountPrice = 0.0,
            buyPrice = 15.0,
            stockQty = 50,
            imageUrl = null,
            description = null,
            isFeatured = false,
            createdAt = null,
            isArchived = false,
        ),
    )

    @Test
    fun offlineOcrParserExtractsSupplierRefDateItemsCategoriesAndLeavesDraftUnconfirmed() {
        val rawOcr = """
            NAIVAS WHOLESALERS LTD
            Receipt No: INV-88412
            15/11/2026
            10 x A4 Exercise Book 200 Pages 850.00
            2 Ream A4 Printing Paper 1,200.00
            1 Epson EcoTank Printer 28,500.00
            Grand Total: Ksh 30,550.00
        """.trimIndent()

        val draft = ReceiptOcrParser.parse(
            rawOcrText = rawOcr,
            catalogueProducts = sampleCatalog(),
            photoPath = "/tmp/receipt.jpg",
        )

        assertFalse(draft.isHumanConfirmed)
        assertEquals(ReceiptExtractionSource.OFFLINE_OCR, draft.extractionSource)
        assertEquals("NAIVAS WHOLESALERS LTD", draft.supplierName)
        assertEquals("INV-88412", draft.invoiceRef)
        assertEquals("15/11/2026", draft.receiptDateText)
        assertEquals(30550.0, draft.extractedTotal, 0.01)
        assertEquals(3, draft.lines.size)

        val bookLine = draft.lines[0]
        assertEquals(PurchaseItemCategory.RESALE_PRODUCT, bookLine.itemCategory)
        assertEquals(10, bookLine.quantity)
        assertEquals(85.0, bookLine.unitCost, 0.01)
        assertEquals(12L, bookLine.matchedProductId)

        val paperLine = draft.lines[1]
        assertEquals(PurchaseItemCategory.CONSUMABLE, paperLine.itemCategory)
        assertNull(paperLine.matchedProductId)

        val printerLine = draft.lines[2]
        assertEquals(PurchaseItemCategory.EQUIPMENT, printerLine.itemCategory)
        assertNull(printerLine.matchedProductId)
    }

    @Test
    fun edgeResponseParserRejectsInventedProductIdsNotPresentInCatalog() {
        val fakeJson = """
            {
              "supplier_name": "Nairobi Stationers",
              "invoice_ref": "NS-204",
              "receipt_date": "2026-11-16",
              "total_amount": 900,
              "warnings": [],
              "items": [
                {
                  "raw_name": "Bic Ball Pen Blue Box",
                  "qty": 2,
                  "unit_cost": 300,
                  "line_total": 600,
                  "suggested_item_type": "RESALE_PRODUCT",
                  "matched_product_id": 45,
                  "confidence_note": "Strong match"
                },
                {
                  "raw_name": "Mystery Fountain Pen",
                  "qty": 1,
                  "unit_cost": 300,
                  "line_total": 300,
                  "suggested_item_type": "RESALE_PRODUCT",
                  "matched_product_id": 999999,
                  "confidence_note": "Uncertain"
                }
              ]
            }
        """.trimIndent()

        val draft = SupabaseReceiptExtractionClient.parseEdgeResponseJson(
            rawJson = fakeJson,
            rawOcrText = "",
            catalogueProducts = sampleCatalog(),
            photoPath = "/tmp/r2.jpg",
        )

        assertFalse(draft.isHumanConfirmed)
        assertEquals(ReceiptExtractionSource.CLOUD_AI_AND_OCR, draft.extractionSource)
        assertEquals(2, draft.lines.size)
        assertEquals(45L, draft.lines[0].matchedProductId)
        assertNull(draft.lines[1].matchedProductId)
    }

    @Test
    fun validatorBlocksUnconfirmedDraftsAndNewResaleProductsWithoutSellingPrice() {
        val unconfirmed = PurchaseReceiptDraft(
            supplierName = "Supplier A",
            lines = listOf(
                ExtractedReceiptLineDraft(
                    rawName = "New Marker Set",
                    itemCategory = PurchaseItemCategory.RESALE_PRODUCT,
                    quantity = 5,
                    unitCost = 120.0,
                    lineTotal = 600.0,
                    registerAsNewProduct = true,
                    newProductSellPrice = null,
                )
            ),
            isHumanConfirmed = false,
        )

        val res1 = ReceiptDraftValidator.validate(unconfirmed)
        assertFalse(res1.isValid)
        assertTrue(res1.errors.any { it.contains("Human review confirmation", ignoreCase = true) })
        assertTrue(res1.errors.any { it.contains("Selling price", ignoreCase = true) })

        val validConfirmed = unconfirmed.copy(
            lines = listOf(
                unconfirmed.lines.first().copy(newProductSellPrice = 180.0)
            ),
            isHumanConfirmed = true,
        )
        val res2 = ReceiptDraftValidator.validate(validConfirmed)
        assertTrue(res2.isValid)
        assertTrue(res2.errors.isEmpty())
    }
}
