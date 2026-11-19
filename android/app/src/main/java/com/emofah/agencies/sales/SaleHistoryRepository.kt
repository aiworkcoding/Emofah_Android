package com.emofah.agencies.sales

import com.emofah.agencies.data.SupabaseConfig
import com.emofah.agencies.data.local.EmofahDatabase
import com.emofah.agencies.data.local.dao.LocalSaleWithItems
import com.emofah.agencies.data.local.entities.LocalAuditEventEntity
import com.emofah.agencies.data.local.entities.PendingFinancialEventEntity
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueueStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * Stage 2.4 (Actions 2.4.1 & 2.4.2):
 * Observes recorded local/synced sales and executes safe, authorised, non-destructive
 * sale reversals with idempotency, stock restoration, accounting adjustment, and audit preservation.
 */
class SaleHistoryRepository(
    private val database: EmofahDatabase,
    private val identityProvider: DeviceIdentityProvider,
    private val connectivityChecker: ConnectivityChecker,
    private val reversalRpcGateway: SaleReversalRpcGateway = SupabaseSaleReversalClient(),
    private val isSupabaseConfigured: Boolean = SupabaseConfig.isConfigured,
    private val onOfflineEventQueued: () -> Unit = {},
) {
    private val saleDao = database.saleDao()
    private val productDao = database.productDao()
    private val syncQueueDao = database.syncQueueDao()
    private val financialAndDraftDao = database.financialAndDraftDao()
    private val auditDao = database.auditDao()

    fun observeAllSalesWithItems(): Flow<List<LocalSaleWithItems>> =
        saleDao.observeAllSalesWithItems()

    suspend fun reverseSale(
        saleWithItems: LocalSaleWithItems,
        reason: String,
    ): ReverseSaleOutcome = withContext(Dispatchers.IO) {
        // Re-read authoritative local state to block concurrent double-reversal attempts.
        val currentRecord = saleDao.getSaleWithItems(saleWithItems.sale.clientSaleId)
            ?: return@withContext ReverseSaleOutcome.Rejected("Sale record no longer exists.")

        val sale = currentRecord.sale
        if (sale.isReversed) {
            return@withContext ReverseSaleOutcome.AlreadyReversed(
                clientSaleId = sale.clientSaleId,
                existingReason = sale.reversalReason,
            )
        }

        val actorRole = identityProvider.getStaffRole()
        val validation = SaleReversalPolicy.validateReversalRequest(
            sale = sale,
            role = actorRole,
            reason = reason,
        )
        if (!validation.isAllowed || validation.cleanReason == null) {
            return@withContext ReverseSaleOutcome.Rejected(
                message = validation.errorMessage ?: "Sale reversal could not be validated."
            )
        }

        val cleanReason = validation.cleanReason
        val primaryItem = currentRecord.items.firstOrNull()
            ?: return@withContext ReverseSaleOutcome.Rejected("Sale has no item details to restore stock safely.")

        val restoreQty = currentRecord.items.sumOf { it.quantity }.coerceAtLeast(1)
        val now = System.currentTimeMillis()
        val reversedAtIso = SaleTransactionRepository.formatIsoUtc(now)
        val actorId = identityProvider.getActorId()
        val deviceId = identityProvider.getDeviceId()
        val idempotencyKey = SaleReversalPolicy.buildReversalIdempotencyKey(sale.clientSaleId)

        val financialAdjustment = PendingFinancialEventEntity(
            eventId = "fin-rev-${sale.clientSaleId}",
            source = "SALE_REVERSAL",
            amount = sale.totalAmount,
            currency = "KES",
            transactionReference = "REV-${sale.clientSaleId.take(12)}",
            counterparty = primaryItem.productNameSnapshot,
            direction = "OUTFLOW",
            rawEvidence = cleanReason,
            parsedJson = JSONObject().apply {
                put("client_sale_id", sale.clientSaleId)
                put("server_sale_id", sale.serverSaleId ?: JSONObject.NULL)
                put("payment_method", sale.paymentMethod)
                put("accounting_treatment", "SALE_REVERSAL_CREDIT_ADJUSTMENT")
                put("restored_qty", restoreQty)
            }.toString(),
            provisionalCategory = "SALE_REVERSAL_ADJUSTMENT",
            classificationConfidence = 1.0f,
            reviewStatus = "AUTO_LINKED_REVERSAL",
            syncStatus = SyncState.LOCAL_PENDING,
            eventTimestampEpochMs = now,
        )

        val beforeJson = JSONObject().apply {
            put("client_sale_id", sale.clientSaleId)
            put("server_sale_id", sale.serverSaleId ?: JSONObject.NULL)
            put("total_amount", sale.totalAmount)
            put("sync_status", sale.syncStatus)
            put("is_reversed", sale.isReversed)
        }.toString()

        // Branch 1: Sale has never been confirmed by server -> cancel queued upload cleanly without touching server sales
        val wasNeverConfirmedOnServer = sale.serverSaleId == null && sale.syncStatus != SyncState.SERVER_CONFIRMED
        if (wasNeverConfirmedOnServer) {
            val pendingQueueEvent = syncQueueDao.findSaleEventByClientSaleId(sale.clientSaleId)
            val audit = LocalAuditEventEntity(
                auditId = "audit-${UUID.randomUUID()}",
                actorId = actorId,
                deviceId = deviceId,
                operation = "SALE_REVERSED_UNSYNCED",
                entityType = "SALE",
                entityId = sale.clientSaleId,
                beforeJson = beforeJson,
                afterJson = JSONObject().apply {
                    put("is_reversed", true)
                    put("sync_status", SyncQueueStatus.RESOLVED_LOCALLY)
                    put("restored_qty", restoreQty)
                    put("reason", cleanReason)
                }.toString(),
                reason = cleanReason,
                syncStatus = SyncState.LOCAL_PENDING,
                occurredAtEpochMs = now,
            )

            saleDao.commitReversalOfUnsyncedSaleAtomically(
                clientSaleId = sale.clientSaleId,
                pendingSaleEventId = pendingQueueEvent?.eventId,
                productId = primaryItem.productId,
                restoreQty = restoreQty,
                reason = cleanReason,
                resolvedAtEpochMs = now,
                financialAdjustment = financialAdjustment.copy(syncStatus = SyncState.SERVER_CONFIRMED),
                auditEvent = audit,
                productDao = productDao,
                financialAndDraftDao = financialAndDraftDao,
                syncQueueDao = syncQueueDao,
                auditDao = auditDao,
            )

            val provisionalQty = productDao.getStockProjection(primaryItem.productId)?.provisionalAvailableQty ?: restoreQty
            return@withContext ReverseSaleOutcome.ReversedUnsyncedLocally(
                clientSaleId = sale.clientSaleId,
                restoredQty = restoreQty,
                provisionalRemainingQty = provisionalQty,
                reason = "Unsynced offline sale reversed locally; pending upload cancelled and $restoreQty unit(s) restored.",
            )
        }

        // Branch 2: Server-confirmed sale -> invoke atomic RPC online or queue reversal offline
        val rpcRequest = AndroidSaleReversalRpcRequest(
            clientSaleId = sale.clientSaleId,
            serverSaleId = sale.serverSaleId,
            productId = primaryItem.productId,
            quantity = restoreQty,
            totalAmount = sale.totalAmount,
            idempotencyKey = idempotencyKey,
            reason = cleanReason,
            paymentMethod = sale.paymentMethod,
            actorId = actorId,
            actorRole = actorRole.name,
            deviceId = deviceId,
            reversedAtIso = reversedAtIso,
        )

        if (isSupabaseConfigured && connectivityChecker.isOnline() && primaryItem.productId > 0L) {
            when (val rpcOutcome = reversalRpcGateway.submitReversal(rpcRequest)) {
                is SaleReversalRpcOutcome.CommittedOnline -> {
                    val onlineAudit = LocalAuditEventEntity(
                        auditId = "audit-${UUID.randomUUID()}",
                        actorId = actorId,
                        deviceId = deviceId,
                        operation = "SALE_REVERSED_ONLINE",
                        entityType = "SALE",
                        entityId = sale.clientSaleId,
                        beforeJson = beforeJson,
                        afterJson = JSONObject().apply {
                            put("is_reversed", true)
                            put("restored_qty", restoreQty)
                            put("remaining_qty", rpcOutcome.remainingQty)
                            put("already_reversed", rpcOutcome.alreadyReversed)
                            put("reason", cleanReason)
                        }.toString(),
                        reason = cleanReason,
                        syncStatus = SyncState.SERVER_CONFIRMED,
                        occurredAtEpochMs = now,
                    )
                    saleDao.commitOnlineSaleReversalAtomically(
                        clientSaleId = sale.clientSaleId,
                        productId = primaryItem.productId,
                        remainingServerQty = rpcOutcome.remainingQty,
                        reason = cleanReason,
                        reversedAtEpochMs = now,
                        financialAdjustment = financialAdjustment.copy(syncStatus = SyncState.SERVER_CONFIRMED),
                        auditEvent = onlineAudit,
                        productDao = productDao,
                        financialAndDraftDao = financialAndDraftDao,
                        auditDao = auditDao,
                    )
                    return@withContext ReverseSaleOutcome.ReversedOnline(
                        clientSaleId = sale.clientSaleId,
                        serverSaleId = sale.serverSaleId,
                        restoredQty = restoreQty,
                        remainingServerQty = rpcOutcome.remainingQty,
                        alreadyReversed = rpcOutcome.alreadyReversed,
                    )
                }

                is SaleReversalRpcOutcome.Rejected -> {
                    return@withContext ReverseSaleOutcome.Rejected(rpcOutcome.message)
                }

                is SaleReversalRpcOutcome.RpcMissing -> {
                    return@withContext queueOfflineReversal(
                        sale = sale,
                        productId = primaryItem.productId,
                        restoreQty = restoreQty,
                        cleanReason = cleanReason,
                        rpcRequest = rpcRequest,
                        beforeJson = beforeJson,
                        financialAdjustment = financialAdjustment,
                        nowEpochMs = now,
                        queueNote = "Reversed locally — deploy Supabase reverse_android_sale SQL migration to sync stock restoration.",
                    )
                }

                is SaleReversalRpcOutcome.AuthRequired -> {
                    return@withContext queueOfflineReversal(
                        sale = sale,
                        productId = primaryItem.productId,
                        restoreQty = restoreQty,
                        cleanReason = cleanReason,
                        rpcRequest = rpcRequest,
                        beforeJson = beforeJson,
                        financialAdjustment = financialAdjustment,
                        nowEpochMs = now,
                        queueNote = "Sign-in required (${rpcOutcome.message}) — reversal saved locally and queued for sync.",
                    )
                }

                is SaleReversalRpcOutcome.NetworkOrTransientError -> {
                    return@withContext queueOfflineReversal(
                        sale = sale,
                        productId = primaryItem.productId,
                        restoreQty = restoreQty,
                        cleanReason = cleanReason,
                        rpcRequest = rpcRequest,
                        beforeJson = beforeJson,
                        financialAdjustment = financialAdjustment,
                        nowEpochMs = now,
                        queueNote = "Network unavailable (${rpcOutcome.message}) — reversal saved locally and queued for sync.",
                    )
                }
            }
        }

        val offlineReason = if (!isSupabaseConfigured) {
            "Supabase not configured — reversal saved locally in Room queue."
        } else {
            "Offline mode — reversal recorded locally with provisional stock restoration and queued for sync."
        }
        queueOfflineReversal(
            sale = sale,
            productId = primaryItem.productId,
            restoreQty = restoreQty,
            cleanReason = cleanReason,
            rpcRequest = rpcRequest,
            beforeJson = beforeJson,
            financialAdjustment = financialAdjustment,
            nowEpochMs = now,
            queueNote = offlineReason,
        )
    }

    private suspend fun queueOfflineReversal(
        sale: com.emofah.agencies.data.local.entities.LocalSaleEntity,
        productId: Long,
        restoreQty: Int,
        cleanReason: String,
        rpcRequest: AndroidSaleReversalRpcRequest,
        beforeJson: String,
        financialAdjustment: PendingFinancialEventEntity,
        nowEpochMs: Long,
        queueNote: String,
    ): ReverseSaleOutcome.ReversedOfflineQueued {
        val syncPayload = JSONObject().apply {
            put("client_sale_id", rpcRequest.clientSaleId)
            put("server_sale_id", rpcRequest.serverSaleId ?: JSONObject.NULL)
            put("product_id", rpcRequest.productId)
            put("qty", rpcRequest.quantity)
            put("total", rpcRequest.totalAmount)
            put("idempotency_key", rpcRequest.idempotencyKey)
            put("reason", rpcRequest.reason)
            put("payment_method", rpcRequest.paymentMethod)
            put("actor_id", rpcRequest.actorId)
            put("actor_role", rpcRequest.actorRole)
            put("device_id", rpcRequest.deviceId)
            put("reversed_at", rpcRequest.reversedAtIso)
        }.toString()

        val syncEvent = SyncQueueEventEntity(
            eventId = "sync-rev-${sale.clientSaleId}",
            eventType = SyncEventType.SALE_REVERSAL,
            entityId = sale.clientSaleId,
            dependencyEventId = null,
            payloadJson = syncPayload,
            actorId = rpcRequest.actorId,
            deviceId = rpcRequest.deviceId,
            status = SyncQueueStatus.QUEUED,
            retryCount = 0,
            nextRetryAtEpochMs = nowEpochMs,
            createdAtEpochMs = nowEpochMs,
        )

        val audit = LocalAuditEventEntity(
            auditId = "audit-${UUID.randomUUID()}",
            actorId = rpcRequest.actorId,
            deviceId = rpcRequest.deviceId,
            operation = "SALE_REVERSED_OFFLINE_QUEUED",
            entityType = "SALE",
            entityId = sale.clientSaleId,
            beforeJson = beforeJson,
            afterJson = syncPayload,
            reason = cleanReason,
            syncStatus = SyncState.LOCAL_PENDING,
            occurredAtEpochMs = nowEpochMs,
        )

        saleDao.commitOfflineReversalOfSyncedSaleAtomically(
            clientSaleId = sale.clientSaleId,
            productId = productId,
            restoreQty = restoreQty,
            reason = cleanReason,
            reversedAtEpochMs = nowEpochMs,
            financialAdjustment = financialAdjustment,
            syncEvent = syncEvent,
            auditEvent = audit,
            productDao = productDao,
            financialAndDraftDao = financialAndDraftDao,
            syncQueueDao = syncQueueDao,
            auditDao = auditDao,
        )
        onOfflineEventQueued()

        val provisionalQty = productDao.getStockProjection(productId)?.provisionalAvailableQty ?: restoreQty
        return ReverseSaleOutcome.ReversedOfflineQueued(
            clientSaleId = sale.clientSaleId,
            queuedEventId = syncEvent.eventId,
            restoredQty = restoreQty,
            provisionalRemainingQty = provisionalQty,
            reason = queueNote,
        )
    }
}
