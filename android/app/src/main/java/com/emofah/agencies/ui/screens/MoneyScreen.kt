package com.emofah.agencies.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.emofah.agencies.data.local.entities.PendingFinancialEventEntity
import com.emofah.agencies.financial.FinancialEventDraft
import com.emofah.agencies.financial.FinancialEventType
import com.emofah.agencies.financial.FinancialMonitoringState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Stage 5.2 — Financial Activity & Consent-Gated M-Pesa Message Monitoring Screen.
 * Enforces explicit consent, Play-compliant SMS disclosure, manual M-Pesa SMS paste fallback,
 * quick expense capture, and live Room pending financial event visibility.
 */
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var pasteSender by remember { mutableStateOf("MPESA") }
    var pasteBody by remember { mutableStateOf("") }
    var showManualExpense by remember { mutableStateOf(false) }
    var expenseAmountText by remember { mutableStateOf("") }
    var expenseReference by remember { mutableStateOf("") }
    var expenseNote by remember { mutableStateOf("") }
    var selectedManualType by remember { mutableStateOf(FinancialEventType.EXPENSE_OUTFLOW) }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        onPermissionResult()
    }

    val timeFormat = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 0. Physical Purchase Receipt Scanner (OCR + AI Draft)
        OutlinedButton(
            onClick = onOpenReceiptScanner,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Outlined.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Scan Physical Purchase Receipt",
                fontWeight = FontWeight.SemiBold,
            )
        }

        // 1. M-Pesa SMS Monitoring Consent & Permission Compliance Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (monitoringState.isLiveSmsActive) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = if (monitoringState.isLiveSmsActive) {
                                Icons.Outlined.CheckCircle
                            } else {
                                Icons.Outlined.Sms
                            },
                            contentDescription = null,
                        )
                        Column {
                            Text(
                                text = "M-Pesa SMS Monitoring",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = when {
                                    monitoringState.isLiveSmsActive -> "Active · Listening only to MPESA sender"
                                    monitoringState.isConsentGranted && !monitoringState.hasSmsPermissions ->
                                        "Consent granted · Waiting for SMS permission"
                                    else -> "Paused · Requires explicit consent"
                                },
                                style = MaterialTheme.typography.labelSmall,
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
                                        )
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
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
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
                            text = "Privacy guarantee: Emofah only inspects incoming SMS from official M-Pesa sender IDs locally on this phone. Personal chats, OTPs, and promo messages are ignored and never uploaded.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    )
                                )
                            },
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
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = "Filtered promos/non-M-Pesa: ${monitoringState.ignoredNonFinancialCount}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        statusMessage?.let { msg ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        // 2. Mode Switcher: Capture Pasted M-Pesa vs Record Manual Expense/Inflow
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
                label = { Text("Paste / Test M-Pesa") },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = showManualExpense,
                onClick = { showManualExpense = true },
                leadingIcon = {
                    Icon(Icons.Outlined.ReceiptLong, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                label = { Text("Manual Expense / Cash") },
                modifier = Modifier.weight(1f),
            )
        }

        if (!showManualExpense) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Import or Test M-Pesa Confirmation SMS",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = pasteSender,
                        onValueChange = { pasteSender = it },
                        label = { Text("Sender ID") },
                        singleLine = true,
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
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                pasteSender = "MPESA"
                                pasteBody =
                                    "QG789XYZ12 Confirmed. You have received Ksh1,500.00 from JANE WANJIKU 0712345678 on 15/11/26 at 10:14 AM. New M-PESA balance is Ksh14,200.00."
                            },
                        ) {
                            Text("Sample Inflow")
                        }
                        OutlinedButton(
                            onClick = {
                                pasteSender = "MPESA"
                                pasteBody =
                                    "QH123ABC99 Confirmed. Ksh850.00 sent to KPLC PREPAID for account 1422334455 on 15/11/26 at 11:00 AM. New M-PESA balance is Ksh13,350.00. Transaction cost, Ksh23.00."
                            },
                        ) {
                            Text("Sample Paybill")
                        }
                    }
                    Button(
                        onClick = {
                            onCapturePastedMpesa(pasteSender, pasteBody)
                        },
                        enabled = pasteBody.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Parse, Validate & Capture")
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Record Manual Cash / Expense Event",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = expenseReference,
                        onValueChange = { expenseReference = it },
                        label = { Text("Receipt / Ref Code (Optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = expenseNote,
                        onValueChange = { expenseNote = it },
                        label = { Text("Payee / Operational Note") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "Note: Final accounting categories & ledger rules are assigned on Desktop after sync.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            val amount = expenseAmountText.replace(",", "").trim().toDoubleOrNull() ?: 0.0
                            onRecordManualEvent(
                                FinancialEventDraft(
                                    eventType = selectedManualType,
                                    amount = amount,
                                    category = "PENDING_DESKTOP_RULES",
                                    referenceCode = expenseReference.trim().takeIf { it.isNotBlank() },
                                    notes = expenseNote,
                                )
                            )
                            if (amount.isFinite() && amount > 0.0) {
                                expenseAmountText = ""
                                expenseReference = ""
                                expenseNote = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Save & Queue Event")
                    }
                }
            }
        }

        HorizontalDivider()

        // 3. Captured Financial Activity Feed
        val pendingCount = financialEvents.count { it.syncStatus != "SERVER_CONFIRMED" }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Outlined.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    text = "Captured Events (${financialEvents.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (pendingCount > 0) {
                OutlinedButton(
                    onClick = onSyncNow,
                    enabled = !isSyncing,
                ) {
                    Text(if (isSyncing) "Syncing..." else "Sync ($pendingCount)")
                }
            }
        }

        if (financialEvents.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Text(
                    text = "No financial events captured yet. Enable M-Pesa SMS monitoring above or paste/record an entry.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp),
                )
            }
        } else {
            financialEvents.take(30).forEach { evt ->
                val isInflow = evt.direction == "INFLOW"
                val parsedObj = remember(evt.parsedJson) {
                    evt.parsedJson?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
                }
                val channelLabel = parsedObj?.optString("channel_label")?.takeIf { it.isNotBlank() }
                val accountRef = parsedObj?.optString("counterparty_account")?.takeIf { it.isNotBlank() && it != "null" }
                val feeCost = parsedObj?.takeIf { !it.isNull("transaction_cost") }?.optDouble("transaction_cost")
                val balanceAfter = parsedObj?.takeIf { !it.isNull("new_balance") }?.optDouble("new_balance")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${if (isInflow) "+" else "-"} Ksh ${evt.amount.roundToInt()} · ${evt.transactionReference ?: evt.source}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isInflow) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                                Text(
                                    text = listOfNotNull(
                                        channelLabel,
                                        evt.counterparty ?: evt.provisionalCategory,
                                        accountRef?.let { "Acc/Tel: $it" },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (evt.reviewStatus == "NEEDS_MANUAL_FIX") {
                                    MaterialTheme.colorScheme.errorContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                },
                            ) {
                                Text(
                                    text = evt.reviewStatus,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                        if (feeCost != null || balanceAfter != null) {
                            Text(
                                text = listOfNotNull(
                                    feeCost?.let { "Fee: Ksh ${it.roundToInt()}" },
                                    balanceAfter?.let { "New Bal: Ksh ${it.roundToInt()}" },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        evt.rawEvidence?.let { evidence ->
                            Text(
                                text = evidence,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "${evt.source} · ${evt.syncStatus}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = timeFormat.format(Date(evt.eventTimestampEpochMs)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}
