package com.emofah.agencies.recognition

import com.emofah.agencies.data.catalog.ProductIdentity

/**
 * Stage 1.6 (Action 1.6.1):
 * Pure domain policy for confirmed recognition learning and correction.
 *
 * Non-negotiable guardrails:
 * - Never learn from unconfirmed guesses (`isHumanConfirmed` must be true).
 * - Record both originally suggested match and human-confirmed selection.
 * - Filter out generic, price-like, numeric-only, or redundant OCR noise before
 *   promoting an alias to the local RecognitionReferenceIndex.
 * - Only associate the captured image when explicitly permitted (`allowImageAssociation`).
 */
enum class RecognitionSelectionOrigin {
    CANDIDATE,
    MANUAL_SEARCH,
}

enum class RecognitionSelectionKind {
    CONFIRMED_TOP_MATCH,
    ALTERNATIVE_CANDIDATE_SELECTED,
    MANUAL_SEARCH_OVERRIDE,
    CLOUD_ASSISTED_CONFIRMED,
}

data class RecognitionLearningPlan(
    val shouldRecordLearning: Boolean,
    val skipReason: String? = null,
    val originalSuggestedProductId: Long?,
    val originalSuggestedProductName: String?,
    val confirmedProductId: Long,
    val confirmedProductName: String,
    val matchStatus: MatchConfidenceStatus,
    val selectionKind: RecognitionSelectionKind,
    val usedCloudAssist: Boolean,
    val wasCorrected: Boolean,
    val learnedAlias: String?,
    val ocrSnippet: String?,
    val shouldAssociateImage: Boolean,
    val visualDHashHex: String?,
    val shouldQueueServerSync: Boolean,
)

object AliasExtractionPolicy {

    private val NOISE_LINES = setOf(
        "ksh",
        "kes",
        "price",
        "total",
        "qty",
        "quantity",
        "made in kenya",
        "made in china",
        "stationery",
        "books",
        "electronics",
        "general",
        "best before",
        "net weight",
    )

    private val PRICE_OR_NUMERIC_ONLY = Regex("""^(?:ksh|kes)?\s*[\d.,:/-]+$""", RegexOption.IGNORE_CASE)
    private val PRICE_TAG_LINE = Regex("""^(?:ksh|kes|price|total|qty)\b[\s:.-]*\d.*$""", RegexOption.IGNORE_CASE)

    fun extractUsefulAlias(
        cloudLabel: String?,
        ocrText: String,
        confirmedProductName: String,
    ): String? {
        val candidates = buildList {
            cloudLabel?.let { add(it) }
            ocrText.lines().forEach { add(it) }
        }

        val normalizedConfirmed = normalizeForComparison(confirmedProductName)
        for (raw in candidates) {
            val cleaned = cleanCandidate(raw) ?: continue
            val norm = normalizeForComparison(cleaned)
            if (norm.isBlank() || norm == normalizedConfirmed) continue
            if (norm in NOISE_LINES) continue
            if (PRICE_TAG_LINE.matches(norm)) continue
            return cleaned
        }
        return null
    }

    private fun cleanCandidate(raw: String): String? {
        val collapsed = raw.replace(Regex("""\s+"""), " ").trim()
        if (collapsed.length !in 3..56) return null
        if (!collapsed.any { it.isLetter() }) return null
        if (PRICE_OR_NUMERIC_ONLY.matches(collapsed)) return null
        val lettersCount = collapsed.count { it.isLetter() }
        if (lettersCount < 3) return null
        return collapsed
    }

    private fun normalizeForComparison(value: String): String {
        return value.lowercase()
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}

object RecognitionLearningPolicy {

    fun buildPlan(
        outcome: RecognitionOutcome?,
        confirmedProduct: ProductIdentity,
        selectionOrigin: RecognitionSelectionOrigin,
        allowImageAssociation: Boolean,
        isHumanConfirmed: Boolean,
    ): RecognitionLearningPlan {
        if (!isHumanConfirmed || outcome == null) {
            return RecognitionLearningPlan(
                shouldRecordLearning = false,
                skipReason = if (!isHumanConfirmed) {
                    "Learning blocked: unconfirmed guess."
                } else {
                    "Learning skipped: no recognition session context."
                },
                originalSuggestedProductId = null,
                originalSuggestedProductName = null,
                confirmedProductId = confirmedProduct.id,
                confirmedProductName = confirmedProduct.name,
                matchStatus = MatchConfidenceStatus.UNIDENTIFIED,
                selectionKind = RecognitionSelectionKind.MANUAL_SEARCH_OVERRIDE,
                usedCloudAssist = false,
                wasCorrected = false,
                learnedAlias = null,
                ocrSnippet = null,
                shouldAssociateImage = false,
                visualDHashHex = null,
                shouldQueueServerSync = false,
            )
        }

        val topCandidate = outcome.candidates.firstOrNull()?.product
        val topId = topCandidate?.id
        val topName = topCandidate?.name

        val selectionKind = when {
            selectionOrigin == RecognitionSelectionOrigin.MANUAL_SEARCH ->
                RecognitionSelectionKind.MANUAL_SEARCH_OVERRIDE
            outcome.usedCloudAssist ->
                RecognitionSelectionKind.CLOUD_ASSISTED_CONFIRMED
            topId != null && topId != confirmedProduct.id ->
                RecognitionSelectionKind.ALTERNATIVE_CANDIDATE_SELECTED
            topId == null ->
                RecognitionSelectionKind.MANUAL_SEARCH_OVERRIDE
            else ->
                RecognitionSelectionKind.CONFIRMED_TOP_MATCH
        }

        val wasCorrected = selectionKind != RecognitionSelectionKind.CONFIRMED_TOP_MATCH ||
            outcome.status != MatchConfidenceStatus.STRONG_MATCH

        val alias = AliasExtractionPolicy.extractUsefulAlias(
            cloudLabel = outcome.extractedCloudLabel,
            ocrText = outcome.extractedOcrText,
            confirmedProductName = confirmedProduct.name,
        )

        val validDHash = outcome.captureDHashHex?.takeIf { it.length == 16 }
        val hasPhoto = outcome.preservedPhotoPath.isNotBlank()
        val associateImage = allowImageAssociation && (validDHash != null || hasPhoto)
        val ocrSnippet = outcome.extractedOcrText.trim()
            .replace(Regex("""\s+"""), " ")
            .take(160)
            .takeIf { it.isNotEmpty() }

        val shouldSync = wasCorrected || alias != null || associateImage

        return RecognitionLearningPlan(
            shouldRecordLearning = true,
            originalSuggestedProductId = topId,
            originalSuggestedProductName = topName,
            confirmedProductId = confirmedProduct.id,
            confirmedProductName = confirmedProduct.name,
            matchStatus = outcome.status,
            selectionKind = selectionKind,
            usedCloudAssist = outcome.usedCloudAssist,
            wasCorrected = wasCorrected,
            learnedAlias = alias,
            ocrSnippet = ocrSnippet,
            shouldAssociateImage = associateImage,
            visualDHashHex = if (associateImage) validDHash else null,
            shouldQueueServerSync = shouldSync,
        )
    }
}
