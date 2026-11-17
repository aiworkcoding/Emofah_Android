package com.emofah.agencies.recognition

import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.catalog.RecognitionReferenceEntry
import com.emofah.agencies.data.local.entities.LocalStockProjectionEntity
import kotlin.math.max

/**
 * Stage 1.3 (Actions 1.3.1 & 1.3.2):
 * Pure, deterministic on-device candidate ranker combining visual dHash similarity
 * and ML Kit OCR text evidence against the local Emofah catalogue index.
 * Never fabricates product IDs and never auto-commits uncertain matches.
 */
enum class MatchConfidenceStatus {
    STRONG_MATCH,
    AMBIGUOUS_ALTERNATIVES,
    UNIDENTIFIED,
}

data class RecognitionCandidate(
    val product: ProductIdentity,
    val stockProjection: LocalStockProjectionEntity?,
    val cachedPhotoPath: String?,
    val internalScore: Double,
    val matchedReasons: List<String>,
)

data class RecognitionOutcome(
    val status: MatchConfidenceStatus,
    val candidates: List<RecognitionCandidate>,
    val extractedOcrText: String,
    val captureId: String,
    val preservedPhotoPath: String,
    val statusLabel: String,
    val cloudFallbackState: CloudFallbackState = CloudFallbackState.NOT_NEEDED,
    val cloudNote: String? = null,
    val usedCloudAssist: Boolean = false,
    val extractedCloudLabel: String? = null,
    val captureDHashHex: String? = null,
)

object RecognitionRanker {

    const val STRONG_MATCH_THRESHOLD = 0.62
    const val STRONG_MARGIN_THRESHOLD = 0.14
    const val MINIMUM_CANDIDATE_THRESHOLD = 0.28
    private val NON_ALNUM = Regex("""[^a-z0-9\s]""")

    fun rankCandidates(
        captureId: String,
        preservedPhotoPath: String,
        captureDHashHex: String?,
        ocrText: String,
        activeEntries: List<RecognitionReferenceEntry>,
        productsById: Map<Long, ProductIdentity>,
        projectionsById: Map<Long, LocalStockProjectionEntity>,
        cachedPhotoPathsById: Map<Long, String?>,
        maxCandidates: Int = 5,
    ): RecognitionOutcome {
        val normalizedOcr = normalizeText(ocrText)
        val ocrTokens = tokenize(normalizedOcr)

        val scored = mutableListOf<RecognitionCandidate>()

        for (entry in activeEntries) {
            if (entry.isArchived) continue
            val product = productsById[entry.productId] ?: continue

            val visualSim = if (!captureDHashHex.isNullOrBlank() && !entry.visualSignatureHex.isNullOrBlank()) {
                dHashSimilarity(captureDHashHex, entry.visualSignatureHex)
            } else {
                null
            }

            val textResult = scoreTextMatch(normalizedOcr, ocrTokens, entry)
            val combinedScore = combineEvidence(visualSim, textResult.score)

            if (combinedScore >= MINIMUM_CANDIDATE_THRESHOLD) {
                val reasons = buildList {
                    if (visualSim != null && visualSim >= 0.72) add("Visual match")
                    if (textResult.matchedAlias) add("Confirmed alias match")
                    if (textResult.matchedVariant) add("Variant match")
                    if (textResult.tokenHits > 0) add("Packaging text match")
                }
                scored.add(
                    RecognitionCandidate(
                        product = product,
                        stockProjection = projectionsById[product.id],
                        cachedPhotoPath = cachedPhotoPathsById[product.id],
                        internalScore = combinedScore,
                        matchedReasons = reasons.ifEmpty { listOf("Catalogue similarity") },
                    )
                )
            }
        }

        val ranked = scored
            .sortedByDescending { it.internalScore }
            .take(maxCandidates.coerceAtLeast(1))

        val status = evaluateConfidence(ranked)
        val label = when (status) {
            MatchConfidenceStatus.STRONG_MATCH ->
                "Best match found — please confirm before selling"
            MatchConfidenceStatus.AMBIGUOUS_ALTERNATIVES ->
                "Multiple similar products found — select the exact item"
            MatchConfidenceStatus.UNIDENTIFIED ->
                "No reliable match found — retake photo or search/register"
        }

        return RecognitionOutcome(
            status = status,
            candidates = ranked,
            extractedOcrText = ocrText.trim(),
            captureId = captureId,
            preservedPhotoPath = preservedPhotoPath,
            statusLabel = label,
            captureDHashHex = captureDHashHex,
        )
    }

    fun evaluateConfidence(ranked: List<RecognitionCandidate>): MatchConfidenceStatus {
        if (ranked.isEmpty()) return MatchConfidenceStatus.UNIDENTIFIED
        val top = ranked[0]
        if (top.internalScore < MINIMUM_CANDIDATE_THRESHOLD) {
            return MatchConfidenceStatus.UNIDENTIFIED
        }
        val secondScore = ranked.getOrNull(1)?.internalScore ?: 0.0
        val margin = top.internalScore - secondScore
        return if (top.internalScore >= STRONG_MATCH_THRESHOLD && margin >= STRONG_MARGIN_THRESHOLD) {
            MatchConfidenceStatus.STRONG_MATCH
        } else {
            MatchConfidenceStatus.AMBIGUOUS_ALTERNATIVES
        }
    }

    fun dHashSimilarity(hexA: String, hexB: String): Double {
        if (hexA.length != 16 || hexB.length != 16) return 0.0
        return try {
            val a = hexA.toULong(16)
            val b = hexB.toULong(16)
            val xor = a xor b
            val distance = xor.countOneBits()
            (64 - distance).toDouble() / 64.0
        } catch (_: Exception) {
            0.0
        }
    }

    private data class TextMatchScore(
        val score: Double,
        val tokenHits: Int,
        val matchedAlias: Boolean,
        val matchedVariant: Boolean,
    )

    private fun scoreTextMatch(
        normalizedOcr: String,
        ocrTokens: Set<String>,
        entry: RecognitionReferenceEntry,
    ): TextMatchScore {
        if (ocrTokens.isEmpty() && normalizedOcr.isBlank()) {
            return TextMatchScore(0.0, 0, matchedAlias = false, matchedVariant = false)
        }

        val nameLower = normalizeText(entry.canonicalName)
        val exactNameBoost = if (nameLower.isNotEmpty() && normalizedOcr.isNotEmpty() && normalizedOcr.contains(nameLower)) 0.35 else 0.0

        val matchedAlias = entry.aliases.any { alias ->
            val normAlias = normalizeText(alias)
            normAlias.isNotEmpty() && normalizedOcr.contains(normAlias)
        }
        val aliasBoost = if (matchedAlias) 0.30 else 0.0

        val entryTokens = entry.searchTokens.toSet()
        val hits = ocrTokens.count { ocrTok ->
            entryTokens.any { refTok -> refTok == ocrTok || (ocrTok.length >= 4 && refTok.contains(ocrTok)) }
        }

        val coverage = if (entryTokens.isEmpty()) 0.0 else hits.toDouble() / max(1, entryTokens.size).toDouble()

        val variantTokens = entry.variantLabel?.let { tokenize(normalizeText(it)) }.orEmpty()
        val matchedVariant = variantTokens.isNotEmpty() && variantTokens.all { vt ->
            ocrTokens.any { it == vt }
        }
        val variantAdjustment = when {
            variantTokens.isEmpty() -> 0.0
            matchedVariant -> 0.18
            // Mild penalty when variant qualifier is unreadable so siblings surface as AMBIGUOUS_ALTERNATIVES
            else -> -0.02
        }

        val total = (coverage * 0.65 + exactNameBoost + aliasBoost + variantAdjustment).coerceIn(0.0, 1.0)
        return TextMatchScore(
            score = total,
            tokenHits = hits,
            matchedAlias = matchedAlias,
            matchedVariant = matchedVariant,
        )
    }

    private fun combineEvidence(visualSim: Double?, textScore: Double): Double {
        return when {
            visualSim != null && textScore > 0.0 -> (0.45 * visualSim + 0.55 * textScore).coerceIn(0.0, 1.0)
            visualSim != null -> (visualSim * 0.85).coerceIn(0.0, 1.0)
            else -> textScore
        }
    }

    private fun normalizeText(input: String): String {
        return NON_ALNUM.replace(input.lowercase(), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun tokenize(normalized: String): Set<String> {
        if (normalized.isBlank()) return emptySet()
        return normalized.split(' ')
            .filter { it.length >= 2 }
            .toSet()
    }
}
