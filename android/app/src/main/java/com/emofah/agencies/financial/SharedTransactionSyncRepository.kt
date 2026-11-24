package com.emofah.agencies.financial

import android.content.Context
import com.emofah.agencies.data.SupabaseConfig
import com.emofah.agencies.data.local.EmofahDatabase
import com.emofah.agencies.data.local.entities.LocalAuditEventEntity
import com.emofah.agencies.data.local.entities.PendingFinancialEventEntity
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueuePolicy
import com.emofah.agencies.data.sync.SyncQueueStatus
import com.emofah.agencies.notifications.EmofahNotificationManager
import com.emofah.agencies.sales.ConnectivityChecker
import com.emofah.agencies.sales.DeviceIdentityProvider
import com.emofah.agencies.services.ServiceOperationDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * Coordinates:
 * 1. Immediate Supabase persistence after Android transaction capture (with honest local fallback).
 * 2. Cross-device pull/reconciliation of shared financial transactions + recent unlinked sales.
 * 3. Atomic online-first (or dependency-ordered offline queued) classification into:
 *      - BUSINESS_PRODUCT (link existing sale OR create new sale + single stock deduction)
 *      - BUSINESS_SERVICE (link service with qty, price, discount without duplicate payment)
 *      - NON_BUSINESS (exclude from business balance while preserving original record & audit trail)
 * 4. Automatic dismissal of Android pending-classification notifications when classified on either device.
 */
class SharedTransactionSyncRepository(
    private val database: EmofahDatabase,
    private val identityProvider: DeviceIdentityProvider,
    private val connectivityChecker: ConnectivityChecker,
    private val financialGateway: FinancialEventGateway = SupabaseFinancialEventClient(),
    private val classificationClient: SupabaseTransactionClassificationClient = SupabaseTransactionClassificationClient(),
    private val attributionStore: MpesaInflowAttributionStore? = null,
    private val onOfflineEventQueued: () -> Unit = {},
    private val clockEpochMs: () -> Long = { System.currentTimeMillis() },
    private val isSupabaseConfigured: Boolean = SupabaseConfig.isConfigured,
) {
    private val financialDao = database.financialAndDraftDao()
    private val syncQueueDao = database.syncQueueDao()
    private val auditDao = database.auditDao()

    /**
     * Attempts immediate Supabase persistence right after MpesaMonitoringRepository saves to Room.
     * Returns true if confirmed on Supabase, false if left queued for WorkManager retry.
     */
    suspend fun uploadCapturedEventImmediately(eventId: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!isSupabaseConfigured || !connectivityChecker.isOnline()) {
                return@withContext false
            }

            val queueId = "sync-$eventId"
            val queued = syncQueueDao.findEventById(queueId) ?: return@withContext false
            if (queued.status == SyncQueueStatus.SYNCED) {
                return@withContext true
            }

            val rpcRequest = SyncQueuePolicy.parseFinancialEventRpcRequest(queued)
                ?: return@withContext false

            val now = clockEpochMs()
            when (val outcome = financialGateway.submitFinancialEvent(rpcRequest)) {
                is FinancialEventRpcOutcome.CommittedOnline -> {
                    val serverEventId = outcome.serverEventId.ifBlank { rpcRequest.clientEventId }
                    val audit = LocalAuditEventEntity(
                        auditId = "audit-${UUID.randomUUID()}",
                        actorId = rpcRequest.actorId,
                        deviceId = rpcRequest.deviceId,
                        operation = "AUTO_CAPTURE_CONFIRMED_SUPABASE",
                        entityType = "FINANCIAL_EVENT",
                        entityId = rpcRequest.clientEventId,
                        beforeJson = null,
                        afterJson = JSONObject().apply {
                            put("server_event_id", serverEventId)
                            put("classification_status", "PENDING_CLASSIFICATION")
                            put("already_committed", outcome.alreadyCommitted)
                        }.toString(),
                        reason = "Auto-persisted captured transaction to Supabase as PENDING_CLASSIFICATION",
                        syncStatus = SyncState.SERVER_CONFIRMED,
                        occurredAtEpochMs = now,
                    )
                    syncQueueDao.reconcileSyncedFinancialEventAtomically(
                        eventId = queued.eventId,
                        financialEventId = rpcRequest.clientEventId,
                        serverEventId = serverEventId,
                        syncedAtEpochMs = now,
                        retryCount = queued.retryCount,
                        auditEvent = audit,
                        financialAndDraftDao = financialDao,
                        auditDao = auditDao,
                    )
                    true
                }

                else -> {
                    onOfflineEventQueued()
                    false
                }
            }
        }

    suspend fun refreshSharedTransactionsFromServer(
        appContext: Context? = null,
    ): SharedTransactionsFetchOutcome = withContext(Dispatchers.IO) {
        if (!isSupabaseConfigured || !connectivityChecker.isOnline()) {
            return@withContext SharedTransactionsFetchOutcome.Failure("Offline")
        }

        val outcome = classificationClient.fetchSharedTransactions(limit = 150)
        if (outcome is SharedTransactionsFetchOutcome.Success) {
            reconcileRemoteSnapshotIntoLocal(
                remoteTransactions = outcome.transactions,
                appContext = appContext,
            )
        }
        outcome
    }

    suspend fun reconcileRemoteSnapshotIntoLocal(
        remoteTransactions: List<RemoteFinancialTransaction>,
        appContext: Context? = null,
    ) = withContext(Dispatchers.IO) {
        val existingList = financialDao.getAllFinancialEventsSnapshot()
        val byEventId = existingList.associateBy { it.eventId }
        val byServerId = existingList.mapNotNull { evt ->
            evt.serverEventId?.takeIf { it.isNotBlank() }?.let { it to evt }
        }.toMap()
        val byRef = existingList.mapNotNull { evt ->
            evt.transactionReference?.trim()?.uppercase()?.takeIf { it.isNotBlank() }?.let { it to evt }
        }.toMap()

        for (remote in remoteTransactions) {
            val matchedLocal = remote.clientEventId?.let { byEventId[it] }
                ?: byServerId[remote.serverEventId]
                ?: remote.referenceCode?.trim()?.uppercase()?.let { byRef[it] }

            // Preserve unsynced local offline classifications until queued sync finishes
            if (matchedLocal != null) {
                val hasPendingLocalClass = syncQueueDao.findEventById("sync-cls-${matchedLocal.eventId}")
                    ?.let { it.status == SyncQueueStatus.QUEUED || it.status == SyncQueueStatus.FAILED_RETRYABLE }
                    ?: false
                if (hasPendingLocalClass) continue
            }

            val targetEventId = matchedLocal?.eventId
                ?: remote.clientEventId?.takeIf { it.isNotBlank() }
                ?: "fin-srv-${remote.serverEventId}"

            val updatedEntity = buildMergedLocalEntity(
                targetEventId = targetEventId,
                existingLocal = matchedLocal,
                remote = remote,
            )
            financialDao.upsertFinancialEvent(updatedEntity)

            if (remote.classificationStatus != ClassificationState.PENDING_CLASSIFICATION) {
                appContext?.let {
                    EmofahNotificationManager.cancelTransactionNotification(it, targetEventId)
                }
                syncAttributionStoreForRemote(updatedEntity, remote)
            }
        }
    }

    private fun buildMergedLocalEntity(
        targetEventId: String,
        existingLocal: PendingFinancialEventEntity?,
        remote: RemoteFinancialTransaction,
    ): PendingFinancialEventEntity {
        val mergedParsedJson = runCatching {
            val base = existingLocal?.parsedJson?.let { JSONObject(it) } ?: JSONObject()
            remote.transactionCost?.let { base.put("transaction_cost", it) }
            remote.newBalance?.let { base.put("new_balance", it) }
            remote.counterpartyAccount?.let { base.put("counterparty_account", it) }
            val netAmount = when (remote.classificationStatus) {
                ClassificationState.BUSINESS_SERVICE -> remote.serviceTotalRevenue ?: remote.amount
                ClassificationState.BUSINESS_PRODUCT -> remote.saleTotalPrice ?: remote.amount
                else -> null
            }
            if (netAmount != null) {
                base.put("classified_net_amount", netAmount)
            }
            base.toString()
        }.getOrNull() ?: existingLocal?.parsedJson

        return PendingFinancialEventEntity(
            eventId = targetEventId,
            source = existingLocal?.source ?: remote.source,
            amount = remote.amount,
            currency = remote.currency,
            transactionReference = remote.referenceCode ?: existingLocal?.transactionReference,
            counterparty = remote.counterparty ?: existingLocal?.counterparty,
            direction = remote.direction,
            rawEvidence = remote.rawEvidence ?: existingLocal?.rawEvidence,
            parsedJson = mergedParsedJson,
            provisionalCategory = remote.classificationStatus.code,
            classificationConfidence = existingLocal?.classificationConfidence ?: 1f,
            reviewStatus = if (remote.classificationStatus == ClassificationState.PENDING_CLASSIFICATION) {
                "PENDING_CLASSIFICATION"
            } else {
                remote.classificationStatus.code
            },
            syncStatus = SyncState.SERVER_CONFIRMED,
            eventTimestampEpochMs = existingLocal?.eventTimestampEpochMs ?: remote.occurredAtEpochMs,
            classificationStatus = remote.classificationStatus.code,
            serverEventId = remote.serverEventId,
            linkedSaleId = remote.linkedSaleId,
            linkedClientSaleId = remote.linkedClientSaleId,
            linkedServiceId = remote.linkedServiceId,
            linkedClientServiceId = remote.linkedClientServiceId,
            linkedProductId = remote.linkedProductId,
            linkedProductName = remote.linkedProductName ?: remote.serviceLabel,
            linkedQuantity = remote.linkedQuantity,
            classificationNote = remote.classificationNote,
            classifiedAtEpochMs = remote.classifiedAtEpochMs,
            classifiedBy = remote.classifiedBy,
            classifiedDevice = remote.classifiedDevice,
            excludesFromBalance = remote.excludesFromBalance,
        )
    }

    private fun syncAttributionStoreForRemote(
        entity: PendingFinancialEventEntity,
        remote: RemoteFinancialTransaction,
    ) {
        val store = attributionStore ?: return
        val target = when (remote.classificationStatus) {
            ClassificationState.BUSINESS_PRODUCT -> MpesaInflowLinkTarget.SALE
            ClassificationState.BUSINESS_SERVICE -> MpesaInflowLinkTarget.SERVICE
            ClassificationState.NON_BUSINESS -> MpesaInflowLinkTarget.DISMISSED
            ClassificationState.PENDING_CLASSIFICATION -> return
        }
        val recId = when (target) {
            MpesaInflowLinkTarget.SALE -> (remote.linkedSaleId?.toString() ?: remote.linkedClientSaleId ?: "sale")
            MpesaInflowLinkTarget.SERVICE -> (remote.linkedServiceId ?: remote.linkedClientServiceId ?: "service")
            MpesaInflowLinkTarget.DISMISSED -> "non-business"
        }
        val summary = when (target) {
            MpesaInflowLinkTarget.SALE ->
                listOfNotNull(remote.linkedProductName, remote.linkedQuantity?.let { "×$it" }).joinToString(" ")
                    .ifBlank { "Linked to Product Sale" }
            MpesaInflowLinkTarget.SERVICE ->
                (remote.serviceLabel ?: remote.linkedProductName ?: "Service") +
                    " · Ksh ${(remote.serviceTotalRevenue ?: remote.amount).toInt()}"
            MpesaInflowLinkTarget.DISMISSED ->
                remote.classificationNote ?: "Removed from business transactions"
        }
        store.tryRecordLink(
            event = entity,
            target = target,
            linkedRecordId = recId,
            summary = summary,
            nowEpochMs = remote.classifiedAtEpochMs ?: clockEpochMs(),
        )
    }

    suspend fun classifyAsProduct(
        event: PendingFinancialEventEntity,
        productId: Long? = null,
        quantity: Int = 1,
        existingSaleId: Long? = null,
        existingClientSaleId: String? = null,
        productNameHint: String? = null,
        unitSellPrice: Double? = null,
        paymentMethod: String = "MPESA",
        note: String? = null,
        appContext: Context? = null,
    ): SharedClassificationOutcome = withContext(Dispatchers.IO) {
        if (!BusinessBalanceCalculator.canClassify(event)) {
            return@withContext SharedClassificationOutcome.Rejected(
                "This transaction is already classified (${event.classificationStatus})."
            )
        }

        val idemKey = "cls-prod-${event.eventId}-${UUID.randomUUID()}"
        val actorId = identityProvider.getActorId()
        val deviceId = identityProvider.getDeviceId()
        val serverId = resolveServerEventId(event)

        if (isSupabaseConfigured && connectivityChecker.isOnline() && serverId != null &&
            (existingSaleId != null || existingClientSaleId == null)
        ) {
            when (
                val rpc = classificationClient.classifyAsProduct(
                    serverEventId = serverId,
                    idempotencyKey = idemKey,
                    productId = productId,
                    quantity = quantity,
                    existingSaleId = existingSaleId,
                    unitSellPrice = unitSellPrice,
                    paymentMethod = paymentMethod,
                    note = note,
                    actorId = actorId,
                    deviceId = deviceId,
                )
            ) {
                is ClassificationRpcOutcome.Committed -> {
                    val updated = applyRpcEventUpdate(event, rpc.updatedEvent, appContext)
                    return@withContext SharedClassificationOutcome.ClassifiedOnline(
                        event = updated,
                        message = if (existingSaleId != null) {
                            "Linked payment to Sale #$existingSaleId."
                        } else {
                            "Created sale & linked ${productNameHint ?: "product"}."
                        },
                    )
                }

                is ClassificationRpcOutcome.AlreadyClassified -> {
                    val updated = applyRpcEventUpdate(event, rpc.updatedEvent, appContext)
                    return@withContext SharedClassificationOutcome.ClassifiedOnline(
                        event = updated,
                        message = rpc.message,
                        alreadyClassifiedByOtherDevice = true,
                    )
                }

                is ClassificationRpcOutcome.Rejected -> {
                    return@withContext SharedClassificationOutcome.Rejected(rpc.message)
                }

                is ClassificationRpcOutcome.AuthRequired,
                is ClassificationRpcOutcome.RpcMissing,
                is ClassificationRpcOutcome.NetworkOrTransientError,
                -> Unit // Fall through to offline queued classification
            }
        }

        val depEventId = resolveClassificationDependency(
            event = event,
            existingClientSaleId = existingClientSaleId,
        )
        val now = clockEpochMs()
        val req = QueuedClassificationRequest(
            clientEventId = event.eventId,
            serverEventId = serverId,
            action = "PRODUCT",
            idempotencyKey = idemKey,
            productId = productId,
            quantity = quantity,
            existingSaleId = existingSaleId,
            existingClientSaleId = existingClientSaleId,
            unitSellPrice = unitSellPrice,
            paymentMethod = paymentMethod,
            note = note,
            actorId = actorId,
            deviceId = deviceId,
        )
        persistOfflineClassification(
            event = event,
            request = req,
            classificationStatus = ClassificationState.BUSINESS_PRODUCT.code,
            linkedSaleId = existingSaleId,
            linkedClientSaleId = existingClientSaleId,
            linkedServiceId = null,
            linkedClientServiceId = null,
            linkedProductId = productId,
            linkedProductName = productNameHint,
            linkedQuantity = quantity,
            excludesFromBalance = false,
            dependencyEventId = depEventId,
            nowMs = now,
            appContext = appContext,
        )
        SharedClassificationOutcome.QueuedOffline(
            event = financialDao.findByEventId(event.eventId) ?: event,
            message = "Saved product classification offline — will sync to Supabase automatically.",
        )
    }

    suspend fun classifyAsService(
        event: PendingFinancialEventEntity,
        draft: ServiceOperationDraft,
        appContext: Context? = null,
    ): SharedClassificationOutcome = withContext(Dispatchers.IO) {
        if (!BusinessBalanceCalculator.canClassify(event)) {
            return@withContext SharedClassificationOutcome.Rejected(
                "This transaction is already classified (${event.classificationStatus})."
            )
        }

        val idemKey = "cls-svc-${event.eventId}-${UUID.randomUUID()}"
        val actorId = identityProvider.getActorId()
        val deviceId = identityProvider.getDeviceId()
        val serverId = resolveServerEventId(event)
        val note = draft.customerNote.trim().takeIf { it.isNotBlank() }

        if (isSupabaseConfigured && connectivityChecker.isOnline() && serverId != null) {
            when (
                val rpc = classificationClient.classifyAsService(
                    serverEventId = serverId,
                    idempotencyKey = idemKey,
                    serviceType = draft.serviceType.code,
                    serviceLabel = draft.serviceType.label,
                    quantityOrDuration = draft.quantityOrDuration,
                    unitLabel = draft.serviceType.unitLabel,
                    unitPrice = draft.unitPrice,
                    discountAmount = draft.discountAmount,
                    note = note,
                    actorId = actorId,
                    deviceId = deviceId,
                )
            ) {
                is ClassificationRpcOutcome.Committed -> {
                    val updated = applyRpcEventUpdate(event, rpc.updatedEvent, appContext)
                    return@withContext SharedClassificationOutcome.ClassifiedOnline(
                        event = updated,
                        message = "Linked payment to ${draft.serviceType.label} (no duplicate payment created).",
                    )
                }

                is ClassificationRpcOutcome.AlreadyClassified -> {
                    val updated = applyRpcEventUpdate(event, rpc.updatedEvent, appContext)
                    return@withContext SharedClassificationOutcome.ClassifiedOnline(
                        event = updated,
                        message = rpc.message,
                        alreadyClassifiedByOtherDevice = true,
                    )
                }

                is ClassificationRpcOutcome.Rejected -> {
                    return@withContext SharedClassificationOutcome.Rejected(rpc.message)
                }

                is ClassificationRpcOutcome.AuthRequired,
                is ClassificationRpcOutcome.RpcMissing,
                is ClassificationRpcOutcome.NetworkOrTransientError,
                -> Unit
            }
        }

        val depEventId = resolveClassificationDependency(event = event, existingClientSaleId = null)
        val now = clockEpochMs()
        val req = QueuedClassificationRequest(
            clientEventId = event.eventId,
            serverEventId = serverId,
            action = "SERVICE",
            idempotencyKey = idemKey,
            quantity = draft.quantityOrDuration,
            serviceType = draft.serviceType.code,
            serviceLabel = draft.serviceType.label,
            unitLabel = draft.serviceType.unitLabel,
            unitPrice = draft.unitPrice,
            discountAmount = draft.discountAmount,
            note = note,
            actorId = actorId,
            deviceId = deviceId,
        )
        persistOfflineClassification(
            event = event,
            request = req,
            classificationStatus = ClassificationState.BUSINESS_SERVICE.code,
            linkedSaleId = null,
            linkedClientSaleId = null,
            linkedServiceId = null,
            linkedClientServiceId = draft.clientServiceId,
            linkedProductId = null,
            linkedProductName = draft.serviceType.label,
            linkedQuantity = draft.quantityOrDuration,
            excludesFromBalance = false,
            dependencyEventId = depEventId,
            nowMs = now,
            appContext = appContext,
        )
        SharedClassificationOutcome.QueuedOffline(
            event = financialDao.findByEventId(event.eventId) ?: event,
            message = "Linked service offline — will sync to Supabase without duplicate payment.",
        )
    }

    suspend fun classifyAsNonBusiness(
        event: PendingFinancialEventEntity,
        reason: String = "Personal / non-business transaction",
        appContext: Context? = null,
    ): SharedClassificationOutcome = withContext(Dispatchers.IO) {
        if (!BusinessBalanceCalculator.canClassify(event)) {
            return@withContext SharedClassificationOutcome.Rejected(
                "This transaction is already classified (${event.classificationStatus})."
            )
        }

        val cleanReason = reason.trim().ifBlank { "Personal / non-business transaction" }
        val idemKey = "cls-nonbiz-${event.eventId}-${UUID.randomUUID()}"
        val actorId = identityProvider.getActorId()
        val deviceId = identityProvider.getDeviceId()
        val serverId = resolveServerEventId(event)

        if (isSupabaseConfigured && connectivityChecker.isOnline() && serverId != null) {
            when (
                val rpc = classificationClient.classifyAsNonBusiness(
                    serverEventId = serverId,
                    idempotencyKey = idemKey,
                    reason = cleanReason,
                    actorId = actorId,
                    deviceId = deviceId,
                )
            ) {
                is ClassificationRpcOutcome.Committed -> {
                    val updated = applyRpcEventUpdate(event, rpc.updatedEvent, appContext)
                    return@withContext SharedClassificationOutcome.ClassifiedOnline(
                        event = updated,
                        message = "Excluded from business balance (original record & history preserved).",
                    )
                }

                is ClassificationRpcOutcome.AlreadyClassified -> {
                    val updated = applyRpcEventUpdate(event, rpc.updatedEvent, appContext)
                    return@withContext SharedClassificationOutcome.ClassifiedOnline(
                        event = updated,
                        message = rpc.message,
                        alreadyClassifiedByOtherDevice = true,
                    )
                }

                is ClassificationRpcOutcome.Rejected -> {
                    return@withContext SharedClassificationOutcome.Rejected(rpc.message)
                }

                is ClassificationRpcOutcome.AuthRequired,
                is ClassificationRpcOutcome.RpcMissing,
                is ClassificationRpcOutcome.NetworkOrTransientError,
                -> Unit
            }
        }

        val depEventId = resolveClassificationDependency(event = event, existingClientSaleId = null)
        val now = clockEpochMs()
        val req = QueuedClassificationRequest(
            clientEventId = event.eventId,
            serverEventId = serverId,
            action = "NON_BUSINESS",
            idempotencyKey = idemKey,
            note = cleanReason,
            actorId = actorId,
            deviceId = deviceId,
        )
        persistOfflineClassification(
            event = event,
            request = req,
            classificationStatus = ClassificationState.NON_BUSINESS.code,
            linkedSaleId = null,
            linkedClientSaleId = null,
            linkedServiceId = null,
            linkedClientServiceId = null,
            linkedProductId = null,
            linkedProductName = null,
            linkedQuantity = null,
            excludesFromBalance = true,
            dependencyEventId = depEventId,
            nowMs = now,
            appContext = appContext,
        )
        SharedClassificationOutcome.QueuedOffline(
            event = financialDao.findByEventId(event.eventId) ?: event,
            message = "Excluded locally & queued for Supabase sync.",
        )
    }

    private suspend fun resolveServerEventId(event: PendingFinancialEventEntity): String? {
        event.serverEventId?.takeIf { it.isNotBlank() && it != event.eventId }?.let { return it }
        val syncEvent = syncQueueDao.findEventById("sync-${event.eventId}")
        return syncEvent?.serverAckId?.takeIf { it.isNotBlank() && it != event.eventId }
    }

    private suspend fun resolveClassificationDependency(
        event: PendingFinancialEventEntity,
        existingClientSaleId: String?,
    ): String? {
        if (!existingClientSaleId.isNullOrBlank()) {
            val saleSync = syncQueueDao.findEventById("sync-sale-$existingClientSaleId")
            if (saleSync != null && saleSync.status != SyncQueueStatus.SYNCED) {
                return saleSync.eventId
            }
        }
        val finSync = syncQueueDao.findEventById("sync-${event.eventId}")
        if (finSync != null && finSync.status != SyncQueueStatus.SYNCED) {
            return finSync.eventId
        }
        return null
    }

    private suspend fun persistOfflineClassification(
        event: PendingFinancialEventEntity,
        request: QueuedClassificationRequest,
        classificationStatus: String,
        linkedSaleId: Long?,
        linkedClientSaleId: String?,
        linkedServiceId: String?,
        linkedClientServiceId: String?,
        linkedProductId: Long?,
        linkedProductName: String?,
        linkedQuantity: Int?,
        excludesFromBalance: Boolean,
        dependencyEventId: String?,
        nowMs: Long,
        appContext: Context?,
    ) {
        val payloadJson = SyncQueuePolicy.serializeFinancialClassificationRequest(request)
        val syncEvent = SyncQueueEventEntity(
            eventId = "sync-cls-${event.eventId}",
            eventType = SyncEventType.FINANCIAL_CLASSIFICATION,
            entityId = event.eventId,
            dependencyEventId = dependencyEventId,
            payloadJson = payloadJson,
            actorId = request.actorId,
            deviceId = request.deviceId,
            status = SyncQueueStatus.QUEUED,
            retryCount = 0,
            nextRetryAtEpochMs = nowMs,
            createdAtEpochMs = nowMs,
        )
        val auditEvent = LocalAuditEventEntity(
            auditId = "audit-${UUID.randomUUID()}",
            actorId = request.actorId,
            deviceId = request.deviceId,
            operation = "FINANCIAL_CLASSIFICATION_QUEUED",
            entityType = "FINANCIAL_EVENT",
            entityId = event.eventId,
            beforeJson = JSONObject().apply {
                put("classification_status", event.classificationStatus)
            }.toString(),
            afterJson = payloadJson,
            reason = request.note ?: "Classified locally as $classificationStatus",
            syncStatus = SyncState.LOCAL_PENDING,
            occurredAtEpochMs = nowMs,
        )
        syncQueueDao.commitFinancialClassificationOfflineAtomically(
            financialEventId = event.eventId,
            classificationStatus = classificationStatus,
            linkedSaleId = linkedSaleId,
            linkedClientSaleId = linkedClientSaleId,
            linkedServiceId = linkedServiceId,
            linkedClientServiceId = linkedClientServiceId,
            linkedProductId = linkedProductId,
            linkedProductName = linkedProductName,
            linkedQuantity = linkedQuantity,
            classificationNote = request.note,
            classifiedAtEpochMs = nowMs,
            classifiedBy = request.actorId,
            classifiedDevice = request.deviceId,
            excludesFromBalance = excludesFromBalance,
            syncEvent = syncEvent,
            auditEvent = auditEvent,
            financialAndDraftDao = financialDao,
            auditDao = auditDao,
        )
        appContext?.let {
            EmofahNotificationManager.cancelTransactionNotification(it, event.eventId)
        }
        onOfflineEventQueued()
    }

    private suspend fun applyRpcEventUpdate(
        localEvent: PendingFinancialEventEntity,
        remote: RemoteFinancialTransaction?,
        appContext: Context?,
    ): PendingFinancialEventEntity {
        if (remote != null) {
            val merged = buildMergedLocalEntity(
                targetEventId = localEvent.eventId,
                existingLocal = localEvent,
                remote = remote,
            )
            financialDao.upsertFinancialEvent(merged)
            syncAttributionStoreForRemote(merged, remote)
            appContext?.let {
                EmofahNotificationManager.cancelTransactionNotification(it, localEvent.eventId)
            }
            return merged
        }
        return financialDao.findByEventId(localEvent.eventId) ?: localEvent
    }
}
