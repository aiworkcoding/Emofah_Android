package com.emofah.agencies.data.catalog

import android.content.Context
import com.emofah.agencies.camera.ImageQualityMath
import com.emofah.agencies.data.SupabaseConfig
import com.emofah.agencies.data.local.EmofahDatabase
import com.emofah.agencies.data.local.entities.ProductReferencePhotoEntity
import com.emofah.agencies.recognition.EmbeddingCacheStore
import com.emofah.agencies.recognition.OnDeviceModelInferenceEngine
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
 * pending stock deltas and confirmed aliases, progressively caches reference images, extracts
 * on-device TFLite vision embeddings + perceptual dHashes, and keeps `RecognitionReferenceIndex`
 * synchronized.
 */
class SupabaseProductRepository(
    context: Context,
    private val database: EmofahDatabase = EmofahDatabase.getInstance(context),
    val inferenceEngine: OnDeviceModelInferenceEngine = OnDeviceModelInferenceEngine(context),
) {
    private val productDao = database.productDao()
    val referenceIndex = RecognitionReferenceIndex(
        storageFile = File(context.filesDir, "recognition_reference_index.json")
    )
    val imageCache = ReferenceImageCache(context)
    val embeddingCache = EmbeddingCacheStore(context)

    @Volatile
    private var isDiskIndexLoaded = false

    suspend fun ensureDiskIndexLoaded() = withContext(Dispatchers.IO) {
        if (!isDiskIndexLoaded) {
            referenceIndex.loadFromDisk()
            isDiskIndexLoaded = true
        }
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
            entities.map { entity ->
                val identity = CatalogueDeltaCalculator.toProductIdentity(entity)
                val refEntry = referenceIndex.getByProductId(entity.id)
                if (refEntry != null) {
                    identity.copy(
                        sku = identity.sku ?: refEntry.sku,
                        barcode = identity.barcode ?: refEntry.barcode,
                    )
                } else {
                    identity
                }
            }
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
        ensureDiskIndexLoaded()
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
                errorMessage = "Supabase connection is unavailable in this build; showing cached Room catalogue.",
            )
        }

        return@withContext try {
            val response = fetchProductsRawJson(includeExtendedColumns = true)
                ?: fetchProductsRawJson(includeExtendedColumns = false)

            when (val outcome = classifyFetchResult(response)) {
                is ProductsFetchOutcome.Fallback -> {
                    loadFromRoomFallback(
                        nowMs = nowMs,
                        errorMessage = outcome.errorMessage,
                    )
                }
                is ProductsFetchOutcome.Success -> {
                    val incoming = parseProductsJson(outcome.body)
                    val incomingById = incoming.associateBy { it.id }
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
                    val cachedProducts = cachedEntities.map { entity ->
                        val base = CatalogueDeltaCalculator.toProductIdentity(entity)
                        val remote = incomingById[entity.id]
                        if (remote != null) {
                            base.copy(sku = remote.sku, barcode = remote.barcode)
                        } else {
                            base
                        }
                    }

                    // Re-hydrate confirmed aliases from Room before rebuilding index
                    for (entity in cachedEntities) {
                        for (alias in CatalogueDeltaCalculator.parseAliasesCsv(entity.confirmedAliasesCsv)) {
                            referenceIndex.addConfirmedAlias(entity.id, alias)
                        }
                    }
                    var snapshot = referenceIndex.rebuildFromProducts(cachedProducts)

                    // Progressively cache reference images and precompute local dHash + TFLite embeddings
                    prefetchAndIndexVisualFeatures(cachedProducts, maxNewDownloads = 12)
                    snapshot = referenceIndex.snapshot()

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
            }
        } catch (ex: Exception) {
            loadFromRoomFallback(
                nowMs = nowMs,
                errorMessage = ex.message ?: "Offline; loaded cached Room catalogue.",
            )
        }
    }

    internal data class ProductsFetchResult(
        val httpCode: Int,
        val body: String?,
    )

    private fun fetchProductsRawJson(includeExtendedColumns: Boolean): ProductsFetchResult? {
        val selectColumns = if (includeExtendedColumns) {
            "id,name,qty,sell_price,buy_price,image_url,category,created_at,description,discount_price,is_featured,sku,barcode,is_active"
        } else {
            "id,name,qty,sell_price,buy_price,image_url,category,created_at,description,discount_price,is_featured"
        }
        val endpoint =
            "${SupabaseConfig.url.trimEnd('/')}/rest/v1/products" +
                "?select=$selectColumns&order=id.asc&limit=1000"

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 12_000
            setRequestProperty("apikey", SupabaseConfig.anonKey)
            setRequestProperty("Authorization", "Bearer ${SupabaseConfig.bearerToken()}")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = conn.responseCode
            if (code == 400 && includeExtendedColumns) {
                // Schema without extended columns; caller will retry base select cleanly.
                null
            } else if (code in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                ProductsFetchResult(code, body)
            } else {
                ProductsFetchResult(code, null)
            }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun prefetchAndIndexVisualFeatures(
        products: List<ProductIdentity>,
        maxNewDownloads: Int = 12,
    ) {
        val withImages = products
            .filter { !it.imageUrl.isNullOrBlank() }
            .map { it.id to it.imageUrl }
        if (withImages.isEmpty()) return

        val cachedPhotos = imageCache.prefetchProgressiveBatch(
            items = withImages,
            maxNewDownloads = maxNewDownloads,
        )
        for (photo in cachedPhotos) {
            val localPath = photo.localCachePath ?: continue
            if (!photo.isValid) continue

            val existingRef = productDao.getReferencePhotosForProduct(photo.productId)
                .firstOrNull { it.id == photo.id && !it.visualSignatureHex.isNullOrBlank() }

            var dHash = existingRef?.visualSignatureHex
            val needsEmbedding = !embeddingCache.hasEmbedding(photo.productId)
            if (dHash == null || needsEmbedding) {
                val bitmap = ImageQualityMath.decodeUprightBitmap(File(localPath), 640)
                if (bitmap != null) {
                    try {
                        if (dHash == null) {
                            dHash = ReferenceImageCache.computeDHashFromBitmap(bitmap)
                        }
                        if (needsEmbedding && inferenceEngine.isReady) {
                            inferenceEngine.extractVisualFeatureEmbeddingOrNull(bitmap)?.let { vec ->
                                embeddingCache.saveEmbedding(photo.productId, vec)
                            }
                        }
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
            }

            if (dHash != null) {
                referenceIndex.attachVisualSignature(photo.productId, dHash)
            }

            if (existingRef == null || existingRef.localCachePath != localPath || existingRef.visualSignatureHex != dHash) {
                productDao.upsertReferencePhoto(
                    ProductReferencePhotoEntity(
                        id = photo.id,
                        productId = photo.productId,
                        angle = photo.angle.name,
                        remoteUrl = photo.remoteUrl,
                        localCachePath = localPath,
                        widthPx = photo.widthPx,
                        heightPx = photo.heightPx,
                        visualSignatureHex = dHash,
                        isValid = photo.isValid,
                        lastVerifiedAtEpochMs = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    /**
     * Progressively caches a single product's reference photograph into local filesDir
     * and records metadata + perceptual dHash + TFLite visual embedding.
     */
    suspend fun ensureReferencePhotoCached(productId: Long): ProductReferencePhoto? =
        withContext(Dispatchers.IO) {
            val product = productDao.getProductById(productId) ?: return@withContext null
            val normalizedUrl = SupabaseConfig.normalizeProductImageUrl(product.imageUrl)
            val photo = imageCache.getOrFetchReference(
                productId = product.id,
                remoteUrl = normalizedUrl,
                angle = ReferenceAngle.FRONT,
            )
            val localPath = photo.localCachePath
            var dHash: String? = null
            if (localPath != null) {
                val bitmap = ImageQualityMath.decodeUprightBitmap(File(localPath), 640)
                if (bitmap != null) {
                    try {
                        dHash = ReferenceImageCache.computeDHashFromBitmap(bitmap)
                        if (inferenceEngine.isReady && !embeddingCache.hasEmbedding(productId)) {
                            inferenceEngine.extractVisualFeatureEmbeddingOrNull(bitmap)?.let { vec ->
                                embeddingCache.saveEmbedding(productId, vec)
                            }
                        }
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
            }
            if (dHash != null) {
                referenceIndex.attachVisualSignature(productId, dHash)
            }
            productDao.upsertReferencePhoto(
                ProductReferencePhotoEntity(
                    id = photo.id,
                    productId = product.id,
                    angle = photo.angle.name,
                    remoteUrl = photo.remoteUrl,
                    localCachePath = localPath,
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
        val cachedProducts = cachedEntities.map { entity ->
            val base = CatalogueDeltaCalculator.toProductIdentity(entity)
            val existingEntry = referenceIndex.getByProductId(entity.id)
            if (existingEntry != null) {
                base.copy(sku = existingEntry.sku, barcode = existingEntry.barcode)
            } else {
                base
            }
        }
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
        internal sealed class ProductsFetchOutcome {
            data class Success(val body: String) : ProductsFetchOutcome()
            data class Fallback(val errorMessage: String) : ProductsFetchOutcome()
        }

        internal fun classifyFetchResult(response: ProductsFetchResult?): ProductsFetchOutcome {
            if (response == null) {
                return ProductsFetchOutcome.Fallback(
                    "Supabase catalogue request returned no response; showing cached Room catalogue."
                )
            }
            val body = response.body
            if (response.httpCode !in 200..299 || body == null) {
                return ProductsFetchOutcome.Fallback(
                    "Supabase HTTP ${response.httpCode}; showing cached Room catalogue."
                )
            }
            return ProductsFetchOutcome.Success(body)
        }

        fun parseProductsJson(rawJson: String): List<ProductIdentity> {
            val arr = JSONArray(rawJson)
            val list = ArrayList<ProductIdentity>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val rawImg = obj.optString("image_url").takeIf { it.isNotEmpty() && it != "null" }
                val normalizedImg = SupabaseConfig.normalizeProductImageUrl(rawImg)
                val isActive = if (obj.has("is_active") && !obj.isNull("is_active")) {
                    obj.optBoolean("is_active", true)
                } else {
                    true
                }
                val dto = SupabaseProductDto(
                    id = obj.getLong("id"),
                    name = obj.getString("name"),
                    qty = obj.optInt("qty", 0),
                    sellPrice = obj.optDouble("sell_price", 0.0),
                    buyPrice = obj.optDouble("buy_price", 0.0),
                    imageUrl = normalizedImg,
                    category = obj.optString("category").takeIf { it.isNotEmpty() && it != "null" },
                    createdAt = obj.optString("created_at").takeIf { it.isNotEmpty() && it != "null" },
                    description = obj.optString("description").takeIf { it.isNotEmpty() && it != "null" },
                    discountPrice = obj.optDouble("discount_price", 0.0),
                    isFeatured = obj.optBoolean("is_featured", false),
                    sku = obj.optString("sku").takeIf { it.isNotEmpty() && it != "null" },
                    barcode = obj.optString("barcode").takeIf { it.isNotEmpty() && it != "null" },
                    isActive = isActive,
                )
                list.add(ProductMetadataExtractor.toProductIdentity(dto, isArchived = !isActive))
            }
            return list
        }
    }
}
