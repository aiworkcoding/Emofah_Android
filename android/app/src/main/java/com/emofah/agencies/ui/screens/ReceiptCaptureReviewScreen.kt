package com.emofah.agencies.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.asImageBitmap
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
import com.emofah.agencies.purchases.ReceiptDraftValidator
import kotlin.math.abs
import kotlin.math.roundToInt

private val NEW_PRODUCT_CATEGORIES = listOf("Stationery", "Books", "Electronics", "General")

/**
 * Section 2 (Part 1): Physical Purchase Receipt Scanning, OCR/AI Extraction & Human Review.
 * Enforces explicit human review (`isHumanConfirmed`), categorises lines into
 * Resale Product / Consumable / Equipment, supports catalogue matching or inline new resale
 * product registration fields, and validates completeness prior to Part 2 backend commit.
 */
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
    val activeCatalog = remember(cachedProducts) {
        cachedProducts.filter { !it.isArchived && it.id > 0L }
    }
    var pickerLineId by remember { mutableStateOf<String?>(null) }
    var printedTotalText by remember(draft.clientPurchaseId, draft.extractedTotal) {
        mutableStateOf(
            if (draft.extractedTotal > 0.0) {
                if (draft.extractedTotal % 1.0 == 0.0) draft.extractedTotal.toInt().toString()
                else draft.extractedTotal.toString()
            } else {
                ""
            }
        )
    }

    val previewBitmap = remember(draft.photoPath) {
        draft.photoPath?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
    }
    val validation = remember(draft) { ReceiptDraftValidator.validate(draft) }

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
        // Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                    )
                    Text(
                        text = "Review OCR/AI extraction before saving",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = draft.extractionSource.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        // Photo Capture & Preview Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (previewBitmap != null) {
                    Image(
                        bitmap = previewBitmap.asImageBitmap(),
                        contentDescription = "Captured receipt photograph",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }

                if (isExtracting) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp))
                        Text(
                            text = "Reading receipt via ML Kit OCR & AI extractor...",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onSnapReceipt,
                        enabled = !isExtracting,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (previewBitmap == null) "Photograph Receipt" else "Retake Receipt")
                    }
                    OutlinedButton(
                        onClick = onResetBlank,
                        enabled = !isExtracting && !isSubmitting,
                    ) {
                        Text("Clear")
                    }
                }
            }
        }

        extractionError?.let { err ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.WarningAmber, contentDescription = null)
                    Text(text = err, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (draft.warnings.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Outlined.WarningAmber, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(
                            text = "Human Review Required",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    draft.warnings.forEach { warn ->
                        Text(
                            text = "• $warn",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        // Receipt Header Fields
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Outlined.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = "Supplier & Receipt Details",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }

                OutlinedTextField(
                    value = draft.supplierName,
                    onValueChange = { updateDraftWithReset(draft.copy(supplierName = it, isHumanConfirmed = false)) },
                    label = { Text("Supplier Name") },
                    singleLine = true,
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
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = draft.receiptDateText,
                        onValueChange = { updateDraftWithReset(draft.copy(receiptDateText = it, isHumanConfirmed = false)) },
                        label = { Text("Date") },
                        placeholder = { Text("DD/MM/YYYY") },
                        singleLine = true,
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
                    label = { Text("Printed Receipt Total (Ksh)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                val computed = draft.computedLinesTotal
                val diff = if (draft.extractedTotal > 0.0) abs(draft.extractedTotal - computed) else 0.0
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Computed line sum: Ksh ${computed.roundToInt()}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (diff > 1.0) {
                        Text(
                            text = "Differs by Ksh ${diff.roundToInt()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        // Line Items Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Purchased Items (${draft.lines.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            OutlinedButton(
                onClick = {
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
                        )
                    )
                },
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add Item")
            }
        }

        if (draft.lines.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Text(
                    text = "No item lines yet. Photograph a physical receipt above or tap 'Add Item' to enter receipt items manually.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp),
                )
            }
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

        HorizontalDivider()

        // Explicit Human Confirmation & Validation Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (validation.isValid) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
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
                    )
                }

                if (validation.errors.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Complete before confirmation:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                        validation.errors.forEach { err ->
                            Text(
                                text = "• $err",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "All receipt checks passed (${draft.lines.size} item(s), Ksh ${draft.effectiveTotal.roundToInt()}).",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
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
                        )
                        Text(
                            text = "Desktop assigns accounting rules after sync",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

                Button(
                    onClick = onSubmitPurchase,
                    enabled = validation.isValid && !isSubmitting && !isAlreadySubmitted,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Submitting Purchase...")
                    } else {
                        Text("Submit Confirmed Purchase")
                    }
                }

                submissionResult?.let { outcome ->
                    val (bgColor, title, detail) = when (outcome) {
                        is PurchaseSubmissionResult.SuccessOnline -> Triple(
                            MaterialTheme.colorScheme.surface,
                            if (outcome.alreadyCommitted) "Purchase Synced (Idempotent ACK)" else "Purchase Submitted Online",
                            "Ksh ${outcome.totalAmount.roundToInt()} · ${outcome.resaleUnitsUpdated} resale unit(s) added to shared stock" +
                                if (outcome.newlyRegisteredCount > 0) " · ${outcome.newlyRegisteredCount} new SKU(s) registered." else ".",
                        )
                        is PurchaseSubmissionResult.SavedOffline -> Triple(
                            MaterialTheme.colorScheme.surface,
                            "Saved Offline · Queued for Sync",
                            "Ksh ${outcome.totalAmount.roundToInt()} · ${outcome.resaleUnitsQueued} unit(s) queued. ${outcome.reason}",
                        )
                        is PurchaseSubmissionResult.DuplicateIgnored -> Triple(
                            MaterialTheme.colorScheme.surface,
                            "Duplicate Submission Ignored",
                            "Purchase ${outcome.clientPurchaseId.take(8)} is already recorded (${outcome.syncStatus}).",
                        )
                        is PurchaseSubmissionResult.Rejected -> Triple(
                            MaterialTheme.colorScheme.errorContainer,
                            "Purchase Not Submitted",
                            outcome.message,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = bgColor,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (isAlreadySubmitted) {
                                OutlinedButton(
                                    onClick = onScanNextReceipt,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Scan Another Receipt")
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    // Catalogue Product Matcher Dialog
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
            }
        )
    }
    var sellPriceText by remember(line.localLineId, line.newProductSellPrice) {
        mutableStateOf(
            line.newProductSellPrice?.let {
                if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()
            }.orEmpty()
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Item #${index + 1} · Line Total Ksh ${line.effectiveLineTotal.roundToInt()}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onRemoveLine, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "Remove line",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            line.confidenceNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            OutlinedTextField(
                value = line.rawName,
                onValueChange = { onUpdateLine(line.copy(rawName = it)) },
                label = { Text("Item Description") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Item Classification Chips (Resale vs Consumable vs Equipment)
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
                                }
                            )
                        },
                        label = { Text(cat.label) },
                    )
                }
            }

            // Resale Product Linking / New Registration Section
            if (line.itemCategory == PurchaseItemCategory.RESALE_PRODUCT) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
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
                            )
                            Switch(
                                checked = line.registerAsNewProduct,
                                onCheckedChange = { isNew ->
                                    onUpdateLine(
                                        line.copy(
                                            registerAsNewProduct = isNew,
                                            matchedProductId = if (isNew) null else line.matchedProductId,
                                            matchedProductName = if (isNew) null else line.matchedProductName,
                                        )
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
                                        color = if (line.matchedProductId != null) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    line.matchedProductId?.let { id ->
                                        Text(
                                            text = "Catalogue ID #$id",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(onClick = onOpenCatalogPicker) {
                                    Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (line.matchedProductId == null) "Match SKU" else "Change")
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

            // Quantity & Unit Cost Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    IconButton(
                        onClick = {
                            val nextQty = (line.quantity - 1).coerceAtLeast(1)
                            qtyText = nextQty.toString()
                            onUpdateLine(
                                line.copy(
                                    quantity = nextQty,
                                    lineTotal = nextQty * line.unitCost,
                                )
                            )
                        },
                    ) {
                        Icon(Icons.Outlined.Remove, contentDescription = "Decrease qty")
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
                                )
                            )
                        },
                        label = { Text("Qty") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            val nextQty = line.quantity + 1
                            qtyText = nextQty.toString()
                            onUpdateLine(
                                line.copy(
                                    quantity = nextQty,
                                    lineTotal = nextQty * line.unitCost,
                                )
                            )
                        },
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = "Increase qty")
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
                            )
                        )
                    },
                    label = { Text("Unit Cost (Ksh)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
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
        title = { Text("Match Catalogue Product") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search product name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(filtered, key = { it.id }) { prod ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectProduct(prod) },
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = prod.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${prod.category} · Sell Ksh ${prod.sellPrice.roundToInt()} · Stock ${prod.stockQty}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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
