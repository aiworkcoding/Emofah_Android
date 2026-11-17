package com.emofah.agencies

import com.emofah.agencies.data.catalog.ProductDraftInput
import com.emofah.agencies.data.catalog.ProductDraftSuggestionExtractor
import com.emofah.agencies.data.catalog.ProductDraftValidator
import com.emofah.agencies.data.catalog.ProductIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StockProductRegistrationStage45Test {

    @Test
    fun supportsSearchHintSuggestionAndStockOpeningQtyValidation() {
        val suggestion = ProductDraftSuggestionExtractor.suggestFromOcr(
            ocrText = "",
            cloudLabel = "Casio FX-82MS Calculator",
        )
        assertEquals("Casio FX-82MS Calculator", suggestion.suggestedName)
        assertEquals("Casio", suggestion.suggestedBrand)
        assertEquals("Electronics", suggestion.suggestedCategory)

        val validZeroStock = ProductDraftValidator.validate(
            input = ProductDraftInput(
                name = "Counter Book 3Q",
                brandHint = "Kasuku",
                variantLabel = "200 pgs",
                category = "Books",
                sellPriceText = "320",
                buyPriceText = "240",
                initialQtyText = "0",
            ),
            existingProducts = emptyList(),
        )
        assertTrue(validZeroStock.isValid)
        assertEquals(0, validZeroStock.parsedInitialQty)
        assertEquals("Kasuku Counter Book 3Q (200 pgs)", validZeroStock.canonicalFullName)

        val negativeQty = ProductDraftValidator.validate(
            input = ProductDraftInput(
                name = "Counter Book 3Q",
                sellPriceText = "320",
                initialQtyText = "-4",
            ),
            existingProducts = emptyList(),
        )
        assertFalse(negativeQty.isValid)
        assertTrue(negativeQty.fieldErrors.containsKey("initialQty"))
    }

    @Test
    fun blocksExactDuplicateFromStockFlowAgainstActiveOrOfflineTempCatalogue() {
        val existing = listOf(
            ProductIdentity(
                id = -1L,
                name = "Deli Heavy Duty Stapler",
                baseName = "Deli Heavy Duty Stapler",
                variantLabel = null,
                brandHint = "Deli",
                category = "Stationery",
                sellPrice = 850.0,
                discountPrice = 0.0,
                buyPrice = 600.0,
                stockQty = 6,
                imageUrl = null,
                description = null,
                isFeatured = false,
                createdAt = null,
            )
        )

        val dupResult = ProductDraftValidator.validate(
            input = ProductDraftInput(
                name = "Deli Heavy Duty Stapler",
                sellPriceText = "900",
                initialQtyText = "3",
            ),
            existingProducts = existing,
        )
        assertFalse(dupResult.isValid)
        assertTrue(dupResult.hasExactDuplicate)
        assertTrue(dupResult.fieldErrors.containsKey("duplicate"))
    }
}
