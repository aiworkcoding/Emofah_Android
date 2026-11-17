package com.emofah.agencies.stock

import com.emofah.agencies.data.SupabaseConfig
import com.emofah.agencies.data.local.EmofahDatabase
import com.emofah.agencies.data.local.entities.LocalAuditEventEntity
import com.emofah.agencies.data.local.entities.LocalStockProjectionEntity
import com.emofah.agencies.data.local.entities.PendingFinancialEventEntity
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueuePolicy
import com.emofah.agencies.data.sync.SyncQueueStatus
import com.emofah.agencies.sales.ConnectivityChecker
import com.emofah.agencies.sales.DeviceIdentityProvider
import com.emofah.agencies.sales.SaleTransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * Stage 4.2 — Stock Receiving Repository.
 * Handles online atomic stock receiving via Supabase RPC and offline-first Room persistence
 * with provisional stock increment, financial supplier entry, and WorkManager sync queue.
 */
class StockReceivingRepository(
    private val database: EmofahDatabase,
    private val identityProvider: DeviceIdentityProvider,
    private val connectivityChecker: ConnectivityChecker,
    private val stockGateway: StockReceivingGateway = SupabaseStockReceivingClient(),
    private val isSupabaseConfigured: Boolean = SupabaseConfig.isConfigured,
    private val onOfflineEventQueued: () -> Unit = {},
) {
    private val productDao = database.productDao()
    private val syncQueueDao = database.syncQueueDao()
    private val financialAndDraftDao = database.financialAndDraftDao()
    private val auditDao = database.auditDao()

    suspend fun receiveStock(
        draft: StockReceiptDraft,
    ): StockReceivingResult = withContext(Dispatchers.IO) {
        val existingProduct = productDao.getProductById(draft.productId)
            ?: return@withContext StockReceivingResult.Rejected("Product #${draft.productId} not found in local catalogue.")

        if (draft.quantityReceived <= 0) {
            return@withContext StockReceivingResult.Rejected("Quantity received must be at least 1 unit.")
        }

        val actorId = identityProvider.getActorId()
        val deviceId = identityProvider.getDeviceId()
        val nowMs = System.currentTimeMillis()
        val receivedAtIso = SaleTransactionRepository.formatIsoUtc(nowMs)

        // Check if receipt already queued/committed locally
        val existingEvent = syncQueueDao.findStockMovementEventByReceiptId(draft.clientReceiptId)
        if (existingEvent != null) {
            val projection = productDao.getStockProjection(draft.productId)
            return@withContext StockReceivingResult.DuplicateIgnored(
                clientReceiptId = draft.clientReceiptId,
                serverQty = projection?.serverQty ?: existingProduct.serverStockQty,
            )
        }

        val totalCost = if (draft.unitCost != null && draft.unitCost > 0.0) {
            draft.unitCost * draft.quantityReceived
        } else {
            null
        }

        val financialEvent = if (totalCost != null && totalCost > 0.0) {
            PendingFinancialEventEntity(
                eventId = "fin-stock-rcpt-${draft.clientReceiptId}",
                source = "STOCK_RECEIVING",
                amount = totalCost,
                currency = "KES",
                transactionReference = draft.invoiceRef ?: "RCPT-${draft.clientReceiptId.take(10)}",
                counterparty = draft.supplier ?: "Supplier",
                direction = "OUTFLOW",
                rawEvidence = draft.invoiceRef,
                parsedJson = JSONObject().apply {
                    put("client_receipt_id", draft.clientReceiptId)
                    put("product_id", draft.productId)
                    put("qty_received", draft.quantityReceived)
                    put("unit_cost", draft.unitCost)
                    put("supplier", draft.supplier ?: JSONObject.NULL)
                }.toString(),
                provisionalCategory = "SUPPLIER_PAYMENT",
                classificationConfidence = 0.95f,
                reviewStatus = "AUTO_LINKED_RECEIPT",
                syncStatus = SyncState.LOCAL_PENDING,
                eventTimestampEpochMs = nowMs,
            )
        } else {
            null
        }

        val rpcRequest = AndroidStockReceiptRpcRequest(
            productId = draft.productId,
            quantity = draft.quantityReceived,
            clientReceiptId = draft.clientReceiptId,
            idempotencyKey = draft.idempotencyKey,
            unitCost = draft.unitCost,
            supplier = draft.supplier,
            invoiceRef = draft.invoiceRef,
            invoicePhotoUrl = draft.invoicePhotoPath,
            actorId = actorId,
            deviceId = deviceId,
            receivedAtIso = receivedAtIso,
            updateBuyPrice = draft.updateBuyPrice,
        )

        val isOfflineProductDraft = draft.productId < 0L
        if (!isOfflineProductDraft && isSupabaseConfigured && connectivityChecker.isOnline()) {
            when (val outcome = stockGateway.submitStockReceipt(rpcRequest)) {
                is StockReceiptRpcOutcome.CommittedOnline -> {
                    val updatedServerQty = outcome.serverQty
                    val existingProj = productDao.getStockProjection(draft.productId)
                    if (existingProj == null) {
                        productDao.upsertStockProjections(
                            listOf(
                                LocalStockProjectionEntity(
                                    productId = draft.productId,
                                    serverQty = updatedServerQty,
                                    serverUpdatedAtEpochMs = nowMs,
                                    pendingDeltaQty = 0,
                                    provisionalAvailableQty = updatedServerQty,
                                )
                            )
                        )
                    } else {
                        productDao.applyServerStockUpdate(
                            productId = draft.productId,
                            serverQty = updatedServerQty,
                            updatedAtEpochMs = nowMs,
                        )
                    }
                    productDao.updateProductServerStock(
                        productId = draft.productId,
                        serverQty = updatedServerQty,
                        updatedAtEpochMs = nowMs,
                    )

                    if (draft.updateBuyPrice && draft.unitCost != null && draft.unitCost > 0.0) {
                        productDao.updateProductBuyPrice(
                            productId = draft.productId,
                            buyPrice = draft.unitCost,
                            updatedAtEpochMs = nowMs,
                        )
                    }

                    if (financialEvent != null) {
                        financialAndDraftDao.upsertFinancialEvent(
                            financialEvent.copy(syncStatus = SyncState.SERVER_CONFIRMED)
                        )
                    }

                    auditDao.appendAudit(
                        LocalAuditEventEntity(
                            auditId = "audit-${UUID.randomUUID()}",
                            actorId = actorId,
                            deviceId = deviceId,
                            operation = "STOCK_RECEIVED_ONLINE",
                            entityType = "PRODUCT",
                            entityId = draft.productId.toString(),
                            beforeJson = JSONObject().apply {
                                put("server_stock_qty", existingProduct.serverStockQty)
                            }.toString(),
                            afterJson = JSONObject().apply {
                                put("client_receipt_id", draft.clientReceiptId)
                                put("qty_received", draft.quantityReceived)
                                put("server_qty", updatedServerQty)
                                put("already_committed", outcome.alreadyCommitted)
                            }.toString(),
                            reason = "Received ${draft.quantityReceived} units from ${draft.supplier ?: "Supplier"}",
                            syncStatus = SyncState.SERVER_CONFIRMED,
                            occurredAtEpochMs = nowMs,
                        )
                    )

                    return@withContext StockReceivingResult.SuccessOnline(
                        clientReceiptId = draft.clientReceiptId,
                        updatedServerQty = updatedServerQty,
                        quantityReceived = draft.quantityReceived,
                        alreadyCommitted = outcome.alreadyCommitted,
                    )
                }

                is StockReceiptRpcOutcome.Rejected -> {
                    return@withContext StockReceivingResult.Rejected(outcome.message)
                }

                is StockReceiptRpcOutcome.RpcMissing -> {
                    return@withContext commitOfflineFallback(
                        draft = draft,
                        rpcRequest = rpcRequest,
                        financialEvent = financialEvent,
                        actorId = actorId,
                        deviceId = deviceId,
                        nowMs = nowMs,
                        reason = "Saved locally offline — deploy Supabase receive_android_stock SQL migration to sync to cloud.",
                    )
                }

                is StockReceiptRpcOutcome.AuthRequired -> {
                    return@withContext commitOfflineFallback(
                        draft = draft,
                        rpcRequest = rpcRequest,
                        financialEvent = financialEvent,
                        actorId = actorId,
                        deviceId = deviceId,
                        nowMs = nowMs,
                        reason = "Sign-in required (${outcome.message}) — saved locally in Room queue.",
                    )
                }

                is StockReceiptRpcOutcome.NetworkOrTransientError -> {
                    return@withContext commitOfflineFallback(
                        draft = draft,
                        rpcRequest = rpcRequest,
                        financialEvent = financialEvent,
                        actorId = actorId,
                        deviceId = deviceId,
                        nowMs = nowMs,
                        reason = "Network unavailable (${outcome.message}) — saved locally offline and queued for sync.",
                    )
                }
            }
        }

        val offlineReason = if (isOfflineProductDraft) {
            "Product is an offline draft — receiving queued to sync after product registration."
        } else if (!isSupabaseConfigured) {
            "Supabase not configured — saved locally in Room queue."
        } else {
            "Device is offline — stock received locally with provisional update and queued for sync."
        }

        return@withContext commitOfflineFallback(
            draft = draft,
            rpcRequest = rpcRequest,
            financialEvent = financialEvent,
            actorId = actorId,
            deviceId = deviceId,
            nowMs = nowMs,
            reason = offlineReason,
        )
    }

    private suspend fun commitOfflineFallback(
        draft: StockReceiptDraft,
        rpcRequest: AndroidStockReceiptRpcRequest,
        financialEvent: PendingFinancialEventEntity?,
        actorId: String,
        deviceId: String,
        nowMs: Long,
        reason: String,
    ): StockReceivingResult.SavedOffline {
        val syncPayload = JSONObject().apply {
            put("client_receipt_id", rpcRequest.clientReceiptId)
            put("idempotency_key", rpcRequest.idempotencyKey)
            put("product_id", rpcRequest.productId)
            put("qty", rpcRequest.quantity)
            put("unit_cost", rpcRequest.unitCost ?: JSONObject.NULL)
            put("supplier", rpcRequest.supplier ?: JSONObject.NULL)
            put("invoice_ref", rpcRequest.invoiceRef ?: JSONObject.NULL)
            put("invoice_photo_url", rpcRequest.invoicePhotoUrl ?: JSONObject.NULL)
            put("actor_id", rpcRequest.actorId)
            put("device_id", rpcRequest.deviceId)
            put("received_at", rpcRequest.receivedAtIso)
            put("update_buy_price", rpcRequest.updateBuyPrice)
        }.toString()

        val parentDraftEventId = if (draft.productId < 0L) {
            syncQueueDao.getEventsByType(SyncEventType.PRODUCT_DRAFT)
                .firstOrNull { evt ->
                    SyncQueuePolicy.parseProductDraftPayload(evt)?.localTempProductId == draft.productId
                }?.eventId
        } else {
            null
        }

        val syncEvent = SyncQueueEventEntity(
            eventId = "sync-stock-${draft.clientReceiptId}",
            eventType = SyncEventType.STOCK_MOVEMENT,
            entityId = draft.clientReceiptId,
            dependencyEventId = parentDraftEventId,
            payloadJson = syncPayload,
            actorId = actorId,
            deviceId = deviceId,
            status = SyncQueueStatus.QUEUED,
            retryCount = 0,
            nextRetryAtEpochMs = nowMs,
            createdAtEpochMs = nowMs,
        )

        val existingProj = productDao.getStockProjection(draft.productId)
        if (existingProj == null) {
            val product = productDao.getProductById(draft.productId)
            val baseQty = product?.serverStockQty ?: 0
            productDao.upsertStockProjections(
                listOf(
                    LocalStockProjectionEntity(
                        productId = draft.productId,
                        serverQty = baseQty,
                        serverUpdatedAtEpochMs = nowMs,
                        pendingDeltaQty = draft.quantityReceived,
                        provisionalAvailableQty = baseQty + draft.quantityReceived,
                    )
                )
            )
        } else {
            productDao.applyProvisionalStockDelta(draft.productId, draft.quantityReceived)
        }

        if (draft.updateBuyPrice && draft.unitCost != null && draft.unitCost > 0.0) {
            productDao.updateProductBuyPrice(
                productId = draft.productId,
                buyPrice = draft.unitCost,
                updatedAtEpochMs = nowMs,
            )
        }

        syncQueueDao.upsertEvent(syncEvent)

        if (financialEvent != null) {
            financialAndDraftDao.upsertFinancialEvent(financialEvent)
        }

        auditDao.appendAudit(
            LocalAuditEventEntity(
                auditId = "audit-${UUID.randomUUID()}",
                actorId = actorId,
                deviceId = deviceId,
                operation = "STOCK_RECEIVED_OFFLINE",
                entityType = "PRODUCT",
                entityId = draft.productId.toString(),
                beforeJson = null,
                afterJson = syncPayload,
                reason = "Offline receipt of ${draft.quantityReceived} units from ${draft.supplier ?: "Supplier"}",
                syncStatus = SyncState.LOCAL_PENDING,
                occurredAtEpochMs = nowMs,
            )
        )

        onOfflineEventQueued()

        val updatedProj = productDao.getStockProjection(draft.productId)
        val provisional = updatedProj?.provisionalAvailableQty ?: draft.quantityReceived

        return StockReceivingResult.SavedOffline(
            clientReceiptId = draft.clientReceiptId,
            queuedEventId = syncEvent.eventId,
            provisionalRemainingQty = provisional,
            quantityReceived = draft.quantityReceived,
            reason = reason,
        )
    }
}
