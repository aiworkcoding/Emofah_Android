package com.emofah.agencies.receipts

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Stage 7.2 — Native Android Classic Bluetooth SPP Thermal Printer Transport.
 * Handles Android 12+ (`BLUETOOTH_CONNECT`) permission checks cleanly, lists paired
 * printers, and sends ESC/POS receipt payloads over RFCOMM SPP (`00001101-...`).
 */
object BluetoothPrinterManager {

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    fun requiresRuntimeConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }

    fun hasBluetoothConnectPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun getAdapter(context: Context): BluetoothAdapter? {
        val manager = context.applicationContext.getSystemService(BluetoothManager::class.java)
        return manager?.adapter
    }

    fun listBondedPrinters(context: Context): Result<List<BluetoothPrinterDevice>> = runCatching {
        if (!hasBluetoothConnectPermission(context)) {
            error("Bluetooth permission is required to view paired printers.")
        }
        val adapter = getAdapter(context)
            ?: error("This device does not support Bluetooth printing.")
        if (!adapter.isEnabled) {
            error("Turn on Bluetooth on your Android device first.")
        }
        readBondedDevicesUnsafe(adapter)
            .sortedWith(
                compareByDescending<BluetoothPrinterDevice> { looksLikePosPrinter(it.name) }
                    .thenBy { it.name.lowercase() }
            )
    }

    suspend fun printReceipt(
        context: Context,
        printer: SavedThermalPrinter?,
        receipt: DigitalReceiptData,
    ): BluetoothPrintOutcome {
        if (printer == null || printer.address.isBlank()) {
            return BluetoothPrintOutcome.PrinterNotSelected(
                "Select a paired Bluetooth thermal printer first."
            )
        }
        val payload = EscPosReceiptFormatter.encodeReceipt(
            receipt = receipt,
            paperWidth = printer.paperWidth,
        )
        return sendEscPosBytes(
            context = context,
            printer = printer,
            payload = payload,
            receiptNumber = receipt.receiptNumber,
        )
    }

    suspend fun printTestSlip(
        context: Context,
        printer: SavedThermalPrinter?,
    ): BluetoothPrintOutcome {
        if (printer == null || printer.address.isBlank()) {
            return BluetoothPrintOutcome.PrinterNotSelected(
                "Select a paired Bluetooth thermal printer first."
            )
        }
        val payload = EscPosReceiptFormatter.encodeTestPrint(
            printerName = printer.name,
            paperWidth = printer.paperWidth,
        )
        return sendEscPosBytes(
            context = context,
            printer = printer,
            payload = payload,
            receiptNumber = "TEST-PRINT",
        )
    }

    private suspend fun sendEscPosBytes(
        context: Context,
        printer: SavedThermalPrinter,
        payload: ByteArray,
        receiptNumber: String,
    ): BluetoothPrintOutcome = withContext(Dispatchers.IO) {
        if (!hasBluetoothConnectPermission(context)) {
            return@withContext BluetoothPrintOutcome.PermissionRequired(
                "Allow Nearby Devices / Bluetooth permission to print receipts."
            )
        }
        val adapter = getAdapter(context)
            ?: return@withContext BluetoothPrintOutcome.Failed(
                "Bluetooth hardware is unavailable on this device."
            )
        if (!adapter.isEnabled) {
            return@withContext BluetoothPrintOutcome.BluetoothDisabled(
                "Bluetooth is turned off. Enable Bluetooth and try again."
            )
        }

        val device = runCatching { adapter.getRemoteDevice(printer.address) }.getOrNull()
            ?: return@withContext BluetoothPrintOutcome.Failed(
                "Saved printer (${printer.name}) address is invalid."
            )

        writeToRfcommSocket(
            adapter = adapter,
            device = device,
            printerName = printer.name,
            payload = payload,
            receiptNumber = receiptNumber,
        )
    }

    @SuppressLint("MissingPermission")
    private fun readBondedDevicesUnsafe(adapter: BluetoothAdapter): List<BluetoothPrinterDevice> {
        val bonded = adapter.bondedDevices ?: return emptyList()
        return bonded.map { dev ->
            BluetoothPrinterDevice(
                address = dev.address,
                name = dev.name?.trim().takeUnless { it.isNullOrBlank() } ?: dev.address,
                isBonded = true,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeToRfcommSocket(
        adapter: BluetoothAdapter,
        device: BluetoothDevice,
        printerName: String,
        payload: ByteArray,
        receiptNumber: String,
    ): BluetoothPrintOutcome {
        return try {
            runCatching {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && adapter.isDiscovering) {
                    adapter.cancelDiscovery()
                }
            }
            val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket.use { rfcomm ->
                rfcomm.connect()
                val stream = rfcomm.outputStream
                stream.write(payload)
                stream.flush()
                // Brief flush window before closing SPP socket so trailing feed/cut completes
                Thread.sleep(250)
            }
            BluetoothPrintOutcome.Printed(
                printerName = printerName,
                receiptNumber = receiptNumber,
                bytesSent = payload.size,
            )
        } catch (sec: SecurityException) {
            BluetoothPrintOutcome.PermissionRequired(
                sec.message ?: "Bluetooth permission denied while connecting to printer."
            )
        } catch (e: Exception) {
            BluetoothPrintOutcome.Failed(
                "Could not connect to $printerName. Ensure the printer is powered on, within range, and paired (${e.message ?: "connection failed"})."
            )
        }
    }

    private fun looksLikePosPrinter(name: String): Boolean {
        val lower = name.lowercase()
        return listOf("pos", "print", "mpt", "rp", "xp", "58", "80", "star", "epson", "zjiang", "mtp")
            .any { lower.contains(it) }
    }
}
