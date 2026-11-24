package com.emofah.agencies.services

import com.emofah.agencies.data.SupabaseConfig
import com.emofah.agencies.data.local.EmofahDatabase
import com.emofah.agencies.data.local.entities.LocalAuditEventEntity
import com.emofah.agencies.data.local.entities.PendingFinancialEventEntity
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueuePolicy
import com.emofah.agencies.data.sync.SyncQueueStatus
import com.emofah.agencies.sales.ConnectivityChecker
import com.emofah.agencies.sales.DeviceIdentityProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/**
 * Section 4:
 * Records service operations (Printing, Photocopying, Scanning, Internet browsing,
 * E-Government assistance), deducting optional tracked consumable stock via Supabase
 * or queueing offline in Room with duplicate prevention.
 */
class ServiceOperationRepository(
    private val database: EmofahDatabase,
    private val identityProvider: DeviceIdentityProvider,
    private val connectivityChecker: ConnectivityChecker,
    private val serviceGateway: ServiceOperationGateway = SupabaseServiceOperationClient(),
    private val isSupabaseConfigured: Boolean = SupabaseConfig.isConfigured,
    private val onOfflineEventQueued: () -> Unit = {},
) {
    private val productDao = database.productDao()
    private val syncQueueDao = database.syncQueueDao()
    private val financialDao = database.financialAndDraftDao()
    private val auditDao = database.auditDao()

    suspend fun recordServiceOperation(
        draft: ServiceOperationDraft,
    ): ServiceOperationResult = withContext(Dispatchers.IO) {
        val validation = ServiceOperationValidator.validate(draft)
        if (!validation.isValid) {
            return@withContext ServiceOperationResult.Rejected(
                validation.errors.firstOrNull() ?: "Invalid service operation details."
            )
        }

        val existingQueued = syncQueueDao.findServiceOperationEventByClientId(draft.clientServiceId)
        if (existingQueued != null) {
            return@withContext ServiceOperationResult.DuplicateIgnored(
                clientServiceId = draft.clientServiceId,
                syncStatus = existingQueued.status,
            )
        }

        val nowMs = System.currentTimeMillis()
        val nowIso = Instant.ofEpochMilli(nowMs).toString()
        val actorId = identityProvider.getActorId()
        val deviceId = identityProvider.getDeviceId()
        val effectiveRevenue = draft.effectiveRevenue

        val rpcRequest = AndroidServiceRpcRequest(
            clientServiceId = draft.clientServiceId,
            idempotencyKey = draft.idempotencyKey,
            serviceType = draft.serviceType,
            serviceLabel = draft.serviceType.label,
            quantityOrDuration = draft.quantityOrDuration,
            unitLabel = draft.serviceType.unitLabel,
            unitPrice = draft.unitPrice,
            totalRevenue = effectiveRevenue,
            paymentMethod = draft.paymentMethod.name,
            customerNote = draft.customerNote.trim().takeIf { it.isNotBlank() },
            consumables = draft.consumables.mapNotNull { cons ->
                val cleanName = cons.consumableName.trim()
                if (cleanName.length < 2) return@mapNotNull null
                ServiceConsumableRpcLine(
                    consumableName = cleanName,
                    usageQty = cons.usageQty.coerceAtLeast(0.0),
                    usageUnit = cons.usageUnit.ifBlank { "units" },
                    linkedProductId = cons.linkedProductId?.takeIf { it > 0L },
                    stockDeductQty = if (cons.linkedProductId != null && cons.linkedProductId > 0L) {
                        cons.stockDeductQty.coerceAtLeast(0)
                    } else {
                        0
                    },
                )
            },
            actorId = actorId,
            deviceId = deviceId,
            performedAtIso = nowIso,
            discountAmount = draft.discountAmount.coerceAtLeast(0.0),
        )

        if (isSupabaseConfigured && connectivityChecker.isOnline()) {
            when (val outcome = serviceGateway.submitServiceOperation(rpcRequest)) {
                is ServiceRpcOutcome.CommittedOnline -> {
                    val serverUpdates = outcome.updatedProducts.mapNotNull { ack ->
                        ack.serverQtyAfter?.let { ack.productId to it }
                    }
                    val finEvent = buildServiceFinancialEvent(
                        draft = draft,
                        revenue = outcome.totalRevenue.takeIf { it > 0.0 } ?: effectiveRevenue,
                        syncStatus = SyncState.SERVER_CONFIRMED,
                        nowMs = nowMs,
                    )
                    val audit = LocalAuditEventEntity(
                        auditId = "audit-${UUID.randomUUID()}",
                        actorId = actorId,
                        deviceId = deviceId,
                        operation = "SERVICE_OPERATION_CONFIRMED_ONLINE",
                        entityType = "SERVICE_OPERATION",
                        entityId = draft.clientServiceId,
                        beforeJson = null,
                        afterJson = JSONObject().apply {
                            put("server_service_id", outcome.serverServiceId)
                            put("service_type", draft.serviceType.code)
                            put("total_revenue", outcome.totalRevenue)
                            put("stock_deducted_items", outcome.updatedProducts.size)
                            put("already_committed", outcome.alreadyCommitted)
                        }.toString(),
                        reason = "Recorded ${draft.serviceType.label} online via record_android_service_operation",
                        syncStatus = SyncState.SERVER_CONFIRMED,
                        occurredAtEpochMs = nowMs,
                    )

                    syncQueueDao.commitServiceOperationOnlineAtomically(
                        updatedServerStock = serverUpdates,
                        financialEvent = finEvent,
                        auditEvent = audit,
                        updatedAtEpochMs = nowMs,
                        productDao = productDao,
                        financialAndDraftDao = financialDao,
                        auditDao = auditDao,
                    )

                    return@withContext ServiceOperationResult.SuccessOnline(
                        serverServiceId = outcome.serverServiceId.ifBlank { draft.clientServiceId },
                        serviceLabel = draft.serviceType.label,
                        totalRevenue = outcome.totalRevenue.takeIf { it > 0.0 } ?: effectiveRevenue,
                        stockDeductedCount = outcome.updatedProducts.sumOf { it.qtyDeducted },
                        alreadyCommitted = outcome.alreadyCommitted,
                    )
                }

                is ServiceRpcOutcome.Rejected -> {
                    return@withContext ServiceOperationResult.Rejected(outcome.message)
                }

                is ServiceRpcOutcome.AuthRequired -> {
                    return@withContext ServiceOperationResult.Rejected(outcome.message)
                }

                is ServiceRpcOutcome.RpcMissing -> {
                    return@withContext saveOfflineServiceOperation(
                        draft = draft,
                        rpcRequest = rpcRequest,
                        nowMs = nowMs,
                        fallbackReason = outcome.message,
                    )
                }

                is ServiceRpcOutcome.NetworkOrTransientError -> {
                    return@withContext saveOfflineServiceOperation(
                        draft = draft,
                        rpcRequest = rpcRequest,
                        nowMs = nowMs,
                        fallbackReason = "Offline fallback: ${outcome.message}",
                    )
                }
            }
        }

        saveOfflineServiceOperation(
            draft = draft,
            rpcRequest = rpcRequest,
            nowMs = nowMs,
            fallbackReason = if (isSupabaseConfigured) {
                "Device offline; service operation queued for automatic sync."
            } else {
                "Supabase not configured; service operation saved locally."
            },
        )
    }

    private suspend fun saveOfflineServiceOperation(
        draft: ServiceOperationDraft,
        rpcRequest: AndroidServiceRpcRequest,
        nowMs: Long,
        fallbackReason: String,
    ): ServiceOperationResult.SavedOffline {
        val stockDeductions = rpcRequest.consumables
            .filter { it.linkedProductId != null && it.linkedProductId > 0L && it.stockDeductQty > 0 }
            .map { it.linkedProductId!! to it.stockDeductQty }

        val payloadJson = SyncQueuePolicy.serializeServiceOperationRpcRequest(rpcRequest)
        val syncEvent = SyncQueueEventEntity(
            eventId = "sync-svc-${draft.clientServiceId}",
            eventType = SyncEventType.SERVICE_OPERATION,
            entityId = draft.clientServiceId,
            dependencyEventId = null,
            payloadJson = payloadJson,
            actorId = rpcRequest.actorId,
            deviceId = rpcRequest.deviceId,
            status = SyncQueueStatus.QUEUED,
            createdAtEpochMs = nowMs,
        )
        val finEvent = buildServiceFinancialEvent(
            draft = draft,
            revenue = rpcRequest.totalRevenue,
            syncStatus = SyncState.LOCAL_PENDING,
            nowMs = nowMs,
        )
        val audit = LocalAuditEventEntity(
            auditId = "audit-${UUID.randomUUID()}",
            actorId = rpcRequest.actorId,
            deviceId = rpcRequest.deviceId,
            operation = "SERVICE_OPERATION_QUEUED_OFFLINE",
            entityType = "SERVICE_OPERATION",
            entityId = draft.clientServiceId,
            beforeJson = null,
            afterJson = payloadJson,
            reason = fallbackReason,
            syncStatus = SyncState.LOCAL_PENDING,
            occurredAtEpochMs = nowMs,
        )

        syncQueueDao.commitServiceOperationOfflineAtomically(
            stockDeductions = stockDeductions,
            financialEvent = finEvent,
            syncEvent = syncEvent,
            auditEvent = audit,
            updatedAtEpochMs = nowMs,
            productDao = productDao,
            financialAndDraftDao = financialDao,
            auditDao = auditDao,
        )
        onOfflineEventQueued()

        return ServiceOperationResult.SavedOffline(
            clientServiceId = draft.clientServiceId,
            serviceLabel = draft.serviceType.label,
            totalRevenue = rpcRequest.totalRevenue,
            stockDeductedCount = stockDeductions.sumOf { it.second },
            reason = fallbackReason,
        )
    }

    private fun buildServiceFinancialEvent(
        draft: ServiceOperationDraft,
        revenue: Double,
        syncStatus: String,
        nowMs: Long,
    ): PendingFinancialEventEntity? {
        if (revenue <= 0.0) return null
        val consumablesSummary = draft.consumables
            .filter { it.consumableName.isNotBlank() }
            .joinToString(", ") { "${it.consumableName} (${it.usageQty.toInt()} ${it.usageUnit})" }
        return PendingFinancialEventEntity(
            eventId = "fin-svc-${draft.clientServiceId}",
            source = "ANDROID_SERVICE",
            amount = revenue,
            currency = "KES",
            transactionReference = null,
            counterparty = draft.serviceType.label,
            direction = "INFLOW",
            rawEvidence = buildString {
                append("${draft.serviceType.label}: ${draft.quantityOrDuration} ${draft.serviceType.unitLabel} · ${draft.paymentMethod.label}")
                if (consumablesSummary.isNotBlank()) append(" · Used: $consumablesSummary")
            },
            parsedJson = JSONObject().apply {
                put("channel_label", "Service: ${draft.serviceType.label}")
                put("service_type", draft.serviceType.code)
                put("quantity_or_duration", draft.quantityOrDuration)
                put("unit_label", draft.serviceType.unitLabel)
                put("unit_price", draft.unitPrice)
                put("discount_amount", draft.discountAmount.coerceAtLeast(0.0))
                put("payment_method", draft.paymentMethod.name)
                draft.customerNote.trim().takeIf { it.isNotBlank() }?.let {
                    put("customer_note", it)
                }
                put("classification_authority", "DESKTOP_MANAGED")
            }.toString(),
            provisionalCategory = "PENDING_DESKTOP_RULES",
            classificationConfidence = 0f,
            reviewStatus = "PENDING_DESKTOP_RULES",
            syncStatus = syncStatus,
            eventTimestampEpochMs = nowMs,
            classificationStatus = "BUSINESS_SERVICE",
            linkedClientServiceId = draft.clientServiceId,
            linkedProductName = draft.serviceType.label,
            linkedQuantity = draft.quantityOrDuration,
            excludesFromBalance = false,
        )
    }
}
