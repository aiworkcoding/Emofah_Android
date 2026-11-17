package com.emofah.agencies.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.local.entities.LocalStockProjectionEntity
import com.emofah.agencies.stock.StockReceiptDraft
import com.emofah.agencies.stock.StockReceivingResult
import com.emofah.agencies.stock.StockReceivingValidator
import kotlin.math.roundToInt

/**
 * Stage 4.2 — Receive Stock Screen.
 * Allows store staff to quickly capture arriving stock shipments, update available stock,
 * capture supplier and purchase cost, attach optional invoice evidence, and commit online
 * or save locally offline.
 */
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

    val bitmap = remember(cachedPhotoPath) {
        cachedPhotoPath?.let { BitmapFactory.decodeFile(it) }
    }

    val manualProductMatches = remember(manualSearchQuery, cachedProducts) {
        val q = manualSearchQuery.trim().lowercase()
        if (q.length < 2) emptyList()
        else cachedProducts.filter {
            it.name.lowercase().contains(q) ||
                it.category.lowercase().contains(q) ||
                (it.variantLabel?.lowercase()?.contains(q) == true)
        }.take(6)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Top Navigation Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Receive Stock",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
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
                    Text("Stock Lookup")
                }
            }
        }
        // Feedback Card from last submission
        lastOutcome?.let { outcome ->
            val (containerColor, title, message) = when (outcome) {
                is StockReceivingResult.SuccessOnline -> Triple(
                    MaterialTheme.colorScheme.primaryContainer,
                    "Stock Received Online · Synced",
                    "+${outcome.quantityReceived} unit(s) recorded in Supabase. New server stock: ${outcome.updatedServerQty}.",
                )
                is StockReceivingResult.SavedOffline -> Triple(
                    MaterialTheme.colorScheme.tertiaryContainer,
                    "Saved Locally Offline · Queued",
                    "+${outcome.quantityReceived} unit(s) applied to local projection. Provisional stock: ${outcome.provisionalRemainingQty}. ${outcome.reason}",
                )
                is StockReceivingResult.DuplicateIgnored -> Triple(
                    MaterialTheme.colorScheme.secondaryContainer,
                    "Duplicate Tap Ignored",
                    "This stock receipt was already committed (current stock: ${outcome.serverQty}).",
                )
                is StockReceivingResult.Rejected -> Triple(
                    MaterialTheme.colorScheme.errorContainer,
                    "Stock Receipt Rejected",
                    outcome.message,
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = containerColor),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = if (outcome is StockReceivingResult.Rejected) Icons.Outlined.WarningAmber else Icons.Outlined.CheckCircle,
                            contentDescription = null,
                        )
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = onScanNext,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Scan Next Arriving Product")
                    }
                }
            }
        }

        // Section 1: Product Selection Header
        if (selectedProduct == null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Step 1: Identify Arriving Product",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Scan the product packaging with camera, or select it from the catalogue below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onScanToSelect,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.CameraAlt, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Scan Arriving Product")
                    }

                    OutlinedTextField(
                        value = manualSearchQuery,
                        onValueChange = { manualSearchQuery = it },
                        label = { Text("Or find product in catalogue") },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    manualProductMatches.forEach { product ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectProductManually(product) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                                        text = product.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = "${product.category} · Stock: ${product.stockQty}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = "Select",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    if (onRegisterNewProduct != null) {
                        OutlinedButton(
                            onClick = { onRegisterNewProduct(manualSearchQuery.trim()) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.AddBox, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (manualSearchQuery.trim().length >= 2) {
                                    "Not found? Register \"${manualSearchQuery.trim().take(24)}\""
                                } else {
                                    "Product not in catalogue? Register New Product"
                                }
                            )
                        }
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = selectedProduct.name,
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

                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = selectedProduct.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitle = listOfNotNull(
                            selectedProduct.brandHint,
                            selectedProduct.variantLabel,
                            selectedProduct.category,
                        ).joinToString(" · ")
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        val currentAvail = stockProjection?.provisionalAvailableQty ?: selectedProduct.stockQty
                        Text(
                            text = "Current available stock: $currentAvail units",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }

                    IconButton(onClick = onClearSelectedProduct) {
                        Icon(Icons.Outlined.Close, contentDescription = "Change product")
                    }
                }
            }

            // Section 2: Quantity Received
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Quantity Received *",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        IconButton(
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
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        )

                        IconButton(
                            onClick = {
                                val current = parsedQty
                                rawQuantityInput = (current + 1).toString()
                            },
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = "Increase")
                        }
                    }

                    // Quick increment chips
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
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("+$bump", maxLines = 1)
                            }
                        }
                    }
                }
            }

            // Section 3: Commercial & Supplier Details (Optional)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Supplier & Cost Details (Optional)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )

                    OutlinedTextField(
                        value = supplierInput,
                        onValueChange = { supplierInput = it },
                        label = { Text("Supplier name / vendor") },
                        placeholder = { Text("e.g. Nairobi Stationers Ltd") },
                        singleLine = true,
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
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (parsedUnitCost != null && parsedUnitCost > 0.0 && parsedQty > 0) {
                        val totalEstimatedCost = parsedUnitCost * parsedQty
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "Total batch purchase cost:",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    text = "Ksh ${totalEstimatedCost.roundToInt()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { updateBuyPrice = !updateBuyPrice },
                        ) {
                            Checkbox(
                                checked = updateBuyPrice,
                                onCheckedChange = { updateBuyPrice = it },
                            )
                            Text(
                                text = "Update catalogue purchase price (buy_price) to Ksh ${parsedUnitCost.roundToInt()}",
                                style = MaterialTheme.typography.bodySmall,
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
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Primary Action: Submit Receipt
            val canSubmit = parsedQty > 0 && !isSubmitting
            Button(
                onClick = {
                    if (!canSubmit) return@Button
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
                enabled = canSubmit,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Recording Stock Receipt...")
                } else {
                    Icon(Icons.Outlined.AddBox, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "RECEIVE $parsedQty UNIT${if (parsedQty == 1) "" else "S"}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
