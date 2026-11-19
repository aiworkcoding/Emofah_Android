package com.emofah.agencies.recognition

import android.content.Context
import com.emofah.agencies.camera.ImageQualityMath
import com.emofah.agencies.camera.PreparedCaptureResult
import com.emofah.agencies.data.SupabaseConfig
import com.emofah.agencies.data.catalog.CatalogueDeltaCalculator
import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.catalog.SupabaseProductRepository
import com.emofah.agencies.data.local.EmofahDatabase
import com.emofah.agencies.sales.ConnectivityChecker
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Stage 1.3 + Stage 1.4 + Stage 1.6:
 * Runs 100% offline on-device recognition first (ML Kit OCR + Room + dHash).
 * When local recognition is uncertain/unidentified and online, optionally falls back
 * to cloud-assisted recognition with strict catalogue ID validation, cost control,
 * and confirmed recognition learning + correction sync.
 */
class OnDeviceProductRecognizer(
    private val context: Context,
    private val database: EmofahDatabase,
    private val repository: SupabaseProductRepository,
    private val connectivityChecker: ConnectivityChecker? = null,
    private val telemetryStore: RecognitionTelemetryStore = RecognitionTelemetryStore(context),
    private val cloudGateway: CloudRecognitionGateway = CloudProductRecognitionClient(telemetryStore),
    private val onCorrectionQueued: () -> Unit = {},
) {
    private val learningRepository by lazy {
        RecognitionLearningRepository(
            context = context,
            database = database,
            repository = repository,
            onCorrectionQueued = onCorrectionQueued,
        )
    }

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognizeOffline(capture: PreparedCaptureResult): RecognitionOutcome =
        withContext(Dispatchers.IO) {
            repository.ensureDiskIndexLoaded()
            val ocrText = extractOcrText(capture.recognitionCopyPath)

            val productDao = database.productDao()
            val activeEntities = productDao.getActiveProducts()
            val productsById = activeEntities
                .map(CatalogueDeltaCalculator::toProductIdentity)
                .associateBy { it.id }

            if (repository.referenceIndex.getActiveEntries().isEmpty() && productsById.isNotEmpty()) {
                for (entity in activeEntities) {
                    for (alias in CatalogueDeltaCalculator.parseAliasesCsv(entity.confirmedAliasesCsv)) {
                        repository.referenceIndex.addConfirmedAlias(entity.id, alias)
                    }
                    val sig = productDao.getReferencePhotosForProduct(entity.id)
                        .firstOrNull { it.isValid && !it.visualSignatureHex.isNullOrBlank() }
                        ?.visualSignatureHex
                    if (!sig.isNullOrBlank()) {
                        repository.referenceIndex.attachVisualSignature(entity.id, sig)
                    }
                }
                repository.referenceIndex.rebuildFromProducts(productsById.values.toList())
            }

            val projectionsById = productDao.getAllStockProjections().associateBy { it.productId }
            val cachedPhotosById = loadCachedPhotoPaths(activeEntities.map { it.id })

            val localOutcome = RecognitionRanker.rankCandidates(
                captureId = capture.captureId,
                preservedPhotoPath = capture.originalPath,
                captureDHashHex = capture.dHashHex,
                ocrText = ocrText,
                activeEntries = repository.referenceIndex.getActiveEntries(),
                productsById = productsById,
                projectionsById = projectionsById,
                cachedPhotoPathsById = cachedPhotosById,
            )
            telemetryStore.recordLocalOutcome(localOutcome.status)

            val initialCloudState = when (localOutcome.status) {
                MatchConfidenceStatus.STRONG_MATCH -> CloudFallbackState.NOT_NEEDED
                MatchConfidenceStatus.AMBIGUOUS_ALTERNATIVES,
                MatchConfidenceStatus.UNIDENTIFIED,
                -> if (connectivityChecker?.isOnline() == false) {
                    CloudFallbackState.OFFLINE_SKIPPED
                } else {
                    CloudFallbackState.READY_TO_TRY
                }
            }
            localOutcome.copy(cloudFallbackState = initialCloudState)
        }

    /**
     * Stage 1.4 (Actions 1.4.1 & 1.4.2):
     * Evaluates local-first cost policy and connectivity before calling the cloud service.
     * Validates all returned product IDs against Room's canonical catalogue and never
     * discards the preserved capture photo if the cloud service fails.
     */
    suspend fun recognizeWithCloudFallback(
        capture: PreparedCaptureResult,
        localOutcome: RecognitionOutcome,
        forceRequested: Boolean = false,
    ): RecognitionOutcome = withContext(Dispatchers.IO) {
        val productDao = database.productDao()
        val activeEntities = productDao.getActiveProducts()
        val productsById = activeEntities
            .map(CatalogueDeltaCalculator::toProductIdentity)
            .associateBy { it.id }

        val decision = CloudCostControlPolicy.evaluateAutoFallback(
            localOutcome = localOutcome,
            isOnline = connectivityChecker?.isOnline() ?: false,
            hasConfiguredEndpoint = SupabaseConfig.cloudRecognitionEndpoint.isNotBlank(),
            hasCachedProducts = productsById.isNotEmpty(),
            cloudCallsToday = telemetryStore.getCloudCallsToday(),
            forceRequested = forceRequested,
        )

        if (decision is CloudEligibilityDecision.Skip) {
            return@withContext localOutcome.copy(
                cloudFallbackState = decision.state,
                cloudNote = decision.message,
            )
        }

        val projectionsById = productDao.getAllStockProjections().associateBy { it.productId }
        val cachedPhotosById = loadCachedPhotoPaths(activeEntities.map { it.id })

        when (
            val fetch = cloudGateway.recognize(
                capture = capture,
                ocrText = localOutcome.extractedOcrText,
                catalogueProducts = productsById.values.toList(),
            )
        ) {
            is CloudRecognitionFetchResult.Unavailable -> {
                localOutcome.copy(
                    cloudFallbackState = CloudFallbackState.UNAVAILABLE_OR_FAILED,
                    cloudNote = "${fetch.reason} Your photo is preserved — choose manually or register product.",
                )
            }

            is CloudRecognitionFetchResult.Success -> {
                val validation = CloudRecognitionValidator.validateSuggestions(
                    suggestions = fetch.suggestions,
                    activeProductsById = productsById,
                    projectionsById = projectionsById,
                    cachedPhotoPathsById = cachedPhotosById,
                    extractedLabel = fetch.extractedLabel,
                )
                telemetryStore.recordCloudValidationResult(
                    validatedCount = validation.validatedCandidates.size,
                    rejectedUnknownCount = validation.rejectedUnknownIds.size,
                )

                val fallbackState = when {
                    validation.validatedCandidates.isEmpty() -> CloudFallbackState.NO_CATALOGUE_MATCH
                    fetch.fromCache -> CloudFallbackState.CACHE_HIT
                    else -> CloudFallbackState.MATCHES_FOUND
                }
                val note = when {
                    validation.validatedCandidates.isEmpty() && validation.rejectedUnknownIds.isNotEmpty() ->
                        "Cloud suggestions did not match registered catalogue IDs. Photo preserved for manual selection or registration."
                    validation.validatedCandidates.isEmpty() ->
                        "No registered catalogue match found in cloud. Photo preserved — search manually or register as new product."
                    fetch.fromCache ->
                        "Loaded from recent cloud scan cache (no extra AI usage)."
                    else ->
                        "Cloud suggestions matched against your Emofah catalogue. Please confirm."
                }

                CloudRecognitionValidator.mergeWithLocalOutcome(
                    localOutcome = localOutcome,
                    validation = validation,
                    fromCloudCache = fetch.fromCache,
                    fallbackState = fallbackState,
                    note = note,
                )
            }
        }
    }

    /**
     * Stage 1.6 (Action 1.6.1): Records human-confirmed selections/corrections,
     * updates aliases + visual signature + local reference index, logs audit trail,
     * and queues approved reference updates for sync. Never learns from unconfirmed guesses.
     */
    suspend fun recordConfirmedSelection(
        outcome: RecognitionOutcome?,
        confirmedProduct: ProductIdentity,
        actorId: String,
        deviceId: String,
        selectionOrigin: RecognitionSelectionOrigin = RecognitionSelectionOrigin.CANDIDATE,
        allowImageAssociation: Boolean = true,
        isHumanConfirmed: Boolean = true,
    ): RecognitionLearningPlan {
        return learningRepository.recordConfirmedLearning(
            outcome = outcome,
            confirmedProduct = confirmedProduct,
            selectionOrigin = selectionOrigin,
            allowImageAssociation = allowImageAssociation,
            isHumanConfirmed = isHumanConfirmed,
            actorId = actorId,
            deviceId = deviceId,
        )
    }

    private suspend fun loadCachedPhotoPaths(productIds: List<Long>): Map<Long, String?> {
        val productDao = database.productDao()
        val map = HashMap<Long, String?>(productIds.size)
        for (pid in productIds) {
            val photos = productDao.getReferencePhotosForProduct(pid)
            val validLocal = photos.firstOrNull { it.isValid && it.localCachePath != null }?.localCachePath
            map[pid] = validLocal
        }
        return map
    }

    private suspend fun extractOcrText(imagePath: String): String {
        val file = File(imagePath)
        if (!file.exists()) return ""
        val bitmap = ImageQualityMath.decodeUprightBitmap(file, 960) ?: return ""
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            suspendCancellableCoroutine { cont ->
                textRecognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        if (cont.isActive) cont.resume(visionText.text.orEmpty())
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume("")
                    }
            }
        } catch (_: Exception) {
            ""
        } finally {
            bitmap.recycle()
        }
    }
}
