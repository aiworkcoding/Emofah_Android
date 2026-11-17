package com.emofah.agencies.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import com.emofah.agencies.data.local.entities.LocalAuditEventEntity
import com.emofah.agencies.data.local.entities.LocalSaleEntity
import com.emofah.agencies.data.local.entities.LocalSaleItemEntity
import com.emofah.agencies.data.local.entities.LocalStockProjectionEntity
import com.emofah.agencies.data.local.entities.PendingFinancialEventEntity
import com.emofah.agencies.data.local.entities.PendingProductDraftEntity
import com.emofah.agencies.data.local.entities.ProductEntity
import com.emofah.agencies.data.local.entities.ProductReferencePhotoEntity
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import kotlinx.coroutines.flow.Flow

data class LocalSaleWithItems(
    @Embedded val sale: LocalSaleEntity,
    @Relation(
        parentColumn = "client_sale_id",
        entityColumn = "client_sale_id",
    )
    val items: List<LocalSaleItemEntity>,
)

@Dao
interface ProductDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProducts(products: List<ProductEntity>)

    @Query("SELECT * FROM products ORDER BY id ASC")
    suspend fun getAllProducts(): List<ProductEntity>

    @Query("SELECT * FROM products WHERE is_archived = 0 ORDER BY id ASC")
    fun observeActiveProducts(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE is_archived = 0 ORDER BY id ASC")
    suspend fun getActiveProducts(): List<ProductEntity>

    @Query("SELECT * FROM products WHERE id = :productId LIMIT 1")
    suspend fun getProductById(productId: Long): ProductEntity?

    @Query("SELECT COUNT(*) FROM products")
    suspend fun countProducts(): Int

    @Query("SELECT MAX(cached_at_epoch_ms) FROM products")
    suspend fun getLastCacheTimestamp(): Long?

    @Query("SELECT MIN(id) FROM products")
    suspend fun getMinProductId(): Long?

    @Query("DELETE FROM products WHERE id = :productId")
    suspend fun deleteProductById(productId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReferencePhoto(photo: ProductReferencePhotoEntity)

    @Query("SELECT * FROM product_reference_photos WHERE product_id = :productId")
    suspend fun getReferencePhotosForProduct(productId: Long): List<ProductReferencePhotoEntity>

    @Query("SELECT * FROM product_reference_photos")
    suspend fun getAllReferencePhotos(): List<ProductReferencePhotoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStockProjections(projections: List<LocalStockProjectionEntity>)

    @Query("SELECT * FROM local_stock_projections")
    suspend fun getAllStockProjections(): List<LocalStockProjectionEntity>

    @Query("SELECT * FROM local_stock_projections")
    fun observeAllStockProjections(): Flow<List<LocalStockProjectionEntity>>

    @Query("SELECT * FROM local_stock_projections WHERE product_id = :productId LIMIT 1")
    suspend fun getStockProjection(productId: Long): LocalStockProjectionEntity?

    @Query(
        """
        UPDATE local_stock_projections
        SET pending_delta_qty = pending_delta_qty + :deltaQty,
            provisional_available_qty = provisional_available_qty + :deltaQty
        WHERE product_id = :productId
        """
    )
    suspend fun applyProvisionalStockDelta(productId: Long, deltaQty: Int)

    @Query(
        """
        UPDATE local_stock_projections
        SET server_qty = :serverQty,
            provisional_available_qty = :serverQty + pending_delta_qty,
            server_updated_at_epoch_ms = :updatedAtEpochMs
        WHERE product_id = :productId
        """
    )
    suspend fun applyServerStockUpdate(productId: Long, serverQty: Int, updatedAtEpochMs: Long)

    @Query(
        """
        UPDATE products
        SET server_stock_qty = :serverQty,
            cached_at_epoch_ms = :updatedAtEpochMs
        WHERE id = :productId
        """
    )
    suspend fun updateProductServerStock(productId: Long, serverQty: Int, updatedAtEpochMs: Long)

    @Query(
        """
        UPDATE products
        SET buy_price = :buyPrice,
            cached_at_epoch_ms = :updatedAtEpochMs
        WHERE id = :productId
        """
    )
    suspend fun updateProductBuyPrice(productId: Long, buyPrice: Double, updatedAtEpochMs: Long)

    @Query(
        """
        UPDATE local_stock_projections
        SET pending_delta_qty = pending_delta_qty + :syncedQtySold,
            server_qty = :newServerQty,
            provisional_available_qty = :newServerQty + (pending_delta_qty + :syncedQtySold),
            server_updated_at_epoch_ms = :updatedAtEpochMs,
            has_conflict = 0,
            conflict_note = NULL
        WHERE product_id = :productId
        """
    )
    suspend fun reconcileSyncedSaleProjection(
        productId: Long,
        syncedQtySold: Int,
        newServerQty: Int,
        updatedAtEpochMs: Long,
    )

    @Query(
        """
        UPDATE local_stock_projections
        SET has_conflict = 1,
            conflict_note = :conflictNote
        WHERE product_id = :productId
        """
    )
    suspend fun flagStockConflict(productId: Long, conflictNote: String)

    @Query(
        """
        UPDATE local_stock_projections
        SET server_qty = :serverQty,
            provisional_available_qty = :serverQty + pending_delta_qty,
            server_updated_at_epoch_ms = :updatedAtEpochMs,
            has_conflict = 1,
            conflict_note = :conflictNote
        WHERE product_id = :productId
        """
    )
    suspend fun flagStockConflictWithServerQty(
        productId: Long,
        serverQty: Int,
        conflictNote: String,
        updatedAtEpochMs: Long,
    )

    @Query("SELECT * FROM local_stock_projections WHERE has_conflict = 1")
    fun observeConflictedStockProjections(): Flow<List<LocalStockProjectionEntity>>

    @Query(
        """
        UPDATE local_stock_projections
        SET pending_delta_qty = pending_delta_qty + :restoreDeltaQty,
            provisional_available_qty = server_qty + (pending_delta_qty + :restoreDeltaQty),
            has_conflict = 0,
            conflict_note = NULL
        WHERE product_id = :productId
        """
    )
    suspend fun restorePendingDeltaAndClearConflict(productId: Long, restoreDeltaQty: Int)

    @Query("UPDATE local_stock_projections SET has_conflict = 0, conflict_note = NULL WHERE product_id = :productId")
    suspend fun clearStockConflictFlag(productId: Long)

    @Transaction
    suspend fun applyCatalogueDelta(
        changedProducts: List<ProductEntity>,
        updatedProjections: List<LocalStockProjectionEntity>,
    ) {
        if (changedProducts.isNotEmpty()) {
            upsertProducts(changedProducts)
        }
        if (updatedProjections.isNotEmpty()) {
            upsertStockProjections(updatedProjections)
        }
    }

    @Transaction
    suspend fun applyBatchStockCountOnline(
        countsByProductId: List<Pair<Long, Int>>,
        updatedAtEpochMs: Long,
    ) {
        for ((productId, countedQty) in countsByProductId) {
            val safeQty = countedQty.coerceAtLeast(0)
            val existing = getStockProjection(productId)
            if (existing == null) {
                upsertStockProjections(
                    listOf(
                        LocalStockProjectionEntity(
                            productId = productId,
                            serverQty = safeQty,
                            serverUpdatedAtEpochMs = updatedAtEpochMs,
                            pendingDeltaQty = 0,
                            provisionalAvailableQty = safeQty,
                        )
                    )
                )
            } else {
                applyServerStockUpdate(productId, safeQty, updatedAtEpochMs)
            }
            updateProductServerStock(productId, safeQty, updatedAtEpochMs)
        }
    }

    @Transaction
    suspend fun applyBatchStockCountOfflineDelta(
        deltasByProductId: List<Pair<Long, Int>>,
        updatedAtEpochMs: Long,
    ) {
        for ((productId, deltaQty) in deltasByProductId) {
            val existing = getStockProjection(productId)
            if (existing == null) {
                val product = getProductById(productId)
                val baseQty = product?.serverStockQty ?: 0
                upsertStockProjections(
                    listOf(
                        LocalStockProjectionEntity(
                            productId = productId,
                            serverQty = baseQty,
                            serverUpdatedAtEpochMs = updatedAtEpochMs,
                            pendingDeltaQty = deltaQty,
                            provisionalAvailableQty = (baseQty + deltaQty).coerceAtLeast(0),
                        )
                    )
                )
            } else {
                applyProvisionalStockDelta(productId, deltaQty)
            }
        }
    }
}

@Dao
interface SaleDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSale(sale: LocalSaleEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSaleItems(items: List<LocalSaleItemEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSyncEvent(event: SyncQueueEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAuditEvent(audit: LocalAuditEventEntity)

    /**
     * Atomic local offline commit: persists sale, line items, sync queue event,
     * audit record, and updates provisional stock in one SQLite transaction.
     */
    @Transaction
    suspend fun commitOfflineSaleAtomically(
        sale: LocalSaleEntity,
        items: List<LocalSaleItemEntity>,
        syncEvent: SyncQueueEventEntity,
        auditEvent: LocalAuditEventEntity,
        productDao: ProductDao,
    ) {
        insertSale(sale)
        insertSaleItems(items)
        for (item in items) {
            if (productDao.getStockProjection(item.productId) == null) {
                val product = productDao.getProductById(item.productId)
                val baseQty = product?.serverStockQty ?: 0
                productDao.upsertStockProjections(
                    listOf(
                        LocalStockProjectionEntity(
                            productId = item.productId,
                            serverQty = baseQty,
                            serverUpdatedAtEpochMs = sale.createdAtEpochMs,
                            pendingDeltaQty = -item.quantity,
                            provisionalAvailableQty = baseQty - item.quantity,
                        )
                    )
                )
            } else {
                productDao.applyProvisionalStockDelta(item.productId, -item.quantity)
            }
        }
        insertSyncEvent(syncEvent)
        insertAuditEvent(auditEvent)
    }

    /**
     * Stage 2.2 (Action 2.2.2):
     * Records a server-confirmed sale locally, updates authoritative server stock,
     * preserves any unrelated queued offline deltas, and logs audit trail atomically.
     */
    @Transaction
    suspend fun commitOnlineSaleAtomically(
        sale: LocalSaleEntity,
        items: List<LocalSaleItemEntity>,
        auditEvent: LocalAuditEventEntity,
        productId: Long,
        remainingServerQty: Int,
        serverUpdatedAtEpochMs: Long,
        productDao: ProductDao,
    ) {
        insertSale(sale)
        insertSaleItems(items)
        if (productDao.getStockProjection(productId) == null) {
            productDao.upsertStockProjections(
                listOf(
                    LocalStockProjectionEntity(
                        productId = productId,
                        serverQty = remainingServerQty,
                        serverUpdatedAtEpochMs = serverUpdatedAtEpochMs,
                        pendingDeltaQty = 0,
                        provisionalAvailableQty = remainingServerQty,
                    )
                )
            )
        } else {
            productDao.applyServerStockUpdate(productId, remainingServerQty, serverUpdatedAtEpochMs)
        }
        productDao.updateProductServerStock(productId, remainingServerQty, serverUpdatedAtEpochMs)
        insertAuditEvent(auditEvent)
    }

    @Transaction
    @Query("SELECT * FROM local_sales ORDER BY created_at_epoch_ms DESC")
    fun observeAllSalesWithItems(): Flow<List<LocalSaleWithItems>>

    @Transaction
    @Query("SELECT * FROM local_sales WHERE client_sale_id = :clientSaleId LIMIT 1")
    suspend fun getSaleWithItems(clientSaleId: String): LocalSaleWithItems?

    @Query("SELECT * FROM local_sales WHERE idempotency_key = :idempotencyKey LIMIT 1")
    suspend fun getSaleByIdempotencyKey(idempotencyKey: String): LocalSaleEntity?

    @Query("SELECT COUNT(*) FROM local_sales WHERE created_at_epoch_ms >= :startOfDayEpochMs AND is_reversed = 0")
    fun observeTodaySalesCount(startOfDayEpochMs: Long): Flow<Int>

    @Query("SELECT COALESCE(SUM(total_amount), 0.0) FROM local_sales WHERE created_at_epoch_ms >= :startOfDayEpochMs AND is_reversed = 0")
    fun observeTodaySalesTotal(startOfDayEpochMs: Long): Flow<Double>

    @Query("UPDATE local_sales SET sync_status = :syncStatus, server_sale_id = :serverSaleId WHERE client_sale_id = :clientSaleId")
    suspend fun updateSaleSyncStatus(clientSaleId: String, syncStatus: String, serverSaleId: Long?)

    @Query("UPDATE local_sales SET is_reversed = 1, reversal_reason = :reason, sync_status = 'RESOLVED_LOCALLY' WHERE client_sale_id = :clientSaleId")
    suspend fun markSaleLocallyReversed(clientSaleId: String, reason: String)

    @Query("UPDATE local_sale_items SET product_id = :newProductId WHERE product_id = :oldProductId")
    suspend fun relinkSaleItemProductId(oldProductId: Long, newProductId: Long)

    @Query("UPDATE local_sales SET is_reversed = 1, reversal_reason = :reason, sync_status = :syncStatus WHERE client_sale_id = :clientSaleId")
    suspend fun updateSaleReversalState(clientSaleId: String, reason: String, syncStatus: String)

    @Transaction
    suspend fun commitOnlineSaleReversalAtomically(
        clientSaleId: String,
        productId: Long,
        remainingServerQty: Int,
        reason: String,
        reversedAtEpochMs: Long,
        financialAdjustment: PendingFinancialEventEntity,
        auditEvent: LocalAuditEventEntity,
        productDao: ProductDao,
        financialAndDraftDao: FinancialAndDraftDao,
        auditDao: AuditDao,
    ) {
        updateSaleReversalState(
            clientSaleId = clientSaleId,
            reason = reason,
            syncStatus = "SERVER_CONFIRMED",
        )
        if (productDao.getStockProjection(productId) == null) {
            productDao.upsertStockProjections(
                listOf(
                    LocalStockProjectionEntity(
                        productId = productId,
                        serverQty = remainingServerQty,
                        serverUpdatedAtEpochMs = reversedAtEpochMs,
                        pendingDeltaQty = 0,
                        provisionalAvailableQty = remainingServerQty,
                    )
                )
            )
        } else {
            productDao.applyServerStockUpdate(productId, remainingServerQty, reversedAtEpochMs)
        }
        productDao.updateProductServerStock(productId, remainingServerQty, reversedAtEpochMs)
        financialAndDraftDao.upsertFinancialEvent(financialAdjustment)
        auditDao.appendAudit(auditEvent)
    }

    @Transaction
    suspend fun commitOfflineReversalOfSyncedSaleAtomically(
        clientSaleId: String,
        productId: Long,
        restoreQty: Int,
        reason: String,
        reversedAtEpochMs: Long,
        financialAdjustment: PendingFinancialEventEntity,
        syncEvent: SyncQueueEventEntity,
        auditEvent: LocalAuditEventEntity,
        productDao: ProductDao,
        financialAndDraftDao: FinancialAndDraftDao,
        syncQueueDao: SyncQueueDao,
        auditDao: AuditDao,
    ) {
        updateSaleReversalState(
            clientSaleId = clientSaleId,
            reason = reason,
            syncStatus = "LOCAL_PENDING",
        )
        if (productId > 0L && restoreQty > 0) {
            if (productDao.getStockProjection(productId) == null) {
                val product = productDao.getProductById(productId)
                val baseQty = product?.serverStockQty ?: 0
                productDao.upsertStockProjections(
                    listOf(
                        LocalStockProjectionEntity(
                            productId = productId,
                            serverQty = baseQty,
                            serverUpdatedAtEpochMs = reversedAtEpochMs,
                            pendingDeltaQty = restoreQty,
                            provisionalAvailableQty = baseQty + restoreQty,
                        )
                    )
                )
            } else {
                productDao.applyProvisionalStockDelta(productId, restoreQty)
            }
        }
        financialAndDraftDao.upsertFinancialEvent(financialAdjustment)
        syncQueueDao.upsertEvent(syncEvent)
        auditDao.appendAudit(auditEvent)
    }

    @Transaction
    suspend fun commitReversalOfUnsyncedSaleAtomically(
        clientSaleId: String,
        pendingSaleEventId: String?,
        productId: Long,
        restoreQty: Int,
        reason: String,
        resolvedAtEpochMs: Long,
        financialAdjustment: PendingFinancialEventEntity,
        auditEvent: LocalAuditEventEntity,
        productDao: ProductDao,
        financialAndDraftDao: FinancialAndDraftDao,
        syncQueueDao: SyncQueueDao,
        auditDao: AuditDao,
    ) {
        if (pendingSaleEventId != null) {
            val existingQueueEvent = syncQueueDao.getEventById(pendingSaleEventId)
            if (existingQueueEvent != null) {
                syncQueueDao.updateEventProgress(
                    eventId = pendingSaleEventId,
                    status = "RESOLVED_LOCALLY",
                    retryCount = existingQueueEvent.retryCount,
                    attemptEpochMs = resolvedAtEpochMs,
                    nextRetryEpochMs = resolvedAtEpochMs,
                    failureReason = reason,
                    serverAckId = "LOCAL_REVERSED",
                )
            }
        }
        markSaleLocallyReversed(clientSaleId = clientSaleId, reason = reason)
        if (productId > 0L && restoreQty > 0) {
            productDao.restorePendingDeltaAndClearConflict(
                productId = productId,
                restoreDeltaQty = restoreQty,
            )
        }
        financialAndDraftDao.upsertFinancialEvent(financialAdjustment)
        auditDao.appendAudit(auditEvent)
    }

    @Query(
        """
        SELECT s.client_sale_id AS clientSaleId,
               s.server_sale_id AS serverSaleId,
               i.quantity AS quantity,
               i.line_total AS lineTotal,
               s.sale_date_iso AS saleDateIso,
               s.created_at_epoch_ms AS createdAtEpochMs,
               s.sync_status AS syncStatus,
               s.is_reversed AS isReversed,
               s.reversal_reason AS reversalReason,
               s.actor_id AS actorId
        FROM local_sale_items i
        INNER JOIN local_sales s ON i.client_sale_id = s.client_sale_id
        WHERE i.product_id = :productId
        ORDER BY s.created_at_epoch_ms DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentMovementsForProduct(
        productId: Long,
        limit: Int = 20,
    ): List<ProductSaleMovementRow>
}

data class ProductSaleMovementRow(
    @ColumnInfo(name = "clientSaleId") val clientSaleId: String,
    @ColumnInfo(name = "serverSaleId") val serverSaleId: Long?,
    @ColumnInfo(name = "quantity") val quantity: Int,
    @ColumnInfo(name = "lineTotal") val lineTotal: Double,
    @ColumnInfo(name = "saleDateIso") val saleDateIso: String,
    @ColumnInfo(name = "createdAtEpochMs") val createdAtEpochMs: Long,
    @ColumnInfo(name = "syncStatus") val syncStatus: String,
    @ColumnInfo(name = "isReversed") val isReversed: Boolean,
    @ColumnInfo(name = "reversalReason") val reversalReason: String?,
    @ColumnInfo(name = "actorId") val actorId: String,
)

@Dao
interface SyncQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvent(event: SyncQueueEventEntity)

    @Query("SELECT * FROM sync_queue_events WHERE event_id = :eventId LIMIT 1")
    suspend fun getEventById(eventId: String): SyncQueueEventEntity?

    @Query("SELECT * FROM sync_queue_events ORDER BY created_at_epoch_ms DESC")
    fun observeAllEvents(): Flow<List<SyncQueueEventEntity>>

    @Query("SELECT * FROM sync_queue_events ORDER BY created_at_epoch_ms ASC")
    suspend fun getAllEventsSnapshot(): List<SyncQueueEventEntity>

    @Query("SELECT * FROM sync_queue_events WHERE event_type = :eventType ORDER BY created_at_epoch_ms ASC")
    suspend fun getEventsByType(eventType: String): List<SyncQueueEventEntity>

    @Query("SELECT * FROM sync_queue_events WHERE dependency_event_id = :dependencyEventId")
    suspend fun getDependentEvents(dependencyEventId: String): List<SyncQueueEventEntity>

    @Query("SELECT * FROM sync_queue_events WHERE entity_id = :entityId AND event_type = 'SALE' LIMIT 1")
    suspend fun findSaleEventByClientSaleId(entityId: String): SyncQueueEventEntity?

    @Query("UPDATE sync_queue_events SET payload_json = :payloadJson WHERE event_id = :eventId")
    suspend fun updateEventPayload(eventId: String, payloadJson: String)

    @Query(
        """
        SELECT q.* FROM sync_queue_events q
        WHERE q.status IN ('QUEUED', 'FAILED_RETRYABLE')
          AND q.next_retry_at_epoch_ms <= :nowEpochMs
          AND (
            q.dependency_event_id IS NULL
            OR EXISTS (
              SELECT 1 FROM sync_queue_events dep
              WHERE dep.event_id = q.dependency_event_id AND dep.status = 'SYNCED'
            )
          )
        ORDER BY q.created_at_epoch_ms ASC
        LIMIT :limit
        """
    )
    suspend fun getNextRunnableEvents(nowEpochMs: Long, limit: Int = 20): List<SyncQueueEventEntity>

    @Query("SELECT COUNT(*) FROM sync_queue_events WHERE status NOT IN ('SYNCED', 'RESOLVED_LOCALLY')")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM sync_queue_events WHERE status = 'CONFLICT' ORDER BY created_at_epoch_ms DESC")
    fun observeConflictedEvents(): Flow<List<SyncQueueEventEntity>>

    @Query("SELECT COUNT(*) FROM sync_queue_events WHERE status = 'CONFLICT'")
    fun observeConflictCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_queue_events WHERE status = 'AUTH_REQUIRED'")
    fun observeAuthRequiredCount(): Flow<Int>

    @Query(
        """
        UPDATE sync_queue_events
        SET status = 'FAILED_RETRYABLE',
            next_retry_at_epoch_ms = :nextRetryEpochMs,
            failure_reason = COALESCE(failure_reason, 'Recovered from interrupted sync')
        WHERE status = 'IN_FLIGHT'
          AND (last_attempt_at_epoch_ms IS NULL OR last_attempt_at_epoch_ms <= :staleBeforeEpochMs)
        """
    )
    suspend fun resetStaleInFlightEvents(staleBeforeEpochMs: Long, nextRetryEpochMs: Long): Int

    @Query(
        """
        UPDATE sync_queue_events
        SET status = 'FAILED_RETRYABLE',
            next_retry_at_epoch_ms = :nowEpochMs,
            failure_reason = COALESCE(failure_reason, 'Recovered after app restart')
        WHERE status = 'IN_FLIGHT'
        """
    )
    suspend fun recoverAllInFlightOnColdStart(nowEpochMs: Long): Int

    @Query(
        """
        UPDATE sync_queue_events
        SET status = 'QUEUED',
            next_retry_at_epoch_ms = :nowEpochMs,
            failure_reason = NULL
        WHERE event_id = :eventId
        """
    )
    suspend fun requeueEventNow(eventId: String, nowEpochMs: Long)

    @Query(
        """
        UPDATE sync_queue_events
        SET status = 'QUEUED',
            next_retry_at_epoch_ms = :nowEpochMs,
            failure_reason = NULL
        WHERE status = 'AUTH_REQUIRED'
        """
    )
    suspend fun requeueAuthBlockedEvents(nowEpochMs: Long): Int

    @Query(
        """
        UPDATE sync_queue_events
        SET status = :status,
            retry_count = :retryCount,
            last_attempt_at_epoch_ms = :attemptEpochMs,
            next_retry_at_epoch_ms = :nextRetryEpochMs,
            failure_reason = :failureReason,
            server_ack_id = :serverAckId
        WHERE event_id = :eventId
        """
    )
    suspend fun updateEventProgress(
        eventId: String,
        status: String,
        retryCount: Int,
        attemptEpochMs: Long,
        nextRetryEpochMs: Long,
        failureReason: String?,
        serverAckId: String?,
    )

    @Transaction
    suspend fun reconcileSyncedSaleAtomically(
        eventId: String,
        clientSaleId: String,
        serverSaleId: Long,
        productId: Long,
        qtySold: Int,
        remainingServerQty: Int,
        syncedAtEpochMs: Long,
        retryCount: Int,
        auditEvent: LocalAuditEventEntity,
        saleDao: SaleDao,
        productDao: ProductDao,
        auditDao: AuditDao,
    ) {
        updateEventProgress(
            eventId = eventId,
            status = "SYNCED",
            retryCount = retryCount,
            attemptEpochMs = syncedAtEpochMs,
            nextRetryEpochMs = syncedAtEpochMs,
            failureReason = null,
            serverAckId = serverSaleId.toString(),
        )
        saleDao.updateSaleSyncStatus(
            clientSaleId = clientSaleId,
            syncStatus = "SERVER_CONFIRMED",
            serverSaleId = serverSaleId,
        )
        productDao.reconcileSyncedSaleProjection(
            productId = productId,
            syncedQtySold = qtySold,
            newServerQty = remainingServerQty,
            updatedAtEpochMs = syncedAtEpochMs,
        )
        productDao.updateProductServerStock(
            productId = productId,
            serverQty = remainingServerQty,
            updatedAtEpochMs = syncedAtEpochMs,
        )
        auditDao.appendAudit(auditEvent)
    }

    @Transaction
    suspend fun markSaleConflictAtomically(
        eventId: String,
        clientSaleId: String,
        productId: Long,
        conflictMessage: String,
        serverAvailableQty: Int?,
        attemptEpochMs: Long,
        retryCount: Int,
        auditEvent: LocalAuditEventEntity,
        saleDao: SaleDao,
        productDao: ProductDao,
        auditDao: AuditDao,
    ) {
        updateEventProgress(
            eventId = eventId,
            status = "CONFLICT",
            retryCount = retryCount,
            attemptEpochMs = attemptEpochMs,
            nextRetryEpochMs = attemptEpochMs,
            failureReason = conflictMessage,
            serverAckId = null,
        )
        saleDao.updateSaleSyncStatus(
            clientSaleId = clientSaleId,
            syncStatus = "CONFLICT",
            serverSaleId = null,
        )
        if (serverAvailableQty != null) {
            productDao.flagStockConflictWithServerQty(
                productId = productId,
                serverQty = serverAvailableQty,
                conflictNote = conflictMessage,
                updatedAtEpochMs = attemptEpochMs,
            )
        } else {
            productDao.flagStockConflict(
                productId = productId,
                conflictNote = conflictMessage,
            )
        }
        auditDao.appendAudit(auditEvent)
    }

    @Transaction
    suspend fun requeueConflictedEventAtomically(
        eventId: String,
        productId: Long?,
        nowEpochMs: Long,
        auditEvent: LocalAuditEventEntity,
        productDao: ProductDao,
        auditDao: AuditDao,
    ) {
        requeueEventNow(eventId = eventId, nowEpochMs = nowEpochMs)
        if (productId != null && productId > 0L) {
            productDao.clearStockConflictFlag(productId)
        }
        auditDao.appendAudit(auditEvent)
    }

    @Transaction
    suspend fun resolveConflictedSaleAsReversedAtomically(
        eventId: String,
        clientSaleId: String,
        productId: Long,
        restoreQty: Int,
        reason: String,
        resolvedAtEpochMs: Long,
        retryCount: Int,
        auditEvent: LocalAuditEventEntity,
        saleDao: SaleDao,
        productDao: ProductDao,
        auditDao: AuditDao,
    ) {
        updateEventProgress(
            eventId = eventId,
            status = "RESOLVED_LOCALLY",
            retryCount = retryCount,
            attemptEpochMs = resolvedAtEpochMs,
            nextRetryEpochMs = resolvedAtEpochMs,
            failureReason = reason,
            serverAckId = "LOCAL_REVERSED",
        )
        saleDao.markSaleLocallyReversed(clientSaleId = clientSaleId, reason = reason)
        if (productId > 0L && restoreQty > 0) {
            productDao.restorePendingDeltaAndClearConflict(
                productId = productId,
                restoreDeltaQty = restoreQty,
            )
        }
        auditDao.appendAudit(auditEvent)
    }

    @Transaction
    suspend fun reconcileSyncedProductDraftAtomically(
        eventId: String,
        draftId: String,
        localTempProductId: Long,
        canonicalProduct: ProductEntity,
        serverQty: Int,
        syncedAtEpochMs: Long,
        retryCount: Int,
        relinkedSalePayloads: List<Pair<String, String>>,
        auditEvent: LocalAuditEventEntity,
        productDao: ProductDao,
        saleDao: SaleDao,
        financialAndDraftDao: FinancialAndDraftDao,
        auditDao: AuditDao,
    ) {
        val tempProjection = productDao.getStockProjection(localTempProductId)
        val preservedPendingDelta = tempProjection?.pendingDeltaQty ?: 0
        val tempPhotos = productDao.getReferencePhotosForProduct(localTempProductId)

        productDao.upsertProducts(listOf(canonicalProduct))
        val existingServerProjection = productDao.getStockProjection(canonicalProduct.id)
        if (existingServerProjection == null) {
            productDao.upsertStockProjections(
                listOf(
                    LocalStockProjectionEntity(
                        productId = canonicalProduct.id,
                        serverQty = serverQty,
                        serverUpdatedAtEpochMs = syncedAtEpochMs,
                        pendingDeltaQty = preservedPendingDelta,
                        provisionalAvailableQty = serverQty + preservedPendingDelta,
                    )
                )
            )
        } else if (preservedPendingDelta != 0) {
            productDao.applyProvisionalStockDelta(canonicalProduct.id, preservedPendingDelta)
            productDao.applyServerStockUpdate(canonicalProduct.id, serverQty, syncedAtEpochMs)
        }

        for (photo in tempPhotos) {
            productDao.upsertReferencePhoto(
                photo.copy(
                    id = "ref_${canonicalProduct.id}_${photo.angle.lowercase()}",
                    productId = canonicalProduct.id,
                    lastVerifiedAtEpochMs = syncedAtEpochMs,
                )
            )
        }

        if (localTempProductId < 0L && localTempProductId != canonicalProduct.id) {
            saleDao.relinkSaleItemProductId(
                oldProductId = localTempProductId,
                newProductId = canonicalProduct.id,
            )
            for ((childEventId, updatedPayload) in relinkedSalePayloads) {
                updateEventPayload(childEventId, updatedPayload)
            }
            productDao.deleteProductById(localTempProductId)
        }

        financialAndDraftDao.markProductDraftSynced(
            draftId = draftId,
            serverProductId = canonicalProduct.id,
            syncStatus = "SERVER_CONFIRMED",
        )
        updateEventProgress(
            eventId = eventId,
            status = "SYNCED",
            retryCount = retryCount,
            attemptEpochMs = syncedAtEpochMs,
            nextRetryEpochMs = syncedAtEpochMs,
            failureReason = null,
            serverAckId = canonicalProduct.id.toString(),
        )
        auditDao.appendAudit(auditEvent)
    }

    @Transaction
    suspend fun reconcileSyncedSaleReversalAtomically(
        eventId: String,
        clientSaleId: String,
        productId: Long,
        restoredQty: Int,
        remainingServerQty: Int,
        reason: String,
        financialEventId: String,
        syncedAtEpochMs: Long,
        retryCount: Int,
        auditEvent: LocalAuditEventEntity,
        saleDao: SaleDao,
        productDao: ProductDao,
        financialAndDraftDao: FinancialAndDraftDao,
        auditDao: AuditDao,
    ) {
        updateEventProgress(
            eventId = eventId,
            status = "SYNCED",
            retryCount = retryCount,
            attemptEpochMs = syncedAtEpochMs,
            nextRetryEpochMs = syncedAtEpochMs,
            failureReason = null,
            serverAckId = clientSaleId,
        )
        saleDao.updateSaleReversalState(
            clientSaleId = clientSaleId,
            reason = reason,
            syncStatus = "SERVER_CONFIRMED",
        )
        // Reversal added +restoredQty to pending_delta_qty while offline; settle that delta now that server_qty reflects the restock.
        productDao.reconcileSyncedSaleProjection(
            productId = productId,
            syncedQtySold = -restoredQty,
            newServerQty = remainingServerQty,
            updatedAtEpochMs = syncedAtEpochMs,
        )
        productDao.updateProductServerStock(
            productId = productId,
            serverQty = remainingServerQty,
            updatedAtEpochMs = syncedAtEpochMs,
        )
        financialAndDraftDao.updateFinancialSyncStatus(
            eventId = financialEventId,
            syncStatus = "SERVER_CONFIRMED",
        )
        auditDao.appendAudit(auditEvent)
    }

    @Query("SELECT * FROM sync_queue_events WHERE entity_id = :entityId AND event_type = 'STOCK_MOVEMENT' LIMIT 1")
    suspend fun findStockMovementEventByReceiptId(entityId: String): SyncQueueEventEntity?

    @Transaction
    suspend fun reconcileSyncedStockReceiptAtomically(
        eventId: String,
        productId: Long,
        qtyReceived: Int,
        newServerQty: Int,
        financialEventId: String?,
        syncedAtEpochMs: Long,
        retryCount: Int,
        auditEvent: LocalAuditEventEntity,
        productDao: ProductDao,
        financialAndDraftDao: FinancialAndDraftDao,
        auditDao: AuditDao,
    ) {
        updateEventProgress(
            eventId = eventId,
            status = "SYNCED",
            retryCount = retryCount,
            attemptEpochMs = syncedAtEpochMs,
            nextRetryEpochMs = syncedAtEpochMs,
            failureReason = null,
            serverAckId = newServerQty.toString(),
        )
        productDao.reconcileSyncedSaleProjection(
            productId = productId,
            syncedQtySold = -qtyReceived,
            newServerQty = newServerQty,
            updatedAtEpochMs = syncedAtEpochMs,
        )
        productDao.updateProductServerStock(
            productId = productId,
            serverQty = newServerQty,
            updatedAtEpochMs = syncedAtEpochMs,
        )
        if (financialEventId != null) {
            financialAndDraftDao.updateFinancialSyncStatus(
                eventId = financialEventId,
                syncStatus = "SERVER_CONFIRMED",
            )
        }
        auditDao.appendAudit(auditEvent)
    }

    @Query("SELECT * FROM sync_queue_events WHERE entity_id = :entityId AND event_type = 'STOCK_ADJUSTMENT' LIMIT 1")
    suspend fun findStockAdjustmentEventByAdjustmentId(entityId: String): SyncQueueEventEntity?

    @Transaction
    suspend fun reconcileSyncedStockAdjustmentAtomically(
        eventId: String,
        productId: Long,
        deltaQty: Int,
        newServerQty: Int,
        syncedAtEpochMs: Long,
        retryCount: Int,
        auditEvent: LocalAuditEventEntity,
        productDao: ProductDao,
        auditDao: AuditDao,
    ) {
        updateEventProgress(
            eventId = eventId,
            status = "SYNCED",
            retryCount = retryCount,
            attemptEpochMs = syncedAtEpochMs,
            nextRetryEpochMs = syncedAtEpochMs,
            failureReason = null,
            serverAckId = newServerQty.toString(),
        )
        productDao.reconcileSyncedSaleProjection(
            productId = productId,
            syncedQtySold = -deltaQty,
            newServerQty = newServerQty,
            updatedAtEpochMs = syncedAtEpochMs,
        )
        productDao.updateProductServerStock(
            productId = productId,
            serverQty = newServerQty,
            updatedAtEpochMs = syncedAtEpochMs,
        )
        auditDao.appendAudit(auditEvent)
    }

    @Transaction
    suspend fun commitOfflineStockCountAtomically(
        deltasByProductId: List<Pair<Long, Int>>,
        syncEvent: SyncQueueEventEntity,
        auditEvent: LocalAuditEventEntity,
        updatedAtEpochMs: Long,
        productDao: ProductDao,
        auditDao: AuditDao,
    ) {
        productDao.applyBatchStockCountOfflineDelta(
            deltasByProductId = deltasByProductId,
            updatedAtEpochMs = updatedAtEpochMs,
        )
        upsertEvent(syncEvent)
        auditDao.appendAudit(auditEvent)
    }

    @Transaction
    suspend fun reconcileSyncedStockCountAtomically(
        eventId: String,
        serverSessionId: String,
        countedItems: List<Triple<Long, Int, Int>>, // (productId, deltaAppliedOffline, countedQty)
        syncedAtEpochMs: Long,
        retryCount: Int,
        auditEvent: LocalAuditEventEntity,
        productDao: ProductDao,
        auditDao: AuditDao,
    ) {
        updateEventProgress(
            eventId = eventId,
            status = "SYNCED",
            retryCount = retryCount,
            attemptEpochMs = syncedAtEpochMs,
            nextRetryEpochMs = syncedAtEpochMs,
            failureReason = null,
            serverAckId = serverSessionId,
        )
        for ((productId, deltaAppliedOffline, countedQty) in countedItems) {
            val safeQty = countedQty.coerceAtLeast(0)
            productDao.reconcileSyncedSaleProjection(
                productId = productId,
                syncedQtySold = -deltaAppliedOffline,
                newServerQty = safeQty,
                updatedAtEpochMs = syncedAtEpochMs,
            )
            productDao.updateProductServerStock(
                productId = productId,
                serverQty = safeQty,
                updatedAtEpochMs = syncedAtEpochMs,
            )
        }
        auditDao.appendAudit(auditEvent)
    }

    @Transaction
    suspend fun commitFinancialEventOfflineAtomically(
        financialEvent: PendingFinancialEventEntity,
        syncEvent: SyncQueueEventEntity,
        auditEvent: LocalAuditEventEntity,
        financialAndDraftDao: FinancialAndDraftDao,
        auditDao: AuditDao,
    ) {
        financialAndDraftDao.upsertFinancialEvent(financialEvent)
        upsertEvent(syncEvent)
        auditDao.appendAudit(auditEvent)
    }

    @Transaction
    suspend fun reconcileSyncedFinancialEventAtomically(
        eventId: String,
        financialEventId: String,
        serverEventId: String,
        syncedAtEpochMs: Long,
        retryCount: Int,
        auditEvent: LocalAuditEventEntity,
        financialAndDraftDao: FinancialAndDraftDao,
        auditDao: AuditDao,
    ) {
        updateEventProgress(
            eventId = eventId,
            status = "SYNCED",
            retryCount = retryCount,
            attemptEpochMs = syncedAtEpochMs,
            nextRetryEpochMs = syncedAtEpochMs,
            failureReason = null,
            serverAckId = serverEventId,
        )
        financialAndDraftDao.updateFinancialSyncStatus(
            eventId = financialEventId,
            syncStatus = "SERVER_CONFIRMED",
        )
        auditDao.appendAudit(auditEvent)
    }

    @Transaction
    suspend fun markFinancialEventConflictAtomically(
        eventId: String,
        financialEventId: String,
        conflictMessage: String,
        attemptEpochMs: Long,
        retryCount: Int,
        auditEvent: LocalAuditEventEntity,
        financialAndDraftDao: FinancialAndDraftDao,
        auditDao: AuditDao,
    ) {
        updateEventProgress(
            eventId = eventId,
            status = "CONFLICT",
            retryCount = retryCount,
            attemptEpochMs = attemptEpochMs,
            nextRetryEpochMs = attemptEpochMs,
            failureReason = conflictMessage,
            serverAckId = null,
        )
        financialAndDraftDao.updateFinancialSyncStatus(
            eventId = financialEventId,
            syncStatus = "CONFLICT",
        )
        auditDao.appendAudit(auditEvent)
    }

    @Query("SELECT * FROM sync_queue_events WHERE entity_id = :clientPurchaseId AND event_type = 'PURCHASE_RECEIPT' LIMIT 1")
    suspend fun findPurchaseEventByClientPurchaseId(clientPurchaseId: String): SyncQueueEventEntity?

    @Transaction
    suspend fun commitPurchaseOnlineAtomically(
        updatedProducts: List<ProductEntity>,
        existingProductIncrements: List<Triple<Long, Int, Double>>, // (productId, serverQtyAfter, unitCost)
        financialEvent: PendingFinancialEventEntity?,
        auditEvent: LocalAuditEventEntity,
        updatedAtEpochMs: Long,
        productDao: ProductDao,
        financialAndDraftDao: FinancialAndDraftDao,
        auditDao: AuditDao,
    ) {
        if (updatedProducts.isNotEmpty()) {
            productDao.upsertProducts(updatedProducts)
            for (prod in updatedProducts) {
                val existingProj = productDao.getStockProjection(prod.id)
                if (existingProj == null) {
                    productDao.upsertStockProjections(
                        listOf(
                            LocalStockProjectionEntity(
                                productId = prod.id,
                                serverQty = prod.serverStockQty,
                                serverUpdatedAtEpochMs = updatedAtEpochMs,
                                pendingDeltaQty = 0,
                                provisionalAvailableQty = prod.serverStockQty,
                            )
                        )
                    )
                } else {
                    productDao.applyServerStockUpdate(prod.id, prod.serverStockQty, updatedAtEpochMs)
                }
            }
        }

        for ((productId, serverQtyAfter, unitCost) in existingProductIncrements) {
            val existingProj = productDao.getStockProjection(productId)
            if (existingProj == null) {
                productDao.upsertStockProjections(
                    listOf(
                        LocalStockProjectionEntity(
                            productId = productId,
                            serverQty = serverQtyAfter,
                            serverUpdatedAtEpochMs = updatedAtEpochMs,
                            pendingDeltaQty = 0,
                            provisionalAvailableQty = serverQtyAfter,
                        )
                    )
                )
            } else {
                productDao.applyServerStockUpdate(productId, serverQtyAfter, updatedAtEpochMs)
            }
            productDao.updateProductServerStock(productId, serverQtyAfter, updatedAtEpochMs)
            if (unitCost > 0.0) {
                productDao.updateProductBuyPrice(productId, unitCost, updatedAtEpochMs)
            }
        }

        if (financialEvent != null) {
            financialAndDraftDao.upsertFinancialEvent(financialEvent)
        }
        auditDao.appendAudit(auditEvent)
    }

    @Transaction
    suspend fun commitPurchaseOfflineAtomically(
        newTempProducts: List<ProductEntity>,
        newTempProjections: List<LocalStockProjectionEntity>,
        existingStockDeltas: List<Triple<Long, Int, Double>>, // (productId, +qty, unitCost)
        financialEvent: PendingFinancialEventEntity?,
        syncEvent: SyncQueueEventEntity,
        auditEvent: LocalAuditEventEntity,
        updatedAtEpochMs: Long,
        productDao: ProductDao,
        financialAndDraftDao: FinancialAndDraftDao,
        auditDao: AuditDao,
    ) {
        if (newTempProducts.isNotEmpty()) {
            productDao.upsertProducts(newTempProducts)
        }
        if (newTempProjections.isNotEmpty()) {
            productDao.upsertStockProjections(newTempProjections)
        }
        for ((productId, deltaQty, unitCost) in existingStockDeltas) {
            val existingProj = productDao.getStockProjection(productId)
            if (existingProj == null) {
                val product = productDao.getProductById(productId)
                val baseQty = product?.serverStockQty ?: 0
                productDao.upsertStockProjections(
                    listOf(
                        LocalStockProjectionEntity(
                            productId = productId,
                            serverQty = baseQty,
                            serverUpdatedAtEpochMs = updatedAtEpochMs,
                            pendingDeltaQty = deltaQty,
                            provisionalAvailableQty = baseQty + deltaQty,
                        )
                    )
                )
            } else {
                productDao.applyProvisionalStockDelta(productId, deltaQty)
            }
            if (unitCost > 0.0) {
                productDao.updateProductBuyPrice(productId, unitCost, updatedAtEpochMs)
            }
        }
        if (financialEvent != null) {
            financialAndDraftDao.upsertFinancialEvent(financialEvent)
        }
        upsertEvent(syncEvent)
        auditDao.appendAudit(auditEvent)
    }

    @Transaction
    suspend fun reconcileSyncedPurchaseAtomically(
        eventId: String,
        serverPurchaseId: String,
        newCanonicalProducts: List<Triple<Long?, ProductEntity, Int>>, // (localTempId, canonicalProduct, serverQtyAfter)
        existingIncrements: List<Triple<Long, Int, Int>>, // (productId, qtyAddedOffline, serverQtyAfter)
        financialEventId: String?,
        syncedAtEpochMs: Long,
        retryCount: Int,
        auditEvent: LocalAuditEventEntity,
        productDao: ProductDao,
        saleDao: SaleDao,
        financialAndDraftDao: FinancialAndDraftDao,
        auditDao: AuditDao,
    ) {
        updateEventProgress(
            eventId = eventId,
            status = "SYNCED",
            retryCount = retryCount,
            attemptEpochMs = syncedAtEpochMs,
            nextRetryEpochMs = syncedAtEpochMs,
            failureReason = null,
            serverAckId = serverPurchaseId,
        )

        for ((localTempId, canonicalProduct, serverQtyAfter) in newCanonicalProducts) {
            val preservedDelta = if (localTempId != null && localTempId < 0L) {
                productDao.getStockProjection(localTempId)?.pendingDeltaQty ?: 0
            } else {
                0
            }
            productDao.upsertProducts(listOf(canonicalProduct))
            val existingProj = productDao.getStockProjection(canonicalProduct.id)
            if (existingProj == null) {
                productDao.upsertStockProjections(
                    listOf(
                        LocalStockProjectionEntity(
                            productId = canonicalProduct.id,
                            serverQty = serverQtyAfter,
                            serverUpdatedAtEpochMs = syncedAtEpochMs,
                            pendingDeltaQty = preservedDelta,
                            provisionalAvailableQty = serverQtyAfter + preservedDelta,
                        )
                    )
                )
            } else {
                if (preservedDelta != 0) {
                    productDao.applyProvisionalStockDelta(canonicalProduct.id, preservedDelta)
                }
                productDao.applyServerStockUpdate(canonicalProduct.id, serverQtyAfter, syncedAtEpochMs)
            }

            if (localTempId != null && localTempId < 0L && localTempId != canonicalProduct.id) {
                saleDao.relinkSaleItemProductId(oldProductId = localTempId, newProductId = canonicalProduct.id)
                productDao.deleteProductById(localTempId)
            }
        }

        for ((productId, qtyAddedOffline, serverQtyAfter) in existingIncrements) {
            productDao.reconcileSyncedSaleProjection(
                productId = productId,
                syncedQtySold = -qtyAddedOffline,
                newServerQty = serverQtyAfter,
                updatedAtEpochMs = syncedAtEpochMs,
            )
            productDao.updateProductServerStock(
                productId = productId,
                serverQty = serverQtyAfter,
                updatedAtEpochMs = syncedAtEpochMs,
            )
        }

        if (financialEventId != null) {
            financialAndDraftDao.updateFinancialSyncStatus(
                eventId = financialEventId,
                syncStatus = "SERVER_CONFIRMED",
            )
        }
        auditDao.appendAudit(auditEvent)
    }

    @Transaction
    suspend fun markPurchaseConflictAtomically(
        eventId: String,
        financialEventId: String?,
        conflictMessage: String,
        attemptEpochMs: Long,
        retryCount: Int,
        auditEvent: LocalAuditEventEntity,
        financialAndDraftDao: FinancialAndDraftDao,
        auditDao: AuditDao,
    ) {
        updateEventProgress(
            eventId = eventId,
            status = "CONFLICT",
            retryCount = retryCount,
            attemptEpochMs = attemptEpochMs,
            nextRetryEpochMs = attemptEpochMs,
            failureReason = conflictMessage,
            serverAckId = null,
        )
        if (financialEventId != null) {
            financialAndDraftDao.updateFinancialSyncStatus(
                eventId = financialEventId,
                syncStatus = "CONFLICT",
            )
        }
        auditDao.appendAudit(auditEvent)
    }

    @Query("SELECT * FROM sync_queue_events WHERE entity_id = :clientServiceId AND event_type = 'SERVICE_OPERATION' LIMIT 1")
    suspend fun findServiceOperationEventByClientId(clientServiceId: String): SyncQueueEventEntity?

    @Transaction
    suspend fun commitServiceOperationOnlineAtomically(
        updatedServerStock: List<Pair<Long, Int>>, // (productId, serverQtyAfter)
        financialEvent: PendingFinancialEventEntity?,
        auditEvent: LocalAuditEventEntity,
        updatedAtEpochMs: Long,
        productDao: ProductDao,
        financialAndDraftDao: FinancialAndDraftDao,
        auditDao: AuditDao,
    ) {
        for ((productId, serverQtyAfter) in updatedServerStock) {
            val safeQty = serverQtyAfter.coerceAtLeast(0)
            val existingProj = productDao.getStockProjection(productId)
            if (existingProj == null) {
                productDao.upsertStockProjections(
                    listOf(
                        LocalStockProjectionEntity(
                            productId = productId,
                            serverQty = safeQty,
                            serverUpdatedAtEpochMs = updatedAtEpochMs,
                            pendingDeltaQty = 0,
                            provisionalAvailableQty = safeQty,
                        )
                    )
                )
            } else {
                productDao.applyServerStockUpdate(productId, safeQty, updatedAtEpochMs)
            }
            productDao.updateProductServerStock(productId, safeQty, updatedAtEpochMs)
        }
        if (financialEvent != null) {
            financialAndDraftDao.upsertFinancialEvent(financialEvent)
        }
        auditDao.appendAudit(auditEvent)
    }

    @Transaction
    suspend fun commitServiceOperationOfflineAtomically(
        stockDeductions: List<Pair<Long, Int>>, // (productId, deductQty > 0)
        financialEvent: PendingFinancialEventEntity?,
        syncEvent: SyncQueueEventEntity,
        auditEvent: LocalAuditEventEntity,
        updatedAtEpochMs: Long,
        productDao: ProductDao,
        financialAndDraftDao: FinancialAndDraftDao,
        auditDao: AuditDao,
    ) {
        for ((productId, deductQty) in stockDeductions) {
            val existingProj = productDao.getStockProjection(productId)
            if (existingProj == null) {
                val product = productDao.getProductById(productId)
                val baseQty = product?.serverStockQty ?: 0
                productDao.upsertStockProjections(
                    listOf(
                        LocalStockProjectionEntity(
                            productId = productId,
                            serverQty = baseQty,
                            serverUpdatedAtEpochMs = updatedAtEpochMs,
                            pendingDeltaQty = -deductQty,
                            provisionalAvailableQty = (baseQty - deductQty).coerceAtLeast(0),
                        )
                    )
                )
            } else {
                productDao.applyProvisionalStockDelta(productId, -deductQty)
            }
        }
        if (financialEvent != null) {
            financialAndDraftDao.upsertFinancialEvent(financialEvent)
        }
        upsertEvent(syncEvent)
        auditDao.appendAudit(auditEvent)
    }

    @Transaction
    suspend fun reconcileSyncedServiceOperationAtomically(
        eventId: String,
        serverServiceId: String,
        deductedStockReconciliations: List<Triple<Long, Int, Int>>, // (productId, deductedOfflineQty, serverQtyAfter)
        financialEventId: String?,
        syncedAtEpochMs: Long,
        retryCount: Int,
        auditEvent: LocalAuditEventEntity,
        productDao: ProductDao,
        financialAndDraftDao: FinancialAndDraftDao,
        auditDao: AuditDao,
    ) {
        updateEventProgress(
            eventId = eventId,
            status = "SYNCED",
            retryCount = retryCount,
            attemptEpochMs = syncedAtEpochMs,
            nextRetryEpochMs = syncedAtEpochMs,
            failureReason = null,
            serverAckId = serverServiceId,
        )
        for ((productId, deductedQty, serverQtyAfter) in deductedStockReconciliations) {
            val safeQty = serverQtyAfter.coerceAtLeast(0)
            productDao.reconcileSyncedSaleProjection(
                productId = productId,
                syncedQtySold = deductedQty,
                newServerQty = safeQty,
                updatedAtEpochMs = syncedAtEpochMs,
            )
            productDao.updateProductServerStock(
                productId = productId,
                serverQty = safeQty,
                updatedAtEpochMs = syncedAtEpochMs,
            )
        }
        if (financialEventId != null) {
            financialAndDraftDao.updateFinancialSyncStatus(
                eventId = financialEventId,
                syncStatus = "SERVER_CONFIRMED",
            )
        }
        auditDao.appendAudit(auditEvent)
    }

    @Transaction
    suspend fun markServiceOperationConflictAtomically(
        eventId: String,
        financialEventId: String?,
        conflictMessage: String,
        attemptEpochMs: Long,
        retryCount: Int,
        auditEvent: LocalAuditEventEntity,
        financialAndDraftDao: FinancialAndDraftDao,
        auditDao: AuditDao,
    ) {
        updateEventProgress(
            eventId = eventId,
            status = "CONFLICT",
            retryCount = retryCount,
            attemptEpochMs = attemptEpochMs,
            nextRetryEpochMs = attemptEpochMs,
            failureReason = conflictMessage,
            serverAckId = null,
        )
        if (financialEventId != null) {
            financialAndDraftDao.updateFinancialSyncStatus(
                eventId = financialEventId,
                syncStatus = "CONFLICT",
            )
        }
        auditDao.appendAudit(auditEvent)
    }

    @Query("SELECT * FROM sync_queue_events WHERE entity_id = :clientUpdateId AND event_type = 'ORDER_UPDATE' LIMIT 1")
    suspend fun findOrderUpdateEventByClientId(clientUpdateId: String): SyncQueueEventEntity?

    @Transaction
    suspend fun commitOrderUpdateOfflineAtomically(
        syncEvent: SyncQueueEventEntity,
        auditEvent: LocalAuditEventEntity,
        auditDao: AuditDao,
    ) {
        upsertEvent(syncEvent)
        auditDao.appendAudit(auditEvent)
    }

    @Transaction
    suspend fun reconcileSyncedOrderUpdateAtomically(
        eventId: String,
        serverEventId: String,
        syncedAtEpochMs: Long,
        retryCount: Int,
        auditEvent: LocalAuditEventEntity,
        auditDao: AuditDao,
    ) {
        updateEventProgress(
            eventId = eventId,
            status = "SYNCED",
            retryCount = retryCount,
            attemptEpochMs = syncedAtEpochMs,
            nextRetryEpochMs = syncedAtEpochMs,
            failureReason = null,
            serverAckId = serverEventId,
        )
        auditDao.appendAudit(auditEvent)
    }

    @Transaction
    suspend fun markOrderUpdateConflictAtomically(
        eventId: String,
        conflictMessage: String,
        attemptEpochMs: Long,
        retryCount: Int,
        auditEvent: LocalAuditEventEntity,
        auditDao: AuditDao,
    ) {
        updateEventProgress(
            eventId = eventId,
            status = "CONFLICT",
            retryCount = retryCount,
            attemptEpochMs = attemptEpochMs,
            nextRetryEpochMs = attemptEpochMs,
            failureReason = conflictMessage,
            serverAckId = null,
        )
        auditDao.appendAudit(auditEvent)
    }

    @Query("SELECT * FROM sync_queue_events WHERE entity_id = :clientConversionId AND event_type = 'ORDER_TO_SALE' LIMIT 1")
    suspend fun findOrderConversionEventByClientId(clientConversionId: String): SyncQueueEventEntity?

    @Transaction
    suspend fun commitOrderConversionOnlineAtomically(
        sale: LocalSaleEntity,
        items: List<LocalSaleItemEntity>,
        updatedServerStock: List<Pair<Long, Int>>,
        auditEvent: LocalAuditEventEntity,
        updatedAtEpochMs: Long,
        saleDao: SaleDao,
        productDao: ProductDao,
        auditDao: AuditDao,
    ) {
        saleDao.insertSale(sale)
        saleDao.insertSaleItems(items)
        for ((productId, serverQtyAfter) in updatedServerStock) {
            val safeQty = serverQtyAfter.coerceAtLeast(0)
            if (productDao.getStockProjection(productId) == null) {
                productDao.upsertStockProjections(
                    listOf(
                        LocalStockProjectionEntity(
                            productId = productId,
                            serverQty = safeQty,
                            serverUpdatedAtEpochMs = updatedAtEpochMs,
                            pendingDeltaQty = 0,
                            provisionalAvailableQty = safeQty,
                        )
                    )
                )
            } else {
                productDao.applyServerStockUpdate(productId, safeQty, updatedAtEpochMs)
            }
            productDao.updateProductServerStock(productId, safeQty, updatedAtEpochMs)
        }
        auditDao.appendAudit(auditEvent)
    }

    @Transaction
    suspend fun commitOrderConversionOfflineAtomically(
        sale: LocalSaleEntity,
        items: List<LocalSaleItemEntity>,
        stockDeductions: List<Pair<Long, Int>>,
        syncEvent: SyncQueueEventEntity,
        auditEvent: LocalAuditEventEntity,
        updatedAtEpochMs: Long,
        saleDao: SaleDao,
        productDao: ProductDao,
        auditDao: AuditDao,
    ) {
        saleDao.insertSale(sale)
        saleDao.insertSaleItems(items)
        for ((productId, qtyDeducted) in stockDeductions) {
            if (productDao.getStockProjection(productId) == null) {
                val product = productDao.getProductById(productId)
                val baseQty = product?.serverStockQty ?: 0
                productDao.upsertStockProjections(
                    listOf(
                        LocalStockProjectionEntity(
                            productId = productId,
                            serverQty = baseQty,
                            serverUpdatedAtEpochMs = updatedAtEpochMs,
                            pendingDeltaQty = -qtyDeducted,
                            provisionalAvailableQty = baseQty - qtyDeducted,
                        )
                    )
                )
            } else {
                productDao.applyProvisionalStockDelta(productId, -qtyDeducted)
            }
        }
        upsertEvent(syncEvent)
        auditDao.appendAudit(auditEvent)
    }

    @Transaction
    suspend fun reconcileSyncedOrderConversionAtomically(
        eventId: String,
        clientSaleId: String,
        serverConversionId: String,
        firstServerSaleId: Long?,
        productReconciliations: List<Triple<Long, Int, Int>>, // (productId, qtyDeductedOffline, serverQtyAfter)
        syncedAtEpochMs: Long,
        retryCount: Int,
        auditEvent: LocalAuditEventEntity,
        saleDao: SaleDao,
        productDao: ProductDao,
        auditDao: AuditDao,
    ) {
        updateEventProgress(
            eventId = eventId,
            status = "SYNCED",
            retryCount = retryCount,
            attemptEpochMs = syncedAtEpochMs,
            nextRetryEpochMs = syncedAtEpochMs,
            failureReason = null,
            serverAckId = serverConversionId,
        )
        saleDao.updateSaleSyncStatus(
            clientSaleId = clientSaleId,
            syncStatus = "SERVER_CONFIRMED",
            serverSaleId = firstServerSaleId,
        )
        for ((productId, qtyDeducted, serverQtyAfter) in productReconciliations) {
            val safeQty = serverQtyAfter.coerceAtLeast(0)
            productDao.reconcileSyncedSaleProjection(
                productId = productId,
                syncedQtySold = qtyDeducted,
                newServerQty = safeQty,
                updatedAtEpochMs = syncedAtEpochMs,
            )
            productDao.updateProductServerStock(
                productId = productId,
                serverQty = safeQty,
                updatedAtEpochMs = syncedAtEpochMs,
            )
        }
        auditDao.appendAudit(auditEvent)
    }

    @Transaction
    suspend fun markOrderConversionConflictAtomically(
        eventId: String,
        clientSaleId: String,
        conflictMessage: String,
        attemptEpochMs: Long,
        retryCount: Int,
        auditEvent: LocalAuditEventEntity,
        saleDao: SaleDao,
        auditDao: AuditDao,
    ) {
        updateEventProgress(
            eventId = eventId,
            status = "CONFLICT",
            retryCount = retryCount,
            attemptEpochMs = attemptEpochMs,
            nextRetryEpochMs = attemptEpochMs,
            failureReason = conflictMessage,
            serverAckId = null,
        )
        saleDao.updateSaleSyncStatus(
            clientSaleId = clientSaleId,
            syncStatus = "CONFLICT",
            serverSaleId = null,
        )
        auditDao.appendAudit(auditEvent)
    }
}

@Dao
interface FinancialAndDraftDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFinancialEvent(event: PendingFinancialEventEntity)

    @Query("UPDATE pending_financial_events SET sync_status = :syncStatus WHERE event_id = :eventId")
    suspend fun updateFinancialSyncStatus(eventId: String, syncStatus: String)

    @Query("SELECT * FROM pending_financial_events WHERE UPPER(transaction_reference) = UPPER(:ref) LIMIT 1")
    suspend fun findByTransactionReference(ref: String): PendingFinancialEventEntity?

    @Query("SELECT * FROM pending_financial_events ORDER BY event_timestamp_epoch_ms DESC")
    fun observeFinancialEvents(): Flow<List<PendingFinancialEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProductDraft(draft: PendingProductDraftEntity)

    @Query("SELECT * FROM pending_product_drafts ORDER BY created_at_epoch_ms DESC")
    fun observeProductDrafts(): Flow<List<PendingProductDraftEntity>>

    @Query("SELECT * FROM pending_product_drafts WHERE sync_status != 'SERVER_CONFIRMED' ORDER BY created_at_epoch_ms DESC")
    suspend fun getPendingProductDrafts(): List<PendingProductDraftEntity>

    @Query("SELECT * FROM pending_product_drafts")
    suspend fun getAllProductDraftsSnapshot(): List<PendingProductDraftEntity>

    @Query("UPDATE pending_product_drafts SET server_product_id = :serverProductId, sync_status = :syncStatus WHERE draft_id = :draftId")
    suspend fun markProductDraftSynced(draftId: String, serverProductId: Long, syncStatus: String)

    @Transaction
    suspend fun commitOfflineProductDraftAtomically(
        draft: PendingProductDraftEntity,
        tempProduct: ProductEntity,
        stockProjection: LocalStockProjectionEntity,
        referencePhoto: ProductReferencePhotoEntity?,
        syncEvent: SyncQueueEventEntity,
        auditEvent: LocalAuditEventEntity,
        productDao: ProductDao,
        syncQueueDao: SyncQueueDao,
        auditDao: AuditDao,
    ) {
        upsertProductDraft(draft)
        productDao.upsertProducts(listOf(tempProduct))
        productDao.upsertStockProjections(listOf(stockProjection))
        if (referencePhoto != null) {
            productDao.upsertReferencePhoto(referencePhoto)
        }
        syncQueueDao.upsertEvent(syncEvent)
        auditDao.appendAudit(auditEvent)
    }

    @Transaction
    suspend fun commitOnlineProductRegistrationAtomically(
        canonicalProduct: ProductEntity,
        stockProjection: LocalStockProjectionEntity,
        referencePhoto: ProductReferencePhotoEntity?,
        auditEvent: LocalAuditEventEntity,
        productDao: ProductDao,
        auditDao: AuditDao,
    ) {
        productDao.upsertProducts(listOf(canonicalProduct))
        productDao.upsertStockProjections(listOf(stockProjection))
        if (referencePhoto != null) {
            productDao.upsertReferencePhoto(referencePhoto)
        }
        auditDao.appendAudit(auditEvent)
    }
}

@Dao
interface AuditDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun appendAudit(event: LocalAuditEventEntity)

    @Query("SELECT * FROM local_audit_events ORDER BY occurred_at_epoch_ms DESC LIMIT :limit")
    fun observeRecentAudits(limit: Int = 250): Flow<List<LocalAuditEventEntity>>

    @Query("SELECT COUNT(*) FROM local_audit_events WHERE sync_status = 'LOCAL_PENDING'")
    fun observePendingAuditCount(): Flow<Int>

    @Query(
        """
        SELECT * FROM local_audit_events
        WHERE sync_status = 'LOCAL_PENDING'
        ORDER BY occurred_at_epoch_ms ASC
        LIMIT :limit
        """
    )
    suspend fun getPendingAudits(limit: Int = 50): List<LocalAuditEventEntity>

    @Query("UPDATE local_audit_events SET sync_status = 'SERVER_CONFIRMED' WHERE audit_id IN (:auditIds)")
    suspend fun markAuditsServerConfirmed(auditIds: List<String>)
}
