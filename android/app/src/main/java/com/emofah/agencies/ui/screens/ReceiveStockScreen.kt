package com.emofah.agencies.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.local.entities.LocalStockProjectionEntity
import com.emofah.agencies.stock.StockReceiptDraft
import com.emofah.agencies.stock.StockReceivingResult
import com.emofah.agencies.stock.StockReceivingValidator
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
import kotlin.math.roundToInt

@Composable
fun ReceiveStockScreen(
    selectedProduct: ProductIdentity?,
    stockProjection: LocalStockProjectionEntity?,
    cachedPhotoPath: String?,
    cachedProducts: List<ProductIdentity>,
    isSubmitting: Boolean,
    lastOutcome: StockReceivingResult?,
    onScanToSelect: () -> Unit,
    onSelectProductManually: (ProductIdentity) -> Unit,
    onClearSelectedProduct: () -> Unit,
    onSubmitReceipt: (StockReceiptDraft) -> Unit,
    onScanNext: () -> Unit,
    onBackToLookup: (() -> Unit)? = null,
    onRegisterNewProduct: ((initialNameHint: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.emofahColors
    var rawQuantityInput by remember(selectedProduct?.id) { mutableStateOf("10") }
    var unitCostInput by remember(selectedProduct?.id) {
        val defaultCost = selectedProduct?.buyPrice?.takeIf { it > 0.0 }?.toString() ?: ""
        mutableStateOf(defaultCost)
    }
    var supplierInput by remember { mutableStateOf("") }
    var invoiceRefInput by remember { mutableStateOf("") }
    var updateBuyPrice by remember { mutableStateOf(false) }
    var manualSearchQuery by remember { mutableStateOf("") }

    val parsedQty = rawQuantityInput.filter { it.isDigit() }.toIntOrNull() ?: 0
    val parsedUnitCost = unitCostInput.trim().toDoubleOrNull()?.takeIf { it >= 0.0 }

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
                    text = "Receive Stock",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Text(
                    text = "Record incoming shipment quantities & supplier cost",
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
                is StockReceivingResult.SuccessOnline -> Triple(
                    EmofahBadgeTone.SUCCESS,
                    "Stock Received Online · Synced",
                    "+${outcome.quantityReceived} unit(s) recorded in Supabase. Server stock: ${outcome.updatedServerQty}.",
                )
                is StockReceivingResult.SavedOffline -> Triple(
                    EmofahBadgeTone.WARNING,
                    "Saved on Phone · Will Sync",
                    "+${outcome.quantityReceived} unit(s) saved locally. Estimated stock: ${outcome.provisionalRemainingQty}. ${outcome.reason}",
                )
                is StockReceivingResult.DuplicateIgnored -> Triple(
                    EmofahBadgeTone.INFO,
                    "Duplicate Tap Ignored",
                    "This stock receipt was already committed (stock: ${outcome.serverQty}).",
                )
                is StockReceivingResult.Rejected -> Triple(
                    EmofahBadgeTone.DANGER,
                    "Stock Receipt Rejected",
                    outcome.message,
                )
            }
            EmofahFeedbackBanner(
                title = title,
                message = message,
                tone = tone,
                actionLabel = "Scan Next",
                onAction = onScanNext,
            )
        }

        if (selectedProduct == null) {
            EmofahCard {
                EmofahSectionHeader(
                    title = "Step 1 · Identify Arriving Product",
                    subtitle = "Scan product packaging or search your local catalogue",
                )
                Button(
                    onClick = onScanToSelect,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Scan Arriving Product")
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

                if (onRegisterNewProduct != null) {
                    OutlinedButton(
                        onClick = { onRegisterNewProduct(manualSearchQuery.trim()) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.AddBox, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (manualSearchQuery.trim().length >= 2) {
                                "Register \"${manualSearchQuery.trim().take(24)}\""
                            } else {
                                "Product not found? Register New Product"
                            },
                        )
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
                            .size(62.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = selectedProduct.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitle = listOfNotNull(
                            selectedProduct.brandHint,
                            selectedProduct.variantLabel,
                            selectedProduct.category,
                        ).joinToString(" · ")
                        if (subtitle.isNotBlank()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textMuted,
                            )
                        }
                        val currentAvail = stockProjection?.provisionalAvailableQty ?: selectedProduct.stockQty
                        EmofahStatusBadge(
                            text = "Current stock: $currentAvail units",
                            tone = EmofahBadgeTone.INFO,
                        )
                    }

                    IconButton(onClick = onClearSelectedProduct) {
                        Icon(Icons.Outlined.Close, contentDescription = "Change product")
                    }
                }
            }

            EmofahCard {
                EmofahSectionHeader(title = "Quantity Received *")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FilledTonalIconButton(
                        onClick = {
                            val current = parsedQty
                            if (current > 1) rawQuantityInput = (current - 1).toString()
                        },
                    ) {
                        Icon(Icons.Outlined.Remove, contentDescription = "Decrease")
                    }

                    OutlinedTextField(
                        value = rawQuantityInput,
                        onValueChange = { input ->
                            rawQuantityInput = input.filter { it.isDigit() }.take(5)
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.titleMedium.tabular().copy(fontWeight = FontWeight.Bold),
                    )

                    FilledTonalIconButton(
                        onClick = {
                            val current = parsedQty
                            rawQuantityInput = (current + 1).toString()
                        },
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = "Increase")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(5, 10, 20, 50).forEach { bump ->
                        OutlinedButton(
                            onClick = {
                                val current = parsedQty
                                rawQuantityInput = (current + bump).toString()
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("+$bump", style = MaterialTheme.typography.labelLarge.tabular(), maxLines = 1)
                        }
                    }
                }
            }

            EmofahCard {
                EmofahSectionHeader(
                    title = "Supplier & Cost Details",
                    subtitle = "Optional batch purchase information",
                )

                OutlinedTextField(
                    value = supplierInput,
                    onValueChange = { supplierInput = it },
                    label = { Text("Supplier name / vendor") },
                    placeholder = { Text("e.g. Nairobi Stationers Ltd") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = unitCostInput,
                    onValueChange = { input ->
                        unitCostInput = input.filter { it.isDigit() || it == '.' }.take(8)
                    },
                    label = { Text("Unit purchase cost (Ksh)") },
                    placeholder = { Text("e.g. 95") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (parsedUnitCost != null && parsedUnitCost > 0.0 && parsedQty > 0) {
                    val totalEstimatedCost = parsedUnitCost * parsedQty
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = colors.surfaceMuted,
                        border = BorderStroke(1.dp, colors.borderSubtle),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Total batch cost",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textMuted,
                            )
                            Text(
                                text = "Ksh ${totalEstimatedCost.roundToInt()}",
                                style = MaterialTheme.typography.titleSmall.tabular(),
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary,
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { updateBuyPrice = !updateBuyPrice },
                    ) {
                        Checkbox(
                            checked = updateBuyPrice,
                            onCheckedChange = { updateBuyPrice = it },
                        )
                        Text(
                            text = "Update catalogue buy price to Ksh ${parsedUnitCost.roundToInt()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textPrimary,
                        )
                    }
                }

                OutlinedTextField(
                    value = invoiceRefInput,
                    onValueChange = { invoiceRefInput = it },
                    label = { Text("Invoice / delivery note reference") },
                    placeholder = { Text("e.g. INV-2026-904") },
                    leadingIcon = { Icon(Icons.Outlined.Receipt, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val canSubmit = parsedQty > 0 && !isSubmitting
            EmofahLoadingButton(
                text = "Receive $parsedQty Unit${if (parsedQty == 1) "" else "s"}",
                loadingText = "Recording Stock Receipt...",
                isLoading = isSubmitting,
                enabled = canSubmit,
                icon = Icons.Outlined.AddBox,
                onClick = {
                    val draft = StockReceivingValidator.createDraft(
                        product = selectedProduct,
                        quantity = parsedQty,
                        unitCost = parsedUnitCost,
                        supplier = supplierInput,
                        invoiceRef = invoiceRefInput,
                        updateBuyPrice = updateBuyPrice,
                    )
                    onSubmitReceipt(draft)
                },
            )
        }
    }
}
