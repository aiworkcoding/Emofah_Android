package com.emofah.agencies.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.emofah.agencies.auth.AuthStatus
import com.emofah.agencies.auth.AuthUiState
import com.emofah.agencies.auth.EmofahPermission
import com.emofah.agencies.sales.StaffRole
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StaffAuthStatusCard(
    authState: AuthUiState,
    isOnline: Boolean,
    pendingSyncCount: Int,
    onOpenSignIn: () -> Unit,
    onRefreshSession: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmSignOutWithPending by remember { mutableStateOf(false) }

    val containerColor = when (authState.status) {
        AuthStatus.AUTHENTICATED -> MaterialTheme.colorScheme.secondaryContainer
        AuthStatus.OFFLINE_CACHED -> MaterialTheme.colorScheme.tertiaryContainer
        AuthStatus.SESSION_EXPIRED -> MaterialTheme.colorScheme.errorContainer
        AuthStatus.UNAUTHENTICATED -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = when (authState.status) {
                            AuthStatus.AUTHENTICATED -> Icons.Outlined.VerifiedUser
                            AuthStatus.SESSION_EXPIRED -> Icons.Outlined.WarningAmber
                            AuthStatus.OFFLINE_CACHED -> Icons.Outlined.Lock
                            AuthStatus.UNAUTHENTICATED -> Icons.Outlined.AccountCircle
                        },
                        contentDescription = null,
                    )
                    Column {
                        Text(
                            text = authState.session?.displayName ?: "Staff Account",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = when (authState.status) {
                                AuthStatus.AUTHENTICATED ->
                                    "${authState.session?.email.orEmpty()} · ${authState.activeRole.label}"
                                AuthStatus.OFFLINE_CACHED ->
                                    "${authState.session?.email.orEmpty()} · Offline cached (${authState.activeRole.label})"
                                AuthStatus.SESSION_EXPIRED ->
                                    "Session expired — sign in to resume syncing"
                                AuthStatus.UNAUTHENTICATED ->
                                    "Not signed in (device attribution: ${authState.activeActorId})"
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                ) {
                    Text(
                        text = authState.status.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            authState.session?.let { session ->
                val expiryText = SimpleDateFormat("dd MMM · HH:mm", Locale.getDefault())
                    .format(Date(session.expiresAtEpochMs))
                Text(
                    text = "Token valid until: $expiryText",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = formatRoleCapabilitiesSummary(authState.activeRole, authState.grantedPermissions),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            authState.lastError?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (authState.status) {
                    AuthStatus.UNAUTHENTICATED, AuthStatus.SESSION_EXPIRED -> {
                        Button(
                            onClick = onOpenSignIn,
                            enabled = !authState.isBusy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (authState.status == AuthStatus.SESSION_EXPIRED) "Sign In Again" else "Staff Sign In")
                        }
                        if (authState.status == AuthStatus.SESSION_EXPIRED) {
                            OutlinedButton(
                                onClick = {
                                    if (pendingSyncCount > 0) {
                                        confirmSignOutWithPending = true
                                    } else {
                                        onSignOut()
                                    }
                                },
                                enabled = !authState.isBusy,
                            ) {
                                Text("Clear")
                            }
                        }
                    }

                    AuthStatus.AUTHENTICATED, AuthStatus.OFFLINE_CACHED -> {
                        if (isOnline) {
                            OutlinedButton(
                                onClick = onRefreshSession,
                                enabled = !authState.isBusy,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Refresh")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                if (pendingSyncCount > 0) {
                                    confirmSignOutWithPending = true
                                } else {
                                    onSignOut()
                                }
                            },
                            enabled = !authState.isBusy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Sign Out")
                        }
                    }
                }
            }
        }
    }

    if (confirmSignOutWithPending) {
        AlertDialog(
            onDismissRequest = { confirmSignOutWithPending = false },
            title = { Text("Pending Offline Records") },
            text = {
                Text(
                    "There are $pendingSyncCount pending item(s) in the local sync queue. " +
                        "Local data in Room will remain safe on this device, but queued items cannot upload until a staff member signs back in."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmSignOutWithPending = false
                        onSignOut()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Sign Out Anyway")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOutWithPending = false }) {
                    Text("Keep Signed In")
                }
            },
        )
    }
}

@Composable
fun StaffSignInDialog(
    authState: AuthUiState,
    isOnline: Boolean,
    authBlockedSyncCount: Int,
    onDismiss: () -> Unit,
    onSubmitSignIn: (email: String, password: String) -> Unit,
    onRefreshExisting: () -> Unit,
    onSignOut: () -> Unit,
) {
    var emailInput by remember(authState.session?.email) {
        mutableStateOf(authState.session?.email.orEmpty())
    }
    var passwordInput by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {
            if (!authState.isBusy) onDismiss()
        },
        title = {
            Text(
                text = when (authState.status) {
                    AuthStatus.SESSION_EXPIRED -> "Re-Authenticate Staff Session"
                    AuthStatus.AUTHENTICATED, AuthStatus.OFFLINE_CACHED -> "Staff Account & Session"
                    AuthStatus.UNAUTHENTICATED -> "Emofah Staff Sign In"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (authBlockedSyncCount > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            text = "$authBlockedSyncCount queued record(s) are paused waiting for a valid staff session. Sign in to resume syncing automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }

                if (authState.status == AuthStatus.AUTHENTICATED || authState.status == AuthStatus.OFFLINE_CACHED) {
                    Text(
                        text = "Signed in as ${authState.session?.email.orEmpty()} (${authState.activeRole.label}).",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Status: ${authState.status.label}. ${formatRoleCapabilitiesSummary(authState.activeRole, authState.grantedPermissions)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = if (isOnline) {
                            "Sign in with your Supabase Emofah staff email and password."
                        } else {
                            "You appear offline. Sign in requires an active connection; offline capture continues safely."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        label = { Text("Staff Email") },
                        singleLine = true,
                        enabled = !authState.isBusy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("Password") },
                        singleLine = true,
                        enabled = !authState.isBusy,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = if (showPassword) "Hide password" else "Show password",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                authState.lastError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (authState.status == AuthStatus.AUTHENTICATED || authState.status == AuthStatus.OFFLINE_CACHED) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isOnline) {
                        OutlinedButton(
                            onClick = onRefreshExisting,
                            enabled = !authState.isBusy,
                        ) {
                            Text("Refresh")
                        }
                    }
                    Button(
                        onClick = onSignOut,
                        enabled = !authState.isBusy,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text("Sign Out")
                    }
                }
            } else {
                Button(
                    onClick = { onSubmitSignIn(emailInput, passwordInput) },
                    enabled = !authState.isBusy && emailInput.isNotBlank() && passwordInput.isNotEmpty(),
                ) {
                    Text(if (authState.isBusy) "Signing In..." else "Sign In")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !authState.isBusy,
            ) {
                Text("Close")
            }
        },
    )
}

private fun formatRoleCapabilitiesSummary(
    role: StaffRole,
    permissions: Set<EmofahPermission>,
): String {
    return when (role) {
        StaffRole.OWNER_MANAGER ->
            "Access (${permissions.size} permissions): Full store, price override, reversal, stock count & security access."
        StaffRole.SUPERVISOR ->
            "Access (${permissions.size} permissions): Sales, price overrides, reversals, stock adjustments & physical counts."
        StaffRole.CASHIER ->
            "Access (${permissions.size} permissions): Standard sales, stock receiving, orders & M-Pesa/financial capture."
    }
}
