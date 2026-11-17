package com.emofah.agencies

import com.emofah.agencies.data.catalog.ProductDraftInput
import com.emofah.agencies.data.catalog.ProductDraftSuggestionExtractor
import com.emofah.agencies.data.catalog.ProductDraftValidator
import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.catalog.ProductRegistrationRpcOutcome
import com.emofah.agencies.data.catalog.SupabaseProductRegistrationClient
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueuePolicy
import com.emofah.agencies.data.sync.SyncQueueStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductRegistrationStage15Test {

    private fun sampleCatalogue(): List<ProductIdentity> = listOf(
        ProductIdentity(
            id = 18L,
            name = "Masking tape (medium)",
            baseName = "Masking tape",
            variantLabel = "medium",
            brandHint = null,
            category = "Stationery",
            sellPrice = 150.0,
            discountPrice = 0.0,
            buyPrice = 95.0,
            stockQty = 10,
            imageUrl = null,
            description = null,
            isFeatured = false,
            createdAt = null,
        ),
        ProductIdentity(
            id = 42L,
            name = "Kasuku Exercise Book 200 pgs",
            baseName = "Kasuku Exercise Book",
            variantLabel = "200 pgs",
            brandHint = "Kasuku",
            category = "Books",
            sellPrice = 120.0,
            discountPrice = 0.0,
            buyPrice = 80.0,
            stockQty = 24,
            imageUrl = null,
            description = null,
            isFeatured = false,
            createdAt = null,
        ),
    )

    @Test
    fun ocrExtractorSuggestsEditableNameBrandVariantAndCategory() {
        val suggestion = ProductDraftSuggestionExtractor.suggestFromOcr(
            ocrText = "KASUKU\nA4 Exercise Book 200 pgs\nRuled",
            cloudLabel = null,
        )

        assertEquals("KASUKU", suggestion.suggestedName)
        assertEquals("Kasuku", suggestion.suggestedBrand)
        assertEquals("Books", suggestion.suggestedCategory)
        assertEquals("A4 200 pgs", suggestion.suggestedVariant)
        assertTrue(suggestion.extractedOcrText.contains("Exercise Book"))

        val electronicsSuggestion = ProductDraftSuggestionExtractor.suggestFromOcr(
            ocrText = "SanDisk 32GB USB Flash Drive",
            cloudLabel = "SanDisk Flash Drive 32GB",
        )
        assertEquals("SanDisk", electronicsSuggestion.suggestedBrand)
        assertEquals("Electronics", electronicsSuggestion.suggestedCategory)
        assertEquals("32GB", electronicsSuggestion.suggestedVariant)
    }

    @Test
    fun validatorRequiresPositiveSellPriceAndRejectsInvalidNumbers() {
        val missingPrice = ProductDraftValidator.validate(
            input = ProductDraftInput(
                name = "Deli Stapler",
                category = "Stationery",
                sellPriceText = "",
                buyPriceText = "250",
                initialQtyText = "5",
            ),
            existingProducts = sampleCatalogue(),
        )
        assertFalse(missingPrice.isValid)
        assertTrue(missingPrice.fieldErrors.containsKey("sellPrice"))

        val zeroPrice = ProductDraftValidator.validate(
            input = ProductDraftInput(
                name = "Deli Stapler",
                category = "Stationery",
                sellPriceText = "0",
                initialQtyText = "5",
            ),
            existingProducts = sampleCatalogue(),
        )
        assertFalse(zeroPrice.isValid)
        assertTrue(zeroPrice.fieldErrors.containsKey("sellPrice"))

        val valid = ProductDraftValidator.validate(
            input = ProductDraftInput(
                name = "Stapler",
                brandHint = "Deli",
                variantLabel = "No. 10",
                category = "Stationery",
                sellPriceText = "350",
                buyPriceText = "220",
                initialQtyText = "6",
            ),
            existingProducts = sampleCatalogue(),
        )
        assertTrue(valid.isValid)
        assertEquals("Deli Stapler (No. 10)", valid.canonicalFullName)
        assertEquals(350.0, valid.parsedSellPrice, 0.001)
        assertEquals(220.0, valid.parsedBuyPrice, 0.001)
        assertEquals(6, valid.parsedInitialQty)
    }

    @Test
    fun duplicatePreventionBlocksExactMatchAndAllowsDistinctVariants() {
        val exactDuplicate = ProductDraftValidator.validate(
            input = ProductDraftInput(
                name = "Masking tape",
                variantLabel = "medium",
                category = "Stationery",
                sellPriceText = "150",
                initialQtyText = "4",
            ),
            existingProducts = sampleCatalogue(),
            allowNearDuplicateOverride = true,
        )
        assertFalse(exactDuplicate.isValid)
        assertTrue(exactDuplicate.hasExactDuplicate)
        assertTrue(exactDuplicate.fieldErrors.containsKey("duplicate"))

        val distinctVariant = ProductDraftValidator.validate(
            input = ProductDraftInput(
                name = "Masking tape",
                variantLabel = "small",
                category = "Stationery",
                sellPriceText = "100",
                initialQtyText = "4",
            ),
            existingProducts = sampleCatalogue(),
            allowNearDuplicateOverride = false,
        )
        assertTrue(distinctVariant.isValid)
        assertFalse(distinctVariant.hasExactDuplicate)
    }

    @Test
    fun registrationRpcResponseParserHandlesCommitReplayDuplicateAndMissingRpc() {
        val committed = SupabaseProductRegistrationClient.parseRpcResponse(
            200,
            """{"status":"COMMITTED","server_product_id":145,"name":"Deli Stapler","qty":6,"sell_price":350.0}""",
            imageUrl = "https://example.supabase.co/storage/v1/object/public/product-images/android-refs/d1.jpg",
        )
        assertTrue(committed is ProductRegistrationRpcOutcome.CommittedOnline)
        committed as ProductRegistrationRpcOutcome.CommittedOnline
        assertEquals(145L, committed.serverProductId)
        assertFalse(committed.alreadyCommitted)
        assertFalse(committed.matchedExistingDuplicate)

        val duplicateExisting = SupabaseProductRegistrationClient.parseRpcResponse(
            200,
            """{"status":"DUPLICATE_EXISTING","server_product_id":18,"name":"Masking tape (medium)","qty":10,"sell_price":150.0}""",
        )
        assertTrue(duplicateExisting is ProductRegistrationRpcOutcome.CommittedOnline)
        assertTrue((duplicateExisting as ProductRegistrationRpcOutcome.CommittedOnline).matchedExistingDuplicate)

        val missingRpc = SupabaseProductRegistrationClient.parseRpcResponse(
            404,
            """{"code":"PGRST202","message":"Could not find the function public.register_android_product"}""",
        )
        assertTrue(missingRpc is ProductRegistrationRpcOutcome.RpcMissing)

        val authErr = SupabaseProductRegistrationClient.parseRpcResponse(401, """{"message":"JWT expired"}""")
        assertTrue(authErr is ProductRegistrationRpcOutcome.AuthRequired)
    }

    @Test
    fun offlineDraftQueuePayloadParsesAndRelinksDependentSalePayload() {
        val draftEvent = SyncQueueEventEntity(
            eventId = "sync-draft-d1",
            eventType = SyncEventType.PRODUCT_DRAFT,
            entityId = "d1",
            dependencyEventId = null,
            payloadJson = """{"draft_id":"d1","idempotency_key":"prod-idem-1","local_temp_product_id":-1,"name":"Deli Stapler","category":"Stationery","sell_price":350.0,"buy_price":200.0,"initial_qty":5,"actor_id":"cashier1","device_id":"dev1"}""",
            actorId = "cashier1",
            deviceId = "dev1",
            status = SyncQueueStatus.QUEUED,
            retryCount = 0,
            nextRetryAtEpochMs = 1000L,
            createdAtEpochMs = 1000L,
        )

        val parsed = SyncQueuePolicy.parseProductDraftPayload(draftEvent)
        assertNotNull(parsed)
        assertEquals(-1L, parsed!!.localTempProductId)
        assertEquals("Deli Stapler", parsed.rpcRequest.name)
        assertEquals(350.0, parsed.rpcRequest.sellPrice, 0.001)

        val invalidDraft = draftEvent.copy(payloadJson = """{"draft_id":"d1","name":"X","sell_price":0}""")
        assertNull(SyncQueuePolicy.parseProductDraftPayload(invalidDraft))

        val originalSalePayload =
            """{"client_sale_id":"s1","idempotency_key":"idem-s1","product_id":-1,"qty_sold":2,"total_price":700.0,"items":[{"id":"s1-item-1","product_id":-1,"quantity":2}]}"""
        val relinked = SyncQueuePolicy.relinkSalePayloadProductId(originalSalePayload, newProductId = 145L)
        val root = JSONObject(relinked)
        assertEquals(145L, root.getLong("product_id"))
        assertEquals(145L, root.getJSONArray("items").getJSONObject(0).getLong("product_id"))
    }
}
