package com.emofah.agencies.auth

import com.emofah.agencies.data.SupabaseConfig
import com.emofah.agencies.data.local.EmofahDatabase
import com.emofah.agencies.data.local.entities.LocalAuditEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.sales.ConnectivityChecker
import com.emofah.agencies.sales.DeviceIdentityProvider
import com.emofah.agencies.sales.StaffRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

/**
 * Stage 9.1 — Manages the Supabase Auth session lifecycle:
 * - Secure encrypted token persistence + offline cached session continuity
 * - Proactive refresh before expiry & single-flight refresh mutex for rotated refresh tokens
 * - Automatic synchronization of active JWT bearer token, DeviceIdentityProvider, and sync queue recovery
 */
class AuthSessionRepository(
    private val sessionStore: AuthSessionStore,
    private val database: EmofahDatabase,
    private val identityProvider: DeviceIdentityProvider,
    private val connectivityChecker: ConnectivityChecker,
    private val authGateway: SupabaseAuthGateway = SupabaseAuthClient(),
    private val clockEpochMs: () -> Long = { System.currentTimeMillis() },
    private val onAuthenticationRestored: suspend () -> Unit = {},
) {
    private val refreshMutex = Mutex()
    private val _uiState = MutableStateFlow(evaluateCurrentUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun registerAsGlobalTokenProvider() {
        SupabaseConfig.registerAccessTokenProvider { currentBearerAccessTokenOrNull() }
        refreshUiStateFromStore()
    }

    fun currentBearerAccessTokenOrNull(): String? {
        val session = sessionStore.readSession() ?: return null
        if (sessionStore.isSessionMarkedExpired()) return null
        return session.accessToken.takeIf { it.isNotBlank() }
    }

    fun refreshUiStateFromStore(): AuthUiState {
        val state = evaluateCurrentUiState()
        _uiState.value = state
        syncIdentityFromState(state)
        return state
    }

    suspend fun signIn(emailInput: String, passwordInput: String): AuthActionOutcome {
        val email = emailInput.trim()
        if (email.isBlank() || !email.contains("@")) {
            return AuthActionOutcome.Rejected("Enter a valid staff email address.")
        }
        if (passwordInput.isEmpty()) {
            return AuthActionOutcome.Rejected("Enter your password.")
        }

        _uiState.value = _uiState.value.copy(isBusy = true, lastError = null)
        return when (val rpc = authGateway.signInWithPassword(email, passwordInput)) {
            is AuthRpcResult.Success -> {
                when (val reconciled = reconcileServerProfile(rpc.session)) {
                    is ServerProfileReconciliation.Deactivated -> {
                        sessionStore.clearSession()
                        identityProvider.setAuthenticatedActorId(null)
                        identityProvider.setStaffRole(StaffRole.CASHIER)
                        _uiState.value = evaluateCurrentUiState().copy(
                            isBusy = false,
                            lastError = reconciled.message,
                        )
                        appendAuthAudit(
                            operation = "AUTH_DEACTIVATED_REJECTED",
                            actorId = AuthSessionPolicy.buildActorId(
                                rpc.session.userId,
                                rpc.session.email,
                                rpc.session.displayName,
                            ),
                            reason = reconciled.message,
                            afterJson = JSONObject().apply {
                                put("user_id", rpc.session.userId)
                                put("email", rpc.session.email)
                            }.toString(),
                        )
                        AuthActionOutcome.Rejected(reconciled.message, sessionExpired = false)
                    }

                    is ServerProfileReconciliation.Active -> {
                        val effectiveSession = reconciled.session
                        sessionStore.saveSession(effectiveSession)
                        val nextState = evaluateCurrentUiState()
                        _uiState.value = nextState
                        syncIdentityFromState(nextState)
                        appendAuthAudit(
                            operation = "AUTH_SIGN_IN",
                            actorId = nextState.activeActorId,
                            reason = "Signed in as ${effectiveSession.email} (${effectiveSession.staffRole.name})",
                            afterJson = JSONObject().apply {
                                put("user_id", effectiveSession.userId)
                                put("email", effectiveSession.email)
                                put("staff_role", effectiveSession.staffRole.name)
                            }.toString(),
                        )
                        runCatching { onAuthenticationRestored() }
                        AuthActionOutcome.SignedIn(effectiveSession)
                    }
                }
            }

            is AuthRpcResult.AuthRejected -> {
                _uiState.value = _uiState.value.copy(
                    isBusy = false,
                    lastError = rpc.message,
                )
                AuthActionOutcome.Rejected(rpc.message, sessionExpired = false)
            }

            is AuthRpcResult.TransientError -> {
                _uiState.value = _uiState.value.copy(
                    isBusy = false,
                    lastError = rpc.message,
                )
                AuthActionOutcome.TransientFailure(rpc.message, keptOfflineSession = false)
            }
        }
    }

    suspend fun ensureFreshSessionIfOnline(forceRefresh: Boolean = false): AuthActionOutcome? {
        return refreshMutex.withLock {
            val stored = sessionStore.readSession() ?: run {
                _uiState.value = evaluateCurrentUiState()
                return@withLock null
            }
            val now = clockEpochMs()
            val isOnline = connectivityChecker.isOnline()
            val isMarkedExpired = sessionStore.isSessionMarkedExpired()

            if (!isOnline) {
                val offlineState = evaluateCurrentUiState(now)
                _uiState.value = offlineState
                syncIdentityFromState(offlineState)
                return@withLock null
            }

            if (isMarkedExpired) {
                val expiredState = evaluateCurrentUiState(now)
                _uiState.value = expiredState
                return@withLock AuthActionOutcome.Rejected(
                    message = expiredState.lastError ?: "Session expired. Please sign in again.",
                    sessionExpired = true,
                )
            }

            val shouldRefresh = forceRefresh || AuthSessionPolicy.shouldProactivelyRefresh(
                session = stored,
                isOnline = true,
                hasSessionExpiredRejection = false,
                nowEpochMs = now,
            )
            if (!shouldRefresh) {
                val current = evaluateCurrentUiState(now)
                _uiState.value = current
                syncIdentityFromState(current)
                return@withLock null
            }

            when (val rpc = authGateway.refreshSession(stored.refreshToken)) {
                is AuthRpcResult.Success -> {
                    val baseMerged = rpc.session.copy(
                        lastAuthenticatedAtEpochMs = stored.lastAuthenticatedAtEpochMs,
                        lastRefreshedAtEpochMs = clockEpochMs(),
                    )
                    when (val reconciled = reconcileServerProfile(baseMerged)) {
                        is ServerProfileReconciliation.Deactivated -> {
                            sessionStore.clearSession()
                            identityProvider.setAuthenticatedActorId(null)
                            identityProvider.setStaffRole(StaffRole.CASHIER)
                            _uiState.value = evaluateCurrentUiState().copy(
                                isBusy = false,
                                lastError = reconciled.message,
                            )
                            appendAuthAudit(
                                operation = "AUTH_DEACTIVATED_REJECTED",
                                actorId = AuthSessionPolicy.buildActorId(
                                    baseMerged.userId,
                                    baseMerged.email,
                                    baseMerged.displayName,
                                ),
                                reason = reconciled.message,
                                afterJson = JSONObject().apply {
                                    put("user_id", baseMerged.userId)
                                    put("email", baseMerged.email)
                                }.toString(),
                            )
                            AuthActionOutcome.Rejected(reconciled.message, sessionExpired = true)
                        }

                        is ServerProfileReconciliation.Active -> {
                            val merged = reconciled.session
                            sessionStore.saveSession(merged)
                            val freshState = evaluateCurrentUiState()
                            _uiState.value = freshState
                            syncIdentityFromState(freshState)
                            runCatching { onAuthenticationRestored() }
                            AuthActionOutcome.Refreshed(merged)
                        }
                    }
                }

                is AuthRpcResult.AuthRejected -> {
                    val reason = "Session expired (${rpc.message}). Sign in again to sync pending items."
                    sessionStore.markSessionExpired(reason)
                    val expiredState = evaluateCurrentUiState()
                    _uiState.value = expiredState
                    appendAuthAudit(
                        operation = "AUTH_SESSION_EXPIRED",
                        actorId = AuthSessionPolicy.buildActorId(stored.userId, stored.email, stored.displayName),
                        reason = reason,
                        afterJson = JSONObject().apply {
                            put("code", rpc.code)
                            put("email", stored.email)
                        }.toString(),
                    )
                    AuthActionOutcome.Rejected(reason, sessionExpired = true)
                }

                is AuthRpcResult.TransientError -> {
                    val cachedState = evaluateCurrentUiState().copy(
                        lastError = rpc.message,
                    )
                    _uiState.value = cachedState
                    syncIdentityFromState(cachedState)
                    AuthActionOutcome.TransientFailure(
                        message = rpc.message,
                        keptOfflineSession = true,
                    )
                }
            }
        }
    }

    private suspend fun reconcileServerProfile(session: AuthUserSession): ServerProfileReconciliation {
        val profile = runCatching { authGateway.fetchStaffProfile(session.accessToken) }.getOrNull()
        return RolePermissionPolicy.reconcileSessionWithServerProfile(session, profile)
    }

    suspend fun handleServerAuthRejected(reason: String) {
        val stored = sessionStore.readSession() ?: return
        sessionStore.markSessionExpired(reason)
        val expiredState = evaluateCurrentUiState()
        _uiState.value = expiredState
        appendAuthAudit(
            operation = "AUTH_SERVER_401_403_HOLD",
            actorId = AuthSessionPolicy.buildActorId(stored.userId, stored.email, stored.displayName),
            reason = reason,
            afterJson = null,
        )
    }

    suspend fun signOut(): AuthActionOutcome.SignedOut {
        val currentSession = sessionStore.readSession()
        val pendingCount = runCatching {
            database.syncQueueDao().getAllEventsSnapshot()
                .count { it.status != "SYNCED" && it.status != "RESOLVED_LOCALLY" }
        }.getOrDefault(0)

        if (currentSession != null && connectivityChecker.isOnline()) {
            runCatching { authGateway.signOut(currentSession.accessToken) }
        }

        val actorBefore = currentSession?.let {
            AuthSessionPolicy.buildActorId(it.userId, it.email, it.displayName)
        } ?: identityProvider.getActorId()

        sessionStore.clearSession()
        identityProvider.setAuthenticatedActorId(null)
        identityProvider.setStaffRole(StaffRole.CASHIER)

        _uiState.value = AuthUiState(
            status = AuthStatus.UNAUTHENTICATED,
            session = null,
            lastError = null,
            isBusy = false,
            needsReauthPrompt = false,
        )

        appendAuthAudit(
            operation = "AUTH_SIGN_OUT",
            actorId = actorBefore,
            reason = "Staff signed out (pending_queue=$pendingCount)",
            afterJson = JSONObject().apply {
                put("had_pending_queue", pendingCount > 0)
                put("pending_count", pendingCount)
            }.toString(),
        )

        return AuthActionOutcome.SignedOut(hadPendingSyncItems = pendingCount > 0)
    }

    private fun evaluateCurrentUiState(nowEpochMs: Long = clockEpochMs()): AuthUiState {
        val stored = sessionStore.readSession()
        val expiredFlag = sessionStore.isSessionMarkedExpired()
        val isOnline = connectivityChecker.isOnline()
        val status = AuthSessionPolicy.evaluateStatus(
            session = stored,
            isOnline = isOnline,
            hasSessionExpiredRejection = expiredFlag,
            nowEpochMs = nowEpochMs,
        )
        return AuthUiState(
            status = status,
            session = stored,
            lastError = sessionStore.readLastError(),
            isBusy = false,
            needsReauthPrompt = status == AuthStatus.SESSION_EXPIRED,
        )
    }

    private fun syncIdentityFromState(state: AuthUiState) {
        val session = state.session
        if (session != null && state.status != AuthStatus.UNAUTHENTICATED) {
            identityProvider.setAuthenticatedActorId(
                AuthSessionPolicy.buildActorId(session.userId, session.email, session.displayName)
            )
            identityProvider.setStaffRole(session.staffRole)
        } else {
            identityProvider.setAuthenticatedActorId(null)
            identityProvider.setStaffRole(StaffRole.CASHIER)
        }
    }

    private suspend fun appendAuthAudit(
        operation: String,
        actorId: String,
        reason: String,
        afterJson: String?,
    ) {
        runCatching {
            database.auditDao().appendAudit(
                LocalAuditEventEntity(
                    auditId = "audit-${UUID.randomUUID()}",
                    actorId = actorId,
                    deviceId = identityProvider.getDeviceId(),
                    operation = operation,
                    entityType = "AUTH_SESSION",
                    entityId = actorId,
                    beforeJson = null,
                    afterJson = afterJson,
                    reason = reason,
                    syncStatus = SyncState.LOCAL_PENDING,
                    occurredAtEpochMs = clockEpochMs(),
                )
            )
        }
    }
}
