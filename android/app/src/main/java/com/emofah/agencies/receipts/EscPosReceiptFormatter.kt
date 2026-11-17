package com.emofah.agencies.receipts

import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stage 7.2 — Pure ESC/POS Thermal Printer Receipt Encoder.
 * Converts any `DigitalReceiptData` (or alignment test slip) into standard 58mm/80mm
 * ESC/POS command bytes without external printer vendor SDKs.
 */
object EscPosReceiptFormatter {

    val CMD_INIT = byteArrayOf(0x1B, 0x40)
    val CMD_ALIGN_LEFT = byteArrayOf(0x1B, 0x61, 0x00)
    val CMD_ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 0x01)
    val CMD_BOLD_ON = byteArrayOf(0x1B, 0x45, 0x01)
    val CMD_BOLD_OFF = byteArrayOf(0x1B, 0x45, 0x00)
    val CMD_TEXT_NORMAL = byteArrayOf(0x1D, 0x21, 0x00)
    val CMD_TEXT_DOUBLE = byteArrayOf(0x1D, 0x21, 0x11)
    val CMD_FEED_AND_CUT = byteArrayOf(0x1D, 0x56, 0x42, 0x00)

    fun sanitizeAscii(input: String): String {
        val normalized = input
            .replace('\u00B7', '-')
            .replace('\u00D7', 'x')
            .replace('\u2013', '-')
            .replace('\u2014', '-')
            .replace('\u2018', '\'')
            .replace('\u2019', '\'')
            .replace('\u201C', '"')
            .replace('\u201D', '"')
        val out = StringBuilder(normalized.length)
        for (ch in normalized) {
            when {
                ch == '\n' || ch == '\r' || ch == '\t' -> out.append(' ')
                ch.code in 32..126 -> out.append(ch)
                else -> out.append(' ')
            }
        }
        return out.toString().replace(Regex(" +"), " ").trim()
    }

    fun wrapLine(text: String, width: Int): List<String> {
        val safeWidth = width.coerceAtLeast(16)
        val clean = sanitizeAscii(text)
        if (clean.isEmpty()) return emptyList()
        if (clean.length <= safeWidth) return listOf(clean)

        val words = clean.split(' ')
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            if (word.length > safeWidth) {
                if (current.isNotEmpty()) {
                    lines.add(current.toString())
                    current = StringBuilder()
                }
                var idx = 0
                while (idx < word.length) {
                    val end = (idx + safeWidth).coerceAtMost(word.length)
                    lines.add(word.substring(idx, end))
                    idx = end
                }
                continue
            }
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (candidate.length <= safeWidth) {
                current = StringBuilder(candidate)
            } else {
                lines.add(current.toString())
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }

    fun formatKeyValueRow(left: String, right: String, width: Int): String {
        val safeWidth = width.coerceAtLeast(16)
        val cleanLeft = sanitizeAscii(left)
        val cleanRight = sanitizeAscii(right)
        if (cleanRight.length >= safeWidth) {
            return cleanRight.take(safeWidth)
        }
        val maxLeft = (safeWidth - cleanRight.length - 1).coerceAtLeast(1)
        val clippedLeft = if (cleanLeft.length > maxLeft) cleanLeft.take(maxLeft) else cleanLeft
        val spaces = (safeWidth - clippedLeft.length - cleanRight.length).coerceAtLeast(1)
        return clippedLeft + " ".repeat(spaces) + cleanRight
    }

    fun encodeReceipt(
        receipt: DigitalReceiptData,
        paperWidth: ThermalPaperWidth = ThermalPaperWidth.MM_58,
        businessName: String = DigitalReceiptFormatter.BUSINESS_NAME,
        locale: Locale = Locale.getDefault(),
    ): ByteArray {
        val cols = paperWidth.columns
        val rule = "-".repeat(cols)
        val dateStr = SimpleDateFormat("dd MMM yyyy, HH:mm", locale)
            .format(Date(receipt.issuedAtEpochMs))

        val out = ByteArrayOutputStream()
        fun writeRaw(bytes: ByteArray) = out.write(bytes)
        fun writeLine(text: String) {
            out.write(sanitizeAscii(text).toByteArray(Charsets.US_ASCII))
            out.write('\n'.code)
        }
        fun writeWrapped(text: String) {
            wrapLine(text, cols).forEach { writeLine(it) }
        }

        writeRaw(CMD_INIT)

        // Business Header
        writeRaw(CMD_ALIGN_CENTER)
        writeRaw(CMD_BOLD_ON)
        writeRaw(CMD_TEXT_DOUBLE)
        writeLine(businessName)
        writeRaw(CMD_TEXT_NORMAL)
        writeLine("DIGITAL RECEIPT")
        writeRaw(CMD_BOLD_OFF)
        writeRaw(CMD_ALIGN_LEFT)
        writeLine(rule)

        if (receipt.isReversed) {
            writeRaw(CMD_ALIGN_CENTER)
            writeRaw(CMD_BOLD_ON)
            writeLine("*** REVERSED / VOID ***")
            writeRaw(CMD_BOLD_OFF)
            writeRaw(CMD_ALIGN_LEFT)
            receipt.reversalReason?.takeIf { it.isNotBlank() }?.let { reason ->
                writeWrapped("Reason: $reason")
            }
            writeLine(rule)
        }

        writeWrapped("Receipt #: ${receipt.receiptNumber}")
        writeWrapped("Date: $dateStr")
        writeWrapped("Type: ${receipt.kind.label}")
        receipt.referenceOrderNumber?.takeIf { it.isNotBlank() }?.let {
            writeWrapped("Order Ref: $it")
        }
        receipt.customerName?.takeIf { it.isNotBlank() }?.let {
            writeWrapped("Customer: $it")
        }
        receipt.customerPhone?.takeIf { it.isNotBlank() }?.let {
            writeWrapped("Phone: $it")
        }
        writeLine(rule)

        // Line items
        receipt.lines.forEach { item ->
            writeRaw(CMD_BOLD_ON)
            writeWrapped(item.title)
            writeRaw(CMD_BOLD_OFF)
            val leftQty = buildString {
                append("Qty ${item.quantityLabel}")
                item.unitPrice?.takeIf { it > 0.0 }?.let {
                    append(" @ ${DigitalReceiptFormatter.formatMoney(it)}")
                }
            }
            val rightTotal = DigitalReceiptFormatter.formatMoney(item.lineTotal)
            writeLine(formatKeyValueRow(leftQty, rightTotal, cols))
            item.note?.takeIf { it.isNotBlank() }?.let { note ->
                writeWrapped("  ($note)")
            }
        }

        writeLine(rule)
        writeRaw(CMD_BOLD_ON)
        writeLine(
            formatKeyValueRow(
                left = "TOTAL",
                right = DigitalReceiptFormatter.formatMoney(receipt.totalAmount),
                width = cols,
            )
        )
        writeRaw(CMD_BOLD_OFF)
        writeWrapped("Paid via: ${receipt.paymentMethodLabel}")
        writeWrapped("Status: ${receipt.syncStatusLabel}")
        receipt.servedByLabel?.takeIf { it.isNotBlank() }?.let { actor ->
            writeWrapped("Served by: $actor")
        }
        receipt.notes.forEach { note ->
            writeWrapped("Note: $note")
        }

        writeLine(rule)
        writeRaw(CMD_ALIGN_CENTER)
        writeLine("Thank you for choosing")
        writeLine(businessName)
        writeRaw(CMD_ALIGN_LEFT)
        out.write('\n'.code)
        out.write('\n'.code)
        out.write('\n'.code)
        writeRaw(CMD_FEED_AND_CUT)

        return out.toByteArray()
    }

    fun encodeTestPrint(
        printerName: String,
        paperWidth: ThermalPaperWidth,
    ): ByteArray {
        val cols = paperWidth.columns
        val rule = "-".repeat(cols)
        val ruler = buildString {
            for (i in 1..cols) append((i % 10).toString())
        }
        val out = ByteArrayOutputStream()
        fun writeLine(text: String) {
            out.write(sanitizeAscii(text).toByteArray(Charsets.US_ASCII))
            out.write('\n'.code)
        }

        out.write(CMD_INIT)
        out.write(CMD_ALIGN_CENTER)
        out.write(CMD_BOLD_ON)
        out.write(CMD_TEXT_DOUBLE)
        writeLine(DigitalReceiptFormatter.BUSINESS_NAME)
        out.write(CMD_TEXT_NORMAL)
        writeLine("BLUETOOTH PRINTER TEST")
        out.write(CMD_BOLD_OFF)
        out.write(CMD_ALIGN_LEFT)
        writeLine(rule)
        wrapLine("Printer: $printerName", cols).forEach { writeLine(it) }
        writeLine("Width: ${paperWidth.label}")
        writeLine(ruler)
        writeLine(formatKeyValueRow("1x Sample Item", "Ksh 150", cols))
        out.write(CMD_BOLD_ON)
        writeLine(formatKeyValueRow("TEST TOTAL", "Ksh 150", cols))
        out.write(CMD_BOLD_OFF)
        writeLine(rule)
        out.write(CMD_ALIGN_CENTER)
        writeLine("Printer Alignment OK")
        out.write(CMD_ALIGN_LEFT)
        out.write('\n'.code)
        out.write('\n'.code)
        out.write('\n'.code)
        out.write(CMD_FEED_AND_CUT)
        return out.toByteArray()
    }
}
