package com.emofah.agencies.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Action 3.1.1 (2): Local cached product record keyed by canonical Supabase `products.id`.
 */
@Entity(
    tableName = "products",
    indices = [
        Index(value = ["category"]),
        Index(value = ["name"]),
    ],
)
data class ProductEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Long,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "base_name")
    val baseName: String,
    @ColumnInfo(name = "variant_label")
    val variantLabel: String?,
    @ColumnInfo(name = "brand_hint")
    val brandHint: String?,
    @ColumnInfo(name = "category")
    val category: String,
    @ColumnInfo(name = "sell_price")
    val sellPrice: Double,
    @ColumnInfo(name = "discount_price")
    val discountPrice: Double,
    @ColumnInfo(name = "buy_price")
    val buyPrice: Double,
    @ColumnInfo(name = "server_stock_qty")
    val serverStockQty: Int,
    @ColumnInfo(name = "image_url")
    val imageUrl: String?,
    @ColumnInfo(name = "description")
    val description: String?,
    @ColumnInfo(name = "is_featured")
    val isFeatured: Boolean,
    @ColumnInfo(name = "created_at")
    val createdAt: String?,
    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean = false,
    @ColumnInfo(name = "confirmed_aliases_csv")
    val confirmedAliasesCsv: String = "",
    @ColumnInfo(name = "cached_at_epoch_ms")
    val cachedAtEpochMs: Long,
)

/**
 * Action 3.1.1 (3): Local product reference-image record linked to canonical `products.id`.
 */
@Entity(
    tableName = "product_reference_photos",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["product_id"])],
)
data class ProductReferencePhotoEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "product_id")
    val productId: Long,
    @ColumnInfo(name = "angle")
    val angle: String, // FRONT, BACK, SIDE, PACKAGE
    @ColumnInfo(name = "remote_url")
    val remoteUrl: String?,
    @ColumnInfo(name = "local_cache_path")
    val localCachePath: String?,
    @ColumnInfo(name = "width_px")
    val widthPx: Int?,
    @ColumnInfo(name = "height_px")
    val heightPx: Int?,
    @ColumnInfo(name = "visual_signature_hex")
    val visualSignatureHex: String?,
    @ColumnInfo(name = "is_valid")
    val isValid: Boolean,
    @ColumnInfo(name = "last_verified_at_epoch_ms")
    val lastVerifiedAtEpochMs: Long,
)

/**
 * Action 3.1.1 (6): Local stock projection distinguishing authoritative server stock
 * from offline provisional deductions/receipts.
 */
@Entity(
    tableName = "local_stock_projections",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class LocalStockProjectionEntity(
    @PrimaryKey
    @ColumnInfo(name = "product_id")
    val productId: Long,
    @ColumnInfo(name = "server_qty")
    val serverQty: Int,
    @ColumnInfo(name = "server_updated_at_epoch_ms")
    val serverUpdatedAtEpochMs: Long,
    @ColumnInfo(name = "pending_delta_qty")
    val pendingDeltaQty: Int = 0,
    @ColumnInfo(name = "provisional_available_qty")
    val provisionalAvailableQty: Int,
    @ColumnInfo(name = "has_conflict")
    val hasConflict: Boolean = false,
    @ColumnInfo(name = "conflict_note")
    val conflictNote: String? = null,
)
