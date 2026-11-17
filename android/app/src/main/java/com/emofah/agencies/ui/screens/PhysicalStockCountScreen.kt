package com.emofah.agencies.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emofah.agencies.auth.EmofahPermission
import com.emofah.agencies.auth.RolePermissionPolicy
import com.emofah.agencies.data.local.EmofahDatabase
import com.emofah.agencies.sales.StaffRole
import com.emofah.agencies.stock.StockCountItemDraft
import com.emofah.agencies.stock.StockCountReason
import com.emofah.agencies.stock.StockCountRepository
import com.emofah.agencies.stock.StockCountSessionDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhysicalStockCountScreen(
    onNavigateBack: () -> Unit,
    onCountSubmitted: () -> Unit,
    staffRole: StaffRole = StaffRole.CASHIER,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val repo = remember(context) { StockCountRepository(context) }
    val canSubmitCount = remember(staffRole) {
        RolePermissionPolicy.hasPermission(staffRole, EmofahPermission.SUBMIT_PHYSICAL_STOCK_COUNT)
    }

    var sessionName by remember { mutableStateOf("Physical Stock Count - " + java.time.LocalDate.now()) }
    var createdBy by remember { mutableStateOf("Android ${staffRole.label}") }
    var items by remember { mutableStateOf<List<StockCountItemDraft>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isSubmitting by remember { mutableStateOf(false) }

    // Load active server-backed products from Room database with provisional stock projections
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = EmofahDatabase.getInstance(context)
            val prods = db.productDao().getActiveProducts().filter { it.id > 0L }
            val projectionsById = db.productDao().getAllStockProjections().associateBy { it.productId }
            val drafts = prods.map { prod ->
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
            withContext(Dispatchers.Main) {
                items = drafts
                isLoading = false
            }
        }
    }

    val totalItems = items.size
    val totalDiscrepancies = items.count { it.isDiscrepancy }
    val netVariance = items.sumOf { it.variance }

    fun updateItemCount(index: Int, item: StockCountItemDraft, newCounted: Int) {
        val safeCount = newCounted.coerceAtLeast(0)
        val hasDiff = (safeCount - item.systemQty) != 0
        val nextReason = when {
            !hasDiff -> StockCountReason.CORRECT.code
            item.reasonCode == StockCountReason.CORRECT.code -> StockCountReason.INVENTORY_AUDIT.code
            else -> item.reasonCode
        }
        items = items.toMutableList().also {
            it[index] = item.copy(
                countedQty = safeCount,
                reasonCode = nextReason,
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Physical Stock Counting", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF103C60),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Items: $totalItems", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "Discrepancies: $totalDiscrepancies",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (totalDiscrepancies > 0) Color(0xFFD97706) else Color(0xFF059669),
                        )
                        Text(
                            text = "Net Variance: ${if (netVariance >= 0) "+$netVariance" else netVariance}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (isSubmitting || !canSubmitCount) return@Button
                            val draft = StockCountSessionDraft(
                                sessionName = sessionName,
                                createdBy = createdBy,
                                items = items,
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF14558a)),
                        enabled = canSubmitCount && !isSubmitting && !isLoading && items.isNotEmpty(),
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                        } else if (!canSubmitCount) {
                            Icon(Icons.Default.Lock, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("SUPERVISOR / MANAGER SIGN-IN REQUIRED", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        } else {
                            Icon(Icons.Default.CheckCircle, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("SUBMIT & RECONCILE COUNT", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF14558a))
            }
        } else if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No active products found in catalogue cache.", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color(0xFFF8FAFC)),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!canSubmitCount) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    text = "Submitting physical stock counts requires an authenticated Supervisor or Owner/Manager session (Current role: ${staffRole.label}).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = sessionName,
                        onValueChange = { sessionName = it },
                        label = { Text("Count Session Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                item {
                    OutlinedTextField(
                        value = createdBy,
                        onValueChange = { createdBy = it },
                        label = { Text("Auditor / Supervisor Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                item {
                    Text(
                        "Review & Enter Physical Quantities",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF103C60),
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                    )
                }

                itemsIndexed(items) { index, item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = item.productName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                Surface(
                                    color = if (item.isDiscrepancy) Color(0xFFFEF3C7) else Color(0xFFECFDF5),
                                    shape = RoundedCornerShape(4.dp),
                                ) {
                                    Text(
                                        text = if (item.isDiscrepancy) {
                                            "Variance: ${if (item.variance > 0) "+${item.variance}" else item.variance}"
                                        } else {
                                            "Match"
                                        },
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (item.isDiscrepancy) Color(0xFFD97706) else Color(0xFF059669),
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("System Qty: ${item.systemQty}", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
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
                                            val parsed = str.toIntOrNull() ?: 0
                                            updateItemCount(index, item, parsed)
                                        },
                                        modifier = Modifier.width(80.dp),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    )
                                    IconButton(
                                        onClick = {
                                            updateItemCount(index, item, item.countedQty + 1)
                                        },
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = "Increase")
                                    }
                                }
                            }

                            if (item.isDiscrepancy) {
                                Spacer(modifier = Modifier.height(8.dp))
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
                                                    items = items.toMutableList().also {
                                                        it[index] = item.copy(reasonCode = reasonOption.code)
                                                    }
                                                },
                                                label = { Text(reasonOption.label, style = MaterialTheme.typography.labelSmall) },
                                            )
                                        }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = item.note,
                                    onValueChange = { str ->
                                        items = items.toMutableList().also {
                                            it[index] = item.copy(note = str)
                                        }
                                    },
                                    label = { Text("Explanation Note (Required, min 3 chars)") },
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
}
