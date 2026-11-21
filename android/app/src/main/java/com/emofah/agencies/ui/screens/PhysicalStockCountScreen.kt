package com.emofah.agencies.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.emofah.agencies.auth.EmofahPermission
import com.emofah.agencies.auth.RolePermissionPolicy
import com.emofah.agencies.data.local.EmofahDatabase
import com.emofah.agencies.sales.StaffRole
import com.emofah.agencies.stock.StockCountItemDraft
import com.emofah.agencies.stock.StockCountReason
import com.emofah.agencies.stock.StockCountRepository
import com.emofah.agencies.stock.StockCountSessionDraft
import com.emofah.agencies.ui.components.EmofahBadgeTone
import com.emofah.agencies.ui.components.EmofahCard
import com.emofah.agencies.ui.components.EmofahEmptyState
import com.emofah.agencies.ui.components.EmofahFeedbackBanner
import com.emofah.agencies.ui.components.EmofahLoadingButton
import com.emofah.agencies.ui.components.EmofahSectionHeader
import com.emofah.agencies.ui.components.EmofahSkeletonCard
import com.emofah.agencies.ui.components.EmofahStatusBadge
import com.emofah.agencies.ui.theme.emofahColors
import com.emofah.agencies.ui.theme.tabular
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhysicalStockCountScreen(
    onNavigateBack: () -> Unit,
    onCountSubmitted: () -> Unit,
    staffRole: StaffRole = StaffRole.CASHIER,
) {
    val colors = MaterialTheme.emofahColors
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val repo = remember(context) { StockCountRepository(context) }
    val canSubmitCount = remember(staffRole) {
        RolePermissionPolicy.hasPermission(staffRole, EmofahPermission.SUBMIT_PHYSICAL_STOCK_COUNT)
    }

    var sessionName by remember { mutableStateOf("Physical Stock Count - ${LocalDate.now()}") }
    var createdBy by remember { mutableStateOf("Android ${staffRole.label}") }
    val items = remember { mutableStateListOf<StockCountItemDraft>() }
    var isLoading by remember { mutableStateOf(true) }
    var isSubmitting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val drafts = withContext(Dispatchers.IO) {
            val db = EmofahDatabase.getInstance(context)
            val prods = db.productDao().getActiveProducts().filter { it.id > 0L }
            val projectionsById = db.productDao().getAllStockProjections().associateBy { it.productId }
            prods.map { prod ->
                val effectiveQty = (projectionsById[prod.id]?.provisionalAvailableQty ?: prod.serverStockQty)
                    .coerceAtLeast(0)
                StockCountItemDraft(
                    productId = prod.id.toInt(),
                    productName = prod.name,
                    systemQty = effectiveQty,
                    countedQty = effectiveQty,
                    reasonCode = StockCountReason.CORRECT.code,
                    note = "",
                )
            }
        }
        items.clear()
        items.addAll(drafts)
        isLoading = false
    }

    val totalItems by remember { derivedStateOf { items.size } }
    val totalDiscrepancies by remember { derivedStateOf { items.count { it.isDiscrepancy } } }
    val netVariance by remember { derivedStateOf { items.sumOf { it.variance } } }

    fun updateItemCount(index: Int, item: StockCountItemDraft, newCounted: Int) {
        if (index !in items.indices) return
        val safeCount = newCounted.coerceAtLeast(0)
        val hasDiff = (safeCount - item.systemQty) != 0
        val nextReason = when {
            !hasDiff -> StockCountReason.CORRECT.code
            item.reasonCode == StockCountReason.CORRECT.code -> StockCountReason.INVENTORY_AUDIT.code
            else -> item.reasonCode
        }
        items[index] = item.copy(
            countedQty = safeCount,
            reasonCode = nextReason,
        )
    }

    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Physical Stock Count",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary,
                            )
                            Text(
                                text = "Audit physical shelves against system counts",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textMuted,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = colors.textPrimary,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = colors.glassSurface,
                    ),
                )
                HorizontalDivider(color = colors.borderSubtle)
            }
        },
        bottomBar = {
            Surface(
                color = colors.surfaceCard,
                shadowElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    HorizontalDivider(color = colors.borderSubtle)
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Items: $totalItems",
                                style = MaterialTheme.typography.bodySmall.tabular(),
                                color = colors.textMuted,
                            )
                            Text(
                                text = "Discrepancies: $totalDiscrepancies",
                                style = MaterialTheme.typography.bodySmall.tabular(),
                                fontWeight = FontWeight.SemiBold,
                                color = if (totalDiscrepancies > 0) colors.warning else colors.success,
                            )
                            Text(
                                text = "Net: ${if (netVariance >= 0) "+$netVariance" else netVariance}",
                                style = MaterialTheme.typography.bodySmall.tabular(),
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary,
                            )
                        }

                        EmofahLoadingButton(
                            text = "Submit & Reconcile Count",
                            loadingText = "Submitting Count...",
                            isLoading = isSubmitting,
                            enabled = !isLoading && items.isNotEmpty(),
                            icon = Icons.Default.CheckCircle,
                            onClick = {
                                val draft = StockCountSessionDraft(
                                    sessionName = sessionName,
                                    createdBy = createdBy,
                                    items = items.toList(),
                                )
                                scope.launch {
                                    isSubmitting = true
                                    val result = repo.submitStockCount(draft, staffRole = staffRole)
                                    isSubmitting = false
                                    if (result.isSuccess) {
                                        val msg = result.getOrNull().orEmpty()
                                        val label = if (msg.startsWith("OFFLINE_QUEUED")) {
                                            "Stock count saved locally & queued for sync."
                                        } else {
                                            "Stock count submitted & reconciled successfully!"
                                        }
                                        Toast.makeText(context, label, Toast.LENGTH_LONG).show()
                                        onCountSubmitted()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Error: ${result.exceptionOrNull()?.localizedMessage ?: "Failed to submit"}",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (isLoading) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(5, key = { it }) {
                    EmofahSkeletonCard()
                }
            }
        } else if (items.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                EmofahEmptyState(
                    icon = Icons.Outlined.Inventory2,
                    title = "No Active Products",
                    message = "No active server-backed products found in the local catalogue cache.",
                    actionLabel = "Go Back",
                    onAction = onNavigateBack,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(colors.canvas),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "session_meta") {
                    EmofahCard {
                        EmofahSectionHeader(title = "Count Session Details")
                        OutlinedTextField(
                            value = sessionName,
                            onValueChange = { sessionName = it },
                            label = { Text("Count Session Name") },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = createdBy,
                            onValueChange = { createdBy = it },
                            label = { Text("Staff / Counter Name") },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }

                item(key = "items_header") {
                    EmofahSectionHeader(
                        title = "Review Physical Quantities",
                        subtitle = "Adjust any product that differs from system stock",
                    )
                }

                itemsIndexed(
                    items = items,
                    key = { _, item -> item.productId },
                ) { index, item ->
                    EmofahCard(
                        borderColor = if (item.isDiscrepancy) {
                            colors.warning.copy(alpha = 0.45f)
                        } else {
                            colors.borderSubtle
                        },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = item.productName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            EmofahStatusBadge(
                                text = if (item.isDiscrepancy) {
                                    "Variance ${if (item.variance > 0) "+${item.variance}" else item.variance}"
                                } else {
                                    "Match"
                                },
                                tone = if (item.isDiscrepancy) EmofahBadgeTone.WARNING else EmofahBadgeTone.SUCCESS,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "System Qty: ${item.systemQty}",
                                style = MaterialTheme.typography.bodySmall.tabular(),
                                color = colors.textMuted,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                FilledTonalIconButton(
                                    onClick = {
                                        if (item.countedQty > 0) {
                                            updateItemCount(index, item, item.countedQty - 1)
                                        }
                                    },
                                ) {
                                    Icon(Icons.Default.Remove, contentDescription = "Decrease")
                                }
                                OutlinedTextField(
                                    value = item.countedQty.toString(),
                                    onValueChange = { str ->
                                        val parsed = str.filter { it.isDigit() }.toIntOrNull() ?: 0
                                        updateItemCount(index, item, parsed)
                                    },
                                    modifier = Modifier.width(82.dp),
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    textStyle = MaterialTheme.typography.titleSmall.tabular().copy(fontWeight = FontWeight.Bold),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                )
                                FilledTonalIconButton(
                                    onClick = {
                                        updateItemCount(index, item, item.countedQty + 1)
                                    },
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Increase")
                                }
                            }
                        }

                        if (item.isDiscrepancy) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                StockCountReason.entries
                                    .filter { it != StockCountReason.CORRECT }
                                    .forEach { reasonOption ->
                                        FilterChip(
                                            selected = item.reasonCode == reasonOption.code,
                                            onClick = {
                                                items[index] = item.copy(reasonCode = reasonOption.code)
                                            },
                                            label = {
                                                Text(
                                                    text = reasonOption.label,
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            },
                                        )
                                    }
                            }
                            OutlinedTextField(
                                value = item.note,
                                onValueChange = { str ->
                                    items[index] = item.copy(note = str)
                                },
                                label = { Text("Explanation Note (Required, min 3 chars)") },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                    }
                }
            }
        }
    }
}
