package com.emofah.agencies.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PointOfSale
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.local.entities.LocalStockProjectionEntity
import com.emofah.agencies.data.local.entities.PendingFinancialEventEntity
import com.emofah.agencies.receipts.DigitalReceiptData
import com.emofah.agencies.sales.PaymentMethod
import com.emofah.agencies.sales.PreparedSaleDraft
import com.emofah.agencies.sales.PriceOverridePolicy
import com.emofah.agencies.sales.SaleCalculator
import com.emofah.agencies.sales.StaffRole
import com.emofah.agencies.sales.StockPolicy
import com.emofah.agencies.ui.components.EmofahAnimatedVisibility
import com.emofah.agencies.ui.components.EmofahBadgeTone
import com.emofah.agencies.ui.components.EmofahCard
import com.emofah.agencies.ui.components.EmofahEmptyState
import com.emofah.agencies.ui.components.EmofahFeedbackBanner
import com.emofah.agencies.ui.components.EmofahInteractiveCard
import com.emofah.agencies.ui.components.EmofahLoadingButton
import com.emofah.agencies.ui.components.EmofahLocalImage
import com.emofah.agencies.ui.components.EmofahSearchField
import com.emofah.agencies.ui.components.EmofahSectionHeader
import com.emofah.agencies.ui.components.EmofahStatusBadge
import com.emofah.agencies.ui.theme.emofahColors
import com.emofah.agencies.ui.theme.tabular
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

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
 * Refined single-product Record Sale screen with async product thumbnails,
 * virtualized manual catalogue picker, tactile quantity stepper, and M-Pesa linking.
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
    pendingMpesaEvent: PendingFinancialEventEntity? = null,
    onClearPendingMpesa: () -> Unit = {},
) {
    if (selectedProduct == null) {
        NoProductSelectedView(
            cachedProducts = cachedProducts,
            pendingMpesaEvent = pendingMpesaEvent,
            onClearPendingMpesa = onClearPendingMpesa,
            onScanProduct = onScanAnother,
            onPickProduct = onSelectProductManually,
            modifier = modifier,
        )
        return
    }

    val colors = MaterialTheme.emofahColors
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

    val sessionDraftSeed = remember(selectedProduct.id, saleSessionNonce) {
        SaleCalculator.createDraft(selectedProduct, 1, selectedPaymentMethod)
    }

    val availableQty = stockProjection?.provisionalAvailableQty ?: selectedProduct.stockQty
    val freshnessText = remember(stockProjection) {
        if (stockProjection == null) {
            "Saved on phone"
        } else {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(Date(stockProjection.serverUpdatedAtEpochMs))
            if (stockProjection.pendingDeltaQty != 0) "Estimated · $time" else "Updated $time"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (pendingMpesaEvent != null) {
            MpesaLinkBanner(
                mpesaEvent = pendingMpesaEvent,
                onClear = onClearPendingMpesa,
            )
        }

        // Post-Sale Confirmation Card
        EmofahAnimatedVisibility(visible = lastSaleFeedback != null) {
            lastSaleFeedback?.let { feedback ->
                val isSuccess = feedback.kind != SaleFeedbackKind.REJECTED
                val tone = when (feedback.kind) {
                    SaleFeedbackKind.ONLINE_CONFIRMED -> EmofahBadgeTone.SUCCESS
                    SaleFeedbackKind.OFFLINE_QUEUED -> EmofahBadgeTone.WARNING
                    SaleFeedbackKind.REJECTED -> EmofahBadgeTone.DANGER
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EmofahFeedbackBanner(
                        title = feedback.title,
                        message = feedback.detail,
                        tone = tone,
                    )
                    if (isSuccess) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = onScanAnother,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp),
                            ) {
                                Icon(Icons.Outlined.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Scan Next", fontWeight = FontWeight.SemiBold)
                            }
                            feedback.receipt?.let { receiptData ->
                                OutlinedButton(
                                    onClick = { previewedReceipt = receiptData },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp),
                                ) {
                                    Icon(Icons.Outlined.ReceiptLong, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Receipt")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Product Summary Header
        EmofahCard(contentPadding = PaddingValues(14.dp)) {
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
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = selectedProduct.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (validation.isPriceOverridden) {
                            "Overridden: Ksh ${validation.unitSellPrice.toInt()} (Reg. Ksh ${selectedProduct.effectiveSellPrice.toInt()})"
                        } else {
                            "Unit price: Ksh ${selectedProduct.effectiveSellPrice.toInt()}"
                        },
                        style = MaterialTheme.typography.bodyMedium.tabular(),
                        color = if (validation.isPriceOverridden) colors.warning else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Available: $availableQty ($freshnessText)",
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = colors.textMuted,
                    )
                }
            }
        }

        // Quantity Stepper & Quick Presets
        EmofahCard(contentPadding = PaddingValues(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Quantity Sold",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                EmofahStatusBadge(
                    text = "${validation.quantity} unit(s)",
                    tone = EmofahBadgeTone.NEUTRAL,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        val next = (parsedQty - 1).coerceAtLeast(1)
                        quantityText = next.toString()
                    },
                    enabled = parsedQty > 1 && !isSubmitting,
                    modifier = Modifier
                        .size(50.dp)
                        .background(colors.surfaceMuted, RoundedCornerShape(12.dp)),
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
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = MaterialTheme.typography.titleLarge.tabular().copy(
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
                        .size(50.dp)
                        .background(colors.surfaceMuted, RoundedCornerShape(12.dp)),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "Increase quantity")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(1, 2, 5, 10).forEach { preset ->
                    FilterChip(
                        selected = parsedQty == preset,
                        onClick = { quantityText = preset.toString() },
                        label = { Text("$preset", style = MaterialTheme.typography.labelMedium.tabular()) },
                        enabled = !isSubmitting,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Payment Method Selector
        EmofahCard(contentPadding = PaddingValues(14.dp)) {
            Text(
                text = "Payment Method",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PaymentMethod.entries.forEach { method ->
                    val selected = selectedPaymentMethod == method
                    if (selected) {
                        Button(
                            onClick = { onSelectPaymentMethod(method) },
                            enabled = !isSubmitting,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(method.label, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onSelectPaymentMethod(method) },
                            enabled = !isSubmitting,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(method.label)
                        }
                    }
                }
            }
        }

        // Open Price Exception Control (audit reason required when changed)
        if (!showPriceOverride) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
            EmofahCard(contentPadding = PaddingValues(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Price Exception",
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
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = overrideReasonText,
                    onValueChange = { overrideReasonText = it },
                    label = { Text("Override Reason (required for audit)") },
                    singleLine = true,
                    enabled = !isSubmitting,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        validation.blockingReason?.let { reason ->
            EmofahFeedbackBanner(
                title = "Cannot complete sale",
                message = reason,
                tone = EmofahBadgeTone.DANGER,
            )
        }

        validation.stockWarning?.let { warning ->
            EmofahFeedbackBanner(
                title = "Stock Notice",
                message = warning,
                tone = EmofahBadgeTone.WARNING,
            )
        }

        // Calculated Total Summary
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = colors.infoContainer,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, colors.info.copy(alpha = 0.24f)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${validation.quantity} × Ksh ${validation.unitSellPrice.toInt()}",
                    style = MaterialTheme.typography.bodyMedium.tabular(),
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                )
                Text(
                    text = "Total: Ksh ${validation.totalAmount.toInt()}",
                    style = MaterialTheme.typography.titleLarge.tabular(),
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
            }
        }

        // Primary Submit Action
        EmofahLoadingButton(
            text = "RECORD SALE · Ksh ${validation.totalAmount.toInt()}",
            loadingText = "Recording Sale...",
            isLoading = isSubmitting,
            enabled = validation.isValidToRecord && !isSubmitting,
            icon = Icons.Outlined.PointOfSale,
            height = 58.dp,
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
        )

        // Auto-return toggle
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = colors.surfaceCard,
            border = BorderStroke(1.dp, colors.borderSubtle),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onToggleAutoReturnToScan(!autoReturnToScan) },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = autoReturnToScan,
                    onCheckedChange = onToggleAutoReturnToScan,
                )
                Text(
                    text = "Auto-open Scan camera right after recording a sale",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }

        OutlinedButton(
            onClick = onScanAnother,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
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
    pendingMpesaEvent: PendingFinancialEventEntity? = null,
    onClearPendingMpesa: () -> Unit = {},
    onScanProduct: () -> Unit,
    onPickProduct: (ProductIdentity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.emofahColors
    var search by remember { mutableStateOf("") }
    val filtered = remember(search, cachedProducts) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) cachedProducts.take(30)
        else cachedProducts.filter {
            it.name.lowercase().contains(q) || it.category.lowercase().contains(q)
        }.take(30)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (pendingMpesaEvent != null) {
            item(key = "mpesa_banner") {
                MpesaLinkBanner(
                    mpesaEvent = pendingMpesaEvent,
                    onClear = onClearPendingMpesa,
                )
            }
        }

        item(key = "scan_hero") {
            EmofahLoadingButton(
                text = "Scan Product to Sell",
                onClick = onScanProduct,
                icon = Icons.Outlined.CameraAlt,
                height = 56.dp,
            )
        }

        item(key = "search_bar") {
            EmofahSearchField(
                value = search,
                onValueChange = { search = it },
                placeholder = "Or pick a product by name or category",
            )
        }

        item(key = "catalog_header") {
            EmofahSectionHeader(
                title = "Shop Catalogue (${filtered.size})",
                subtitle = "Tap any item to start recording a sale",
            )
        }

        if (filtered.isEmpty()) {
            item(key = "empty_catalog") {
                EmofahEmptyState(
                    icon = Icons.Outlined.Inventory2,
                    title = if (search.isBlank()) "No Saved Products Yet" else "No Matching Products",
                    message = if (search.isBlank()) {
                        "Connect to the internet once to download your shop catalogue, or scan a product."
                    } else {
                        "No items matched \"$search\"."
                    },
                )
            }
        } else {
            items(filtered, key = { it.id }) { item ->
                EmofahInteractiveCard(
                    onClick = { onPickProduct(item) },
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                            )
                            Text(
                                text = "Stock: ${item.stockQty} · ${item.category}",
                                style = MaterialTheme.typography.labelSmall.tabular(),
                                color = colors.textMuted,
                            )
                        }
                        Text(
                            text = "Ksh ${item.effectiveSellPrice.toInt()}",
                            style = MaterialTheme.typography.labelLarge.tabular(),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MpesaLinkBanner(
    mpesaEvent: PendingFinancialEventEntity,
    onClear: () -> Unit,
) {
    EmofahFeedbackBanner(
        title = "Linking M-Pesa Payment: Ksh ${mpesaEvent.amount.roundToInt()}",
        message = listOfNotNull(
            mpesaEvent.counterparty?.takeIf { it.isNotBlank() }?.let { "From $it" },
            mpesaEvent.transactionReference?.takeIf { it.isNotBlank() }?.let { "Ref $it" },
        ).joinToString(" · ").ifBlank { "Pick the product sold to link this payment" },
        tone = EmofahBadgeTone.INFO,
        actionLabel = "Unlink",
        onAction = onClear,
    )
}
