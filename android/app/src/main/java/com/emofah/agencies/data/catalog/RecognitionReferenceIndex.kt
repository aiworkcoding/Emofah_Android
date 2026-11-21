package com.emofah.agencies.data.catalog

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * Action 1.1.5:
 * Versioned Recognition Reference Index keyed by canonical `products.id`.
 * Supports local persistence, confirmed alias learning, archived product exclusion,
 * and full index rebuilds when local cache is invalidated or catalogue changes.
 */
class RecognitionReferenceIndex(
    private val storageFile: File? = null,
) {
    private val entriesByProductId = linkedMapOf<Long, RecognitionReferenceEntry>()
    private val confirmedAliasesByProductId = linkedMapOf<Long, MutableList<String>>()
    private val visualSignaturesByProductId = linkedMapOf<Long, String>()

    @Synchronized
    fun rebuildFromProducts(
        products: List<ProductIdentity>,
        nowIso: String = Instant.now().toString(),
    ): RecognitionIndexSnapshot {
        entriesByProductId.clear()
        for (product in products) {
            val aliases = confirmedAliasesByProductId[product.id]?.toList().orEmpty()
            val tokens = ProductMetadataExtractor.buildSearchTokens(product, aliases)
            val entry = RecognitionReferenceEntry(
                indexVersion = INDEX_VERSION,
                productId = product.id,
                canonicalName = product.name,
                baseName = product.baseName,
                variantLabel = product.variantLabel,
                brandHint = product.brandHint,
                category = product.category,
                aliases = aliases,
                searchTokens = tokens,
                visualSignatureHex = visualSignaturesByProductId[product.id],
                referencePhotoCount = if (product.imageUrl != null) 1 else 0,
                isArchived = product.isArchived,
                updatedAtIso = nowIso,
                sku = product.sku,
                barcode = product.barcode,
            )
            entriesByProductId[product.id] = entry
        }
        val snapshot = snapshot(nowIso)
        persistSnapshot(snapshot)
        return snapshot
    }

    @Synchronized
    fun addConfirmedAlias(
        productId: Long,
        alias: String,
        nowIso: String = Instant.now().toString(),
    ): RecognitionReferenceEntry? {
        val normalized = alias.trim()
        if (normalized.isEmpty()) return entriesByProductId[productId]

        val list = confirmedAliasesByProductId.getOrPut(productId) { mutableListOf() }
        if (list.none { it.equals(normalized, ignoreCase = true) }) {
            list.add(normalized)
        }

        val existing = entriesByProductId[productId] ?: return null
        val aliasTokens = normalized.lowercase()
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.length >= 2 }

        val updated = existing.copy(
            aliases = list.toList(),
            searchTokens = (existing.searchTokens + aliasTokens).distinct(),
            updatedAtIso = nowIso,
        )
        entriesByProductId[productId] = updated
        persistSnapshot(snapshot(nowIso))
        return updated
    }

    @Synchronized
    fun attachVisualSignature(
        productId: Long,
        visualSignatureHex: String,
        nowIso: String = Instant.now().toString(),
    ): RecognitionReferenceEntry? {
        visualSignaturesByProductId[productId] = visualSignatureHex
        val existing = entriesByProductId[productId] ?: return null
        val updated = existing.copy(
            visualSignatureHex = visualSignatureHex,
            updatedAtIso = nowIso,
        )
        entriesByProductId[productId] = updated
        persistSnapshot(snapshot(nowIso))
        return updated
    }

    /**
     * Active recognition candidates strictly exclude archived products.
     */
    @Synchronized
    fun getActiveEntries(): List<RecognitionReferenceEntry> {
        return entriesByProductId.values.filter { !it.isArchived }
    }

    /**
     * Historical lookups still resolve archived product IDs when needed for past orders/sales.
     */
    @Synchronized
    fun getByProductId(productId: Long): RecognitionReferenceEntry? {
        return entriesByProductId[productId]
    }

    @Synchronized
    fun searchByText(query: String, limit: Int = 10): List<RecognitionReferenceEntry> {
        val safeLimit = limit.coerceAtLeast(0)
        if (safeLimit == 0) return emptyList()

        val trimmed = query.trim()
        val terms = query.lowercase()
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.length >= 2 }

        val active = getActiveEntries()
        if (terms.isEmpty()) return active.take(safeLimit)

        return active
            .map { entry ->
                val barcodeBoost = if (
                    trimmed.isNotEmpty() &&
                    (entry.barcode?.equals(trimmed, ignoreCase = true) == true ||
                        entry.sku?.equals(trimmed, ignoreCase = true) == true)
                ) 20 else 0
                val exactBoost = if (trimmed.isNotEmpty() && entry.canonicalName.contains(trimmed, ignoreCase = true)) 10 else 0
                val aliasBoost = if (trimmed.isNotEmpty() && entry.aliases.any { it.contains(trimmed, ignoreCase = true) }) 8 else 0
                val tokenHits = terms.count { t ->
                    entry.searchTokens.any { token -> token.contains(t) }
                }
                entry to (barcodeBoost + exactBoost + aliasBoost + tokenHits)
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(safeLimit)
            .map { it.first }
    }

    @Synchronized
    fun snapshot(builtAtIso: String = Instant.now().toString()): RecognitionIndexSnapshot {
        val all = entriesByProductId.values.toList()
        return RecognitionIndexSnapshot(
            version = INDEX_VERSION,
            builtAtIso = builtAtIso,
            totalProducts = all.size,
            activeIndexCount = all.count { !it.isArchived },
            withImageCount = all.count { it.referencePhotoCount > 0 },
            entries = all,
        )
    }

    @Synchronized
    fun loadFromDisk(): RecognitionIndexSnapshot? {
        val file = storageFile ?: return null
        if (!file.exists()) return null
        return try {
            val root = JSONObject(file.readText())
            if (root.optInt("version", -1) != INDEX_VERSION) {
                return null
            }
            val arr = root.getJSONArray("entries")
            entriesByProductId.clear()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val aliases = obj.getJSONArray("aliases").toStringList()
                val tokens = obj.getJSONArray("searchTokens").toStringList()
                val sig = obj.optString("visualSignatureHex").takeIf { it.isNotEmpty() && it != "null" }
                val entry = RecognitionReferenceEntry(
                    indexVersion = obj.getInt("indexVersion"),
                    productId = obj.getLong("productId"),
                    canonicalName = obj.getString("canonicalName"),
                    baseName = obj.getString("baseName"),
                    variantLabel = obj.optString("variantLabel").takeIf { it.isNotEmpty() && it != "null" },
                    brandHint = obj.optString("brandHint").takeIf { it.isNotEmpty() && it != "null" },
                    category = obj.getString("category"),
                    aliases = aliases,
                    searchTokens = tokens,
                    visualSignatureHex = sig,
                    referencePhotoCount = obj.getInt("referencePhotoCount"),
                    isArchived = obj.getBoolean("isArchived"),
                    updatedAtIso = obj.getString("updatedAtIso"),
                    sku = obj.optString("sku").takeIf { it.isNotEmpty() && it != "null" },
                    barcode = obj.optString("barcode").takeIf { it.isNotEmpty() && it != "null" },
                )
                entriesByProductId[entry.productId] = entry
                if (aliases.isNotEmpty()) {
                    confirmedAliasesByProductId[entry.productId] = aliases.toMutableList()
                }
                if (sig != null) {
                    visualSignaturesByProductId[entry.productId] = sig
                }
            }
            snapshot(root.optString("builtAtIso", Instant.now().toString()))
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun invalidateAndClear() {
        entriesByProductId.clear()
        storageFile?.takeIf { it.exists() }?.delete()
    }

    private fun persistSnapshot(snapshot: RecognitionIndexSnapshot) {
        val file = storageFile ?: return
        try {
            file.parentFile?.mkdirs()
            val arr = JSONArray()
            for (e in snapshot.entries) {
                arr.put(
                    JSONObject().apply {
                        put("indexVersion", e.indexVersion)
                        put("productId", e.productId)
                        put("canonicalName", e.canonicalName)
                        put("baseName", e.baseName)
                        put("variantLabel", e.variantLabel ?: JSONObject.NULL)
                        put("brandHint", e.brandHint ?: JSONObject.NULL)
                        put("category", e.category)
                        put("aliases", JSONArray(e.aliases))
                        put("searchTokens", JSONArray(e.searchTokens))
                        put("visualSignatureHex", e.visualSignatureHex ?: JSONObject.NULL)
                        put("referencePhotoCount", e.referencePhotoCount)
                        put("isArchived", e.isArchived)
                        put("updatedAtIso", e.updatedAtIso)
                        put("sku", e.sku ?: JSONObject.NULL)
                        put("barcode", e.barcode ?: JSONObject.NULL)
                    }
                )
            }
            val root = JSONObject().apply {
                put("version", snapshot.version)
                put("builtAtIso", snapshot.builtAtIso)
                put("totalProducts", snapshot.totalProducts)
                put("activeIndexCount", snapshot.activeIndexCount)
                put("withImageCount", snapshot.withImageCount)
                put("entries", arr)
            }
            file.writeText(root.toString())
        } catch (_: Exception) {
            // Non-fatal persistence failure; in-memory index remains intact
        }
    }

    private fun JSONArray.toStringList(): List<String> =
        List(length()) { idx -> getString(idx) }

    companion object {
        const val INDEX_VERSION = 2
    }
}
