package com.emofah.agencies.recognition

import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.catalog.RecognitionReferenceEntry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EmbeddedModelAndEmbeddingTest {

    private fun sampleProducts(): List<ProductIdentity> = listOf(
        ProductIdentity(
            id = 10L,
            name = "Crown Silk Emulsion 4L White",
            baseName = "Crown Silk Emulsion White",
            variantLabel = "4L",
            brandHint = "Crown",
            category = "Paints",
            sellPrice = 2400.0,
            discountPrice = 0.0,
            buyPrice = 1800.0,
            stockQty = 8,
            imageUrl = "https://nbmmfdiqywmkjaltezro.supabase.co/storage/v1/object/public/product-images/crown_4l.jpg",
            description = null,
            isFeatured = false,
            createdAt = null,
            sku = "CRW-4L-WHT",
            barcode = "6161100123456",
        ),
        ProductIdentity(
            id = 11L,
            name = "Crown Silk Emulsion 1L White",
            baseName = "Crown Silk Emulsion White",
            variantLabel = "1L",
            brandHint = "Crown",
            category = "Paints",
            sellPrice = 750.0,
            discountPrice = 0.0,
            buyPrice = 540.0,
            stockQty = 14,
            imageUrl = "https://nbmmfdiqywmkjaltezro.supabase.co/storage/v1/object/public/product-images/crown_1l.jpg",
            description = null,
            isFeatured = false,
            createdAt = null,
            sku = "CRW-1L-WHT",
            barcode = "6161100123499",
        ),
        ProductIdentity(
            id = 12L,
            name = "Super Glue 3g Tube",
            baseName = "Super Glue",
            variantLabel = "3g",
            brandHint = null,
            category = "Adhesives",
            sellPrice = 50.0,
            discountPrice = 0.0,
            buyPrice = 28.0,
            stockQty = 40,
            imageUrl = null,
            description = null,
            isFeatured = false,
            createdAt = null,
            sku = "GLUE-3G",
            barcode = "8901234567890",
        ),
    )

    private fun toEntries(products: List<ProductIdentity>): List<RecognitionReferenceEntry> =
        products.map { p ->
            RecognitionReferenceEntry(
                indexVersion = 2,
                productId = p.id,
                canonicalName = p.name,
                baseName = p.baseName,
                variantLabel = p.variantLabel,
                brandHint = p.brandHint,
                category = p.category,
                aliases = emptyList(),
                searchTokens = p.name.lowercase().split(" ").filter { it.length >= 2 },
                visualSignatureHex = null,
                referencePhotoCount = if (p.imageUrl != null) 1 else 0,
                isArchived = false,
                updatedAtIso = "2026-11-21T00:00:00Z",
                sku = p.sku,
                barcode = p.barcode,
            )
        }

    @Test
    fun bundledTfliteModelAssetExistsAndHasValidTfl3Header() {
        val candidates = listOf(
            File("src/main/assets/models/emofah_vision_model.tflite"),
            File("app/src/main/assets/models/emofah_vision_model.tflite"),
        )
        val modelFile = candidates.firstOrNull { it.exists() }
        assertNotNull("Bundled TFLite model must exist in assets/models/", modelFile)
        val file = modelFile!!
        assertTrue("Model size must exceed 4 MB (MobileNetV3-Small weights)", file.length() > 4_000_000L)

        val header = file.inputStream().use { input ->
            ByteArray(8).also { input.read(it) }
        }
        val magic = String(header, 4, 4, Charsets.US_ASCII)
        assertEquals("TFL3", magic)
    }

    @Test
    fun embeddingCacheStore_persistsNormalizedVectorsAndComputesCosineSimilarity() {
        val tempDir = Files.createTempDirectory("emofah_emb_test").toFile()
        try {
            val store = EmbeddingCacheStore(tempDir)
            val raw = FloatArray(1024) { idx -> if (idx % 2 == 0) 3.0f else 4.0f }
            val saved = store.saveEmbedding(10L, raw)
            assertTrue(store.hasEmbedding(10L))

            val storeReloaded = EmbeddingCacheStore(tempDir)
            val loaded = storeReloaded.loadEmbedding(10L, expectedDim = 1024)
            assertNotNull(loaded)
            assertArrayEquals(saved, loaded!!, 1e-5f)

            val simSelf = EmbeddingCacheStore.cosineSimilarity(saved, loaded)
            assertEquals(1.0, simSelf, 1e-4)

            val orthogonal = FloatArray(1024) { idx -> if (idx == 1) 1.0f else 0.0f }
            val simOrtho = EmbeddingCacheStore.cosineSimilarity(saved, orthogonal)
            assertTrue("Unrelated vector cosine should be low", simOrtho < 0.25)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun rankCandidates_usesVisualEmbeddingsBarcodesAndRejectsUnknownObjects() {
        val products = sampleProducts()
        val productsById = products.associateBy { it.id }
        val entries = toEntries(products)

        val emb10 = EmbeddingCacheStore.l2Normalize(FloatArray(64) { i -> if (i < 32) 1f else 0f })
        val emb11 = EmbeddingCacheStore.l2Normalize(FloatArray(64) { i -> if (i >= 32) 1f else 0f })
        val embeddings = mapOf(10L to emb10, 11L to emb11)

        // 1. Visual embedding alone identifies 4L paint cleanly
        val visualOutcome = RecognitionRanker.rankCandidates(
            captureId = "cap-1",
            preservedPhotoPath = "/tmp/cap1.jpg",
            captureDHashHex = null,
            ocrText = "",
            activeEntries = entries,
            productsById = productsById,
            projectionsById = emptyMap(),
            cachedPhotoPathsById = emptyMap(),
            captureEmbedding = emb10.copyOf(),
            embeddingsByProductId = embeddings,
        )
        assertEquals(MatchConfidenceStatus.STRONG_MATCH, visualOutcome.status)
        assertEquals(10L, visualOutcome.candidates.first().product.id)
        assertTrue(visualOutcome.candidates.first().matchedReasons.contains("AI visual embedding match"))

        // 2. Barcode exact match identifies product without relying on OCR text
        val barcodeOutcome = RecognitionRanker.rankCandidates(
            captureId = "cap-2",
            preservedPhotoPath = "/tmp/cap2.jpg",
            captureDHashHex = null,
            ocrText = "",
            activeEntries = entries,
            productsById = productsById,
            projectionsById = emptyMap(),
            cachedPhotoPathsById = emptyMap(),
            decodedBarcodes = listOf("8901234567890"),
        )
        assertEquals(MatchConfidenceStatus.STRONG_MATCH, barcodeOutcome.status)
        assertEquals(12L, barcodeOutcome.candidates.first().product.id)
        assertTrue(barcodeOutcome.candidates.first().matchedReasons.contains("Barcode match"))

        // 3. Similar variants without variant qualifier surface as AMBIGUOUS_ALTERNATIVES
        val variantOutcome = RecognitionRanker.rankCandidates(
            captureId = "cap-3",
            preservedPhotoPath = "/tmp/cap3.jpg",
            captureDHashHex = null,
            ocrText = "Crown Silk Emulsion White",
            activeEntries = entries,
            productsById = productsById,
            projectionsById = emptyMap(),
            cachedPhotoPathsById = emptyMap(),
        )
        assertEquals(MatchConfidenceStatus.AMBIGUOUS_ALTERNATIVES, variantOutcome.status)
        assertTrue(variantOutcome.candidates.size >= 2)

        // 4. Unknown product returns UNIDENTIFIED instead of fabricating a match
        val unknownEmb = EmbeddingCacheStore.l2Normalize(FloatArray(64) { i -> if (i % 2 == 0) 1f else -1f })
        val unknownOutcome = RecognitionRanker.rankCandidates(
            captureId = "cap-4",
            preservedPhotoPath = "/tmp/cap4.jpg",
            captureDHashHex = null,
            ocrText = "Totally Unrelated Bicycle Helmet 999",
            activeEntries = entries,
            productsById = productsById,
            projectionsById = emptyMap(),
            cachedPhotoPathsById = emptyMap(),
            captureEmbedding = unknownEmb,
            embeddingsByProductId = embeddings,
        )
        assertEquals(MatchConfidenceStatus.UNIDENTIFIED, unknownOutcome.status)
        assertFalse(unknownOutcome.candidates.any { it.internalScore >= RecognitionRanker.STRONG_MATCH_THRESHOLD })
    }
}
