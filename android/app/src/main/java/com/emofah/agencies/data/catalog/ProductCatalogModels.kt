package com.emofah.agencies.data.catalog

/**
 * Exact mirror of live Supabase `public.products` table (inspected 2026-11-14).
 * Never invent columns that do not exist on the shared backend.
 */
data class SupabaseProductDto(
    val id: Long,
    val name: String,
    val qty: Int,
    val sellPrice: Double,
    val buyPrice: Double,
    val imageUrl: String?,
    val category: String?,
    val createdAt: String?,
    val description: String?,
    val discountPrice: Double,
    val isFeatured: Boolean,
)

enum class ReferenceAngle {
    FRONT,
    BACK,
    SIDE,
    PACKAGE,
}

data class ProductReferencePhoto(
    val id: String,
    val productId: Long,
    val angle: ReferenceAngle,
    val remoteUrl: String?,
    val localCachePath: String?,
    val widthPx: Int?,
    val heightPx: Int?,
    val isValid: Boolean,
    val lastVerifiedAtIso: String?,
)

/**
 * Canonical Android product representation keyed strictly by Supabase `products.id`.
 */
data class ProductIdentity(
    val id: Long,
    val name: String,
    val baseName: String,
    val variantLabel: String?,
    val brandHint: String?,
    val category: String,
    val sellPrice: Double,
    val discountPrice: Double,
    val buyPrice: Double,
    val stockQty: Int,
    val imageUrl: String?,
    val description: String?,
    val isFeatured: Boolean,
    val createdAt: String?,
    val isArchived: Boolean = false,
) {
    val effectiveSellPrice: Double
        get() = if (discountPrice > 0.0) discountPrice else sellPrice
}

data class RecognitionReferenceEntry(
    val indexVersion: Int,
    val productId: Long,
    val canonicalName: String,
    val baseName: String,
    val variantLabel: String?,
    val brandHint: String?,
    val category: String,
    val aliases: List<String>,
    val searchTokens: List<String>,
    val visualSignatureHex: String?,
    val referencePhotoCount: Int,
    val isArchived: Boolean,
    val updatedAtIso: String,
)

data class RecognitionIndexSnapshot(
    val version: Int,
    val builtAtIso: String,
    val totalProducts: Int,
    val activeIndexCount: Int,
    val withImageCount: Int,
    val entries: List<RecognitionReferenceEntry>,
)
