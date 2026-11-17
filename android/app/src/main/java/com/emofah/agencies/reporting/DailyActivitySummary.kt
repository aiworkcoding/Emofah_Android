package com.emofah.agencies.reporting

import com.emofah.agencies.data.local.dao.LocalSaleWithItems
import com.emofah.agencies.data.local.entities.PendingFinancialEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import kotlin.math.roundToInt

data class DailyActivitySummary(
    val startOfTodayEpochMs: Long,
    val totalActiveSalesCount: Int,
    val totalActiveSalesAmount: Double,
    val reversedSalesCount: Int,
    val salesPaymentBreakdown: Map<String, Double>,
    val serviceOperationsCount: Int,
    val serviceOperationsRevenue: Double,
    val mpesaInflowTotal: Double,
    val mpesaOutflowTotal: Double,
    val pendingSyncCount: Int,
    val conflictCount: Int,
) {
    val netOperationalRevenue: Double
        get() = totalActiveSalesAmount + serviceOperationsRevenue
}

/**
 * Stage 8.1 — Pure JVM Daily Operational Summary Calculator.
 * Aggregates today's operational activity (active sales, payment method breakdown,
 * service operations, M-Pesa inflows/outflows, and sync queue health) strictly
 * separated from Desktop-managed accounting profit/classification.
 */
object DailyActivitySummaryCalculator {

    fun compute(
        startOfTodayEpochMs: Long,
        salesWithItems: List<LocalSaleWithItems>,
        financialEvents: List<PendingFinancialEventEntity>,
        pendingSyncCount: Int,
        conflictCount: Int,
    ): DailyActivitySummary {
        val todaySales = salesWithItems.filter { it.sale.createdAtEpochMs >= startOfTodayEpochMs }
        val activeSales = todaySales.filterNot { it.sale.isReversed }
        val reversedCount = todaySales.size - activeSales.size

        val totalActiveAmount = activeSales.sumOf { it.sale.totalAmount }
        val paymentBreakdown = mutableMapOf<String, Double>()
        activeSales.forEach { record ->
            val methodLabel = record.sale.paymentMethod.ifBlank { "Cash" }
            val current = paymentBreakdown[methodLabel] ?: 0.0
            paymentBreakdown[methodLabel] = current + record.sale.totalAmount
        }

        val todayFinancials = financialEvents.filter { it.eventTimestampEpochMs >= startOfTodayEpochMs }
        val serviceEvents = todayFinancials.filter { it.source.equals("ANDROID_SERVICE", ignoreCase = true) }
        val serviceCount = serviceEvents.size
        val serviceRevenue = serviceEvents.sumOf { it.amount }

        var mpesaInflow = 0.0
        var mpesaOutflow = 0.0
        todayFinancials.forEach { evt ->
            if (evt.source.equals("MPESA_SMS", ignoreCase = true) || evt.source.equals("MANUAL", ignoreCase = true)) {
                when (evt.direction.uppercase()) {
                    "INFLOW" -> mpesaInflow += evt.amount
                    "OUTFLOW" -> mpesaOutflow += evt.amount
                }
            }
        }

        return DailyActivitySummary(
            startOfTodayEpochMs = startOfTodayEpochMs,
            totalActiveSalesCount = activeSales.size,
            totalActiveSalesAmount = totalActiveAmount,
            reversedSalesCount = reversedCount,
            salesPaymentBreakdown = paymentBreakdown,
            serviceOperationsCount = serviceCount,
            serviceOperationsRevenue = serviceRevenue,
            mpesaInflowTotal = mpesaInflow,
            mpesaOutflowTotal = mpesaOutflow,
            pendingSyncCount = pendingSyncCount,
            conflictCount = conflictCount,
        )
    }

    fun formatSummaryReport(summary: DailyActivitySummary): String {
        return buildString {
            appendLine("EMOFAH · DAILY OPERATIONAL SUMMARY")
            appendLine("----------------------------------------")
            appendLine("Active Sales: ${summary.totalActiveSalesCount} · Ksh ${summary.totalActiveSalesAmount.roundToInt()}")
            if (summary.reversedSalesCount > 0) {
                appendLine("Reversed Sales: ${summary.reversedSalesCount}")
            }
            if (summary.salesPaymentBreakdown.isNotEmpty()) {
                val breakdownStr = summary.salesPaymentBreakdown.entries.joinToString(" | ") {
                    "${it.key}: Ksh ${it.value.roundToInt()}"
                }
                appendLine("Payment Breakdown: $breakdownStr")
            }
            appendLine("Services (${summary.serviceOperationsCount}): Ksh ${summary.serviceOperationsRevenue.roundToInt()}")
            appendLine("Net Operational Revenue: Ksh ${summary.netOperationalRevenue.roundToInt()}")
            if (summary.mpesaInflowTotal > 0.0 || summary.mpesaOutflowTotal > 0.0) {
                appendLine("M-Pesa Inflow: Ksh ${summary.mpesaInflowTotal.roundToInt()} | Outflow: Ksh ${summary.mpesaOutflowTotal.roundToInt()}")
            }
            appendLine("Sync Status: ${summary.pendingSyncCount} pending · ${summary.conflictCount} conflict(s)")
            append("Note: Accounting classifications managed strictly by Desktop.")
        }
    }
}
