package com.emofah.agencies.data.sync

import com.emofah.agencies.data.SupabaseConfig
import com.emofah.agencies.data.catalog.ProductMetadataExtractor
import com.emofah.agencies.data.catalog.ProductRegistrationGateway
import com.emofah.agencies.data.catalog.ProductRegistrationRpcOutcome
import com.emofah.agencies.data.catalog.SupabaseProductDto
import com.emofah.agencies.data.catalog.SupabaseProductRegistrationClient
import com.emofah.agencies.data.local.EmofahDatabase
import com.emofah.agencies.data.local.entities.LocalAuditEventEntity
import com.emofah.agencies.data.local.entities.ProductEntity
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.financial.ClassificationRpcOutcome
import com.emofah.agencies.financial.ClassificationState
import com.emofah.agencies.financial.FinancialEventGateway
import com.emofah.agencies.financial.FinancialEventRpcOutcome
import com.emofah.agencies.financial.SupabaseFinancialEventClient
import com.emofah.agencies.financial.SupabaseTransactionClassificationClient
import com.emofah.agencies.orders.OrderConversionGateway
import com.emofah.agencies.orders.OrderConversionRpcOutcome
import com.emofah.agencies.orders.OrderStatusGateway
import com.emofah.agencies.orders.OrderStatusRpcOutcome
import com.emofah.agencies.orders.SupabaseOrderConversionClient
import com.emofah.agencies.orders.SupabaseOrderStatusClient
import com.emofah.agencies.purchases.PurchaseItemCategory
import com.emofah.agencies.purchases.PurchaseRpcOutcome
import com.emofah.agencies.purchases.PurchaseSubmissionGateway
import com.emofah.agencies.purchases.SupabasePurchaseClient
import com.emofah.agencies.recognition.RecognitionCorrectionGateway
import com.emofah.agencies.recognition.RecognitionCorrectionRpcOutcome
import com.emofah.agencies.recognition.SupabaseRecognitionCorrectionClient
import com.emofah.agencies.services.ServiceOperationGateway
import com.emofah.agencies.services.ServiceRpcOutcome
import com.emofah.agencies.services.SupabaseServiceOperationClient
import com.emofah.agencies.sales.ConnectivityChecker
import com.emofah.agencies.sales.SaleReversalRpcGateway
import com.emofah.agencies.sales.SaleReversalRpcOutcome
import com.emofah.agencies.sales.SaleRpcGateway
import com.emofah.agencies.sales.SaleRpcOutcome
import com.emofah.agencies.sales.SupabaseSaleReversalClient
import com.emofah.agencies.sales.SupabaseSaleRpcClient
import com.emofah.agencies.stock.StockAdjustmentGateway
import com.emofah.agencies.stock.StockAdjustmentRpcOutcome
import com.emofah.agencies.stock.StockCountGateway
import com.emofah.agencies.stock.StockCountRpcOutcome
import com.emofah.agencies.stock.StockReceiptRpcOutcome
import com.emofah.agencies.stock.StockReceivingGateway
import com.emofah.agencies.stock.SupabaseStockAdjustmentClient
import com.emofah.agencies.stock.SupabaseStockCountClient
import com.emofah.agencies.stock.SupabaseStockReceivingClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

sealed class QueueItemExecutionResult {
    data object Synced : QueueItemExecutionResult()
    data class RetryableFailure(val reason: String) : QueueItemExecutionResult()
    data class Conflict(val reason: String, val serverAvailableQty: Int? = null) : QueueItemExecutionResult()
    data class AuthBlocked(val reason: String) : QueueItemExecutionResult()
    data object DeferredHandler : QueueItemExecutionResult()
}

/**
 * Stage 3.3 — Dependency-Ordered Synchronisation Queue Processor.
 *
 * Guarantees:
 * - Single-flight execution via Mutex so concurrent triggers never double-process an item.
 * - Crash recovery for stale IN_FLIGHT records.
 * - Strict parent-before-child dependency enforcement (`dependency_event_id`).
 * - Bounded exponential backoff for transient network/server failures.
 * - Non-destructive conflict preservation (`CONFLICT` + audit log) and auth pause (`AUTH_REQUIRED`).
 */
class SyncQueueProcessor(
    private val database: EmofahDatabase,
    private val connectivityChecker: ConnectivityChecker,
    private val saleRpcGateway: SaleRpcGateway = SupabaseSaleRpcClient(),
    private val saleReversalGateway: SaleReversalRpcGateway = SupabaseSaleReversalClient(),
    private val stockReceivingGateway: StockReceivingGateway = SupabaseStockReceivingClient(),
    private val stockAdjustmentGateway: StockAdjustmentGateway = SupabaseStockAdjustmentClient(),
    private val stockCountGateway: StockCountGateway = SupabaseStockCountClient(),
    private val financialEventGateway: FinancialEventGateway = SupabaseFinancialEventClient(),
    private val purchaseGateway: PurchaseSubmissionGateway = SupabasePurchaseClient(),
    private val serviceOperationGateway: ServiceOperationGateway = SupabaseServiceOperationClient(),
    private val orderStatusGateway: OrderStatusGateway = SupabaseOrderStatusClient(),
    private val orderConversionGateway: OrderConversionGateway = SupabaseOrderConversionClient(),
    private val productRegistrationGateway: ProductRegistrationGateway = SupabaseProductRegistrationClient(),
    private val recognitionCorrectionGateway: RecognitionCorrectionGateway = SupabaseRecognitionCorrectionClient(),
    private val isSupabaseConfigured: Boolean = SupabaseConfig.isConfigured,
    private val clockEpochMs: () -> Long = { System.currentTimeMillis() },
    private val onOrderUpdateReconciled: (orderId: String, confirmedRawStatus: String?) -> Unit = { _, _ -> },
    private val onOrderConversionReconciled: (orderId: String, synced: Boolean) -> Unit = { _, _ -> },
    private val classificationClient: SupabaseTransactionClassificationClient = SupabaseTransactionClassificationClient(),
    private val onTransactionClassified: (eventId: String) -> Unit = {},
) {
    private val mutex = Mutex()
    private val syncQueueDao = database.syncQueueDao()
    private val saleDao = database.saleDao()
    private val productDao = database.productDao()
    private val financialAndDraftDao = database.financialAndDraftDao()
    private val auditDao = database.auditDao()

    suspend fun processPendingQueue(maxEventsPerPass: Int = 25): SyncPassSummary = mutex.withLock {
        if (!isSupabaseConfigured || !connectivityChecker.isOnline()) {
            return@withLock SyncPassSummary(
                attemptedCount = 0,
                syncedCount = 0,
                retryableCount = 0,
                conflictCount = 0,
                authBlockedCount = 0,
                deferredCount = 0,
                skippedOffline = true,
            )
        }

        val recoveryNow = clockEpochMs()
        syncQueueDao.resetStaleInFlightEvents(
            staleBeforeEpochMs = recoveryNow - SyncQueuePolicy.STALE_IN_FLIGHT_MS,
            nextRetryEpochMs = recoveryNow,
        )
        repairQueueIntegrityBeforePass(recoveryNow)

        var attempted = 0
        var synced = 0
        var retryable = 0
        var conflicts = 0
        var authBlocked = 0
        var deferred = 0
        val processedThisPass = mutableSetOf<String>()

        while (attempted < maxEventsPerPass) {
            val now = clockEpochMs()
            val candidates = syncQueueDao.getNextRunnableEvents(nowEpochMs = now, limit = 20)
                .filter { it.eventId !in processedThisPass }

            if (candidates.isEmpty()) break

            var progressedThisRound = false
            for (event in candidates) {
                if (attempted >= maxEventsPerPass) break
                processedThisPass.add(event.eventId)

                when (val outcome = executeSingleEvent(event, now)) {
                    is QueueItemExecutionResult.Synced -> {
                        attempted++
                        synced++
                        progressedThisRound = true
                    }

                    is QueueItemExecutionResult.RetryableFailure -> {
                        attempted++
                        retryable++
                        progressedThisRound = true
                        // Stop pass early if transport appears down so remaining items keep their queue position
                        if (!connectivityChecker.isOnline()) {
                            return@withLock SyncPassSummary(
                                attemptedCount = attempted,
                                syncedCount = synced,
                                retryableCount = retryable,
                                conflictCount = conflicts,
                                authBlockedCount = authBlocked,
                                deferredCount = deferred,
                                skippedOffline = true,
                            )
                        }
                    }

                    is QueueItemExecutionResult.Conflict -> {
                        attempted++
                        conflicts++
                        progressedThisRound = true
                    }

                    is QueueItemExecutionResult.AuthBlocked -> {
                        attempted++
                        authBlocked++
                        return@withLock SyncPassSummary(
                            attemptedCount = attempted,
                            syncedCount = synced,
                            retryableCount = retryable,
                            conflictCount = conflicts,
                            authBlockedCount = authBlocked,
                            deferredCount = deferred,
                        )
                    }

                    is QueueItemExecutionResult.DeferredHandler -> {
                        deferred++
                    }
                }
            }

            if (!progressedThisRound) break
        }

        SyncPassSummary(
            attemptedCount = attempted,
            syncedCount = synced,
            retryableCount = retryable,
            conflictCount = conflicts,
            authBlockedCount = authBlocked,
            deferredCount = deferred,
        )
    }

    private suspend fun executeSingleEvent(
        event: SyncQueueEventEntity,
        attemptEpochMs: Long,
    ): QueueItemExecutionResult {
        return when (event.eventType) {
            SyncEventType.PRODUCT_DRAFT -> processProductDraftEvent(event, attemptEpochMs)
            SyncEventType.SALE -> processSaleEvent(event, attemptEpochMs)
            SyncEventType.SALE_REVERSAL -> processSaleReversalEvent(event, attemptEpochMs)
            SyncEventType.STOCK_MOVEMENT -> processStockMovementEvent(event, attemptEpochMs)
            SyncEventType.STOCK_ADJUSTMENT -> processStockAdjustmentEvent(event, attemptEpochMs)
            SyncEventType.STOCK_COUNT -> processStockCountEvent(event, attemptEpochMs)
            SyncEventType.FINANCIAL_EVENT -> processFinancialEvent(event, attemptEpochMs)
            SyncEventType.FINANCIAL_CLASSIFICATION -> processFinancialClassificationEvent(event, attemptEpochMs)
            SyncEventType.PURCHASE_RECEIPT -> processPurchaseReceiptEvent(event, attemptEpochMs)
            SyncEventType.SERVICE_OPERATION -> processServiceOperationEvent(event, attemptEpochMs)
            SyncEventType.ORDER_UPDATE -> processOrderUpdateEvent(event, attemptEpochMs)
            SyncEventType.ORDER_TO_SALE -> processOrderToSaleEvent(event, attemptEpochMs)
            SyncEventType.RECOGNITION_CORRECTION -> processRecognitionCorrectionEvent(event, attemptEpochMs)
            // Future modules (Stage 7.x) register handlers without dropping queued rows
            SyncEventType.PRODUCT_IMAGE,
            -> QueueItemExecutionResult.DeferredHandler

            else -> {
                val reason = "Unsupported sync event type: ${event.eventType}"
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.CONFLICT,
                    retryCount = event.retryCount + 1,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = attemptEpochMs,
                    failureReason = reason,
                    serverAckId = null,
                )
                QueueItemExecutionResult.Conflict(reason)
            }
        }
    }

    private suspend fun processSaleEvent(
        event: SyncQueueEventEntity,
        attemptEpochMs: Long,
    ): QueueItemExecutionResult {
        val rpcRequest = SyncQueuePolicy.parseSaleRpcRequest(event)
        if (rpcRequest == null) {
            val msg = "Malformed queued sale payload for ${event.entityId}."
            syncQueueDao.markSaleConflictAtomically(
                eventId = event.eventId,
                clientSaleId = event.entityId,
                productId = -1L,
                conflictMessage = msg,
                serverAvailableQty = null,
                attemptEpochMs = attemptEpochMs,
                retryCount = event.retryCount + 1,
                auditEvent = LocalAuditEventEntity(
                    auditId = "audit-${UUID.randomUUID()}",
                    actorId = event.actorId,
                    deviceId = event.deviceId,
                    operation = "SYNC_SALE_CONFLICT",
                    entityType = "SALE",
                    entityId = event.entityId,
                    beforeJson = event.payloadJson,
                    afterJson = null,
                    reason = msg,
                    syncStatus = SyncState.CONFLICT,
                    occurredAtEpochMs = attemptEpochMs,
                ),
                saleDao = saleDao,
                productDao = productDao,
                auditDao = auditDao,
            )
            return QueueItemExecutionResult.Conflict(msg)
        }

        syncQueueDao.updateEventProgress(
            eventId = event.eventId,
            status = SyncQueueStatus.IN_FLIGHT,
            retryCount = event.retryCount,
            attemptEpochMs = attemptEpochMs,
            nextRetryEpochMs = attemptEpochMs,
            failureReason = null,
            serverAckId = null,
        )

        val nextRetryCount = event.retryCount + 1
        return when (val outcome = saleRpcGateway.submitSale(rpcRequest)) {
            is SaleRpcOutcome.CommittedOnline -> {
                val syncedAt = clockEpochMs()
                val audit = LocalAuditEventEntity(
                    auditId = "audit-${UUID.randomUUID()}",
                    actorId = event.actorId,
                    deviceId = event.deviceId,
                    operation = "SYNC_SALE_CONFIRMED",
                    entityType = "SALE",
                    entityId = rpcRequest.clientSaleId,
                    beforeJson = null,
                    afterJson = JSONObject().apply {
                        put("server_sale_id", outcome.serverSaleId)
                        put("remaining_qty", outcome.remainingQty)
                        put("already_committed", outcome.alreadyCommitted)
                    }.toString(),
                    reason = "Synced queued offline sale via record_android_sale",
                    syncStatus = SyncState.SERVER_CONFIRMED,
                    occurredAtEpochMs = syncedAt,
                )
                syncQueueDao.reconcileSyncedSaleAtomically(
                    eventId = event.eventId,
                    clientSaleId = rpcRequest.clientSaleId,
                    serverSaleId = outcome.serverSaleId,
                    productId = rpcRequest.productId,
                    qtySold = rpcRequest.quantity,
                    remainingServerQty = outcome.remainingQty,
                    syncedAtEpochMs = syncedAt,
                    retryCount = event.retryCount,
                    auditEvent = audit,
                    saleDao = saleDao,
                    productDao = productDao,
                    auditDao = auditDao,
                )
                QueueItemExecutionResult.Synced
            }

            is SaleRpcOutcome.Rejected -> {
                val audit = LocalAuditEventEntity(
                    auditId = "audit-${UUID.randomUUID()}",
                    actorId = event.actorId,
                    deviceId = event.deviceId,
                    operation = "SYNC_SALE_CONFLICT",
                    entityType = "SALE",
                    entityId = rpcRequest.clientSaleId,
                    beforeJson = event.payloadJson,
                    afterJson = JSONObject().apply {
                        put("code", outcome.code)
                        put("message", outcome.message)
                        outcome.serverAvailableQty?.let { put("server_available_qty", it) }
                    }.toString(),
                    reason = outcome.message,
                    syncStatus = SyncState.CONFLICT,
                    occurredAtEpochMs = attemptEpochMs,
                )
                syncQueueDao.markSaleConflictAtomically(
                    eventId = event.eventId,
                    clientSaleId = rpcRequest.clientSaleId,
                    productId = rpcRequest.productId,
                    conflictMessage = outcome.message,
                    serverAvailableQty = outcome.serverAvailableQty,
                    attemptEpochMs = attemptEpochMs,
                    retryCount = nextRetryCount,
                    auditEvent = audit,
                    saleDao = saleDao,
                    productDao = productDao,
                    auditDao = auditDao,
                )
                QueueItemExecutionResult.Conflict(outcome.message, outcome.serverAvailableQty)
            }

            is SaleRpcOutcome.AuthRequired -> {
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.AUTH_REQUIRED,
                    retryCount = event.retryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = attemptEpochMs,
                    failureReason = outcome.message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.AuthBlocked(outcome.message)
            }

            is SaleRpcOutcome.RpcMissing -> {
                val nextAt = SyncQueuePolicy.computeNextRetryEpochMs(attemptEpochMs, nextRetryCount)
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.FAILED_RETRYABLE,
                    retryCount = nextRetryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = nextAt,
                    failureReason = outcome.message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.RetryableFailure(outcome.message)
            }

            is SaleRpcOutcome.NetworkOrTransientError -> {
                val nextAt = SyncQueuePolicy.computeNextRetryEpochMs(attemptEpochMs, nextRetryCount)
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.FAILED_RETRYABLE,
                    retryCount = nextRetryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = nextAt,
                    failureReason = outcome.message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.RetryableFailure(outcome.message)
            }
        }
    }

    private suspend fun processProductDraftEvent(
        event: SyncQueueEventEntity,
        attemptEpochMs: Long,
    ): QueueItemExecutionResult {
        val parsed = SyncQueuePolicy.parseProductDraftPayload(event)
        if (parsed == null) {
            val msg = "Malformed queued product draft payload for ${event.entityId}."
            syncQueueDao.updateEventProgress(
                eventId = event.eventId,
                status = SyncQueueStatus.CONFLICT,
                retryCount = event.retryCount + 1,
                attemptEpochMs = attemptEpochMs,
                nextRetryEpochMs = attemptEpochMs,
                failureReason = msg,
                serverAckId = null,
            )
            return QueueItemExecutionResult.Conflict(msg)
        }

        syncQueueDao.updateEventProgress(
            eventId = event.eventId,
            status = SyncQueueStatus.IN_FLIGHT,
            retryCount = event.retryCount,
            attemptEpochMs = attemptEpochMs,
            nextRetryEpochMs = attemptEpochMs,
            failureReason = null,
            serverAckId = null,
        )

        val nextRetryCount = event.retryCount + 1
        val req = parsed.rpcRequest
        return when (val outcome = productRegistrationGateway.registerProduct(req)) {
            is ProductRegistrationRpcOutcome.CommittedOnline -> {
                val syncedAt = clockEpochMs()
                val canonicalName = outcome.canonicalName.ifBlank { req.name }
                val serverQty = if (outcome.matchedExistingDuplicate) outcome.qty else req.initialQty
                val serverSell = if (outcome.matchedExistingDuplicate && outcome.sellPrice > 0.0) {
                    outcome.sellPrice
                } else {
                    req.sellPrice
                }

                val identity = ProductMetadataExtractor.toProductIdentity(
                    SupabaseProductDto(
                        id = outcome.serverProductId,
                        name = canonicalName,
                        qty = serverQty,
                        sellPrice = serverSell,
                        buyPrice = req.buyPrice,
                        imageUrl = outcome.imageUrl,
                        category = req.category,
                        createdAt = null,
                        description = req.description,
                        discountPrice = 0.0,
                        isFeatured = false,
                    )
                )
                val canonicalProduct = ProductEntity(
                    id = identity.id,
                    name = identity.name,
                    baseName = identity.baseName,
                    variantLabel = identity.variantLabel,
                    brandHint = identity.brandHint,
                    category = identity.category,
                    sellPrice = identity.sellPrice,
                    discountPrice = identity.discountPrice,
                    buyPrice = identity.buyPrice,
                    serverStockQty = serverQty,
                    imageUrl = identity.imageUrl,
                    description = identity.description,
                    isFeatured = false,
                    createdAt = null,
                    isArchived = false,
                    confirmedAliasesCsv = parsed.ocrText
                        ?.takeIf { it.length in 3..64 && !identity.name.equals(it, ignoreCase = true) }
                        .orEmpty(),
                    cachedAtEpochMs = syncedAt,
                )

                val dependentEvents = syncQueueDao.getDependentEvents(event.eventId)
                val relinkedPayloads = dependentEvents.map { child ->
                    val updatedPayload = when (child.eventType) {
                        SyncEventType.RECOGNITION_CORRECTION ->
                            SyncQueuePolicy.relinkRecognitionCorrectionPayloadProductId(
                                payloadJson = child.payloadJson,
                                oldTempProductId = parsed.localTempProductId,
                                newProductId = identity.id,
                            )
                        SyncEventType.STOCK_MOVEMENT ->
                            SyncQueuePolicy.relinkStockMovementPayloadProductId(
                                payloadJson = child.payloadJson,
                                newProductId = identity.id,
                            )
                        SyncEventType.STOCK_ADJUSTMENT ->
                            SyncQueuePolicy.relinkStockAdjustmentPayloadProductId(
                                payloadJson = child.payloadJson,
                                newProductId = identity.id,
                            )
                        else ->
                            SyncQueuePolicy.relinkSalePayloadProductId(
                                payloadJson = child.payloadJson,
                                newProductId = identity.id,
                            )
                    }
                    child.eventId to updatedPayload
                }

                val audit = LocalAuditEventEntity(
                    auditId = "audit-${UUID.randomUUID()}",
                    actorId = event.actorId,
                    deviceId = event.deviceId,
                    operation = "SYNC_PRODUCT_DRAFT_CONFIRMED",
                    entityType = "PRODUCT",
                    entityId = identity.id.toString(),
                    beforeJson = event.payloadJson,
                    afterJson = JSONObject().apply {
                        put("draft_id", req.draftId)
                        put("server_product_id", identity.id)
                        put("matched_existing_duplicate", outcome.matchedExistingDuplicate)
                        put("relinked_child_events", relinkedPayloads.size)
                    }.toString(),
                    reason = "Synced offline product draft via register_android_product",
                    syncStatus = SyncState.SERVER_CONFIRMED,
                    occurredAtEpochMs = syncedAt,
                )

                syncQueueDao.reconcileSyncedProductDraftAtomically(
                    eventId = event.eventId,
                    draftId = req.draftId,
                    localTempProductId = parsed.localTempProductId,
                    canonicalProduct = canonicalProduct,
                    serverQty = serverQty,
                    syncedAtEpochMs = syncedAt,
                    retryCount = event.retryCount,
                    relinkedSalePayloads = relinkedPayloads,
                    auditEvent = audit,
                    productDao = productDao,
                    saleDao = saleDao,
                    financialAndDraftDao = financialAndDraftDao,
                    auditDao = auditDao,
                )
                QueueItemExecutionResult.Synced
            }

            is ProductRegistrationRpcOutcome.Rejected -> {
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.CONFLICT,
                    retryCount = nextRetryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = attemptEpochMs,
                    failureReason = outcome.message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.Conflict(outcome.message)
            }

            is ProductRegistrationRpcOutcome.AuthRequired -> {
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.AUTH_REQUIRED,
                    retryCount = event.retryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = attemptEpochMs,
                    failureReason = outcome.message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.AuthBlocked(outcome.message)
            }

            is ProductRegistrationRpcOutcome.RpcMissing -> {
                val nextAt = SyncQueuePolicy.computeNextRetryEpochMs(attemptEpochMs, nextRetryCount)
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.FAILED_RETRYABLE,
                    retryCount = nextRetryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = nextAt,
                    failureReason = outcome.message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.RetryableFailure(outcome.message)
            }

            is ProductRegistrationRpcOutcome.NetworkOrTransientError -> {
                val nextAt = SyncQueuePolicy.computeNextRetryEpochMs(attemptEpochMs, nextRetryCount)
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.FAILED_RETRYABLE,
                    retryCount = nextRetryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = nextAt,
                    failureReason = outcome.message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.RetryableFailure(outcome.message)
            }
        }
    }

    private suspend fun processRecognitionCorrectionEvent(
        event: SyncQueueEventEntity,
        attemptEpochMs: Long,
    ): QueueItemExecutionResult {
        val rpcRequest = SyncQueuePolicy.parseRecognitionCorrectionPayload(event)
        if (rpcRequest == null) {
            val msg = "Malformed recognition correction payload for ${event.entityId}."
            syncQueueDao.updateEventProgress(
                eventId = event.eventId,
                status = SyncQueueStatus.CONFLICT,
                retryCount = event.retryCount + 1,
                attemptEpochMs = attemptEpochMs,
                nextRetryEpochMs = attemptEpochMs,
                failureReason = msg,
                serverAckId = null,
            )
            return QueueItemExecutionResult.Conflict(msg)
        }

        syncQueueDao.updateEventProgress(
            eventId = event.eventId,
            status = SyncQueueStatus.IN_FLIGHT,
            retryCount = event.retryCount,
            attemptEpochMs = attemptEpochMs,
            nextRetryEpochMs = attemptEpochMs,
            failureReason = null,
            serverAckId = null,
        )

        val nextRetryCount = event.retryCount + 1
        return when (val outcome = recognitionCorrectionGateway.submitCorrection(rpcRequest)) {
            is RecognitionCorrectionRpcOutcome.CommittedOnline -> {
                val syncedAt = clockEpochMs()
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.SYNCED,
                    retryCount = event.retryCount,
                    attemptEpochMs = syncedAt,
                    nextRetryEpochMs = syncedAt,
                    failureReason = null,
                    serverAckId = outcome.serverCorrectionId.toString(),
                )
                runCatching {
                    auditDao.appendAudit(
                        LocalAuditEventEntity(
                            auditId = "audit-${UUID.randomUUID()}",
                            actorId = event.actorId,
                            deviceId = event.deviceId,
                            operation = "SYNC_RECOGNITION_CORRECTION_CONFIRMED",
                            entityType = "PRODUCT",
                            entityId = outcome.confirmedProductId.toString(),
                            beforeJson = null,
                            afterJson = JSONObject().apply {
                                put("correction_id", rpcRequest.correctionId)
                                put("server_correction_id", outcome.serverCorrectionId)
                                put("learned_alias", rpcRequest.learnedAlias ?: JSONObject.NULL)
                            }.toString(),
                            reason = "Synced confirmed recognition correction via record_android_recognition_correction",
                            syncStatus = SyncState.SERVER_CONFIRMED,
                            occurredAtEpochMs = syncedAt,
                        )
                    )
                }
                QueueItemExecutionResult.Synced
            }

            is RecognitionCorrectionRpcOutcome.Rejected -> {
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.CONFLICT,
                    retryCount = nextRetryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = attemptEpochMs,
                    failureReason = outcome.message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.Conflict(outcome.message)
            }

            is RecognitionCorrectionRpcOutcome.AuthRequired -> {
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.AUTH_REQUIRED,
                    retryCount = event.retryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = attemptEpochMs,
                    failureReason = outcome.message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.AuthBlocked(outcome.message)
            }

            is RecognitionCorrectionRpcOutcome.RpcMissing,
            is RecognitionCorrectionRpcOutcome.NetworkOrTransientError,
            -> {
                val message = when (outcome) {
                    is RecognitionCorrectionRpcOutcome.RpcMissing -> outcome.message
                    is RecognitionCorrectionRpcOutcome.NetworkOrTransientError -> outcome.message
                    else -> "Transient error syncing recognition correction."
                }
                val nextAt = SyncQueuePolicy.computeNextRetryEpochMs(attemptEpochMs, nextRetryCount)
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.FAILED_RETRYABLE,
                    retryCount = nextRetryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = nextAt,
                    failureReason = message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.RetryableFailure(message)
            }
        }
    }

    private suspend fun processSaleReversalEvent(
        event: SyncQueueEventEntity,
        attemptEpochMs: Long,
    ): QueueItemExecutionResult {
        val rpcRequest = SyncQueuePolicy.parseSaleReversalRpcRequest(event)
        if (rpcRequest == null) {
            val msg = "Malformed sale reversal payload for ${event.entityId}."
            syncQueueDao.updateEventProgress(
                eventId = event.eventId,
                status = SyncQueueStatus.CONFLICT,
                retryCount = event.retryCount + 1,
                attemptEpochMs = attemptEpochMs,
                nextRetryEpochMs = attemptEpochMs,
                failureReason = msg,
                serverAckId = null,
            )
            return QueueItemExecutionResult.Conflict(msg)
        }

        syncQueueDao.updateEventProgress(
            eventId = event.eventId,
            status = SyncQueueStatus.IN_FLIGHT,
            retryCount = event.retryCount,
            attemptEpochMs = attemptEpochMs,
            nextRetryEpochMs = attemptEpochMs,
            failureReason = null,
            serverAckId = null,
        )

        val nextRetryCount = event.retryCount + 1
        return when (val outcome = saleReversalGateway.submitReversal(rpcRequest)) {
            is SaleReversalRpcOutcome.CommittedOnline -> {
                val syncedAt = clockEpochMs()
                val audit = LocalAuditEventEntity(
                    auditId = "audit-${UUID.randomUUID()}",
                    actorId = event.actorId,
                    deviceId = event.deviceId,
                    operation = "SYNC_SALE_REVERSAL_CONFIRMED",
                    entityType = "SALE",
                    entityId = rpcRequest.clientSaleId,
                    beforeJson = null,
                    afterJson = JSONObject().apply {
                        put("client_sale_id", rpcRequest.clientSaleId)
                        put("restored_qty", rpcRequest.quantity)
                        put("remaining_qty", outcome.remainingQty)
                        put("already_reversed", outcome.alreadyReversed)
                    }.toString(),
                    reason = rpcRequest.reason,
                    syncStatus = SyncState.SERVER_CONFIRMED,
                    occurredAtEpochMs = syncedAt,
                )
                syncQueueDao.reconcileSyncedSaleReversalAtomically(
                    eventId = event.eventId,
                    clientSaleId = rpcRequest.clientSaleId,
                    productId = rpcRequest.productId,
                    restoredQty = rpcRequest.quantity,
                    remainingServerQty = outcome.remainingQty,
                    reason = rpcRequest.reason,
                    financialEventId = "fin-rev-${rpcRequest.clientSaleId}",
                    syncedAtEpochMs = syncedAt,
                    retryCount = event.retryCount,
                    auditEvent = audit,
                    saleDao = saleDao,
                    productDao = productDao,
                    financialAndDraftDao = financialAndDraftDao,
                    auditDao = auditDao,
                )
                QueueItemExecutionResult.Synced
            }

            is SaleReversalRpcOutcome.Rejected -> {
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.CONFLICT,
                    retryCount = nextRetryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = attemptEpochMs,
                    failureReason = outcome.message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.Conflict(outcome.message)
            }

            is SaleReversalRpcOutcome.AuthRequired -> {
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.AUTH_REQUIRED,
                    retryCount = event.retryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = attemptEpochMs,
                    failureReason = outcome.message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.AuthBlocked(outcome.message)
            }

            is SaleReversalRpcOutcome.RpcMissing,
            is SaleReversalRpcOutcome.NetworkOrTransientError,
            -> {
                val message = when (outcome) {
                    is SaleReversalRpcOutcome.RpcMissing -> outcome.message
                    is SaleReversalRpcOutcome.NetworkOrTransientError -> outcome.message
                    else -> "Transient error syncing sale reversal."
                }
                val nextAt = SyncQueuePolicy.computeNextRetryEpochMs(attemptEpochMs, nextRetryCount)
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.FAILED_RETRYABLE,
                    retryCount = nextRetryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = nextAt,
                    failureReason = message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.RetryableFailure(message)
            }
        }
    }

    private suspend fun processStockMovementEvent(
        event: SyncQueueEventEntity,
        attemptEpochMs: Long,
    ): QueueItemExecutionResult {
        val rpcRequest = SyncQueuePolicy.parseStockMovementRpcRequest(event)
        if (rpcRequest == null) {
            val msg = "Malformed stock movement payload for ${event.entityId}."
            syncQueueDao.updateEventProgress(
                eventId = event.eventId,
                status = SyncQueueStatus.CONFLICT,
                retryCount = event.retryCount + 1,
                attemptEpochMs = attemptEpochMs,
                nextRetryEpochMs = attemptEpochMs,
                failureReason = msg,
                serverAckId = null,
            )
            return QueueItemExecutionResult.Conflict(msg)
        }

        syncQueueDao.updateEventProgress(
            eventId = event.eventId,
            status = SyncQueueStatus.IN_FLIGHT,
            retryCount = event.retryCount,
            attemptEpochMs = attemptEpochMs,
            nextRetryEpochMs = attemptEpochMs,
            failureReason = null,
            serverAckId = null,
        )

        val nextRetryCount = event.retryCount + 1
        return when (val outcome = stockReceivingGateway.submitStockReceipt(rpcRequest)) {
            is StockReceiptRpcOutcome.CommittedOnline -> {
                val syncedAt = clockEpochMs()
                val audit = LocalAuditEventEntity(
                    auditId = "audit-${UUID.randomUUID()}",
                    actorId = event.actorId,
                    deviceId = event.deviceId,
                    operation = "SYNC_STOCK_MOVEMENT_CONFIRMED",
                    entityType = "PRODUCT",
                    entityId = rpcRequest.productId.toString(),
                    beforeJson = null,
                    afterJson = JSONObject().apply {
                        put("client_receipt_id", rpcRequest.clientReceiptId)
                        put("qty_received", rpcRequest.quantity)
                        put("server_qty", outcome.serverQty)
                        put("already_committed", outcome.alreadyCommitted)
                    }.toString(),
                    reason = "Synced stock receiving via receive_android_stock",
                    syncStatus = SyncState.SERVER_CONFIRMED,
                    occurredAtEpochMs = syncedAt,
                )
                syncQueueDao.reconcileSyncedStockReceiptAtomically(
                    eventId = event.eventId,
                    productId = rpcRequest.productId,
                    qtyReceived = rpcRequest.quantity,
                    newServerQty = outcome.serverQty,
                    financialEventId = "fin-stock-rcpt-${rpcRequest.clientReceiptId}",
                    syncedAtEpochMs = syncedAt,
                    retryCount = event.retryCount,
                    auditEvent = audit,
                    productDao = productDao,
                    financialAndDraftDao = financialAndDraftDao,
                    auditDao = auditDao,
                )
                QueueItemExecutionResult.Synced
            }

            is StockReceiptRpcOutcome.Rejected -> {
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.CONFLICT,
                    retryCount = nextRetryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = attemptEpochMs,
                    failureReason = outcome.message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.Conflict(outcome.message)
            }

            is StockReceiptRpcOutcome.AuthRequired -> {
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.AUTH_REQUIRED,
                    retryCount = event.retryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = attemptEpochMs,
                    failureReason = outcome.message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.AuthBlocked(outcome.message)
            }

            is StockReceiptRpcOutcome.RpcMissing,
            is StockReceiptRpcOutcome.NetworkOrTransientError,
            -> {
                val message = when (outcome) {
                    is StockReceiptRpcOutcome.RpcMissing -> outcome.message
                    is StockReceiptRpcOutcome.NetworkOrTransientError -> outcome.message
                    else -> "Transient error syncing stock movement."
                }
                val nextAt = SyncQueuePolicy.computeNextRetryEpochMs(attemptEpochMs, nextRetryCount)
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.FAILED_RETRYABLE,
                    retryCount = nextRetryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = nextAt,
                    failureReason = message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.RetryableFailure(message)
            }
        }
    }

    private suspend fun processStockAdjustmentEvent(
        event: SyncQueueEventEntity,
        attemptEpochMs: Long,
    ): QueueItemExecutionResult {
        val rpcRequest = SyncQueuePolicy.parseStockAdjustmentRpcRequest(event)
        if (rpcRequest == null) {
            val msg = "Malformed queued stock adjustment payload for ${event.entityId}."
            syncQueueDao.updateEventProgress(
                eventId = event.eventId,
                status = SyncQueueStatus.CONFLICT,
                retryCount = event.retryCount + 1,
                attemptEpochMs = attemptEpochMs,
                nextRetryEpochMs = attemptEpochMs,
                failureReason = msg,
                serverAckId = null,
            )
            return QueueItemExecutionResult.Conflict(msg)
        }

        syncQueueDao.updateEventProgress(
            eventId = event.eventId,
            status = SyncQueueStatus.IN_FLIGHT,
            retryCount = event.retryCount,
            attemptEpochMs = attemptEpochMs,
            nextRetryEpochMs = attemptEpochMs,
            failureReason = null,
            serverAckId = null,
        )

        val nextRetryCount = event.retryCount + 1
        return when (val outcome = stockAdjustmentGateway.submitStockAdjustment(rpcRequest)) {
            is StockAdjustmentRpcOutcome.CommittedOnline -> {
                val syncedAt = clockEpochMs()
                val audit = LocalAuditEventEntity(
                    auditId = "audit-${UUID.randomUUID()}",
                    actorId = event.actorId,
                    deviceId = event.deviceId,
                    operation = "SYNC_STOCK_ADJUSTMENT_CONFIRMED",
                    entityType = "PRODUCT",
                    entityId = rpcRequest.productId.toString(),
                    beforeJson = null,
                    afterJson = JSONObject().apply {
                        put("client_adjustment_id", rpcRequest.clientAdjustmentId)
                        put("delta_qty", rpcRequest.deltaQty)
                        put("reason_code", rpcRequest.reasonCode)
                        put("server_qty", outcome.serverQty)
                        put("previous_qty", outcome.previousQty)
                        put("is_clamped", outcome.isClamped)
                        put("already_committed", outcome.alreadyCommitted)
                    }.toString(),
                    reason = "Synced stock adjustment via adjust_android_stock",
                    syncStatus = SyncState.SERVER_CONFIRMED,
                    occurredAtEpochMs = syncedAt,
                )
                syncQueueDao.reconcileSyncedStockAdjustmentAtomically(
                    eventId = event.eventId,
                    productId = rpcRequest.productId,
                    deltaQty = rpcRequest.deltaQty,
                    newServerQty = outcome.serverQty,
                    syncedAtEpochMs = syncedAt,
                    retryCount = event.retryCount,
                    auditEvent = audit,
                    productDao = productDao,
                    auditDao = auditDao,
                )
                QueueItemExecutionResult.Synced
            }

            is StockAdjustmentRpcOutcome.Rejected -> {
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.CONFLICT,
                    retryCount = nextRetryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = attemptEpochMs,
                    failureReason = outcome.message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.Conflict(outcome.message)
            }

            is StockAdjustmentRpcOutcome.AuthRequired -> {
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.AUTH_REQUIRED,
                    retryCount = event.retryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = attemptEpochMs,
                    failureReason = outcome.message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.AuthBlocked(outcome.message)
            }

            is StockAdjustmentRpcOutcome.RpcMissing,
            is StockAdjustmentRpcOutcome.NetworkOrTransientError,
            -> {
                val message = when (outcome) {
                    is StockAdjustmentRpcOutcome.RpcMissing -> outcome.message
                    is StockAdjustmentRpcOutcome.NetworkOrTransientError -> outcome.message
                    else -> "Transient error syncing stock adjustment."
                }
                val nextAt = SyncQueuePolicy.computeNextRetryEpochMs(attemptEpochMs, nextRetryCount)
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.FAILED_RETRYABLE,
                    retryCount = nextRetryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = nextAt,
                    failureReason = message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.RetryableFailure(message)
            }
        }
    }

    private suspend fun processStockCountEvent(
        event: SyncQueueEventEntity,
        attemptEpochMs: Long,
    ): QueueItemExecutionResult {
        val draft = SyncQueuePolicy.parseStockCountRpcRequest(event)
        if (draft == null) {
            val msg = "Malformed queued physical stock count payload for ${event.entityId}."
            syncQueueDao.updateEventProgress(
                eventId = event.eventId,
                status = SyncQueueStatus.CONFLICT,
                retryCount = event.retryCount + 1,
                attemptEpochMs = attemptEpochMs,
                nextRetryEpochMs = attemptEpochMs,
                failureReason = msg,
                serverAckId = null,
            )
            return QueueItemExecutionResult.Conflict(msg)
        }

        syncQueueDao.updateEventProgress(
            eventId = event.eventId,
            status = SyncQueueStatus.IN_FLIGHT,
            retryCount = event.retryCount,
            attemptEpochMs = attemptEpochMs,
            nextRetryEpochMs = attemptEpochMs,
            failureReason = null,
            serverAckId = null,
        )

        val nextRetryCount = event.retryCount + 1
        return when (val outcome = stockCountGateway.submitStockCount(draft)) {
            is StockCountRpcOutcome.CommittedOnline -> {
                val syncedAt = clockEpochMs()
                val sessionId = outcome.sessionId.ifBlank { draft.idempotencyKey }
                val audit = LocalAuditEventEntity(
                    auditId = "audit-${UUID.randomUUID()}",
                    actorId = event.actorId,
                    deviceId = event.deviceId,
                    operation = "SYNC_PHYSICAL_STOCK_COUNT_CONFIRMED",
                    entityType = "STOCK_COUNT",
                    entityId = sessionId,
                    beforeJson = null,
                    afterJson = JSONObject().apply {
                        put("session_id", sessionId)
                        put("session_name", draft.sessionName)
                        put("items_counted", draft.totalItemsCounted)
                        put("already_committed", outcome.alreadyCommitted)
                    }.toString(),
                    reason = "Synced offline physical stock count via submit_physical_stock_count",
                    syncStatus = SyncState.SERVER_CONFIRMED,
                    occurredAtEpochMs = syncedAt,
                )
                syncQueueDao.reconcileSyncedStockCountAtomically(
                    eventId = event.eventId,
                    serverSessionId = sessionId,
                    countedItems = draft.items.map {
                        Triple(it.productId.toLong(), it.variance, it.countedQty)
                    },
                    syncedAtEpochMs = syncedAt,
                    retryCount = event.retryCount,
                    auditEvent = audit,
                    productDao = productDao,
                    auditDao = auditDao,
                )
                QueueItemExecutionResult.Synced
            }

            is StockCountRpcOutcome.Rejected -> {
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.CONFLICT,
                    retryCount = nextRetryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = attemptEpochMs,
                    failureReason = outcome.message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.Conflict(outcome.message)
            }

            is StockCountRpcOutcome.AuthRequired -> {
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.AUTH_REQUIRED,
                    retryCount = event.retryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = attemptEpochMs,
                    failureReason = outcome.message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.AuthBlocked(outcome.message)
            }

            is StockCountRpcOutcome.RpcMissing,
            is StockCountRpcOutcome.NetworkOrTransientError,
            -> {
                val message = when (outcome) {
                    is StockCountRpcOutcome.RpcMissing -> outcome.message
                    is StockCountRpcOutcome.NetworkOrTransientError -> outcome.message
                    else -> "Transient error syncing physical stock count."
                }
                val nextAt = SyncQueuePolicy.computeNextRetryEpochMs(attemptEpochMs, nextRetryCount)
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.FAILED_RETRYABLE,
                    retryCount = nextRetryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = nextAt,
                    failureReason = message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.RetryableFailure(message)
            }
        }
    }

    private suspend fun processFinancialEvent(
        event: SyncQueueEventEntity,
        attemptEpochMs: Long,
    ): QueueItemExecutionResult {
        val rpcRequest = SyncQueuePolicy.parseFinancialEventRpcRequest(event)
        if (rpcRequest == null) {
            val msg = "Malformed queued financial event payload for ${event.entityId}."
            syncQueueDao.markFinancialEventConflictAtomically(
                eventId = event.eventId,
                financialEventId = event.entityId,
                conflictMessage = msg,
                attemptEpochMs = attemptEpochMs,
                retryCount = event.retryCount + 1,
                auditEvent = LocalAuditEventEntity(
                    auditId = "audit-${UUID.randomUUID()}",
                    actorId = event.actorId,
                    deviceId = event.deviceId,
                    operation = "SYNC_FINANCIAL_EVENT_CONFLICT",
                    entityType = "FINANCIAL_EVENT",
                    entityId = event.entityId,
                    beforeJson = event.payloadJson,
                    afterJson = null,
                    reason = msg,
                    syncStatus = SyncState.CONFLICT,
                    occurredAtEpochMs = attemptEpochMs,
                ),
                financialAndDraftDao = financialAndDraftDao,
                auditDao = auditDao,
            )
            return QueueItemExecutionResult.Conflict(msg)
        }

        syncQueueDao.updateEventProgress(
            eventId = event.eventId,
            status = SyncQueueStatus.IN_FLIGHT,
            retryCount = event.retryCount,
            attemptEpochMs = attemptEpochMs,
            nextRetryEpochMs = attemptEpochMs,
            failureReason = null,
            serverAckId = null,
        )

        val nextRetryCount = event.retryCount + 1
        return when (val outcome = financialEventGateway.submitFinancialEvent(rpcRequest)) {
            is FinancialEventRpcOutcome.CommittedOnline -> {
                val syncedAt = clockEpochMs()
                val serverEventId = outcome.serverEventId.ifBlank { rpcRequest.clientEventId }
                val audit = LocalAuditEventEntity(
                    auditId = "audit-${UUID.randomUUID()}",
                    actorId = event.actorId,
                    deviceId = event.deviceId,
                    operation = "SYNC_FINANCIAL_EVENT_CONFIRMED",
                    entityType = "FINANCIAL_EVENT",
                    entityId = rpcRequest.clientEventId,
                    beforeJson = null,
                    afterJson = JSONObject().apply {
                        put("server_event_id", serverEventId)
                        put("reference_code", rpcRequest.referenceCode ?: JSONObject.NULL)
                        put("direction", rpcRequest.direction)
                        put("amount", rpcRequest.amount)
                        put("already_committed", outcome.alreadyCommitted)
                        put("classification_status", "PENDING_DESKTOP_RULES")
                    }.toString(),
                    reason = "Synced financial event via record_android_financial_event (awaiting Desktop classification)",
                    syncStatus = SyncState.SERVER_CONFIRMED,
                    occurredAtEpochMs = syncedAt,
                )
                syncQueueDao.reconcileSyncedFinancialEventAtomically(
                    eventId = event.eventId,
                    financialEventId = rpcRequest.clientEventId,
                    serverEventId = serverEventId,
                    syncedAtEpochMs = syncedAt,
                    retryCount = event.retryCount,
                    auditEvent = audit,
                    financialAndDraftDao = financialAndDraftDao,
                    auditDao = auditDao,
                )
                QueueItemExecutionResult.Synced
            }

            is FinancialEventRpcOutcome.Rejected -> {
                val audit = LocalAuditEventEntity(
                    auditId = "audit-${UUID.randomUUID()}",
                    actorId = event.actorId,
                    deviceId = event.deviceId,
                    operation = "SYNC_FINANCIAL_EVENT_CONFLICT",
                    entityType = "FINANCIAL_EVENT",
                    entityId = rpcRequest.clientEventId,
                    beforeJson = event.payloadJson,
                    afterJson = JSONObject().apply {
                        put("code", outcome.code)
                        put("message", outcome.message)
                    }.toString(),
                    reason = outcome.message,
                    syncStatus = SyncState.CONFLICT,
                    occurredAtEpochMs = attemptEpochMs,
                )
                syncQueueDao.markFinancialEventConflictAtomically(
                    eventId = event.eventId,
                    financialEventId = rpcRequest.clientEventId,
                    conflictMessage = outcome.message,
                    attemptEpochMs = attemptEpochMs,
                    retryCount = nextRetryCount,
                    auditEvent = audit,
                    financialAndDraftDao = financialAndDraftDao,
                    auditDao = auditDao,
                )
                QueueItemExecutionResult.Conflict(outcome.message)
            }

            is FinancialEventRpcOutcome.AuthRequired -> {
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.AUTH_REQUIRED,
                    retryCount = event.retryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = attemptEpochMs,
                    failureReason = outcome.message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.AuthBlocked(outcome.message)
            }

            is FinancialEventRpcOutcome.RpcMissing,
            is FinancialEventRpcOutcome.NetworkOrTransientError,
            -> {
                val message = when (outcome) {
                    is FinancialEventRpcOutcome.RpcMissing -> outcome.message
                    is FinancialEventRpcOutcome.NetworkOrTransientError -> outcome.message
                    else -> "Transient error syncing financial event."
                }
                val nextAt = SyncQueuePolicy.computeNextRetryEpochMs(attemptEpochMs, nextRetryCount)
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.FAILED_RETRYABLE,
                    retryCount = nextRetryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = nextAt,
                    failureReason = message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.RetryableFailure(message)
            }
        }
    }

    private suspend fun processPurchaseReceiptEvent(
        event: SyncQueueEventEntity,
        attemptEpochMs: Long,
    ): QueueItemExecutionResult {
        val rpcRequest = SyncQueuePolicy.parsePurchaseRpcRequest(event)
        val finEventId = "fin-purch-${event.entityId}"
        if (rpcRequest == null) {
            val msg = "Malformed queued purchase receipt payload for ${event.entityId}."
            syncQueueDao.markPurchaseConflictAtomically(
                eventId = event.eventId,
                financialEventId = finEventId,
                conflictMessage = msg,
                attemptEpochMs = attemptEpochMs,
                retryCount = event.retryCount + 1,
                auditEvent = LocalAuditEventEntity(
                    auditId = "audit-${UUID.randomUUID()}",
                    actorId = event.actorId,
                    deviceId = event.deviceId,
                    operation = "SYNC_PURCHASE_RECEIPT_CONFLICT",
                    entityType = "PURCHASE",
                    entityId = event.entityId,
                    beforeJson = event.payloadJson,
                    afterJson = null,
                    reason = msg,
                    syncStatus = SyncState.CONFLICT,
                    occurredAtEpochMs = attemptEpochMs,
                ),
                financialAndDraftDao = financialAndDraftDao,
                auditDao = auditDao,
            )
            return QueueItemExecutionResult.Conflict(msg)
        }

        syncQueueDao.updateEventProgress(
            eventId = event.eventId,
            status = SyncQueueStatus.IN_FLIGHT,
            retryCount = event.retryCount,
            attemptEpochMs = attemptEpochMs,
            nextRetryEpochMs = attemptEpochMs,
            failureReason = null,
            serverAckId = null,
        )

        val nextRetryCount = event.retryCount + 1
        return when (val outcome = purchaseGateway.submitPurchase(rpcRequest)) {
            is PurchaseRpcOutcome.CommittedOnline -> {
                val syncedAt = clockEpochMs()
                val newCanonicalProducts = mutableListOf<Triple<Long?, ProductEntity, Int>>()
                val existingIncrements = mutableListOf<Triple<Long, Int, Int>>()

                for (ack in outcome.resolvedProducts) {
                    val serverPid = ack.serverProductId ?: continue
                    if (ack.itemCategory != PurchaseItemCategory.RESALE_PRODUCT) continue
                    val serverQtyAfter = ack.serverQtyAfter ?: ack.qty
                    if (ack.registeredNewProduct) {
                        val identity = ProductMetadataExtractor.toProductIdentity(
                            SupabaseProductDto(
                                id = serverPid,
                                name = ack.productName,
                                qty = serverQtyAfter,
                                sellPrice = ack.sellPrice,
                                buyPrice = ack.buyPrice,
                                imageUrl = null,
                                category = ack.category,
                                createdAt = null,
                                description = null,
                                discountPrice = 0.0,
                                isFeatured = false,
                            )
                        )
                        val entity = ProductEntity(
                            id = identity.id,
                            name = identity.name,
                            baseName = identity.baseName,
                            variantLabel = identity.variantLabel,
                            brandHint = identity.brandHint,
                            category = identity.category,
                            sellPrice = identity.sellPrice,
                            discountPrice = identity.discountPrice,
                            buyPrice = identity.buyPrice,
                            serverStockQty = serverQtyAfter,
                            imageUrl = null,
                            description = null,
                            isFeatured = false,
                            createdAt = null,
                            isArchived = false,
                            confirmedAliasesCsv = "",
                            cachedAtEpochMs = syncedAt,
                        )
                        newCanonicalProducts += Triple(ack.localTempProductId, entity, serverQtyAfter)
                    } else {
                        existingIncrements += Triple(serverPid, ack.qty, serverQtyAfter)
                    }
                }

                val audit = LocalAuditEventEntity(
                    auditId = "audit-${UUID.randomUUID()}",
                    actorId = event.actorId,
                    deviceId = event.deviceId,
                    operation = "SYNC_PURCHASE_RECEIPT_CONFIRMED",
                    entityType = "PURCHASE",
                    entityId = rpcRequest.clientPurchaseId,
                    beforeJson = null,
                    afterJson = JSONObject().apply {
                        put("server_purchase_id", outcome.serverPurchaseId)
                        put("total_amount", outcome.totalAmount)
                        put("resolved_items", outcome.resolvedProducts.size)
                        put("already_committed", outcome.alreadyCommitted)
                    }.toString(),
                    reason = "Synced confirmed purchase receipt via record_android_purchase",
                    syncStatus = SyncState.SERVER_CONFIRMED,
                    occurredAtEpochMs = syncedAt,
                )

                syncQueueDao.reconcileSyncedPurchaseAtomically(
                    eventId = event.eventId,
                    serverPurchaseId = outcome.serverPurchaseId.ifBlank { rpcRequest.clientPurchaseId },
                    newCanonicalProducts = newCanonicalProducts,
                    existingIncrements = existingIncrements,
                    financialEventId = if (rpcRequest.recordFinancialOutflow) finEventId else null,
                    syncedAtEpochMs = syncedAt,
                    retryCount = event.retryCount,
                    auditEvent = audit,
                    productDao = productDao,
                    saleDao = saleDao,
                    financialAndDraftDao = financialAndDraftDao,
                    auditDao = auditDao,
                )
                QueueItemExecutionResult.Synced
            }

            is PurchaseRpcOutcome.Rejected -> {
                val audit = LocalAuditEventEntity(
                    auditId = "audit-${UUID.randomUUID()}",
                    actorId = event.actorId,
                    deviceId = event.deviceId,
                    operation = "SYNC_PURCHASE_RECEIPT_CONFLICT",
                    entityType = "PURCHASE",
                    entityId = rpcRequest.clientPurchaseId,
                    beforeJson = event.payloadJson,
                    afterJson = JSONObject().apply {
                        put("code", outcome.code)
                        put("message", outcome.message)
                    }.toString(),
                    reason = outcome.message,
                    syncStatus = SyncState.CONFLICT,
                    occurredAtEpochMs = attemptEpochMs,
                )
                syncQueueDao.markPurchaseConflictAtomically(
                    eventId = event.eventId,
                    financialEventId = if (rpcRequest.recordFinancialOutflow) finEventId else null,
                    conflictMessage = outcome.message,
                    attemptEpochMs = attemptEpochMs,
                    retryCount = nextRetryCount,
                    auditEvent = audit,
                    financialAndDraftDao = financialAndDraftDao,
                    auditDao = auditDao,
                )
                QueueItemExecutionResult.Conflict(outcome.message)
            }

            is PurchaseRpcOutcome.AuthRequired -> {
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.AUTH_REQUIRED,
                    retryCount = event.retryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = attemptEpochMs,
                    failureReason = outcome.message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.AuthBlocked(outcome.message)
            }

            is PurchaseRpcOutcome.RpcMissing,
            is PurchaseRpcOutcome.NetworkOrTransientError,
            -> {
                val message = when (outcome) {
                    is PurchaseRpcOutcome.RpcMissing -> outcome.message
                    is PurchaseRpcOutcome.NetworkOrTransientError -> outcome.message
                    else -> "Transient error syncing purchase receipt."
                }
                val nextAt = SyncQueuePolicy.computeNextRetryEpochMs(attemptEpochMs, nextRetryCount)
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.FAILED_RETRYABLE,
                    retryCount = nextRetryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = nextAt,
                    failureReason = message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.RetryableFailure(message)
            }
        }
    }

    private suspend fun processServiceOperationEvent(
        event: SyncQueueEventEntity,
        attemptEpochMs: Long,
    ): QueueItemExecutionResult {
        val rpcRequest = SyncQueuePolicy.parseServiceOperationRpcRequest(event)
        val finEventId = "fin-svc-${event.entityId}"
        if (rpcRequest == null) {
            val msg = "Malformed queued service operation payload for ${event.entityId}."
            syncQueueDao.markServiceOperationConflictAtomically(
                eventId = event.eventId,
                financialEventId = finEventId,
                conflictMessage = msg,
                attemptEpochMs = attemptEpochMs,
                retryCount = event.retryCount + 1,
                auditEvent = LocalAuditEventEntity(
                    auditId = "audit-${UUID.randomUUID()}",
                    actorId = event.actorId,
                    deviceId = event.deviceId,
                    operation = "SYNC_SERVICE_OPERATION_CONFLICT",
                    entityType = "SERVICE_OPERATION",
                    entityId = event.entityId,
                    beforeJson = event.payloadJson,
                    afterJson = null,
                    reason = msg,
                    syncStatus = SyncState.CONFLICT,
                    occurredAtEpochMs = attemptEpochMs,
                ),
                financialAndDraftDao = financialAndDraftDao,
                auditDao = auditDao,
            )
            return QueueItemExecutionResult.Conflict(msg)
        }

        syncQueueDao.updateEventProgress(
            eventId = event.eventId,
            status = SyncQueueStatus.IN_FLIGHT,
            retryCount = event.retryCount,
            attemptEpochMs = attemptEpochMs,
            nextRetryEpochMs = attemptEpochMs,
            failureReason = null,
            serverAckId = null,
        )

        val nextRetryCount = event.retryCount + 1
        return when (val outcome = serviceOperationGateway.submitServiceOperation(rpcRequest)) {
            is ServiceRpcOutcome.CommittedOnline -> {
                val syncedAt = clockEpochMs()
                val stockReconciliations = outcome.updatedProducts.mapNotNull { ack ->
                    ack.serverQtyAfter?.let { Triple(ack.productId, ack.qtyDeducted, it) }
                }
                val audit = LocalAuditEventEntity(
                    auditId = "audit-${UUID.randomUUID()}",
                    actorId = event.actorId,
                    deviceId = event.deviceId,
                    operation = "SYNC_SERVICE_OPERATION_CONFIRMED",
                    entityType = "SERVICE_OPERATION",
                    entityId = rpcRequest.clientServiceId,
                    beforeJson = null,
                    afterJson = JSONObject().apply {
                        put("server_service_id", outcome.serverServiceId)
                        put("service_type", rpcRequest.serviceType.code)
                        put("total_revenue", outcome.totalRevenue)
                        put("already_committed", outcome.alreadyCommitted)
                    }.toString(),
                    reason = "Synced service operation via record_android_service_operation",
                    syncStatus = SyncState.SERVER_CONFIRMED,
                    occurredAtEpochMs = syncedAt,
                )
                syncQueueDao.reconcileSyncedServiceOperationAtomically(
                    eventId = event.eventId,
                    serverServiceId = outcome.serverServiceId.ifBlank { rpcRequest.clientServiceId },
                    deductedStockReconciliations = stockReconciliations,
                    financialEventId = if (rpcRequest.totalRevenue > 0.0) finEventId else null,
                    syncedAtEpochMs = syncedAt,
                    retryCount = event.retryCount,
                    auditEvent = audit,
                    productDao = productDao,
                    financialAndDraftDao = financialAndDraftDao,
                    auditDao = auditDao,
                )
                QueueItemExecutionResult.Synced
            }

            is ServiceRpcOutcome.Rejected -> {
                val audit = LocalAuditEventEntity(
                    auditId = "audit-${UUID.randomUUID()}",
                    actorId = event.actorId,
                    deviceId = event.deviceId,
                    operation = "SYNC_SERVICE_OPERATION_CONFLICT",
                    entityType = "SERVICE_OPERATION",
                    entityId = rpcRequest.clientServiceId,
                    beforeJson = event.payloadJson,
                    afterJson = JSONObject().apply {
                        put("code", outcome.code)
                        put("message", outcome.message)
                    }.toString(),
                    reason = outcome.message,
                    syncStatus = SyncState.CONFLICT,
                    occurredAtEpochMs = attemptEpochMs,
                )
                syncQueueDao.markServiceOperationConflictAtomically(
                    eventId = event.eventId,
                    financialEventId = if (rpcRequest.totalRevenue > 0.0) finEventId else null,
                    conflictMessage = outcome.message,
                    attemptEpochMs = attemptEpochMs,
                    retryCount = nextRetryCount,
                    auditEvent = audit,
                    financialAndDraftDao = financialAndDraftDao,
                    auditDao = auditDao,
                )
                QueueItemExecutionResult.Conflict(outcome.message)
            }

            is ServiceRpcOutcome.AuthRequired -> {
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.AUTH_REQUIRED,
                    retryCount = event.retryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = attemptEpochMs,
                    failureReason = outcome.message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.AuthBlocked(outcome.message)
            }

            is ServiceRpcOutcome.RpcMissing,
            is ServiceRpcOutcome.NetworkOrTransientError,
            -> {
                val message = when (outcome) {
                    is ServiceRpcOutcome.RpcMissing -> outcome.message
                    is ServiceRpcOutcome.NetworkOrTransientError -> outcome.message
                    else -> "Transient error syncing service operation."
                }
                val nextAt = SyncQueuePolicy.computeNextRetryEpochMs(attemptEpochMs, nextRetryCount)
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.FAILED_RETRYABLE,
                    retryCount = nextRetryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = nextAt,
                    failureReason = message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.RetryableFailure(message)
            }
        }
    }

    private suspend fun processOrderUpdateEvent(
        event: SyncQueueEventEntity,
        attemptEpochMs: Long,
    ): QueueItemExecutionResult {
        val rpcRequest = SyncQueuePolicy.parseOrderStatusRpcRequest(event)
        if (rpcRequest == null) {
            val msg = "Malformed queued order update payload for ${event.entityId}."
            syncQueueDao.markOrderUpdateConflictAtomically(
                eventId = event.eventId,
                conflictMessage = msg,
                attemptEpochMs = attemptEpochMs,
                retryCount = event.retryCount + 1,
                auditEvent = LocalAuditEventEntity(
                    auditId = "audit-${UUID.randomUUID()}",
                    actorId = event.actorId,
                    deviceId = event.deviceId,
                    operation = "SYNC_ORDER_UPDATE_CONFLICT",
                    entityType = "ORDER",
                    entityId = event.entityId,
                    beforeJson = event.payloadJson,
                    afterJson = null,
                    reason = msg,
                    syncStatus = SyncState.CONFLICT,
                    occurredAtEpochMs = attemptEpochMs,
                ),
                auditDao = auditDao,
            )
            return QueueItemExecutionResult.Conflict(msg)
        }

        syncQueueDao.updateEventProgress(
            eventId = event.eventId,
            status = SyncQueueStatus.IN_FLIGHT,
            retryCount = event.retryCount,
            attemptEpochMs = attemptEpochMs,
            nextRetryEpochMs = attemptEpochMs,
            failureReason = null,
            serverAckId = null,
        )

        val nextRetryCount = event.retryCount + 1
        return when (val outcome = orderStatusGateway.updateOrderStatus(rpcRequest)) {
            is OrderStatusRpcOutcome.CommittedOnline -> {
                val syncedAt = clockEpochMs()
                val audit = LocalAuditEventEntity(
                    auditId = "audit-${UUID.randomUUID()}",
                    actorId = event.actorId,
                    deviceId = event.deviceId,
                    operation = "SYNC_ORDER_UPDATE_CONFIRMED",
                    entityType = "ORDER",
                    entityId = rpcRequest.orderId,
                    beforeJson = null,
                    afterJson = JSONObject().apply {
                        put("server_event_id", outcome.serverEventId)
                        put("new_status", outcome.newRawStatus)
                        put("already_committed", outcome.alreadyCommitted)
                    }.toString(),
                    reason = rpcRequest.reason ?: "Synced order status via update_android_order_status",
                    syncStatus = SyncState.SERVER_CONFIRMED,
                    occurredAtEpochMs = syncedAt,
                )
                syncQueueDao.reconcileSyncedOrderUpdateAtomically(
                    eventId = event.eventId,
                    serverEventId = outcome.serverEventId.ifBlank { rpcRequest.clientUpdateId },
                    syncedAtEpochMs = syncedAt,
                    retryCount = event.retryCount,
                    auditEvent = audit,
                    auditDao = auditDao,
                )
                onOrderUpdateReconciled(rpcRequest.orderId, outcome.newRawStatus)
                QueueItemExecutionResult.Synced
            }

            is OrderStatusRpcOutcome.Rejected -> {
                val audit = LocalAuditEventEntity(
                    auditId = "audit-${UUID.randomUUID()}",
                    actorId = event.actorId,
                    deviceId = event.deviceId,
                    operation = "SYNC_ORDER_UPDATE_CONFLICT",
                    entityType = "ORDER",
                    entityId = rpcRequest.orderId,
                    beforeJson = event.payloadJson,
                    afterJson = JSONObject().apply {
                        put("code", outcome.code)
                        put("message", outcome.message)
                        put("current_server_status", outcome.currentServerRawStatus ?: JSONObject.NULL)
                    }.toString(),
                    reason = outcome.message,
                    syncStatus = SyncState.CONFLICT,
                    occurredAtEpochMs = attemptEpochMs,
                )
                syncQueueDao.markOrderUpdateConflictAtomically(
                    eventId = event.eventId,
                    conflictMessage = outcome.message,
                    attemptEpochMs = attemptEpochMs,
                    retryCount = nextRetryCount,
                    auditEvent = audit,
                    auditDao = auditDao,
                )
                onOrderUpdateReconciled(rpcRequest.orderId, outcome.currentServerRawStatus)
                QueueItemExecutionResult.Conflict(outcome.message)
            }

            is OrderStatusRpcOutcome.AuthRequired -> {
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.AUTH_REQUIRED,
                    retryCount = event.retryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = attemptEpochMs,
                    failureReason = outcome.message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.AuthBlocked(outcome.message)
            }

            is OrderStatusRpcOutcome.RpcMissing,
            is OrderStatusRpcOutcome.NetworkOrTransientError,
            -> {
                val message = when (outcome) {
                    is OrderStatusRpcOutcome.RpcMissing -> outcome.message
                    is OrderStatusRpcOutcome.NetworkOrTransientError -> outcome.message
                    else -> "Transient error syncing order update."
                }
                val nextAt = SyncQueuePolicy.computeNextRetryEpochMs(attemptEpochMs, nextRetryCount)
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.FAILED_RETRYABLE,
                    retryCount = nextRetryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = nextAt,
                    failureReason = message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.RetryableFailure(message)
            }
        }
    }

    private suspend fun processOrderToSaleEvent(
        event: SyncQueueEventEntity,
        attemptEpochMs: Long,
    ): QueueItemExecutionResult {
        val rpcRequest = SyncQueuePolicy.parseOrderConversionRpcRequest(event)
        if (rpcRequest == null) {
            val msg = "Malformed queued order conversion payload for ${event.entityId}."
            syncQueueDao.markOrderConversionConflictAtomically(
                eventId = event.eventId,
                clientSaleId = event.entityId,
                conflictMessage = msg,
                attemptEpochMs = attemptEpochMs,
                retryCount = event.retryCount + 1,
                auditEvent = LocalAuditEventEntity(
                    auditId = "audit-${UUID.randomUUID()}",
                    actorId = event.actorId,
                    deviceId = event.deviceId,
                    operation = "SYNC_ORDER_CONVERSION_CONFLICT",
                    entityType = "ORDER",
                    entityId = event.entityId,
                    beforeJson = event.payloadJson,
                    afterJson = null,
                    reason = msg,
                    syncStatus = SyncState.CONFLICT,
                    occurredAtEpochMs = attemptEpochMs,
                ),
                saleDao = saleDao,
                auditDao = auditDao,
            )
            return QueueItemExecutionResult.Conflict(msg)
        }

        syncQueueDao.updateEventProgress(
            eventId = event.eventId,
            status = SyncQueueStatus.IN_FLIGHT,
            retryCount = event.retryCount,
            attemptEpochMs = attemptEpochMs,
            nextRetryEpochMs = attemptEpochMs,
            failureReason = null,
            serverAckId = null,
        )

        val nextRetryCount = event.retryCount + 1
        return when (val outcome = orderConversionGateway.convertOrderToSale(rpcRequest)) {
            is OrderConversionRpcOutcome.CommittedOnline -> {
                val syncedAt = clockEpochMs()
                val reconciliations = outcome.updatedProducts.map { ack ->
                    Triple(ack.productId, ack.qtyDeducted, ack.serverQtyAfter)
                }
                val audit = LocalAuditEventEntity(
                    auditId = "audit-${UUID.randomUUID()}",
                    actorId = event.actorId,
                    deviceId = event.deviceId,
                    operation = "SYNC_ORDER_CONVERSION_CONFIRMED",
                    entityType = "ORDER",
                    entityId = rpcRequest.orderId,
                    beforeJson = null,
                    afterJson = JSONObject().apply {
                        put("server_conversion_id", outcome.serverConversionId)
                        put("total_amount", outcome.totalAmount)
                        put("already_committed", outcome.alreadyCommitted)
                    }.toString(),
                    reason = "Synced order-to-sale conversion via convert_android_order_to_sale",
                    syncStatus = SyncState.SERVER_CONFIRMED,
                    occurredAtEpochMs = syncedAt,
                )
                syncQueueDao.reconcileSyncedOrderConversionAtomically(
                    eventId = event.eventId,
                    clientSaleId = rpcRequest.clientConversionId,
                    serverConversionId = outcome.serverConversionId.ifBlank { rpcRequest.clientConversionId },
                    firstServerSaleId = outcome.updatedProducts.firstOrNull()?.serverSaleId,
                    productReconciliations = reconciliations,
                    syncedAtEpochMs = syncedAt,
                    retryCount = event.retryCount,
                    auditEvent = audit,
                    saleDao = saleDao,
                    productDao = productDao,
                    auditDao = auditDao,
                )
                onOrderConversionReconciled(rpcRequest.orderId, true)
                QueueItemExecutionResult.Synced
            }

            is OrderConversionRpcOutcome.Rejected -> {
                val audit = LocalAuditEventEntity(
                    auditId = "audit-${UUID.randomUUID()}",
                    actorId = event.actorId,
                    deviceId = event.deviceId,
                    operation = "SYNC_ORDER_CONVERSION_CONFLICT",
                    entityType = "ORDER",
                    entityId = rpcRequest.orderId,
                    beforeJson = event.payloadJson,
                    afterJson = JSONObject().apply {
                        put("code", outcome.code)
                        put("message", outcome.message)
                        put("already_converted", outcome.alreadyConverted)
                    }.toString(),
                    reason = outcome.message,
                    syncStatus = SyncState.CONFLICT,
                    occurredAtEpochMs = attemptEpochMs,
                )
                syncQueueDao.markOrderConversionConflictAtomically(
                    eventId = event.eventId,
                    clientSaleId = rpcRequest.clientConversionId,
                    conflictMessage = outcome.message,
                    attemptEpochMs = attemptEpochMs,
                    retryCount = nextRetryCount,
                    auditEvent = audit,
                    saleDao = saleDao,
                    auditDao = auditDao,
                )
                onOrderConversionReconciled(rpcRequest.orderId, outcome.alreadyConverted)
                QueueItemExecutionResult.Conflict(outcome.message)
            }

            is OrderConversionRpcOutcome.AuthRequired -> {
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.AUTH_REQUIRED,
                    retryCount = event.retryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = attemptEpochMs,
                    failureReason = outcome.message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.AuthBlocked(outcome.message)
            }

            is OrderConversionRpcOutcome.RpcMissing,
            is OrderConversionRpcOutcome.NetworkOrTransientError,
            -> {
                val message = when (outcome) {
                    is OrderConversionRpcOutcome.RpcMissing -> outcome.message
                    is OrderConversionRpcOutcome.NetworkOrTransientError -> outcome.message
                    else -> "Transient error syncing order sale conversion."
                }
                val nextAt = SyncQueuePolicy.computeNextRetryEpochMs(attemptEpochMs, nextRetryCount)
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.FAILED_RETRYABLE,
                    retryCount = nextRetryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = nextAt,
                    failureReason = message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.RetryableFailure(message)
            }
        }
    }

    private suspend fun processFinancialClassificationEvent(
        event: SyncQueueEventEntity,
        attemptEpochMs: Long,
    ): QueueItemExecutionResult {
        val req = SyncQueuePolicy.parseFinancialClassificationRequest(event)
        if (req == null) {
            val msg = "Malformed queued classification payload for ${event.entityId}."
            syncQueueDao.updateEventProgress(
                eventId = event.eventId,
                status = SyncQueueStatus.CONFLICT,
                retryCount = event.retryCount + 1,
                attemptEpochMs = attemptEpochMs,
                nextRetryEpochMs = attemptEpochMs,
                failureReason = msg,
                serverAckId = null,
            )
            return QueueItemExecutionResult.Conflict(msg)
        }

        val localFinancial = financialAndDraftDao.findByEventId(req.clientEventId)
        val finSyncRow = syncQueueDao.getEventById("sync-${req.clientEventId}")
        val resolvedServerEventId = req.serverEventId?.takeIf { it.isNotBlank() && it != req.clientEventId }
            ?: localFinancial?.serverEventId?.takeIf { it.isNotBlank() && it != req.clientEventId }
            ?: finSyncRow?.serverAckId?.takeIf { it.isNotBlank() && it != req.clientEventId }

        val nextRetryCount = event.retryCount + 1
        if (resolvedServerEventId == null) {
            val msg = "Waiting for captured transaction upload ack before classifying ${req.clientEventId}."
            val nextAt = SyncQueuePolicy.computeNextRetryEpochMs(attemptEpochMs, nextRetryCount)
            syncQueueDao.updateEventProgress(
                eventId = event.eventId,
                status = SyncQueueStatus.FAILED_RETRYABLE,
                retryCount = nextRetryCount,
                attemptEpochMs = attemptEpochMs,
                nextRetryEpochMs = nextAt,
                failureReason = msg,
                serverAckId = null,
            )
            return QueueItemExecutionResult.RetryableFailure(msg)
        }

        val effectiveExistingSaleId = when {
            req.existingSaleId != null && req.existingSaleId > 0L -> req.existingSaleId
            !req.existingClientSaleId.isNullOrBlank() -> {
                val saleWithItems = saleDao.getSaleWithItems(req.existingClientSaleId)
                val serverSaleId = saleWithItems?.sale?.serverSaleId
                if (serverSaleId == null || serverSaleId <= 0L) {
                    val msg = "Waiting for linked sale ${req.existingClientSaleId} to sync before classifying."
                    val nextAt = SyncQueuePolicy.computeNextRetryEpochMs(attemptEpochMs, nextRetryCount)
                    syncQueueDao.updateEventProgress(
                        eventId = event.eventId,
                        status = SyncQueueStatus.FAILED_RETRYABLE,
                        retryCount = nextRetryCount,
                        attemptEpochMs = attemptEpochMs,
                        nextRetryEpochMs = nextAt,
                        failureReason = msg,
                        serverAckId = null,
                    )
                    return QueueItemExecutionResult.RetryableFailure(msg)
                }
                serverSaleId
            }
            else -> null
        }

        syncQueueDao.updateEventProgress(
            eventId = event.eventId,
            status = SyncQueueStatus.IN_FLIGHT,
            retryCount = event.retryCount,
            attemptEpochMs = attemptEpochMs,
            nextRetryEpochMs = attemptEpochMs,
            failureReason = null,
            serverAckId = null,
        )

        val outcome = when (req.action.uppercase()) {
            "PRODUCT" -> classificationClient.classifyAsProduct(
                serverEventId = resolvedServerEventId,
                idempotencyKey = req.idempotencyKey,
                productId = req.productId,
                quantity = req.quantity,
                existingSaleId = effectiveExistingSaleId,
                unitSellPrice = req.unitSellPrice,
                paymentMethod = req.paymentMethod,
                note = req.note,
                actorId = req.actorId,
                deviceId = req.deviceId,
            )

            "SERVICE" -> classificationClient.classifyAsService(
                serverEventId = resolvedServerEventId,
                idempotencyKey = req.idempotencyKey,
                serviceType = req.serviceType ?: "OTHER",
                serviceLabel = req.serviceLabel,
                quantityOrDuration = req.quantity,
                unitLabel = req.unitLabel ?: "units",
                unitPrice = req.unitPrice,
                discountAmount = req.discountAmount,
                note = req.note,
                actorId = req.actorId,
                deviceId = req.deviceId,
            )

            else -> classificationClient.classifyAsNonBusiness(
                serverEventId = resolvedServerEventId,
                idempotencyKey = req.idempotencyKey,
                reason = req.note,
                actorId = req.actorId,
                deviceId = req.deviceId,
            )
        }

        return when (outcome) {
            is ClassificationRpcOutcome.Committed -> {
                val syncedAt = clockEpochMs()
                val remote = outcome.updatedEvent
                if (remote != null) {
                    financialAndDraftDao.updateFinancialClassification(
                        eventId = req.clientEventId,
                        classificationStatus = remote.classificationStatus.code,
                        linkedSaleId = remote.linkedSaleId,
                        linkedClientSaleId = remote.linkedClientSaleId,
                        linkedServiceId = remote.linkedServiceId,
                        linkedClientServiceId = remote.linkedClientServiceId,
                        linkedProductId = remote.linkedProductId,
                        linkedProductName = remote.linkedProductName,
                        linkedQuantity = remote.linkedQuantity,
                        classificationNote = remote.classificationNote,
                        classifiedAtEpochMs = remote.classifiedAtEpochMs ?: syncedAt,
                        classifiedBy = remote.classifiedBy,
                        classifiedDevice = remote.classifiedDevice,
                        excludesFromBalance = remote.excludesFromBalance,
                    )
                    financialAndDraftDao.updateFinancialSyncStatus(
                        eventId = req.clientEventId,
                        syncStatus = SyncState.SERVER_CONFIRMED,
                    )
                }
                val audit = LocalAuditEventEntity(
                    auditId = "audit-${UUID.randomUUID()}",
                    actorId = req.actorId,
                    deviceId = req.deviceId,
                    operation = "SYNC_FINANCIAL_CLASSIFICATION_CONFIRMED",
                    entityType = "FINANCIAL_EVENT",
                    entityId = req.clientEventId,
                    beforeJson = null,
                    afterJson = JSONObject().apply {
                        put("action", req.action)
                        put("server_event_id", resolvedServerEventId)
                        put("classification_status", remote?.classificationStatus?.code ?: req.action)
                    }.toString(),
                    reason = "Synced shared transaction classification to Supabase",
                    syncStatus = SyncState.SERVER_CONFIRMED,
                    occurredAtEpochMs = syncedAt,
                )
                syncQueueDao.reconcileSyncedFinancialClassificationAtomically(
                    eventId = event.eventId,
                    serverAckId = resolvedServerEventId,
                    syncedAtEpochMs = syncedAt,
                    retryCount = event.retryCount,
                    auditEvent = audit,
                    auditDao = auditDao,
                )
                onTransactionClassified(req.clientEventId)
                QueueItemExecutionResult.Synced
            }

            is ClassificationRpcOutcome.AlreadyClassified -> {
                val syncedAt = clockEpochMs()
                val remote = outcome.updatedEvent
                if (remote != null) {
                    financialAndDraftDao.updateFinancialClassification(
                        eventId = req.clientEventId,
                        classificationStatus = remote.classificationStatus.code,
                        linkedSaleId = remote.linkedSaleId,
                        linkedClientSaleId = remote.linkedClientSaleId,
                        linkedServiceId = remote.linkedServiceId,
                        linkedClientServiceId = remote.linkedClientServiceId,
                        linkedProductId = remote.linkedProductId,
                        linkedProductName = remote.linkedProductName,
                        linkedQuantity = remote.linkedQuantity,
                        classificationNote = remote.classificationNote,
                        classifiedAtEpochMs = remote.classifiedAtEpochMs ?: syncedAt,
                        classifiedBy = remote.classifiedBy,
                        classifiedDevice = remote.classifiedDevice,
                        excludesFromBalance = remote.excludesFromBalance,
                    )
                    financialAndDraftDao.updateFinancialSyncStatus(
                        eventId = req.clientEventId,
                        syncStatus = SyncState.SERVER_CONFIRMED,
                    )
                }
                val audit = LocalAuditEventEntity(
                    auditId = "audit-${UUID.randomUUID()}",
                    actorId = req.actorId,
                    deviceId = req.deviceId,
                    operation = "SYNC_FINANCIAL_CLASSIFICATION_REMOTE_WIN",
                    entityType = "FINANCIAL_EVENT",
                    entityId = req.clientEventId,
                    beforeJson = event.payloadJson,
                    afterJson = JSONObject().apply {
                        put("winner_status", remote?.classificationStatus?.code ?: ClassificationState.PENDING_CLASSIFICATION.code)
                        put("message", outcome.message)
                    }.toString(),
                    reason = outcome.message,
                    syncStatus = SyncState.SERVER_CONFIRMED,
                    occurredAtEpochMs = syncedAt,
                )
                syncQueueDao.reconcileSyncedFinancialClassificationAtomically(
                    eventId = event.eventId,
                    serverAckId = resolvedServerEventId,
                    syncedAtEpochMs = syncedAt,
                    retryCount = event.retryCount,
                    auditEvent = audit,
                    auditDao = auditDao,
                )
                onTransactionClassified(req.clientEventId)
                QueueItemExecutionResult.Synced
            }

            is ClassificationRpcOutcome.Rejected -> {
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.CONFLICT,
                    retryCount = nextRetryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = attemptEpochMs,
                    failureReason = outcome.message,
                    serverAckId = null,
                )
                runCatching {
                    auditDao.appendAudit(
                        LocalAuditEventEntity(
                            auditId = "audit-${UUID.randomUUID()}",
                            actorId = req.actorId,
                            deviceId = req.deviceId,
                            operation = "SYNC_FINANCIAL_CLASSIFICATION_CONFLICT",
                            entityType = "FINANCIAL_EVENT",
                            entityId = req.clientEventId,
                            beforeJson = event.payloadJson,
                            afterJson = outcome.code,
                            reason = outcome.message,
                            syncStatus = SyncState.CONFLICT,
                            occurredAtEpochMs = attemptEpochMs,
                        )
                    )
                }
                QueueItemExecutionResult.Conflict(outcome.message)
            }

            is ClassificationRpcOutcome.AuthRequired -> {
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.AUTH_REQUIRED,
                    retryCount = event.retryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = attemptEpochMs,
                    failureReason = outcome.message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.AuthBlocked(outcome.message)
            }

            is ClassificationRpcOutcome.RpcMissing,
            is ClassificationRpcOutcome.NetworkOrTransientError,
            -> {
                val message = when (outcome) {
                    is ClassificationRpcOutcome.RpcMissing -> outcome.message
                    is ClassificationRpcOutcome.NetworkOrTransientError -> outcome.message
                    else -> "Transient error syncing classification."
                }
                val nextAt = SyncQueuePolicy.computeNextRetryEpochMs(attemptEpochMs, nextRetryCount)
                syncQueueDao.updateEventProgress(
                    eventId = event.eventId,
                    status = SyncQueueStatus.FAILED_RETRYABLE,
                    retryCount = nextRetryCount,
                    attemptEpochMs = attemptEpochMs,
                    nextRetryEpochMs = nextAt,
                    failureReason = message,
                    serverAckId = null,
                )
                QueueItemExecutionResult.RetryableFailure(message)
            }
        }
    }

    private suspend fun repairQueueIntegrityBeforePass(nowEpochMs: Long) {
        val events = syncQueueDao.getAllEventsSnapshot()
        val drafts = financialAndDraftDao.getAllProductDraftsSnapshot()
        val plan = ReliabilityRecoveryPolicy.analyzeQueueIntegrity(events, drafts)

        for (repair in plan.relinkRepairs) {
            syncQueueDao.updateEventPayload(repair.eventId, repair.updatedPayloadJson)
        }
        for (blocked in plan.dependencyBlocked) {
            val current = syncQueueDao.getEventById(blocked.eventId)
            syncQueueDao.updateEventProgress(
                eventId = blocked.eventId,
                status = SyncQueueStatus.CONFLICT,
                retryCount = (current?.retryCount ?: 0) + 1,
                attemptEpochMs = nowEpochMs,
                nextRetryEpochMs = nowEpochMs,
                failureReason = blocked.reason,
                serverAckId = null,
            )
        }
        for (malformed in plan.malformedEvents) {
            val current = syncQueueDao.getEventById(malformed.eventId)
            syncQueueDao.updateEventProgress(
                eventId = malformed.eventId,
                status = SyncQueueStatus.CONFLICT,
                retryCount = (current?.retryCount ?: 0) + 1,
                attemptEpochMs = nowEpochMs,
                nextRetryEpochMs = nowEpochMs,
                failureReason = malformed.reason,
                serverAckId = null,
            )
        }
    }
}
