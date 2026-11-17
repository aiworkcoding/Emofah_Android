package com.emofah.agencies.financial

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.emofah.agencies.data.local.EmofahDatabase
import com.emofah.agencies.data.sync.SyncScheduler
import com.emofah.agencies.sales.DeviceIdentityProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Stage 5.2 – 5.5 — Consent-gated Android SMS BroadcastReceiver for M-Pesa notifications.
 * Exits immediately without inspecting messages unless the user has granted explicit monitoring consent.
 * Queues captured financial events for reliable WorkManager sync to Supabase.
 */
class MpesaSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val prefs = FinancialMonitoringPrefs(context.applicationContext)
        if (!prefs.isConsentGranted()) {
            // Strict compliance: never inspect SMS contents without explicit opt-in consent.
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val groupedBySender = linkedMapOf<String, StringBuilder>()
        for (sms in messages) {
            val sender = sms.displayOriginatingAddress ?: sms.originatingAddress ?: ""
            val bodyPart = sms.messageBody ?: ""
            if (bodyPart.isNotEmpty()) {
                groupedBySender.getOrPut(sender) { StringBuilder() }.append(bodyPart)
            }
        }
        if (groupedBySender.isEmpty()) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = MpesaMonitoringRepository(
                    database = EmofahDatabase.getInstance(appContext),
                    monitoringPrefs = prefs,
                    onOfflineEventQueued = {
                        SyncScheduler.enqueueImmediateSync(appContext)
                    },
                )
                val identity = DeviceIdentityProvider(appContext)
                for ((sender, bodyBuilder) in groupedBySender) {
                    repo.processIncomingSms(
                        sender = sender,
                        body = bodyBuilder.toString(),
                        actorId = identity.getActorId(),
                        deviceId = identity.getDeviceId(),
                        requireConsentCheck = true,
                        allowManualPasteOverride = false,
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
