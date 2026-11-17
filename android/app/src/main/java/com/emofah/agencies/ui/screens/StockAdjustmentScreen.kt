package com.emofah.agencies.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import com.emofah.agencies.stock.StockAdjustmentValidator
import kotlin.math.abs

/**
 * Stage 4.3 — Stock Adjustment Screen (Blueprint Part F).
 * Supports recording inventory adjustments (damage, expiration, shrinkage, store use, count corrections)
 * with mandatory accounting reasons, explanation notes, supervisor authorization checks, and offline Room fallback.
 */
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
    var selectedReason by remember { mutableStateOf(StockAdjustmentReason.DAMAGED) }
    var adjustmentMagnitude by remember { mutableIntStateOf(1) }
    var notesInput by remember { mutableStateOf("") }
    var supervisorOverrideInput by remember { mutableStateOf("") }
    var manualSearchQuery by remember { mutableStateOf("") }
    var entryMode by remember { mutableStateOf(AdjustmentEntryMode.DELTA) }
    var countedPhysicalStockInput by remember(selectedProduct?.id, stockProjection?.provisionalAvailableQty) {
        val curr = stockProjection?.provisionalAvailableQty ?: selectedProduct?.stockQty ?: 0
        mutableStateOf(curr.toString())
    }

    val currentAvailable = stockProjection?.provisionalAvailableQty ?: selectedProduct?.stockQty ?: 0

    // Compute effective delta
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
        // Navigation Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Stock Adjustment",
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
                is StockAdjustmentResult.SuccessOnline -> Triple(
                    MaterialTheme.colorScheme.primaryContainer,
                    "Stock Adjusted Online · Synced",
                    "Delta (${if (outcome.quantityDelta > 0) "+${outcome.quantityDelta}" else "${outcome.quantityDelta}"}) applied. Server stock updated to ${outcome.updatedServerQty} units.",
                )
                is StockAdjustmentResult.SavedOffline -> Triple(
                    MaterialTheme.colorScheme.tertiaryContainer,
                    "Saved Locally Offline · Queued",
                    "Delta (${if (outcome.quantityDelta > 0) "+${outcome.quantityDelta}" else "${outcome.quantityDelta}"}) applied locally. Provisional stock: ${outcome.provisionalRemainingQty}. ${outcome.reasonMessage}",
                )
                is StockAdjustmentResult.DuplicateIgnored -> Triple(
                    MaterialTheme.colorScheme.secondaryContainer,
                    "Duplicate Tap Ignored",
                    "This adjustment was already applied (server stock: ${outcome.serverQty}).",
                )
                is StockAdjustmentResult.Rejected -> Triple(
                    MaterialTheme.colorScheme.errorContainer,
                    "Adjustment Rejected",
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
                            imageVector = if (outcome is StockAdjustmentResult.Rejected) Icons.Outlined.WarningAmber else Icons.Outlined.CheckCircle,
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
                }
            }
        }

        // Step 1: Select Product
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
                        text = "Step 1: Identify Product to Adjust",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Scan packaging with camera or select an existing product from catalogue.",
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
                        Text("Scan Product to Adjust")
                    }

                    OutlinedTextField(
                        value = manualSearchQuery,
                        onValueChange = { manualSearchQuery = it },
                        label = { Text("Or find in catalogue") },
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
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${product.category} · Stock: ${product.stockQty}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = "Select",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Selected Product Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = selectedProduct.name,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Surface(
                            modifier = Modifier.size(56.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Inventory2,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = selectedProduct.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${selectedProduct.category} · Available: $currentAvailable units",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    IconButton(onClick = onClearSelectedProduct) {
                        Icon(Icons.Outlined.Close, contentDescription = "Change product")
                    }
                }
            }

            // Step 2: Reason Selection
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Reason Code (Required)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
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
                                        onClick = {
                                            selectedReason = reason
                                        },
                                        label = {
                                            Text(
                                                text = reason.label,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = if (selectedReason == reason) FontWeight.Bold else FontWeight.Normal,
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = selectedReason.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Step 3: Quantity Controls
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Adjustment Quantity",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
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
                                label = { Text("Count Target") },
                            )
                        }
                    }

                    if (entryMode == AdjustmentEntryMode.DELTA) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                onClick = { adjustmentMagnitude = (adjustmentMagnitude - 1).coerceAtLeast(1) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Outlined.Remove, contentDescription = "Decrease")
                            }

                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "${if (selectedReason.isDecrease) "-" else "+"}$adjustmentMagnitude unit(s)",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }

                            Button(
                                onClick = { adjustmentMagnitude += 1 },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = "Increase")
                            }
                        }

                        // Quick Chips
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
                                    Text(text = "$chipQty")
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
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // Stock Comparison summary
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
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
                                    text = "Current Stock: $currentAvailable",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    text = "Adjustment: ${if (effectiveDelta > 0) "+$effectiveDelta" else "$effectiveDelta"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (effectiveDelta < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "New Provisional Stock",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Text(
                                    text = "$projectedNewStock units",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }

            // Step 4: Explanation Notes
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Explanation Notes (Required)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    OutlinedTextField(
                        value = notesInput,
                        onValueChange = { notesInput = it },
                        label = { Text("Details: batch, cause, location or action taken") },
                        placeholder = { Text("e.g. Lid cracked during offloading from supplier truck") },
                        minLines = 2,
                        maxLines = 4,
                        leadingIcon = { Icon(Icons.Outlined.EditNote, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "Minimum 3 characters. Mandatory for audit trail.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (notesInput.trim().length < 3 && notesInput.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Step 5: Authorization Notice / Supervisor Override
            val canAdjustDirectly = RolePermissionPolicy.hasPermission(staffRole, EmofahPermission.ADJUST_STOCK)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (!canAdjustDirectly) {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Security,
                            contentDescription = null,
                            tint = if (!canAdjustDirectly) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = if (!canAdjustDirectly) "Supervisor Authorization Required" else "Authorized by Role (${staffRole.label})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    if (!canAdjustDirectly) {
                        Text(
                            text = "Cashiers cannot authorize discretionary stock adjustments. Online sync also requires a Supervisor or Owner/Manager sign-in.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = supervisorOverrideInput,
                            onValueChange = { supervisorOverrideInput = it },
                            label = { Text("Supervisor PIN or Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // Submit Button
            val isFormValid = effectiveDelta != 0 &&
                notesInput.trim().length >= 3 &&
                (canAdjustDirectly || supervisorOverrideInput.trim().isNotEmpty())

            Button(
                onClick = {
                    if (!isFormValid || isSubmitting) return@Button
                    val draft = StockAdjustmentDraft(
                        productId = selectedProduct.id,
                        productName = selectedProduct.name,
                        currentAvailableQty = currentAvailable,
                        deltaQty = effectiveDelta,
                        reason = selectedReason,
                        notes = notesInput.trim(),
                        supervisorApprovedBy = if (!canAdjustDirectly) supervisorOverrideInput.trim() else null,
                    )
                    onSubmitAdjustment(draft)
                },
                enabled = isFormValid && !isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (effectiveDelta < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                ),
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(
                        text = "APPLY ADJUSTMENT (${if (effectiveDelta > 0) "+$effectiveDelta" else "$effectiveDelta"} UNITS)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

private enum class AdjustmentEntryMode {
    DELTA,
    COUNT_TARGET,
}
