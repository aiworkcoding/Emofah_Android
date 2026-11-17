package com.emofah.agencies

import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.catalog.ProductMetadataExtractor
import com.emofah.agencies.data.catalog.RecognitionReferenceIndex
import com.emofah.agencies.data.catalog.SupabaseProductDto
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueuePolicy
import com.emofah.agencies.data.sync.SyncQueueStatus
import com.emofah.agencies.recognition.AliasExtractionPolicy
import com.emofah.agencies.recognition.MatchConfidenceStatus
import com.emofah.agencies.recognition.RecognitionCandidate
import com.emofah.agencies.recognition.RecognitionCorrectionRpcOutcome
import com.emofah.agencies.recognition.RecognitionLearningPolicy
import com.emofah.agencies.recognition.RecognitionOutcome
import com.emofah.agencies.recognition.RecognitionRanker
import com.emofah.agencies.recognition.RecognitionSelectionKind
import com.emofah.agencies.recognition.RecognitionSelectionOrigin
import com.emofah.agencies.recognition.SupabaseRecognitionCorrectionClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionLearningStage16Test {

    private fun product(id: Long, name: String, category: String = "Stationery"): ProductIdentity {
        return ProductMetadataExtractor.toProductIdentity(
            SupabaseProductDto(
                id = id,
                name = name,
                qty = 10,
                sellPrice = 150.0,
                buyPrice = 95.0,
                imageUrl = null,
                category = category,
                createdAt = null,
                description = null,
                discountPrice = 0.0,
                isFeatured = false,
            )
        )
    }

    @Test
    fun aliasExtractionFiltersNoiseAndExtractsDistinctPackagingClue() {
        assertNull(
            AliasExtractionPolicy.extractUsefulAlias(
                cloudLabel = null,
                ocrText = "Ksh 150\n123456\nMade in Kenya",
                confirmedProductName = "Masking tape (medium)",
            )
        )

        assertNull(
            AliasExtractionPolicy.extractUsefulAlias(
                cloudLabel = "Masking tape (medium)",
                ocrText = "Masking tape (medium)",
                confirmedProductName = "Masking tape (medium)",
            )
        )

        val extracted = AliasExtractionPolicy.extractUsefulAlias(
            cloudLabel = null,
            ocrText = "Ksh 40\nBIC CRISTAL ORIGINAL\nMade in Kenya",
            confirmedProductName = "Ball pen (blue)",
        )
        assertEquals("BIC CRISTAL ORIGINAL", extracted)
    }

    @Test
    fun unconfirmedGuessesNeverTriggerLearningOrImageAssociation() {
        val p18 = product(18L, "Masking tape (medium)")
        val outcome = RecognitionOutcome(
            status = MatchConfidenceStatus.AMBIGUOUS_ALTERNATIVES,
            candidates = listOf(
                RecognitionCandidate(
                    product = p18,
                    stockProjection = null,
                    cachedPhotoPath = null,
                    internalScore = 0.52,
                    matchedReasons = listOf("Packaging text match"),
                )
            ),
            extractedOcrText = "SHURTAPE PRO",
            captureId = "cap-unconfirmed",
            preservedPhotoPath = "/tmp/cap.jpg",
            statusLabel = "Ambiguous",
            captureDHashHex = "0f0f0f0f0f0f0f0f",
        )

        val plan = RecognitionLearningPolicy.buildPlan(
            outcome = outcome,
            confirmedProduct = p18,
            selectionOrigin = RecognitionSelectionOrigin.CANDIDATE,
            allowImageAssociation = true,
            isHumanConfirmed = false,
        )

        assertFalse(plan.shouldRecordLearning)
        assertFalse(plan.shouldAssociateImage)
        assertFalse(plan.shouldQueueServerSync)
        assertNull(plan.learnedAlias)
        assertNull(plan.visualDHashHex)
    }

    @Test
    fun confirmedCorrectionRecordsSuggestedVsActualAndRespectsImagePermission() {
        val suggested = product(18L, "Masking tape (medium)")
        val actual = product(20L, "Masking tape (large)")

        val outcome = RecognitionOutcome(
            status = MatchConfidenceStatus.AMBIGUOUS_ALTERNATIVES,
            candidates = listOf(
                RecognitionCandidate(
                    product = suggested,
                    stockProjection = null,
                    cachedPhotoPath = null,
                    internalScore = 0.54,
                    matchedReasons = listOf("Packaging text match"),
                )
            ),
            extractedOcrText = "48MM HEAVY DUTY",
            captureId = "cap-corrected",
            preservedPhotoPath = "/tmp/cap_large.jpg",
            statusLabel = "Choose Exact Product",
            captureDHashHex = "a1b2c3d4e5f60708",
        )

        val withImagePermitted = RecognitionLearningPolicy.buildPlan(
            outcome = outcome,
            confirmedProduct = actual,
            selectionOrigin = RecognitionSelectionOrigin.MANUAL_SEARCH,
            allowImageAssociation = true,
            isHumanConfirmed = true,
        )

        assertTrue(withImagePermitted.shouldRecordLearning)
        assertTrue(withImagePermitted.wasCorrected)
        assertEquals(18L, withImagePermitted.originalSuggestedProductId)
        assertEquals("Masking tape (medium)", withImagePermitted.originalSuggestedProductName)
        assertEquals(20L, withImagePermitted.confirmedProductId)
        assertEquals(RecognitionSelectionKind.MANUAL_SEARCH_OVERRIDE, withImagePermitted.selectionKind)
        assertEquals("48MM HEAVY DUTY", withImagePermitted.learnedAlias)
        assertTrue(withImagePermitted.shouldAssociateImage)
        assertEquals("a1b2c3d4e5f60708", withImagePermitted.visualDHashHex)
        assertTrue(withImagePermitted.shouldQueueServerSync)

        val withoutImagePermitted = RecognitionLearningPolicy.buildPlan(
            outcome = outcome,
            confirmedProduct = actual,
            selectionOrigin = RecognitionSelectionOrigin.CANDIDATE,
            allowImageAssociation = false,
            isHumanConfirmed = true,
        )
        assertEquals(RecognitionSelectionKind.ALTERNATIVE_CANDIDATE_SELECTED, withoutImagePermitted.selectionKind)
        assertFalse(withoutImagePermitted.shouldAssociateImage)
        assertNull(withoutImagePermitted.visualDHashHex)
        assertEquals("48MM HEAVY DUTY", withoutImagePermitted.learnedAlias)
    }

    @Test
    fun indexUpdateFromConfirmedLearningImmediatelyImprovesNextLocalScan() {
        val p1 = product(28L, "Ball pen (blue)")
        val p2 = product(29L, "Ball pen (black)")
        val catalogue = listOf(p1, p2)
        val productsById = catalogue.associateBy { it.id }

        val index = RecognitionReferenceIndex()
        index.rebuildFromProducts(catalogue, nowIso = "2026-11-14T10:00:00Z")

        val beforeOutcome = RecognitionRanker.rankCandidates(
            captureId = "cap-1",
            preservedPhotoPath = "/tmp/pen.jpg",
            captureDHashHex = "1122334455667788",
            ocrText = "CRISTAL SOFT",
            activeEntries = index.getActiveEntries(),
            productsById = productsById,
            projectionsById = emptyMap(),
            cachedPhotoPathsById = emptyMap(),
        )
        assertEquals(MatchConfidenceStatus.UNIDENTIFIED, beforeOutcome.status)

        // Human confirms Ball pen (blue) and permits visual + alias learning
        index.addConfirmedAlias(28L, "CRISTAL SOFT", nowIso = "2026-11-14T10:01:00Z")
        index.attachVisualSignature(28L, "1122334455667788", nowIso = "2026-11-14T10:01:00Z")

        val afterOutcome = RecognitionRanker.rankCandidates(
            captureId = "cap-2",
            preservedPhotoPath = "/tmp/pen2.jpg",
            captureDHashHex = "1122334455667788",
            ocrText = "CRISTAL SOFT",
            activeEntries = index.getActiveEntries(),
            productsById = productsById,
            projectionsById = emptyMap(),
            cachedPhotoPathsById = emptyMap(),
        )
        assertEquals(MatchConfidenceStatus.STRONG_MATCH, afterOutcome.status)
        assertEquals(28L, afterOutcome.candidates.first().product.id)
    }

    @Test
    fun correctionRpcResponseAndQueuePayloadRelinkingWorkCorrectly() {
        val committed = SupabaseRecognitionCorrectionClient.parseRpcResponse(
            200,
            """{"status":"COMMITTED","server_correction_id":77,"confirmed_product_id":28,"already_committed":false}""",
            uploadedImageUrl = "https://example.supabase.co/storage/v1/object/public/product-images/android-corrections/recog-1.jpg",
        )
        assertTrue(committed is RecognitionCorrectionRpcOutcome.CommittedOnline)
        committed as RecognitionCorrectionRpcOutcome.CommittedOnline
        assertEquals(77L, committed.serverCorrectionId)
        assertEquals(28L, committed.confirmedProductId)

        val missingRpc = SupabaseRecognitionCorrectionClient.parseRpcResponse(
            404,
            """{"code":"PGRST202","message":"Could not find the function public.record_android_recognition_correction"}""",
        )
        assertTrue(missingRpc is RecognitionCorrectionRpcOutcome.RpcMissing)

        val rawPayload =
            """{"correction_id":"recog-1","idempotency_key":"recog-idem-1","suggested_product_id":null,"confirmed_product_id":-1,"match_status":"UNIDENTIFIED","selection_kind":"MANUAL_SEARCH_OVERRIDE","used_cloud_assist":false,"learned_alias":"CRISTAL SOFT","actor_id":"staff1","device_id":"dev1"}"""
        val relinked = SyncQueuePolicy.relinkRecognitionCorrectionPayloadProductId(
            payloadJson = rawPayload,
            oldTempProductId = -1L,
            newProductId = 145L,
        )
        assertEquals(145L, JSONObject(relinked).getLong("confirmed_product_id"))

        val event = SyncQueueEventEntity(
            eventId = "sync-recog-1",
            eventType = SyncEventType.RECOGNITION_CORRECTION,
            entityId = "recog-1",
            dependencyEventId = null,
            payloadJson = relinked,
            actorId = "staff1",
            deviceId = "dev1",
            status = SyncQueueStatus.QUEUED,
            retryCount = 0,
            nextRetryAtEpochMs = 1000L,
            createdAtEpochMs = 1000L,
        )
        val parsed = SyncQueuePolicy.parseRecognitionCorrectionPayload(event)
        assertNotNull(parsed)
        assertEquals(145L, parsed!!.confirmedProductId)
        assertEquals("CRISTAL SOFT", parsed.learnedAlias)
    }
}
