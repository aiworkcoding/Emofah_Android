package com.emofah.agencies.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.emofah.agencies.receipts.BluetoothPrintOutcome
import com.emofah.agencies.receipts.BluetoothPrinterDevice
import com.emofah.agencies.receipts.BluetoothPrinterManager
import com.emofah.agencies.receipts.BluetoothPrinterPrefs
import com.emofah.agencies.receipts.DigitalReceiptData
import com.emofah.agencies.receipts.DigitalReceiptExporter
import com.emofah.agencies.receipts.DigitalReceiptFormatter
import com.emofah.agencies.receipts.ThermalPaperWidth
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class PendingBluetoothAction {
    OPEN_PICKER,
    PRINT_RECEIPT,
    TEST_PRINT,
}

/**
 * Stage 7.1 + Stage 7.2 — Native Digital Receipt preview modal with:
 * - Bluetooth thermal ESC/POS printing (58mm / 80mm, paired printer selection, test slip)
 * - PDF and plain-text sharing
 */
@Composable
fun DigitalReceiptPreviewDialog(
    receipt: DigitalReceiptData,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val printerPrefs = remember(context) { BluetoothPrinterPrefs(context) }

    var savedPrinter by remember(context) { mutableStateOf(printerPrefs.readSavedPrinter()) }
    var shareOrPrintError by remember(receipt.receiptNumber) { mutableStateOf<String?>(null) }
    var printSuccessMessage by remember(receipt.receiptNumber) { mutableStateOf<String?>(null) }
    var isPrinting by remember { mutableStateOf(false) }

    var showPrinterPicker by remember { mutableStateOf(false) }
    var bondedPrinters by remember { mutableStateOf<List<BluetoothPrinterDevice>>(emptyList()) }
    var pickerMessage by remember { mutableStateOf<String?>(null) }
    var pendingPermissionAction by remember { mutableStateOf<PendingBluetoothAction?>(null) }

    val dateFormatted = remember(receipt.issuedAtEpochMs) {
        SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault())
            .format(Date(receipt.issuedAtEpochMs))
    }

    fun loadPairedPrinters() {
        BluetoothPrinterManager.listBondedPrinters(context)
            .onSuccess { list ->
                bondedPrinters = list
                pickerMessage = if (list.isEmpty()) {
                    "No paired Bluetooth devices found. Pair your printer in Android Bluetooth Settings first, then tap Refresh."
                } else {
                    null
                }
            }
            .onFailure { err ->
                bondedPrinters = emptyList()
                pickerMessage = err.message ?: "Unable to read paired Bluetooth printers."
            }
    }

    fun executePrintReceipt() {
        val target = savedPrinter
        if (target == null) {
            loadPairedPrinters()
            showPrinterPicker = true
            return
        }
        isPrinting = true
        shareOrPrintError = null
        printSuccessMessage = null
        scope.launch {
            when (val outcome = BluetoothPrinterManager.printReceipt(context, target, receipt)) {
                is BluetoothPrintOutcome.Printed -> {
                    printSuccessMessage = "Printed ${outcome.receiptNumber} to ${outcome.printerName} (${outcome.bytesSent} bytes)."
                }
                is BluetoothPrintOutcome.PrinterNotSelected -> {
                    loadPairedPrinters()
                    showPrinterPicker = true
                }
                is BluetoothPrintOutcome.BluetoothDisabled -> {
                    shareOrPrintError = outcome.message
                }
                is BluetoothPrintOutcome.PermissionRequired -> {
                    shareOrPrintError = outcome.message
                }
                is BluetoothPrintOutcome.Failed -> {
                    shareOrPrintError = outcome.message
                }
            }
            isPrinting = false
        }
    }

    fun executeTestPrint() {
        val target = savedPrinter ?: return
        isPrinting = true
        pickerMessage = null
        scope.launch {
            when (val outcome = BluetoothPrinterManager.printTestSlip(context, target)) {
                is BluetoothPrintOutcome.Printed -> {
                    pickerMessage = "Test slip printed on ${outcome.printerName}."
                }
                is BluetoothPrintOutcome.BluetoothDisabled -> {
                    pickerMessage = outcome.message
                }
                is BluetoothPrintOutcome.PermissionRequired -> {
                    pickerMessage = outcome.message
                }
                is BluetoothPrintOutcome.PrinterNotSelected -> {
                    pickerMessage = outcome.message
                }
                is BluetoothPrintOutcome.Failed -> {
                    pickerMessage = outcome.message
                }
            }
            isPrinting = false
        }
    }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingPermissionAction
        pendingPermissionAction = null
        if (granted) {
            when (action) {
                PendingBluetoothAction.OPEN_PICKER -> {
                    loadPairedPrinters()
                    showPrinterPicker = true
                }
                PendingBluetoothAction.PRINT_RECEIPT -> executePrintReceipt()
                PendingBluetoothAction.TEST_PRINT -> executeTestPrint()
                null -> Unit
            }
        } else {
            shareOrPrintError = "Bluetooth permission is required to connect to a thermal printer."
        }
    }

    fun runWithBluetoothPermission(action: PendingBluetoothAction) {
        if (BluetoothPrinterManager.hasBluetoothConnectPermission(context)) {
            when (action) {
                PendingBluetoothAction.OPEN_PICKER -> {
                    loadPairedPrinters()
                    showPrinterPicker = true
                }
                PendingBluetoothAction.PRINT_RECEIPT -> executePrintReceipt()
                PendingBluetoothAction.TEST_PRINT -> executeTestPrint()
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pendingPermissionAction = action
            btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            shareOrPrintError = "Bluetooth permission unavailable on this device."
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isPrinting) onDismiss() },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.ReceiptLong,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column {
                    Text(
                        text = "Digital Receipt",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = receipt.receiptNumber,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = DigitalReceiptFormatter.BUSINESS_NAME,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "${receipt.kind.label} · $dateFormatted",
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        if (receipt.isReversed) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        text = "REVERSED / VOID RECEIPT",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    receipt.reversalReason?.takeIf { it.isNotBlank() }?.let { reason ->
                                        Text(
                                            text = "Reason: $reason",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                    }
                                }
                            }
                        }

                        receipt.referenceOrderNumber?.takeIf { it.isNotBlank() }?.let { orderRef ->
                            Text(
                                text = "Order Ref: $orderRef",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (!receipt.customerName.isNullOrBlank() || !receipt.customerPhone.isNullOrBlank()) {
                            Text(
                                text = "Customer: " + listOfNotNull(receipt.customerName, receipt.customerPhone)
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        HorizontalDivider()

                        receipt.lines.forEach { item ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = DigitalReceiptFormatter.formatMoney(item.lineTotal),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                                val unitPart = item.unitPrice?.takeIf { it > 0.0 }?.let {
                                    " @ ${DigitalReceiptFormatter.formatMoney(it)}"
                                }.orEmpty()
                                Text(
                                    text = "Qty: ${item.quantityLabel}$unitPart",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                item.note?.takeIf { it.isNotBlank() }?.let { note ->
                                    Text(
                                        text = note,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        HorizontalDivider()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "TOTAL",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = DigitalReceiptFormatter.formatMoney(receipt.totalAmount),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Paid via: ${receipt.paymentMethodLabel}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                text = receipt.syncStatusLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        receipt.servedByLabel?.takeIf { it.isNotBlank() }?.let { actor ->
                            Text(
                                text = "Served by: $actor",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        receipt.notes.forEach { note ->
                            Text(
                                text = note,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Stage 7.2 — Saved Bluetooth Printer Strip
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Bluetooth,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = savedPrinter?.let { "${it.name} (${it.paperWidth.mm}mm)" }
                                    ?: "No Bluetooth printer selected",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        TextButton(
                            onClick = { runWithBluetoothPermission(PendingBluetoothAction.OPEN_PICKER) },
                            enabled = !isPrinting,
                        ) {
                            Text(if (savedPrinter == null) "Select" else "Change")
                        }
                    }
                }

                printSuccessMessage?.let { okMsg ->
                    Text(
                        text = okMsg,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }

                shareOrPrintError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // Stage 7.2 — Print Thermal Receipt
                Button(
                    onClick = {
                        if (savedPrinter == null) {
                            runWithBluetoothPermission(PendingBluetoothAction.OPEN_PICKER)
                        } else {
                            runWithBluetoothPermission(PendingBluetoothAction.PRINT_RECEIPT)
                        }
                    },
                    enabled = !isPrinting,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (isPrinting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Print,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = when {
                            isPrinting -> "Printing..."
                            savedPrinter == null -> "Connect Bluetooth Printer"
                            else -> "Print Receipt"
                        },
                    )
                }

                // Stage 7.1 — Digital Share Actions (PDF + Plain Text)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            shareOrPrintError = null
                            DigitalReceiptExporter.shareReceiptPdf(context, receipt)
                                .onFailure { err ->
                                    shareOrPrintError = err.message ?: "Unable to share PDF receipt on this device."
                                }
                        },
                        enabled = !isPrinting,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PictureAsPdf,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Share PDF")
                    }

                    OutlinedButton(
                        onClick = {
                            shareOrPrintError = null
                            DigitalReceiptExporter.shareReceiptText(context, receipt)
                                .onFailure { err ->
                                    shareOrPrintError = err.message ?: "Unable to share receipt text on this device."
                                }
                        },
                        enabled = !isPrinting,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Share Text")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !isPrinting) {
                Text("Close")
            }
        },
    )

    if (showPrinterPicker) {
        var selectedWidth by remember(savedPrinter) {
            mutableStateOf(savedPrinter?.paperWidth ?: printerPrefs.readPreferredWidth())
        }

        AlertDialog(
            onDismissRequest = { if (!isPrinting) showPrinterPicker = false },
            title = {
                Text(
                    text = "Bluetooth Thermal Printer",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Paper Width",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ThermalPaperWidth.entries.forEach { width ->
                            FilterChip(
                                selected = selectedWidth == width,
                                onClick = {
                                    selectedWidth = width
                                    printerPrefs.updatePaperWidth(width)
                                    savedPrinter = savedPrinter?.copy(paperWidth = width)
                                },
                                label = { Text(width.label) },
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Paired Bluetooth Devices",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        TextButton(
                            onClick = { runWithBluetoothPermission(PendingBluetoothAction.OPEN_PICKER) },
                            enabled = !isPrinting,
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Refresh")
                        }
                    }

                    pickerMessage?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 210.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        bondedPrinters.forEach { dev ->
                            val isSelected = savedPrinter?.address == dev.address
                            Surface(
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !isPrinting) {
                                        savedPrinter = printerPrefs.savePrinter(
                                            address = dev.address,
                                            name = dev.name,
                                            paperWidth = selectedWidth,
                                        )
                                        pickerMessage = "Selected ${dev.name} (${selectedWidth.label})."
                                    },
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = dev.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = dev.address,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (isSelected) {
                                        Text(
                                            text = "Active",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                                            if (context !is android.app.Activity) {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        }
                                    )
                                }
                            },
                            enabled = !isPrinting,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Pair in Settings")
                        }
                        if (savedPrinter != null) {
                            OutlinedButton(
                                onClick = { runWithBluetoothPermission(PendingBluetoothAction.TEST_PRINT) },
                                enabled = !isPrinting,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(if (isPrinting) "Printing..." else "Test Print")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showPrinterPicker = false },
                    enabled = !isPrinting,
                ) {
                    Text("Done")
                }
            },
        )
    }
}
