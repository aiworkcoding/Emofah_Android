package com.emofah.agencies.receipts

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stage 7.1 — Native Android PDF Receipt Generator & Share Sheet Exporter.
 * Generates clean 80mm-width printable PDF receipts via `android.graphics.pdf.PdfDocument`
 * inside `cacheDir/receipts/` and shares via `FileProvider` (`ACTION_SEND`).
 */
object DigitalReceiptExporter {

    private const val RECEIPTS_DIR = "receipts"
    private const val PDF_WIDTH_PT = 320
    private const val PDF_MARGIN_PT = 18f
    private const val MAX_CACHED_PDFS = 20

    fun buildSafeReceiptFileName(receiptNumber: String): String {
        val sanitized = receiptNumber
            .trim()
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .trim('_')
            .ifBlank { "emofah_receipt" }
        return "receipt_$sanitized.pdf"
    }

    fun generateReceiptPdf(
        context: Context,
        receipt: DigitalReceiptData,
        locale: Locale = Locale.getDefault(),
    ): Result<File> = runCatching {
        val dir = File(context.cacheDir, RECEIPTS_DIR).apply { mkdirs() }
        pruneOldReceiptFiles(dir)

        val outFile = File(dir, buildSafeReceiptFileName(receipt.receiptNumber))
        val contentWidth = PDF_WIDTH_PT - (PDF_MARGIN_PT * 2f)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 13f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 9.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 9.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val regularPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 9f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 8f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        val rightPaint = Paint(regularPaint).apply {
            textAlign = Paint.Align.RIGHT
        }
        val rightBoldPaint = Paint(boldPaint).apply {
            textAlign = Paint.Align.RIGHT
            textSize = 11f
        }
        val linePaint = Paint().apply {
            color = Color.GRAY
            strokeWidth = 0.8f
        }

        val dateText = SimpleDateFormat("dd MMM yyyy, HH:mm", locale)
            .format(Date(receipt.issuedAtEpochMs))

        // Estimate dynamic vertical height cleanly before creating PdfDocument page
        var estimatedHeight = 140
        if (receipt.isReversed) estimatedHeight += 32
        if (!receipt.referenceOrderNumber.isNullOrBlank()) estimatedHeight += 13
        if (!receipt.customerName.isNullOrBlank()) estimatedHeight += 13
        if (!receipt.customerPhone.isNullOrBlank()) estimatedHeight += 13
        receipt.lines.forEach { item ->
            val titleLines = wrapText(item.title, boldPaint, contentWidth - 4f).size
            estimatedHeight += (titleLines * 12) + 18
            if (!item.note.isNullOrBlank()) {
                estimatedHeight += wrapText(item.note, mutedPaint, contentWidth - 8f).size * 11
            }
        }
        estimatedHeight += (receipt.notes.size * 14) + 110
        val pageHeight = estimatedHeight.coerceAtLeast(260)

        val document = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(PDF_WIDTH_PT, pageHeight, 1).create()
            val page = document.startPage(pageInfo)
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)

            val centerX = PDF_WIDTH_PT / 2f
            val leftX = PDF_MARGIN_PT
            val rightX = PDF_WIDTH_PT - PDF_MARGIN_PT
            var y = 24f

            canvas.drawText(DigitalReceiptFormatter.BUSINESS_NAME, centerX, y, titlePaint)
            y += 14f
            canvas.drawText("DIGITAL RECEIPT", centerX, y, subtitlePaint)
            y += 12f
            canvas.drawLine(leftX, y, rightX, y, linePaint)
            y += 14f

            if (receipt.isReversed) {
                val voidPaint = Paint(subtitlePaint).apply { color = Color.rgb(180, 24, 24) }
                canvas.drawText("*** REVERSED / VOID ***", centerX, y, voidPaint)
                y += 12f
                receipt.reversalReason?.takeIf { it.isNotBlank() }?.let { reason ->
                    wrapText("Reason: $reason", mutedPaint, contentWidth).forEach { wLine ->
                        canvas.drawText(wLine, leftX, y, mutedPaint)
                        y += 11f
                    }
                }
                canvas.drawLine(leftX, y, rightX, y, linePaint)
                y += 13f
            }

            canvas.drawText("Receipt #: ${receipt.receiptNumber}", leftX, y, boldPaint)
            y += 13f
            canvas.drawText("Date: $dateText", leftX, y, regularPaint)
            y += 12f
            canvas.drawText("Type: ${receipt.kind.label}", leftX, y, regularPaint)
            y += 12f
            receipt.referenceOrderNumber?.takeIf { it.isNotBlank() }?.let {
                canvas.drawText("Order Ref: $it", leftX, y, regularPaint)
                y += 12f
            }
            receipt.customerName?.takeIf { it.isNotBlank() }?.let {
                canvas.drawText("Customer: $it", leftX, y, regularPaint)
                y += 12f
            }
            receipt.customerPhone?.takeIf { it.isNotBlank() }?.let {
                canvas.drawText("Phone: $it", leftX, y, regularPaint)
                y += 12f
            }

            canvas.drawLine(leftX, y, rightX, y, linePaint)
            y += 14f

            receipt.lines.forEach { item ->
                wrapText(item.title, boldPaint, contentWidth).forEach { titleLine ->
                    canvas.drawText(titleLine, leftX, y, boldPaint)
                    y += 12f
                }
                val qtyAndUnit = buildString {
                    append("Qty: ${item.quantityLabel}")
                    item.unitPrice?.takeIf { it > 0.0 }?.let {
                        append(" @ ${DigitalReceiptFormatter.formatMoney(it)}")
                    }
                }
                canvas.drawText(qtyAndUnit, leftX, y, regularPaint)
                canvas.drawText(DigitalReceiptFormatter.formatMoney(item.lineTotal), rightX, y, rightPaint)
                y += 13f

                item.note?.takeIf { it.isNotBlank() }?.let { note ->
                    wrapText(note, mutedPaint, contentWidth - 6f).forEach { noteLine ->
                        canvas.drawText(noteLine, leftX + 4f, y, mutedPaint)
                        y += 11f
                    }
                }
                y += 3f
            }

            canvas.drawLine(leftX, y, rightX, y, linePaint)
            y += 16f

            val totalLabelPaint = Paint(boldPaint).apply { textSize = 11f }
            canvas.drawText("TOTAL", leftX, y, totalLabelPaint)
            canvas.drawText(DigitalReceiptFormatter.formatMoney(receipt.totalAmount), rightX, y, rightBoldPaint)
            y += 16f

            canvas.drawText("Paid via: ${receipt.paymentMethodLabel}", leftX, y, regularPaint)
            y += 12f
            canvas.drawText("Status: ${receipt.syncStatusLabel}", leftX, y, regularPaint)
            y += 12f
            receipt.servedByLabel?.takeIf { it.isNotBlank() }?.let { actor ->
                canvas.drawText("Served by: $actor", leftX, y, mutedPaint)
                y += 12f
            }
            receipt.notes.forEach { note ->
                wrapText(note, mutedPaint, contentWidth).forEach { noteLine ->
                    canvas.drawText(noteLine, leftX, y, mutedPaint)
                    y += 11f
                }
            }

            y += 6f
            canvas.drawLine(leftX, y, rightX, y, linePaint)
            y += 15f
            canvas.drawText("Thank you for your business!", centerX, y, mutedPaint.apply { textAlign = Paint.Align.CENTER })

            document.finishPage(page)
            FileOutputStream(outFile).use { stream ->
                document.writeTo(stream)
            }
        } finally {
            document.close()
        }
        outFile
    }

    fun shareReceiptPdf(
        context: Context,
        receipt: DigitalReceiptData,
    ): Result<Unit> = runCatching {
        val pdfFile = generateReceiptPdf(context, receipt).getOrThrow()
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            pdfFile,
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Emofah Receipt ${receipt.receiptNumber}")
            putExtra(
                Intent.EXTRA_TEXT,
                "Digital Receipt ${receipt.receiptNumber} · ${DigitalReceiptFormatter.formatMoney(receipt.totalAmount)}",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Share Receipt PDF").apply {
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(chooser)
    }

    fun shareReceiptText(
        context: Context,
        receipt: DigitalReceiptData,
    ): Result<Unit> = runCatching {
        val body = DigitalReceiptFormatter.formatPlainText(receipt)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Emofah Receipt ${receipt.receiptNumber}")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        val chooser = Intent.createChooser(shareIntent, "Share Receipt Text").apply {
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(chooser)
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        if (paint.measureText(clean) <= maxWidth) return listOf(clean)

        val words = clean.split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) {
                    lines.add(current.toString())
                    current = StringBuilder(word)
                } else {
                    lines.add(word.take(32))
                    current = StringBuilder()
                }
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }

    private fun pruneOldReceiptFiles(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        if (files.size > MAX_CACHED_PDFS) {
            files.drop(MAX_CACHED_PDFS).forEach { runCatching { it.delete() } }
        }
    }
}
