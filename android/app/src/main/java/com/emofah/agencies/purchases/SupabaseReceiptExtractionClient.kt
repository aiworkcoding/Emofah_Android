package com.emofah.agencies.purchases

import android.graphics.Bitmap
import android.util.Base64
import com.emofah.agencies.camera.ImageQualityMath
import com.emofah.agencies.camera.PreparedCaptureResult
import com.emofah.agencies.data.SupabaseConfig
import com.emofah.agencies.data.catalog.ProductIdentity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

interface ReceiptExtractionGateway {
    suspend fun extractReceipt(
        capture: PreparedCaptureResult,
        catalogueProducts: List<ProductIdentity>,
        isOnline: Boolean,
    ): PurchaseReceiptDraft
}

/**
 * Local-first receipt & supplier invoice recognition engine:
 * 1. Corrects EXIF orientation and enhances paper/thermal ink contrast via [ReceiptImageEnhancer].
 * 2. Extracts ML Kit text together with 2D bounding boxes ([OcrSpatialLine]).
 * 3. Structures supplier, invoice ref, date, payment details, multi-column line items,
 *    discounts, VAT/tax, subtotal, and grand total locally via [ReceiptLayoutParser].
 * 4. Flags arithmetic discrepancies without mutating extracted values via [ReceiptArithmeticValidator].
 * 5. Always marks drafts unconfirmed (`isHumanConfirmed = false`) for staff verification.
 */
class SupabaseReceiptExtractionClient(
    private val endpointUrl: String = SupabaseConfig.receiptExtractionEndpoint,
    private val anonKey: String = SupabaseConfig.anonKey,
) : ReceiptExtractionGateway {

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private data class LocalOcrCapture(
        val rawText: String,
        val spatialLines: List<OcrSpatialLine>,
    )

    override suspend fun extractReceipt(
        capture: PreparedCaptureResult,
        catalogueProducts: List<ProductIdentity>,
        isOnline: Boolean,
    ): PurchaseReceiptDraft = withContext(Dispatchers.IO) {
        val imagePath = File(capture.originalPath).takeIf { it.exists() }?.absolutePath
            ?: capture.recognitionCopyPath
        val localOcr = runLocalSpatialOcr(imagePath)

        val localDraft = if (localOcr.spatialLines.isNotEmpty()) {
            ReceiptLayoutParser.parseSpatialLines(
                spatialLines = localOcr.spatialLines,
                catalogueProducts = catalogueProducts,
                photoPath = imagePath,
            )
        } else {
            ReceiptLayoutParser.parseRawText(
                rawOcrText = localOcr.rawText,
                catalogueProducts = catalogueProducts,
                photoPath = imagePath,
            )
        }

        // Only try optional cloud enhancement if online, configured, AND local parser didn't already extract items cleanly
        if (isOnline && endpointUrl.isNotBlank() && localDraft.lines.isEmpty()) {
            val base64Jpeg = encodeReceiptJpegBase64(imagePath)
            if (base64Jpeg != null || localOcr.rawText.isNotBlank()) {
                val aiOutcome = callExtractReceiptEdge(
                    imageBase64 = base64Jpeg.orEmpty(),
                    ocrText = localDraft.rawOcrText,
                    catalogueProducts = catalogueProducts,
                    photoPath = imagePath,
                )
                if (aiOutcome.isSuccess) {
                    val cloudDraft = aiOutcome.getOrThrow()
                    val arithmetic = ReceiptArithmeticValidator.validate(cloudDraft)
                    return@withContext if (arithmetic.hasDiscrepancies) {
                        cloudDraft.copy(warnings = (cloudDraft.warnings + arithmetic.warnings).distinct())
                    } else {
                        cloudDraft
                    }
                }
            }
        }

        if (!isOnline) {
            localDraft.copy(
                warnings = (listOf("Offline mode: extracted on-device. Verify items and totals before saving.") + localDraft.warnings).distinct(),
            )
        } else {
            localDraft
        }
    }

    private suspend fun runLocalSpatialOcr(imagePath: String): LocalOcrCapture {
        val bitmap = ImageQualityMath.decodeUprightBitmap(File(imagePath), 1280)
            ?: return LocalOcrCapture("", emptyList())
        val enhanced = runCatching { ReceiptImageEnhancer.enhanceForOcr(bitmap) }.getOrElse { bitmap }
        return try {
            val inputImage = InputImage.fromBitmap(enhanced, 0)
            suspendCancellableCoroutine { cont ->
                textRecognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        val spatial = visionText.textBlocks.flatMap { block ->
                            block.lines.mapNotNull { line ->
                                val box = line.boundingBox ?: return@mapNotNull null
                                OcrSpatialLine(
                                    text = line.text,
                                    left = box.left,
                                    top = box.top,
                                    right = box.right,
                                    bottom = box.bottom,
                                )
                            }
                        }
                        if (cont.isActive) {
                            cont.resume(LocalOcrCapture(visionText.text.orEmpty(), spatial))
                        }
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume(LocalOcrCapture("", emptyList()))
                    }
            }
        } finally {
            if (enhanced !== bitmap && !enhanced.isRecycled) enhanced.recycle()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun callExtractReceiptEdge(
        imageBase64: String,
        ocrText: String,
        catalogueProducts: List<ProductIdentity>,
        photoPath: String,
    ): Result<PurchaseReceiptDraft> {
        val activeCatalog = catalogueProducts.filter { !it.isArchived && it.id > 0L }.take(150)
        val payloadJson = buildRequestPayload(
            imageBase64 = imageBase64,
            ocrText = ocrText,
            catalogueProducts = activeCatalog,
        )

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                if (anonKey.isNotBlank()) {
                    setRequestProperty("apikey", anonKey)
                    setRequestProperty("Authorization", "Bearer ${SupabaseConfig.bearerToken(anonKey)}")
                }
            }

            conn.outputStream.use { out ->
                out.write(payloadJson.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                val msg = runCatching { JSONObject(body).optString("error") }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "HTTP $code"
                return Result.failure(IOException(msg))
            }

            Result.success(
                parseEdgeResponseJson(
                    rawJson = body,
                    rawOcrText = ocrText,
                    catalogueProducts = activeCatalog,
                    photoPath = photoPath,
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    private fun encodeReceiptJpegBase64(imagePath: String): String? {
        val file = File(imagePath)
        if (!file.exists()) return null
        val bitmap = ImageQualityMath.decodeUprightBitmap(file, 1280) ?: return null
        return try {
            val (w, h) = ImageQualityMath.computeScaledDimensions(
                bitmap.width,
                bitmap.height,
                1280,
            )
            val scaled = if (w != bitmap.width || h != bitmap.height) {
                Bitmap.createScaledBitmap(bitmap, w, h, true)
            } else {
                bitmap
            }
            val bytes = ByteArrayOutputStream().use { baos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                baos.toByteArray()
            }
            if (scaled != bitmap) {
                scaled.recycle()
            }
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    companion object {
        internal fun buildRequestPayload(
            imageBase64: String,
            ocrText: String,
            catalogueProducts: List<ProductIdentity>,
        ): String {
            val catalogArr = JSONArray()
            for (item in catalogueProducts.take(150)) {
                catalogArr.put(
                    JSONObject().apply {
                        put("id", item.id)
                        put("name", item.name)
                        put("category", item.category)
                        put("buy_price", item.buyPrice)
                        put("sell_price", item.sellPrice)
                    }
                )
            }
            return JSONObject().apply {
                put("image_base64", imageBase64)
                put("mime_type", "image/jpeg")
                put("ocr_text", ocrText)
                put("catalog_context", catalogArr)
            }.toString()
        }

        internal fun parseEdgeResponseJson(
            rawJson: String,
            rawOcrText: String,
            catalogueProducts: List<ProductIdentity>,
            photoPath: String?,
        ): PurchaseReceiptDraft {
            val root = JSONObject(rawJson)
            val catalogById = catalogueProducts
                .filter { !it.isArchived && it.id > 0L }
                .associateBy { it.id }

            val warnings = mutableListOf<String>()
            val warnArr = root.optJSONArray("warnings") ?: JSONArray()
            for (i in 0 until warnArr.length()) {
                val w = warnArr.optString(i).trim()
                if (w.isNotBlank() && w != "null") warnings += w
            }

            val itemsArr = root.optJSONArray("items") ?: JSONArray()
            val lines = mutableListOf<ExtractedReceiptLineDraft>()
            for (i in 0 until itemsArr.length()) {
                val obj = itemsArr.optJSONObject(i) ?: continue
                val rawName = obj.optString("raw_name", "").trim()
                if (rawName.isBlank()) continue
                val qty = obj.optInt("qty", 1).coerceAtLeast(1)
                val unitCost = obj.optDouble("unit_cost", 0.0).let { if (it.isNaN() || it < 0.0) 0.0 else it }
                val lineTotal = obj.optDouble("line_total", 0.0).let { if (it.isNaN() || it < 0.0) 0.0 else it }
                val itemCategory = PurchaseItemCategory.fromCode(obj.optString("suggested_item_type"))
                val candidateId = if (obj.has("matched_product_id") && !obj.isNull("matched_product_id")) {
                    obj.optLong("matched_product_id", -1L).takeIf { it > 0L }
                } else {
                    null
                }
                val validMatched = candidateId?.let { catalogById[it] }
                val confidenceNote = obj.optString("confidence_note")
                    .takeIf { it.isNotBlank() && it != "null" }

                lines += ExtractedReceiptLineDraft(
                    rawName = rawName,
                    matchedProductId = validMatched?.id,
                    matchedProductName = validMatched?.name,
                    itemCategory = itemCategory,
                    quantity = qty,
                    unitCost = unitCost,
                    lineTotal = lineTotal,
                    registerAsNewProduct = false,
                    newProductCategory = validMatched?.category ?: "General",
                    confidenceNote = confidenceNote,
                )
            }

            if (warnings.none { it.contains("confirm", ignoreCase = true) }) {
                warnings += "Review extracted supplier, quantities, costs, and item types before confirming."
            }

            val supplier = root.optString("supplier_name", "").takeIf { it != "null" }.orEmpty().trim()
            val invoiceRef = root.optString("invoice_ref", "").takeIf { it != "null" }.orEmpty().trim()
            val receiptDate = root.optString("receipt_date", "").takeIf { it != "null" }.orEmpty().trim()
            val totalAmount = root.optDouble("total_amount", 0.0).let { if (it.isNaN() || it < 0.0) 0.0 else it }
            val subtotalAmount = root.optDouble("subtotal_amount", 0.0).let { if (it.isNaN() || it < 0.0) 0.0 else it }
            val discountAmount = root.optDouble("discount_amount", 0.0).let { if (it.isNaN() || it < 0.0) 0.0 else it }
            val taxAmount = root.optDouble("tax_amount", 0.0).let { if (it.isNaN() || it < 0.0) 0.0 else it }
            val paymentMethod = root.optString("payment_method", "").takeIf { it != "null" }.orEmpty().trim()
            val paymentReference = root.optString("payment_reference", "").takeIf { it != "null" }.orEmpty().trim()

            return PurchaseReceiptDraft(
                supplierName = supplier,
                invoiceRef = invoiceRef,
                receiptDateText = receiptDate,
                extractedTotal = totalAmount,
                subtotalAmount = subtotalAmount,
                discountAmount = discountAmount,
                taxAmount = taxAmount,
                paymentMethod = paymentMethod,
                paymentReference = paymentReference,
                lines = lines,
                rawOcrText = rawOcrText,
                photoPath = photoPath,
                extractionSource = ReceiptExtractionSource.CLOUD_AI_AND_OCR,
                warnings = warnings.distinct(),
                isHumanConfirmed = false,
            )
        }
    }
}
