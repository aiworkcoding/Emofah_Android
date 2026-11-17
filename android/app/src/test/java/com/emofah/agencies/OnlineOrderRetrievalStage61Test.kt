package com.emofah.agencies

import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.orders.StoreOrderParser
import com.emofah.agencies.orders.StoreOrderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineOrderRetrievalStage61Test {

    @Test
    fun emptyRemoteOrdersArrayReturnsEmptyListWithoutFabricatedData() {
        val parsed = StoreOrderParser.parseOrdersJson("[]")
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun parsesLiveOrdersColumnsAndEnrichesProductNamesFromCatalogue() {
        val catalogById = mapOf(
            42L to ProductIdentity(
                id = 42L,
                name = "Kasuku A4 Exercise Book 200 pgs",
                baseName = "Kasuku A4 Exercise Book",
                variantLabel = "200 pgs",
                brandHint = "Kasuku",
                category = "Books",
                sellPrice = 120.0,
                discountPrice = 0.0,
                buyPrice = 85.0,
                stockQty = 30,
                imageUrl = null,
                description = null,
                isFeatured = false,
                createdAt = null,
            )
        )

        val rawJson = """
            [
              {
                "id": 108,
                "created_at": "2026-11-16T08:15:00Z",
                "status": "pending",
                "total_price": 390,
                "items": [
                  { "product_id": 42, "qty": 2, "price": 120 },
                  { "name": "Bic Ball Pen Blue", "quantity": 5, "unit_price": 30, "line_total": 150 }
                ]
              },
              {
                "id": 107,
                "created_at": "2026-11-15T16:00:00Z",
                "status": "completed",
                "total_price": 500,
                "items": "{\"customer_name\":\"Amina Wanjiku\",\"customer_phone\":\"0712345678\",\"items\":[{\"name\":\"A4 Paper Ream\",\"qty\":1,\"price\":500}]}"
              }
            ]
        """.trimIndent()

        val orders = StoreOrderParser.parseOrdersJson(rawJson, catalogById)
        assertEquals(2, orders.size)

        val first = orders[0]
        assertEquals("108", first.id)
        assertEquals("#108", first.displayNumber)
        assertEquals(StoreOrderStatus.PENDING, first.status)
        assertEquals(390.0, first.totalPrice, 0.01)
        assertNull(first.customerName)
        assertEquals(2, first.items.size)
        assertEquals("Kasuku A4 Exercise Book 200 pgs", first.items[0].productName)
        assertEquals(240.0, first.items[0].lineTotal, 0.01)
        assertEquals(7, first.totalUnits)

        val second = orders[1]
        assertEquals(StoreOrderStatus.FULFILLED, second.status)
        assertEquals("Amina Wanjiku", second.customerName)
        assertEquals("0712345678", second.customerPhone)
        assertEquals("A4 Paper Ream", second.items[0].productName)
    }
}
