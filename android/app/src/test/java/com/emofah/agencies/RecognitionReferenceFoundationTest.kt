package com.emofah.agencies

import com.emofah.agencies.data.catalog.ProductMetadataExtractor
import com.emofah.agencies.data.catalog.RecognitionReferenceIndex
import com.emofah.agencies.data.catalog.SupabaseProductDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecognitionReferenceFoundationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sampleProducts() = listOf(
        ProductMetadataExtractor.toProductIdentity(
            SupabaseProductDto(
                id = 18L,
                name = "Masking tape (medium)",
                qty = 1,
                sellPrice = 150.0,
                buyPrice = 115.0,
                imageUrl = "https://nbmmfdiqywmkjaltezro.supabase.co/storage/v1/object/public/product-images/medium-masking-tape.png",
                category = "Stationery",
                createdAt = "2026-01-05T14:40:33Z",
                description = null,
                discountPrice = 0.0,
                isFeatured = false,
            )
        ),
        ProductMetadataExtractor.toProductIdentity(
            SupabaseProductDto(
                id = 20L,
                name = "Masking tape (large)",
                qty = 4,
                sellPrice = 220.0,
                buyPrice = 170.0,
                imageUrl = null,
                category = "Stationery",
                createdAt = "2026-01-05T14:40:33Z",
                description = null,
                discountPrice = 0.0,
                isFeatured = false,
            )
        ),
        ProductMetadataExtractor.toProductIdentity(
            SupabaseProductDto(
                id = 28L,
                name = "Cristal bic pen (blue)",
                qty = 30,
                sellPrice = 25.0,
                buyPrice = 18.0,
                imageUrl = null,
                category = "Stationery",
                createdAt = "2026-01-05T14:40:33Z",
                description = null,
                discountPrice = 0.0,
                isFeatured = false,
            ),
            isArchived = true,
        ),
    )

    @Test
    fun similarNamedVariantsPreserveDistinctCanonicalIds() {
        val products = sampleProducts()
        val medium = products[0]
        val large = products[1]

        assertNotEquals(medium.id, large.id)
        assertEquals("Masking tape", medium.baseName)
        assertEquals("medium", medium.variantLabel)
        assertEquals("large", large.variantLabel)
    }

    @Test
    fun indexExcludesArchivedFromActiveSearchWhileKeepingHistoricalIdLookup() {
        val indexFile = tempFolder.newFile("recognition_index.json")
        val index = RecognitionReferenceIndex(indexFile)

        val snapshot = index.rebuildFromProducts(sampleProducts(), nowIso = "2026-11-14T00:00:00Z")
        assertEquals(3, snapshot.totalProducts)
        assertEquals(2, snapshot.activeIndexCount)
        assertEquals(1, snapshot.withImageCount)

        val activeSearch = index.searchByText("pen blue")
        assertTrue(activeSearch.isEmpty())

        val archivedLookup = index.getByProductId(28L)
        assertEquals("Cristal bic pen (blue)", archivedLookup?.canonicalName)
    }

    @Test
    fun confirmedAliasAndDiskPersistenceSurviveReloadAndRebuild() {
        val indexFile = tempFolder.newFile("recognition_index.json")
        val index = RecognitionReferenceIndex(indexFile)
        index.rebuildFromProducts(sampleProducts(), nowIso = "2026-11-14T00:00:00Z")

        index.addConfirmedAlias(20L, "sellotape kubwa")
        index.attachVisualSignature(18L, "0f0f0f0f0f0f0f0f")

        val reloaded = RecognitionReferenceIndex(indexFile)
        val loadedSnap = reloaded.loadFromDisk()
        assertEquals(3, loadedSnap?.totalProducts)

        val aliasHits = reloaded.searchByText("sellotape kubwa")
        assertEquals(1, aliasHits.size)
        assertEquals(20L, aliasHits.first().productId)
        assertEquals("0f0f0f0f0f0f0f0f", reloaded.getByProductId(18L)?.visualSignatureHex)

        reloaded.invalidateAndClear()
        assertNull(reloaded.loadFromDisk())
    }
}
