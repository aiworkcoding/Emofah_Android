package com.emofah.agencies.recognition

import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.local.entities.LocalStockProjectionEntity

/**
 * Stage 1.4 (Actions 1.4.1 & 1.4.2):
 * Pure Kotlin models, cost-control policy, catalogue context builder,
 * and strict candidate validator for cloud-assisted recognition fallback.
 */
enum class CloudFallbackState {
    NOT_NEEDED,
    READY_TO_TRY,
    CHECKING,
    MATCHES_FOUND,
    CACHE_HIT,
    NO_CATALOGUE_MATCH,
    OFFLINE_SKIPPED,
    BUDGET_LIMIT_REACHED,
    UNAVAILABLE_OR_FAILED,
}

data class CloudCatalogContextItem(
    val id: Long,
    val name: String,
    val category: String,
    val variantLabel: String?,
    val brandHint: String?,
)

data class CloudCandidateSuggestion(
    val productId: Long?,
    val suggestedName: String? = null,
    val suggestedBrand: String? = null,
    val suggestedVariant: String? = null,
    val reason: String? = null,
)

data class CloudRecognitionValidationResult(
    val validatedCandidates: List<RecognitionCandidate>,
    val rejectedUnknownIds: List<Long>,
    val extractedLabel: String?,
)

sealed class CloudEligibilityDecision {
    data class Eligible(val reason: String) : CloudEligibilityDecision()
    data class Skip(val state: CloudFallbackState, val message: String) : CloudEligibilityDecision()
}

data class RecognitionTelemetrySnapshot(
    val localStrongCount: Int = 0,
    val localAmbiguousCount: Int = 0,
    val localUnidentifiedCount: Int = 0,
    val cloudCallsToday: Int = 0,
    val cloudCallsTotal: Int = 0,
    val cloudCacheHits: Int = 0,
    val cloudMatchesFound: Int = 0,
    val cloudFailures: Int = 0,
    val rejectedUnknownIdsTotal: Int = 0,
    val lastCloudError: String? = null,
)

object CloudCostControlPolicy {

    const val MAX_DAILY_CLOUD_CALLS = 40
    const val MAX_CONTEXT_ITEMS = 120
    const val CLOUD_IMAGE_MAX_DIMENSION = 512
    const val CLOUD_IMAGE_JPEG_QUALITY = 72
    const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L

    fun evaluateAutoFallback(
        localOutcome: RecognitionOutcome,
        isOnline: Boolean,
        hasConfiguredEndpoint: Boolean,
        hasCachedProducts: Boolean,
        cloudCallsToday: Int,
        forceRequested: Boolean = false,
    ): CloudEligibilityDecision {
        if (!hasCachedProducts) {
            return CloudEligibilityDecision.Skip(
                state = CloudFallbackState.UNAVAILABLE_OR_FAILED,
                message = "Sync local product catalogue before trying cloud recognition.",
            )
        }

        // Action 1.4.2: Never burn cloud AI on strong local hits unless explicitly requested.
        if (localOutcome.status == MatchConfidenceStatus.STRONG_MATCH && !forceRequested) {
            return CloudEligibilityDecision.Skip(
                state = CloudFallbackState.NOT_NEEDED,
                message = "Identified locally.",
            )
        }

        // For ambiguous local matches with multiple plausible candidates, show local choices first
        // and let the operator tap "Try Cloud Match" only if needed.
        if (localOutcome.status == MatchConfidenceStatus.AMBIGUOUS_ALTERNATIVES && !forceRequested) {
            return CloudEligibilityDecision.Skip(
                state = CloudFallbackState.READY_TO_TRY,
                message = "Select a local match or tap Try Cloud Match.",
            )
        }

        if (!isOnline) {
            return CloudEligibilityDecision.Skip(
                state = CloudFallbackState.OFFLINE_SKIPPED,
                message = "Device is offline. Photo preserved — select manually or retry when online.",
            )
        }

        if (!hasConfiguredEndpoint) {
            return CloudEligibilityDecision.Skip(
                state = CloudFallbackState.UNAVAILABLE_OR_FAILED,
                message = "Cloud recognition endpoint not configured. Using local catalogue only.",
            )
        }

        if (cloudCallsToday >= MAX_DAILY_CLOUD_CALLS) {
            return CloudEligibilityDecision.Skip(
                state = CloudFallbackState.BUDGET_LIMIT_REACHED,
                message = "Daily cloud assist limit reached. Using local catalogue search.",
            )
        }

        return CloudEligibilityDecision.Eligible("Local recognition uncertain and device online.")
    }

    /**
     * Builds a compact, token-prioritized catalogue context so cloud prompts remain
     * small and cost-effective while preserving exact canonical IDs and variants.
     */
    fun buildCompactCatalogueContext(
        products: List<ProductIdentity>,
        ocrText: String,
        maxItems: Int = MAX_CONTEXT_ITEMS,
    ): List<CloudCatalogContextItem> {
        val safeMax = maxItems.coerceAtLeast(1)
        val active = products.filter { !it.isArchived }
        if (active.size <= safeMax) {
            return active.map {
                CloudCatalogContextItem(
                    id = it.id,
                    name = it.name,
                    category = it.category,
                    variantLabel = it.variantLabel,
                    brandHint = it.brandHint,
                )
            }
        }

        val ocrTokens = ocrText.lowercase()
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.length >= 2 }
            .toSet()

        return active
            .sortedByDescending { product ->
                if (ocrTokens.isEmpty()) {
                    if (product.isFeatured) 1 else 0
                } else {
                    val hay = "${product.name} ${product.category} ${product.variantLabel.orEmpty()} ${product.brandHint.orEmpty()}".lowercase()
                    val overlap = ocrTokens.count { tok -> hay.contains(tok) }
                    overlap * 10 + if (product.isFeatured) 1 else 0
                }
            }
            .take(safeMax)
            .map {
                CloudCatalogContextItem(
                    id = it.id,
                    name = it.name,
                    category = it.category,
                    variantLabel = it.variantLabel,
                    brandHint = it.brandHint,
                )
            }
    }
}

object CloudRecognitionValidator {

    /**
     * Action 1.4.1:
     * Validates every cloud-returned product ID strictly against the actual active
     * Emofah catalogue. Rejects unknown, negative, duplicate, or archived IDs.
     */
    fun validateSuggestions(
        suggestions: List<CloudCandidateSuggestion>,
        activeProductsById: Map<Long, ProductIdentity>,
        projectionsById: Map<Long, LocalStockProjectionEntity>,
        cachedPhotoPathsById: Map<Long, String?>,
        extractedLabel: String? = null,
    ): CloudRecognitionValidationResult {
        val accepted = mutableListOf<RecognitionCandidate>()
        val rejectedUnknownIds = mutableListOf<Long>()
        val seenIds = mutableSetOf<Long>()

        for ((index, suggestion) in suggestions.withIndex()) {
            val pid = suggestion.productId
            if (pid == null || pid <= 0L) continue

            val realProduct = activeProductsById[pid]
            if (realProduct == null || realProduct.isArchived) {
                rejectedUnknownIds.add(pid)
                continue
            }

            if (!seenIds.add(pid)) continue

            val reason = suggestion.reason?.trim().takeIf { !it.isNullOrBlank() }
                ?: "Cloud-assisted match"
            // Assign descending internal scores strictly within uncertain-confirmable band
            // so human confirmation is ALWAYS required (Blueprint Action 1.4.1 / N6).
            val rankScore = (0.78 - index * 0.07).coerceIn(0.45, 0.78)
            accepted.add(
                RecognitionCandidate(
                    product = realProduct,
                    stockProjection = projectionsById[pid],
                    cachedPhotoPath = cachedPhotoPathsById[pid],
                    internalScore = rankScore,
                    matchedReasons = listOf(reason, "Cloud fallback"),
                )
            )
        }

        return CloudRecognitionValidationResult(
            validatedCandidates = accepted,
            rejectedUnknownIds = rejectedUnknownIds,
            extractedLabel = extractedLabel?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Merges validated cloud candidates with any existing local candidates,
     * preserving canonical product IDs and never auto-skipping user confirmation.
     */
    fun mergeWithLocalOutcome(
        localOutcome: RecognitionOutcome,
        validation: CloudRecognitionValidationResult,
        fromCloudCache: Boolean,
        fallbackState: CloudFallbackState,
        note: String? = null,
    ): RecognitionOutcome {
        val localById = localOutcome.candidates.associateBy { it.product.id }
        val merged = mutableListOf<RecognitionCandidate>()
        val addedIds = mutableSetOf<Long>()

        for (cloudCandidate in validation.validatedCandidates) {
            val existingLocal = localById[cloudCandidate.product.id]
            val combinedReasons = if (existingLocal != null) {
                (cloudCandidate.matchedReasons + existingLocal.matchedReasons).distinct()
            } else {
                cloudCandidate.matchedReasons
            }
            merged.add(cloudCandidate.copy(matchedReasons = combinedReasons))
            addedIds.add(cloudCandidate.product.id)
        }

        for (localCandidate in localOutcome.candidates) {
            if (addedIds.add(localCandidate.product.id)) {
                merged.add(localCandidate)
            }
        }

        val capped = merged.take(5)
        val nextStatus = when {
            capped.isEmpty() -> MatchConfidenceStatus.UNIDENTIFIED
            capped.size == 1 && localOutcome.status == MatchConfidenceStatus.STRONG_MATCH ->
                MatchConfidenceStatus.STRONG_MATCH
            else -> MatchConfidenceStatus.AMBIGUOUS_ALTERNATIVES
        }

        val nextLabel = when {
            capped.isNotEmpty() && fromCloudCache ->
                "Matched from recent cloud scan cache — please confirm product"
            capped.isNotEmpty() ->
                "Cloud-assisted suggestions found — please confirm exact product"
            !note.isNullOrBlank() -> note
            else -> localOutcome.statusLabel
        }

        val combinedOcr = listOfNotNull(
            localOutcome.extractedOcrText.takeIf { it.isNotBlank() },
            validation.extractedLabel?.takeIf { label ->
                label.isNotBlank() && !localOutcome.extractedOcrText.contains(label, ignoreCase = true)
            },
        ).joinToString(" ").trim()

        return localOutcome.copy(
            status = nextStatus,
            candidates = capped,
            extractedOcrText = combinedOcr,
            statusLabel = nextLabel,
            cloudFallbackState = fallbackState,
            cloudNote = note,
            usedCloudAssist = validation.validatedCandidates.isNotEmpty() ||
                fallbackState == CloudFallbackState.CACHE_HIT,
            extractedCloudLabel = validation.extractedLabel,
        )
    }
}
