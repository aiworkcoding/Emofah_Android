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
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PointOfSale
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.local.entities.LocalStockProjectionEntity
import com.emofah.agencies.receipts.DigitalReceiptData
import com.emofah.agencies.sales.PaymentMethod
import com.emofah.agencies.sales.PreparedSaleDraft
import com.emofah.agencies.sales.PriceOverridePolicy
import com.emofah.agencies.sales.SaleCalculator
import com.emofah.agencies.sales.StaffRole
import com.emofah.agencies.sales.StockPolicy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SaleFeedbackKind {
    ONLINE_CONFIRMED,
    OFFLINE_QUEUED,
    REJECTED,
}

data class SaleFeedbackUi(
    val kind: SaleFeedbackKind,
    val title: String,
    val detail: String,
    val receipt: DigitalReceiptData? = null,
)

/**
 * Stage 2.1 – 2.3 (Actions 2.1.1 – 2.3.2):
 * Frictionless single-product sale screen with:
 * - Default quantity = 1, persisted payment method, zero forced customer fields.
 * - Prominent one-touch RECORD SALE action and rapid return to Scan Product.
 * - Policy-guarded, audited price exception controls visible only to authorized roles.
 */
@Composable
fun RecordSaleScreen(
    selectedProduct: ProductIdentity?,
    stockProjection: LocalStockProjectionEntity?,
    cachedPhotoPath: String?,
    cachedProducts: List<ProductIdentity>,
    selectedPaymentMethod: PaymentMethod,
    staffRole: StaffRole = StaffRole.CASHIER,
    autoReturnToScan: Boolean = false,
    isSubmitting: Boolean,
    saleSessionNonce: Int,
    lastSaleFeedback: SaleFeedbackUi?,
    onSelectPaymentMethod: (PaymentMethod) -> Unit,
    onToggleAutoReturnToScan: (Boolean) -> Unit = {},
    onSelectProductManually: (ProductIdentity) -> Unit,
    onScanAnother: () -> Unit,
    onRecordSaleClick: (PreparedSaleDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selectedProduct == null) {
        NoProductSelectedView(
            cachedProducts = cachedProducts,
            onScanProduct = onScanAnother,
            onPickProduct = onSelectProductManually,
            modifier = modifier,
        )
        return
    }

    val canOverridePrice = remember(staffRole) {
        PriceOverridePolicy.canOverridePrice(staffRole)
    }

    var quantityText by remember(selectedProduct.id, saleSessionNonce) { mutableStateOf("1") }
    var showPriceOverride by remember(selectedProduct.id, saleSessionNonce) { mutableStateOf(false) }
    var overridePriceText by remember(selectedProduct.id, saleSessionNonce) {
        mutableStateOf(selectedProduct.effectiveSellPrice.toInt().toString())
    }
    var overrideReasonText by remember(selectedProduct.id, saleSessionNonce) { mutableStateOf("") }
    var previewedReceipt by remember { mutableStateOf<DigitalReceiptData?>(null) }

    val parsedQty = remember(quantityText) { SaleCalculator.parseQuantityInput(quantityText) }
    val requestedOverridePrice = if (canOverridePrice && showPriceOverride) {
        SaleCalculator.parsePriceInput(overridePriceText)
    } else {
        null
    }

    val validation = remember(
        selectedProduct,
        stockProjection,
        parsedQty,
        requestedOverridePrice,
        overrideReasonText,
        staffRole,
    ) {
        SaleCalculator.validateAndCalculate(
            product = selectedProduct,
            stockProjection = stockProjection,
            rawQuantity = parsedQty,
            stockPolicy = StockPolicy.ALLOW_WITH_WARNING,
            overrideUnitPrice = requestedOverridePrice,
            overrideReason = if (showPriceOverride) overrideReasonText else null,
            staffRole = staffRole,
            requireOverrideReason = true,
        )
    }

    // Stable draft identity per product sale session so retries cannot duplicate,
    // incrementing saleSessionNonce only after a completed sale clears it.
    val sessionDraftSeed = remember(selectedProduct.id, saleSessionNonce) {
        SaleCalculator.createDraft(selectedProduct, 1, selectedPaymentMethod)
    }

    val photoBitmap = remember(cachedPhotoPath) {
        cachedPhotoPath?.let { BitmapFactory.decodeFile(it) }
    }
    val availableQty = stockProjection?.provisionalAvailableQty ?: selectedProduct.stockQty
    val freshnessText = remember(stockProjection) {
        if (stockProjection == null) {
            "Cached stock"
        } else {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(Date(stockProjection.serverUpdatedAtEpochMs))
            if (stockProjection.pendingDeltaQty != 0) "Provisional · synced $time" else "Server synced $time"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Post-Sale Confirmation Banner at top with immediate one-tap Return to Scan
        lastSaleFeedback?.let { feedback ->
            val isSuccess = feedback.kind != SaleFeedbackKind.REJECTED
            val containerColor = when (feedback.kind) {
                SaleFeedbackKind.ONLINE_CONFIRMED -> MaterialTheme.colorScheme.primaryContainer
                SaleFeedbackKind.OFFLINE_QUEUED -> MaterialTheme.colorScheme.tertiaryContainer
                SaleFeedbackKind.REJECTED -> MaterialTheme.colorScheme.errorContainer
            }
            val contentColor = when (feedback.kind) {
                SaleFeedbackKind.ONLINE_CONFIRMED -> MaterialTheme.colorScheme.onPrimaryContainer
                SaleFeedbackKind.OFFLINE_QUEUED -> MaterialTheme.colorScheme.onTertiaryContainer
                SaleFeedbackKind.REJECTED -> MaterialTheme.colorScheme.onErrorContainer
            }
            val icon = when (feedback.kind) {
                SaleFeedbackKind.ONLINE_CONFIRMED -> Icons.Outlined.CloudDone
                SaleFeedbackKind.OFFLINE_QUEUED -> Icons.Outlined.CloudOff
                SaleFeedbackKind.REJECTED -> Icons.Outlined.ErrorOutline
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = containerColor),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = contentColor,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = feedback.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = contentColor,
                            )
                            Text(
                                text = feedback.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = contentColor,
                            )
                        }
                    }
                    if (isSuccess) {
                        Button(
                            onClick = onScanAnother,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                        ) {
                            Icon(Icons.Outlined.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Scan Next Product", fontWeight = FontWeight.Bold)
                        }
                        feedback.receipt?.let { receiptData ->
                            OutlinedButton(
                                onClick = { previewedReceipt = receiptData },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp),
                            ) {
                                Icon(Icons.Outlined.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("View / Share Receipt")
                            }
                        }
                    }
                }
            }
        }

        // Product Summary Header
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
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
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (photoBitmap != null) {
                        Image(
                            bitmap = photoBitmap.asImageBitmap(),
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
                    )
                    Text(
                        text = if (validation.isPriceOverridden) {
                            "Overridden: Ksh ${validation.unitSellPrice.toInt()} (Reg. Ksh ${selectedProduct.effectiveSellPrice.toInt()})"
                        } else {
                            "Unit price: Ksh ${selectedProduct.effectiveSellPrice.toInt()}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (validation.isPriceOverridden) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Available: $availableQty ($freshnessText)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Quantity Control (Action 2.1.2 & 2.3.1: defaults to 1, instant stepper + numeric input)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Quantity Sold",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            val next = (parsedQty - 1).coerceAtLeast(1)
                            quantityText = next.toString()
                        },
                        enabled = parsedQty > 1 && !isSubmitting,
                        modifier = Modifier
                            .size(52.dp)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)),
                    ) {
                        Icon(Icons.Outlined.Remove, contentDescription = "Decrease quantity")
                    }

                    OutlinedTextField(
                        value = quantityText,
                        onValueChange = { raw ->
                            val filtered = raw.filter { it.isDigit() }.take(5)
                            quantityText = filtered
                        },
                        singleLine = true,
                        enabled = !isSubmitting,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        ),
                        modifier = Modifier.weight(1f),
                    )

                    IconButton(
                        onClick = {
                            val next = (parsedQty + 1).coerceAtLeast(1)
                            quantityText = next.toString()
                        },
                        enabled = !isSubmitting,
                        modifier = Modifier
                            .size(52.dp)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = "Increase quantity")
                    }
                }
            }
        }

        // Payment Method Selector (Persisted across sales; zero forced customer details)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PaymentMethod.entries.forEach { method ->
                FilterChip(
                    selected = selectedPaymentMethod == method,
                    onClick = { onSelectPaymentMethod(method) },
                    label = { Text(method.label) },
                    enabled = !isSubmitting,
                )
            }
        }

        // Action 2.3.2: Role-guarded Price Exception Control
        if (canOverridePrice) {
            if (!showPriceOverride) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Authorized (${staffRole.label})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            overridePriceText = selectedProduct.effectiveSellPrice.toInt().toString()
                            showPriceOverride = true
                        },
                        enabled = !isSubmitting,
                    ) {
                        Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Override Price")
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Authorized Price Exception",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            TextButton(
                                onClick = {
                                    showPriceOverride = false
                                    overrideReasonText = ""
                                    overridePriceText = selectedProduct.effectiveSellPrice.toInt().toString()
                                },
                                enabled = !isSubmitting,
                            ) {
                                Text("Reset")
                            }
                        }
                        OutlinedTextField(
                            value = overridePriceText,
                            onValueChange = { overridePriceText = it },
                            label = { Text("Override Unit Price (Reg. Ksh ${selectedProduct.effectiveSellPrice.toInt()})") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            enabled = !isSubmitting,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = overrideReasonText,
                            onValueChange = { overrideReasonText = it },
                            label = { Text("Override Reason (required for audit)") },
                            singleLine = true,
                            enabled = !isSubmitting,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = "Registered price locked (${staffRole.label}). Manager/Supervisor needed to override.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Warnings / Blocking messages
        validation.blockingReason?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium,
            )
        }

        validation.stockWarning?.let { warning ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.WarningAmber, contentDescription = null)
                    Text(text = warning, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Calculated Total Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(18.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${validation.quantity} × Ksh ${validation.unitSellPrice.toInt()}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "Total: Ksh ${validation.totalAmount.toInt()}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Prominent One-Touch RECORD SALE Button (Action 2.1.3)
        Button(
            onClick = {
                if (!isSubmitting && validation.isValidToRecord) {
                    val draft = SaleCalculator.createDraft(
                        product = selectedProduct,
                        quantity = validation.quantity,
                        paymentMethod = selectedPaymentMethod,
                        existingDraftId = sessionDraftSeed.clientSaleId,
                        existingIdempotencyKey = sessionDraftSeed.idempotencyKey,
                        overrideUnitPrice = if (validation.isPriceOverridden) validation.unitSellPrice else null,
                        priceOverrideReason = if (validation.isPriceOverridden) overrideReasonText else null,
                    )
                    onRecordSaleClick(draft)
                }
            },
            enabled = validation.isValidToRecord && !isSubmitting,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
        ) {
            Icon(Icons.Outlined.PointOfSale, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (isSubmitting) "RECORDING..." else "RECORD SALE · Ksh ${validation.totalAmount.toInt()}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        // Action 2.3.1: Optional fast-scan mode toggle & instant Scan return
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { onToggleAutoReturnToScan(!autoReturnToScan) }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = autoReturnToScan,
                onCheckedChange = onToggleAutoReturnToScan,
            )
            Text(
                text = "Auto-open Scan camera right after recording a sale",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedButton(
            onClick = onScanAnother,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Icon(Icons.Outlined.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Scan Another Product")
        }
    }

    previewedReceipt?.let { receiptData ->
        DigitalReceiptPreviewDialog(
            receipt = receiptData,
            onDismiss = { previewedReceipt = null },
        )
    }
}

@Composable
private fun NoProductSelectedView(
    cachedProducts: List<ProductIdentity>,
    onScanProduct: () -> Unit,
    onPickProduct: (ProductIdentity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var search by remember { mutableStateOf("") }
    val filtered = remember(search, cachedProducts) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) cachedProducts.take(15)
        else cachedProducts.filter { it.name.lowercase().contains(q) }.take(15)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onScanProduct,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Icon(Icons.Outlined.CameraAlt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Scan Product to Sell", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("Or pick from cached catalogue") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        filtered.forEach { item ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPickProduct(item) },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Stock: ${item.stockQty} · ${item.category}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Ksh ${item.effectiveSellPrice.toInt()}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
