package com.emofah.agencies.data.catalog

/**
 * Action 1.1.2 & 1.1.4:
 * Extracts structured recognition metadata (base name, variant/size/colour qualifier,
 * brand hint, and normalized search tokens) from live catalogue rows without
 * replacing the authoritative `products.id` primary key.
 */
object ProductMetadataExtractor {

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
        "A4",
        "A5",
    )

    private val PAREN_VARIANT_REGEX = Regex("""\(([^)]+)\)""")
    private val SIZE_QTY_REGEX = Regex("""\b(\d+\s?(?:pgs|pages|ml|l|g|kg|mm|cm|m|pcs|pack))\b""", RegexOption.IGNORE_CASE)
    private val NON_ALNUM_REGEX = Regex("""[^a-z0-9\s]""")

    fun toProductIdentity(
        dto: SupabaseProductDto,
        isArchived: Boolean = false,
    ): ProductIdentity {
        val trimmedName = dto.name.trim()
        val parenMatches = PAREN_VARIANT_REGEX.findAll(trimmedName)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()

        val sizeMatches = SIZE_QTY_REGEX.findAll(trimmedName)
            .map { it.groupValues[1].trim() }
            .toList()

        val variantParts = (parenMatches + sizeMatches).distinctBy { it.lowercase() }
        val variantLabel = if (variantParts.isEmpty()) null else variantParts.joinToString(" · ")

        val baseName = PAREN_VARIANT_REGEX.replace(trimmedName, "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifEmpty { trimmedName }

        val brandHint = KNOWN_BRANDS.firstOrNull { brand ->
            trimmedName.contains(brand, ignoreCase = true)
        }

        return ProductIdentity(
            id = dto.id,
            name = trimmedName,
            baseName = baseName,
            variantLabel = variantLabel,
            brandHint = brandHint,
            category = dto.category?.trim().orEmpty().ifEmpty { "General" },
            sellPrice = dto.sellPrice,
            discountPrice = dto.discountPrice,
            buyPrice = dto.buyPrice,
            stockQty = dto.qty,
            imageUrl = dto.imageUrl?.trim()?.takeIf { it.isNotEmpty() },
            description = dto.description?.trim()?.takeIf { it.isNotEmpty() },
            isFeatured = dto.isFeatured,
            createdAt = dto.createdAt,
            isArchived = isArchived || !dto.isActive,
            sku = dto.sku?.trim()?.takeIf { it.isNotEmpty() },
            barcode = dto.barcode?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    fun buildSearchTokens(
        product: ProductIdentity,
        confirmedAliases: List<String> = emptyList(),
    ): List<String> {
        val rawSources = buildList {
            add(product.name)
            add(product.baseName)
            product.variantLabel?.let { add(it) }
            product.brandHint?.let { add(it) }
            add(product.category)
            product.description?.let { add(it) }
            product.sku?.let { add(it) }
            product.barcode?.let { add(it) }
            addAll(confirmedAliases)
        }

        return rawSources
            .flatMap { text ->
                NON_ALNUM_REGEX.replace(text.lowercase(), " ")
                    .split(Regex("""\s+"""))
                    .filter { it.length >= 2 }
            }
            .distinct()
    }
}
