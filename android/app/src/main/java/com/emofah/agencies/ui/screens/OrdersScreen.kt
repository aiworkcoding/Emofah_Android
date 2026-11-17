package com.emofah.agencies.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.orders.OrderConversionPolicy
import com.emofah.agencies.orders.OrderFeedSnapshot
import com.emofah.agencies.orders.OrderStatusTransitionPolicy
import com.emofah.agencies.orders.ResolvedOrderSaleLine
import com.emofah.agencies.orders.StoreOrderRecord
import com.emofah.agencies.orders.StoreOrderStatus
import com.emofah.agencies.receipts.DigitalReceiptData
import com.emofah.agencies.receipts.DigitalReceiptFormatter
import com.emofah.agencies.sales.PaymentMethod
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private enum class OrderStatusFilter(val label: String) {
    ALL("All"),
    PENDING("Pending"),
    ACTIVE("Confirmed / Ready"),
    FULFILLED("Fulfilled"),
    CANCELLED("Cancelled"),
}

/**
 * Stage 6.1 + Stage 6.2 — Online Orders Retrieval & Operational Management Screen.
 * Displays live and cached storefront orders from shared `public.orders` with status filtering,
 * search, detailed itemized inspection, Pending Sync badges, and operational status transitions
 * (Pending -> Confirmed -> Ready -> Fulfilled / Cancelled).
 */
@Composable
fun OrdersScreen(
    snapshot: OrderFeedSnapshot,
    isRefreshing: Boolean,
    isDeviceOnline: Boolean,
    onRefresh: () -> Unit,
    catalogueProducts: List<ProductIdentity> = emptyList(),
    actionMessage: String? = null,
    isUpdatingOrder: Boolean = false,
    onUpdateOrderStatus: (StoreOrderRecord, StoreOrderStatus, String?) -> Unit = { _, _, _ -> },
    onConvertOrderToSale: (StoreOrderRecord, List<ResolvedOrderSaleLine>, PaymentMethod) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(OrderStatusFilter.ALL) }
    var inspectedOrderId by remember { mutableStateOf<String?>(null) }
    var convertingOrder by remember { mutableStateOf<StoreOrderRecord?>(null) }
    var previewedReceipt by remember { mutableStateOf<DigitalReceiptData?>(null) }

    val timeFormat = remember { SimpleDateFormat("dd MMM · HH:mm", Locale.getDefault()) }

    val liveInspectedOrder = remember(snapshot.orders, inspectedOrderId) {
        inspectedOrderId?.let { targetId -> snapshot.orders.find { it.id == targetId } }
    }

    val filteredOrders = remember(snapshot.orders, searchQuery, selectedFilter) {
        val q = searchQuery.trim().lowercase()
        snapshot.orders.filter { order ->
            val matchesFilter = when (selectedFilter) {
                OrderStatusFilter.ALL -> true
                OrderStatusFilter.PENDING -> order.status == StoreOrderStatus.PENDING || order.status == StoreOrderStatus.UNKNOWN
                OrderStatusFilter.ACTIVE -> order.status == StoreOrderStatus.CONFIRMED || order.status == StoreOrderStatus.READY
                OrderStatusFilter.FULFILLED -> order.status == StoreOrderStatus.FULFILLED
                OrderStatusFilter.CANCELLED -> order.status == StoreOrderStatus.CANCELLED
            }
            val matchesQuery = q.isEmpty() ||
                order.displayNumber.lowercase().contains(q) ||
                order.id.lowercase().contains(q) ||
                (order.customerName?.lowercase()?.contains(q) == true) ||
                (order.customerPhone?.lowercase()?.contains(q) == true) ||
                order.items.any { it.productName.lowercase().contains(q) }
            matchesFilter && matchesQuery
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header + Refresh
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Online Orders",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                val freshness = snapshot.lastFetchedEpochMs?.let {
                    "Last synced: ${timeFormat.format(Date(it))}"
                } ?: if (isDeviceOnline) "Connected · Tap refresh" else "Offline"
                Text(
                    text = freshness,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = onRefresh,
                enabled = !isRefreshing && !isUpdatingOrder,
                shape = RoundedCornerShape(14.dp),
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(if (isRefreshing) "Syncing" else "Refresh")
            }
        }

        val bannerText = actionMessage?.takeIf { it.isNotBlank() } ?: snapshot.statusMessage
        bannerText?.let { msg ->
            Surface(
                color = if (actionMessage != null) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (actionMessage != null) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        // Search
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search order #, item, or customer") },
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

        // Status filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OrderStatusFilter.entries.forEach { filter ->
                val count = when (filter) {
                    OrderStatusFilter.ALL -> snapshot.orders.size
                    OrderStatusFilter.PENDING -> snapshot.orders.count { it.status == StoreOrderStatus.PENDING || it.status == StoreOrderStatus.UNKNOWN }
                    OrderStatusFilter.ACTIVE -> snapshot.orders.count { it.status == StoreOrderStatus.CONFIRMED || it.status == StoreOrderStatus.READY }
                    OrderStatusFilter.FULFILLED -> snapshot.orders.count { it.status == StoreOrderStatus.FULFILLED }
                    OrderStatusFilter.CANCELLED -> snapshot.orders.count { it.status == StoreOrderStatus.CANCELLED }
                }
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { selectedFilter = filter },
                    label = { Text("${filter.label} ($count)") },
                )
            }
        }

        if (filteredOrders.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ShoppingBag,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (snapshot.orders.isEmpty()) "No Storefront Orders Yet" else "No Matching Orders",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (snapshot.orders.isEmpty()) {
                            "Shared public.orders currently has 0 orders. Storefront checkouts will appear here automatically on refresh."
                        } else {
                            "Adjust the status filter or search query to inspect other orders."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            filteredOrders.forEach { order ->
                OrderSummaryCard(
                    order = order,
                    timeFormat = timeFormat,
                    onClick = { inspectedOrderId = order.id },
                )
            }
        }
    }

    liveInspectedOrder?.let { detail ->
        OrderDetailDialog(
            order = detail,
            timeFormat = timeFormat,
            isUpdating = isUpdatingOrder,
            onTransition = { target, reason ->
                onUpdateOrderStatus(detail, target, reason)
            },
            onOpenConvertToSale = {
                convertingOrder = detail
            },
            onOpenDigitalReceipt = {
                previewedReceipt = DigitalReceiptFormatter.buildFromOrder(detail)
            },
            onDismiss = { inspectedOrderId = null },
        )
    }

    convertingOrder?.let { targetOrder ->
        val freshOrder = snapshot.orders.find { it.id == targetOrder.id } ?: targetOrder
        ConvertOrderToSaleDialog(
            order = freshOrder,
            catalogueProducts = catalogueProducts,
            isSubmitting = isUpdatingOrder,
            onConfirm = { resolvedLines, paymentMethod ->
                onConvertOrderToSale(freshOrder, resolvedLines, paymentMethod)
                convertingOrder = null
            },
            onDismiss = { if (!isUpdatingOrder) convertingOrder = null },
        )
    }

    previewedReceipt?.let { receiptData ->
        DigitalReceiptPreviewDialog(
            receipt = receiptData,
            onDismiss = { previewedReceipt = null },
        )
    }
}

@Composable
private fun OrderSummaryCard(
    order: StoreOrderRecord,
    timeFormat: SimpleDateFormat,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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
                Text(
                    text = "Order ${order.displayNumber}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (order.isConvertedToSale) {
                        ConvertedSaleChip()
                    }
                    if (order.isPendingSync) {
                        PendingSyncChip()
                    }
                    OrderStatusBadge(status = order.status, rawStatus = order.rawStatus)
                }
            }

            if (!order.customerName.isNullOrBlank() || !order.customerPhone.isNullOrBlank()) {
                val custLine = listOfNotNull(order.customerName, order.customerPhone).joinToString(" · ")
                Text(
                    text = custLine,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }

            val itemsPreview = if (order.items.isEmpty()) {
                "No line items decoded"
            } else {
                order.items.take(3).joinToString(", ") { "${it.quantity}× ${it.productName}" } +
                    if (order.items.size > 3) " +${order.items.size - 3} more" else ""
            }
            Text(
                text = itemsPreview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val timeText = if (order.createdAtEpochMs > 0L) {
                    timeFormat.format(Date(order.createdAtEpochMs))
                } else {
                    order.createdAtIso.take(16).ifBlank { "Time unknown" }
                }
                Text(
                    text = "$timeText · ${order.totalUnits} unit(s)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Ksh ${order.totalPrice.roundToInt()}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ConvertedSaleChip() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = "Sale Recorded",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun PendingSyncChip() {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = "Pending Sync",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun OrderStatusBadge(status: StoreOrderStatus, rawStatus: String) {
    val (bg, fg) = when (status) {
        StoreOrderStatus.PENDING, StoreOrderStatus.UNKNOWN ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        StoreOrderStatus.CONFIRMED, StoreOrderStatus.READY ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        StoreOrderStatus.FULFILLED ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        StoreOrderStatus.CANCELLED ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(color = bg, shape = RoundedCornerShape(8.dp)) {
        Text(
            text = if (status == StoreOrderStatus.UNKNOWN) rawStatus else status.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun OrderDetailDialog(
    order: StoreOrderRecord,
    timeFormat: SimpleDateFormat,
    isUpdating: Boolean,
    onTransition: (StoreOrderStatus, String?) -> Unit,
    onOpenConvertToSale: () -> Unit,
    onOpenDigitalReceipt: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showCancelConfirm by remember(order.id) { mutableStateOf(false) }
    var cancelReason by remember(order.id) { mutableStateOf("") }
    var localCancelError by remember(order.id) { mutableStateOf<String?>(null) }

    val allowedTransitions = remember(order.status) {
        OrderStatusTransitionPolicy.allowedNextStatuses(order.status)
    }
    val forwardTransitions = remember(allowedTransitions) {
        allowedTransitions.filter { it != StoreOrderStatus.CANCELLED }
    }
    val canCancel = StoreOrderStatus.CANCELLED in allowedTransitions

    AlertDialog(
        onDismissRequest = { if (!isUpdating) onDismiss() },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Order ${order.displayNumber}", fontWeight = FontWeight.Bold)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (order.isConvertedToSale) ConvertedSaleChip()
                    if (order.isPendingSync) PendingSyncChip()
                    OrderStatusBadge(status = order.status, rawStatus = order.rawStatus)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (order.createdAtEpochMs > 0L || order.createdAtIso.isNotBlank()) {
                    val formatted = if (order.createdAtEpochMs > 0L) {
                        timeFormat.format(Date(order.createdAtEpochMs))
                    } else {
                        order.createdAtIso
                    }
                    Text(
                        text = "Placed: $formatted",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!order.customerName.isNullOrBlank()) {
                    Text("Customer: ${order.customerName}", style = MaterialTheme.typography.bodySmall)
                }
                if (!order.customerPhone.isNullOrBlank()) {
                    Text("Phone: ${order.customerPhone}", style = MaterialTheme.typography.bodySmall)
                }
                if (!order.deliveryNote.isNullOrBlank()) {
                    Text("Note: ${order.deliveryNote}", style = MaterialTheme.typography.bodySmall)
                }

                HorizontalDivider()

                Text("Items", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                if (order.items.isEmpty()) {
                    Text(
                        text = "No structured item details attached to this order.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    order.items.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "${item.quantity}× ${item.productName}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "Ksh ${item.lineTotal.roundToInt()}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Order Total", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("Ksh ${order.totalPrice.roundToInt()}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }

                if (allowedTransitions.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        text = "Order Actions",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )

                    if (forwardTransitions.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            forwardTransitions.forEachIndexed { idx, target ->
                                val label = OrderStatusTransitionPolicy.actionLabel(target)
                                if (idx == 0) {
                                    Button(
                                        onClick = { onTransition(target, null) },
                                        enabled = !isUpdating,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                    ) {
                                        if (isUpdating) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                            )
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        Text(label)
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { onTransition(target, null) },
                                        enabled = !isUpdating,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                    ) {
                                        Text(label)
                                    }
                                }
                            }
                        }
                    }

                    if (canCancel) {
                        if (!showCancelConfirm) {
                            TextButton(
                                onClick = {
                                    showCancelConfirm = true
                                    localCancelError = null
                                },
                                enabled = !isUpdating,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Cancel Order", color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            OutlinedTextField(
                                value = cancelReason,
                                onValueChange = {
                                    cancelReason = it
                                    localCancelError = null
                                },
                                label = { Text("Cancellation reason (required)") },
                                singleLine = false,
                                maxLines = 2,
                                isError = localCancelError != null,
                                supportingText = localCancelError?.let { err -> { Text(err) } },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        showCancelConfirm = false
                                        localCancelError = null
                                    },
                                    enabled = !isUpdating,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Back")
                                }
                                Button(
                                    onClick = {
                                        val clean = cancelReason.trim()
                                        if (clean.length < 3) {
                                            localCancelError = "Enter at least 3 characters."
                                        } else {
                                            onTransition(StoreOrderStatus.CANCELLED, clean)
                                            showCancelConfirm = false
                                        }
                                    },
                                    enabled = !isUpdating,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError,
                                    ),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Confirm Cancel")
                                }
                            }
                        }
                    }
                }

                val canConvertToSale = OrderConversionPolicy.canConvertStatus(
                    status = order.status,
                    alreadyConverted = order.isConvertedToSale,
                )
                if (canConvertToSale) {
                    Button(
                        onClick = onOpenConvertToSale,
                        enabled = !isUpdating && order.items.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Convert to Sale & Deduct Stock")
                    }
                    if (order.items.isEmpty()) {
                        Text(
                            text = "This order has no line items available to record as a product sale.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else if (order.isConvertedToSale) {
                    Text(
                        text = "Converted into shared sale & inventory movement.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }

                if (order.isConvertedToSale || order.status == StoreOrderStatus.FULFILLED) {
                    OutlinedButton(
                        onClick = onOpenDigitalReceipt,
                        enabled = !isUpdating,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ReceiptLong,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("View / Share Digital Receipt")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !isUpdating) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun ConvertOrderToSaleDialog(
    order: StoreOrderRecord,
    catalogueProducts: List<ProductIdentity>,
    isSubmitting: Boolean,
    onConfirm: (List<ResolvedOrderSaleLine>, PaymentMethod) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedPayment by remember(order.id) { mutableStateOf(PaymentMethod.MPESA) }
    var linkedProductsByLine by remember(order.id, catalogueProducts) {
        mutableStateOf(
            order.items.mapIndexedNotNull { idx, item ->
                val validPid = item.productId?.takeIf { pid ->
                    catalogueProducts.isEmpty() || catalogueProducts.any { it.id == pid && !it.isArchived }
                }
                validPid?.let { idx to it }
            }.toMap()
        )
    }
    var pickingLineIndex by remember { mutableStateOf<Int?>(null) }
    var productSearch by remember { mutableStateOf("") }

    val catalogueById = remember(catalogueProducts) { catalogueProducts.associateBy { it.id } }

    val resolvedLines = remember(order.items, linkedProductsByLine, catalogueById) {
        order.items.mapIndexed { idx, item ->
            val pid = linkedProductsByLine[idx] ?: -1L
            val prod = if (pid > 0L) catalogueById[pid] else null
            val resolvedUnit = when {
                item.unitPrice > 0.0 -> item.unitPrice
                prod != null && prod.effectiveSellPrice > 0.0 -> prod.effectiveSellPrice
                else -> 0.0
            }
            val resolvedTotal = when {
                item.lineTotal > 0.0 -> item.lineTotal
                resolvedUnit > 0.0 -> resolvedUnit * item.quantity
                else -> 0.0
            }
            ResolvedOrderSaleLine(
                productId = pid,
                productName = prod?.name ?: item.productName,
                quantity = item.quantity,
                unitPrice = resolvedUnit,
                lineTotal = resolvedTotal,
            )
        }
    }

    val validation = remember(order, resolvedLines) {
        OrderConversionPolicy.validateConversion(order, resolvedLines)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Convert Order ${order.displayNumber}",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Creates real shared sale record(s), deducts stock, and marks the order Fulfilled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("Payment Method", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PaymentMethod.entries.forEach { pm ->
                        FilterChip(
                            selected = selectedPayment == pm,
                            onClick = { selectedPayment = pm },
                            label = { Text(pm.label) },
                        )
                    }
                }

                HorizontalDivider()

                Text("Order Items (${resolvedLines.size})", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                resolvedLines.forEachIndexed { idx, line ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "${line.quantity}× ${line.productName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "Ksh ${line.lineTotal.roundToInt()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            if (line.productId > 0L) {
                                Text(
                                    text = "Linked SKU #${line.productId}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Product ID not linked",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    TextButton(
                                        onClick = {
                                            pickingLineIndex = idx
                                            productSearch = order.items[idx].productName
                                        }
                                    ) {
                                        Text("Pick Product")
                                    }
                                }
                            }
                        }
                    }
                }

                if (!validation.isValid && validation.errorMessage != null) {
                    Text(
                        text = validation.errorMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Total Sale", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        text = "Ksh ${resolvedLines.sumOf { it.lineTotal }.roundToInt()}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(resolvedLines, selectedPayment) },
                enabled = validation.isValid && !isSubmitting,
            ) {
                Text(if (isSubmitting) "Converting..." else "Confirm Sale & Fulfill")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) {
                Text("Cancel")
            }
        },
    )

    pickingLineIndex?.let { lineIdx ->
        val filtered = remember(catalogueProducts, productSearch) {
            val q = productSearch.trim().lowercase()
            catalogueProducts
                .filter { !it.isArchived }
                .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.category.lowercase().contains(q) }
                .take(25)
        }
        AlertDialog(
            onDismissRequest = { pickingLineIndex = null },
            title = { Text("Link Order Line to Product") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = productSearch,
                        onValueChange = { productSearch = it },
                        label = { Text("Search catalogue") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Column(
                        modifier = Modifier
                            .height(220.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        filtered.forEach { prod ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        linkedProductsByLine = linkedProductsByLine + (lineIdx to prod.id)
                                        pickingLineIndex = null
                                    },
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = prod.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = "Ksh ${prod.effectiveSellPrice.roundToInt()} · Qty ${prod.stockQty}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickingLineIndex = null }) {
                    Text("Close")
                }
            },
        )
    }
}
