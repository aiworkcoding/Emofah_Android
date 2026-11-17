package com.emofah.agencies

import com.emofah.agencies.receipts.DigitalReceiptData
import com.emofah.agencies.receipts.EscPosReceiptFormatter
import com.emofah.agencies.receipts.ReceiptLineItem
import com.emofah.agencies.receipts.ReceiptTransactionKind
import com.emofah.agencies.receipts.ThermalPaperWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class BluetoothPrintingStage72Test {

    @Test
    fun sanitizeAsciiAndColumnFormatting_handle58mmAnd80mmWidthsCleanly() {
        assertEquals(
            "A4 Paper - 2 x 500 - Pack",
            EscPosReceiptFormatter.sanitizeAscii("A4 Paper · 2 × 500 — Pack"),
        )

        val row58 = EscPosReceiptFormatter.formatKeyValueRow(
            left = "Qty 2 @ Ksh 350",
            right = "Ksh 700",
            width = ThermalPaperWidth.MM_58.columns,
        )
        assertEquals(ThermalPaperWidth.MM_58.columns, row58.length)
        assertTrue(row58.startsWith("Qty 2 @ Ksh 350"))
        assertTrue(row58.endsWith("Ksh 700"))

        val row80 = EscPosReceiptFormatter.formatKeyValueRow(
            left = "TOTAL",
            right = "Ksh 12500",
            width = ThermalPaperWidth.MM_80.columns,
        )
        assertEquals(ThermalPaperWidth.MM_80.columns, row80.length)
        assertTrue(row80.startsWith("TOTAL"))
        assertTrue(row80.endsWith("Ksh 12500"))

        val wrapped = EscPosReceiptFormatter.wrapLine(
            text = "HP LaserJet Original Black Toner Cartridge 85A",
            width = 24,
        )
        assertTrue(wrapped.size >= 2)
        assertTrue(wrapped.all { it.length <= 24 })
    }

    @Test
    fun encodeReceipt_producesValidEscPosCommandsAndPreservesReversedMarker() {
        val receipt = DigitalReceiptData(
            receiptNumber = "EMO-S-109",
            kind = ReceiptTransactionKind.PRODUCT_SALE,
            issuedAtEpochMs = 1794816000000L,
            paymentMethodLabel = "M-Pesa",
            totalAmount = 600.0,
            lines = listOf(
                ReceiptLineItem(
                    title = "Counter Book 4 Quire",
                    quantityLabel = "2",
                    unitPrice = 300.0,
                    lineTotal = 600.0,
                    note = "Price adjusted (Promo)",
                )
            ),
            syncStatusLabel = "Reversed",
            isReversed = true,
            reversalReason = "Duplicate entry",
            servedByLabel = "cashier-1",
        )

        val bytes = EscPosReceiptFormatter.encodeReceipt(
            receipt = receipt,
            paperWidth = ThermalPaperWidth.MM_58,
            locale = Locale.US,
        )

        // Starts with ESC @ (0x1B, 0x40)
        assertTrue(bytes.size > 40)
        assertEquals(0x1B.toByte(), bytes[0])
        assertEquals(0x40.toByte(), bytes[1])

        // Ends with GS V 66 0 cut command
        assertEquals(0x1D.toByte(), bytes[bytes.size - 4])
        assertEquals(0x56.toByte(), bytes[bytes.size - 3])
        assertEquals(0x42.toByte(), bytes[bytes.size - 2])
        assertEquals(0x00.toByte(), bytes[bytes.size - 1])

        val asciiBody = bytes.toString(Charsets.US_ASCII)
        assertTrue(asciiBody.contains("EMOFAH AGENCIES"))
        assertTrue(asciiBody.contains("*** REVERSED / VOID ***"))
        assertTrue(asciiBody.contains("Duplicate entry"))
        assertTrue(asciiBody.contains("EMO-S-109"))
        assertTrue(asciiBody.contains("Counter Book 4 Quire"))
        assertTrue(asciiBody.contains("TOTAL"))
        assertTrue(asciiBody.contains("Ksh 600"))
        assertTrue(asciiBody.contains("Paid via: M-Pesa"))
    }

    @Test
    fun encodeTestPrint_andPaperWidthLookup_workAsExpected() {
        assertEquals(ThermalPaperWidth.MM_80, ThermalPaperWidth.fromMm(80))
        assertEquals(ThermalPaperWidth.MM_58, ThermalPaperWidth.fromMm(999))

        val testBytes = EscPosReceiptFormatter.encodeTestPrint(
            printerName = "Xprinter-58H",
            paperWidth = ThermalPaperWidth.MM_58,
        )
        val ascii = testBytes.toString(Charsets.US_ASCII)
        assertTrue(ascii.contains("BLUETOOTH PRINTER TEST"))
        assertTrue(ascii.contains("Xprinter-58H"))
        assertTrue(ascii.contains("12345678901234567890123456789012"))
        assertFalse(ascii.contains("REVERSED / VOID"))
    }
}
