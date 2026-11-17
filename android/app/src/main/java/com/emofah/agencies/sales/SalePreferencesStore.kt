package com.emofah.agencies.sales

import android.content.Context

/**
 * Stage 2.3 (Action 2.3.1):
 * Persists operator sale-speed preferences (last selected payment method and
 * optional rapid return-to-scan mode) across sessions so ordinary sales never
 * repeat unnecessary choices.
 */
class SalePreferencesStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readLastPaymentMethod(): PaymentMethod {
        val raw = prefs.getString(KEY_PAYMENT_METHOD, PaymentMethod.CASH.name)
        return PaymentMethod.entries.firstOrNull { it.name == raw } ?: PaymentMethod.CASH
    }

    fun saveLastPaymentMethod(method: PaymentMethod) {
        prefs.edit().putString(KEY_PAYMENT_METHOD, method.name).apply()
    }

    fun readAutoReturnToScan(): Boolean {
        return prefs.getBoolean(KEY_AUTO_RETURN_SCAN, false)
    }

    fun saveAutoReturnToScan(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RETURN_SCAN, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "emofah_sale_prefs"
        private const val KEY_PAYMENT_METHOD = "last_payment_method"
        private const val KEY_AUTO_RETURN_SCAN = "auto_return_to_scan"
    }
}
