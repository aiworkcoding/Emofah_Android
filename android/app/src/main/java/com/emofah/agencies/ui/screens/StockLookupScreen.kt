package com.emofah.agencies.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.local.entities.LocalStockProjectionEntity
import com.emofah.agencies.stock.LowStockLevel
import com.emofah.agencies.stock.StockAlertSummary
import com.emofah.agencies.stock.StockLookupPolicy
import com.emofah.agencies.stock.StockLookupSnapshot
import com.emofah.agencies.stock.StockMovementEntry
import kotlin.math.roundToInt

private enum class StockFilterMode {
    ALL,
    ALERTS_ONLY,
    OUT_OF_STOCK_ONLY,
}

/**
 * Stage 4.1 — Stock Lookup Screen.
 * Allows operators to scan or search a catalogue product and inspect live/provisional stock,
 * freshness, low-stock status, supported shelf location, and recent stock movements.
 */
@Composable
fun StockLookupScreen(
    cachedProducts: List<ProductIdentity>,
    stockProjections: List<LocalStockProjectionEntity>,
    selectedSnapshot: StockLookupSnapshot?,
    isLoadingDetail: Boolean,
    isDeviceOnline: Boolean,
    onScanForStock: () -> Unit,
    onSelectProduct: (ProductIdentity) -> Unit,
    onRefreshSelected: (Long) -> Unit,
    onClearSelection: () -> Unit,
    onSellProduct: (ProductIdentity) -> Unit,
    onOpenReceiveStock: ((ProductIdentity?) -> Unit)? = null,
    onOpenAdjustStock: ((ProductIdentity?) -> Unit)? = null,
    onOpenPhysicalCount: (() -> Unit)? = null,
    onRegisterNewProduct: ((initialNameHint: String) -> Unit)? = null,
    alertSummary: StockAlertSummary? = null,
    modifier: Modifier = Modifier,
) {
    var searchQuery by remember { mutableStateOf("") }
    var filterMode by remember { mutableStateOf(StockFilterMode.ALL) }
    var showAlertsBannerItems by remember { mutableStateOf(false) }

    val projectionsByProductId = remember(stockProjections) {
        stockProjections.associateBy { it.productId }
    }
    val activeThreshold = alertSummary?.threshold ?: StockLookupPolicy.DEFAULT_LOW_STOCK_THRESHOLD
    val activeProducts = remember(cachedProducts) { cachedProducts.filter { !it.isArchived } }

    val outOfStockCount = alertSummary?.outOfStockItems?.size ?: remember(activeProducts, projectionsByProductId, activeThreshold) {
        activeProducts.count { product ->
            val avail = projectionsByProductId[product.id]?.provisionalAvailableQty ?: product.stockQty
            avail <= 0
        }
    }
    val totalAlertCount = alertSummary?.totalAlertsCount ?: remember(activeProducts, projectionsByProductId, activeThreshold) {
        activeProducts.count { product ->
            val avail = projectionsByProductId[product.id]?.provisionalAvailableQty ?: product.stockQty
            avail <= activeThreshold
        }
    }

    val filteredProducts = remember(activeProducts, projectionsByProductId, searchQuery, filterMode, activeThreshold) {
        val q = searchQuery.trim().lowercase()
        activeProducts.filter { product ->
            val matchesQuery = q.isEmpty() ||
                product.name.lowercase().contains(q) ||
                product.category.lowercase().contains(q) ||
                (product.variantLabel?.lowercase()?.contains(q) == true)
            val avail = projectionsByProductId[product.id]?.provisionalAvailableQty ?: product.stockQty
            val matchesFilter = when (filterMode) {
                StockFilterMode.ALL -> true
                StockFilterMode.ALERTS_ONLY -> avail <= activeThreshold
                StockFilterMode.OUT_OF_STOCK_ONLY -> avail <= 0
            }
            matchesQuery && matchesFilter
        }.take(40)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Primary Stock Actions: Scan, Receive, Adjust, Count
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onScanForStock,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .weight(0.9f)
                    .height(52.dp),
            ) {
                Icon(Icons.Outlined.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Scan",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
            if (onOpenReceiveStock != null) {
                OutlinedButton(
                    onClick = { onOpenReceiveStock(selectedSnapshot?.product) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1.0f)
                        .height(52.dp),
                ) {
                    Icon(Icons.Outlined.AddBox, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = "Receive",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
            if (onOpenAdjustStock != null) {
                OutlinedButton(
                    onClick = { onOpenAdjustStock(selectedSnapshot?.product) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1.0f)
                        .height(52.dp),
                ) {
                    Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = "Adjust",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }
        // Second row of actions: Physical Count & Register New Product
        if (onOpenPhysicalCount != null || onRegisterNewProduct != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onOpenPhysicalCount != null) {
                    OutlinedButton(
                        onClick = onOpenPhysicalCount,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Icon(Icons.Outlined.Inventory2, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Cycle Count",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
                if (onRegisterNewProduct != null) {
                    OutlinedButton(
                        onClick = { onRegisterNewProduct(searchQuery.trim()) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Icon(Icons.Outlined.AddBox, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "New Product",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        // Quick Search & Low-Stock Filter
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search cached catalogue") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Clear search")
                    }
                }
            } else {
                null
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Stage 4.6 — Stock Alerts Summary Banner
        val urgentAlerts = alertSummary?.urgentItems.orEmpty()
        if (totalAlertCount > 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (outOfStockCount > 0) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    },
                ),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAlertsBannerItems = !showAlertsBannerItems },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.WarningAmber, contentDescription = null)
                            Column {
                                Text(
                                    text = "Stock Alerts · $outOfStockCount Out · ${(totalAlertCount - outOfStockCount).coerceAtLeast(0)} Low",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = if (alertSummary?.isConfiguredFromServer == true) {
                                        "Alert threshold <= $activeThreshold units (server setting)"
                                    } else {
                                        "Alert threshold <= $activeThreshold units (default)"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        Text(
                            text = if (showAlertsBannerItems) "Hide" else "View (${urgentAlerts.size.coerceAtMost(5)})",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (showAlertsBannerItems && urgentAlerts.isNotEmpty()) {
                        urgentAlerts.take(5).forEach { item ->
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectProduct(item.product) },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.product.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${item.availableQty} left · ${item.level.label}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                    if (onOpenReceiveStock != null) {
                                        OutlinedButton(onClick = { onOpenReceiveStock(item.product) }) {
                                            Text("Restock", maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = filterMode == StockFilterMode.ALL,
                onClick = { filterMode = StockFilterMode.ALL },
                label = { Text("All (${activeProducts.size})") },
            )
            FilterChip(
                selected = filterMode == StockFilterMode.ALERTS_ONLY,
                onClick = { filterMode = StockFilterMode.ALERTS_ONLY },
                label = { Text("Alerts ($totalAlertCount)") },
            )
            FilterChip(
                selected = filterMode == StockFilterMode.OUT_OF_STOCK_ONLY,
                onClick = { filterMode = StockFilterMode.OUT_OF_STOCK_ONLY },
                label = { Text("Out ($outOfStockCount)") },
            )
        }
        Text(
            text = if (isDeviceOnline) "Online · Live lookup (Alert <= $activeThreshold)" else "Offline · Cached stock (Alert <= $activeThreshold)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (isLoadingDetail) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("Checking stock and recent movements...", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else if (selectedSnapshot != null) {
            SelectedProductStockDetail(
                snapshot = selectedSnapshot,
                isDeviceOnline = isDeviceOnline,
                onRefresh = { onRefreshSelected(selectedSnapshot.product.id) },
                onClearSelection = onClearSelection,
                onSellProduct = { onSellProduct(selectedSnapshot.product) },
                onReceiveStock = onOpenReceiveStock?.let { cb -> { cb(selectedSnapshot.product) } },
                onAdjustStock = onOpenAdjustStock?.let { cb -> { cb(selectedSnapshot.product) } },
            )
        }

        // Catalogue List (either full directory or quick switch below selected detail)
        Text(
            text = if (selectedSnapshot == null) {
                "Catalogue Stock (${filteredProducts.size})"
            } else {
                "Switch Product (${filteredProducts.size})"
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )

        if (filteredProducts.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "No matching products found in local catalogue cache.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (onRegisterNewProduct != null) {
                        OutlinedButton(
                            onClick = { onRegisterNewProduct(searchQuery.trim()) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.AddBox, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            val labelHint = searchQuery.trim().take(24)
                            Text(
                                if (labelHint.isNotEmpty()) {
                                    "Register \"$labelHint\" as New Product"
                                } else {
                                    "Register New Product"
                                }
                            )
                        }
                    }
                }
            }
        } else {
            filteredProducts.forEach { product ->
                val proj = projectionsByProductId[product.id]
                val availableQty = proj?.provisionalAvailableQty ?: product.stockQty
                val level = StockLookupPolicy.evaluateLowStock(availableQty, activeThreshold)
                val isSelected = selectedSnapshot?.product?.id == product.id

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectProduct(product) },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = product.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${product.category} · Ksh ${product.effectiveSellPrice.roundToInt()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "$availableQty units",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            LowStockBadge(level)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedProductStockDetail(
    snapshot: StockLookupSnapshot,
    isDeviceOnline: Boolean,
    onRefresh: () -> Unit,
    onClearSelection: () -> Unit,
    onSellProduct: () -> Unit,
    onReceiveStock: (() -> Unit)? = null,
    onAdjustStock: (() -> Unit)? = null,
) {
    val product = snapshot.product
    val bitmap = remember(snapshot.cachedPhotoPath) {
        snapshot.cachedPhotoPath?.let { BitmapFactory.decodeFile(it) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = product.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Inventory2,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = product.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = listOfNotNull(
                            product.brandHint,
                            product.variantLabel,
                            product.category,
                            "Ksh ${product.effectiveSellPrice.roundToInt()}",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }

                IconButton(onClick = onClearSelection) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close stock detail")
                }
            }

            // Available Stock & Low-Stock Status Row
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = "Available Stock",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "${snapshot.provisionalQty} units",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        LowStockBadge(snapshot.lowStockLevel)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Server stock: ${snapshot.serverQty}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Pending offline delta: ${if (snapshot.pendingDeltaQty > 0) "+${snapshot.pendingDeltaQty}" else snapshot.pendingDeltaQty}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = snapshot.freshnessLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (isDeviceOnline && product.id > 0L) {
                            OutlinedButton(onClick = onRefresh) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Refresh")
                            }
                        }
                    }

                    // Supported Product Location
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Place,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = snapshot.supportedLocation?.let { "Location: $it" }
                                ?: "Location: Not specified in shared catalogue",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (snapshot.lowStockLevel != LowStockLevel.IN_STOCK) {
                        Text(
                            text = "Alert threshold: <= ${snapshot.lowStockThreshold} unit(s).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (snapshot.hasConflict) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text(
                            text = snapshot.conflictNote ?: "Stock sync conflict flagged — review in Activity.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            snapshot.statusNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onSellProduct,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.ShoppingCart, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Sell Product", maxLines = 1)
                }
                if (onReceiveStock != null) {
                    OutlinedButton(
                        onClick = onReceiveStock,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.AddBox, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("Receive", maxLines = 1)
                    }
                }
                if (onAdjustStock != null) {
                    OutlinedButton(
                        onClick = onAdjustStock,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("Adjust", maxLines = 1)
                    }
                }
            }

            // Recent Stock Movements
            Text(
                text = "Recent Stock Movements (${snapshot.movements.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )

            if (snapshot.movements.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Text(
                        text = "No recent sales or reversal movements recorded for this product.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    )
                }
            } else {
                snapshot.movements.take(10).forEach { movement ->
                    MovementItemRow(movement)
                }
            }
        }
    }
}

@Composable
private fun MovementItemRow(movement: StockMovementEntry) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = movement.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = movement.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = movement.formattedTime,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LowStockBadge(level: LowStockLevel) {
    val (bg, fg) = when (level) {
        LowStockLevel.OUT_OF_STOCK ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        LowStockLevel.LOW_STOCK ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        LowStockLevel.IN_STOCK ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bg,
    ) {
        Text(
            text = level.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
