package com.emofah.agencies.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.emofah.agencies.data.catalog.ProductDraftInput
import com.emofah.agencies.data.catalog.ProductDraftSuggestionExtractor
import com.emofah.agencies.data.catalog.ProductDraftValidator
import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.catalog.RegisterProductOutcome
import com.emofah.agencies.ui.components.EmofahBadgeTone
import com.emofah.agencies.ui.components.EmofahCard
import com.emofah.agencies.ui.components.EmofahFeedbackBanner
import com.emofah.agencies.ui.components.EmofahLoadingButton
import com.emofah.agencies.ui.components.EmofahLocalImage
import com.emofah.agencies.ui.components.EmofahSectionHeader
import com.emofah.agencies.ui.components.EmofahStatusBadge
import com.emofah.agencies.ui.theme.emofahColors
import com.emofah.agencies.ui.theme.tabular
import java.util.UUID

@Composable
fun UnknownProductRegistrationScreen(
    preservedPhotoPath: String?,
    extractedOcrText: String,
    extractedCloudLabel: String?,
    existingProducts: List<ProductIdentity>,
    isSubmitting: Boolean,
    lastOutcome: RegisterProductOutcome?,
    onSubmitRegistration: (input: ProductDraftInput, allowNearDuplicateOverride: Boolean) -> Unit,
    onUseExistingProduct: (ProductIdentity) -> Unit,
    onSellRegisteredProduct: (ProductIdentity) -> Unit,
    onCancel: () -> Unit,
    headerSubtitle: String = "Confirm product details and selling price before recording a sale. Suggestions from packaging text are editable.",
    backButtonLabel: String = "Back to Scan",
    primarySuccessLabelBuilder: ((ProductIdentity) -> String)? = null,
    secondarySuccessLabelBuilder: ((ProductIdentity) -> String)? = null,
    onSecondarySuccessAction: ((ProductIdentity) -> Unit)? = null,
    onCapturePhotoClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.emofahColors
    val sessionKey = remember(preservedPhotoPath, extractedOcrText, extractedCloudLabel) {
        UUID.randomUUID().toString()
    }
    val suggestions = remember(preservedPhotoPath, extractedOcrText, extractedCloudLabel) {
        ProductDraftSuggestionExtractor.suggestFromOcr(
            ocrText = extractedOcrText,
            cloudLabel = extractedCloudLabel,
        )
    }

    val draftId by remember(sessionKey) { mutableStateOf("draft-${UUID.randomUUID()}") }
    val idempotencyKey by remember(sessionKey) { mutableStateOf("prod-idem-${UUID.randomUUID()}") }
    var name by remember(sessionKey) { mutableStateOf(suggestions.suggestedName) }
    var brand by remember(sessionKey) { mutableStateOf(suggestions.suggestedBrand.orEmpty()) }
    var variant by remember(sessionKey) { mutableStateOf(suggestions.suggestedVariant.orEmpty()) }
    var category by remember(sessionKey) { mutableStateOf(suggestions.suggestedCategory) }
    var sellPriceText by remember(sessionKey) { mutableStateOf("") }
    var buyPriceText by remember(sessionKey) { mutableStateOf("") }
    var initialQtyText by remember(sessionKey) { mutableStateOf("1") }
    var description by remember(sessionKey) { mutableStateOf("") }
    var confirmDistinctVariant by remember(sessionKey) { mutableStateOf(false) }

    val liveCanonicalName = remember(name, brand, variant) {
        ProductDraftValidator.buildCanonicalName(name, brand, variant)
    }
    val liveDuplicates = remember(liveCanonicalName, variant, existingProducts) {
        ProductDraftValidator.findPotentialDuplicates(
            proposedFullName = liveCanonicalName,
            proposedVariant = variant,
            existingProducts = existingProducts,
        )
    }
    val hasExactDuplicate = liveDuplicates.any { it.isExactMatch }

    val validationErrors = (lastOutcome as? RegisterProductOutcome.ValidationFailed)?.validation?.fieldErrors.orEmpty()
    val completedProduct = when (lastOutcome) {
        is RegisterProductOutcome.RegisteredOnline -> lastOutcome.product
        is RegisterProductOutcome.SavedOfflineDraft -> lastOutcome.localTempProduct
        else -> null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Register New Product",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            )
            Text(
                text = headerSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }

        EmofahCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EmofahLocalImage(
                    path = preservedPhotoPath,
                    contentDescription = "Captured product photo",
                    fallbackIcon = Icons.Outlined.Inventory2,
                    maxDimensionPx = 280,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    EmofahStatusBadge(
                        text = if (!preservedPhotoPath.isNullOrBlank()) "Photo Preserved" else "No Photo Attached",
                        tone = if (!preservedPhotoPath.isNullOrBlank()) EmofahBadgeTone.SUCCESS else EmofahBadgeTone.NEUTRAL,
                    )
                    val ocrSummary = suggestions.extractedOcrText.ifBlank {
                        extractedCloudLabel ?: "No packaging text detected"
                    }
                    Text(
                        text = "Detected: ${ocrSummary.take(80)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textMuted,
                        maxLines = 2,
                    )
                    if (preservedPhotoPath.isNullOrBlank() && onCapturePhotoClick != null) {
                        OutlinedButton(
                            onClick = onCapturePhotoClick,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            Text("Snap Reference Photo")
                        }
                    }
                }
            }
        }

        if (completedProduct != null) {
            val isOfflineDraft = lastOutcome is RegisterProductOutcome.SavedOfflineDraft
            val statusMsg = when (lastOutcome) {
                is RegisterProductOutcome.RegisteredOnline -> lastOutcome.message
                is RegisterProductOutcome.SavedOfflineDraft -> lastOutcome.message
                else -> ""
            }
            EmofahFeedbackBanner(
                title = if (isOfflineDraft) "Saved as Offline Draft" else "Product Registered",
                message = statusMsg,
                tone = if (isOfflineDraft) EmofahBadgeTone.WARNING else EmofahBadgeTone.SUCCESS,
            )

            EmofahCard {
                Button(
                    onClick = { onSellRegisteredProduct(completedProduct) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text(
                        text = primarySuccessLabelBuilder?.invoke(completedProduct)
                            ?: "Sell ${completedProduct.name} (Ksh ${completedProduct.effectiveSellPrice.toInt()})",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (secondarySuccessLabelBuilder != null && onSecondarySuccessAction != null) {
                    OutlinedButton(
                        onClick = { onSecondarySuccessAction(completedProduct) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(secondarySuccessLabelBuilder(completedProduct))
                    }
                }
                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(backButtonLabel)
                }
            }
            return@Column
        }

        if (lastOutcome is RegisterProductOutcome.Rejected) {
            EmofahFeedbackBanner(
                title = "Registration Failed",
                message = lastOutcome.message,
                tone = EmofahBadgeTone.DANGER,
            )
        }

        EmofahCard {
            EmofahSectionHeader(title = "Product Identity & Pricing")

            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    confirmDistinctVariant = false
                },
                label = { Text("Product Name *") },
                isError = validationErrors.containsKey("name"),
                supportingText = validationErrors["name"]?.let { err -> { Text(err) } },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = brand,
                    onValueChange = { brand = it },
                    label = { Text("Brand (optional)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = variant,
                    onValueChange = {
                        variant = it
                        confirmDistinctVariant = false
                    },
                    label = { Text("Size / Colour") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Category",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textMuted,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProductDraftSuggestionExtractor.SUPPORTED_CATEGORIES.forEach { cat ->
                        FilterChip(
                            selected = category.equals(cat, ignoreCase = true),
                            onClick = { category = cat },
                            label = { Text(cat) },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = sellPriceText,
                    onValueChange = { sellPriceText = it },
                    label = { Text("Selling Price (Ksh) *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = validationErrors.containsKey("sellPrice"),
                    supportingText = validationErrors["sellPrice"]?.let { err -> { Text(err) } },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = buyPriceText,
                    onValueChange = { buyPriceText = it },
                    label = { Text("Cost Price") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = validationErrors.containsKey("buyPrice"),
                    supportingText = validationErrors["buyPrice"]?.let { err -> { Text(err) } },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                )
            }

            OutlinedTextField(
                value = initialQtyText,
                onValueChange = { initialQtyText = it },
                label = { Text("Opening Stock Quantity *") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = validationErrors.containsKey("initialQty"),
                supportingText = validationErrors["initialQty"]?.let { err -> { Text(err) } },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Notes / Packaging details (optional)") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (liveDuplicates.isNotEmpty()) {
            EmofahCard(
                containerColor = if (hasExactDuplicate) colors.dangerContainer else colors.warningContainer,
                borderColor = (if (hasExactDuplicate) colors.danger else colors.warning).copy(alpha = 0.25f),
                contentPadding = PaddingValues(14.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = if (hasExactDuplicate) colors.danger else colors.warning,
                    )
                    Text(
                        text = if (hasExactDuplicate) {
                            "This product already exists in your catalogue"
                        } else {
                            "Similar product(s) found — avoid duplicates"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (hasExactDuplicate) colors.danger else colors.warning,
                    )
                }

                liveDuplicates.forEach { dup ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = colors.surfaceCard,
                        border = BorderStroke(1.dp, colors.borderSubtle),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onUseExistingProduct(dup.existingProduct) },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = dup.existingProduct.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.textPrimary,
                                )
                                Text(
                                    text = "${dup.reason} · Ksh ${dup.existingProduct.effectiveSellPrice.toInt()} · Stock ${dup.existingProduct.stockQty}",
                                    style = MaterialTheme.typography.labelSmall.tabular(),
                                    color = colors.textMuted,
                                )
                            }
                            EmofahStatusBadge(text = "Use Existing", tone = EmofahBadgeTone.INFO)
                        }
                    }
                }

                if (!hasExactDuplicate) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { confirmDistinctVariant = !confirmDistinctVariant },
                    ) {
                        Checkbox(
                            checked = confirmDistinctVariant,
                            onCheckedChange = { confirmDistinctVariant = it },
                        )
                        Text(
                            text = "This is a different product/variant — register separately",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textPrimary,
                        )
                    }
                }
            }
        }

        validationErrors["duplicate"]?.let { dupErr ->
            Text(
                text = dupErr,
                style = MaterialTheme.typography.bodySmall,
                color = colors.danger,
            )
        }
        validationErrors["duplicate_confirm"]?.let { dupConfirmErr ->
            Text(
                text = dupConfirmErr,
                style = MaterialTheme.typography.bodySmall,
                color = colors.danger,
            )
        }

        EmofahLoadingButton(
            text = "Save & Register Product",
            loadingText = "Saving Product...",
            isLoading = isSubmitting,
            enabled = !hasExactDuplicate,
            icon = Icons.Outlined.CheckCircle,
            onClick = {
                onSubmitRegistration(
                    ProductDraftInput(
                        draftId = draftId,
                        idempotencyKey = idempotencyKey,
                        name = name,
                        brandHint = brand,
                        variantLabel = variant,
                        category = category,
                        sellPriceText = sellPriceText,
                        buyPriceText = buyPriceText,
                        initialQtyText = initialQtyText,
                        description = description,
                        preservedPhotoPath = preservedPhotoPath,
                        ocrText = suggestions.extractedOcrText,
                    ),
                    confirmDistinctVariant,
                )
            },
        )

        OutlinedButton(
            onClick = onCancel,
            enabled = !isSubmitting,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text("Cancel")
        }
    }
}
