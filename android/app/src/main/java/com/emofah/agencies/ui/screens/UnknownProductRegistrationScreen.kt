package com.emofah.agencies.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.emofah.agencies.data.catalog.ProductDraftInput
import com.emofah.agencies.data.catalog.ProductDraftSuggestionExtractor
import com.emofah.agencies.data.catalog.ProductDraftValidator
import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.catalog.RegisterProductOutcome
import java.util.UUID

/**
 * Stage 1.5 (Action 1.5.1):
 * Quick registration route for an unidentified scanned product:
 * - Preserves captured photo & extracts OCR/cloud hints.
 * - Requires human review/confirmation & selling price > 0 before selling.
 * - Prevents accidental duplicate registration against existing catalogue items.
 * - Supports online registration and durable offline pending product drafts.
 */
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
    val sessionKey = remember(preservedPhotoPath, extractedOcrText, extractedCloudLabel) {
        UUID.randomUUID().toString()
    }
    val suggestions = remember(preservedPhotoPath, extractedOcrText, extractedCloudLabel) {
        ProductDraftSuggestionExtractor.suggestFromOcr(
            ocrText = extractedOcrText,
            cloudLabel = extractedCloudLabel,
        )
    }

    var draftId by remember(sessionKey) { mutableStateOf("draft-${UUID.randomUUID()}") }
    var idempotencyKey by remember(sessionKey) { mutableStateOf("prod-idem-${UUID.randomUUID()}") }
    var name by remember(sessionKey) { mutableStateOf(suggestions.suggestedName) }
    var brand by remember(sessionKey) { mutableStateOf(suggestions.suggestedBrand.orEmpty()) }
    var variant by remember(sessionKey) { mutableStateOf(suggestions.suggestedVariant.orEmpty()) }
    var category by remember(sessionKey) { mutableStateOf(suggestions.suggestedCategory) }
    var sellPriceText by remember(sessionKey) { mutableStateOf("") }
    var buyPriceText by remember(sessionKey) { mutableStateOf("") }
    var initialQtyText by remember(sessionKey) { mutableStateOf("1") }
    var description by remember(sessionKey) { mutableStateOf("") }
    var confirmDistinctVariant by remember(sessionKey) { mutableStateOf(false) }

    val previewBitmap = remember(preservedPhotoPath) {
        preservedPhotoPath?.let { BitmapFactory.decodeFile(it) }
    }

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
        Text(
            text = "Register New Product",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = headerSubtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Preserved Captured Photo Preview
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (previewBitmap != null) {
                        Image(
                            bitmap = previewBitmap.asImageBitmap(),
                            contentDescription = "Captured product photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Inventory2,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (previewBitmap != null) "Captured photo preserved" else "No photo attached",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val ocrSummary = suggestions.extractedOcrText.ifBlank {
                        extractedCloudLabel ?: "No packaging text detected"
                    }
                    Text(
                        text = "Detected text: ${ocrSummary.take(80)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                    if (previewBitmap == null && onCapturePhotoClick != null) {
                        OutlinedButton(
                            onClick = onCapturePhotoClick,
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text("Snap Reference Photo (Optional)")
                        }
                    }
                }
            }
        }

        // Post-save success / offline draft confirmation
        if (completedProduct != null) {
            val isOfflineDraft = lastOutcome is RegisterProductOutcome.SavedOfflineDraft
            val statusMsg = when (lastOutcome) {
                is RegisterProductOutcome.RegisteredOnline -> lastOutcome.message
                is RegisterProductOutcome.SavedOfflineDraft -> lastOutcome.message
                else -> ""
            }
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isOfflineDraft) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (isOfflineDraft) Icons.Outlined.CloudOff else Icons.Outlined.CheckCircle,
                            contentDescription = null,
                        )
                        Text(
                            text = if (isOfflineDraft) "Saved as Offline Draft" else "Product Registered",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(text = statusMsg, style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = { onSellRegisteredProduct(completedProduct) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) {
                        Text(
                            text = primarySuccessLabelBuilder?.invoke(completedProduct)
                                ?: "Sell ${completedProduct.name} (Ksh ${completedProduct.effectiveSellPrice.toInt()})",
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (secondarySuccessLabelBuilder != null && onSecondarySuccessAction != null) {
                        OutlinedButton(
                            onClick = { onSecondarySuccessAction(completedProduct) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(secondarySuccessLabelBuilder(completedProduct))
                        }
                    }
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(backButtonLabel)
                    }
                }
            }
            return@Column
        }

        if (lastOutcome is RegisterProductOutcome.Rejected) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(
                    text = lastOutcome.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        // Editable Product Fields (Human confirmation required)
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
                modifier = Modifier.weight(1f),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Category",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = buyPriceText,
                onValueChange = { buyPriceText = it },
                label = { Text("Cost Price (optional)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = validationErrors.containsKey("buyPrice"),
                supportingText = validationErrors["buyPrice"]?.let { err -> { Text(err) } },
                singleLine = true,
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
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("Notes / Packaging details (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Duplicate Product Prevention Guard
        if (liveDuplicates.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (hasExactDuplicate) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.WarningAmber, contentDescription = null)
                        Text(
                            text = if (hasExactDuplicate) {
                                "This product already exists in your catalogue"
                            } else {
                                "Similar product(s) found — avoid duplicates"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    liveDuplicates.forEach { dup ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .clickable { onUseExistingProduct(dup.existingProduct) }
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = dup.existingProduct.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "${dup.reason} · Ksh ${dup.existingProduct.effectiveSellPrice.toInt()} · Stock ${dup.existingProduct.stockQty}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = "Use Existing",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
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
                            )
                        }
                    }
                }
            }
        }

        validationErrors["duplicate"]?.let { dupErr ->
            Text(
                text = dupErr,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        validationErrors["duplicate_confirm"]?.let { dupConfirmErr ->
            Text(
                text = dupConfirmErr,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(
            onClick = {
                if (isSubmitting) return@Button
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
            enabled = !isSubmitting && !hasExactDuplicate,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text("Saving Product...")
            } else {
                Text(
                    text = "SAVE & REGISTER PRODUCT",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        OutlinedButton(
            onClick = onCancel,
            enabled = !isSubmitting,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text("Cancel")
        }
    }
}
