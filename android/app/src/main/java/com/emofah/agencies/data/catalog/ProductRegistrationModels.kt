package com.emofah.agencies.data.catalog

import java.util.UUID

/**
 * Stage 1.5 (Action 1.5.1):
 * Pure Kotlin OCR/cloud suggestion extraction, commercial validation,
 * and duplicate-registration prevention.
 *
 * Rule: Suggestions never silently become authoritative product data;
 * human confirmation and a valid selling price are always required.
 */
data class ProductDraftSuggestions(
    val suggestedName: String,
    val suggestedBrand: String?,
    val suggestedCategory: String,
    val suggestedVariant: String?,
    val extractedOcrText: String,
)

data class ProductDraftInput(
    val draftId: String = "draft-${UUID.randomUUID()}",
    val idempotencyKey: String = "prod-idem-${UUID.randomUUID()}",
    val name: String,
    val brandHint: String = "",
    val variantLabel: String = "",
    val category: String = "General",
    val sellPriceText: String,
    val buyPriceText: String = "",
    val initialQtyText: String = "1",
    val description: String = "",
    val preservedPhotoPath: String? = null,
    val ocrText: String = "",
)

data class DuplicateProductMatch(
    val existingProduct: ProductIdentity,
    val reason: String,
    val isExactMatch: Boolean,
)

data class ProductDraftValidationResult(
    val isValid: Boolean,
    val fieldErrors: Map<String, String>,
    val canonicalFullName: String,
    val parsedSellPrice: Double,
    val parsedBuyPrice: Double,
    val parsedInitialQty: Int,
    val potentialDuplicates: List<DuplicateProductMatch>,
    val hasExactDuplicate: Boolean,
)

object ProductDraftSuggestionExtractor {

    val SUPPORTED_CATEGORIES = listOf("Stationery", "Books", "Electronics", "General")

    private val KNOWN_BRANDS = listOf(
        "Bic",
        "Express",
        "Obama",
        "Kasuku",
        "Kartasi",
        "Deli",
        "Epson",
        "HP",
        "Faber-Castell",
        "Staedtler",
        "Pelikan",
        "Casio",
        "SanDisk",
    )

    private val VARIANT_REGEX = Regex(
        """\b(\d+\s?(?:pgs|pages|ml|l|g|kg|mm|cm|m|pcs|pack|gb|tb)|a[345]|blue|black|red|green|small|medium|large)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val BOOK_KEYWORDS = listOf("book", "exercise", "pgs", "pages", "counter", "diary", "ruled", "square", "kasuku", "kartasi")
    private val ELECTRONICS_KEYWORDS = listOf("usb", "cable", "mouse", "keyboard", "battery", "charger", "earphone", "calculator", "toner", "cartridge", "flash", "gb", "epson", "hp", "casio")
    private val STATIONERY_KEYWORDS = listOf("pen", "pencil", "tape", "glue", "ruler", "eraser", "marker", "staple", "paper", "envelope", "folder", "file", "bic", "deli")

    fun suggestFromOcr(
        ocrText: String,
        cloudLabel: String? = null,
    ): ProductDraftSuggestions {
        val cleanOcr = ocrText.trim()
        val sourceText = listOfNotNull(
            cloudLabel?.trim()?.takeIf { it.isNotEmpty() },
            cleanOcr.takeIf { it.isNotEmpty() },
        ).joinToString("\n")

        val lines = sourceText.lines()
            .map { it.replace(Regex("""\s+"""), " ").trim() }
            .filter { it.length >= 2 }

        val rawBestLine = lines.firstOrNull { line ->
            line.any { it.isLetter() }
        }.orEmpty()

        val suggestedBrand = KNOWN_BRANDS.firstOrNull { brand ->
            sourceText.contains(brand, ignoreCase = true)
        }

        val variantTokens = VARIANT_REGEX.findAll(sourceText)
            .map { it.groupValues[1].trim() }
            .distinctBy { it.lowercase() }
            .take(2)
            .toList()
        val suggestedVariant = variantTokens.takeIf { it.isNotEmpty() }?.joinToString(" ")

        val lowerAll = sourceText.lowercase()
        val suggestedCategory = when {
            BOOK_KEYWORDS.any { lowerAll.contains(it) } -> "Books"
            ELECTRONICS_KEYWORDS.any { lowerAll.contains(it) } -> "Electronics"
            STATIONERY_KEYWORDS.any { lowerAll.contains(it) } -> "Stationery"
            else -> "General"
        }

        val cleanedName = rawBestLine
            .take(60)
            .trim()

        return ProductDraftSuggestions(
            suggestedName = cleanedName,
            suggestedBrand = suggestedBrand,
            suggestedCategory = suggestedCategory,
            suggestedVariant = suggestedVariant,
            extractedOcrText = cleanOcr,
        )
    }
}

object ProductDraftValidator {

    private val NON_ALNUM = Regex("""[^a-z0-9\s]""")

    fun buildCanonicalName(
        name: String,
        brandHint: String,
        variantLabel: String,
    ): String {
        val cleanName = name.trim().replace(Regex("""\s+"""), " ")
        val cleanBrand = brandHint.trim().replace(Regex("""\s+"""), " ")
        val cleanVariant = variantLabel.trim().replace(Regex("""\s+"""), " ")

        val withBrand = if (
            cleanBrand.isNotEmpty() &&
            !cleanName.contains(cleanBrand, ignoreCase = true)
        ) {
            "$cleanBrand $cleanName"
        } else {
            cleanName
        }

        return if (
            cleanVariant.isNotEmpty() &&
            !withBrand.contains(cleanVariant, ignoreCase = true)
        ) {
            "$withBrand ($cleanVariant)"
        } else {
            withBrand
        }
    }

    fun validate(
        input: ProductDraftInput,
        existingProducts: List<ProductIdentity>,
        allowNearDuplicateOverride: Boolean = false,
    ): ProductDraftValidationResult {
        val errors = linkedMapOf<String, String>()

        val rawName = input.name.trim()
        if (rawName.length < 2) {
            errors["name"] = "Enter a clear product name (at least 2 characters)."
        }

        val sellPrice = input.sellPriceText.trim().toDoubleOrNull()
        if (sellPrice == null || sellPrice <= 0.0) {
            errors["sellPrice"] = "Enter a selling price greater than 0 before selling."
        }

        val buyPrice = if (input.buyPriceText.isBlank()) {
            0.0
        } else {
            input.buyPriceText.trim().toDoubleOrNull()
        }
        if (buyPrice == null || buyPrice < 0.0) {
            errors["buyPrice"] = "Cost price must be 0 or greater."
        }

        val initialQty = input.initialQtyText.trim().toIntOrNull()
        if (initialQty == null || initialQty < 0) {
            errors["initialQty"] = "Stock quantity must be 0 or a whole number."
        }

        val canonicalName = buildCanonicalName(
            name = rawName,
            brandHint = input.brandHint,
            variantLabel = input.variantLabel,
        )

        val duplicates = findPotentialDuplicates(
            proposedFullName = canonicalName,
            proposedVariant = input.variantLabel,
            existingProducts = existingProducts,
        )
        val hasExact = duplicates.any { it.isExactMatch }
        if (hasExact) {
            errors["duplicate"] =
                "\"${duplicates.first { it.isExactMatch }.existingProduct.name}\" already exists. Select the existing product instead of registering a duplicate."
        } else if (duplicates.isNotEmpty() && !allowNearDuplicateOverride) {
            errors["duplicate_confirm"] =
                "Similar product found in catalogue. Confirm it is a distinct item or select the existing product."
        }

        return ProductDraftValidationResult(
            isValid = errors.isEmpty(),
            fieldErrors = errors,
            canonicalFullName = canonicalName,
            parsedSellPrice = sellPrice ?: 0.0,
            parsedBuyPrice = buyPrice ?: 0.0,
            parsedInitialQty = initialQty ?: 0,
            potentialDuplicates = duplicates,
            hasExactDuplicate = hasExact,
        )
    }

    fun findPotentialDuplicates(
        proposedFullName: String,
        proposedVariant: String,
        existingProducts: List<ProductIdentity>,
    ): List<DuplicateProductMatch> {
        val normProposed = normalize(proposedFullName)
        if (normProposed.length < 2) return emptyList()

        val proposedTokens = tokenize(normProposed)
        val normVariant = normalize(proposedVariant)
        val matches = mutableListOf<DuplicateProductMatch>()

        for (existing in existingProducts) {
            if (existing.isArchived) continue
            val normExisting = normalize(existing.name)
            if (normExisting == normProposed) {
                matches.add(
                    DuplicateProductMatch(
                        existingProduct = existing,
                        reason = "Exact name match in catalogue",
                        isExactMatch = true,
                    )
                )
                continue
            }

            val existingTokens = tokenize(normExisting)
            if (proposedTokens.isEmpty() || existingTokens.isEmpty()) continue
            val intersection = proposedTokens.intersect(existingTokens).size
            val union = proposedTokens.union(existingTokens).size
            val jaccard = intersection.toDouble() / union.toDouble()

            val existingVariantNorm = normalize(existing.variantLabel.orEmpty())
            val variantsConflict = normVariant.isNotEmpty() &&
                existingVariantNorm.isNotEmpty() &&
                normVariant != existingVariantNorm

            if (jaccard >= 0.72 && !variantsConflict) {
                matches.add(
                    DuplicateProductMatch(
                        existingProduct = existing,
                        reason = "Very similar product name",
                        isExactMatch = false,
                    )
                )
            }
        }

        return matches
            .sortedByDescending { it.isExactMatch }
            .take(3)
    }

    private fun normalize(value: String): String {
        return NON_ALNUM.replace(value.lowercase(), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun tokenize(normalized: String): Set<String> {
        return normalized.split(' ').filter { it.length >= 2 }.toSet()
    }
}
