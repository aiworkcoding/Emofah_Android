package com.emofah.agencies.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emofah.agencies.auth.AuthStatus
import com.emofah.agencies.auth.AuthUiState
import com.emofah.agencies.auth.EmofahPermission
import com.emofah.agencies.auth.RoleUpdateOutcome
import com.emofah.agencies.auth.StaffDirectoryOutcome
import com.emofah.agencies.auth.StaffRoleDirectoryEntry
import com.emofah.agencies.sales.StaffRole
import com.emofah.agencies.ui.components.EmofahBadgeTone
import com.emofah.agencies.ui.components.EmofahCard
import com.emofah.agencies.ui.components.EmofahFeedbackBanner
import com.emofah.agencies.ui.components.EmofahLoadingButton
import com.emofah.agencies.ui.components.EmofahStatusBadge
import com.emofah.agencies.ui.theme.emofahColors
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
    val colors = MaterialTheme.emofahColors
    var confirmSignOutWithPending by remember { mutableStateOf(false) }

    val (tone, iconBg, iconTint) = when (authState.status) {
        AuthStatus.AUTHENTICATED -> Triple(EmofahBadgeTone.SUCCESS, colors.successContainer, colors.success)
        AuthStatus.OFFLINE_CACHED -> Triple(EmofahBadgeTone.WARNING, colors.warningContainer, colors.warning)
        AuthStatus.SESSION_EXPIRED -> Triple(EmofahBadgeTone.DANGER, colors.dangerContainer, colors.danger)
        AuthStatus.UNAUTHENTICATED -> Triple(EmofahBadgeTone.NEUTRAL, colors.surfaceMuted, colors.textMuted)
    }

    val expiryText = remember(authState.session?.expiresAtEpochMs) {
        authState.session?.expiresAtEpochMs?.let { epoch ->
            SimpleDateFormat("dd MMM · HH:mm", Locale.getDefault()).format(Date(epoch))
        }
    }

    EmofahCard(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = iconBg,
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when (authState.status) {
                                AuthStatus.AUTHENTICATED -> Icons.Outlined.VerifiedUser
                                AuthStatus.SESSION_EXPIRED -> Icons.Outlined.WarningAmber
                                AuthStatus.OFFLINE_CACHED -> Icons.Outlined.Lock
                                AuthStatus.UNAUTHENTICATED -> Icons.Outlined.AccountCircle
                            },
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Column {
                    Text(
                        text = authState.session?.displayName ?: "Staff Account",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when (authState.status) {
                            AuthStatus.AUTHENTICATED ->
                                "${authState.session?.email.orEmpty()} · ${authState.activeRole.label}"
                            AuthStatus.OFFLINE_CACHED ->
                                "${authState.session?.email.orEmpty()} · Offline (${authState.activeRole.label})"
                            AuthStatus.SESSION_EXPIRED ->
                                "Sign in again to resume syncing"
                            AuthStatus.UNAUTHENTICATED ->
                                "Not signed in (this phone: ${authState.activeActorId})"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            EmofahStatusBadge(
                text = authState.status.label,
                tone = tone,
                leadingDot = true,
            )
        }

        expiryText?.let {
            Text(
                text = "Signed in until: $it",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }

        Text(
            text = formatRoleCapabilitiesSummary(authState.activeRole, authState.grantedPermissions),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textMuted,
        )

        authState.lastError?.let { err ->
            EmofahFeedbackBanner(
                title = "Account notice",
                message = err,
                tone = EmofahBadgeTone.DANGER,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (authState.status) {
                AuthStatus.UNAUTHENTICATED, AuthStatus.SESSION_EXPIRED -> {
                    EmofahLoadingButton(
                        text = if (authState.status == AuthStatus.SESSION_EXPIRED) "Sign In Again" else "Staff Sign In",
                        onClick = onOpenSignIn,
                        isLoading = authState.isBusy,
                        loadingText = "Checking...",
                        icon = Icons.Outlined.Lock,
                        height = 44.dp,
                        modifier = Modifier.weight(1f),
                    )
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
                            shape = RoundedCornerShape(12.dp),
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
                            shape = RoundedCornerShape(12.dp),
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
                        shape = RoundedCornerShape(12.dp),
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

    if (confirmSignOutWithPending) {
        AlertDialog(
            onDismissRequest = { confirmSignOutWithPending = false },
            shape = RoundedCornerShape(22.dp),
            containerColor = colors.surfaceCard,
            title = { Text("Items Waiting to Sync") },
            text = {
                Text(
                    "$pendingSyncCount item(s) are still saved on this phone waiting to upload. " +
                        "They will stay safe on this phone, and will upload once someone signs back in."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmSignOutWithPending = false
                        onSignOut()
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Sign Out Anyway")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOutWithPending = false }) {
                    Text("Stay Signed In")
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
    onRequestOtp: (email: String, onSent: (Boolean) -> Unit) -> Unit,
    onVerifyOtp: (email: String, token: String) -> Unit,
    onRefreshExisting: () -> Unit,
    onSignOut: () -> Unit,
    onBootstrapInitialAdmin: () -> Unit = {},
    onLoadStaffDirectory: ((StaffDirectoryOutcome) -> Unit) -> Unit = {},
    onAssignStaffRole: (email: String, role: StaffRole, isActive: Boolean, onDone: (RoleUpdateOutcome) -> Unit) -> Unit = { _, _, _, _ -> },
) {
    val colors = MaterialTheme.emofahColors
    var emailInput by remember(authState.session?.email) {
        mutableStateOf(authState.session?.email.orEmpty())
    }
    var otpTokenInput by remember { mutableStateOf("") }
    var otpSent by remember { mutableStateOf(false) }

    var showStaffManager by remember { mutableStateOf(false) }
    var staffList by remember { mutableStateOf<List<StaffRoleDirectoryEntry>>(emptyList()) }
    var staffManagerMessage by remember { mutableStateOf<String?>(null) }
    var staffManagerLoading by remember { mutableStateOf(false) }
    var assignEmailInput by remember { mutableStateOf("") }
    var assignRoleChoice by remember { mutableStateOf(StaffRole.CASHIER) }

    AlertDialog(
        onDismissRequest = {
            if (!authState.isBusy) onDismiss()
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = colors.surfaceCard,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = colors.infoContainer,
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.MailOutline,
                            contentDescription = null,
                            tint = colors.info,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Column {
                    Text(
                        text = when (authState.status) {
                            AuthStatus.SESSION_EXPIRED -> "Sign In Again"
                            AuthStatus.AUTHENTICATED, AuthStatus.OFFLINE_CACHED -> "Your Staff Account"
                            AuthStatus.UNAUTHENTICATED -> "Email Code Sign In"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Passwordless 6-digit staff login",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textMuted,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (authBlockedSyncCount > 0) {
                    EmofahFeedbackBanner(
                        title = "Sign in to upload saved work",
                        message = "$authBlockedSyncCount record(s) are waiting for verification to sync.",
                        tone = EmofahBadgeTone.DANGER,
                    )
                }

                if (authState.status == AuthStatus.AUTHENTICATED || authState.status == AuthStatus.OFFLINE_CACHED) {
                    EmofahCard(
                        containerColor = colors.surfaceElevated,
                        contentPadding = PaddingValues(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = authState.session?.email.orEmpty(),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "${authState.activeRole.label} · ${authState.status.label}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textMuted,
                                )
                            }
                            EmofahStatusBadge(
                                text = authState.activeRole.label,
                                tone = EmofahBadgeTone.INFO,
                            )
                        }
                        Text(
                            text = formatRoleCapabilitiesSummary(authState.activeRole, authState.grantedPermissions),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textMuted,
                        )
                    }

                    if (authState.bootstrapNeeded && authState.activeRole != StaffRole.OWNER_MANAGER) {
                        EmofahCard(
                            containerColor = colors.infoContainer,
                            borderColor = colors.info.copy(alpha = 0.25f),
                            contentPadding = PaddingValues(12.dp),
                        ) {
                            Text(
                                text = "First-Time Shop Owner Setup",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary,
                            )
                            Text(
                                text = "No Owner account is set up yet. If you are the shop owner, tap below to activate Owner access on this account.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textMuted,
                            )
                            EmofahLoadingButton(
                                text = "Claim Owner Access",
                                onClick = onBootstrapInitialAdmin,
                                enabled = isOnline && !authState.isBusy,
                                isLoading = authState.isBusy,
                                height = 44.dp,
                            )
                        }
                    }

                    if (authState.activeRole == StaffRole.OWNER_MANAGER) {
                        HorizontalDivider(color = colors.borderSubtle)
                        OutlinedButton(
                            onClick = {
                                showStaffManager = !showStaffManager
                                if (showStaffManager && isOnline) {
                                    staffManagerLoading = true
                                    staffManagerMessage = null
                                    onLoadStaffDirectory { outcome ->
                                        staffManagerLoading = false
                                        when (outcome) {
                                            is StaffDirectoryOutcome.Loaded -> staffList = outcome.staff
                                            is StaffDirectoryOutcome.Rejected -> staffManagerMessage = outcome.message
                                            is StaffDirectoryOutcome.TransientError -> staffManagerMessage = outcome.message
                                        }
                                    }
                                }
                            },
                            enabled = !authState.isBusy,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (showStaffManager) "Hide Staff Access Manager" else "Manage Staff Roles")
                        }

                        if (showStaffManager) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Give a staff member Cashier, Supervisor, or Owner access after they sign in once with their email code.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textMuted,
                                )
                                OutlinedTextField(
                                    value = assignEmailInput,
                                    onValueChange = { assignEmailInput = it },
                                    label = { Text("Staff Email") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    StaffRole.entries.forEach { roleOption ->
                                        val selected = assignRoleChoice == roleOption
                                        if (selected) {
                                            Button(
                                                onClick = { assignRoleChoice = roleOption },
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.weight(1f),
                                            ) {
                                                Text(
                                                    text = when (roleOption) {
                                                        StaffRole.CASHIER -> "Cashier"
                                                        StaffRole.SUPERVISOR -> "Superv."
                                                        StaffRole.OWNER_MANAGER -> "Owner"
                                                    },
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            }
                                        } else {
                                            OutlinedButton(
                                                onClick = { assignRoleChoice = roleOption },
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.weight(1f),
                                            ) {
                                                Text(
                                                    text = when (roleOption) {
                                                        StaffRole.CASHIER -> "Cashier"
                                                        StaffRole.SUPERVISOR -> "Superv."
                                                        StaffRole.OWNER_MANAGER -> "Owner"
                                                    },
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            }
                                        }
                                    }
                                }
                                EmofahLoadingButton(
                                    text = "Save Staff Role",
                                    loadingText = "Updating...",
                                    isLoading = staffManagerLoading,
                                    enabled = isOnline && !staffManagerLoading && assignEmailInput.contains("@"),
                                    height = 44.dp,
                                    onClick = {
                                        staffManagerLoading = true
                                        staffManagerMessage = null
                                        onAssignStaffRole(assignEmailInput, assignRoleChoice, true) { res ->
                                            when (res) {
                                                is RoleUpdateOutcome.Updated -> {
                                                    staffManagerMessage = res.message
                                                    assignEmailInput = ""
                                                    onLoadStaffDirectory { dir ->
                                                        staffManagerLoading = false
                                                        if (dir is StaffDirectoryOutcome.Loaded) {
                                                            staffList = dir.staff
                                                        }
                                                    }
                                                }
                                                is RoleUpdateOutcome.Rejected -> {
                                                    staffManagerLoading = false
                                                    staffManagerMessage = res.message
                                                }
                                                is RoleUpdateOutcome.TransientError -> {
                                                    staffManagerLoading = false
                                                    staffManagerMessage = res.message
                                                }
                                            }
                                        }
                                    },
                                )

                                staffManagerMessage?.let { msg ->
                                    Text(
                                        text = msg,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }

                                staffList.forEach { member ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = colors.surfaceElevated,
                                        border = BorderStroke(1.dp, colors.borderSubtle),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = member.email,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = "${member.staffRole.label}${if (!member.isActive) " · Disabled" else ""}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = colors.textMuted,
                                                )
                                            }
                                            TextButton(
                                                onClick = {
                                                    assignEmailInput = member.email
                                                    assignRoleChoice = member.staffRole
                                                },
                                            ) {
                                                Text("Select")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = if (isOnline) {
                            if (!otpSent) {
                                "Enter your email address. We will send you a 6-digit login code — no password needed."
                            } else {
                                "We sent a 6-digit login code to $emailInput. Check your email and enter the code below."
                            }
                        } else {
                            "No internet right now. You need internet to sign in, but you can still record sales offline."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )

                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        label = { Text("Your Email Address") },
                        singleLine = true,
                        enabled = !authState.isBusy && !otpSent,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = colors.borderSubtle,
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (otpSent) {
                        OutlinedTextField(
                            value = otpTokenInput,
                            onValueChange = { otpTokenInput = it.filter { ch -> ch.isDigit() || ch == ' ' }.trim() },
                            label = { Text("6-Digit Email Code") },
                            placeholder = { Text("e.g. 123456") },
                            singleLine = true,
                            enabled = !authState.isBusy,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            TextButton(
                                onClick = {
                                    otpSent = false
                                    otpTokenInput = ""
                                },
                                enabled = !authState.isBusy,
                            ) {
                                Text("Change Email")
                            }
                            TextButton(
                                onClick = {
                                    onRequestOtp(emailInput) { sent ->
                                        otpSent = sent
                                    }
                                },
                                enabled = !authState.isBusy && isOnline,
                            ) {
                                Text("Resend Code")
                            }
                        }
                    }
                }

                authState.lastError?.let { err ->
                    EmofahFeedbackBanner(
                        title = "Sign in error",
                        message = err,
                        tone = EmofahBadgeTone.DANGER,
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
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Refresh")
                        }
                    }
                    Button(
                        onClick = onSignOut,
                        enabled = !authState.isBusy,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text("Sign Out")
                    }
                }
            } else {
                if (!otpSent) {
                    EmofahLoadingButton(
                        text = "Send Email Code",
                        loadingText = "Sending...",
                        isLoading = authState.isBusy,
                        enabled = !authState.isBusy && emailInput.isNotBlank() && emailInput.contains("@"),
                        height = 44.dp,
                        onClick = {
                            if (emailInput.isNotBlank()) {
                                onRequestOtp(emailInput) { sent ->
                                    otpSent = sent
                                }
                            }
                        },
                        modifier = Modifier.width(176.dp),
                    )
                } else {
                    EmofahLoadingButton(
                        text = "Sign In",
                        loadingText = "Checking...",
                        isLoading = authState.isBusy,
                        enabled = !authState.isBusy && otpTokenInput.length >= 4,
                        height = 44.dp,
                        onClick = { onVerifyOtp(emailInput, otpTokenInput) },
                        modifier = Modifier.width(150.dp),
                    )
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
