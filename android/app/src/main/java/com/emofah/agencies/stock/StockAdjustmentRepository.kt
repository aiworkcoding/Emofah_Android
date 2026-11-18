package com.emofah.agencies.stock

import com.emofah.agencies.data.SupabaseConfig
import com.emofah.agencies.data.local.EmofahDatabase
import com.emofah.agencies.data.local.entities.LocalAuditEventEntity
import com.emofah.agencies.data.local.entities.LocalStockProjectionEntity
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueuePolicy
import com.emofah.agencies.data.sync.SyncQueueStatus
import com.emofah.agencies.sales.ConnectivityChecker
import com.emofah.agencies.sales.DeviceIdentityProvider
import com.emofah.agencies.sales.SaleTransactionRepository
import com.emofah.agencies.sales.StaffRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * Stage 4.3 — Stock Adjustment Repository.
 * Handles online atomic stock adjustments via Supabase RPC and offline-first Room persistence
 * with provisional stock delta updates, audit logging, and WorkManager sync queue integration.
 */
class StockAdjustmentRepository(
    private val database: EmofahDatabase,
    private val identityProvider: DeviceIdentityProvider,
    private val connectivityChecker: ConnectivityChecker,
    private val adjustmentGateway: StockAdjustmentGateway = SupabaseStockAdjustmentClient(),
    private val isSupabaseConfigured: Boolean = SupabaseConfig.isConfigured,
    private val onOfflineEventQueued: () -> Unit = {},
) {
    private val productDao = database.productDao()
    private val syncQueueDao = database.syncQueueDao()
    private val auditDao = database.auditDao()

    suspend fun adjustStock(
        draft: StockAdjustmentDraft,
        staffRole: StaffRole = identityProvider.getStaffRole(),
    ): StockAdjustmentResult = withContext(Dispatchers.IO) {
        val validation = StockAdjustmentValidator.validateDraft(draft, staffRole)
        if (validation is StockAdjustmentValidation.Invalid) {
            return@withContext StockAdjustmentResult.Rejected(validation.message)
        }
        val cleanDraft = (validation as StockAdjustmentValidation.Valid).sanitizedDraft

        val existingProduct = productDao.getProductById(cleanDraft.productId)
            ?: return@withContext StockAdjustmentResult.Rejected("Product #${cleanDraft.productId} not found in local catalogue.")

        val actorId = identityProvider.getActorId()
        val deviceId = identityProvider.getDeviceId()
        val nowMs = System.currentTimeMillis()
        val adjustedAtIso = SaleTransactionRepository.formatIsoUtc(nowMs)

        // Idempotency check locally
        val existingEvent = syncQueueDao.findStockAdjustmentEventByAdjustmentId(cleanDraft.clientAdjustmentId)
        if (existingEvent != null) {
            val projection = productDao.getStockProjection(cleanDraft.productId)
            return@withContext StockAdjustmentResult.DuplicateIgnored(
                clientAdjustmentId = cleanDraft.clientAdjustmentId,
                serverQty = projection?.serverQty ?: existingProduct.serverStockQty,
            )
        }

        val rpcRequest = AndroidStockAdjustmentRpcRequest(
            productId = cleanDraft.productId,
            deltaQty = cleanDraft.deltaQty,
            reasonCode = cleanDraft.reason.code,
            notes = cleanDraft.notes,
            clientAdjustmentId = cleanDraft.clientAdjustmentId,
            idempotencyKey = cleanDraft.idempotencyKey,
            actorId = cleanDraft.supervisorApprovedBy ?: actorId,
            actorRole = staffRole.name,
            deviceId = deviceId,
            adjustedAtIso = adjustedAtIso,
        )

        val isOfflineProductDraft = cleanDraft.productId < 0L
        if (!isOfflineProductDraft && isSupabaseConfigured && connectivityChecker.isOnline()) {
            when (val outcome = adjustmentGateway.submitStockAdjustment(rpcRequest)) {
                is StockAdjustmentRpcOutcome.CommittedOnline -> {
                    val updatedServerQty = outcome.serverQty
                    val existingProj = productDao.getStockProjection(cleanDraft.productId)
                    if (existingProj == null) {
                        productDao.upsertStockProjections(
                            listOf(
                                LocalStockProjectionEntity(
                                    productId = cleanDraft.productId,
                                    serverQty = updatedServerQty,
                                    serverUpdatedAtEpochMs = nowMs,
                                    pendingDeltaQty = 0,
                                    provisionalAvailableQty = updatedServerQty,
                                )
                            )
                        )
                    } else {
                        productDao.applyServerStockUpdate(
                            productId = cleanDraft.productId,
                            serverQty = updatedServerQty,
                            updatedAtEpochMs = nowMs,
                        )
                    }
                    productDao.updateProductServerStock(
                        productId = cleanDraft.productId,
                        serverQty = updatedServerQty,
                        updatedAtEpochMs = nowMs,
                    )

                    auditDao.appendAudit(
                        LocalAuditEventEntity(
                            auditId = "audit-${UUID.randomUUID()}",
                            actorId = rpcRequest.actorId,
                            deviceId = deviceId,
                            operation = "STOCK_ADJUSTMENT_ONLINE",
                            entityType = "PRODUCT",
                            entityId = cleanDraft.productId.toString(),
                            beforeJson = JSONObject().apply {
                                put("server_stock_qty", existingProduct.serverStockQty)
                            }.toString(),
                            afterJson = JSONObject().apply {
                                put("client_adjustment_id", cleanDraft.clientAdjustmentId)
                                put("delta_qty", cleanDraft.deltaQty)
                                put("reason_code", cleanDraft.reason.code)
                                put("server_qty", updatedServerQty)
                                put("previous_qty", outcome.previousQty)
                                put("is_clamped", outcome.isClamped)
                                put("already_committed", outcome.alreadyCommitted)
                            }.toString(),
                            reason = "${cleanDraft.reason.label} (${cleanDraft.deltaQty}): ${cleanDraft.notes}",
                            syncStatus = SyncState.SERVER_CONFIRMED,
                            occurredAtEpochMs = nowMs,
                        )
                    )

                    return@withContext StockAdjustmentResult.SuccessOnline(
                        clientAdjustmentId = cleanDraft.clientAdjustmentId,
                        updatedServerQty = updatedServerQty,
                        previousServerQty = outcome.previousQty,
                        quantityDelta = cleanDraft.deltaQty,
                        reason = cleanDraft.reason,
                        isClamped = outcome.isClamped,
                        alreadyCommitted = outcome.alreadyCommitted,
                    )
                }

                is StockAdjustmentRpcOutcome.Rejected -> {
                    return@withContext StockAdjustmentResult.Rejected(outcome.message)
                }

                is StockAdjustmentRpcOutcome.RpcMissing -> {
                    return@withContext commitOfflineAdjustmentFallback(
                        draft = cleanDraft,
                        rpcRequest = rpcRequest,
                        actorId = actorId,
                        deviceId = deviceId,
                        nowMs = nowMs,
                        reason = "Saved locally offline — deploy adjust_android_stock migration to sync to cloud.",
                    )
                }

                is StockAdjustmentRpcOutcome.AuthRequired -> {
                    return@withContext commitOfflineAdjustmentFallback(
                        draft = cleanDraft,
                        rpcRequest = rpcRequest,
                        actorId = actorId,
                        deviceId = deviceId,
                        nowMs = nowMs,
                        reason = "Sign-in required (${outcome.message}) — saved locally in Room queue.",
                    )
                }

                is StockAdjustmentRpcOutcome.NetworkOrTransientError -> {
                    return@withContext commitOfflineAdjustmentFallback(
                        draft = cleanDraft,
                        rpcRequest = rpcRequest,
                        actorId = actorId,
                        deviceId = deviceId,
                        nowMs = nowMs,
                        reason = "Network unreachable (${outcome.message}) — saved locally in Room queue.",
                    )
                }
            }
        }

        return@withContext commitOfflineAdjustmentFallback(
            draft = cleanDraft,
            rpcRequest = rpcRequest,
            actorId = actorId,
            deviceId = deviceId,
            nowMs = nowMs,
            reason = if (!connectivityChecker.isOnline()) "Device offline" else "Awaiting remote sync",
        )
    }

    private suspend fun commitOfflineAdjustmentFallback(
        draft: StockAdjustmentDraft,
        rpcRequest: AndroidStockAdjustmentRpcRequest,
        actorId: String,
        deviceId: String,
        nowMs: Long,
        reason: String,
    ): StockAdjustmentResult {
        val existingProj = productDao.getStockProjection(draft.productId)
        val product = productDao.getProductById(draft.productId)
        val baseServerQty = existingProj?.serverQty ?: product?.serverStockQty ?: 0

        if (existingProj == null) {
            val initialProvisional = (baseServerQty + draft.deltaQty).coerceAtLeast(0)
            productDao.upsertStockProjections(
                listOf(
                    LocalStockProjectionEntity(
                        productId = draft.productId,
                        serverQty = baseServerQty,
                        serverUpdatedAtEpochMs = nowMs,
                        pendingDeltaQty = draft.deltaQty,
                        provisionalAvailableQty = initialProvisional,
                    )
                )
            )
        } else {
            productDao.applyProvisionalStockDelta(
                productId = draft.productId,
                deltaQty = draft.deltaQty,
            )
        }

        val queuePayloadJson = JSONObject().apply {
            put("client_adjustment_id", rpcRequest.clientAdjustmentId)
            put("idempotency_key", rpcRequest.idempotencyKey)
            put("product_id", rpcRequest.productId)
            put("delta_qty", rpcRequest.deltaQty)
            put("reason_code", rpcRequest.reasonCode)
            put("notes", rpcRequest.notes)
            put("actor_id", rpcRequest.actorId)
            put("actor_role", rpcRequest.actorRole)
            put("device_id", rpcRequest.deviceId)
            put("adjusted_at", rpcRequest.adjustedAtIso)
        }.toString()

        val parentDraftEventId = if (draft.productId < 0L) {
            syncQueueDao.getEventsByType(SyncEventType.PRODUCT_DRAFT)
                .firstOrNull { evt ->
                    SyncQueuePolicy.parseProductDraftPayload(evt)?.localTempProductId == draft.productId
                }?.eventId
        } else {
            null
        }

        val queueEventId = "evt-stock-adj-${draft.clientAdjustmentId}"
        val queueEvent = SyncQueueEventEntity(
            eventId = queueEventId,
            eventType = SyncEventType.STOCK_ADJUSTMENT,
            entityId = draft.clientAdjustmentId,
            dependencyEventId = parentDraftEventId,
            payloadJson = queuePayloadJson,
            actorId = actorId,
            deviceId = deviceId,
            status = SyncQueueStatus.QUEUED,
            retryCount = 0,
            lastAttemptAtEpochMs = null,
            nextRetryAtEpochMs = nowMs,
            failureReason = null,
            serverAckId = null,
            createdAtEpochMs = nowMs,
        )
        syncQueueDao.upsertEvent(queueEvent)

        auditDao.appendAudit(
            LocalAuditEventEntity(
                auditId = "audit-${UUID.randomUUID()}",
                actorId = actorId,
                deviceId = deviceId,
                operation = "STOCK_ADJUSTMENT_OFFLINE_QUEUED",
                entityType = "PRODUCT",
                entityId = draft.productId.toString(),
                beforeJson = JSONObject().apply {
                    put("server_stock_qty", baseServerQty)
                }.toString(),
                afterJson = JSONObject().apply {
                    put("client_adjustment_id", draft.clientAdjustmentId)
                    put("delta_qty", draft.deltaQty)
                    put("reason_code", draft.reason.code)
                    put("queue_event_id", queueEventId)
                }.toString(),
                reason = "Offline stock adjustment: ${draft.reason.label} (${draft.deltaQty}). $reason",
                syncStatus = SyncState.LOCAL_PENDING,
                occurredAtEpochMs = nowMs,
            )
        )

        onOfflineEventQueued()

        val updatedProj = productDao.getStockProjection(draft.productId)
        val provisionalRem = updatedProj?.provisionalAvailableQty ?: (baseServerQty + draft.deltaQty).coerceAtLeast(0)

        return StockAdjustmentResult.SavedOffline(
            clientAdjustmentId = draft.clientAdjustmentId,
            queuedEventId = queueEventId,
            provisionalRemainingQty = provisionalRem,
            quantityDelta = draft.deltaQty,
            reason = draft.reason,
            reasonMessage = reason,
        )
    }
}
