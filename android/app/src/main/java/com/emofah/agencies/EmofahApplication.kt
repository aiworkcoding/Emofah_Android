package com.emofah.agencies

import android.app.Application
import com.emofah.agencies.auth.AndroidKeyStoreTokenStorage
import com.emofah.agencies.auth.AuthSessionRepository
import com.emofah.agencies.auth.AuthSessionStore
import com.emofah.agencies.data.local.EmofahDatabase
import com.emofah.agencies.data.sync.SyncScheduler
import com.emofah.agencies.notifications.EmofahNotificationManager
import com.emofah.agencies.sales.AndroidConnectivityChecker
import com.emofah.agencies.sales.DeviceIdentityProvider

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EmofahApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: EmofahDatabase by lazy { EmofahDatabase.getInstance(this) }
    val authSessionRepository: AuthSessionRepository by lazy {
        AuthSessionRepository(
            sessionStore = AuthSessionStore(AndroidKeyStoreTokenStorage(this)),
            database = database,
            identityProvider = DeviceIdentityProvider(this),
            connectivityChecker = AndroidConnectivityChecker(this),
            onAuthenticationRestored = {
                SyncScheduler.onAuthenticationRestored(this, database)
            },
        )
    }

    override fun onCreate() {
        super.onCreate()
        EmofahNotificationManager.ensureChannelsCreated(this)
        appScope.launch {
            authSessionRepository.registerAsGlobalTokenProvider(refreshImmediately = true)
            SyncScheduler.ensurePeriodicSyncScheduled(this@EmofahApplication)
        }
    }
}
