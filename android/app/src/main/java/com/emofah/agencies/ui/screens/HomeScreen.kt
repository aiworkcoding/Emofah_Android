package com.emofah.agencies.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PointOfSale
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.emofah.agencies.auth.AuthStatus
import com.emofah.agencies.auth.AuthUiState
import com.emofah.agencies.data.SupabaseConfig
import com.emofah.agencies.reporting.DailyActivitySummary
import com.emofah.agencies.ui.navigation.EmofahDestination

/**
 * Blueprint Part B2 — Home Dashboard prioritizing primary daily actions over charts.
 * Displays honest state only (no fabricated sales totals or mock stock counts).
 */
@Composable
fun HomeScreen(
    todaySalesCount: Int,
    todaySalesTotal: Double,
    pendingSyncCount: Int,
    cachedProductCount: Int,
    onNavigate: (EmofahDestination) -> Unit,
    onOpenReceiveStock: (() -> Unit)? = null,
    onOpenScanReceipt: (() -> Unit)? = null,
    onOpenServices: (() -> Unit)? = null,
    lowStockAlertCount: Int = 0,
    outOfStockCount: Int = 0,
    dailySummary: DailyActivitySummary? = null,
    authState: AuthUiState = AuthUiState(),
    onOpenAuth: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Daily Operations",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        if (authState.status == AuthStatus.SESSION_EXPIRED) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Staff session expired",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = "Sign in to resume background sync. Offline recording remains safe.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    OutlinedButton(onClick = { onOpenAuth?.invoke() }) {
                        Text("Sign In")
                    }
                }
            }
        }

        // Primary Action: Scan Product (Blueprint Part B2 & A3)
        Button(
            onClick = { onNavigate(EmofahDestination.Scan) },
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Icon(
                imageVector = Icons.Outlined.CameraAlt,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.size(10.dp))
            Text(
                text = "Scan Product",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // Secondary Prominent Action: Record Sale
        Button(
            onClick = { onNavigate(EmofahDestination.RecordSale) },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Icon(
                imageVector = Icons.Outlined.PointOfSale,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.size(10.dp))
            Text(
                text = "Record Sale",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // Remaining Part B2 Quick Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            QuickActionButton(
                label = "Receive Stock",
                icon = Icons.Outlined.AddBox,
                onClick = {
                    if (onOpenReceiveStock != null) {
                        onOpenReceiveStock()
                    } else {
                        onNavigate(EmofahDestination.Stock)
                    }
                },
                modifier = Modifier.weight(1f),
            )
            QuickActionButton(
                label = "Scan Receipt",
                icon = Icons.Outlined.DocumentScanner,
                onClick = {
                    if (onOpenScanReceipt != null) {
                        onOpenScanReceipt()
                    } else {
                        onNavigate(EmofahDestination.Money)
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            QuickActionButton(
                label = "Record Expense",
                icon = Icons.Outlined.ReceiptLong,
                onClick = { onNavigate(EmofahDestination.Money) },
                modifier = Modifier.weight(1f),
            )
            QuickActionButton(
                label = "View Orders",
                icon = Icons.Outlined.ShoppingBag,
                onClick = { onNavigate(EmofahDestination.Orders) },
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            QuickActionButton(
                label = "Services",
                icon = Icons.Outlined.Print,
                onClick = { onOpenServices?.invoke() },
                modifier = Modifier.weight(1f),
            )
            QuickActionButton(
                label = "Activity & Sync",
                icon = Icons.Outlined.History,
                onClick = { onNavigate(EmofahDestination.Activity) },
                modifier = Modifier.weight(1f),
            )
        }

        dailySummary?.let { summary ->
            DailyOperationalSummaryCard(summary = summary)
        }

        // Honest Operational Summary Card (Zero fabricated numbers)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Today & Sync Status",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                StatusRow(
                    label = "Sales recorded today",
                    value = "$todaySalesCount sale(s) · Ksh ${todaySalesTotal.toInt()}",
                )
                StatusRow(
                    label = "Pending offline queue",
                    value = if (pendingSyncCount == 0) "All synced (0 pending)" else "$pendingSyncCount pending sync",
                )
                StatusRow(
                    label = "Cached catalogue",
                    value = "$cachedProductCount products in Room",
                )
                StatusRow(
                    label = "Stock alerts",
                    value = if (outOfStockCount == 0 && lowStockAlertCount == 0) {
                        "All stocked (0 alerts)"
                    } else {
                        "$outOfStockCount out · $lowStockAlertCount low"
                    },
                )
                StatusRow(
                    label = "Backend connection",
                    value = if (SupabaseConfig.isConfigured) "Configured" else "Awaiting local.properties",
                )
                StatusRow(
                    label = "Staff session",
                    value = when (authState.status) {
                        AuthStatus.AUTHENTICATED -> "${authState.session?.email.orEmpty()} (${authState.activeRole.label})"
                        AuthStatus.OFFLINE_CACHED -> "Offline cached (${authState.session?.email.orEmpty()})"
                        AuthStatus.SESSION_EXPIRED -> "Expired — sign in required"
                        AuthStatus.UNAUTHENTICATED -> "Not signed in (${authState.activeActorId})"
                    },
                )
            }
        }
    }
}

@Composable
private fun QuickActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.size(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
