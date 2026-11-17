package com.emofah.agencies.data.catalog

import com.emofah.agencies.data.local.entities.LocalStockProjectionEntity
import com.emofah.agencies.data.local.entities.ProductEntity

/**
 * Stage 3.2 (Action 3.2.1):
 * Computes minimal delta updates between remote Supabase `products` rows and
 * existing Room `ProductEntity` / `LocalStockProjectionEntity` rows while
 * preserving local confirmed aliases and offline pending stock deltas.
 */
object CatalogueDeltaCalculator {

    data class DeltaPlan(
        val changedProducts: List<ProductEntity>,
        val updatedProjections: List<LocalStockProjectionEntity>,
        val unchangedCount: Int,
    )

    data class FreshnessStatus(
        val lastSyncedAtEpochMs: Long?,
        val totalCachedProducts: Int,
        val isStale: Boolean,
        val staleThresholdMs: Long = DEFAULT_STALE_THRESHOLD_MS,
    )

    const val DEFAULT_STALE_THRESHOLD_MS = 6L * 60L * 60L * 1000L // 6 hours

    fun computeDeltaPlan(
        incoming: List<ProductIdentity>,
        existingProducts: List<ProductEntity>,
        existingProjections: List<LocalStockProjectionEntity>,
        nowEpochMs: Long,
    ): DeltaPlan {
        val existingById = existingProducts.associateBy { it.id }
        val projById = existingProjections.associateBy { it.productId }

        val changedProducts = mutableListOf<ProductEntity>()
        val updatedProjections = mutableListOf<LocalStockProjectionEntity>()
        var unchangedCount = 0

        for (remote in incoming) {
            val prev = existingById[remote.id]
            val candidate = ProductEntity(
                id = remote.id,
                name = remote.name,
                baseName = remote.baseName,
                variantLabel = remote.variantLabel,
                brandHint = remote.brandHint,
                category = remote.category,
                sellPrice = remote.sellPrice,
                discountPrice = remote.discountPrice,
                buyPrice = remote.buyPrice,
                serverStockQty = remote.stockQty,
                imageUrl = remote.imageUrl,
                description = remote.description,
                isFeatured = remote.isFeatured,
                createdAt = remote.createdAt,
                isArchived = prev?.isArchived ?: remote.isArchived,
                confirmedAliasesCsv = prev?.confirmedAliasesCsv.orEmpty(),
                cachedAtEpochMs = nowEpochMs,
            )

            if (prev == null || hasProductChanged(prev, candidate)) {
                changedProducts.add(candidate)
            } else {
                unchangedCount++
            }

            val prevProj = projById[remote.id]
            val pendingDelta = prevProj?.pendingDeltaQty ?: 0
            val nextProvisional = remote.stockQty + pendingDelta
            val detectedOversell = pendingDelta < 0 && nextProvisional < 0
            val preservedConflict = (prevProj?.hasConflict == true) || detectedOversell
            val conflictNote = when {
                prevProj?.hasConflict == true && !prevProj.conflictNote.isNullOrBlank() ->
                    prevProj.conflictNote
                detectedOversell ->
                    "Offline pending stock deduction ($pendingDelta) exceeds server stock (${remote.stockQty})."
                else -> prevProj?.conflictNote
            }
            val nextProj = LocalStockProjectionEntity(
                productId = remote.id,
                serverQty = remote.stockQty,
                serverUpdatedAtEpochMs = nowEpochMs,
                pendingDeltaQty = pendingDelta,
                provisionalAvailableQty = nextProvisional,
                hasConflict = preservedConflict,
                conflictNote = conflictNote,
            )

            if (
                prevProj == null ||
                prevProj.serverQty != nextProj.serverQty ||
                prevProj.provisionalAvailableQty != nextProj.provisionalAvailableQty ||
                prevProj.hasConflict != nextProj.hasConflict
            ) {
                updatedProjections.add(nextProj)
            }
        }

        return DeltaPlan(
            changedProducts = changedProducts,
            updatedProjections = updatedProjections,
            unchangedCount = unchangedCount,
        )
    }

    fun evaluateFreshness(
        lastSyncedAtEpochMs: Long?,
        totalCachedProducts: Int,
        nowEpochMs: Long,
        staleThresholdMs: Long = DEFAULT_STALE_THRESHOLD_MS,
    ): FreshnessStatus {
        val isStale = lastSyncedAtEpochMs == null ||
            totalCachedProducts == 0 ||
            (nowEpochMs - lastSyncedAtEpochMs) > staleThresholdMs
        return FreshnessStatus(
            lastSyncedAtEpochMs = lastSyncedAtEpochMs,
            totalCachedProducts = totalCachedProducts,
            isStale = isStale,
            staleThresholdMs = staleThresholdMs,
        )
    }

    fun toProductIdentity(entity: ProductEntity): ProductIdentity {
        return ProductIdentity(
            id = entity.id,
            name = entity.name,
            baseName = entity.baseName,
            variantLabel = entity.variantLabel,
            brandHint = entity.brandHint,
            category = entity.category,
            sellPrice = entity.sellPrice,
            discountPrice = entity.discountPrice,
            buyPrice = entity.buyPrice,
            stockQty = entity.serverStockQty,
            imageUrl = entity.imageUrl,
            description = entity.description,
            isFeatured = entity.isFeatured,
            createdAt = entity.createdAt,
            isArchived = entity.isArchived,
        )
    }

    fun parseAliasesCsv(csv: String): List<String> {
        if (csv.isBlank()) return emptyList()
        return csv.split("|")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    fun encodeAliasesCsv(aliases: List<String>): String {
        return aliases.map { it.replace("|", " ").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("|")
    }

    private fun hasProductChanged(prev: ProductEntity, next: ProductEntity): Boolean {
        return prev.name != next.name ||
            prev.baseName != next.baseName ||
            prev.variantLabel != next.variantLabel ||
            prev.brandHint != next.brandHint ||
            prev.category != next.category ||
            prev.sellPrice != next.sellPrice ||
            prev.discountPrice != next.discountPrice ||
            prev.buyPrice != next.buyPrice ||
            prev.serverStockQty != next.serverStockQty ||
            prev.imageUrl != next.imageUrl ||
            prev.description != next.description ||
            prev.isFeatured != next.isFeatured
    }
}
