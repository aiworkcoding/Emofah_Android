package com.emofah.agencies

import com.emofah.agencies.data.catalog.ProductMetadataExtractor
import com.emofah.agencies.data.catalog.RecognitionReferenceIndex
import com.emofah.agencies.data.catalog.SupabaseProductDto
import com.emofah.agencies.recognition.MatchConfidenceStatus
import com.emofah.agencies.recognition.RecognitionRanker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionRankerTest {

    private fun buildCatalogue() = listOf(
        ProductMetadataExtractor.toProductIdentity(
            SupabaseProductDto(
                id = 28L,
                name = "Cristal bic pen (blue)",
                qty = 30,
                sellPrice = 25.0,
                buyPrice = 18.0,
                imageUrl = null,
                category = "Stationery",
                createdAt = null,
                description = null,
                discountPrice = 0.0,
                isFeatured = false,
            )
        ),
        ProductMetadataExtractor.toProductIdentity(
            SupabaseProductDto(
                id = 30L,
                name = "Cristal bic pen (red)",
                qty = 24,
                sellPrice = 25.0,
                buyPrice = 18.0,
                imageUrl = null,
                category = "Stationery",
                createdAt = null,
                description = null,
                discountPrice = 0.0,
                isFeatured = false,
            )
        ),
        ProductMetadataExtractor.toProductIdentity(
            SupabaseProductDto(
                id = 18L,
                name = "Masking tape (medium)",
                qty = 5,
                sellPrice = 150.0,
                buyPrice = 115.0,
                imageUrl = null,
                category = "Stationery",
                createdAt = null,
                description = null,
                discountPrice = 0.0,
                isFeatured = false,
            )
        ),
    )

    @Test
    fun dHashSimilarityScoresIdenticalAndOppositeHashesCorrectly() {
        assertEquals(1.0, RecognitionRanker.dHashSimilarity("ffffffffffffffff", "ffffffffffffffff"), 0.001)
        assertEquals(0.0, RecognitionRanker.dHashSimilarity("0000000000000000", "ffffffffffffffff"), 0.001)
    }

    @Test
    fun strongVisualAndOcrMatchIdentifiesExactVariantOverSibling() {
        val products = buildCatalogue()
        val index = RecognitionReferenceIndex().apply {
            rebuildFromProducts(products, nowIso = "2026-11-14T01:00:00Z")
            attachVisualSignature(28L, "0f0f0f0f0f0f0f0f")
            attachVisualSignature(30L, "f0f0f0f0f0f0f0f0")
        }

        val outcome = RecognitionRanker.rankCandidates(
            captureId = "cap-1",
            preservedPhotoPath = "/tmp/orig.jpg",
            captureDHashHex = "0f0f0f0f0f0f0f0f",
            ocrText = "CRISTAL BIC PEN BLUE",
            activeEntries = index.getActiveEntries(),
            productsById = products.associateBy { it.id },
            projectionsById = emptyMap(),
            cachedPhotoPathsById = emptyMap(),
        )

        assertEquals(MatchConfidenceStatus.STRONG_MATCH, outcome.status)
        assertEquals(28L, outcome.candidates.first().product.id)
    }

    @Test
    fun ambiguousOcrWithoutVisualDistinctionPresentsAlternativesForConfirmation() {
        val products = buildCatalogue()
        val index = RecognitionReferenceIndex().apply {
            rebuildFromProducts(products, nowIso = "2026-11-14T01:00:00Z")
        }

        val outcome = RecognitionRanker.rankCandidates(
            captureId = "cap-2",
            preservedPhotoPath = "/tmp/orig.jpg",
            captureDHashHex = null,
            ocrText = "cristal bic pen",
            activeEntries = index.getActiveEntries(),
            productsById = products.associateBy { it.id },
            projectionsById = emptyMap(),
            cachedPhotoPathsById = emptyMap(),
        )

        assertEquals(MatchConfidenceStatus.AMBIGUOUS_ALTERNATIVES, outcome.status)
        assertEquals(2, outcome.candidates.size)
    }

    @Test
    fun unfamiliarOcrReturnsUnidentifiedAndNeverInventsProductId() {
        val products = buildCatalogue()
        val index = RecognitionReferenceIndex().apply {
            rebuildFromProducts(products, nowIso = "2026-11-14T01:00:00Z")
        }

        val outcome = RecognitionRanker.rankCandidates(
            captureId = "cap-3",
            preservedPhotoPath = "/tmp/unfamiliar.jpg",
            captureDHashHex = "1234567890abcdef",
            ocrText = "UNKNOWN IMPORTED THERMOS FLASK 2L",
            activeEntries = index.getActiveEntries(),
            productsById = products.associateBy { it.id },
            projectionsById = emptyMap(),
            cachedPhotoPathsById = emptyMap(),
        )

        assertEquals(MatchConfidenceStatus.UNIDENTIFIED, outcome.status)
        assertTrue(outcome.candidates.isEmpty())
        assertEquals("/tmp/unfamiliar.jpg", outcome.preservedPhotoPath)
    }
}
