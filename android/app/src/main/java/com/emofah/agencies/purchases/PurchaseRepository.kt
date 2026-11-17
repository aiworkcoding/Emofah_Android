package com.emofah.agencies.purchases

import com.emofah.agencies.data.SupabaseConfig
import com.emofah.agencies.data.catalog.ProductMetadataExtractor
import com.emofah.agencies.data.catalog.SupabaseProductDto
import com.emofah.agencies.data.catalog.SupabaseProductRepository
import com.emofah.agencies.data.local.EmofahDatabase
import com.emofah.agencies.data.local.entities.LocalAuditEventEntity
import com.emofah.agencies.data.local.entities.LocalStockProjectionEntity
import com.emofah.agencies.data.local.entities.PendingFinancialEventEntity
import com.emofah.agencies.data.local.entities.ProductEntity
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
 * Section 2 (Part 2):
 * Submits human-confirmed physical purchase receipts to Supabase via `record_android_purchase`,
 * registers newly purchased resale products (purchase cost, quantity, selling price),
 * updates shared inventory, and supports offline queueing + duplicate prevention.
 */
class PurchaseRepository(
    private val database: EmofahDatabase,
    private val productRepository: SupabaseProductRepository,
    private val identityProvider: DeviceIdentityProvider,
    private val connectivityChecker: ConnectivityChecker,
    private val purchaseGateway: PurchaseSubmissionGateway = SupabasePurchaseClient(),
    private val isSupabaseConfigured: Boolean = SupabaseConfig.isConfigured,
    private val onOfflineEventQueued: () -> Unit = {},
) {
    private val productDao = database.productDao()
    private val syncQueueDao = database.syncQueueDao()
    private val financialDao = database.financialAndDraftDao()
    private val auditDao = database.auditDao()

    suspend fun submitConfirmedPurchase(
        draft: PurchaseReceiptDraft,
        recordFinancialOutflow: Boolean = true,
    ): PurchaseSubmissionResult = withContext(Dispatchers.IO) {
        val validation = ReceiptDraftValidator.validate(draft)
        if (!validation.isValid) {
            return@withContext PurchaseSubmissionResult.Rejected(
                validation.errors.firstOrNull() ?: "Purchase receipt is incomplete or unconfirmed."
            )
        }

        // Duplicate tap / duplicate queue check by stable clientPurchaseId
        val existingQueued = syncQueueDao.findPurchaseEventByClientPurchaseId(draft.clientPurchaseId)
        if (existingQueued != null) {
            return@withContext PurchaseSubmissionResult.DuplicateIgnored(
                clientPurchaseId = draft.clientPurchaseId,
                syncStatus = existingQueued.status,
            )
        }

        val nowMs = System.currentTimeMillis()
        val nowIso = Instant.ofEpochMilli(nowMs).toString()
        val actorId = identityProvider.getActorId()
        val deviceId = identityProvider.getDeviceId()
        val effectiveTotal = draft.effectiveTotal

        val request = AndroidPurchaseRpcRequest(
            clientPurchaseId = draft.clientPurchaseId,
            idempotencyKey = draft.idempotencyKey,
            supplierName = draft.supplierName.trim().takeIf { it.isNotBlank() },
            invoiceRef = draft.invoiceRef.trim().takeIf { it.isNotBlank() },
            receiptDateText = draft.receiptDateText.trim().takeIf { it.isNotBlank() },
            totalAmount = effectiveTotal,
            extractionSource = draft.extractionSource.name,
            isHumanConfirmed = true,
            items = draft.lines.map { line ->
                AndroidPurchaseLineRequest(
                    rawName = line.rawName.trim(),
                    itemCategory = line.itemCategory,
                    matchedProductId = if (line.itemCategory == PurchaseItemCategory.RESALE_PRODUCT && !line.registerAsNewProduct) {
                        line.matchedProductId
                    } else {
                        null
                    },
                    registerAsNewProduct = line.itemCategory == PurchaseItemCategory.RESALE_PRODUCT && line.registerAsNewProduct,
                    newProductSellPrice = if (line.itemCategory == PurchaseItemCategory.RESALE_PRODUCT && line.registerAsNewProduct) {
                        line.newProductSellPrice
                    } else {
                        null
                    },
                    newProductCategory = line.newProductCategory.ifBlank { "General" },
                    quantity = line.quantity,
                    unitCost = line.unitCost,
                    lineTotal = line.effectiveLineTotal,
                )
            },
            actorId = actorId,
            deviceId = deviceId,
            recordedAtIso = nowIso,
            recordFinancialOutflow = recordFinancialOutflow,
        )

        if (isSupabaseConfigured && connectivityChecker.isOnline()) {
            when (val outcome = purchaseGateway.submitPurchase(request)) {
                is PurchaseRpcOutcome.CommittedOnline -> {
                    val canonicalNewProducts = mutableListOf<ProductEntity>()
                    val existingIncrements = mutableListOf<Triple<Long, Int, Double>>()
                    var resaleUnits = 0
                    var newlyRegisteredCount = 0

                    for (ack in outcome.resolvedProducts) {
                        if (ack.itemCategory != PurchaseItemCategory.RESALE_PRODUCT) continue
                        val pid = ack.serverProductId ?: continue
                        resaleUnits += ack.qty
                        val serverQtyAfter = ack.serverQtyAfter ?: ack.qty
                        if (ack.registeredNewProduct) {
                            newlyRegisteredCount++
                            val identity = ProductMetadataExtractor.toProductIdentity(
                                SupabaseProductDto(
                                    id = pid,
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
                            canonicalNewProducts += ProductEntity(
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
                                cachedAtEpochMs = nowMs,
                            )
                        } else {
                            existingIncrements += Triple(pid, serverQtyAfter, ack.buyPrice)
                        }
                    }

                    val finEvent = buildLinkedFinancialOutflow(
                        draft = draft,
                        effectiveTotal = outcome.totalAmount.takeIf { it > 0.0 } ?: effectiveTotal,
                        syncStatus = SyncState.SERVER_CONFIRMED,
                        nowMs = nowMs,
                        enabled = recordFinancialOutflow,
                    )
                    val audit = LocalAuditEventEntity(
                        auditId = "audit-${UUID.randomUUID()}",
                        actorId = actorId,
                        deviceId = deviceId,
                        operation = "PURCHASE_CONFIRMED_ONLINE",
                        entityType = "PURCHASE",
                        entityId = draft.clientPurchaseId,
                        beforeJson = null,
                        afterJson = JSONObject().apply {
                            put("server_purchase_id", outcome.serverPurchaseId)
                            put("total_amount", outcome.totalAmount)
                            put("resale_units", resaleUnits)
                            put("newly_registered", newlyRegisteredCount)
                            put("already_committed", outcome.alreadyCommitted)
                        }.toString(),
                        reason = "Submitted human-confirmed purchase receipt to Supabase",
                        syncStatus = SyncState.SERVER_CONFIRMED,
                        occurredAtEpochMs = nowMs,
                    )

                    syncQueueDao.commitPurchaseOnlineAtomically(
                        updatedProducts = canonicalNewProducts,
                        existingProductIncrements = existingIncrements,
                        financialEvent = finEvent,
                        auditEvent = audit,
                        updatedAtEpochMs = nowMs,
                        productDao = productDao,
                        financialAndDraftDao = financialDao,
                        auditDao = auditDao,
                    )
                    // Rebuild on-device recognition index so newly registered resale items are scannable immediately
                    runCatching { productRepository.syncCatalogueAndRebuildIndex(forceNetwork = false) }

                    return@withContext PurchaseSubmissionResult.SuccessOnline(
                        serverPurchaseId = outcome.serverPurchaseId.ifBlank { draft.clientPurchaseId },
                        totalAmount = outcome.totalAmount.takeIf { it > 0.0 } ?: effectiveTotal,
                        resaleUnitsUpdated = resaleUnits,
                        newlyRegisteredCount = newlyRegisteredCount,
                        alreadyCommitted = outcome.alreadyCommitted,
                    )
                }

                is PurchaseRpcOutcome.Rejected -> {
                    return@withContext PurchaseSubmissionResult.Rejected(outcome.message)
                }

                is PurchaseRpcOutcome.AuthRequired -> {
                    return@withContext PurchaseSubmissionResult.Rejected(outcome.message)
                }

                is PurchaseRpcOutcome.RpcMissing -> {
                    return@withContext saveOfflinePurchase(
                        draft = draft,
                        baseRequest = request,
                        effectiveTotal = effectiveTotal,
                        recordFinancialOutflow = recordFinancialOutflow,
                        nowMs = nowMs,
                        fallbackReason = outcome.message,
                    )
                }

                is PurchaseRpcOutcome.NetworkOrTransientError -> {
                    return@withContext saveOfflinePurchase(
                        draft = draft,
                        baseRequest = request,
                        effectiveTotal = effectiveTotal,
                        recordFinancialOutflow = recordFinancialOutflow,
                        nowMs = nowMs,
                        fallbackReason = "Offline fallback: ${outcome.message}",
                    )
                }
            }
        }

        saveOfflinePurchase(
            draft = draft,
            baseRequest = request,
            effectiveTotal = effectiveTotal,
            recordFinancialOutflow = recordFinancialOutflow,
            nowMs = nowMs,
            fallbackReason = if (isSupabaseConfigured) {
                "Device offline; purchase queued for automatic sync."
            } else {
                "Supabase not configured; purchase saved locally."
            },
        )
    }

    private suspend fun saveOfflinePurchase(
        draft: PurchaseReceiptDraft,
        baseRequest: AndroidPurchaseRpcRequest,
        effectiveTotal: Double,
        recordFinancialOutflow: Boolean,
        nowMs: Long,
        fallbackReason: String,
    ): PurchaseSubmissionResult.SavedOffline {
        val minId = productDao.getMinProductId() ?: 0L
        var nextTempId = if (minId < 0L) minId - 1L else -1L

        val newTempProducts = mutableListOf<ProductEntity>()
        val newTempProjections = mutableListOf<LocalStockProjectionEntity>()
        val existingStockDeltas = mutableListOf<Triple<Long, Int, Double>>()
        val updatedItems = mutableListOf<AndroidPurchaseLineRequest>()
        var resaleUnitsQueued = 0
        var newlyRegisteredCount = 0

        for (line in baseRequest.items) {
            if (line.itemCategory == PurchaseItemCategory.RESALE_PRODUCT) {
                resaleUnitsQueued += line.quantity
                if (line.registerAsNewProduct) {
                    val tempId = nextTempId--
                    newlyRegisteredCount++
                    val identity = ProductMetadataExtractor.toProductIdentity(
                        SupabaseProductDto(
                            id = tempId,
                            name = line.rawName,
                            qty = line.quantity,
                            sellPrice = line.newProductSellPrice ?: 0.0,
                            buyPrice = line.unitCost,
                            imageUrl = null,
                            category = line.newProductCategory,
                            createdAt = null,
                            description = null,
                            discountPrice = 0.0,
                            isFeatured = false,
                        )
                    )
                    newTempProducts += ProductEntity(
                        id = tempId,
                        name = identity.name,
                        baseName = identity.baseName,
                        variantLabel = identity.variantLabel,
                        brandHint = identity.brandHint,
                        category = identity.category,
                        sellPrice = identity.sellPrice,
                        discountPrice = 0.0,
                        buyPrice = identity.buyPrice,
                        serverStockQty = 0,
                        imageUrl = null,
                        description = null,
                        isFeatured = false,
                        createdAt = null,
                        isArchived = false,
                        confirmedAliasesCsv = "",
                        cachedAtEpochMs = nowMs,
                    )
                    newTempProjections += LocalStockProjectionEntity(
                        productId = tempId,
                        serverQty = 0,
                        serverUpdatedAtEpochMs = nowMs,
                        pendingDeltaQty = line.quantity,
                        provisionalAvailableQty = line.quantity,
                    )
                    updatedItems += line.copy(localTempProductId = tempId)
                } else {
                    val pid = line.matchedProductId
                    if (pid != null && pid > 0L) {
                        existingStockDeltas += Triple(pid, line.quantity, line.unitCost)
                    }
                    updatedItems += line
                }
            } else {
                updatedItems += line
            }
        }

        val offlineReq = baseRequest.copy(items = updatedItems)
        val payloadJson = SyncQueuePolicy.serializePurchaseRpcRequest(offlineReq)
        val syncEvent = SyncQueueEventEntity(
            eventId = "sync-purch-${draft.clientPurchaseId}",
            eventType = SyncEventType.PURCHASE_RECEIPT,
            entityId = draft.clientPurchaseId,
            dependencyEventId = null,
            payloadJson = payloadJson,
            actorId = offlineReq.actorId,
            deviceId = offlineReq.deviceId,
            status = SyncQueueStatus.QUEUED,
            createdAtEpochMs = nowMs,
        )
        val finEvent = buildLinkedFinancialOutflow(
            draft = draft,
            effectiveTotal = effectiveTotal,
            syncStatus = SyncState.LOCAL_PENDING,
            nowMs = nowMs,
            enabled = recordFinancialOutflow,
        )
        val audit = LocalAuditEventEntity(
            auditId = "audit-${UUID.randomUUID()}",
            actorId = offlineReq.actorId,
            deviceId = offlineReq.deviceId,
            operation = "PURCHASE_QUEUED_OFFLINE",
            entityType = "PURCHASE",
            entityId = draft.clientPurchaseId,
            beforeJson = null,
            afterJson = payloadJson,
            reason = fallbackReason,
            syncStatus = SyncState.LOCAL_PENDING,
            occurredAtEpochMs = nowMs,
        )

        syncQueueDao.commitPurchaseOfflineAtomically(
            newTempProducts = newTempProducts,
            newTempProjections = newTempProjections,
            existingStockDeltas = existingStockDeltas,
            financialEvent = finEvent,
            syncEvent = syncEvent,
            auditEvent = audit,
            updatedAtEpochMs = nowMs,
            productDao = productDao,
            financialAndDraftDao = financialDao,
            auditDao = auditDao,
        )
        if (newTempProducts.isNotEmpty()) {
            runCatching { productRepository.syncCatalogueAndRebuildIndex(forceNetwork = false) }
        }
        onOfflineEventQueued()

        return PurchaseSubmissionResult.SavedOffline(
            clientPurchaseId = draft.clientPurchaseId,
            totalAmount = effectiveTotal,
            resaleUnitsQueued = resaleUnitsQueued,
            newlyRegisteredCount = newlyRegisteredCount,
            reason = fallbackReason,
        )
    }

    private fun buildLinkedFinancialOutflow(
        draft: PurchaseReceiptDraft,
        effectiveTotal: Double,
        syncStatus: String,
        nowMs: Long,
        enabled: Boolean,
    ): PendingFinancialEventEntity? {
        if (!enabled || effectiveTotal <= 0.0) return null
        return PendingFinancialEventEntity(
            eventId = "fin-purch-${draft.clientPurchaseId}",
            source = "ANDROID_RECEIPT",
            amount = effectiveTotal,
            currency = "KES",
            transactionReference = draft.invoiceRef.trim().takeIf { it.isNotBlank() },
            counterparty = draft.supplierName.trim().takeIf { it.isNotBlank() },
            direction = "OUTFLOW",
            rawEvidence = "Receipt ${draft.invoiceRef.ifBlank { draft.clientPurchaseId.take(8) }} · ${draft.lines.size} item(s)",
            parsedJson = JSONObject().apply {
                put("channel_label", "Purchase Receipt")
                put("classification_authority", "DESKTOP_MANAGED")
                put("client_purchase_id", draft.clientPurchaseId)
            }.toString(),
            provisionalCategory = "PENDING_DESKTOP_RULES",
            classificationConfidence = 0f,
            reviewStatus = "PENDING_DESKTOP_RULES",
            syncStatus = syncStatus,
            eventTimestampEpochMs = nowMs,
        )
    }
}
