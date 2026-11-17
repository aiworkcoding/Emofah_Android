package com.emofah.agencies

import androidx.exifinterface.media.ExifInterface
import com.emofah.agencies.camera.ImageQualityMath
import com.emofah.agencies.data.catalog.ProductDraftInput
import com.emofah.agencies.data.catalog.ProductDraftSuggestionExtractor
import com.emofah.agencies.data.catalog.ProductDraftValidator
import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.catalog.ProductMetadataExtractor
import com.emofah.agencies.data.catalog.RecognitionReferenceIndex
import com.emofah.agencies.data.catalog.SupabaseProductDto
import com.emofah.agencies.recognition.AliasExtractionPolicy
import com.emofah.agencies.recognition.CloudCandidateSuggestion
import com.emofah.agencies.recognition.CloudCostControlPolicy
import com.emofah.agencies.recognition.CloudEligibilityDecision
import com.emofah.agencies.recognition.CloudFallbackState
import com.emofah.agencies.recognition.CloudProductRecognitionClient
import com.emofah.agencies.recognition.CloudRecognitionValidator
import com.emofah.agencies.recognition.MatchConfidenceStatus
import com.emofah.agencies.recognition.RecognitionLearningPolicy
import com.emofah.agencies.recognition.RecognitionRanker
import com.emofah.agencies.recognition.RecognitionSelectionKind
import com.emofah.agencies.recognition.RecognitionSelectionOrigin
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Stage 10.1 — Image recognition end-to-end verification suite:
 * Exercises camera quality math, catalogue variant/archive indexing, on-device ranking edge cases,
 * cloud-assisted fallback budget/ID guardrails, confirmed learning loops, and unknown-item registration.
 */
class ImageRecognitionStage101Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun dto(
        id: Long,
        name: String,
        category: String = "Stationery",
        qty: Int = 15,
        sellPrice: Double = 100.0,
        isFeatured: Boolean = false,
        description: String? = null,
    ) = SupabaseProductDto(
        id = id,
        name = name,
        qty = qty,
        sellPrice = sellPrice,
        buyPrice = sellPrice * 0.7,
        imageUrl = null,
        category = category,
        createdAt = "2026-11-16T08:00:00Z",
        description = description,
        discountPrice = 0.0,
        isFeatured = isFeatured,
    )

    private fun catalogue(): List<ProductIdentity> = listOf(
        ProductMetadataExtractor.toProductIdentity(dto(101L, "Kasuku Exercise Book 200 pgs", category = "Books", sellPrice = 120.0)),
        ProductMetadataExtractor.toProductIdentity(dto(102L, "Kasuku Exercise Book 100 pgs", category = "Books", sellPrice = 70.0)),
        ProductMetadataExtractor.toProductIdentity(dto(201L, "Cristal bic pen (blue)", sellPrice = 25.0)),
        ProductMetadataExtractor.toProductIdentity(dto(202L, "Cristal bic pen (black)", sellPrice = 25.0)),
        ProductMetadataExtractor.toProductIdentity(dto(301L, "Masking tape (medium)", sellPrice = 150.0)),
        ProductMetadataExtractor.toProductIdentity(dto(999L, "Discontinued Kasuku Book", category = "Books", sellPrice = 90.0), isArchived = true),
    )

    @Test
    fun captureQualityAndExifEdgeCasesHandleDegenerateAndFlippedInputs() {
        // 0x0 or negative dimensions clamp safely to 1x1 without divide-by-zero
        assertEquals(1 to 1, ImageQualityMath.computeScaledDimensions(0, 1200, 640))
        assertEquals(1 to 1, ImageQualityMath.computeScaledDimensions(800, -5, 640))

        // Empty or sub-3x3 luma grid safely triggers dark + blurry warnings
        val tiny = ImageQualityMath.assessLumaGrid(intArrayOf(200, 200), 2, 1)
        assertTrue(tiny.isTooDark)
        assertTrue(tiny.isTooBlurry)
        assertNotNull(tiny.warningMessage)

        // Bright but flat (out-of-focus) capture warns only on blur, not darkness
        val brightFlat = IntArray(24 * 24) { 180 }
        val blurryOnly = ImageQualityMath.assessLumaGrid(brightFlat, 24, 24)
        assertFalse(blurryOnly.isTooDark)
        assertTrue(blurryOnly.isTooBlurry)
        assertTrue(blurryOnly.warningMessage!!.contains("blurry", ignoreCase = true))

        // Transpose / transverse EXIF tags combine rotation + horizontal flip
        assertEquals(90, ImageQualityMath.exifOrientationToDegrees(ExifInterface.ORIENTATION_TRANSPOSE))
        assertEquals(270, ImageQualityMath.exifOrientationToDegrees(ExifInterface.ORIENTATION_TRANSVERSE))
        assertTrue(ImageQualityMath.isHorizontalFlip(ExifInterface.ORIENTATION_TRANSPOSE))
        assertTrue(ImageQualityMath.isHorizontalFlip(ExifInterface.ORIENTATION_FLIP_VERTICAL))
    }

    @Test
    fun punctuationOnlyProductNameDoesNotFalselyBoostUnrelatedOcr() {
        val junkProduct = ProductMetadataExtractor.toProductIdentity(dto(501L, "--- / ***"))
        val realProduct = ProductMetadataExtractor.toProductIdentity(dto(101L, "Kasuku Exercise Book 200 pgs", category = "Books"))
        val products = listOf(junkProduct, realProduct)
        val index = RecognitionReferenceIndex().apply {
            rebuildFromProducts(products, nowIso = "2026-11-16T10:00:00Z")
        }

        val outcome = RecognitionRanker.rankCandidates(
            captureId = "cap-punct",
            preservedPhotoPath = "/tmp/punct.jpg",
            captureDHashHex = null,
            ocrText = "RANDOM UNLISTED SOAP BAR 500G",
            activeEntries = index.getActiveEntries(),
            productsById = products.associateBy { it.id },
            projectionsById = emptyMap(),
            cachedPhotoPathsById = emptyMap(),
            maxCandidates = 0, // Also verifies non-positive maxCandidates guard
        )

        assertEquals(MatchConfidenceStatus.UNIDENTIFIED, outcome.status)
        assertTrue(outcome.candidates.isEmpty())
    }

    @Test
    fun variantDisambiguationSeparates200PgsFrom100PgsAndFlagsPartialScanAmbiguous() {
        val products = catalogue()
        val activeMap = products.filter { !it.isArchived }.associateBy { it.id }
        val index = RecognitionReferenceIndex().apply {
            rebuildFromProducts(products, nowIso = "2026-11-16T10:00:00Z")
        }

        // Exact OCR with variant clue picks 200 pgs as strong match
        val exact200 = RecognitionRanker.rankCandidates(
            captureId = "cap-200",
            preservedPhotoPath = "/tmp/book200.jpg",
            captureDHashHex = null,
            ocrText = "KASUKU EXERCISE BOOK 200 PGS",
            activeEntries = index.getActiveEntries(),
            productsById = activeMap,
            projectionsById = emptyMap(),
            cachedPhotoPathsById = emptyMap(),
        )
        assertEquals(MatchConfidenceStatus.STRONG_MATCH, exact200.status)
        assertEquals(101L, exact200.candidates.first().product.id)

        // Partial OCR omitting variant remains ambiguous and never exposes archived ID 999L
        val partialBook = RecognitionRanker.rankCandidates(
            captureId = "cap-partial",
            preservedPhotoPath = "/tmp/book_partial.jpg",
            captureDHashHex = null,
            ocrText = "KASUKU EXERCISE BOOK",
            activeEntries = index.getActiveEntries(),
            productsById = activeMap,
            projectionsById = emptyMap(),
            cachedPhotoPathsById = emptyMap(),
        )
        assertEquals(MatchConfidenceStatus.AMBIGUOUS_ALTERNATIVES, partialBook.status)
        val returnedIds = partialBook.candidates.map { it.product.id }
        assertTrue(returnedIds.containsAll(listOf(101L, 102L)))
        assertFalse(returnedIds.contains(999L))
    }

    @Test
    fun indexSearchHandlesBlankAndNegativeLimitsSafelyWhileExcludingArchived() {
        val indexFile = tempFolder.newFile("stage101_index.json")
        val index = RecognitionReferenceIndex(indexFile)
        index.rebuildFromProducts(catalogue(), nowIso = "2026-11-16T10:00:00Z")

        assertTrue(index.searchByText("Kasuku", limit = -5).isEmpty())
        assertTrue(index.searchByText("Discontinued").isEmpty())
        assertEquals(5, index.searchByText("   ", limit = 10).size)

        val reload = RecognitionReferenceIndex(indexFile)
        val snap = reload.loadFromDisk()
        assertNotNull(snap)
        assertEquals(5, snap!!.activeIndexCount)
    }

    @Test
    fun cloudFallbackPolicyEnforcesAmbiguityPauseForceRetryAndBudgetGuards() {
        val products = catalogue().filter { !it.isArchived }
        val activeMap = products.associateBy { it.id }
        val index = RecognitionReferenceIndex().apply {
            rebuildFromProducts(products, nowIso = "2026-11-16T10:00:00Z")
        }

        val ambiguousLocal = RecognitionRanker.rankCandidates(
            captureId = "cap-ambig-cloud",
            preservedPhotoPath = "/tmp/pen_ambig.jpg",
            captureDHashHex = null,
            ocrText = "CRISTAL BIC PEN",
            activeEntries = index.getActiveEntries(),
            productsById = activeMap,
            projectionsById = emptyMap(),
            cachedPhotoPathsById = emptyMap(),
        )
        assertEquals(MatchConfidenceStatus.AMBIGUOUS_ALTERNATIVES, ambiguousLocal.status)

        // Default auto-fallback skips burning cloud AI on ambiguous local hits until user taps Try Cloud Match
        val passiveDecision = CloudCostControlPolicy.evaluateAutoFallback(
            localOutcome = ambiguousLocal,
            isOnline = true,
            hasConfiguredEndpoint = true,
            hasCachedProducts = true,
            cloudCallsToday = 3,
            forceRequested = false,
        )
        assertTrue(passiveDecision is CloudEligibilityDecision.Skip)
        assertEquals(CloudFallbackState.READY_TO_TRY, (passiveDecision as CloudEligibilityDecision.Skip).state)

        // Explicit operator retry proceeds while under daily budget
        val forcedDecision = CloudCostControlPolicy.evaluateAutoFallback(
            localOutcome = ambiguousLocal,
            isOnline = true,
            hasConfiguredEndpoint = true,
            hasCachedProducts = true,
            cloudCallsToday = 3,
            forceRequested = true,
        )
        assertTrue(forcedDecision is CloudEligibilityDecision.Eligible)

        // Compact payload preserves IDs & excludes archived items
        val compact = CloudCostControlPolicy.buildCompactCatalogueContext(
            products = catalogue(),
            ocrText = "KASUKU 200 PGS",
            maxItems = 0, // verifies coerceAtLeast(1) guard
        )
        assertEquals(1, compact.size)
        assertEquals(101L, compact.first().id)

        val requestJson = CloudProductRecognitionClient.buildRequestPayload(
            imageBase64 = "ZmFrZQ==",
            ocrText = "KASUKU 200 PGS",
            contextItems = compact,
        )
        val reqObj = JSONObject(requestJson)
        assertEquals("image/jpeg", reqObj.getString("mime_type"))
        assertEquals(1, reqObj.getJSONArray("catalog_context").length())
    }

    @Test(expected = JSONException::class)
    fun cloudResponseParserRejectsMalformedHtmlOrNonJsonResponses() {
        CloudProductRecognitionClient.parseResponseJson("<html>502 Bad Gateway</html>")
    }

    @Test
    fun cloudMergePreservesExistingLocalMatchForHumanConfirmationAndRejectsArchivedIds() {
        val products = catalogue()
        val activeMap = products.filter { !it.isArchived }.associateBy { it.id }
        val index = RecognitionReferenceIndex().apply {
            rebuildFromProducts(products, nowIso = "2026-11-16T10:00:00Z")
        }

        val localUnidentified = RecognitionRanker.rankCandidates(
            captureId = "cap-cloud-merge",
            preservedPhotoPath = "/tmp/tape_photo.jpg",
            captureDHashHex = null,
            ocrText = "ADHESIVE ROLL",
            activeEntries = index.getActiveEntries(),
            productsById = activeMap,
            projectionsById = emptyMap(),
            cachedPhotoPathsById = emptyMap(),
        )
        assertEquals(MatchConfidenceStatus.UNIDENTIFIED, localUnidentified.status)

        val validation = CloudRecognitionValidator.validateSuggestions(
            suggestions = listOf(
                CloudCandidateSuggestion(productId = 999L, reason = "Archived should be blocked"),
                CloudCandidateSuggestion(productId = 88888L, reason = "Unknown ID should be blocked"),
                CloudCandidateSuggestion(productId = 301L, reason = "Matches cream adhesive roll"),
            ),
            activeProductsById = activeMap,
            projectionsById = emptyMap(),
            cachedPhotoPathsById = emptyMap(),
            extractedLabel = "Masking Tape Roll",
        )

        assertEquals(listOf(999L, 88888L), validation.rejectedUnknownIds)
        assertEquals(listOf(301L), validation.validatedCandidates.map { it.product.id })

        val merged = CloudRecognitionValidator.mergeWithLocalOutcome(
            localOutcome = localUnidentified,
            validation = validation,
            fromCloudCache = true,
            fallbackState = CloudFallbackState.CACHE_HIT,
            note = "Cached cloud match",
        )
        assertEquals(MatchConfidenceStatus.AMBIGUOUS_ALTERNATIVES, merged.status)
        assertTrue(merged.usedCloudAssist)
        assertEquals("ADHESIVE ROLL Masking Tape Roll", merged.extractedOcrText)
        assertEquals("/tmp/tape_photo.jpg", merged.preservedPhotoPath)
    }

    @Test
    fun confirmedLearningFiltersShelfPriceTagsAndBoostsSubsequentScan() {
        // Price-prefixed OCR tags must never become learned product aliases
        val filteredAlias = AliasExtractionPolicy.extractUsefulAlias(
            cloudLabel = null,
            ocrText = "Price: 150\nTotal: 300\nQty: 2\nKsh 150 only\nSHURTAPE PRO 48MM",
            confirmedProductName = "Masking tape (medium)",
        )
        assertEquals("SHURTAPE PRO 48MM", filteredAlias)

        val products = catalogue()
        val activeMap = products.filter { !it.isArchived }.associateBy { it.id }
        val index = RecognitionReferenceIndex().apply {
            rebuildFromProducts(products, nowIso = "2026-11-16T10:00:00Z")
        }

        val initialOutcome = RecognitionRanker.rankCandidates(
            captureId = "cap-learn-1",
            preservedPhotoPath = "/tmp/tape_new.jpg",
            captureDHashHex = "abcdabcd12341234",
            ocrText = "SHURTAPE PRO 48MM",
            activeEntries = index.getActiveEntries(),
            productsById = activeMap,
            projectionsById = emptyMap(),
            cachedPhotoPathsById = emptyMap(),
        )
        assertEquals(MatchConfidenceStatus.UNIDENTIFIED, initialOutcome.status)

        // Unconfirmed selection is strictly blocked
        val blockedPlan = RecognitionLearningPolicy.buildPlan(
            outcome = initialOutcome,
            confirmedProduct = activeMap.getValue(301L),
            selectionOrigin = RecognitionSelectionOrigin.MANUAL_SEARCH,
            allowImageAssociation = true,
            isHumanConfirmed = false,
        )
        assertFalse(blockedPlan.shouldRecordLearning)

        // Confirmed manual override generates alias + dHash learning plan
        val confirmedPlan = RecognitionLearningPolicy.buildPlan(
            outcome = initialOutcome,
            confirmedProduct = activeMap.getValue(301L),
            selectionOrigin = RecognitionSelectionOrigin.MANUAL_SEARCH,
            allowImageAssociation = true,
            isHumanConfirmed = true,
        )
        assertTrue(confirmedPlan.shouldRecordLearning)
        assertEquals(RecognitionSelectionKind.MANUAL_SEARCH_OVERRIDE, confirmedPlan.selectionKind)
        assertEquals("SHURTAPE PRO 48MM", confirmedPlan.learnedAlias)
        assertEquals("abcdabcd12341234", confirmedPlan.visualDHashHex)

        index.addConfirmedAlias(301L, confirmedPlan.learnedAlias!!)
        index.attachVisualSignature(301L, confirmedPlan.visualDHashHex!!)

        val improvedOutcome = RecognitionRanker.rankCandidates(
            captureId = "cap-learn-2",
            preservedPhotoPath = "/tmp/tape_new2.jpg",
            captureDHashHex = "abcdabcd12341234",
            ocrText = "SHURTAPE PRO 48MM",
            activeEntries = index.getActiveEntries(),
            productsById = activeMap,
            projectionsById = emptyMap(),
            cachedPhotoPathsById = emptyMap(),
        )
        assertEquals(MatchConfidenceStatus.STRONG_MATCH, improvedOutcome.status)
        assertEquals(301L, improvedOutcome.candidates.first().product.id)
    }

    @Test
    fun unknownProductHandoffValidatesDuplicateProtectionAndSellingPriceRequirement() {
        val suggestion = ProductDraftSuggestionExtractor.suggestFromOcr(
            ocrText = "DELI\nHeavy Duty Stapler\nNo. 10",
            cloudLabel = null,
        )
        assertEquals("DELI", suggestion.suggestedName)
        assertEquals("Deli", suggestion.suggestedBrand)
        assertEquals("Stationery", suggestion.suggestedCategory)

        val duplicateResult = ProductDraftValidator.validate(
            input = ProductDraftInput(
                name = "Masking tape",
                variantLabel = "medium",
                category = "Stationery",
                sellPriceText = "155",
                initialQtyText = "3",
                preservedPhotoPath = "/tmp/unknown.jpg",
            ),
            existingProducts = catalogue(),
        )
        assertFalse(duplicateResult.isValid)
        assertTrue(duplicateResult.hasExactDuplicate)

        val invalidPriceResult = ProductDraftValidator.validate(
            input = ProductDraftInput(
                name = "Deli Heavy Duty Stapler",
                variantLabel = "No. 10",
                category = "Stationery",
                sellPriceText = "0",
                initialQtyText = "3",
                preservedPhotoPath = "/tmp/unknown.jpg",
            ),
            existingProducts = catalogue(),
        )
        assertFalse(invalidPriceResult.isValid)
        assertTrue(invalidPriceResult.fieldErrors.containsKey("sellPrice"))

        val validResult = ProductDraftValidator.validate(
            input = ProductDraftInput(
                name = "Heavy Duty Stapler",
                brandHint = "Deli",
                variantLabel = "No. 10",
                category = "Stationery",
                sellPriceText = "450",
                buyPriceText = "310",
                initialQtyText = "4",
                preservedPhotoPath = "/tmp/unknown.jpg",
            ),
            existingProducts = catalogue(),
        )
        assertTrue(validResult.isValid)
        assertEquals("Deli Heavy Duty Stapler (No. 10)", validResult.canonicalFullName)
        assertEquals(450.0, validResult.parsedSellPrice, 0.001)
    }
}
