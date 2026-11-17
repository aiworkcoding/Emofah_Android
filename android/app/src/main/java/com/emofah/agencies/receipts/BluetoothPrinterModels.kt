package com.emofah.agencies.receipts

import android.content.Context

enum class ThermalPaperWidth(
    val mm: Int,
    val columns: Int,
    val label: String,
) {
    MM_58(mm = 58, columns = 32, label = "58mm (32 cols)"),
    MM_80(mm = 80, columns = 48, label = "80mm (48 cols)"),
    ;

    companion object {
        fun fromMm(mm: Int): ThermalPaperWidth {
            return entries.firstOrNull { it.mm == mm } ?: MM_58
        }
    }
}

data class SavedThermalPrinter(
    val address: String,
    val name: String,
    val paperWidth: ThermalPaperWidth = ThermalPaperWidth.MM_58,
)

data class BluetoothPrinterDevice(
    val address: String,
    val name: String,
    val isBonded: Boolean = true,
)

sealed interface BluetoothPrintOutcome {
    data class Printed(
        val printerName: String,
        val receiptNumber: String,
        val bytesSent: Int,
    ) : BluetoothPrintOutcome

    data class PermissionRequired(val message: String) : BluetoothPrintOutcome
    data class BluetoothDisabled(val message: String) : BluetoothPrintOutcome
    data class PrinterNotSelected(val message: String) : BluetoothPrintOutcome
    data class Failed(val message: String) : BluetoothPrintOutcome
}

/**
 * Stage 7.2 — Persists the staff device's selected Bluetooth thermal printer and paper width.
 */
class BluetoothPrinterPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readSavedPrinter(): SavedThermalPrinter? {
        val address = prefs.getString(KEY_ADDRESS, null)?.trim().orEmpty()
        if (address.isEmpty()) return null
        val name = prefs.getString(KEY_NAME, null)?.trim().orEmpty().ifBlank { address }
        val widthMm = prefs.getInt(KEY_PAPER_MM, ThermalPaperWidth.MM_58.mm)
        return SavedThermalPrinter(
            address = address,
            name = name,
            paperWidth = ThermalPaperWidth.fromMm(widthMm),
        )
    }

    fun readPreferredWidth(): ThermalPaperWidth {
        return ThermalPaperWidth.fromMm(prefs.getInt(KEY_PAPER_MM, ThermalPaperWidth.MM_58.mm))
    }

    fun savePrinter(
        address: String,
        name: String,
        paperWidth: ThermalPaperWidth,
    ): SavedThermalPrinter {
        val cleanAddress = address.trim()
        val cleanName = name.trim().ifBlank { cleanAddress }
        prefs.edit()
            .putString(KEY_ADDRESS, cleanAddress)
            .putString(KEY_NAME, cleanName)
            .putInt(KEY_PAPER_MM, paperWidth.mm)
            .apply()
        return SavedThermalPrinter(
            address = cleanAddress,
            name = cleanName,
            paperWidth = paperWidth,
        )
    }

    fun updatePaperWidth(paperWidth: ThermalPaperWidth) {
        prefs.edit()
            .putInt(KEY_PAPER_MM, paperWidth.mm)
            .apply()
    }

    fun clearSavedPrinter() {
        prefs.edit()
            .remove(KEY_ADDRESS)
            .remove(KEY_NAME)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "emofah_bluetooth_printer"
        private const val KEY_ADDRESS = "printer_address"
        private const val KEY_NAME = "printer_name"
        private const val KEY_PAPER_MM = "paper_width_mm"
    }
}
