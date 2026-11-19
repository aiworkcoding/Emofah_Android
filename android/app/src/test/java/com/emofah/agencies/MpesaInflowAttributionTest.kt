package com.emofah.agencies

import com.emofah.agencies.data.local.entities.PendingFinancialEventEntity
import com.emofah.agencies.financial.MpesaInflowAttributionPolicy
import com.emofah.agencies.financial.MpesaInflowLinkRecord
import com.emofah.agencies.financial.MpesaInflowLinkTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpesaInflowAttributionTest {

    private fun event(
        id: String,
        source: String = "MPESA_SMS",
        direction: String = "INFLOW",
        amount: Double = 500.0,
        ref: String? = "TK12ABC345",
        ts: Long = 1000L,
    ) = PendingFinancialEventEntity(
        eventId = id,
        source = source,
        amount = amount,
        transactionReference = ref,
        counterparty = "JANE WANJIKU",
        direction = direction,
        rawEvidence = null,
        parsedJson = null,
        eventTimestampEpochMs = ts,
    )

    @Test
    fun detectsInflowOnlyFromMpesaSmsOrPaste() {
        assertTrue(MpesaInflowAttributionPolicy.isMpesaInflowEvent(event("e1", source = "MPESA_SMS", direction = "INFLOW")))
        assertTrue(MpesaInflowAttributionPolicy.isMpesaInflowEvent(event("e2", source = "MPESA_PASTE", direction = "inflow")))
        assertFalse(MpesaInflowAttributionPolicy.isMpesaInflowEvent(event("e3", source = "MPESA_SMS", direction = "OUTFLOW")))
        assertFalse(MpesaInflowAttributionPolicy.isMpesaInflowEvent(event("e4", source = "ANDROID_SERVICE", direction = "INFLOW")))
        assertFalse(MpesaInflowAttributionPolicy.isMpesaInflowEvent(event("e5", source = "MANUAL", direction = "INFLOW")))
    }

    @Test
    fun preventsDuplicateLinkingByEventIdOrCaseInsensitiveReference() {
        val smsInflow = event("evt-1", ref = "tk99abc111", ts = 2000L)
        val pastedDuplicate = event("evt-2", source = "MPESA_PASTE", ref = " TK99ABC111 ", ts = 2500L)
        val unlinkedNewer = event("evt-3", source = "MPESA_SMS", ref = "TL00XYZ999", ts = 3000L)

        val links = mapOf(
            "evt-1" to MpesaInflowLinkRecord(
                eventId = "evt-1",
                transactionReference = "TK99ABC111",
                target = MpesaInflowLinkTarget.SALE,
                linkedRecordId = "sale-42",
                summary = "A4 Ream x1",
                linkedAtEpochMs = 2100L,
            )
        )

        assertNotNull(MpesaInflowAttributionPolicy.findExistingLink(smsInflow, links))
        assertNotNull(MpesaInflowAttributionPolicy.findExistingLink(pastedDuplicate, links))

        val latestUnlinked = MpesaInflowAttributionPolicy.findLatestUnlinkedInflow(
            events = listOf(smsInflow, pastedDuplicate, unlinkedNewer),
            linksByEventId = links,
        )
        assertNotNull(latestUnlinked)
        assertEquals("evt-3", latestUnlinked!!.eventId)

        val allLinked = links + ("evt-3" to MpesaInflowLinkRecord(
            eventId = "evt-3",
            transactionReference = "TL00XYZ999",
            target = MpesaInflowLinkTarget.SERVICE,
            linkedRecordId = "svc-9",
            summary = "Printing · Ksh 500",
            linkedAtEpochMs = 3100L,
        ))
        assertNull(
            MpesaInflowAttributionPolicy.findLatestUnlinkedInflow(
                events = listOf(smsInflow, pastedDuplicate, unlinkedNewer),
                linksByEventId = allLinked,
            )
        )
    }

    @Test
    fun roundTripsJsonPersistenceCleanly() {
        val original = listOf(
            MpesaInflowLinkRecord(
                eventId = "evt-a",
                transactionReference = "UA11BB22CC",
                target = MpesaInflowLinkTarget.SALE,
                linkedRecordId = "105",
                summary = "Book x2",
                linkedAtEpochMs = 5000L,
            ),
            MpesaInflowLinkRecord(
                eventId = "evt-b",
                transactionReference = null,
                target = MpesaInflowLinkTarget.DISMISSED,
                linkedRecordId = "dismissed",
                summary = "Not a shop sale or service",
                linkedAtEpochMs = 6000L,
            ),
        )

        val json = MpesaInflowAttributionPolicy.encodeLinksToJson(original)
        val decoded = MpesaInflowAttributionPolicy.decodeLinksFromJson(json)
        assertEquals(2, decoded.size)
        assertEquals(MpesaInflowLinkTarget.SALE, decoded["evt-a"]?.target)
        assertEquals("UA11BB22CC", decoded["evt-a"]?.transactionReference)
        assertEquals(MpesaInflowLinkTarget.DISMISSED, decoded["evt-b"]?.target)
        assertNull(decoded["evt-b"]?.transactionReference)
    }
}
