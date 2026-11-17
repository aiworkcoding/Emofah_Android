package com.emofah.agencies.stock

import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.local.dao.ProductSaleMovementRow
import com.emofah.agencies.data.local.entities.SyncState
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

enum class LowStockLevel(val label: String) {
    OUT_OF_STOCK("Out of Stock"),
    LOW_STOCK("Low Stock"),
    IN_STOCK("In Stock"),
}

enum class StockMovementKind(val label: String) {
    LOCAL_SALE("Sale"),
    LOCAL_REVERSAL("Sale Reversal"),
    SERVER_SALE("Server Sale"),
}

data class StockMovementEntry(
    val id: String,
    val kind: StockMovementKind,
    val deltaQty: Int,
    val title: String,
    val detail: String,
    val occurredAtEpochMs: Long,
    val formattedTime: String,
    val isServerConfirmed: Boolean,
)

data class RemoteServerSaleRow(
    val serverSaleId: Long,
    val qtySold: Int,
    val totalPrice: Double,
    val saleDateIso: String,
)

data class StockLookupSnapshot(
    val product: ProductIdentity,
    val serverQty: Int,
    val provisionalQty: Int,
    val pendingDeltaQty: Int,
    val hasConflict: Boolean,
    val conflictNote: String?,
    val serverUpdatedAtEpochMs: Long,
    val freshnessLabel: String,
    val lowStockLevel: LowStockLevel,
    val lowStockThreshold: Int,
    val supportedLocation: String?,
    val cachedPhotoPath: String?,
    val movements: List<StockMovementEntry>,
    val statusNote: String? = null,
)

/**
 * Stage 4.1 — Pure business & display rules for Stock Lookup:
 * - Low-stock evaluation using configured or default threshold
 * - Freshness formatting distinguishing server updates vs pending offline deltas
 * - Honest extraction of product location only when present in metadata
 * - Non-duplicating stock movement aggregation from local Room + shared Supabase sales
 */
object StockLookupPolicy {

    const val DEFAULT_LOW_STOCK_THRESHOLD = 5

    fun evaluateLowStock(
        availableQty: Int,
        threshold: Int = DEFAULT_LOW_STOCK_THRESHOLD,
    ): LowStockLevel {
        val safeThreshold = threshold.coerceAtLeast(1)
        return when {
            availableQty <= 0 -> LowStockLevel.OUT_OF_STOCK
            availableQty <= safeThreshold -> LowStockLevel.LOW_STOCK
            else -> LowStockLevel.IN_STOCK
        }
    }

    fun extractSupportedLocation(description: String?): String? {
        val raw = description?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val regex = Regex(
            pattern = """(?im)^(?:location|shelf|aisle|bin|rack)\s*[:\-]\s*(.+)$""",
        )
        val match = regex.find(raw)?.groupValues?.getOrNull(1)?.trim()
        return match?.takeIf { it.isNotEmpty() }
    }

    fun formatFreshnessLabel(
        serverUpdatedAtEpochMs: Long?,
        nowEpochMs: Long = System.currentTimeMillis(),
        pendingDeltaQty: Int = 0,
        locale: Locale = Locale.getDefault(),
    ): String {
        if (serverUpdatedAtEpochMs == null || serverUpdatedAtEpochMs <= 0L) {
            return if (pendingDeltaQty != 0) {
                "Local provisional stock ($pendingDeltaQty pending sync)"
            } else {
                "Cached stock time unknown"
            }
        }
        val ageMinutes = ((nowEpochMs - serverUpdatedAtEpochMs).coerceAtLeast(0L) / 60_000L)
        val ageText = when {
            ageMinutes < 1L -> "just now"
            ageMinutes < 60L -> "${ageMinutes}m ago"
            ageMinutes < 24 * 60L -> "${ageMinutes / 60L}h ago"
            else -> SimpleDateFormat("dd MMM · HH:mm", locale).format(Date(serverUpdatedAtEpochMs))
        }
        val deltaNote = when {
            pendingDeltaQty < 0 -> " · ${pendingDeltaQty} pending sale(s)"
            pendingDeltaQty > 0 -> " · +$pendingDeltaQty pending sync"
            else -> ""
        }
        return "Server updated $ageText$deltaNote"
    }

    fun mergeMovements(
        localRows: List<ProductSaleMovementRow>,
        remoteSales: List<RemoteServerSaleRow>,
        locale: Locale = Locale.getDefault(),
    ): List<StockMovementEntry> {
        val dateFmt = SimpleDateFormat("dd MMM · HH:mm", locale)
        val entries = mutableListOf<StockMovementEntry>()
        val knownServerSaleIds = mutableSetOf<Long>()

        for (row in localRows) {
            row.serverSaleId?.let { knownServerSaleIds.add(it) }
            val confirmed = row.syncStatus == SyncState.SERVER_CONFIRMED
            entries.add(
                StockMovementEntry(
                    id = "local-sale-${row.clientSaleId}",
                    kind = StockMovementKind.LOCAL_SALE,
                    deltaQty = -row.quantity,
                    title = "Sale · -${row.quantity} unit(s)",
                    detail = buildString {
                        append("Ksh ${row.lineTotal.toInt()} · ${row.actorId}")
                        if (row.serverSaleId != null) append(" · #${row.serverSaleId}")
                        append(if (confirmed) " · Synced" else " · Local Pending")
                    },
                    occurredAtEpochMs = row.createdAtEpochMs,
                    formattedTime = dateFmt.format(Date(row.createdAtEpochMs)),
                    isServerConfirmed = confirmed,
                )
            )
            if (row.isReversed) {
                entries.add(
                    StockMovementEntry(
                        id = "local-rev-${row.clientSaleId}",
                        kind = StockMovementKind.LOCAL_REVERSAL,
                        deltaQty = +row.quantity,
                        title = "Reversal · +${row.quantity} unit(s)",
                        detail = buildString {
                            append(row.reversalReason?.takeIf { it.isNotBlank() } ?: "Authorized reversal")
                            append(if (confirmed) " · Synced" else " · Local")
                        },
                        occurredAtEpochMs = row.createdAtEpochMs + 1L,
                        formattedTime = dateFmt.format(Date(row.createdAtEpochMs)),
                        isServerConfirmed = confirmed,
                    )
                )
            }
        }

        for (remote in remoteSales) {
            if (remote.serverSaleId in knownServerSaleIds) continue
            val epochMs = runCatching { Instant.parse(remote.saleDateIso).toEpochMilli() }.getOrElse { 0L }
            val timeLabel = if (epochMs > 0L) dateFmt.format(Date(epochMs)) else remote.saleDateIso.take(16)
            entries.add(
                StockMovementEntry(
                    id = "server-sale-${remote.serverSaleId}",
                    kind = StockMovementKind.SERVER_SALE,
                    deltaQty = -remote.qtySold,
                    title = "Server Sale · -${remote.qtySold} unit(s)",
                    detail = "Ksh ${remote.totalPrice.toInt()} · #${remote.serverSaleId} · Shared Ledger",
                    occurredAtEpochMs = epochMs,
                    formattedTime = timeLabel,
                    isServerConfirmed = true,
                )
            )
        }

        return entries.sortedByDescending { it.occurredAtEpochMs }.take(20)
    }
}
