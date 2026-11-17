package com.emofah.agencies.recognition

import android.content.Context
import android.graphics.BitmapFactory
import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.catalog.ReferenceAngle
import com.emofah.agencies.data.catalog.SupabaseProductRepository
import com.emofah.agencies.data.local.EmofahDatabase
import com.emofah.agencies.data.local.entities.LocalAuditEventEntity
import com.emofah.agencies.data.local.entities.ProductReferencePhotoEntity
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueuePolicy
import com.emofah.agencies.data.sync.SyncQueueStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Stage 1.6 (Action 1.6.1):
 * Coordinates human-confirmed recognition learning:
 * - Records original suggestion vs actual selected product.
 * - Associates captured image & perceptual dHash with the product when permitted.
 * - Adds cleaned OCR/cloud aliases into Room + RecognitionReferenceIndex immediately.
 * - Preserves an immutable LocalAuditEventEntity trail.
 * - Enqueues `RECOGNITION_CORRECTION` into `SyncQueueEventEntity` (respecting parent
 *   `PRODUCT_DRAFT` dependencies when the product was created offline).
 */
class RecognitionLearningRepository(
    private val context: Context,
    private val database: EmofahDatabase,
    private val repository: SupabaseProductRepository,
    private val onCorrectionQueued: () -> Unit = {},
) {
    private val productDao = database.productDao()
    private val syncQueueDao = database.syncQueueDao()
    private val auditDao = database.auditDao()

    suspend fun recordConfirmedLearning(
        outcome: RecognitionOutcome?,
        confirmedProduct: ProductIdentity,
        selectionOrigin: RecognitionSelectionOrigin,
        allowImageAssociation: Boolean,
        isHumanConfirmed: Boolean,
        actorId: String,
        deviceId: String,
    ): RecognitionLearningPlan = withContext(Dispatchers.IO) {
        val plan = RecognitionLearningPolicy.buildPlan(
            outcome = outcome,
            confirmedProduct = confirmedProduct,
            selectionOrigin = selectionOrigin,
            allowImageAssociation = allowImageAssociation,
            isHumanConfirmed = isHumanConfirmed,
        )

        if (!plan.shouldRecordLearning || outcome == null) {
            return@withContext plan
        }

        val nowMs = System.currentTimeMillis()
        val correctionId = "recog-${UUID.randomUUID()}"
        val idempotencyKey = "recog-idem-$correctionId"

        // 1. Associate learned packaging alias locally & in RecognitionReferenceIndex
        if (!plan.learnedAlias.isNullOrBlank()) {
            repository.saveConfirmedAlias(confirmedProduct.id, plan.learnedAlias)
        }

        // 2. Associate captured image & perceptual dHash when permitted
        var stableLocalPhotoPath: String? = null
        var finalDHash = plan.visualDHashHex
        if (plan.shouldAssociateImage && outcome.preservedPhotoPath.isNotBlank()) {
            stableLocalPhotoPath = persistLearnedReferencePhoto(
                productId = confirmedProduct.id,
                correctionId = correctionId,
                sourcePath = outcome.preservedPhotoPath,
            )
            if (stableLocalPhotoPath != null) {
                if (finalDHash.isNullOrBlank()) {
                    finalDHash = repository.imageCache.computeDHashHex(stableLocalPhotoPath)
                }
                val dims = readImageBounds(stableLocalPhotoPath)
                productDao.upsertReferencePhoto(
                    ProductReferencePhotoEntity(
                        id = "ref_${confirmedProduct.id}_learned",
                        productId = confirmedProduct.id,
                        angle = ReferenceAngle.PACKAGE.name,
                        remoteUrl = confirmedProduct.imageUrl,
                        localCachePath = stableLocalPhotoPath,
                        widthPx = dims?.first,
                        heightPx = dims?.second,
                        visualSignatureHex = finalDHash,
                        isValid = true,
                        lastVerifiedAtEpochMs = nowMs,
                    )
                )
            }
        }

        if (!finalDHash.isNullOrBlank()) {
            repository.referenceIndex.attachVisualSignature(confirmedProduct.id, finalDHash)
        }

        // 3. Preserve audit trail of recognition confirmation / correction
        val beforeJson = JSONObject().apply {
            put("suggested_product_id", plan.originalSuggestedProductId ?: JSONObject.NULL)
            put("suggested_product_name", plan.originalSuggestedProductName ?: JSONObject.NULL)
            put("match_status", plan.matchStatus.name)
            put("used_cloud_assist", plan.usedCloudAssist)
            put("ocr_snippet", plan.ocrSnippet ?: JSONObject.NULL)
        }.toString()

        val afterJson = JSONObject().apply {
            put("confirmed_product_id", plan.confirmedProductId)
            put("confirmed_product_name", plan.confirmedProductName)
            put("selection_kind", plan.selectionKind.name)
            put("was_corrected", plan.wasCorrected)
            put("learned_alias", plan.learnedAlias ?: JSONObject.NULL)
            put("image_associated", stableLocalPhotoPath != null)
            put("visual_dhash_hex", finalDHash ?: JSONObject.NULL)
        }.toString()

        runCatching {
            auditDao.appendAudit(
                LocalAuditEventEntity(
                    auditId = "audit-${UUID.randomUUID()}",
                    actorId = actorId,
                    deviceId = deviceId,
                    operation = if (plan.wasCorrected) {
                        "RECOGNITION_CORRECTION_LEARNED"
                    } else {
                        "RECOGNITION_CONFIRMED_LEARNED"
                    },
                    entityType = "PRODUCT",
                    entityId = confirmedProduct.id.toString(),
                    beforeJson = beforeJson,
                    afterJson = afterJson,
                    reason = "Staff confirmed product identity (${plan.selectionKind.name})",
                    syncStatus = SyncState.LOCAL_PENDING,
                    occurredAtEpochMs = nowMs,
                )
            )
        }

        // 4. Queue approved reference update for shared Supabase synchronisation
        if (plan.shouldQueueServerSync) {
            val parentDraftEventId = if (confirmedProduct.id < 0L) {
                syncQueueDao.getEventsByType(SyncEventType.PRODUCT_DRAFT)
                    .firstOrNull { evt ->
                        SyncQueuePolicy.parseProductDraftPayload(evt)?.localTempProductId == confirmedProduct.id
                    }
                    ?.eventId
            } else {
                null
            }

            val payloadJson = JSONObject().apply {
                put("correction_id", correctionId)
                put("idempotency_key", idempotencyKey)
                put("suggested_product_id", plan.originalSuggestedProductId ?: JSONObject.NULL)
                put("suggested_product_name", plan.originalSuggestedProductName ?: JSONObject.NULL)
                put("confirmed_product_id", plan.confirmedProductId)
                put("match_status", plan.matchStatus.name)
                put("selection_kind", plan.selectionKind.name)
                put("used_cloud_assist", plan.usedCloudAssist)
                put("learned_alias", plan.learnedAlias ?: JSONObject.NULL)
                put("ocr_snippet", plan.ocrSnippet ?: JSONObject.NULL)
                put("visual_dhash_hex", finalDHash ?: JSONObject.NULL)
                put("local_photo_path", stableLocalPhotoPath ?: JSONObject.NULL)
                put("actor_id", actorId)
                put("device_id", deviceId)
            }.toString()

            syncQueueDao.upsertEvent(
                SyncQueueEventEntity(
                    eventId = "sync-$correctionId",
                    eventType = SyncEventType.RECOGNITION_CORRECTION,
                    entityId = correctionId,
                    dependencyEventId = parentDraftEventId,
                    payloadJson = payloadJson,
                    actorId = actorId,
                    deviceId = deviceId,
                    status = SyncQueueStatus.QUEUED,
                    retryCount = 0,
                    nextRetryAtEpochMs = nowMs,
                    createdAtEpochMs = nowMs,
                )
            )
            onCorrectionQueued()
        }

        plan
    }

    private fun persistLearnedReferencePhoto(
        productId: Long,
        correctionId: String,
        sourcePath: String,
    ): String? {
        val srcFile = File(sourcePath)
        if (!srcFile.exists() || srcFile.length() == 0L) return null
        return try {
            val refsDir = File(context.filesDir, "reference_images").apply { mkdirs() }
            val safeId = correctionId.replace(Regex("""[^a-zA-Z0-9_-]"""), "_")
            val destFile = File(refsDir, "learned_${productId}_$safeId.jpg")
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
