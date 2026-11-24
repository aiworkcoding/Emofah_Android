package com.emofah.agencies.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emofah.agencies.data.local.entities.PendingFinancialEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.financial.BusinessBalanceCalculator
import com.emofah.agencies.financial.ClassificationState
import com.emofah.agencies.financial.FinancialEventDraft
import com.emofah.agencies.financial.FinancialEventType
import com.emofah.agencies.financial.FinancialMonitoringState
import com.emofah.agencies.financial.MpesaInflowAttributionPolicy
import com.emofah.agencies.financial.MpesaInflowLinkRecord
import com.emofah.agencies.financial.MpesaInflowLinkTarget
import com.emofah.agencies.financial.RemoteRecentSale
import com.emofah.agencies.ui.components.EmofahBadgeTone
import com.emofah.agencies.ui.components.EmofahCard
import com.emofah.agencies.ui.components.EmofahEmptyState
import com.emofah.agencies.ui.components.EmofahFeedbackBanner
import com.emofah.agencies.ui.components.EmofahLoadingButton
import com.emofah.agencies.ui.components.EmofahSectionHeader
import com.emofah.agencies.ui.components.EmofahStatusBadge
import com.emofah.agencies.ui.theme.emofahColors
import com.emofah.agencies.ui.theme.tabular
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun MoneyScreen(
    monitoringState: FinancialMonitoringState,
    financialEvents: List<PendingFinancialEventEntity>,
    statusMessage: String?,
    isSyncing: Boolean = false,
    onGrantConsent: () -> Unit,
    onRevokeConsent: () -> Unit,
    onPermissionResult: () -> Unit,
    onCapturePastedMpesa: (sender: String, body: String) -> Unit,
    onRecordManualEvent: (FinancialEventDraft) -> Unit,
    onSyncNow: () -> Unit = {},
    onOpenReceiptScanner: () -> Unit = {},
    mpesaInflowLinks: Map<String, MpesaInflowLinkRecord> = emptyMap(),
    remoteRecentSales: List<RemoteRecentSale> = emptyList(),
    onUseMpesaForSale: (PendingFinancialEventEntity) -> Unit = {},
    onUseMpesaForService: (PendingFinancialEventEntity) -> Unit = {},
    onDismissMpesaInflow: (PendingFinancialEventEntity) -> Unit = {},
    onLinkExistingSaleToTransaction: (
        event: PendingFinancialEventEntity,
        saleId: Long,
        productId: Long,
        productName: String,
        quantity: Int,
    ) -> Unit = { _, _, _, _, _ -> },
    onRemoveFromBusiness: (event: PendingFinancialEventEntity, reason: String) -> Unit = { evt, _ ->
        onDismissMpesaInflow(evt)
    },
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.emofahColors
    val context = LocalContext.current
    var pasteSender by remember { mutableStateOf("MPESA") }
    var pasteBody by remember { mutableStateOf("") }
    var showManualExpense by remember { mutableStateOf(false) }
    var expenseAmountText by remember { mutableStateOf("") }
    var expenseReference by remember { mutableStateOf("") }
    var expenseNote by remember { mutableStateOf("") }
    var selectedManualType by remember { mutableStateOf(FinancialEventType.EXPENSE_OUTFLOW) }
    var linkingProductTarget by remember { mutableStateOf<PendingFinancialEventEntity?>(null) }
    var removingBusinessTarget by remember { mutableStateOf<PendingFinancialEventEntity?>(null) }
    var removalReason by remember { mutableStateOf("Personal / non-business transaction") }

    val balanceReport = remember(financialEvents) {
        BusinessBalanceCalculator.compute(financialEvents)
    }
    val pendingSharedTransactions = remember(financialEvents, mpesaInflowLinks) {
        MpesaInflowAttributionPolicy.findPendingSharedTransactions(financialEvents, mpesaInflowLinks)
    }
    val unlinkedRemoteSales = remember(remoteRecentSales) {
        remoteRecentSales.filter { it.alreadyLinkedEventId.isNullOrBlank() }
    }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        onPermissionResult()
    }

    val timeFormat = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }
    val pendingCount = remember(financialEvents) {
        financialEvents.count { it.syncStatus != "SERVER_CONFIRMED" }
    }
    val visibleEvents = remember(financialEvents) { financialEvents.take(50) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "business_balance_summary") {
            EmofahCard(
                containerColor = colors.surfaceCard,
                borderColor = colors.borderStrong,
                shadowElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Business Balance (Classified)",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textMuted,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Ksh ${balanceReport.businessBalance.roundToInt()}",
                            style = MaterialTheme.typography.headlineSmall.tabular(),
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                        )
                    }
                    EmofahStatusBadge(
                        text = if (pendingSharedTransactions.isEmpty()) {
                            "All classified"
                        } else {
                            "${pendingSharedTransactions.size} awaiting"
                        },
                        tone = if (pendingSharedTransactions.isEmpty()) {
                            EmofahBadgeTone.SUCCESS
                        } else {
                            EmofahBadgeTone.WARNING
                        },
                        leadingDot = true,
                    )
                }

                HorizontalDivider(color = colors.borderSubtle)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Products (${balanceReport.businessProductCount}): Ksh ${balanceReport.businessProductNet.roundToInt()}",
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = colors.textPrimary,
                    )
                    Text(
                        text = "Services (${balanceReport.businessServiceCount}): Ksh ${balanceReport.businessServiceNet.roundToInt()}",
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = colors.textPrimary,
                    )
                }
                Text(
                    text = "${balanceReport.pendingCount} awaiting · ${balanceReport.nonBusinessCount} personal/excluded (Ksh 0 in balance)",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
        }

        if (pendingSharedTransactions.isNotEmpty()) {
            items(
                items = pendingSharedTransactions.take(8),
                key = { "pending_cls_${it.eventId}" },
            ) { pendingEvt ->
                val isInflow = pendingEvt.direction.equals("INFLOW", ignoreCase = true)
                val directionLabel = when (pendingEvt.direction.uppercase()) {
                    "INFLOW" -> "Received"
                    "OUTFLOW" -> "Sent"
                    else -> "Transfer"
                }
                val isServerConfirmed = pendingEvt.syncStatus == SyncState.SERVER_CONFIRMED
                EmofahCard(
                    containerColor = colors.infoContainer,
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    shadowElevation = 2.dp,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EmofahStatusBadge(
                            text = if (isServerConfirmed) {
                                "Saved in Supabase · Awaiting Classification"
                            } else {
                                "Saved Locally · Uploading"
                            },
                            tone = if (isServerConfirmed) EmofahBadgeTone.INFO else EmofahBadgeTone.WARNING,
                            leadingDot = true,
                        )
                        EmofahStatusBadge(
                            text = directionLabel,
                            tone = if (isInflow) EmofahBadgeTone.SUCCESS else EmofahBadgeTone.DANGER,
                        )
                    }

                    Text(
                        text = "${if (isInflow) "+" else "-"} Ksh ${pendingEvt.amount.roundToInt()}",
                        style = MaterialTheme.typography.headlineSmall.tabular(),
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )

                    Text(
                        text = buildString {
                            append(if (isInflow) "From " else "To ")
                            append(pendingEvt.counterparty?.takeIf { it.isNotBlank() } ?: "Counterparty")
                            pendingEvt.transactionReference?.takeIf { it.isNotBlank() }?.let {
                                append(" · Ref $it")
                            }
                            append(" · ${timeFormat.format(Date(pendingEvt.eventTimestampEpochMs))}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textPrimary,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { linkingProductTarget = pendingEvt },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                        ) {
                            Text("Link Product", fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { onUseMpesaForService(pendingEvt) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                        ) {
                            Text("Link Service", fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = {
                                removalReason = "Personal / non-business transaction"
                                removingBusinessTarget = pendingEvt
                            },
                        ) {
                            Text("Remove from Business Transactions")
                        }
                    }
                }
            }
        }

        item(key = "receipt_scanner_cta") {
            OutlinedButton(
                onClick = onOpenReceiptScanner,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, colors.borderStrong),
            ) {
                Icon(Icons.Outlined.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Scan Physical Purchase Receipt",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        item(key = "mpesa_consent_card") {
            EmofahCard(
                containerColor = if (monitoringState.isLiveSmsActive) colors.successContainer else colors.surfaceCard,
                borderColor = if (monitoringState.isLiveSmsActive) {
                    colors.success.copy(alpha = 0.28f)
                } else {
                    colors.borderSubtle
                },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = if (monitoringState.isLiveSmsActive) {
                                Icons.Outlined.CheckCircle
                            } else {
                                Icons.Outlined.Sms
                            },
                            contentDescription = null,
                            tint = if (monitoringState.isLiveSmsActive) colors.success else colors.textMuted,
                        )
                        Column {
                            Text(
                                text = "M-Pesa SMS Monitoring",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary,
                            )
                            Text(
                                text = when {
                                    monitoringState.isLiveSmsActive -> "Active · Listening only to MPESA sender"
                                    monitoringState.isConsentGranted && !monitoringState.hasSmsPermissions ->
                                        "Consent granted · Waiting for SMS permission"
                                    else -> "Paused · Requires explicit consent"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textMuted,
                            )
                        }
                    }
                    Switch(
                        checked = monitoringState.isConsentGranted,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                onGrantConsent()
                                if (!monitoringState.hasSmsPermissions) {
                                    smsPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.RECEIVE_SMS,
                                            Manifest.permission.READ_SMS,
                                        ),
                                    )
                                }
                            } else {
                                onRevokeConsent()
                            }
                        },
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.surfaceCard.copy(alpha = 0.9f),
                    border = BorderStroke(1.dp, colors.borderSubtle),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "Privacy guarantee: Emofah only inspects incoming SMS from official M-Pesa sender IDs locally on this phone. Personal chats, OTPs, and promo messages are ignored.",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textMuted,
                        )
                    }
                }

                if (monitoringState.isConsentGranted && !monitoringState.hasSmsPermissions) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                smsPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.RECEIVE_SMS,
                                        Manifest.permission.READ_SMS,
                                    ),
                                )
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Grant SMS Access")
                        }
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                )
                                context.startActivity(intent)
                            },
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Settings")
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Detected: ${monitoringState.capturedCount}",
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = colors.textMuted,
                    )
                    Text(
                        text = "Ignored non-M-Pesa: ${monitoringState.ignoredNonFinancialCount}",
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = colors.textMuted,
                    )
                }
            }
        }

        statusMessage?.let { msg ->
            item(key = "money_status_msg") {
                EmofahFeedbackBanner(
                    title = "Financial Capture",
                    message = msg,
                    tone = EmofahBadgeTone.INFO,
                )
            }
        }

        item(key = "entry_mode_and_form") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = !showManualExpense,
                        onClick = { showManualExpense = false },
                        leadingIcon = {
                            Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text("Paste / Test M-Pesa", maxLines = 1) },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = showManualExpense,
                        onClick = { showManualExpense = true },
                        leadingIcon = {
                            Icon(Icons.Outlined.ReceiptLong, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text("Manual Expense / Cash", maxLines = 1) },
                        modifier = Modifier.weight(1f),
                    )
                }

                if (!showManualExpense) {
                    EmofahCard {
                        EmofahSectionHeader(
                            title = "Import or Test M-Pesa SMS",
                            subtitle = "Paste any official confirmation SMS to parse & link",
                        )
                        OutlinedTextField(
                            value = pasteSender,
                            onValueChange = { pasteSender = it },
                            label = { Text("Sender ID") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = pasteBody,
                            onValueChange = { pasteBody = it },
                            label = { Text("Paste M-Pesa SMS message") },
                            placeholder = {
                                Text("e.g. QG789XYZ12 Confirmed. You have received Ksh1,500.00 from JOHN DOE...")
                            },
                            minLines = 3,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                shape = RoundedCornerShape(10.dp),
                                onClick = {
                                    pasteSender = "MPESA"
                                    pasteBody =
                                        "QG789XYZ12 Confirmed. You have received Ksh1,500.00 from JANE WANJIKU 0712345678 on 15/11/26 at 10:14 AM. New M-PESA balance is Ksh14,200.00."
                                },
                            ) {
                                Text("Sample Inflow")
                            }
                            OutlinedButton(
                                shape = RoundedCornerShape(10.dp),
                                onClick = {
                                    pasteSender = "MPESA"
                                    pasteBody =
                                        "QH123ABC99 Confirmed. Ksh850.00 sent to KPLC PREPAID for account 1422334455 on 15/11/26 at 11:00 AM. New M-PESA balance is Ksh13,350.00. Transaction cost, Ksh23.00."
                                },
                            ) {
                                Text("Sample Paybill")
                            }
                        }
                        EmofahLoadingButton(
                            text = "Parse, Validate & Capture",
                            enabled = pasteBody.isNotBlank(),
                            onClick = { onCapturePastedMpesa(pasteSender, pasteBody) },
                        )
                    }
                } else {
                    EmofahCard {
                        EmofahSectionHeader(
                            title = "Record Manual Cash / Expense",
                            subtitle = "Final accounting categories are assigned on Desktop after sync",
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = selectedManualType == FinancialEventType.EXPENSE_OUTFLOW,
                                onClick = { selectedManualType = FinancialEventType.EXPENSE_OUTFLOW },
                                label = { Text("Expense") },
                            )
                            FilterChip(
                                selected = selectedManualType == FinancialEventType.SUPPLIER_PAYMENT,
                                onClick = { selectedManualType = FinancialEventType.SUPPLIER_PAYMENT },
                                label = { Text("Supplier Pay") },
                            )
                            FilterChip(
                                selected = selectedManualType == FinancialEventType.CASH_SALE,
                                onClick = { selectedManualType = FinancialEventType.CASH_SALE },
                                label = { Text("Cash Inflow") },
                            )
                        }
                        OutlinedTextField(
                            value = expenseAmountText,
                            onValueChange = { expenseAmountText = it },
                            label = { Text("Amount (Ksh)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = expenseReference,
                            onValueChange = { expenseReference = it },
                            label = { Text("Receipt / Ref Code (Optional)") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = expenseNote,
                            onValueChange = { expenseNote = it },
                            label = { Text("Payee / Operational Note") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        EmofahLoadingButton(
                            text = "Save & Queue Event",
                            enabled = (expenseAmountText.replace(",", "").trim().toDoubleOrNull() ?: 0.0) > 0.0,
                            onClick = {
                                val amount = expenseAmountText.replace(",", "").trim().toDoubleOrNull() ?: 0.0
                                onRecordManualEvent(
                                    FinancialEventDraft(
                                        eventType = selectedManualType,
                                        amount = amount,
                                        category = "PENDING_DESKTOP_RULES",
                                        referenceCode = expenseReference.trim().takeIf { it.isNotBlank() },
                                        notes = expenseNote,
                                    ),
                                )
                                if (amount.isFinite() && amount > 0.0) {
                                    expenseAmountText = ""
                                    expenseReference = ""
                                    expenseNote = ""
                                }
                            },
                        )
                    }
                }
            }
        }

        item(key = "events_divider_header") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HorizontalDivider(color = colors.borderSubtle)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Outlined.AccountBalanceWallet,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = colors.textPrimary,
                        )
                        Text(
                            text = "Captured Events (${financialEvents.size})",
                            style = MaterialTheme.typography.titleSmall.tabular(),
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                        )
                    }
                    if (pendingCount > 0) {
                        OutlinedButton(
                            onClick = onSyncNow,
                            enabled = !isSyncing,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (isSyncing) "Syncing..." else "Sync ($pendingCount)")
                        }
                    }
                }
            }
        }

        if (visibleEvents.isEmpty()) {
            item(key = "events_empty") {
                EmofahEmptyState(
                    icon = Icons.Outlined.AccountBalanceWallet,
                    title = "No Financial Events Yet",
                    message = "Enable M-Pesa SMS monitoring above or paste/record an entry to track cash and M-Pesa flows.",
                )
            }
        } else {
            items(
                items = visibleEvents,
                key = { it.eventId },
            ) { evt ->
                val isInflow = evt.direction == "INFLOW"
                val parsedObj = remember(evt.eventId, evt.parsedJson) {
                    evt.parsedJson?.let { runCatching { JSONObject(it) }.getOrNull() }
                }
                val channelLabel = parsedObj?.optString("channel_label")?.takeIf { it.isNotBlank() }
                val accountRef = parsedObj?.optString("counterparty_account")?.takeIf { it.isNotBlank() && it != "null" }
                val feeCost = parsedObj?.takeIf { !it.isNull("transaction_cost") }?.optDouble("transaction_cost")
                val balanceAfter = parsedObj?.takeIf { !it.isNull("new_balance") }?.optDouble("new_balance")
                val existingLink = MpesaInflowAttributionPolicy.findExistingLink(evt, mpesaInflowLinks)
                val effectiveStatus = BusinessBalanceCalculator.effectiveStatus(evt)
                val isPendingClass = BusinessBalanceCalculator.isPendingClassification(evt) && existingLink == null
                val (statusLabel, statusTone) = when {
                    effectiveStatus == ClassificationState.BUSINESS_PRODUCT ||
                        existingLink?.target == MpesaInflowLinkTarget.SALE -> "Product Sale" to EmofahBadgeTone.SUCCESS
                    effectiveStatus == ClassificationState.BUSINESS_SERVICE ||
                        existingLink?.target == MpesaInflowLinkTarget.SERVICE -> "Service" to EmofahBadgeTone.INFO
                    effectiveStatus == ClassificationState.NON_BUSINESS ||
                        existingLink?.target == MpesaInflowLinkTarget.DISMISSED -> "Non-Business (Excluded)" to EmofahBadgeTone.NEUTRAL
                    evt.reviewStatus == "NEEDS_MANUAL_FIX" -> "Check Details" to EmofahBadgeTone.DANGER
                    evt.syncStatus == SyncState.SERVER_CONFIRMED -> "Awaiting Classification" to EmofahBadgeTone.WARNING
                    else -> "Saved Locally" to EmofahBadgeTone.WARNING
                }

                EmofahCard(contentPadding = PaddingValues(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${if (isInflow) "+" else "-"} Ksh ${evt.amount.roundToInt()} · ${evt.transactionReference ?: evt.source}",
                                style = MaterialTheme.typography.titleSmall.tabular(),
                                fontWeight = FontWeight.Bold,
                                color = if (isInflow) colors.success else colors.danger,
                            )
                            Text(
                                text = listOfNotNull(
                                    channelLabel,
                                    evt.counterparty ?: if (isPendingClass) "Awaiting classification" else evt.classificationStatus,
                                    accountRef?.let { "Tel/Acc: $it" },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textPrimary,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        EmofahStatusBadge(text = statusLabel, tone = statusTone)
                    }

                    if (feeCost != null || balanceAfter != null) {
                        Text(
                            text = listOfNotNull(
                                feeCost?.let { "Fee: Ksh ${it.roundToInt()}" },
                                balanceAfter?.let { "New Bal: Ksh ${it.roundToInt()}" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall.tabular(),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    evt.rawEvidence?.let { evidence ->
                        Text(
                            text = evidence,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (isPendingClass) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            OutlinedButton(
                                onClick = { linkingProductTarget = evt },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Product", maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = { onUseMpesaForService(evt) },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Service", maxLines = 1)
                            }
                            TextButton(
                                onClick = {
                                    removalReason = "Personal / non-business transaction"
                                    removingBusinessTarget = evt
                                },
                            ) {
                                Text("Remove", maxLines = 1)
                            }
                        }
                    } else {
                        val linkedSummary = listOfNotNull(
                            evt.linkedProductName?.let { name ->
                                evt.linkedQuantity?.let { qty -> "$name ×$qty" } ?: name
                            },
                            evt.linkedSaleId?.let { "Sale #$it" },
                            evt.classificationNote,
                        ).joinToString(" · ").ifBlank { existingLink?.summary.orEmpty() }
                        if (linkedSummary.isNotBlank()) {
                            Text(
                                text = linkedSummary,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = if (evt.syncStatus == SyncState.SERVER_CONFIRMED) "Saved in Supabase" else "Saved on Phone (Queued)",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textMuted,
                        )
                        Text(
                            text = timeFormat.format(Date(evt.eventTimestampEpochMs)),
                            style = MaterialTheme.typography.labelSmall.tabular(),
                            color = colors.textMuted,
                        )
                    }
                }
            }
        }
    }

    linkingProductTarget?.let { targetEvent ->
        AlertDialog(
            onDismissRequest = { linkingProductTarget = null },
            title = { Text("Link to Product Sale") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Payment: Ksh ${targetEvent.amount.roundToInt()}" +
                            (targetEvent.transactionReference?.let { " · Ref $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Button(
                        onClick = {
                            linkingProductTarget = null
                            onUseMpesaForSale(targetEvent)
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Create New Product Sale & Link")
                    }
                    if (unlinkedRemoteSales.isNotEmpty()) {
                        HorizontalDivider(color = colors.borderSubtle)
                        Text(
                            text = "Or attach to existing unlinked sale (no double stock deduction):",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textMuted,
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            unlinkedRemoteSales.take(6).forEach { sale ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, colors.borderStrong),
                                    color = colors.surfaceCard,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            linkingProductTarget = null
                                            onLinkExistingSaleToTransaction(
                                                targetEvent,
                                                sale.saleId,
                                                sale.productId,
                                                sale.productName,
                                                sale.qtySold,
                                            )
                                        },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Sale #${sale.saleId} · ${sale.productName}",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = "Qty ${sale.qtySold}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = colors.textMuted,
                                            )
                                        }
                                        Text(
                                            text = "Ksh ${sale.totalPrice.roundToInt()}",
                                            style = MaterialTheme.typography.labelMedium.tabular(),
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { linkingProductTarget = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    removingBusinessTarget?.let { targetEvent ->
        AlertDialog(
            onDismissRequest = { removingBusinessTarget = null },
            title = { Text("Remove from Business Records") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "This marks Ksh ${targetEvent.amount.roundToInt()} as Non-Business and excludes it from Business Balance. The original captured payment and classification history stay preserved for audit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                    OutlinedTextField(
                        value = removalReason,
                        onValueChange = { removalReason = it },
                        label = { Text("Audit Reason") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = targetEvent
                        val note = removalReason.trim().ifBlank { "Personal / non-business transaction" }
                        removingBusinessTarget = null
                        onRemoveFromBusiness(target, note)
                    },
                ) {
                    Text("Confirm Exclude")
                }
            },
            dismissButton = {
                TextButton(onClick = { removingBusinessTarget = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}
