package com.emofah.agencies.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object SyncState {
    const val LOCAL_PENDING = "LOCAL_PENDING"
    const val SYNCING = "SYNCING"
    const val SERVER_CONFIRMED = "SERVER_CONFIRMED"
    const val CONFLICT = "CONFLICT"
    const val FAILED = "FAILED"
}

/**
 * Action 3.1.1 (4): Local sale record using a stable client UUID and idempotency key.
 */
@Entity(
    tableName = "local_sales",
    indices = [
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["sync_status"]),
        Index(value = ["created_at_epoch_ms"]),
    ],
)
data class LocalSaleEntity(
    @PrimaryKey
    @ColumnInfo(name = "client_sale_id")
    val clientSaleId: String,
    @ColumnInfo(name = "server_sale_id")
    val serverSaleId: Long? = null,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "total_amount")
    val totalAmount: Double,
    @ColumnInfo(name = "payment_method")
    val paymentMethod: String,
    @ColumnInfo(name = "actor_id")
    val actorId: String,
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    @ColumnInfo(name = "source")
    val source: String = "ANDROID",
    @ColumnInfo(name = "sale_date_iso")
    val saleDateIso: String,
    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,
    @ColumnInfo(name = "sync_status")
    val syncStatus: String = SyncState.LOCAL_PENDING,
    @ColumnInfo(name = "is_reversed")
    val isReversed: Boolean = false,
    @ColumnInfo(name = "reversal_reason")
    val reversalReason: String? = null,
)

/**
 * Action 3.1.1 (5): Local sale line item linked to LocalSaleEntity.
 */
@Entity(
    tableName = "local_sale_items",
    foreignKeys = [
        ForeignKey(
            entity = LocalSaleEntity::class,
            parentColumns = ["client_sale_id"],
            childColumns = ["client_sale_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["client_sale_id"]),
        Index(value = ["product_id"]),
    ],
)
data class LocalSaleItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "client_sale_id")
    val clientSaleId: String,
    @ColumnInfo(name = "product_id")
    val productId: Long,
    @ColumnInfo(name = "product_name_snapshot")
    val productNameSnapshot: String,
    @ColumnInfo(name = "quantity")
    val quantity: Int,
    @ColumnInfo(name = "unit_sell_price")
    val unitSellPrice: Double,
    @ColumnInfo(name = "original_sell_price")
    val originalSellPrice: Double,
    @ColumnInfo(name = "price_override_reason")
    val priceOverrideReason: String? = null,
    @ColumnInfo(name = "line_total")
    val lineTotal: Double,
)

/**
 * Action 3.1.1 (8): Offline pending product registration draft before server ID assignment.
 */
@Entity(
    tableName = "pending_product_drafts",
    indices = [Index(value = ["sync_status"])],
)
data class PendingProductDraftEntity(
    @PrimaryKey
    @ColumnInfo(name = "draft_id")
    val draftId: String,
    @ColumnInfo(name = "server_product_id")
    val serverProductId: Long? = null,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "category")
    val category: String,
    @ColumnInfo(name = "sell_price")
    val sellPrice: Double,
    @ColumnInfo(name = "buy_price")
    val buyPrice: Double,
    @ColumnInfo(name = "initial_qty")
    val initialQty: Int,
    @ColumnInfo(name = "description")
    val description: String? = null,
    @ColumnInfo(name = "local_photo_path")
    val localPhotoPath: String? = null,
    @ColumnInfo(name = "ocr_text")
    val ocrText: String? = null,
    @ColumnInfo(name = "sync_status")
    val syncStatus: String = SyncState.LOCAL_PENDING,
    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,
)
