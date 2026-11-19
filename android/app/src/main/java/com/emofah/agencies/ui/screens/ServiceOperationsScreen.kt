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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.emofah.agencies.ui.components.EmofahAnimatedVisibility
import com.emofah.agencies.ui.components.EmofahBadgeTone
import com.emofah.agencies.ui.components.EmofahCard
import com.emofah.agencies.ui.components.EmofahEmptyState
import com.emofah.agencies.ui.components.EmofahFeedbackBanner
import com.emofah.agencies.ui.components.EmofahInteractiveCard
import com.emofah.agencies.ui.components.EmofahLoadingButton
import com.emofah.agencies.ui.components.EmofahSearchField
import com.emofah.agencies.ui.components.EmofahStatusBadge
import com.emofah.agencies.ui.theme.emofahColors
import com.emofah.agencies.ui.theme.tabular
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Simplified Service Operations workflow (Printing, Photocopy, Scan, eCitizen, KRA, Typesetting, Design).
 * Preserves exact 7 primary options, M-Pesa linking, and optional consumables stock link.
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
    prefilledMpesaAmount: Double? = null,
    prefilledMpesaRef: String? = null,
    prefilledMpesaSender: String? = null,
    onClearPrefilledMpesa: () -> Unit = {},
) {
    val colors = MaterialTheme.emofahColors
    val activeCatalog = remember(cachedProducts) {
        cachedProducts.filter { !it.isArchived && it.id > 0L }
    }
    val primaryServiceTypes = remember {
        ServiceOperationType.entries.filter { it.isPrimaryShopOption }
    }

    fun formatAmountInput(value: Double): String {
        if (value <= 0.0) return ""
        return if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
    }

    var draft by remember { mutableStateOf(ServiceOperationDraft()) }
    var unitPriceText by remember(draft.clientServiceId) { mutableStateOf(formatAmountInput(draft.unitPrice)) }
    var discountText by remember(draft.clientServiceId) { mutableStateOf(formatAmountInput(draft.discountAmount)) }
    var customTotalText by remember(draft.clientServiceId) {
        mutableStateOf(draft.customTotalRevenue?.let { formatAmountInput(it) }.orEmpty())
    }
    var showAdvancedOptions by remember { mutableStateOf(false) }
    var pickingConsumableId by remember { mutableStateOf<String?>(null) }
    var lastCommittedDraft by remember { mutableStateOf<ServiceOperationDraft?>(null) }
    var lastCommittedReceipt by remember { mutableStateOf<DigitalReceiptData?>(null) }
    var previewedReceipt by remember { mutableStateOf<DigitalReceiptData?>(null) }

    LaunchedEffect(prefilledMpesaAmount, prefilledMpesaRef, prefilledMpesaSender) {
        if (prefilledMpesaAmount != null && prefilledMpesaAmount > 0.0) {
            val noteParts = listOfNotNull(
                prefilledMpesaRef?.takeIf { it.isNotBlank() }?.let { "M-Pesa $it" },
                prefilledMpesaSender?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            draft = draft.copy(
                paymentMethod = PaymentMethod.MPESA,
                customTotalRevenue = prefilledMpesaAmount,
                customerNote = noteParts.ifBlank { draft.customerNote },
            )
            customTotalText = formatAmountInput(prefilledMpesaAmount)
        }
    }

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
            val freshDraft = ServiceOperationDraft(
                serviceType = prevType,
                unitPrice = prevType.defaultUnitPrice,
                paymentMethod = prevMethod,
            )
            draft = freshDraft
            unitPriceText = formatAmountInput(freshDraft.unitPrice)
            discountText = ""
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
                        text = "Record Service",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = "Printing, photocopy, eCitizen & forms",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textMuted,
                    )
                }
            }
            TextButton(
                onClick = {
                    val fresh = ServiceOperationDraft(
                        serviceType = draft.serviceType,
                        unitPrice = draft.serviceType.defaultUnitPrice,
                    )
                    draft = fresh
                    unitPriceText = formatAmountInput(fresh.unitPrice)
                    discountText = ""
                    customTotalText = ""
                    onClearPrefilledMpesa()
                },
            ) {
                Text("Clear")
            }
        }

        if (prefilledMpesaAmount != null && prefilledMpesaAmount > 0.0) {
            EmofahFeedbackBanner(
                title = "Linking M-Pesa Payment: Ksh ${prefilledMpesaAmount.roundToInt()}",
                message = listOfNotNull(
                    prefilledMpesaSender?.takeIf { it.isNotBlank() }?.let { "From $it" },
                    prefilledMpesaRef?.takeIf { it.isNotBlank() }?.let { "Ref $it" },
                ).joinToString(" · ").ifBlank { "Pick service type and tap Record" },
                tone = EmofahBadgeTone.INFO,
                actionLabel = "Unlink",
                onAction = {
                    onClearPrefilledMpesa()
                    draft = draft.copy(customTotalRevenue = null)
                    customTotalText = ""
                },
            )
        }

        if (lastResult != null) {
            ServiceResultBanner(
                result = lastResult,
                receipt = lastCommittedReceipt,
                onViewReceipt = { receiptData -> previewedReceipt = receiptData },
            )
        }

        // 1. Service Type Selector (7 simplified options in clean 2-column grid)
        EmofahCard(contentPadding = PaddingValues(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Print,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "1. What Service Did You Do?",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            primaryServiceTypes.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pair.forEach { type ->
                        val isSelected = draft.serviceType == type
                        val onSelectService = {
                            draft = draft.copy(
                                serviceType = type,
                                unitPrice = type.defaultUnitPrice,
                                discountAmount = 0.0,
                            )
                            unitPriceText = formatAmountInput(type.defaultUnitPrice)
                            discountText = ""
                        }
                        if (isSelected) {
                            Button(
                                onClick = onSelectService,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(
                                    text = "${type.label} (${type.defaultUnitPrice.roundToInt()})",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelLarge.tabular(),
                                )
                            }
                        } else {
                            OutlinedButton(
                                onClick = onSelectService,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(
                                    text = "${type.label} (${type.defaultUnitPrice.roundToInt()})",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelLarge.tabular(),
                                )
                            }
                        }
                    }
                    if (pair.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // 2. Quantity, Price, Discount & Payment Method
        EmofahCard(contentPadding = PaddingValues(14.dp)) {
            Text(
                text = "2. Count, Price & Payment",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )

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
                    shape = RoundedCornerShape(12.dp),
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
                    label = { Text("How many ${draft.serviceType.unitLabel}?") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )

                OutlinedButton(
                    onClick = {
                        draft = draft.copy(quantityOrDuration = (draft.quantityOrDuration + 1).coerceAtMost(99999))
                    },
                    enabled = !isSubmitting,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "Increase")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(1, 2, 5, 10).forEach { presetQty ->
                    FilterChip(
                        selected = draft.quantityOrDuration == presetQty,
                        onClick = { draft = draft.copy(quantityOrDuration = presetQty) },
                        label = { Text("$presetQty", style = MaterialTheme.typography.labelMedium.tabular()) },
                        modifier = Modifier.weight(1f),
                    )
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
                        draft = draft.copy(
                            unitPrice = parsed,
                            customTotalRevenue = null,
                        )
                        customTotalText = ""
                    },
                    label = { Text("Price Each (Ksh)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )

                OutlinedTextField(
                    value = discountText,
                    onValueChange = { raw ->
                        discountText = raw
                        val parsed = raw.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                        draft = draft.copy(
                            discountAmount = parsed,
                            customTotalRevenue = null,
                        )
                        customTotalText = ""
                    },
                    label = { Text("Discount (Ksh)") },
                    placeholder = { Text("0") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }

            Surface(
                color = colors.infoContainer,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, colors.info.copy(alpha = 0.22f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = "${draft.serviceType.label} · ${draft.quantityOrDuration} ${draft.serviceType.unitLabel}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textPrimary,
                        )
                        if (draft.discountAmount > 0.0 && draft.customTotalRevenue == null) {
                            Text(
                                text = "Discount: -Ksh ${draft.discountAmount.roundToInt()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textMuted,
                            )
                        } else if (draft.customTotalRevenue != null) {
                            Text(
                                text = "Using custom total",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.info,
                            )
                        }
                    }
                    Text(
                        text = "Ksh ${draft.effectiveRevenue.roundToInt()}",
                        style = MaterialTheme.typography.titleMedium.tabular(),
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                }
            }

            Text(
                text = "How Did Customer Pay?",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PaymentMethod.entries.forEach { method ->
                    val selected = draft.paymentMethod == method
                    if (selected) {
                        Button(
                            onClick = { draft = draft.copy(paymentMethod = method) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(method.label, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { draft = draft.copy(paymentMethod = method) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(method.label)
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { showAdvancedOptions = !showAdvancedOptions },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (showAdvancedOptions) {
                        "Hide Extra Options"
                    } else {
                        "Optional: Custom Total, Note & Paper/Ink"
                    }
                )
            }
        }

        EmofahAnimatedVisibility(visible = showAdvancedOptions) {
            EmofahCard(contentPadding = PaddingValues(14.dp)) {
                OutlinedTextField(
                    value = customTotalText,
                    onValueChange = { raw ->
                        customTotalText = raw
                        val parsed = raw.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.coerceAtLeast(0.0)
                        draft = draft.copy(customTotalRevenue = parsed)
                    },
                    label = { Text("Exact Total Amount (Optional)") },
                    placeholder = { Text("Leave blank to calculate automatically") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = draft.customerNote,
                    onValueChange = { draft = draft.copy(customerNote = it) },
                    label = { Text("Short Note (optional)") },
                    placeholder = { Text("e.g. eCitizen certificate, P3 form, M-Pesa ref") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                HorizontalDivider(color = colors.borderSubtle)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Paper / Ink Used (Optional)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Add only if you want to record paper/ink used or reduce shop stock.",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textMuted,
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
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add")
                    }
                }

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

        if (!validation.isValid) {
            EmofahFeedbackBanner(
                title = "Check Entry",
                message = validation.errors.first(),
                tone = EmofahBadgeTone.DANGER,
            )
        }

        // Primary Submit Action
        EmofahLoadingButton(
            text = "RECORD ${draft.serviceType.label.uppercase()} · KSH ${draft.effectiveRevenue.roundToInt()}",
            loadingText = "Recording Service...",
            isLoading = isSubmitting,
            enabled = validation.isValid && !isSubmitting,
            height = 56.dp,
            onClick = {
                lastCommittedDraft = draft
                lastCommittedReceipt = null
                onSubmitService(draft)
            },
        )

        // Recent Service Activity
        EmofahCard(contentPadding = PaddingValues(14.dp)) {
            Text(
                text = "Recent Service Operations (${recentServiceEvents.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (recentServiceEvents.isEmpty()) {
                EmofahEmptyState(
                    icon = Icons.Outlined.Print,
                    title = "No Services Yet",
                    message = "Recorded print, copy, and cyber services from this device will appear here.",
                )
            } else {
                recentServiceEvents.take(15).forEach { evt ->
                    val syncBadge = when (evt.syncStatus) {
                        SyncState.SERVER_CONFIRMED -> "Synced" to EmofahBadgeTone.SUCCESS
                        SyncState.CONFLICT -> "Conflict" to EmofahBadgeTone.DANGER
                        else -> "Pending Sync" to EmofahBadgeTone.WARNING
                    }
                    val formattedTime = remember(evt.eventTimestampEpochMs, timeFormat) {
                        timeFormat.format(Date(evt.eventTimestampEpochMs))
                    }
                    EmofahInteractiveCard(
                        onClick = {
                            previewedReceipt = DigitalReceiptFormatter.buildFromServiceEntity(evt)
                        },
                        containerColor = colors.surfaceElevated,
                        contentPadding = PaddingValues(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = evt.counterparty ?: "Service",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    EmofahStatusBadge(text = syncBadge.first, tone = syncBadge.second)
                                }
                                evt.rawEvidence?.takeIf { it.isNotBlank() }?.let { raw ->
                                    Text(
                                        text = raw,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textMuted,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    text = "$formattedTime · Tap for receipt",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textMuted,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "+Ksh ${evt.amount.roundToInt()}",
                                style = MaterialTheme.typography.titleSmall.tabular(),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
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
    val colors = MaterialTheme.emofahColors
    var qtyText by remember(line.localLineId) {
        mutableStateOf(
            if (line.usageQty % 1.0 == 0.0) line.usageQty.toInt().toString()
            else line.usageQty.toString()
        )
    }

    Surface(
        color = colors.surfaceElevated,
        border = BorderStroke(1.dp, colors.borderSubtle),
        shape = RoundedCornerShape(12.dp),
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
                shape = RoundedCornerShape(10.dp),
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
                    shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )

                OutlinedTextField(
                    value = line.usageUnit,
                    onValueChange = { onChange(line.copy(usageUnit = it)) },
                    label = { Text("Unit (sheets, ml)") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider(color = colors.borderSubtle)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (line.linkedProductId != null) {
                            "Linked item: ${line.linkedProductName ?: "#${line.linkedProductId}"}"
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
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("-")
                        }
                        Text(
                            text = line.stockDeductQty.toString(),
                            style = MaterialTheme.typography.titleSmall.tabular(),
                            fontWeight = FontWeight.Bold,
                        )
                        OutlinedButton(
                            onClick = {
                                onChange(line.copy(stockDeductQty = (line.stockDeductQty + 1).coerceAtMost(999)))
                            },
                            enabled = !isSubmitting,
                            shape = RoundedCornerShape(10.dp),
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
    val (title, detail, tone, isError) = when (result) {
        is ServiceOperationResult.SuccessOnline -> Quintuple(
            if (result.alreadyCommitted) "Already Saved" else "Service Saved",
            "${result.serviceLabel} · Ksh ${result.totalRevenue.roundToInt()} · Stock updated: ${result.stockDeductedCount} item(s).",
            EmofahBadgeTone.SUCCESS,
            false,
        )

        is ServiceOperationResult.SavedOffline -> Quintuple(
            "Saved on Phone (Offline)",
            "${result.serviceLabel} · Ksh ${result.totalRevenue.roundToInt()} · Will upload when internet returns.",
            EmofahBadgeTone.WARNING,
            false,
        )

        is ServiceOperationResult.DuplicateIgnored -> Quintuple(
            "Already Recorded",
            "This service was already saved (${result.syncStatus}).",
            EmofahBadgeTone.INFO,
            false,
        )

        is ServiceOperationResult.Rejected -> Quintuple(
            "Could Not Save Service",
            result.message,
            EmofahBadgeTone.DANGER,
            true,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EmofahFeedbackBanner(
            title = title,
            message = detail,
            tone = tone,
        )
        if (!isError && receipt != null) {
            OutlinedButton(
                onClick = { onViewReceipt(receipt) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.ReceiptLong, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("View / Share Digital Receipt")
            }
        }
    }
}

private data class Quintuple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)

@Composable
private fun ConsumableCataloguePickerDialog(
    products: List<ProductIdentity>,
    onDismiss: () -> Unit,
    onUnlink: () -> Unit,
    onSelectProduct: (ProductIdentity) -> Unit,
) {
    val colors = MaterialTheme.emofahColors
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
        shape = RoundedCornerShape(22.dp),
        containerColor = colors.surfaceCard,
        title = { Text("Pick Shop Item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EmofahSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search paper reams, ink bottles...",
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(filtered, key = { it.id }) { prod ->
                        Surface(
                            color = colors.surfaceElevated,
                            border = BorderStroke(1.dp, colors.borderSubtle),
                            shape = RoundedCornerShape(10.dp),
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
                                    style = MaterialTheme.typography.labelSmall.tabular(),
                                    color = colors.textMuted,
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
