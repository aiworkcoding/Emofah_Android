package com.emofah.agencies.data.catalog

import android.content.Context
import com.emofah.agencies.data.SupabaseConfig
import com.emofah.agencies.data.local.EmofahDatabase
import com.emofah.agencies.data.local.entities.ProductReferencePhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Stage 1.1 + Stage 3.2 Repository:
 * Queries the authoritative shared Emofah Supabase `products` table, applies changed-record
 * deltas into Room (`ProductEntity` & `LocalStockProjectionEntity`) while preserving offline
 * pending stock deltas and confirmed aliases, progressively caches reference images, and
 * keeps the local `RecognitionReferenceIndex` synchronized.
 */
class SupabaseProductRepository(
    context: Context,
    private val database: EmofahDatabase = EmofahDatabase.getInstance(context),
) {
    private val productDao = database.productDao()
    val referenceIndex = RecognitionReferenceIndex(
        storageFile = File(context.filesDir, "recognition_reference_index.json")
    )
    val imageCache = ReferenceImageCache(context)

    init {
        referenceIndex.loadFromDisk()
    }

    data class SyncResult(
        val products: List<ProductIdentity>,
        val indexSnapshot: RecognitionIndexSnapshot,
        val freshness: CatalogueDeltaCalculator.FreshnessStatus,
        val changedCount: Int,
        val unchangedCount: Int,
        val fromCache: Boolean,
        val errorMessage: String? = null,
    )

    fun observeCachedProducts(): Flow<List<ProductIdentity>> {
        return productDao.observeActiveProducts().map { entities ->
            entities.map(CatalogueDeltaCalculator::toProductIdentity)
        }
    }

    suspend fun getCacheFreshness(
        nowEpochMs: Long = System.currentTimeMillis(),
    ): CatalogueDeltaCalculator.FreshnessStatus = withContext(Dispatchers.IO) {
        val lastTs = productDao.getLastCacheTimestamp()
        val count = productDao.countProducts()
        CatalogueDeltaCalculator.evaluateFreshness(lastTs, count, nowEpochMs)
    }

    suspend fun syncCatalogueAndRebuildIndex(
        forceNetwork: Boolean = true,
    ): SyncResult = withContext(Dispatchers.IO) {
        val nowMs = System.currentTimeMillis()
        if (!forceNetwork) {
            val freshness = getCacheFreshness(nowMs)
            if (!freshness.isStale) {
                return@withContext loadFromRoomFallback(
                    nowMs = nowMs,
                    errorMessage = null,
                )
            }
        }
        if (!SupabaseConfig.isConfigured) {
            return@withContext loadFromRoomFallback(
                nowMs = nowMs,
                errorMessage = "SupabaseConfig not set in local.properties",
            )
        }

        return@withContext try {
            val endpoint =
                "${SupabaseConfig.url.trimEnd('/')}/rest/v1/products" +
                    "?select=id,name,qty,sell_price,buy_price,image_url,category,created_at,description,discount_price,is_featured" +
                    "&order=id.asc&limit=1000"

            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 12_000
                setRequestProperty("apikey", SupabaseConfig.anonKey)
                setRequestProperty("Authorization", "Bearer ${SupabaseConfig.bearerToken()}")
                setRequestProperty("Accept", "application/json")
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                loadFromRoomFallback(
                    nowMs = nowMs,
                    errorMessage = "Supabase HTTP $code; showing cached Room catalogue.",
                )
            } else {
                val rawJson = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val incoming = parseProductsJson(rawJson)
                val existingProducts = productDao.getAllProducts()
                val existingProjections = productDao.getAllStockProjections()

                val delta = CatalogueDeltaCalculator.computeDeltaPlan(
                    incoming = incoming,
                    existingProducts = existingProducts,
                    existingProjections = existingProjections,
                    nowEpochMs = nowMs,
                )

                productDao.applyCatalogueDelta(
                    changedProducts = delta.changedProducts,
                    updatedProjections = delta.updatedProjections,
                )

                val cachedEntities = productDao.getActiveProducts()
                val cachedProducts = cachedEntities.map(CatalogueDeltaCalculator::toProductIdentity)

                // Re-hydrate confirmed aliases from Room before rebuilding index
                for (entity in cachedEntities) {
                    for (alias in CatalogueDeltaCalculator.parseAliasesCsv(entity.confirmedAliasesCsv)) {
                        referenceIndex.addConfirmedAlias(entity.id, alias)
                    }
                }
                val snapshot = referenceIndex.rebuildFromProducts(cachedProducts)
                val freshness = CatalogueDeltaCalculator.evaluateFreshness(
                    lastSyncedAtEpochMs = nowMs,
                    totalCachedProducts = cachedEntities.size,
                    nowEpochMs = nowMs,
                )

                SyncResult(
                    products = cachedProducts,
                    indexSnapshot = snapshot,
                    freshness = freshness,
                    changedCount = delta.changedProducts.size,
                    unchangedCount = delta.unchangedCount,
                    fromCache = false,
                )
            }
        } catch (ex: Exception) {
            loadFromRoomFallback(
                nowMs = nowMs,
                errorMessage = ex.message ?: "Offline; loaded cached Room catalogue.",
            )
        }
    }

    /**
     * Progressively caches a single product's reference photograph into local filesDir
     * and records metadata + perceptual dHash in Room + RecognitionReferenceIndex.
     */
    suspend fun ensureReferencePhotoCached(productId: Long): ProductReferencePhoto? =
        withContext(Dispatchers.IO) {
            val product = productDao.getProductById(productId) ?: return@withContext null
            val photo = imageCache.getOrFetchReference(
                productId = product.id,
                remoteUrl = product.imageUrl,
                angle = ReferenceAngle.FRONT,
            )
            val dHash = photo.localCachePath?.let { imageCache.computeDHashHex(it) }
            if (dHash != null) {
                referenceIndex.attachVisualSignature(productId, dHash)
            }
            productDao.upsertReferencePhoto(
                ProductReferencePhotoEntity(
                    id = photo.id,
                    productId = product.id,
                    angle = photo.angle.name,
                    remoteUrl = photo.remoteUrl,
                    localCachePath = photo.localCachePath,
                    widthPx = photo.widthPx,
                    heightPx = photo.heightPx,
                    visualSignatureHex = dHash,
                    isValid = photo.isValid,
                    lastVerifiedAtEpochMs = System.currentTimeMillis(),
                )
            )
            photo
        }

    suspend fun saveConfirmedAlias(productId: Long, alias: String) = withContext(Dispatchers.IO) {
        val clean = alias.trim()
        if (clean.isEmpty()) return@withContext
        val entity = productDao.getProductById(productId) ?: return@withContext
        val existing = CatalogueDeltaCalculator.parseAliasesCsv(entity.confirmedAliasesCsv)
        val updatedCsv = CatalogueDeltaCalculator.encodeAliasesCsv(existing + clean)
        productDao.upsertProducts(listOf(entity.copy(confirmedAliasesCsv = updatedCsv)))
        referenceIndex.addConfirmedAlias(productId, clean)
    }

    private suspend fun loadFromRoomFallback(
        nowMs: Long,
        errorMessage: String? = null,
    ): SyncResult {
        val cachedEntities = productDao.getActiveProducts()
        val cachedProducts = cachedEntities.map(CatalogueDeltaCalculator::toProductIdentity)
        for (entity in cachedEntities) {
            for (alias in CatalogueDeltaCalculator.parseAliasesCsv(entity.confirmedAliasesCsv)) {
                referenceIndex.addConfirmedAlias(entity.id, alias)
            }
        }
        val snapshot = referenceIndex.rebuildFromProducts(cachedProducts)
        val lastTs = productDao.getLastCacheTimestamp()
        val freshness = CatalogueDeltaCalculator.evaluateFreshness(
            lastSyncedAtEpochMs = lastTs,
            totalCachedProducts = cachedEntities.size,
            nowEpochMs = nowMs,
        )
        return SyncResult(
            products = cachedProducts,
            indexSnapshot = snapshot,
            freshness = freshness,
            changedCount = 0,
            unchangedCount = cachedProducts.size,
            fromCache = true,
            errorMessage = errorMessage,
        )
    }

    companion object {
        fun parseProductsJson(rawJson: String): List<ProductIdentity> {
            val arr = JSONArray(rawJson)
            val list = ArrayList<ProductIdentity>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val dto = SupabaseProductDto(
                    id = obj.getLong("id"),
                    name = obj.getString("name"),
                    qty = obj.optInt("qty", 0),
                    sellPrice = obj.optDouble("sell_price", 0.0),
                    buyPrice = obj.optDouble("buy_price", 0.0),
                    imageUrl = obj.optString("image_url").takeIf { it.isNotEmpty() && it != "null" },
                    category = obj.optString("category").takeIf { it.isNotEmpty() && it != "null" },
                    createdAt = obj.optString("created_at").takeIf { it.isNotEmpty() && it != "null" },
                    description = obj.optString("description").takeIf { it.isNotEmpty() && it != "null" },
                    discountPrice = obj.optDouble("discount_price", 0.0),
                    isFeatured = obj.optBoolean("is_featured", false),
                )
                list.add(ProductMetadataExtractor.toProductIdentity(dto))
            }
            return list
        }
    }
}
