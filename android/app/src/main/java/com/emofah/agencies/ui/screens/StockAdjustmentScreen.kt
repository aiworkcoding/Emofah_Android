package com.emofah.agencies.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emofah.agencies.auth.EmofahPermission
import com.emofah.agencies.auth.RolePermissionPolicy
import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.local.entities.LocalStockProjectionEntity
import com.emofah.agencies.sales.StaffRole
import com.emofah.agencies.stock.StockAdjustmentDraft
import com.emofah.agencies.stock.StockAdjustmentReason
import com.emofah.agencies.stock.StockAdjustmentResult
import com.emofah.agencies.ui.components.EmofahBadgeTone
import com.emofah.agencies.ui.components.EmofahCard
import com.emofah.agencies.ui.components.EmofahFeedbackBanner
import com.emofah.agencies.ui.components.EmofahInteractiveCard
import com.emofah.agencies.ui.components.EmofahLoadingButton
import com.emofah.agencies.ui.components.EmofahLocalImage
import com.emofah.agencies.ui.components.EmofahSearchField
import com.emofah.agencies.ui.components.EmofahSectionHeader
import com.emofah.agencies.ui.components.EmofahStatusBadge
import com.emofah.agencies.ui.theme.emofahColors
import com.emofah.agencies.ui.theme.tabular

@Composable
fun StockAdjustmentScreen(
    selectedProduct: ProductIdentity?,
    stockProjection: LocalStockProjectionEntity?,
    cachedPhotoPath: String?,
    cachedProducts: List<ProductIdentity>,
    staffRole: StaffRole,
    isSubmitting: Boolean,
    lastOutcome: StockAdjustmentResult?,
    onScanToSelect: () -> Unit,
    onSelectProductManually: (ProductIdentity) -> Unit,
    onClearSelectedProduct: () -> Unit,
    onSubmitAdjustment: (StockAdjustmentDraft) -> Unit,
    onBackToLookup: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.emofahColors
    var selectedReason by remember { mutableStateOf(StockAdjustmentReason.DAMAGED) }
    var adjustmentMagnitude by remember { mutableIntStateOf(1) }
    var notesInput by remember { mutableStateOf("") }
    var manualSearchQuery by remember { mutableStateOf("") }
    var entryMode by remember { mutableStateOf(AdjustmentEntryMode.DELTA) }
    var countedPhysicalStockInput by remember(selectedProduct?.id, stockProjection?.provisionalAvailableQty) {
        val curr = stockProjection?.provisionalAvailableQty ?: selectedProduct?.stockQty ?: 0
        mutableStateOf(curr.toString())
    }

    val currentAvailable = stockProjection?.provisionalAvailableQty ?: selectedProduct?.stockQty ?: 0

    val effectiveDelta = when (entryMode) {
        AdjustmentEntryMode.DELTA -> {
            if (selectedReason.isDecrease) -adjustmentMagnitude else adjustmentMagnitude
        }
        AdjustmentEntryMode.COUNT_TARGET -> {
            val counted = countedPhysicalStockInput.filter { it.isDigit() || it == '-' }.toIntOrNull() ?: currentAvailable
            counted - currentAvailable
        }
    }

    val projectedNewStock = (currentAvailable + effectiveDelta).coerceAtLeast(0)

    val manualProductMatches = remember(manualSearchQuery, cachedProducts) {
        val q = manualSearchQuery.trim().lowercase()
        if (q.length < 2) {
            emptyList()
        } else {
            cachedProducts.filter {
                it.name.lowercase().contains(q) ||
                    it.category.lowercase().contains(q) ||
                    (it.variantLabel?.lowercase()?.contains(q) == true)
            }.take(8)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Stock Adjustment",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Text(
                    text = "Record damage, shrinkage, store use, or count variance",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            if (onBackToLookup != null) {
                OutlinedButton(
                    onClick = onBackToLookup,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Inventory2,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Stock")
                }
            }
        }

        lastOutcome?.let { outcome ->
            val (tone, title, message) = when (outcome) {
                is StockAdjustmentResult.SuccessOnline -> Triple(
                    EmofahBadgeTone.SUCCESS,
                    "Stock Adjusted Online · Synced",
                    "Delta (${if (outcome.quantityDelta > 0) "+${outcome.quantityDelta}" else "${outcome.quantityDelta}"}) applied. Server stock: ${outcome.updatedServerQty} units.",
                )
                is StockAdjustmentResult.SavedOffline -> Triple(
                    EmofahBadgeTone.WARNING,
                    "Saved on Phone · Will Upload",
                    "Change (${if (outcome.quantityDelta > 0) "+${outcome.quantityDelta}" else "${outcome.quantityDelta}"}) saved offline. Estimated stock: ${outcome.provisionalRemainingQty}. ${outcome.reasonMessage}",
                )
                is StockAdjustmentResult.DuplicateIgnored -> Triple(
                    EmofahBadgeTone.INFO,
                    "Duplicate Tap Ignored",
                    "This adjustment was already applied (server stock: ${outcome.serverQty}).",
                )
                is StockAdjustmentResult.Rejected -> Triple(
                    EmofahBadgeTone.DANGER,
                    "Adjustment Rejected",
                    outcome.message,
                )
            }
            EmofahFeedbackBanner(
                title = title,
                message = message,
                tone = tone,
            )
        }

        if (selectedProduct == null) {
            EmofahCard {
                EmofahSectionHeader(
                    title = "Step 1 · Identify Product",
                    subtitle = "Scan packaging or search your local catalogue",
                )

                Button(
                    onClick = onScanToSelect,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Scan Product to Adjust")
                }

                EmofahSearchField(
                    value = manualSearchQuery,
                    onValueChange = { manualSearchQuery = it },
                    placeholder = "Search product name or category...",
                )

                manualProductMatches.forEach { product ->
                    EmofahInteractiveCard(
                        onClick = { onSelectProductManually(product) },
                        contentPadding = PaddingValues(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = product.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${product.category} · Stock: ${product.stockQty}",
                                    style = MaterialTheme.typography.labelSmall.tabular(),
                                    color = colors.textMuted,
                                )
                            }
                            EmofahStatusBadge(text = "Select", tone = EmofahBadgeTone.INFO)
                        }
                    }
                }
            }
        } else {
            EmofahCard(
                containerColor = colors.surfaceElevated,
                borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EmofahLocalImage(
                        path = cachedPhotoPath,
                        contentDescription = selectedProduct.name,
                        fallbackIcon = Icons.Outlined.Inventory2,
                        maxDimensionPx = 256,
                        modifier = Modifier
                            .size(58.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = selectedProduct.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${selectedProduct.category} · Available: $currentAvailable units",
                            style = MaterialTheme.typography.labelSmall.tabular(),
                            color = colors.textMuted,
                        )
                    }

                    IconButton(onClick = onClearSelectedProduct) {
                        Icon(Icons.Outlined.Close, contentDescription = "Change product")
                    }
                }
            }

            EmofahCard {
                EmofahSectionHeader(
                    title = "Reason Code *",
                    subtitle = selectedReason.description,
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StockAdjustmentReason.entries.chunked(2).forEach { rowReasons ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowReasons.forEach { reason ->
                                FilterChip(
                                    selected = selectedReason == reason,
                                    onClick = { selectedReason = reason },
                                    label = {
                                        Text(
                                            text = reason.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (selectedReason == reason) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }

            EmofahCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Adjustment Quantity",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = entryMode == AdjustmentEntryMode.DELTA,
                            onClick = { entryMode = AdjustmentEntryMode.DELTA },
                            label = { Text("Units (±)") },
                        )
                        FilterChip(
                            selected = entryMode == AdjustmentEntryMode.COUNT_TARGET,
                            onClick = { entryMode = AdjustmentEntryMode.COUNT_TARGET },
                            label = { Text("Target") },
                        )
                    }
                }

                if (entryMode == AdjustmentEntryMode.DELTA) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalIconButton(
                            onClick = { adjustmentMagnitude = (adjustmentMagnitude - 1).coerceAtLeast(1) },
                        ) {
                            Icon(Icons.Outlined.Remove, contentDescription = "Decrease")
                        }

                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = colors.surfaceMuted,
                            border = BorderStroke(1.dp, colors.borderSubtle),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "${if (selectedReason.isDecrease) "-" else "+"}$adjustmentMagnitude unit(s)",
                                    style = MaterialTheme.typography.titleMedium.tabular(),
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textPrimary,
                                )
                            }
                        }

                        FilledTonalIconButton(
                            onClick = { adjustmentMagnitude += 1 },
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = "Increase")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(1, 2, 5, 10).forEach { chipQty ->
                            OutlinedButton(
                                onClick = { adjustmentMagnitude = chipQty },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(text = "$chipQty", style = MaterialTheme.typography.labelLarge.tabular())
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = countedPhysicalStockInput,
                        onValueChange = { countedPhysicalStockInput = it },
                        label = { Text("Physical count found on shelf") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = colors.surfaceMuted,
                    border = BorderStroke(1.dp, colors.borderSubtle),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = "Current: $currentAvailable",
                                style = MaterialTheme.typography.bodySmall.tabular(),
                                color = colors.textMuted,
                            )
                            Text(
                                text = "Adjustment: ${if (effectiveDelta > 0) "+$effectiveDelta" else "$effectiveDelta"}",
                                style = MaterialTheme.typography.bodySmall.tabular(),
                                fontWeight = FontWeight.SemiBold,
                                color = if (effectiveDelta < 0) colors.danger else MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Projected Stock",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textMuted,
                            )
                            Text(
                                text = "$projectedNewStock units",
                                style = MaterialTheme.typography.titleMedium.tabular(),
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary,
                            )
                        }
                    }
                }
            }

            EmofahCard {
                EmofahSectionHeader(
                    title = "Explanation Notes *",
                    subtitle = "Minimum 3 characters for audit trail",
                )
                OutlinedTextField(
                    value = notesInput,
                    onValueChange = { notesInput = it },
                    label = { Text("Details: batch, cause, location or action taken") },
                    placeholder = { Text("e.g. Lid cracked during offloading") },
                    minLines = 2,
                    maxLines = 4,
                    isError = notesInput.trim().length < 3 && notesInput.isNotEmpty(),
                    leadingIcon = { Icon(Icons.Outlined.EditNote, contentDescription = null) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val isFormValid = effectiveDelta != 0 &&
                notesInput.trim().length >= 3

            EmofahLoadingButton(
                text = "Apply Adjustment (${if (effectiveDelta > 0) "+$effectiveDelta" else "$effectiveDelta"} Units)",
                loadingText = "Applying Adjustment...",
                isLoading = isSubmitting,
                enabled = isFormValid,
                icon = Icons.Outlined.CheckCircle,
                containerColor = if (effectiveDelta < 0) colors.danger else MaterialTheme.colorScheme.primary,
                onClick = {
                    val draft = StockAdjustmentDraft(
                        productId = selectedProduct.id,
                        productName = selectedProduct.name,
                        currentAvailableQty = currentAvailable,
                        deltaQty = effectiveDelta,
                        reason = selectedReason,
                        notes = notesInput.trim(),
                        supervisorApprovedBy = null,
                    )
                    onSubmitAdjustment(draft)
                },
            )
        }
    }
}

private enum class AdjustmentEntryMode {
    DELTA,
    COUNT_TARGET,
}
