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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.purchases.ExtractedReceiptLineDraft
import com.emofah.agencies.purchases.PurchaseItemCategory
import com.emofah.agencies.purchases.PurchaseReceiptDraft
import com.emofah.agencies.purchases.PurchaseSubmissionResult
import com.emofah.agencies.purchases.ReceiptArithmeticValidator
import com.emofah.agencies.purchases.ReceiptDraftValidator
import com.emofah.agencies.ui.components.EmofahBadgeTone
import com.emofah.agencies.ui.components.EmofahCard
import com.emofah.agencies.ui.components.EmofahEmptyState
import com.emofah.agencies.ui.components.EmofahFeedbackBanner
import com.emofah.agencies.ui.components.EmofahInteractiveCard
import com.emofah.agencies.ui.components.EmofahLoadingButton
import com.emofah.agencies.ui.components.EmofahLocalImage
import com.emofah.agencies.ui.components.EmofahSearchField
import com.emofah.agencies.ui.components.EmofahSectionHeader
import com.emofah.agencies.ui.components.EmofahSkeletonCard
import com.emofah.agencies.ui.components.EmofahStatusBadge
import com.emofah.agencies.ui.theme.emofahColors
import com.emofah.agencies.ui.theme.tabular
import kotlin.math.abs
import kotlin.math.roundToInt

private val NEW_PRODUCT_CATEGORIES = listOf("Stationery", "Books", "Electronics", "General")

@Composable
fun ReceiptCaptureReviewScreen(
    draft: PurchaseReceiptDraft,
    cachedProducts: List<ProductIdentity>,
    isExtracting: Boolean,
    extractionError: String?,
    onSnapReceipt: () -> Unit,
    onUpdateDraft: (PurchaseReceiptDraft) -> Unit,
    onResetBlank: () -> Unit,
    onBack: () -> Unit,
    isSubmitting: Boolean = false,
    recordFinancialOutflow: Boolean = true,
    onToggleFinancialOutflow: (Boolean) -> Unit = {},
    submissionResult: PurchaseSubmissionResult? = null,
    onSubmitPurchase: () -> Unit = {},
    onScanNextReceipt: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.emofahColors
    val activeCatalog = remember(cachedProducts) {
        cachedProducts.filter { !it.isArchived && it.id > 0L }
    }
    var pickerLineId by remember { mutableStateOf<String?>(null) }
    var subtotalText by remember(draft.clientPurchaseId, draft.subtotalAmount) {
        mutableStateOf(
            if (draft.subtotalAmount > 0.0) {
                if (draft.subtotalAmount % 1.0 == 0.0) draft.subtotalAmount.toInt().toString()
                else draft.subtotalAmount.toString()
            } else ""
        )
    }
    var discountText by remember(draft.clientPurchaseId, draft.discountAmount) {
        mutableStateOf(
            if (draft.discountAmount > 0.0) {
                if (draft.discountAmount % 1.0 == 0.0) draft.discountAmount.toInt().toString()
                else draft.discountAmount.toString()
            } else ""
        )
    }
    var taxText by remember(draft.clientPurchaseId, draft.taxAmount) {
        mutableStateOf(
            if (draft.taxAmount > 0.0) {
                if (draft.taxAmount % 1.0 == 0.0) draft.taxAmount.toInt().toString()
                else draft.taxAmount.toString()
            } else ""
        )
    }
    var printedTotalText by remember(draft.clientPurchaseId, draft.extractedTotal) {
        mutableStateOf(
            if (draft.extractedTotal > 0.0) {
                if (draft.extractedTotal % 1.0 == 0.0) draft.extractedTotal.toInt().toString()
                else draft.extractedTotal.toString()
            } else {
                ""
            },
        )
    }

    val validation = remember(draft) { ReceiptDraftValidator.validate(draft) }
    val arithmeticReport = remember(draft) { ReceiptArithmeticValidator.validate(draft) }

    fun updateDraftWithReset(next: PurchaseReceiptDraft) {
        onUpdateDraft(next)
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
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                    )
                }
                Column {
                    Text(
                        text = "Scan Purchase Receipt",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = "Review OCR/AI extraction before saving",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textMuted,
                    )
                }
            }
            EmofahStatusBadge(
                text = draft.extractionSource.label,
                tone = EmofahBadgeTone.INFO,
            )
        }

        EmofahCard {
            if (!draft.photoPath.isNullOrBlank()) {
                EmofahLocalImage(
                    path = draft.photoPath,
                    contentDescription = "Captured receipt photograph",
                    fallbackIcon = Icons.Outlined.ReceiptLong,
                    maxDimensionPx = 768,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )
            }

            if (isExtracting) {
                EmofahFeedbackBanner(
                    title = "Extracting Receipt Items...",
                    message = "Reading supplier, line items, and totals via ML Kit OCR & AI extractor.",
                    tone = EmofahBadgeTone.INFO,
                )
                EmofahSkeletonCard()
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onSnapReceipt,
                    enabled = !isExtracting && !isSubmitting,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (draft.photoPath.isNullOrBlank()) "Photograph Receipt" else "Retake Receipt")
                }
                OutlinedButton(
                    onClick = onResetBlank,
                    enabled = !isExtracting && !isSubmitting,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("Clear")
                }
            }
        }

        extractionError?.let { err ->
            EmofahFeedbackBanner(
                title = "Extraction Warning",
                message = err,
                tone = EmofahBadgeTone.DANGER,
            )
        }

        if (draft.warnings.isNotEmpty()) {
            EmofahFeedbackBanner(
                title = "Human Review Required",
                message = draft.warnings.joinToString("\n") { "• $it" },
                tone = EmofahBadgeTone.WARNING,
            )
        }

        EmofahCard {
            EmofahSectionHeader(title = "Supplier & Receipt Totals")

            OutlinedTextField(
                value = draft.supplierName,
                onValueChange = { updateDraftWithReset(draft.copy(supplierName = it, isHumanConfirmed = false)) },
                label = { Text("Supplier Name") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft.invoiceRef,
                    onValueChange = { updateDraftWithReset(draft.copy(invoiceRef = it, isHumanConfirmed = false)) },
                    label = { Text("Receipt / Inv No.") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = draft.receiptDateText,
                    onValueChange = { updateDraftWithReset(draft.copy(receiptDateText = it, isHumanConfirmed = false)) },
                    label = { Text("Date") },
                    placeholder = { Text("DD/MM/YYYY") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft.paymentMethod,
                    onValueChange = { updateDraftWithReset(draft.copy(paymentMethod = it, isHumanConfirmed = false)) },
                    label = { Text("Payment Method") },
                    placeholder = { Text("Cash / M-Pesa / Bank") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = draft.paymentReference,
                    onValueChange = { updateDraftWithReset(draft.copy(paymentReference = it, isHumanConfirmed = false)) },
                    label = { Text("Payment Ref") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = subtotalText,
                    onValueChange = { raw ->
                        subtotalText = raw
                        val parsed = raw.replace(",", "").toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                        updateDraftWithReset(draft.copy(subtotalAmount = parsed, isHumanConfirmed = false))
                    },
                    label = { Text("Subtotal (Ksh)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = discountText,
                    onValueChange = { raw ->
                        discountText = raw
                        val parsed = raw.replace(",", "").toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                        updateDraftWithReset(draft.copy(discountAmount = parsed, isHumanConfirmed = false))
                    },
                    label = { Text("Discount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = taxText,
                    onValueChange = { raw ->
                        taxText = raw
                        val parsed = raw.replace(",", "").toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                        updateDraftWithReset(draft.copy(taxAmount = parsed, isHumanConfirmed = false))
                    },
                    label = { Text("VAT / Tax") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                )
            }

            OutlinedTextField(
                value = printedTotalText,
                onValueChange = { raw ->
                    printedTotalText = raw
                    val parsed = raw.replace(",", "").toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                    updateDraftWithReset(draft.copy(extractedTotal = parsed, isHumanConfirmed = false))
                },
                label = { Text("Printed Grand Total (Ksh)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            val computed = draft.computedLinesTotal
            val diff = if (draft.extractedTotal > 0.0) abs(draft.extractedTotal - computed) else 0.0
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.surfaceMuted,
                border = BorderStroke(1.dp, colors.borderSubtle),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Computed line sum: Ksh ${computed.roundToInt()}",
                        style = MaterialTheme.typography.labelMedium.tabular(),
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    )
                    if (arithmeticReport.hasDiscrepancies || diff > 1.5) {
                        EmofahStatusBadge(
                            text = if (diff > 1.5) "Differs Ksh ${diff.roundToInt()}" else "Check math",
                            tone = EmofahBadgeTone.DANGER,
                        )
                    }
                }
            }

            if (arithmeticReport.warnings.isNotEmpty()) {
                EmofahFeedbackBanner(
                    title = "Arithmetic Discrepancy Flagged",
                    message = arithmeticReport.warnings.joinToString("\n") { "• $it" },
                    tone = EmofahBadgeTone.WARNING,
                )
            }
        }

        EmofahSectionHeader(
            title = "Purchased Items (${draft.lines.size})",
            trailingActionLabel = "+ Add Item",
            onTrailingAction = {
                val blankLine = ExtractedReceiptLineDraft(
                    rawName = "",
                    itemCategory = PurchaseItemCategory.RESALE_PRODUCT,
                    quantity = 1,
                    unitCost = 0.0,
                    lineTotal = 0.0,
                )
                updateDraftWithReset(
                    draft.copy(
                        lines = draft.lines + blankLine,
                        isHumanConfirmed = false,
                    ),
                )
            },
        )

        if (draft.lines.isEmpty()) {
            EmofahEmptyState(
                icon = Icons.Outlined.ReceiptLong,
                title = "No Line Items Yet",
                message = "Photograph a physical receipt above or tap '+ Add Item' to enter items manually.",
            )
        } else {
            draft.lines.forEachIndexed { idx, line ->
                ReceiptLineEditorCard(
                    index = idx,
                    line = line,
                    onUpdateLine = { updated ->
                        val nextLines = draft.lines.map {
                            if (it.localLineId == line.localLineId) updated else it
                        }
                        updateDraftWithReset(draft.copy(lines = nextLines, isHumanConfirmed = false))
                    },
                    onOpenCatalogPicker = { pickerLineId = line.localLineId },
                    onRemoveLine = {
                        val nextLines = draft.lines.filterNot { it.localLineId == line.localLineId }
                        updateDraftWithReset(draft.copy(lines = nextLines, isHumanConfirmed = false))
                    },
                )
            }
        }

        HorizontalDivider(color = colors.borderSubtle)

        EmofahCard(
            containerColor = if (validation.isValid) colors.successContainer else colors.surfaceCard,
            borderColor = if (validation.isValid) colors.success.copy(alpha = 0.3f) else colors.borderSubtle,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        updateDraftWithReset(draft.copy(isHumanConfirmed = !draft.isHumanConfirmed))
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = draft.isHumanConfirmed,
                    onCheckedChange = { checked ->
                        updateDraftWithReset(draft.copy(isHumanConfirmed = checked))
                    },
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "I have reviewed and verified all items, categories (Resale / Consumable / Equipment), quantities, costs, and totals against the physical receipt.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                )
            }

            if (validation.errors.isNotEmpty()) {
                EmofahFeedbackBanner(
                    title = "Complete Before Confirmation",
                    message = validation.errors.joinToString("\n") { "• $it" },
                    tone = EmofahBadgeTone.DANGER,
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = colors.success,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "All receipt checks passed (${draft.lines.size} item(s), Ksh ${draft.effectiveTotal.roundToInt()}).",
                        style = MaterialTheme.typography.labelMedium.tabular(),
                        fontWeight = FontWeight.SemiBold,
                        color = colors.success,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Log purchase outflow event",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = "Desktop assigns accounting rules after sync",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textMuted,
                    )
                }
                Switch(
                    checked = recordFinancialOutflow,
                    onCheckedChange = onToggleFinancialOutflow,
                )
            }

            val isAlreadySubmitted = submissionResult is PurchaseSubmissionResult.SuccessOnline ||
                submissionResult is PurchaseSubmissionResult.SavedOffline ||
                submissionResult is PurchaseSubmissionResult.DuplicateIgnored

            EmofahLoadingButton(
                text = "Submit Confirmed Purchase",
                loadingText = "Submitting Purchase...",
                isLoading = isSubmitting,
                enabled = validation.isValid && !isAlreadySubmitted,
                icon = Icons.Outlined.CheckCircle,
                onClick = onSubmitPurchase,
            )

            submissionResult?.let { outcome ->
                val (tone, title, detail) = when (outcome) {
                    is PurchaseSubmissionResult.SuccessOnline -> Triple(
                        EmofahBadgeTone.SUCCESS,
                        if (outcome.alreadyCommitted) "Purchase Already Saved" else "Purchase Saved Online",
                        "Ksh ${outcome.totalAmount.roundToInt()} · ${outcome.resaleUnitsUpdated} resale unit(s) added to shared stock" +
                            if (outcome.newlyRegisteredCount > 0) " · ${outcome.newlyRegisteredCount} new item(s) added." else ".",
                    )
                    is PurchaseSubmissionResult.SavedOffline -> Triple(
                        EmofahBadgeTone.WARNING,
                        "Saved Offline · Queued for Sync",
                        "Ksh ${outcome.totalAmount.roundToInt()} · ${outcome.resaleUnitsQueued} unit(s) queued. ${outcome.reason}",
                    )
                    is PurchaseSubmissionResult.DuplicateIgnored -> Triple(
                        EmofahBadgeTone.INFO,
                        "Duplicate Submission Ignored",
                        "Purchase ${outcome.clientPurchaseId.take(8)} is already recorded (${outcome.syncStatus}).",
                    )
                    is PurchaseSubmissionResult.Rejected -> Triple(
                        EmofahBadgeTone.DANGER,
                        "Purchase Not Submitted",
                        outcome.message,
                    )
                }
                EmofahFeedbackBanner(
                    title = title,
                    message = detail,
                    tone = tone,
                    actionLabel = if (isAlreadySubmitted) "Scan Another" else null,
                    onAction = if (isAlreadySubmitted) onScanNextReceipt else null,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }

    val activePickerLine = draft.lines.firstOrNull { it.localLineId == pickerLineId }
    if (activePickerLine != null) {
        CatalogProductPickerDialog(
            lineNameHint = activePickerLine.rawName,
            products = activeCatalog,
            onSelectProduct = { product ->
                val nextLines = draft.lines.map {
                    if (it.localLineId == activePickerLine.localLineId) {
                        it.copy(
                            matchedProductId = product.id,
                            matchedProductName = product.name,
                            registerAsNewProduct = false,
                            newProductCategory = product.category,
                        )
                    } else {
                        it
                    }
                }
                updateDraftWithReset(draft.copy(lines = nextLines, isHumanConfirmed = false))
                pickerLineId = null
            },
            onDismiss = { pickerLineId = null },
        )
    }
}

@Composable
private fun ReceiptLineEditorCard(
    index: Int,
    line: ExtractedReceiptLineDraft,
    onUpdateLine: (ExtractedReceiptLineDraft) -> Unit,
    onOpenCatalogPicker: () -> Unit,
    onRemoveLine: () -> Unit,
) {
    val colors = MaterialTheme.emofahColors
    var qtyText by remember(line.localLineId, line.quantity) {
        mutableStateOf(line.quantity.toString())
    }
    var costText by remember(line.localLineId, line.unitCost) {
        mutableStateOf(
            if (line.unitCost > 0.0) {
                if (line.unitCost % 1.0 == 0.0) line.unitCost.toInt().toString()
                else line.unitCost.toString()
            } else {
                ""
            },
        )
    }
    var lineTotalText by remember(line.localLineId, line.lineTotal) {
        mutableStateOf(
            if (line.lineTotal > 0.0) {
                if (line.lineTotal % 1.0 == 0.0) line.lineTotal.toInt().toString()
                else line.lineTotal.toString()
            } else {
                ""
            },
        )
    }
    var sellPriceText by remember(line.localLineId, line.newProductSellPrice) {
        mutableStateOf(
            line.newProductSellPrice?.let {
                if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()
            }.orEmpty(),
        )
    }
    val lineArithmeticMismatch = line.unitCost > 0.0 && line.lineTotal > 0.0 &&
        abs((line.quantity.coerceAtLeast(1) * line.unitCost) - line.lineTotal) > 1.5

    EmofahCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Item #${index + 1} · Line Total Ksh ${line.effectiveLineTotal.roundToInt()}",
                style = MaterialTheme.typography.labelMedium.tabular(),
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            )
            IconButton(onClick = onRemoveLine, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = "Remove line",
                    tint = colors.danger,
                )
            }
        }

        line.confidenceNote?.let { note ->
            EmofahStatusBadge(text = note, tone = EmofahBadgeTone.INFO)
        }

        OutlinedTextField(
            value = line.rawName,
            onValueChange = { onUpdateLine(line.copy(rawName = it)) },
            label = { Text("Item Description") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PurchaseItemCategory.entries.forEach { cat ->
                FilterChip(
                    selected = line.itemCategory == cat,
                    onClick = {
                        onUpdateLine(
                            if (cat == PurchaseItemCategory.RESALE_PRODUCT) {
                                line.copy(itemCategory = cat)
                            } else {
                                line.copy(
                                    itemCategory = cat,
                                    matchedProductId = null,
                                    matchedProductName = null,
                                    registerAsNewProduct = false,
                                )
                            },
                        )
                    },
                    label = { Text(cat.label) },
                )
            }
        }

        if (line.itemCategory == PurchaseItemCategory.RESALE_PRODUCT) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.surfaceMuted,
                border = BorderStroke(1.dp, colors.borderSubtle),
                modifier = Modifier.fillMaxWidth(),
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
                            text = "Register as New Resale Product",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                        )
                        Switch(
                            checked = line.registerAsNewProduct,
                            onCheckedChange = { isNew ->
                                onUpdateLine(
                                    line.copy(
                                        registerAsNewProduct = isNew,
                                        matchedProductId = if (isNew) null else line.matchedProductId,
                                        matchedProductName = if (isNew) null else line.matchedProductName,
                                    ),
                                )
                            },
                        )
                    }

                    if (!line.registerAsNewProduct) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = line.matchedProductName ?: "No catalogue product matched yet",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (line.matchedProductId != null) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (line.matchedProductId != null) colors.textPrimary else colors.danger,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                line.matchedProductId?.let { id ->
                                    Text(
                                        text = "Catalogue ID #$id",
                                        style = MaterialTheme.typography.labelSmall.tabular(),
                                        color = colors.textMuted,
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = onOpenCatalogPicker,
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (line.matchedProductId == null) "Pick Item" else "Change")
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = sellPriceText,
                            onValueChange = { raw ->
                                sellPriceText = raw
                                val sell = raw.replace(",", "").toDoubleOrNull()
                                onUpdateLine(line.copy(newProductSellPrice = sell))
                            },
                            label = { Text("Selling Price (Ksh) *") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            NEW_PRODUCT_CATEGORIES.forEach { catName ->
                                FilterChip(
                                    selected = line.newProductCategory.equals(catName, ignoreCase = true),
                                    onClick = { onUpdateLine(line.copy(newProductCategory = catName)) },
                                    label = { Text(catName) },
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1.1f),
            ) {
                FilledTonalIconButton(
                    onClick = {
                        val nextQty = (line.quantity - 1).coerceAtLeast(1)
                        qtyText = nextQty.toString()
                        onUpdateLine(
                            line.copy(
                                quantity = nextQty,
                                lineTotal = nextQty * line.unitCost,
                            ),
                        )
                    },
                    modifier = Modifier.size(38.dp),
                ) {
                    Icon(Icons.Outlined.Remove, contentDescription = "Decrease qty", modifier = Modifier.size(18.dp))
                }
                OutlinedTextField(
                    value = qtyText,
                    onValueChange = { raw ->
                        qtyText = raw
                        val parsed = raw.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        onUpdateLine(
                            line.copy(
                                quantity = parsed,
                                lineTotal = parsed * line.unitCost,
                            ),
                        )
                    },
                    label = { Text("Qty") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                )
                FilledTonalIconButton(
                    onClick = {
                        val nextQty = line.quantity + 1
                        qtyText = nextQty.toString()
                        onUpdateLine(
                            line.copy(
                                quantity = nextQty,
                                lineTotal = nextQty * line.unitCost,
                            ),
                        )
                    },
                    modifier = Modifier.size(38.dp),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "Increase qty", modifier = Modifier.size(18.dp))
                }
            }

            OutlinedTextField(
                value = costText,
                onValueChange = { raw ->
                    costText = raw
                    val cost = raw.replace(",", "").toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                    onUpdateLine(
                        line.copy(
                            unitCost = cost,
                            lineTotal = line.quantity * cost,
                        ),
                    )
                },
                label = { Text("Unit Cost (Ksh)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(0.9f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = lineTotalText,
                onValueChange = { raw ->
                    lineTotalText = raw
                    val parsed = raw.replace(",", "").toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                    onUpdateLine(line.copy(lineTotal = parsed))
                },
                label = { Text("Printed Line Total (Ksh)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
            )
            if (lineArithmeticMismatch) {
                EmofahStatusBadge(
                    text = "${line.quantity}×${line.unitCost.roundToInt()} ≠ ${line.lineTotal.roundToInt()}",
                    tone = EmofahBadgeTone.WARNING,
                )
            }
        }
    }
}

@Composable
private fun CatalogProductPickerDialog(
    lineNameHint: String,
    products: List<ProductIdentity>,
    onSelectProduct: (ProductIdentity) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.emofahColors
    var query by remember { mutableStateOf(lineNameHint) }
    val filtered = remember(query, products) {
        val q = query.trim().lowercase()
        if (q.isBlank()) {
            products.take(40)
        } else {
            products.filter {
                it.name.lowercase().contains(q) ||
                    it.category.lowercase().contains(q) ||
                    (it.brandHint?.lowercase()?.contains(q) == true)
            }.take(40)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Match Catalogue Product", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EmofahSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search product name...",
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.id }) { prod ->
                        EmofahInteractiveCard(
                            onClick = { onSelectProduct(prod) },
                            contentPadding = PaddingValues(11.dp),
                        ) {
                            Text(
                                text = prod.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${prod.category} · Sell Ksh ${prod.sellPrice.roundToInt()} · Stock ${prod.stockQty}",
                                style = MaterialTheme.typography.labelSmall.tabular(),
                                color = colors.textMuted,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}
