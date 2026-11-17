package com.emofah.agencies

import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.catalog.ProductMetadataExtractor
import com.emofah.agencies.data.catalog.SupabaseProductDto
import com.emofah.agencies.data.local.entities.LocalStockProjectionEntity
import com.emofah.agencies.recognition.CloudCandidateSuggestion
import com.emofah.agencies.recognition.CloudCostControlPolicy
import com.emofah.agencies.recognition.CloudEligibilityDecision
import com.emofah.agencies.recognition.CloudFallbackState
import com.emofah.agencies.recognition.CloudProductRecognitionClient
import com.emofah.agencies.recognition.CloudRecognitionValidator
import com.emofah.agencies.recognition.MatchConfidenceStatus
import com.emofah.agencies.recognition.RecognitionCandidate
import com.emofah.agencies.recognition.RecognitionOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudRecognitionStage14Test {

    private fun product(
        id: Long,
        name: String,
        category: String = "Stationery",
        isArchived: Boolean = false,
        isFeatured: Boolean = false,
    ): ProductIdentity {
        return ProductMetadataExtractor.toProductIdentity(
            SupabaseProductDto(
                id = id,
                name = name,
                qty = 12,
                sellPrice = 150.0,
                buyPrice = 100.0,
                imageUrl = null,
                category = category,
                createdAt = null,
                description = null,
                discountPrice = 0.0,
                isFeatured = isFeatured,
            )
        ).copy(isArchived = isArchived)
    }

    private fun sampleOutcome(
        status: MatchConfidenceStatus,
        candidates: List<RecognitionCandidate> = emptyList(),
        ocr: String = "",
    ): RecognitionOutcome {
        return RecognitionOutcome(
            status = status,
            candidates = candidates,
            extractedOcrText = ocr,
            captureId = "cap-stage14",
            preservedPhotoPath = "/tmp/preserved_orig.jpg",
            statusLabel = "Local status",
        )
    }

    @Test
    fun strongLocalMatchNeverTriggersAutoCloudCall() {
        val p18 = product(18L, "Masking tape (medium)")
        val strong = sampleOutcome(
            status = MatchConfidenceStatus.STRONG_MATCH,
            candidates = listOf(
                RecognitionCandidate(
                    product = p18,
                    stockProjection = null,
                    cachedPhotoPath = null,
                    internalScore = 0.86,
                    matchedReasons = listOf("Visual match"),
                )
            ),
        )

        val decision = CloudCostControlPolicy.evaluateAutoFallback(
            localOutcome = strong,
            isOnline = true,
            hasConfiguredEndpoint = true,
            hasCachedProducts = true,
            cloudCallsToday = 0,
            forceRequested = false,
        )

        assertTrue(decision is CloudEligibilityDecision.Skip)
        assertEquals(CloudFallbackState.NOT_NEEDED, (decision as CloudEligibilityDecision.Skip).state)
    }

    @Test
    fun unidentifiedLocalMatchSkipsCloudWhenOfflineAndPreservesPhoto() {
        val unidentified = sampleOutcome(MatchConfidenceStatus.UNIDENTIFIED)

        val decision = CloudCostControlPolicy.evaluateAutoFallback(
            localOutcome = unidentified,
            isOnline = false,
            hasConfiguredEndpoint = true,
            hasCachedProducts = true,
            cloudCallsToday = 0,
            forceRequested = false,
        )

        assertTrue(decision is CloudEligibilityDecision.Skip)
        assertEquals(CloudFallbackState.OFFLINE_SKIPPED, (decision as CloudEligibilityDecision.Skip).state)
        assertEquals("/tmp/preserved_orig.jpg", unidentified.preservedPhotoPath)
    }

    @Test
    fun dailyBudgetLimitStopsRunawayCloudCalls() {
        val unidentified = sampleOutcome(MatchConfidenceStatus.UNIDENTIFIED)

        val decision = CloudCostControlPolicy.evaluateAutoFallback(
            localOutcome = unidentified,
            isOnline = true,
            hasConfiguredEndpoint = true,
            hasCachedProducts = true,
            cloudCallsToday = CloudCostControlPolicy.MAX_DAILY_CLOUD_CALLS,
            forceRequested = true,
        )

        assertTrue(decision is CloudEligibilityDecision.Skip)
        assertEquals(CloudFallbackState.BUDGET_LIMIT_REACHED, (decision as CloudEligibilityDecision.Skip).state)
    }

    @Test
    fun validatorRejectsUnknownAndArchivedProductIdsWhileKeepingValidIds() {
        val p18 = product(18L, "Masking tape (medium)")
        val p20 = product(20L, "Masking tape (large)")
        val p99Archived = product(99L, "Old tape", isArchived = true)
        val activeMap = mapOf(18L to p18, 20L to p20)
        val projections = mapOf(
            18L to LocalStockProjectionEntity(
                productId = 18L,
                serverQty = 8,
                serverUpdatedAtEpochMs = 1_000_000L,
                pendingDeltaQty = 0,
                provisionalAvailableQty = 8,
            )
        )

        val result = CloudRecognitionValidator.validateSuggestions(
            suggestions = listOf(
                CloudCandidateSuggestion(productId = 9999L, reason = "Fabricated ID"),
                CloudCandidateSuggestion(productId = p99Archived.id, reason = "Archived item"),
                CloudCandidateSuggestion(productId = 18L, reason = "Blue masking roll label"),
                CloudCandidateSuggestion(productId = 18L, reason = "Duplicate suggestion"),
                CloudCandidateSuggestion(productId = 20L, reason = "Alternative size"),
            ),
            activeProductsById = activeMap,
            projectionsById = projections,
            cachedPhotoPathsById = emptyMap(),
            extractedLabel = "Masking Tape",
        )

        assertEquals(listOf(9999L, 99L), result.rejectedUnknownIds)
        assertEquals(listOf(18L, 20L), result.validatedCandidates.map { it.product.id })
        assertEquals("Masking Tape", result.extractedLabel)
    }

    @Test
    fun mergedCloudMatchesAlwaysRequireHumanConfirmation() {
        val p18 = product(18L, "Masking tape (medium)")
        val localUnidentified = sampleOutcome(MatchConfidenceStatus.UNIDENTIFIED)
        val validation = CloudRecognitionValidator.validateSuggestions(
            suggestions = listOf(
                CloudCandidateSuggestion(productId = 18L, reason = "Matches medium masking tape"),
            ),
            activeProductsById = mapOf(18L to p18),
            projectionsById = emptyMap(),
            cachedPhotoPathsById = emptyMap(),
            extractedLabel = "Masking tape",
        )

        val merged = CloudRecognitionValidator.mergeWithLocalOutcome(
            localOutcome = localUnidentified,
            validation = validation,
            fromCloudCache = false,
            fallbackState = CloudFallbackState.MATCHES_FOUND,
            note = "Cloud suggestions matched against your Emofah catalogue. Please confirm.",
        )

        assertEquals(MatchConfidenceStatus.AMBIGUOUS_ALTERNATIVES, merged.status)
        assertTrue(merged.usedCloudAssist)
        assertEquals(1, merged.candidates.size)
        assertEquals(18L, merged.candidates.first().product.id)
    }

    @Test
    fun compactContextPrioritizesOcrMatchingItemsWithinBound() {
        val products = (1L..150L).map { idx ->
            when (idx) {
                140L -> product(idx, "Kasuku Exercise Book 200 pgs", category = "Books")
                else -> product(idx, "Stationery Item #$idx")
            }
        }

        val context = CloudCostControlPolicy.buildCompactCatalogueContext(
            products = products,
            ocrText = "KASUKU 200 PGS",
            maxItems = 25,
        )

        assertEquals(25, context.size)
        assertEquals(140L, context.first().id)
        assertFalse(context.any { it.id <= 0L })
    }

    @Test
    fun cloudResponseParserHandlesMalformedAndValidCandidatesSafely() {
        val raw = """
            {
              "extracted_label": "Bic Cristal Blue",
              "candidates": [
                {"product_id": 28, "reason": "Matches blue pen packaging"},
                {"product_id": null, "reason": "Ignored null"},
                {"product_id": -5, "reason": "Ignored negative"}
              ]
            }
        """.trimIndent()

        val parsed = CloudProductRecognitionClient.parseResponseJson(raw)
        assertEquals("Bic Cristal Blue", parsed.extractedLabel)
        assertEquals(3, parsed.suggestions.size)
        assertEquals(28L, parsed.suggestions[0].productId)
        assertEquals(null, parsed.suggestions[1].productId)
        assertEquals(null, parsed.suggestions[2].productId)
    }
}
