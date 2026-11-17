package com.emofah.agencies.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PointOfSale
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Blueprint Part B1 — Primary Navigation (7 destinations).
 */
enum class EmofahDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val showInBottomBar: Boolean,
) {
    Home("home", "Home", Icons.Outlined.Home, true),
    Scan("scan", "Scan", Icons.Outlined.CameraAlt, true),
    RecordSale("record_sale", "Sale", Icons.Outlined.PointOfSale, true),
    Stock("stock", "Stock", Icons.Outlined.Inventory2, true),
    Money("money", "Money", Icons.Outlined.AccountBalanceWallet, true),
    Orders("orders", "Orders", Icons.Outlined.ShoppingBag, false),
    Activity("activity", "Activity", Icons.Outlined.History, false),
}
