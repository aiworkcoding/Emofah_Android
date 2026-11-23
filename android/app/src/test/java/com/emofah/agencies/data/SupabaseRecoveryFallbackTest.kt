package com.emofah.agencies.data

import com.emofah.agencies.data.catalog.ProductRegistrationRpcOutcome
import com.emofah.agencies.data.catalog.SupabaseProductRegistrationClient
import com.emofah.agencies.financial.FinancialEventRpcOutcome
import com.emofah.agencies.financial.SupabaseFinancialEventClient
import com.emofah.agencies.orders.OrderStatusRpcOutcome
import com.emofah.agencies.orders.SupabaseOrderStatusClient
import com.emofah.agencies.purchases.PurchaseRpcOutcome
import com.emofah.agencies.purchases.SupabasePurchaseClient
import com.emofah.agencies.recognition.RecognitionCorrectionRpcOutcome
import com.emofah.agencies.recognition.SupabaseRecognitionCorrectionClient
import com.emofah.agencies.sales.AndroidSaleRpcRequest
import com.emofah.agencies.sales.SaleRpcOutcome
import com.emofah.agencies.sales.SupabaseSaleReversalClient
import com.emofah.agencies.sales.SupabaseSaleRpcClient
import com.emofah.agencies.stock.AndroidStockAdjustmentRpcRequest
import com.emofah.agencies.stock.AndroidStockReceiptRpcRequest
import com.emofah.agencies.stock.StockAdjustmentRpcOutcome
import com.emofah.agencies.stock.StockReceiptRpcOutcome
import com.emofah.agencies.stock.SupabaseStockAdjustmentClient
import com.emofah.agencies.stock.SupabaseStockReceivingClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseRecoveryFallbackTest {

    @Test
    fun clientsWithMissingUrlOrKeyReportBuildConfigNotLocalProperties() = runBlocking {
        val saleClient = SupabaseSaleRpcClient(supabaseUrl = "", anonKey = "")
        val saleOutcome = saleClient.submitSale(
            AndroidSaleRpcRequest(
                productId = 1L,
                quantity = 1,
                totalAmount = 100.0,
                clientSaleId = "s-1",
                idempotencyKey = "k-1",
                paymentMethod = "CASH",
                actorId = "staff",
                deviceId = "dev",
                saleDateIso = "2026-11-21T00:00:00Z",
            )
        )
        assertTrue(saleOutcome is SaleRpcOutcome.NetworkOrTransientError)
        assertFalse((saleOutcome as SaleRpcOutcome.NetworkOrTransientError).message.contains("local.properties"))

        val receiveClient = SupabaseStockReceivingClient(supabaseUrl = "", anonKey = "")
        val receiveOutcome = receiveClient.submitStockReceipt(
            AndroidStockReceiptRpcRequest(
                productId = 1L,
                quantity = 1,
                clientReceiptId = "r-1",
                idempotencyKey = "kr-1",
                unitCost = 50.0,
                supplier = null,
                invoiceRef = null,
                invoicePhotoUrl = null,
                actorId = "staff",
                deviceId = "dev",
                receivedAtIso = "2026-11-21T00:00:00Z",
            )
        )
        assertTrue(receiveOutcome is StockReceiptRpcOutcome.NetworkOrTransientError)
        assertFalse((receiveOutcome as StockReceiptRpcOutcome.NetworkOrTransientError).message.contains("local.properties"))

        val adjClient = SupabaseStockAdjustmentClient(supabaseUrl = "", anonKey = "")
        val adjOutcome = adjClient.submitStockAdjustment(
            AndroidStockAdjustmentRpcRequest(
                productId = 1L,
                deltaQty = -1,
                reasonCode = "DAMAGED",
                notes = "Test damaged",
                clientAdjustmentId = "a-1",
                idempotencyKey = "ka-1",
                actorId = "staff",
                actorRole = "CASHIER",
                deviceId = "dev",
                adjustedAtIso = "2026-11-21T00:00:00Z",
            )
        )
        assertTrue(adjOutcome is StockAdjustmentRpcOutcome.NetworkOrTransientError)
        assertFalse((adjOutcome as StockAdjustmentRpcOutcome.NetworkOrTransientError).message.contains("local.properties"))
    }

    @Test
    fun parseRpcResponsesPreserveAuthRequiredAndMissingRpcDetection() {
        val sale404 = SupabaseSaleRpcClient.parseRpcResponse(
            httpStatus = 404,
            responseBody = """{"code":"PGRST202","message":"Could not find function public.record_android_sale"}""",
        )
        assertTrue(sale404 is SaleRpcOutcome.RpcMissing)

        val sale401 = SupabaseSaleRpcClient.parseRpcResponse(
            httpStatus = 401,
            responseBody = """{"message":"JWT expired"}""",
        )
        assertTrue(sale401 is SaleRpcOutcome.AuthRequired)

        val reg404 = SupabaseProductRegistrationClient.parseRpcResponse(
            httpStatus = 404,
            responseBody = """{"code":"PGRST202"}""",
        )
        assertTrue(reg404 is ProductRegistrationRpcOutcome.RpcMissing)

        val order404 = SupabaseOrderStatusClient.parseRpcResponse(404, """{"code":"PGRST202"}""")
        assertTrue(order404 is OrderStatusRpcOutcome.RpcMissing)

        val purchase404 = SupabasePurchaseClient.parseRpcResponse(404, """{"code":"PGRST202"}""")
        assertTrue(purchase404 is PurchaseRpcOutcome.RpcMissing)

        val fin403 = SupabaseFinancialEventClient.parseRpcResponse(403, """{"message":"RLS denied"}""")
        assertTrue(fin403 is FinancialEventRpcOutcome.AuthRequired)

        val corr404 = SupabaseRecognitionCorrectionClient.parseRpcResponse(404, """{"code":"PGRST202"}""")
        assertTrue(corr404 is RecognitionCorrectionRpcOutcome.RpcMissing)

        val rev409 = SupabaseSaleReversalClient.parseRpcResponse(409, """{"code":"23503","message":"FK error"}""")
        assertTrue(rev409 is com.emofah.agencies.sales.SaleReversalRpcOutcome.Rejected)
    }

    @Test
    fun classifyProductsFetchResultHandlesNullAndHttpFailuresWithoutLosingValidData() {
        val nullOutcome = com.emofah.agencies.data.catalog.SupabaseProductRepository.classifyFetchResult(null)
        assertTrue(nullOutcome is com.emofah.agencies.data.catalog.SupabaseProductRepository.Companion.ProductsFetchOutcome.Fallback)
        assertTrue((nullOutcome as com.emofah.agencies.data.catalog.SupabaseProductRepository.Companion.ProductsFetchOutcome.Fallback).errorMessage.isNotBlank())

        val http400Outcome = com.emofah.agencies.data.catalog.SupabaseProductRepository.classifyFetchResult(
            com.emofah.agencies.data.catalog.SupabaseProductRepository.ProductsFetchResult(400, null)
        )
        assertTrue(http400Outcome is com.emofah.agencies.data.catalog.SupabaseProductRepository.Companion.ProductsFetchOutcome.Fallback)
        assertTrue((http400Outcome as com.emofah.agencies.data.catalog.SupabaseProductRepository.Companion.ProductsFetchOutcome.Fallback).errorMessage.contains("400"))

        val nullBodyOutcome = com.emofah.agencies.data.catalog.SupabaseProductRepository.classifyFetchResult(
            com.emofah.agencies.data.catalog.SupabaseProductRepository.ProductsFetchResult(200, null)
        )
        assertTrue(nullBodyOutcome is com.emofah.agencies.data.catalog.SupabaseProductRepository.Companion.ProductsFetchOutcome.Fallback)

        val validJson = """[{"id":10,"name":"A4 Exercise Book 200 pgs","qty":12,"sell_price":120.0,"buy_price":80.0,"category":"Books"}]"""
        val okOutcome = com.emofah.agencies.data.catalog.SupabaseProductRepository.classifyFetchResult(
            com.emofah.agencies.data.catalog.SupabaseProductRepository.ProductsFetchResult(200, validJson)
        )
        assertTrue(okOutcome is com.emofah.agencies.data.catalog.SupabaseProductRepository.Companion.ProductsFetchOutcome.Success)
        val parsed = com.emofah.agencies.data.catalog.SupabaseProductRepository.parseProductsJson(
            (okOutcome as com.emofah.agencies.data.catalog.SupabaseProductRepository.Companion.ProductsFetchOutcome.Success).body
        )
        org.junit.Assert.assertEquals(1, parsed.size)
        org.junit.Assert.assertEquals(10L, parsed[0].id)
    }
}
