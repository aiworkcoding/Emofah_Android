package com.emofah.agencies.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emofah.agencies.reporting.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionHistoryScreen(
    repository: TransactionHistoryRepository,
    onBackClick: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var transactions by remember { mutableStateOf<List<UnifiedTransactionItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var filterOptions by remember { mutableStateOf(TransactionFilterOptions()) }
    var selectedItemForDetail by remember { mutableStateOf<UnifiedTransactionItem?>(null) }

    fun loadData() {
        scope.launch {
            isLoading = true
            try {
                transactions = repository.fetchAllTransactions()
            } catch (e: Exception) {
                transactions = emptyList()
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadData()
    }

    val filteredTransactions = remember(transactions, filterOptions) {
        TransactionHistoryPolicy.filterTransactions(transactions, filterOptions)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transaction History", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { loadData() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF103c60),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        }
    ) { paddingVals ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingVals)
                .background(Color(0xFFF8FAFC))
        ) {
            // Search & Filters Header
            Surface(
                tonalElevation = 2.dp,
                color = Color.White,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Search bar
                    OutlinedTextField(
                        value = filterOptions.searchQuery,
                        onValueChange = { filterOptions = filterOptions.copy(searchQuery = it) },
                        placeholder = { Text("Search transactions, IDs, or references...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (filterOptions.searchQuery.isNotBlank()) {
                                IconButton(onClick = { filterOptions = filterOptions.copy(searchQuery = "") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Type filter chips
                    ScrollableTabRow(
                        selectedTabIndex = filterOptions.selectedType.ordinal,
                        edgePadding = 0.dp,
                        containerColor = Color.Transparent,
                        divider = {}
                    ) {
                        UnifiedTransactionType.values().forEach { tType ->
                            Tab(
                                selected = filterOptions.selectedType == tType,
                                onClick = { filterOptions = filterOptions.copy(selectedType = tType) },
                                text = {
                                    Text(
                                        text = tType.name,
                                        fontSize = 12.sp,
                                        fontWeight = if (filterOptions.selectedType == tType) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Sync status filter row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Sync:", fontSize = 12.sp, color = Color.Gray)
                        TransactionSyncFilter.values().forEach { sFilter ->
                            FilterChip(
                                selected = filterOptions.syncFilter == sFilter,
                                onClick = { filterOptions = filterOptions.copy(syncFilter = sFilter) },
                                label = { Text(sFilter.name, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }

            // Content List
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (filteredTransactions.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "No transactions found",
                            fontWeight = FontWeight.Bold,
                            color = Color.DarkGray
                        )
                        Text(
                            "Try adjusting your search query or filters.",
                            fontSize = 13.sp,
                            color = Color.Gray
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredTransactions, key = { it.id }) { item ->
                            TransactionCard(item = item) {
                                selectedItemForDetail = item
                            }
                        }
                    }
                }
            }
        }
    }

    // Detail Dialog
    selectedItemForDetail?.let { item ->
        AlertDialog(
            onDismissRequest = { selectedItemForDetail = null },
            title = { Text(item.title) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Type: ${item.type}", fontWeight = FontWeight.Bold)
                    Text("Date: ${item.formattedDate}")
                    Text("Amount: ${item.formattedAmount}")
                    Text("Payment: ${item.paymentMethod}")
                    Text("Sync Status: ${item.syncStatus}")
                    if (item.referenceCode.isNotBlank()) {
                        Text("Reference: ${item.referenceCode}")
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Raw Details:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Surface(
                        color = Color(0xFFF1F5F9),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = item.rawDetails,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(8.dp),
                            color = Color.DarkGray
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedItemForDetail = null }) {
                    Text("Close")
                }
            },
            dismissButton = {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                TextButton(onClick = {
                    val shareIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_SUBJECT, "Emofah Transaction — ${item.title}")
                        putExtra(Intent.EXTRA_TEXT, "Emofah Transaction Record:\n${item.title}\nDate: ${item.formattedDate}\nAmount: ${item.formattedAmount}\nStatus: ${item.syncStatus}\nDetails: ${item.rawDetails}")
                        type = "text/plain"
                    }
                    ctx.startActivity(Intent.createChooser(shareIntent, "Share Transaction"))
                }) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Share")
                }
            }
        )
    }
}

@Composable
fun TransactionCard(
    item: UnifiedTransactionItem,
    onClick: () -> Unit
) {
    val statusColor = when {
        item.syncStatus.contains("CONFIRMED", true) || item.syncStatus.contains("SYNCED", true) -> Color(0xFF10B981)
        item.syncStatus.contains("CONFLICT", true) -> Color(0xFFEF4444)
        else -> Color(0xFFF59E0B)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = when (item.type) {
                            UnifiedTransactionType.SALE -> Color(0xFFE0F2FE)
                            UnifiedTransactionType.SERVICE -> Color(0xFFFEE2E2)
                            UnifiedTransactionType.PURCHASE -> Color(0xFFFEF3C7)
                            UnifiedTransactionType.STOCK -> Color(0xFFF3E8FF)
                            UnifiedTransactionType.MONEY -> Color(0xFFDCFCE7)
                            else -> Color(0xFFF1F5F9)
                        },
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = item.type.name,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = Color(0xFF1E293B)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = item.formattedDate,
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color(0xFF0F172A)
                )
                Text(
                    text = item.subtitle,
                    fontSize = 12.sp,
                    color = Color.DarkGray
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                if (item.amount > 0.0) {
                    Text(
                        text = item.formattedAmount,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color(0xFF0F172A)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = item.syncStatus,
                        fontSize = 10.sp,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
