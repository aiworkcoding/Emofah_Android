package com.emofah.agencies.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
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
import com.emofah.agencies.ui.components.EmofahAnimatedVisibility
import com.emofah.agencies.ui.components.EmofahBadgeTone
import com.emofah.agencies.ui.components.EmofahCard
import com.emofah.agencies.ui.components.EmofahEmptyState
import com.emofah.agencies.ui.components.EmofahFeedbackBanner
import com.emofah.agencies.ui.components.EmofahInteractiveCard
import com.emofah.agencies.ui.components.EmofahLocalImage
import com.emofah.agencies.ui.components.EmofahSearchField
import com.emofah.agencies.ui.components.EmofahSectionHeader
import com.emofah.agencies.ui.components.EmofahSkeletonCard
import com.emofah.agencies.ui.components.EmofahStatusBadge
import com.emofah.agencies.ui.theme.emofahColors
import com.emofah.agencies.ui.theme.tabular
import kotlin.math.roundToInt

private enum class StockFilterMode {
    ALL,
    ALERTS_ONLY,
    OUT_OF_STOCK_ONLY,
}

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
    val colors = MaterialTheme.emofahColors
    var searchQuery by remember { mutableStateOf("") }
    var filterMode by remember { mutableStateOf(StockFilterMode.ALL) }
    var showAlertsBannerItems by remember { mutableStateOf(false) }

    val projectionsByProductId = remember(stockProjections) {
        stockProjections.associateBy { it.productId }
    }
    val activeThreshold = alertSummary?.threshold ?: StockLookupPolicy.DEFAULT_LOW_STOCK_THRESHOLD
    val activeProducts = remember(cachedProducts) { cachedProducts.filter { !it.isArchived } }

    val computedOutOfStockCount = remember(activeProducts, projectionsByProductId) {
        activeProducts.count { product ->
            val avail = projectionsByProductId[product.id]?.provisionalAvailableQty ?: product.stockQty
            avail <= 0
        }
    }
    val computedTotalAlertCount = remember(activeProducts, projectionsByProductId, activeThreshold) {
        activeProducts.count { product ->
            val avail = projectionsByProductId[product.id]?.provisionalAvailableQty ?: product.stockQty
            avail <= activeThreshold
        }
    }
    val outOfStockCount = alertSummary?.outOfStockItems?.size ?: computedOutOfStockCount
    val totalAlertCount = alertSummary?.totalAlertsCount ?: computedTotalAlertCount

    val filteredProducts = remember(
        activeProducts,
        projectionsByProductId,
        searchQuery,
        filterMode,
        activeThreshold,
    ) {
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
        }.take(60)
    }

    val urgentAlerts = alertSummary?.urgentItems.orEmpty()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "stock_actions") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onScanForStock,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(0.95f)
                            .height(48.dp),
                    ) {
                        Icon(Icons.Outlined.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Scan", fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    if (onOpenReceiveStock != null) {
                        OutlinedButton(
                            onClick = { onOpenReceiveStock(selectedSnapshot?.product) },
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, colors.borderStrong),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        ) {
                            Icon(Icons.Outlined.AddBox, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Receive", fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                    if (onOpenAdjustStock != null) {
                        OutlinedButton(
                            onClick = { onOpenAdjustStock(selectedSnapshot?.product) },
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, colors.borderStrong),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        ) {
                            Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Adjust", fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }

                if (onOpenPhysicalCount != null || onRegisterNewProduct != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (onOpenPhysicalCount != null) {
                            OutlinedButton(
                                onClick = onOpenPhysicalCount,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, colors.borderSubtle),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                            ) {
                                Icon(Icons.Outlined.Inventory2, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Stock Count", maxLines = 1)
                            }
                        }
                        if (onRegisterNewProduct != null) {
                            OutlinedButton(
                                onClick = { onRegisterNewProduct(searchQuery.trim()) },
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, colors.borderSubtle),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                            ) {
                                Icon(Icons.Outlined.AddBox, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("New Product", maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        item(key = "stock_search_filters") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EmofahSearchField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = "Search product name, brand, or category",
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EmofahStatusBadge(
                        text = if (isDeviceOnline) "Online · Live Lookup" else "Offline · Cached Stock",
                        tone = if (isDeviceOnline) EmofahBadgeTone.SUCCESS else EmofahBadgeTone.WARNING,
                        leadingDot = true,
                    )
                    Text(
                        text = "Alert ≤ $activeThreshold",
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = colors.textMuted,
                    )
                }
            }
        }

        if (totalAlertCount > 0) {
            item(key = "stock_alerts_banner") {
                val isCritical = outOfStockCount > 0
                EmofahCard(
                    containerColor = if (isCritical) colors.dangerContainer else colors.warningContainer,
                    borderColor = (if (isCritical) colors.danger else colors.warning).copy(alpha = 0.25f),
                    contentPadding = PaddingValues(14.dp),
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
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.WarningAmber,
                                contentDescription = null,
                                tint = if (isCritical) colors.danger else colors.warning,
                            )
                            Column {
                                Text(
                                    text = "$outOfStockCount Out · ${(totalAlertCount - outOfStockCount).coerceAtLeast(0)} Low Stock",
                                    style = MaterialTheme.typography.titleSmall.tabular(),
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isCritical) colors.danger else colors.warning,
                                )
                                Text(
                                    text = if (alertSummary?.isConfiguredFromServer == true) {
                                        "Threshold ≤ $activeThreshold units (server setting)"
                                    } else {
                                        "Threshold ≤ $activeThreshold units (default)"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textPrimary,
                                )
                            }
                        }
                        TextButton(onClick = { showAlertsBannerItems = !showAlertsBannerItems }) {
                            Text(if (showAlertsBannerItems) "Hide" else "View (${urgentAlerts.size.coerceAtMost(5)})")
                        }
                    }

                    EmofahAnimatedVisibility(visible = showAlertsBannerItems && urgentAlerts.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            urgentAlerts.take(5).forEach { item ->
                                Surface(
                                    color = colors.surfaceCard,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, colors.borderSubtle),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectProduct(item.product) },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
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
                                                style = MaterialTheme.typography.labelSmall.tabular(),
                                                color = colors.danger,
                                            )
                                        }
                                        if (onOpenReceiveStock != null) {
                                            TextButton(onClick = { onOpenReceiveStock(item.product) }) {
                                                Text("Restock")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isLoadingDetail) {
            item(key = "stock_detail_skeleton") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EmofahSkeletonCard()
                    EmofahSkeletonCard()
                }
            }
        } else if (selectedSnapshot != null) {
            item(key = "selected_stock_detail_${selectedSnapshot.product.id}") {
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
        }

        item(key = "catalogue_header") {
            EmofahSectionHeader(
                title = if (selectedSnapshot == null) "Catalogue Stock" else "Switch Product",
                subtitle = "${filteredProducts.size} matching items",
            )
        }

        if (filteredProducts.isEmpty()) {
            item(key = "catalogue_empty") {
                val hint = searchQuery.trim().take(24)
                EmofahEmptyState(
                    icon = Icons.Outlined.Inventory2,
                    title = "No matching products",
                    message = "No products matched your current filter in the local catalogue cache.",
                    actionLabel = if (onRegisterNewProduct != null) {
                        if (hint.isNotEmpty()) "Register \"$hint\"" else "Register New Product"
                    } else {
                        null
                    },
                    onAction = onRegisterNewProduct?.let { cb -> { cb(searchQuery.trim()) } },
                )
            }
        } else {
            items(
                items = filteredProducts,
                key = { it.id },
            ) { product ->
                val proj = projectionsByProductId[product.id]
                val availableQty = proj?.provisionalAvailableQty ?: product.stockQty
                val level = StockLookupPolicy.evaluateLowStock(availableQty, activeThreshold)
                val isSelected = selectedSnapshot?.product?.id == product.id

                EmofahInteractiveCard(
                    onClick = { onSelectProduct(product) },
                    isSelected = isSelected,
                    contentPadding = PaddingValues(13.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = product.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${product.category} · Ksh ${product.effectiveSellPrice.roundToInt()}",
                                style = MaterialTheme.typography.labelSmall.tabular(),
                                color = colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "$availableQty units",
                                style = MaterialTheme.typography.titleSmall.tabular(),
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary,
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
    val colors = MaterialTheme.emofahColors
    val product = snapshot.product

    EmofahCard(
        containerColor = colors.surfaceElevated,
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EmofahLocalImage(
                path = snapshot.cachedPhotoPath,
                contentDescription = product.name,
                fallbackIcon = Icons.Outlined.Inventory2,
                maxDimensionPx = 256,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        product.brandHint,
                        product.variantLabel,
                        product.category,
                        "Ksh ${product.effectiveSellPrice.roundToInt()}",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = colors.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(onClick = onClearSelection) {
                Icon(Icons.Outlined.Close, contentDescription = "Close stock detail")
            }
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.surfaceCard,
            border = BorderStroke(1.dp, colors.borderSubtle),
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
                            color = colors.textMuted,
                        )
                        Text(
                            text = "${snapshot.provisionalQty} units",
                            style = MaterialTheme.typography.headlineSmall.tabular(),
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                        )
                    }
                    LowStockBadge(snapshot.lowStockLevel)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Server: ${snapshot.serverQty}",
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = colors.textMuted,
                    )
                    Text(
                        text = "Pending offline: ${if (snapshot.pendingDeltaQty > 0) "+${snapshot.pendingDeltaQty}" else snapshot.pendingDeltaQty}",
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = colors.textMuted,
                    )
                }

                HorizontalDivider(color = colors.borderSubtle)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = snapshot.freshnessLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textMuted,
                        modifier = Modifier.weight(1f),
                    )
                    if (isDeviceOnline && product.id > 0L) {
                        OutlinedButton(
                            onClick = onRefresh,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Refresh")
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Place,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = colors.textMuted,
                    )
                    Text(
                        text = snapshot.supportedLocation?.let { "Location: $it" }
                            ?: "Location: Not specified in shared catalogue",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textMuted,
                    )
                }

                if (snapshot.lowStockLevel != LowStockLevel.IN_STOCK) {
                    Text(
                        text = "Alert threshold: ≤ ${snapshot.lowStockThreshold} unit(s).",
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = colors.danger,
                    )
                }
            }
        }

        if (snapshot.hasConflict) {
            EmofahFeedbackBanner(
                title = "Sync Conflict Flagged",
                message = snapshot.conflictNote ?: "Review stock conflict in Activity.",
                tone = EmofahBadgeTone.DANGER,
            )
        }

        snapshot.statusNote?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textMuted,
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
                Icon(Icons.Outlined.ShoppingCart, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text("Sell", maxLines = 1)
            }
            if (onReceiveStock != null) {
                OutlinedButton(
                    onClick = onReceiveStock,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.AddBox, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
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
                    Spacer(Modifier.width(4.dp))
                    Text("Adjust", maxLines = 1)
                }
            }
        }

        Text(
            text = "Recent Stock Movements (${snapshot.movements.size})",
            style = MaterialTheme.typography.titleSmall.tabular(),
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
        )

        if (snapshot.movements.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.surfaceCard,
                border = BorderStroke(1.dp, colors.borderSubtle),
            ) {
                Text(
                    text = "No recent sales or reversal movements recorded for this product.",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textMuted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                snapshot.movements.take(10).forEach { movement ->
                    MovementItemRow(movement)
                }
            }
        }
    }
}

@Composable
private fun MovementItemRow(movement: StockMovementEntry) {
    val colors = MaterialTheme.emofahColors
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceCard,
        border = BorderStroke(1.dp, colors.borderSubtle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = movement.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Text(
                    text = movement.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
            Text(
                text = movement.formattedTime,
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = colors.textMuted,
            )
        }
    }
}

@Composable
private fun LowStockBadge(level: LowStockLevel) {
    val tone = when (level) {
        LowStockLevel.OUT_OF_STOCK -> EmofahBadgeTone.DANGER
        LowStockLevel.LOW_STOCK -> EmofahBadgeTone.WARNING
        LowStockLevel.IN_STOCK -> EmofahBadgeTone.SUCCESS
    }
    EmofahStatusBadge(
        text = level.label,
        tone = tone,
    )
}
