package com.emofah.agencies.ui.screens

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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.local.entities.PendingFinancialEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.receipts.DigitalReceiptData
import com.emofah.agencies.receipts.DigitalReceiptFormatter
import com.emofah.agencies.sales.PaymentMethod
import com.emofah.agencies.services.ServiceConsumableDraft
import com.emofah.agencies.services.ServiceOperationDraft
import com.emofah.agencies.services.ServiceOperationResult
import com.emofah.agencies.services.ServiceOperationType
import com.emofah.agencies.services.ServiceOperationValidator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Section 4 — Quick Service Operations (Printing, Photocopying, Scanning,
 * Internet Browsing, E-Government Assistance).
 * Records service type, quantity/duration, unit price, total revenue, payment method,
 * and optional consumables used (deducting real shared catalogue products when linked).
 */
@Composable
fun ServiceOperationsScreen(
    cachedProducts: List<ProductIdentity>,
    recentServiceEvents: List<PendingFinancialEventEntity>,
    isSubmitting: Boolean,
    lastResult: ServiceOperationResult?,
    onSubmitService: (ServiceOperationDraft) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeCatalog = remember(cachedProducts) {
        cachedProducts.filter { !it.isArchived && it.id > 0L }
    }

    var draft by remember { mutableStateOf(ServiceOperationDraft()) }
    var unitPriceText by remember(draft.clientServiceId) { mutableStateOf("") }
    var customTotalText by remember(draft.clientServiceId) { mutableStateOf("") }
    var pickingConsumableId by remember { mutableStateOf<String?>(null) }
    var lastCommittedDraft by remember { mutableStateOf<ServiceOperationDraft?>(null) }
    var lastCommittedReceipt by remember { mutableStateOf<DigitalReceiptData?>(null) }
    var previewedReceipt by remember { mutableStateOf<DigitalReceiptData?>(null) }

    // Reset form IDs cleanly after an online or offline commit so rapid next entries never replay IDs
    LaunchedEffect(lastResult) {
        if (lastResult is ServiceOperationResult.SuccessOnline ||
            lastResult is ServiceOperationResult.SavedOffline
        ) {
            val committedSnapshot = lastCommittedDraft ?: draft
            lastCommittedReceipt = DigitalReceiptFormatter.buildFromServiceOutcome(
                result = lastResult,
                draft = committedSnapshot,
                actorId = "",
            )
            val prevType = draft.serviceType
            val prevMethod = draft.paymentMethod
            draft = ServiceOperationDraft(
                serviceType = prevType,
                paymentMethod = prevMethod,
            )
            unitPriceText = ""
            customTotalText = ""
            lastCommittedDraft = null
        }
    }

    val validation = remember(draft) { ServiceOperationValidator.validate(draft) }
    val timeFormat = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }

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
                        text = "Service Operations",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Printing, cyber & e-gov assistance",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(
                onClick = {
                    draft = ServiceOperationDraft(serviceType = draft.serviceType)
                    unitPriceText = ""
                    customTotalText = ""
                },
            ) {
                Text("Reset")
            }
        }

        // Result feedback banner
        if (lastResult != null) {
            ServiceResultBanner(
                result = lastResult,
                receipt = lastCommittedReceipt,
                onViewReceipt = { receiptData -> previewedReceipt = receiptData },
            )
        }

        // 1. Service Type Selector
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Print,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "1. Select Service Type",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ServiceOperationType.entries.forEach { type ->
                        FilterChip(
                            selected = draft.serviceType == type,
                            onClick = {
                                draft = draft.copy(serviceType = type)
                            },
                            label = { Text(type.label) },
                        )
                    }
                }
            }
        }

        // 2. Quantity / Duration, Price & Payment Method
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "2. Quantity / Duration & Revenue",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )

                // Quantity/Duration Stepper
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            if (draft.quantityOrDuration > 1) {
                                draft = draft.copy(quantityOrDuration = draft.quantityOrDuration - 1)
                            }
                        },
                        enabled = draft.quantityOrDuration > 1 && !isSubmitting,
                    ) {
                        Icon(Icons.Outlined.Remove, contentDescription = "Decrease")
                    }

                    OutlinedTextField(
                        value = draft.quantityOrDuration.toString(),
                        onValueChange = { raw ->
                            val parsed = raw.filter { it.isDigit() }.toIntOrNull()
                            if (parsed != null && parsed >= 1) {
                                draft = draft.copy(quantityOrDuration = parsed.coerceAtMost(99999))
                            } else if (raw.isBlank()) {
                                draft = draft.copy(quantityOrDuration = 1)
                            }
                        },
                        label = { Text("Qty / Duration (${draft.serviceType.unitLabel})") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )

                    OutlinedButton(
                        onClick = {
                            draft = draft.copy(quantityOrDuration = (draft.quantityOrDuration + 1).coerceAtMost(99999))
                        },
                        enabled = !isSubmitting,
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = "Increase")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = unitPriceText,
                        onValueChange = { raw ->
                            unitPriceText = raw
                            val parsed = raw.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                            draft = draft.copy(unitPrice = parsed)
                        },
                        label = { Text("Unit Price (Ksh / ${draft.serviceType.unitLabel.trimEnd('s')})") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )

                    OutlinedTextField(
                        value = customTotalText,
                        onValueChange = { raw ->
                            customTotalText = raw
                            val parsed = raw.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.coerceAtLeast(0.0)
                            draft = draft.copy(customTotalRevenue = parsed)
                        },
                        label = { Text("Total Override (Opt)") },
                        placeholder = { Text("Auto") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }

                // Revenue Highlight Box
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${draft.serviceType.label} · ${draft.quantityOrDuration} ${draft.serviceType.unitLabel}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = "Ksh ${draft.effectiveRevenue.roundToInt()}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }

                // Payment Method
                Text(
                    text = "Payment Method",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PaymentMethod.entries.forEach { method ->
                        FilterChip(
                            selected = draft.paymentMethod == method,
                            onClick = { draft = draft.copy(paymentMethod = method) },
                            label = { Text(method.label) },
                        )
                    }
                }

                OutlinedTextField(
                    value = draft.customerNote,
                    onValueChange = { draft = draft.copy(customerNote = it) },
                    label = { Text("Service Note (optional)") },
                    placeholder = { Text("e.g. Color A4, KRA PIN / eCitizen, PC #2") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // 3. Consumables Used (Optional, links to shared products if stock deduction needed)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "3. Consumables Used (Optional)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Record paper/ink used; link catalogue SKU only to deduct shared stock.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            draft = draft.copy(
                                consumables = draft.consumables + ServiceConsumableDraft(
                                    consumableName = draft.serviceType.suggestedConsumables.firstOrNull() ?: "A4 Paper",
                                    usageQty = draft.quantityOrDuration.toDouble(),
                                    usageUnit = "sheets",
                                )
                            )
                        },
                        enabled = !isSubmitting,
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add")
                    }
                }

                // Quick-add suggested consumables
                if (draft.serviceType.suggestedConsumables.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        draft.serviceType.suggestedConsumables.forEach { suggested ->
                            val unit = if (suggested.contains("Ink", ignoreCase = true) ||
                                suggested.contains("Toner", ignoreCase = true)
                            ) {
                                "ml"
                            } else {
                                "sheets"
                            }
                            FilterChip(
                                selected = false,
                                onClick = {
                                    draft = draft.copy(
                                        consumables = draft.consumables + ServiceConsumableDraft(
                                            consumableName = suggested,
                                            usageQty = if (unit == "sheets") draft.quantityOrDuration.toDouble() else 1.0,
                                            usageUnit = unit,
                                        )
                                    )
                                },
                                label = { Text("+ $suggested") },
                            )
                        }
                    }
                }

                if (draft.consumables.isEmpty()) {
                    Text(
                        text = "No consumables attached. Tap '+ Add' or a suggestion above if paper/ink was consumed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    draft.consumables.forEachIndexed { idx, line ->
                        ConsumableLineCard(
                            index = idx,
                            line = line,
                            isSubmitting = isSubmitting,
                            onChange = { updatedLine ->
                                draft = draft.copy(
                                    consumables = draft.consumables.map {
                                        if (it.localLineId == updatedLine.localLineId) updatedLine else it
                                    }
                                )
                            },
                            onPickCatalogueProduct = {
                                pickingConsumableId = line.localLineId
                            },
                            onRemove = {
                                draft = draft.copy(
                                    consumables = draft.consumables.filterNot { it.localLineId == line.localLineId }
                                )
                            },
                        )
                    }
                }
            }
        }

        // Validation hint
        if (!validation.isValid) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = validation.errors.first(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // Primary Submit Action
        Button(
            onClick = {
                lastCommittedDraft = draft
                lastCommittedReceipt = null
                onSubmitService(draft)
            },
            enabled = validation.isValid && !isSubmitting,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
                Text("Recording Service...")
            } else {
                Text(
                    text = "RECORD ${draft.serviceType.label.uppercase()} · KSH ${draft.effectiveRevenue.roundToInt()}",
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Recent Service Activity
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Recent Service Operations (${recentServiceEvents.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (recentServiceEvents.isEmpty()) {
                    Text(
                        text = "No services recorded from this device yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    recentServiceEvents.take(15).forEach { evt ->
                        val syncBadge = when (evt.syncStatus) {
                            SyncState.SERVER_CONFIRMED -> "Synced"
                            SyncState.CONFLICT -> "Conflict"
                            else -> "Pending Sync"
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    previewedReceipt = DigitalReceiptFormatter.buildFromServiceEntity(evt)
                                },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = evt.counterparty ?: "Service",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = evt.rawEvidence ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${timeFormat.format(Date(evt.eventTimestampEpochMs))} · $syncBadge · Tap for receipt",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "+Ksh ${evt.amount.roundToInt()}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    previewedReceipt?.let { receiptData ->
        DigitalReceiptPreviewDialog(
            receipt = receiptData,
            onDismiss = { previewedReceipt = null },
        )
    }

    // Catalogue SKU Picker Dialog for consumable stock deduction
    val activeConsumableId = pickingConsumableId
    if (activeConsumableId != null) {
        ConsumableCataloguePickerDialog(
            products = activeCatalog,
            onDismiss = { pickingConsumableId = null },
            onUnlink = {
                draft = draft.copy(
                    consumables = draft.consumables.map {
                        if (it.localLineId == activeConsumableId) {
                            it.copy(
                                linkedProductId = null,
                                linkedProductName = null,
                                stockDeductQty = 0,
                            )
                        } else {
                            it
                        }
                    }
                )
                pickingConsumableId = null
            },
            onSelectProduct = { product ->
                draft = draft.copy(
                    consumables = draft.consumables.map {
                        if (it.localLineId == activeConsumableId) {
                            it.copy(
                                linkedProductId = product.id,
                                linkedProductName = product.name,
                                stockDeductQty = it.stockDeductQty.coerceAtLeast(1),
                            )
                        } else {
                            it
                        }
                    }
                )
                pickingConsumableId = null
            },
        )
    }
}

@Composable
private fun ConsumableLineCard(
    index: Int,
    line: ServiceConsumableDraft,
    isSubmitting: Boolean,
    onChange: (ServiceConsumableDraft) -> Unit,
    onPickCatalogueProduct: () -> Unit,
    onRemove: () -> Unit,
) {
    var qtyText by remember(line.localLineId) {
        mutableStateOf(
            if (line.usageQty % 1.0 == 0.0) line.usageQty.toInt().toString()
            else line.usageQty.toString()
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
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
                    text = "Consumable #${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(
                    onClick = onRemove,
                    enabled = !isSubmitting,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "Remove consumable",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            OutlinedTextField(
                value = line.consumableName,
                onValueChange = { onChange(line.copy(consumableName = it)) },
                label = { Text("Consumable Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = qtyText,
                    onValueChange = { raw ->
                        qtyText = raw
                        val parsed = raw.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                        onChange(line.copy(usageQty = parsed))
                    },
                    label = { Text("Usage Qty") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )

                OutlinedTextField(
                    value = line.usageUnit,
                    onValueChange = { onChange(line.copy(usageUnit = it)) },
                    label = { Text("Unit (sheets, ml)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (line.linkedProductId != null) {
                            "Linked SKU: ${line.linkedProductName ?: "#${line.linkedProductId}"}"
                        } else {
                            "Shared Stock Link: None (operational note only)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (line.linkedProductId != null) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    onClick = onPickCatalogueProduct,
                    enabled = !isSubmitting,
                ) {
                    Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (line.linkedProductId != null) "Change" else "Link Stock")
                }
            }

            if (line.linkedProductId != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Deduct from shared stock (whole units):",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (line.stockDeductQty > 0) {
                                    onChange(line.copy(stockDeductQty = line.stockDeductQty - 1))
                                }
                            },
                            enabled = line.stockDeductQty > 0 && !isSubmitting,
                        ) {
                            Text("-")
                        }
                        Text(
                            text = line.stockDeductQty.toString(),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        OutlinedButton(
                            onClick = {
                                onChange(line.copy(stockDeductQty = (line.stockDeductQty + 1).coerceAtMost(999)))
                            },
                            enabled = !isSubmitting,
                        ) {
                            Text("+")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServiceResultBanner(
    result: ServiceOperationResult,
    receipt: DigitalReceiptData? = null,
    onViewReceipt: (DigitalReceiptData) -> Unit = {},
) {
    val (bg, contentColor, title, detail, isError) = when (result) {
        is ServiceOperationResult.SuccessOnline -> Quintuple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            if (result.alreadyCommitted) "Service Already Confirmed (Idempotent)" else "Service Recorded Online",
            "${result.serviceLabel} · Ksh ${result.totalRevenue.roundToInt()} · Stock deducted: ${result.stockDeductedCount} unit(s).",
            false,
        )

        is ServiceOperationResult.SavedOffline -> Quintuple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            "Service Queued Offline",
            "${result.serviceLabel} · Ksh ${result.totalRevenue.roundToInt()} · ${result.reason}",
            false,
        )

        is ServiceOperationResult.DuplicateIgnored -> Quintuple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            "Duplicate Prevented",
            "Service ${result.clientServiceId.take(8)} already exists (${result.syncStatus}).",
            false,
        )

        is ServiceOperationResult.Rejected -> Quintuple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            "Could Not Record Service",
            result.message,
            true,
        )
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isError) Icons.Outlined.WarningAmber else Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = contentColor,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = contentColor,
                    )
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor,
                    )
                }
            }
            if (!isError && receipt != null) {
                OutlinedButton(
                    onClick = { onViewReceipt(receipt) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.ReceiptLong, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("View / Share Digital Receipt")
                }
            }
        }
    }
}

private data class Quintuple<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
)

@Composable
private fun ConsumableCataloguePickerDialog(
    products: List<ProductIdentity>,
    onDismiss: () -> Unit,
    onUnlink: () -> Unit,
    onSelectProduct: (ProductIdentity) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(products, query) {
        val q = query.trim().lowercase()
        if (q.isBlank()) products.take(40)
        else products.filter {
            it.name.lowercase().contains(q) ||
                it.category.lowercase().contains(q)
        }.take(40)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link Shared Catalogue SKU") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text("Search paper reams, ink bottles...") },
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
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
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
                                    text = "${prod.category} · Stock: ${prod.stockQty} · Sell Ksh ${prod.sellPrice.roundToInt()}",
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
            TextButton(onClick = onUnlink) {
                Text("Clear Link")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
