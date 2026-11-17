package com.emofah.agencies.data.catalog

import android.content.Context
import android.graphics.BitmapFactory
import com.emofah.agencies.data.SupabaseConfig
import com.emofah.agencies.data.local.EmofahDatabase
import com.emofah.agencies.data.local.entities.LocalAuditEventEntity
import com.emofah.agencies.data.local.entities.LocalStockProjectionEntity
import com.emofah.agencies.data.local.entities.PendingProductDraftEntity
import com.emofah.agencies.data.local.entities.ProductEntity
import com.emofah.agencies.data.local.entities.ProductReferencePhotoEntity
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueueStatus
import com.emofah.agencies.sales.ConnectivityChecker
import com.emofah.agencies.sales.DeviceIdentityProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

sealed class RegisterProductOutcome {
    data class RegisteredOnline(
        val product: ProductIdentity,
        val matchedExistingDuplicate: Boolean,
        val message: String,
    ) : RegisterProductOutcome()

    data class SavedOfflineDraft(
        val draftId: String,
        val localTempProduct: ProductIdentity,
        val message: String,
    ) : RegisterProductOutcome()

    data class ValidationFailed(
        val validation: ProductDraftValidationResult,
    ) : RegisterProductOutcome()

    data class Rejected(
        val message: String,
    ) : RegisterProductOutcome()
}

/**
 * Stage 1.5 (Action 1.5.1):
 * Coordinates validated, human-confirmed product registration:
 * - Duplicate prevention against Room products + pending drafts.
 * - Online: registers via idempotent `register_android_product` RPC, caches in Room,
 *   attaches dHash reference photo, and updates RecognitionReferenceIndex immediately.
 * - Offline / transient error / undeployed RPC: saves durable `PendingProductDraftEntity`
 *   + temporary negative-ID `ProductEntity` + `SyncQueueEventEntity(PRODUCT_DRAFT)` so the
 *   operator can sell the item immediately and sync in dependency order when online.
 */
class ProductRegistrationRepository(
    private val context: Context,
    private val database: EmofahDatabase,
    private val repository: SupabaseProductRepository,
    private val identityProvider: DeviceIdentityProvider,
    private val connectivityChecker: ConnectivityChecker,
    private val registrationGateway: ProductRegistrationGateway = SupabaseProductRegistrationClient(),
    private val isSupabaseConfigured: Boolean = SupabaseConfig.isConfigured,
    private val onOfflineEventQueued: () -> Unit = {},
) {
    private val productDao = database.productDao()
    private val draftDao = database.financialAndDraftDao()
    private val syncQueueDao = database.syncQueueDao()
    private val auditDao = database.auditDao()

    suspend fun registerProduct(
        input: ProductDraftInput,
        allowNearDuplicateOverride: Boolean = false,
    ): RegisterProductOutcome = withContext(Dispatchers.IO) {
        val activeProducts = productDao.getActiveProducts().map(CatalogueDeltaCalculator::toProductIdentity)
        val pendingDrafts = draftDao.getPendingProductDrafts().map { draft ->
            ProductMetadataExtractor.toProductIdentity(
                SupabaseProductDto(
                    id = -1L,
                    name = draft.name,
                    qty = draft.initialQty,
                    sellPrice = draft.sellPrice,
                    buyPrice = draft.buyPrice,
                    imageUrl = null,
                    category = draft.category,
                    createdAt = null,
                    description = draft.description,
                    discountPrice = 0.0,
                    isFeatured = false,
                )
            )
        }

        val validation = ProductDraftValidator.validate(
            input = input,
            existingProducts = activeProducts + pendingDrafts,
            allowNearDuplicateOverride = allowNearDuplicateOverride,
        )
        if (!validation.isValid) {
            return@withContext RegisterProductOutcome.ValidationFailed(validation)
        }

        val actorId = identityProvider.getActorId()
        val deviceId = identityProvider.getDeviceId()
        val nowMs = System.currentTimeMillis()
        val stableLocalPhoto = persistStableReferenceCopy(input.draftId, input.preservedPhotoPath)
        val dHash = stableLocalPhoto?.let { repository.imageCache.computeDHashHex(it) }
        val dims = stableLocalPhoto?.let { readImageBounds(it) }

        val rpcRequest = RegisterProductRpcRequest(
            draftId = input.draftId,
            idempotencyKey = input.idempotencyKey,
            name = validation.canonicalFullName,
            category = input.category.trim().ifEmpty { "General" },
            sellPrice = validation.parsedSellPrice,
            buyPrice = validation.parsedBuyPrice,
            initialQty = validation.parsedInitialQty,
            description = input.description.trim().takeIf { it.isNotEmpty() },
            localPhotoPath = stableLocalPhoto,
            actorId = actorId,
            deviceId = deviceId,
        )

        if (isSupabaseConfigured && connectivityChecker.isOnline()) {
            when (val rpcOutcome = registrationGateway.registerProduct(rpcRequest)) {
                is ProductRegistrationRpcOutcome.CommittedOnline -> {
                    val serverId = rpcOutcome.serverProductId
                    val finalName = rpcOutcome.canonicalName.ifBlank { validation.canonicalFullName }
                    val finalQty = if (rpcOutcome.matchedExistingDuplicate) rpcOutcome.qty else validation.parsedInitialQty
                    val finalSell = if (rpcOutcome.matchedExistingDuplicate && rpcOutcome.sellPrice > 0.0) {
                        rpcOutcome.sellPrice
                    } else {
                        validation.parsedSellPrice
                    }

                    val identity = ProductMetadataExtractor.toProductIdentity(
                        SupabaseProductDto(
                            id = serverId,
                            name = finalName,
                            qty = finalQty,
                            sellPrice = finalSell,
                            buyPrice = validation.parsedBuyPrice,
                            imageUrl = rpcOutcome.imageUrl,
                            category = rpcRequest.category,
                            createdAt = null,
                            description = rpcRequest.description,
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
                        serverStockQty = identity.stockQty,
                        imageUrl = identity.imageUrl,
                        description = identity.description,
                        isFeatured = false,
                        createdAt = null,
                        isArchived = false,
                        confirmedAliasesCsv = input.ocrText.trim()
                            .takeIf { it.length in 3..64 && !identity.name.equals(it, ignoreCase = true) }
                            .orEmpty(),
                        cachedAtEpochMs = nowMs,
                    )

                    val projection = LocalStockProjectionEntity(
                        productId = identity.id,
                        serverQty = finalQty,
                        serverUpdatedAtEpochMs = nowMs,
                        pendingDeltaQty = 0,
                        provisionalAvailableQty = finalQty,
                    )

                    val refPhoto = stableLocalPhoto?.let { path ->
                        ProductReferencePhotoEntity(
                            id = "ref_${identity.id}_front",
                            productId = identity.id,
                            angle = ReferenceAngle.FRONT.name,
                            remoteUrl = rpcOutcome.imageUrl,
                            localCachePath = path,
                            widthPx = dims?.first,
                            heightPx = dims?.second,
                            visualSignatureHex = dHash,
                            isValid = true,
                            lastVerifiedAtEpochMs = nowMs,
                        )
                    }

                    val audit = LocalAuditEventEntity(
                        auditId = "audit-${UUID.randomUUID()}",
                        actorId = actorId,
                        deviceId = deviceId,
                        operation = if (rpcOutcome.matchedExistingDuplicate) {
                            "PRODUCT_REGISTRATION_LINKED_EXISTING"
                        } else {
                            "PRODUCT_REGISTERED_ONLINE"
                        },
                        entityType = "PRODUCT",
                        entityId = identity.id.toString(),
                        beforeJson = null,
                        afterJson = JSONObject().apply {
                            put("server_product_id", identity.id)
                            put("name", identity.name)
                            put("sell_price", identity.sellPrice)
                            put("qty", identity.stockQty)
                        }.toString(),
                        reason = "Staff confirmed quick product registration",
                        syncStatus = SyncState.SERVER_CONFIRMED,
                        occurredAtEpochMs = nowMs,
                    )

                    draftDao.commitOnlineProductRegistrationAtomically(
                        canonicalProduct = entity,
                        stockProjection = projection,
                        referencePhoto = refPhoto,
                        auditEvent = audit,
                        productDao = productDao,
                        auditDao = auditDao,
                    )

                    refreshRecognitionIndexFromRoom(
                        newProductId = identity.id,
                        dHash = dHash,
                    )

                    val msg = if (rpcOutcome.matchedExistingDuplicate) {
                        "Linked to existing catalogue item #${identity.id} (${identity.name}). Ready to sell."
                    } else {
                        "Registered \"${identity.name}\" (#${identity.id}) in Emofah catalogue."
                    }
                    return@withContext RegisterProductOutcome.RegisteredOnline(
                        product = identity,
                        matchedExistingDuplicate = rpcOutcome.matchedExistingDuplicate,
                        message = msg,
                    )
                }

                is ProductRegistrationRpcOutcome.Rejected -> {
                    return@withContext RegisterProductOutcome.Rejected(rpcOutcome.message)
                }

                is ProductRegistrationRpcOutcome.RpcMissing -> {
                    return@withContext saveOfflineDraft(
                        input = input,
                        validation = validation,
                        stableLocalPhoto = stableLocalPhoto,
                        dHash = dHash,
                        dims = dims,
                        actorId = actorId,
                        deviceId = deviceId,
                        nowMs = nowMs,
                        reasonNote = "Saved as local draft — deploy Supabase register_android_product SQL migration to sync to cloud.",
                    )
                }

                is ProductRegistrationRpcOutcome.AuthRequired -> {
                    return@withContext saveOfflineDraft(
                        input = input,
                        validation = validation,
                        stableLocalPhoto = stableLocalPhoto,
                        dHash = dHash,
                        dims = dims,
                        actorId = actorId,
                        deviceId = deviceId,
                        nowMs = nowMs,
                        reasonNote = "Saved as local draft — sign-in required before cloud sync.",
                    )
                }

                is ProductRegistrationRpcOutcome.NetworkOrTransientError -> {
                    return@withContext saveOfflineDraft(
                        input = input,
                        validation = validation,
                        stableLocalPhoto = stableLocalPhoto,
                        dHash = dHash,
                        dims = dims,
                        actorId = actorId,
                        deviceId = deviceId,
                        nowMs = nowMs,
                        reasonNote = "Saved offline as pending product draft. Ready to sell now and queued to sync when online.",
                    )
                }
            }
        }

        val offlineMsg = if (!isSupabaseConfigured) {
            "Saved locally in Room draft queue (Supabase not configured)."
        } else {
            "Device is offline — saved as local product draft. Ready to sell now and queued to sync when online."
        }
        saveOfflineDraft(
            input = input,
            validation = validation,
            stableLocalPhoto = stableLocalPhoto,
            dHash = dHash,
            dims = dims,
            actorId = actorId,
            deviceId = deviceId,
            nowMs = nowMs,
            reasonNote = offlineMsg,
        )
    }

    private suspend fun saveOfflineDraft(
        input: ProductDraftInput,
        validation: ProductDraftValidationResult,
        stableLocalPhoto: String?,
        dHash: String?,
        dims: Pair<Int, Int>?,
        actorId: String,
        deviceId: String,
        nowMs: Long,
        reasonNote: String,
    ): RegisterProductOutcome.SavedOfflineDraft {
        val minExisting = productDao.getMinProductId() ?: 0L
        val localTempId = if (minExisting < 0L) minExisting - 1L else -1L
        val cleanCategory = input.category.trim().ifEmpty { "General" }
        val cleanDesc = input.description.trim().takeIf { it.isNotEmpty() }

        val tempIdentity = ProductMetadataExtractor.toProductIdentity(
            SupabaseProductDto(
                id = localTempId,
                name = validation.canonicalFullName,
                qty = validation.parsedInitialQty,
                sellPrice = validation.parsedSellPrice,
                buyPrice = validation.parsedBuyPrice,
                imageUrl = null,
                category = cleanCategory,
                createdAt = null,
                description = cleanDesc,
                discountPrice = 0.0,
                isFeatured = false,
            )
        )

        val draftEntity = PendingProductDraftEntity(
            draftId = input.draftId,
            serverProductId = null,
            name = tempIdentity.name,
            category = cleanCategory,
            sellPrice = validation.parsedSellPrice,
            buyPrice = validation.parsedBuyPrice,
            initialQty = validation.parsedInitialQty,
            description = cleanDesc,
            localPhotoPath = stableLocalPhoto,
            ocrText = input.ocrText.trim().takeIf { it.isNotEmpty() },
            syncStatus = SyncState.LOCAL_PENDING,
            createdAtEpochMs = nowMs,
        )

        val tempProductEntity = ProductEntity(
            id = localTempId,
            name = tempIdentity.name,
            baseName = tempIdentity.baseName,
            variantLabel = tempIdentity.variantLabel,
            brandHint = tempIdentity.brandHint,
            category = cleanCategory,
            sellPrice = tempIdentity.sellPrice,
            discountPrice = 0.0,
            buyPrice = tempIdentity.buyPrice,
            serverStockQty = validation.parsedInitialQty,
            imageUrl = null,
            description = cleanDesc,
            isFeatured = false,
            createdAt = null,
            isArchived = false,
            confirmedAliasesCsv = input.ocrText.trim()
                .takeIf { it.length in 3..64 && !tempIdentity.name.equals(it, ignoreCase = true) }
                .orEmpty(),
            cachedAtEpochMs = nowMs,
        )

        val projection = LocalStockProjectionEntity(
            productId = localTempId,
            serverQty = validation.parsedInitialQty,
            serverUpdatedAtEpochMs = nowMs,
            pendingDeltaQty = 0,
            provisionalAvailableQty = validation.parsedInitialQty,
        )

        val refPhoto = stableLocalPhoto?.let { path ->
            ProductReferencePhotoEntity(
                id = "ref_${localTempId}_front",
                productId = localTempId,
                angle = ReferenceAngle.FRONT.name,
                remoteUrl = null,
                localCachePath = path,
                widthPx = dims?.first,
                heightPx = dims?.second,
                visualSignatureHex = dHash,
                isValid = true,
                lastVerifiedAtEpochMs = nowMs,
            )
        }

        val payloadJson = JSONObject().apply {
            put("draft_id", input.draftId)
            put("idempotency_key", input.idempotencyKey)
            put("local_temp_product_id", localTempId)
            put("name", tempIdentity.name)
            put("category", cleanCategory)
            put("sell_price", validation.parsedSellPrice)
            put("buy_price", validation.parsedBuyPrice)
            put("initial_qty", validation.parsedInitialQty)
            put("description", cleanDesc ?: JSONObject.NULL)
            put("local_photo_path", stableLocalPhoto ?: JSONObject.NULL)
            put("ocr_text", input.ocrText.trim().takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
            put("actor_id", actorId)
            put("device_id", deviceId)
        }.toString()

        val syncEvent = SyncQueueEventEntity(
            eventId = "sync-draft-${input.draftId}",
            eventType = SyncEventType.PRODUCT_DRAFT,
            entityId = input.draftId,
            dependencyEventId = null,
            payloadJson = payloadJson,
            actorId = actorId,
            deviceId = deviceId,
            status = SyncQueueStatus.QUEUED,
            retryCount = 0,
            nextRetryAtEpochMs = nowMs,
            createdAtEpochMs = nowMs,
        )

        val auditEvent = LocalAuditEventEntity(
            auditId = "audit-${UUID.randomUUID()}",
            actorId = actorId,
            deviceId = deviceId,
            operation = "PRODUCT_DRAFT_CREATED_OFFLINE",
            entityType = "PRODUCT_DRAFT",
            entityId = input.draftId,
            beforeJson = null,
            afterJson = payloadJson,
            reason = "Staff confirmed offline product draft registration",
            syncStatus = SyncState.LOCAL_PENDING,
            occurredAtEpochMs = nowMs,
        )

        draftDao.commitOfflineProductDraftAtomically(
            draft = draftEntity,
            tempProduct = tempProductEntity,
            stockProjection = projection,
            referencePhoto = refPhoto,
            syncEvent = syncEvent,
            auditEvent = auditEvent,
            productDao = productDao,
            syncQueueDao = syncQueueDao,
            auditDao = auditDao,
        )

        refreshRecognitionIndexFromRoom(
            newProductId = localTempId,
            dHash = dHash,
        )
        onOfflineEventQueued()

        return RegisterProductOutcome.SavedOfflineDraft(
            draftId = input.draftId,
            localTempProduct = tempIdentity,
            message = reasonNote,
        )
    }

    private suspend fun refreshRecognitionIndexFromRoom(
        newProductId: Long,
        dHash: String?,
    ) {
        val allActive = productDao.getActiveProducts()
        val identities = allActive.map(CatalogueDeltaCalculator::toProductIdentity)
        for (entity in allActive) {
            for (alias in CatalogueDeltaCalculator.parseAliasesCsv(entity.confirmedAliasesCsv)) {
                repository.referenceIndex.addConfirmedAlias(entity.id, alias)
            }
        }
        repository.referenceIndex.rebuildFromProducts(identities)
        if (!dHash.isNullOrBlank()) {
            repository.referenceIndex.attachVisualSignature(newProductId, dHash)
        }
    }

    private fun persistStableReferenceCopy(draftId: String, sourcePath: String?): String? {
        if (sourcePath.isNullOrBlank()) return null
        val srcFile = File(sourcePath)
        if (!srcFile.exists() || srcFile.length() == 0L) return null
        return try {
            val refsDir = File(context.filesDir, "reference_images").apply { mkdirs() }
            val safeId = draftId.replace(Regex("""[^a-zA-Z0-9_-]"""), "_")
            val destFile = File(refsDir, "draft_ref_$safeId.jpg")
            if (srcFile.absolutePath != destFile.absolutePath) {
                srcFile.copyTo(destFile, overwrite = true)
            }
            destFile.absolutePath
        } catch (_: Exception) {
            sourcePath
        }
    }

    private fun readImageBounds(path: String): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        return if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
    }
}
