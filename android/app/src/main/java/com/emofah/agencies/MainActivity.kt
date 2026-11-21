package com.emofah.agencies

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.emofah.agencies.camera.PreparedCaptureResult
import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.catalog.ProductRegistrationRepository
import com.emofah.agencies.data.catalog.RegisterProductOutcome
import com.emofah.agencies.data.catalog.SupabaseProductRepository
import com.emofah.agencies.data.local.entities.LocalStockProjectionEntity
import com.emofah.agencies.data.local.entities.PendingFinancialEventEntity
import com.emofah.agencies.data.sync.ConflictManager
import com.emofah.agencies.data.sync.ConflictResolutionOutcome
import com.emofah.agencies.data.sync.OfflineStatusSummary
import com.emofah.agencies.data.sync.ReliabilityHealthSnapshot
import com.emofah.agencies.data.sync.ReliabilityRecoveryManager
import com.emofah.agencies.data.sync.SyncQueueProcessor
import com.emofah.agencies.data.sync.SyncScheduler
import com.emofah.agencies.data.sync.SyncTelemetryStore
import com.emofah.agencies.recognition.MatchConfidenceStatus
import com.emofah.agencies.recognition.OnDeviceProductRecognizer
import com.emofah.agencies.recognition.RecognitionOutcome
import com.emofah.agencies.sales.AndroidConnectivityChecker
import com.emofah.agencies.sales.DeviceIdentityProvider
import com.emofah.agencies.sales.PaymentMethod
import com.emofah.agencies.sales.RecordSaleOutcome
import com.emofah.agencies.sales.ReverseSaleOutcome
import com.emofah.agencies.sales.SaleHistoryMapper
import com.emofah.agencies.sales.SaleHistoryRepository
import com.emofah.agencies.sales.SalePreferencesStore
import com.emofah.agencies.sales.SaleTransactionRepository
import com.emofah.agencies.financial.FinancialMonitoringPrefs
import com.emofah.agencies.financial.ManualFinancialOutcome
import com.emofah.agencies.financial.MpesaCaptureOutcome
import com.emofah.agencies.financial.MpesaInflowAttributionPolicy
import com.emofah.agencies.financial.MpesaInflowAttributionStore
import com.emofah.agencies.financial.MpesaInflowLinkTarget
import com.emofah.agencies.financial.MpesaMonitoringRepository
import com.emofah.agencies.stock.StockAdjustmentDraft
import com.emofah.agencies.stock.StockAdjustmentRepository
import com.emofah.agencies.stock.StockAdjustmentResult
import com.emofah.agencies.stock.StockAlertPolicy
import com.emofah.agencies.stock.StockLookupPolicy
import com.emofah.agencies.stock.StockLookupRepository
import com.emofah.agencies.stock.StockLookupSnapshot
import com.emofah.agencies.stock.StockReceiptDraft
import com.emofah.agencies.stock.StockReceivingRepository
import com.emofah.agencies.stock.StockReceivingResult
import com.emofah.agencies.ui.navigation.EmofahDestination
import com.emofah.agencies.ui.screens.ActivitySyncScreen
import com.emofah.agencies.ui.screens.CameraCaptureScreen
import com.emofah.agencies.ui.screens.HomeScreen
import com.emofah.agencies.ui.screens.MoneyScreen
import com.emofah.agencies.ui.screens.OrdersScreen
import com.emofah.agencies.ui.screens.ReceiveStockScreen
import com.emofah.agencies.ui.screens.RecognitionResultScreen
import com.emofah.agencies.ui.screens.RecordSaleScreen
import com.emofah.agencies.ui.screens.SaleFeedbackKind
import com.emofah.agencies.ui.screens.SaleFeedbackUi
import com.emofah.agencies.ui.screens.StockAdjustmentScreen
import com.emofah.agencies.ui.screens.StockLookupScreen
import com.emofah.agencies.ui.screens.PhysicalStockCountScreen
import com.emofah.agencies.orders.OrderConversionRepository
import com.emofah.agencies.orders.OrderConversionResult
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WarningAmber
import com.emofah.agencies.audit.AuditRecordUi
import com.emofah.agencies.audit.AuditSyncRepository
import com.emofah.agencies.audit.RemoteAuditFetchOutcome
import com.emofah.agencies.auth.AuthActionOutcome
import com.emofah.agencies.auth.AuthBootstrapOutcome
import com.emofah.agencies.auth.AuthOtpResult
import com.emofah.agencies.auth.AuthStatus
import com.emofah.agencies.auth.EmofahPermission
import com.emofah.agencies.auth.PermissionCheckResult
import com.emofah.agencies.auth.RolePermissionPolicy
import com.emofah.agencies.orders.OrderFeedSnapshot
import com.emofah.agencies.orders.OrderManagementRepository
import com.emofah.agencies.orders.OrderStatusUpdateResult
import com.emofah.agencies.orders.SupabaseOrderRepository
import com.emofah.agencies.notifications.EmofahNotificationManager
import com.emofah.agencies.notifications.NotificationEvaluator
import com.emofah.agencies.purchases.PurchaseReceiptDraft
import com.emofah.agencies.purchases.PurchaseRepository
import com.emofah.agencies.purchases.PurchaseSubmissionResult
import com.emofah.agencies.purchases.SupabaseReceiptExtractionClient
import com.emofah.agencies.receipts.DigitalReceiptFormatter
import com.emofah.agencies.reporting.DailyActivitySummaryCalculator
import com.emofah.agencies.services.ServiceOperationRepository
import com.emofah.agencies.services.ServiceOperationResult
import com.emofah.agencies.orders.StoreOrderStatus
import com.emofah.agencies.ui.components.EmofahBadgeTone
import com.emofah.agencies.ui.components.EmofahMotion
import com.emofah.agencies.ui.components.EmofahStatusBadge
import com.emofah.agencies.ui.components.LocalReducedMotion
import com.emofah.agencies.ui.components.emofahPressScale
import com.emofah.agencies.ui.components.rememberReducedMotionEnabled
import com.emofah.agencies.ui.screens.ReceiptCaptureReviewScreen
import com.emofah.agencies.ui.screens.ServiceOperationsScreen
import com.emofah.agencies.ui.screens.StaffSignInDialog
import com.emofah.agencies.ui.screens.UnknownProductRegistrationScreen
import com.emofah.agencies.ui.theme.EmofahTheme
import com.emofah.agencies.ui.theme.emofahColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.time.LocalDate
import java.time.ZoneId

private enum class ScanPurpose {
    RECORD_SALE,
    STOCK_LOOKUP,
    RECEIVE_STOCK,
    ADJUST_STOCK,
    RECEIPT_CAPTURE,
    STOCK_REGISTER_PHOTO,
}

class MainActivity : ComponentActivity() {
    private var pendingNotificationRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingNotificationRoute = intent?.getStringExtra(EmofahNotificationManager.EXTRA_TARGET_ROUTE)
        enableEdgeToEdge()
        setContent {
            val reducedMotion = rememberReducedMotionEnabled()
            CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
                EmofahTheme {
                    EmofahAppShell(
                        pendingNotificationRoute = pendingNotificationRoute,
                        onConsumeNotificationRoute = { pendingNotificationRoute = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingNotificationRoute = intent.getStringExtra(EmofahNotificationManager.EXTRA_TARGET_ROUTE)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmofahAppShell(
    pendingNotificationRoute: String? = null,
    onConsumeNotificationRoute: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as EmofahApplication
    val scope = rememberCoroutineScope()

    val repository = remember(app) { SupabaseProductRepository(app, app.database) }
    val connectivityChecker = remember(app) { AndroidConnectivityChecker(app) }
    val recognizer = remember(app, repository, connectivityChecker) {
        OnDeviceProductRecognizer(
            context = app,
            database = app.database,
            repository = repository,
            connectivityChecker = connectivityChecker,
            onCorrectionQueued = { SyncScheduler.enqueueImmediateSync(app) },
        )
    }
    val identityProvider = remember(app) { DeviceIdentityProvider(app) }
    val salePrefs = remember(app) { SalePreferencesStore(app) }
    val syncTelemetry = remember(app) { SyncTelemetryStore(app) }
    val conflictManager = remember(app) { ConflictManager(app.database) }
    val saleRepository = remember(app, connectivityChecker, identityProvider) {
        SaleTransactionRepository(
            database = app.database,
            identityProvider = identityProvider,
            connectivityChecker = connectivityChecker,
            onOfflineEventQueued = { SyncScheduler.enqueueImmediateSync(app) },
        )
    }
    val saleHistoryRepository = remember(app, connectivityChecker, identityProvider) {
        SaleHistoryRepository(
            database = app.database,
            identityProvider = identityProvider,
            connectivityChecker = connectivityChecker,
            onOfflineEventQueued = { SyncScheduler.enqueueImmediateSync(app) },
        )
    }
    val stockLookupRepository = remember(app, connectivityChecker) {
        StockLookupRepository(
            database = app.database,
            connectivityChecker = connectivityChecker,
        )
    }
    val stockReceivingRepository = remember(app, connectivityChecker, identityProvider) {
        StockReceivingRepository(
            database = app.database,
            identityProvider = identityProvider,
            connectivityChecker = connectivityChecker,
            onOfflineEventQueued = { SyncScheduler.enqueueImmediateSync(app) },
        )
    }
    val stockAdjustmentRepository = remember(app, connectivityChecker, identityProvider) {
        StockAdjustmentRepository(
            database = app.database,
            identityProvider = identityProvider,
            connectivityChecker = connectivityChecker,
            onOfflineEventQueued = { SyncScheduler.enqueueImmediateSync(app) },
        )
    }
    val productRegistrationRepository = remember(app, repository, identityProvider, connectivityChecker) {
        ProductRegistrationRepository(
            context = app,
            database = app.database,
            repository = repository,
            identityProvider = identityProvider,
            connectivityChecker = connectivityChecker,
            onOfflineEventQueued = { SyncScheduler.enqueueImmediateSync(app) },
        )
    }
    val financialMonitoringPrefs = remember(app) { FinancialMonitoringPrefs(app) }
    val mpesaAttributionStore = remember(app) { MpesaInflowAttributionStore(app) }
    val mpesaMonitoringRepository = remember(app, financialMonitoringPrefs) {
        MpesaMonitoringRepository(
            database = app.database,
            monitoringPrefs = financialMonitoringPrefs,
            onOfflineEventQueued = { SyncScheduler.enqueueImmediateSync(app) },
        )
    }
    val receiptExtractionClient = remember { SupabaseReceiptExtractionClient() }
    val purchaseRepository = remember(app, repository, identityProvider, connectivityChecker) {
        PurchaseRepository(
            database = app.database,
            productRepository = repository,
            identityProvider = identityProvider,
            connectivityChecker = connectivityChecker,
            onOfflineEventQueued = { SyncScheduler.enqueueImmediateSync(app) },
        )
    }
    val serviceOperationRepository = remember(app, identityProvider, connectivityChecker) {
        ServiceOperationRepository(
            database = app.database,
            identityProvider = identityProvider,
            connectivityChecker = connectivityChecker,
            onOfflineEventQueued = { SyncScheduler.enqueueImmediateSync(app) },
        )
    }
    val orderRepository = remember(app, connectivityChecker) {
        SupabaseOrderRepository(
            context = app,
            connectivityChecker = connectivityChecker,
        )
    }
    val orderManagementRepository = remember(app, orderRepository, identityProvider, connectivityChecker) {
        OrderManagementRepository(
            database = app.database,
            orderRepository = orderRepository,
            identityProvider = identityProvider,
            connectivityChecker = connectivityChecker,
            onOfflineEventQueued = { SyncScheduler.enqueueImmediateSync(app) },
        )
    }
    val orderConversionRepository = remember(app, orderRepository, identityProvider, connectivityChecker) {
        OrderConversionRepository(
            database = app.database,
            orderRepository = orderRepository,
            identityProvider = identityProvider,
            connectivityChecker = connectivityChecker,
            onOfflineEventQueued = { SyncScheduler.enqueueImmediateSync(app) },
        )
    }
    val syncQueueProcessor = remember(app, connectivityChecker, orderRepository) {
        SyncQueueProcessor(
            database = app.database,
            connectivityChecker = connectivityChecker,
            onOrderUpdateReconciled = { orderId, confirmedRawStatus ->
                orderRepository.clearPendingStatusOverlay(orderId, confirmedRawStatus)
            },
            onOrderConversionReconciled = { orderId, synced ->
                if (synced) {
                    orderRepository.markOrderConverted(orderId, markPendingOverlay = false)
                } else {
                    orderRepository.unmarkOrderConverted(orderId)
                }
            },
        )
    }
    val auditSyncRepository = remember(app, identityProvider, connectivityChecker) {
        AuditSyncRepository(
            database = app.database,
            identityProvider = identityProvider,
            connectivityChecker = connectivityChecker,
        )
    }
    val reliabilityRecoveryManager = remember(app, identityProvider) {
        ReliabilityRecoveryManager(
            context = app,
            database = app.database,
            identityProvider = identityProvider,
        )
    }
    val authRepository = app.authSessionRepository
    val authState by authRepository.uiState.collectAsState()
    var showAuthDialog by remember { mutableStateOf(false) }

    var isDeviceOnline by remember { mutableStateOf(connectivityChecker.isOnline()) }
    DisposableEffect(app, connectivityChecker, scope) {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch(Dispatchers.Main.immediate) {
                    isDeviceOnline = connectivityChecker.isOnline()
                    authRepository.refreshUiStateFromStoreAsync()
                }
            }

            override fun onLost(network: Network) {
                scope.launch(Dispatchers.Main.immediate) {
                    isDeviceOnline = connectivityChecker.isOnline()
                    authRepository.refreshUiStateFromStoreAsync()
                }
            }
        }
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { cm?.unregisterNetworkCallback(callback) } }
    }

    val cachedProducts by repository.observeCachedProducts().collectAsState(initial = emptyList())
    val allQueueEvents by app.database.syncQueueDao()
        .observeAllEvents()
        .collectAsState(initial = emptyList())
    val offlineSummary = remember(isDeviceOnline, allQueueEvents) {
        OfflineStatusSummary.fromQueue(isOnline = isDeviceOnline, events = allQueueEvents)
    }
    var isManualSyncing by remember { mutableStateOf(false) }
    var syncCheckpoint by remember { mutableStateOf(syncTelemetry.readCheckpoint()) }
    var syncUiMessage by remember { mutableStateOf<String?>(null) }

    val startOfTodayEpochMs = remember {
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    val allSalesWithItems by saleHistoryRepository.observeAllSalesWithItems()
        .collectAsState(initial = emptyList())
    val (todaySalesUi, earlierSalesUi) = remember(allSalesWithItems, startOfTodayEpochMs) {
        SaleHistoryMapper.partitionTodayAndEarlier(allSalesWithItems, startOfTodayEpochMs)
    }
    var isReversingSale by remember { mutableStateOf(false) }

    val cachedStockProjections by stockLookupRepository.observeAllStockProjections()
        .collectAsState(initial = emptyList())
    var stockAlertThreshold by remember { mutableStateOf(StockLookupPolicy.DEFAULT_LOW_STOCK_THRESHOLD) }
    var isAlertThresholdFromServer by remember { mutableStateOf(false) }
    val stockAlertSummary = remember(
        cachedProducts,
        cachedStockProjections,
        stockAlertThreshold,
        isAlertThresholdFromServer,
    ) {
        StockAlertPolicy.buildSummary(
            products = cachedProducts,
            projections = cachedStockProjections,
            threshold = stockAlertThreshold,
            isConfiguredFromServer = isAlertThresholdFromServer,
        )
    }
    var selectedStockSnapshot by remember { mutableStateOf<StockLookupSnapshot?>(null) }
    var isLoadingStockDetail by remember { mutableStateOf(false) }
    var scanPurpose by remember { mutableStateOf(ScanPurpose.RECORD_SALE) }

    val todaySalesCount by app.database.saleDao()
        .observeTodaySalesCount(startOfTodayEpochMs)
        .collectAsState(initial = 0)
    val todaySalesTotal by app.database.saleDao()
        .observeTodaySalesTotal(startOfTodayEpochMs)
        .collectAsState(initial = 0.0)
    val pendingSyncCount by app.database.syncQueueDao()
        .observePendingCount()
        .collectAsState(initial = 0)
    val conflictCount by app.database.syncQueueDao()
        .observeConflictCount()
        .collectAsState(initial = 0)
    val financialEvents by mpesaMonitoringRepository.observePendingFinancialEvents()
        .collectAsState(initial = emptyList())
    val mpesaLinks by mpesaAttributionStore.linksFlow.collectAsState()
    val latestUnlinkedMpesaInflow = remember(financialEvents, mpesaLinks) {
        MpesaInflowAttributionPolicy.findLatestUnlinkedInflow(financialEvents, mpesaLinks)
    }
    var activeMpesaSaleEvent by remember { mutableStateOf<PendingFinancialEventEntity?>(null) }
    var activeMpesaServiceEvent by remember { mutableStateOf<PendingFinancialEventEntity?>(null) }
    var financialMonitoringState by remember { mutableStateOf(financialMonitoringPrefs.readState()) }
    var financialUiMessage by remember { mutableStateOf<String?>(null) }

    val dailySummary = remember(
        startOfTodayEpochMs,
        allSalesWithItems,
        financialEvents,
        pendingSyncCount,
        conflictCount,
    ) {
        DailyActivitySummaryCalculator.compute(
            startOfTodayEpochMs = startOfTodayEpochMs,
            salesWithItems = allSalesWithItems,
            financialEvents = financialEvents,
            pendingSyncCount = pendingSyncCount,
            conflictCount = conflictCount,
        )
    }

    val pendingAuditCount by app.database.auditDao()
        .observePendingAuditCount()
        .collectAsState(initial = 0)
    val recentLocalAudits by app.database.auditDao()
        .observeRecentAudits(limit = 250)
        .collectAsState(initial = emptyList())
    var remoteAuditRecords by remember { mutableStateOf<List<AuditRecordUi>>(emptyList()) }
    var isSyncingAudits by remember { mutableStateOf(false) }
    var isLoadingRemoteAudits by remember { mutableStateOf(false) }
    var auditStatusMessage by remember { mutableStateOf<String?>(null) }
    var reliabilitySnapshot by remember { mutableStateOf<ReliabilityHealthSnapshot?>(null) }
    var isRunningRecovery by remember { mutableStateOf(false) }
    var recoveryStatusMessage by remember { mutableStateOf<String?>(null) }

    var isRefreshingOrders by remember { mutableStateOf(false) }
    var isUpdatingOrder by remember { mutableStateOf(false) }
    var orderActionMessage by remember { mutableStateOf<String?>(null) }
    var orderFeedSnapshot by remember {
        mutableStateOf(
            OrderFeedSnapshot(
                orders = emptyList(),
                isFromCache = true,
                statusMessage = null,
            )
        )
    }

    LaunchedEffect(orderRepository, cachedProducts.size) {
        if (orderFeedSnapshot.lastFetchedEpochMs == null && orderFeedSnapshot.orders.isEmpty()) {
            orderFeedSnapshot = orderRepository.readCachedSnapshotAsync(cachedProducts)
        }
    }

    LaunchedEffect(repository, syncQueueProcessor, stockLookupRepository, orderRepository, authRepository, auditSyncRepository, reliabilityRecoveryManager) {
        // Yield after first frame so initial Home UI renders instantaneously before background I/O.
        yield()
        val startupReport = reliabilityRecoveryManager.runStartupRecovery()
        reliabilitySnapshot = startupReport.snapshotAfter
        authRepository.ensureFreshSessionIfOnline()
        yield()
        repository.syncCatalogueAndRebuildIndex()
        stockLookupRepository.fetchConfiguredThresholdOrNull()?.let { configured ->
            stockAlertThreshold = configured
            isAlertThresholdFromServer = true
        }
        val summary = syncQueueProcessor.processPendingQueue()
        if (summary.authBlockedCount > 0) {
            authRepository.handleServerAuthRejected(
                "Session rejected during startup sync. Please sign in again."
            )
        }
        if (!summary.skippedOffline && summary.authBlockedCount == 0) {
            auditSyncRepository.flushPendingAudits()
        }
        EmofahNotificationManager.notifySyncAttentionIfNeeded(app, summary)
        val fetchedOrders = orderRepository.fetchOrders(
            catalogueProducts = cachedProducts,
            forceRefresh = true,
        )
        orderFeedSnapshot = fetchedOrders
        if (!fetchedOrders.isFromCache) {
            EmofahNotificationManager.notifyNewOrdersIfNeeded(app, fetchedOrders.orders)
        }
        syncTelemetry.recordAttempt(System.currentTimeMillis(), summary)
        syncCheckpoint = withContext(Dispatchers.IO) { syncTelemetry.readCheckpoint() }
        reliabilitySnapshot = reliabilityRecoveryManager.inspectHealthSnapshot()
        if (summary.retryableCount > 0 || summary.skippedOffline) {
            SyncScheduler.enqueueImmediateSync(app)
        }
    }

    LaunchedEffect(cachedProducts.size, stockAlertSummary.totalAlertsCount) {
        if (cachedProducts.isNotEmpty()) {
            EmofahNotificationManager.notifyStockAlertsIfNeeded(app, stockAlertSummary)
        }
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: EmofahDestination.Home.route
    val currentDestination = EmofahDestination.entries.firstOrNull { it.route == currentRoute }
        ?: EmofahDestination.Home

    var activeCapture by remember { mutableStateOf<PreparedCaptureResult?>(null) }
    var isRecognizing by remember { mutableStateOf(false) }
    var isCheckingCloud by remember { mutableStateOf(false) }
    var recognitionOutcome by remember { mutableStateOf<RecognitionOutcome?>(null) }
    var confirmedSaleProduct by remember { mutableStateOf<ProductIdentity?>(null) }
    var selectedStockProjection by remember { mutableStateOf<LocalStockProjectionEntity?>(null) }
    var selectedProductPhotoPath by remember { mutableStateOf<String?>(null) }
    var selectedPaymentMethod by remember { mutableStateOf(salePrefs.readLastPaymentMethod()) }
    var autoReturnToScan by remember { mutableStateOf(salePrefs.readAutoReturnToScan()) }
    var isSubmittingSale by remember { mutableStateOf(false) }
    var saleSessionNonce by remember { mutableStateOf(0) }
    var saleFeedback by remember { mutableStateOf<SaleFeedbackUi?>(null) }
    var registerUnknownDraft by remember { mutableStateOf<Triple<String, String, String?>?>(null) }
    var isRegisteringProduct by remember { mutableStateOf(false) }
    var registrationOutcome by remember { mutableStateOf<RegisterProductOutcome?>(null) }
    var isReceivingStockMode by remember { mutableStateOf(false) }
    var stockReceivingProduct by remember { mutableStateOf<ProductIdentity?>(null) }
    var stockReceivingProjection by remember { mutableStateOf<LocalStockProjectionEntity?>(null) }
    var stockReceivingPhotoPath by remember { mutableStateOf<String?>(null) }
    var isSubmittingReceipt by remember { mutableStateOf(false) }
    var stockReceiptOutcome by remember { mutableStateOf<StockReceivingResult?>(null) }

    var isAdjustingStockMode by remember { mutableStateOf(false) }
    var stockAdjustingProduct by remember { mutableStateOf<ProductIdentity?>(null) }
    var stockAdjustingProjection by remember { mutableStateOf<LocalStockProjectionEntity?>(null) }
    var stockAdjustingPhotoPath by remember { mutableStateOf<String?>(null) }
    var isSubmittingAdjustment by remember { mutableStateOf(false) }
    var stockAdjustmentOutcome by remember { mutableStateOf<StockAdjustmentResult?>(null) }
    var isPhysicalCountingMode by remember { mutableStateOf(false) }
    var stockRegisterDraft by remember { mutableStateOf<Triple<String, String, String?>?>(null) }

    var isReceiptCaptureMode by remember { mutableStateOf(false) }
    var receiptDraft by remember { mutableStateOf(PurchaseReceiptDraft()) }
    var isExtractingReceipt by remember { mutableStateOf(false) }
    var receiptExtractionError by remember { mutableStateOf<String?>(null) }
    var isSubmittingPurchase by remember { mutableStateOf(false) }
    var recordPurchaseOutflow by remember { mutableStateOf(true) }
    var purchaseSubmissionResult by remember { mutableStateOf<PurchaseSubmissionResult?>(null) }

    var isServiceOperationsMode by remember { mutableStateOf(false) }
    var isSubmittingService by remember { mutableStateOf(false) }
    var serviceOperationResult by remember { mutableStateOf<ServiceOperationResult?>(null) }

    LaunchedEffect(confirmedSaleProduct?.id) {
        val pid = confirmedSaleProduct?.id
        if (pid != null) {
            selectedStockProjection = app.database.productDao().getStockProjection(pid)
            selectedProductPhotoPath = app.database.productDao()
                .getReferencePhotosForProduct(pid)
                .firstOrNull { it.isValid && it.localCachePath != null }
                ?.localCachePath
        } else {
            selectedStockProjection = null
            selectedProductPhotoPath = null
        }
    }

    LaunchedEffect(stockReceivingProduct?.id) {
        val pid = stockReceivingProduct?.id
        if (pid != null) {
            stockReceivingProjection = app.database.productDao().getStockProjection(pid)
            stockReceivingPhotoPath = app.database.productDao()
                .getReferencePhotosForProduct(pid)
                .firstOrNull { it.isValid && it.localCachePath != null }
                ?.localCachePath
        } else {
            stockReceivingProjection = null
            stockReceivingPhotoPath = null
        }
    }

    LaunchedEffect(stockAdjustingProduct?.id) {
        val pid = stockAdjustingProduct?.id
        if (pid != null) {
            stockAdjustingProjection = app.database.productDao().getStockProjection(pid)
            stockAdjustingPhotoPath = app.database.productDao()
                .getReferencePhotosForProduct(pid)
                .firstOrNull { it.isValid && it.localCachePath != null }
                ?.localCachePath
        } else {
            stockAdjustingProjection = null
            stockAdjustingPhotoPath = null
        }
    }

    fun navigateTo(dest: EmofahDestination) {
        navController.navigate(dest.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun useMpesaForSale(event: PendingFinancialEventEntity) {
        val existing = mpesaAttributionStore.findLink(event)
        if (existing != null) {
            financialUiMessage = "M-Pesa payment is already linked (${existing.summary})."
            return
        }
        activeMpesaSaleEvent = event
        selectedPaymentMethod = PaymentMethod.MPESA
        salePrefs.saveLastPaymentMethod(PaymentMethod.MPESA)
        saleFeedback = null
        isServiceOperationsMode = false
        navigateTo(EmofahDestination.RecordSale)
    }

    fun useMpesaForService(event: PendingFinancialEventEntity) {
        val existing = mpesaAttributionStore.findLink(event)
        if (existing != null) {
            financialUiMessage = "M-Pesa payment is already linked (${existing.summary})."
            return
        }
        activeMpesaServiceEvent = event
        serviceOperationResult = null
        isServiceOperationsMode = true
        navigateTo(EmofahDestination.Home)
    }

    fun dismissMpesaInflow(event: PendingFinancialEventEntity) {
        val linked = mpesaAttributionStore.tryRecordLink(
            event = event,
            target = MpesaInflowLinkTarget.DISMISSED,
            linkedRecordId = "dismissed",
            summary = "Not a shop sale or service",
        )
        if (activeMpesaSaleEvent?.eventId == event.eventId) activeMpesaSaleEvent = null
        if (activeMpesaServiceEvent?.eventId == event.eventId) activeMpesaServiceEvent = null
        financialUiMessage = if (linked) {
            "M-Pesa payment marked as other / not a sale."
        } else {
            "M-Pesa payment was already handled."
        }
    }

    LaunchedEffect(pendingNotificationRoute) {
        val targetDest = NotificationEvaluator.resolveDestinationRoute(pendingNotificationRoute)
        if (targetDest != null) {
            navigateTo(targetDest)
            onConsumeNotificationRoute()
        }
    }

    val reducedMotion = LocalReducedMotion.current
    val activeOrdersCount = remember(orderFeedSnapshot.orders) {
        orderFeedSnapshot.orders.count {
            it.status == StoreOrderStatus.PENDING || it.status == StoreOrderStatus.CONFIRMED || it.status == StoreOrderStatus.READY
        }
    }
    val attentionSyncCount = pendingSyncCount + conflictCount

    val hasActiveSubMode = when (currentDestination) {
        EmofahDestination.Home -> isServiceOperationsMode
        EmofahDestination.Scan -> registerUnknownDraft != null || activeCapture != null
        EmofahDestination.Stock -> stockRegisterDraft != null || isPhysicalCountingMode || isAdjustingStockMode || isReceivingStockMode
        EmofahDestination.Money -> isReceiptCaptureMode
        else -> false
    }

    BackHandler(enabled = hasActiveSubMode) {
        when (currentDestination) {
            EmofahDestination.Home -> if (isServiceOperationsMode) isServiceOperationsMode = false
            EmofahDestination.Scan -> {
                if (registerUnknownDraft != null) {
                    registerUnknownDraft = null
                    registrationOutcome = null
                } else if (activeCapture != null) {
                    activeCapture = null
                    recognitionOutcome = null
                    isRecognizing = false
                    isCheckingCloud = false
                }
            }
            EmofahDestination.Stock -> {
                when {
                    stockRegisterDraft != null -> {
                        stockRegisterDraft = null
                        registrationOutcome = null
                    }
                    isPhysicalCountingMode -> isPhysicalCountingMode = false
                    isAdjustingStockMode -> isAdjustingStockMode = false
                    isReceivingStockMode -> isReceivingStockMode = false
                }
            }
            EmofahDestination.Money -> if (isReceiptCaptureMode) isReceiptCaptureMode = false
            else -> Unit
        }
    }

    val colors = MaterialTheme.emofahColors

    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            EmofahTopAppBar(
                currentDestination = currentDestination,
                isOnline = isDeviceOnline,
                authStatus = authState.status,
                activeOrdersCount = activeOrdersCount,
                attentionSyncCount = attentionSyncCount,
                onOpenAuth = { showAuthDialog = true },
                onOpenOrders = { navigateTo(EmofahDestination.Orders) },
                onOpenActivity = { navigateTo(EmofahDestination.Activity) },
            )
        },
        bottomBar = {
            EmofahBottomNavigationBar(
                currentDestination = currentDestination,
                onSelectDestination = { dest ->
                    if (dest == EmofahDestination.Home) {
                        isServiceOperationsMode = false
                    }
                    if (dest == EmofahDestination.Scan) {
                        scanPurpose = ScanPurpose.RECORD_SALE
                    }
                    if (dest == EmofahDestination.Stock) {
                        isReceivingStockMode = false
                        isAdjustingStockMode = false
                        isPhysicalCountingMode = false
                        stockRegisterDraft = null
                    }
                    if (dest == EmofahDestination.Money) {
                        isReceiptCaptureMode = false
                    }
                    navigateTo(dest)
                },
            )
        },
    ) { innerPadding ->
        val transitionDuration = if (reducedMotion) 0 else EmofahMotion.QUICK_MS
        NavHost(
            navController = navController,
            startDestination = EmofahDestination.Home.route,
            modifier = Modifier.padding(innerPadding),
            enterTransition = {
                fadeIn(tween(transitionDuration)) +
                    slideInHorizontally(tween(transitionDuration)) { it / 18 }
            },
            exitTransition = {
                fadeOut(tween(transitionDuration))
            },
            popEnterTransition = {
                fadeIn(tween(transitionDuration)) +
                    slideInHorizontally(tween(transitionDuration)) { -it / 18 }
            },
            popExitTransition = {
                fadeOut(tween(transitionDuration))
            },
        ) {
            composable(EmofahDestination.Home.route) {
                if (isServiceOperationsMode) {
                    ServiceOperationsScreen(
                        cachedProducts = cachedProducts,
                        recentServiceEvents = financialEvents.filter { it.source == "ANDROID_SERVICE" },
                        isSubmitting = isSubmittingService,
                        lastResult = serviceOperationResult,
                        prefilledMpesaAmount = activeMpesaServiceEvent?.amount,
                        prefilledMpesaRef = activeMpesaServiceEvent?.transactionReference,
                        prefilledMpesaSender = activeMpesaServiceEvent?.counterparty,
                        onClearPrefilledMpesa = { activeMpesaServiceEvent = null },
                        onSubmitService = { draft ->
                            if (isSubmittingService) return@ServiceOperationsScreen
                            isSubmittingService = true
                            val linkedMpesa = activeMpesaServiceEvent
                            scope.launch {
                                val outcome = serviceOperationRepository.recordServiceOperation(draft)
                                serviceOperationResult = outcome
                                if (linkedMpesa != null &&
                                    (outcome is ServiceOperationResult.SuccessOnline ||
                                        outcome is ServiceOperationResult.SavedOffline)
                                ) {
                                    val recId = when (outcome) {
                                        is ServiceOperationResult.SuccessOnline -> outcome.serverServiceId
                                        is ServiceOperationResult.SavedOffline -> outcome.clientServiceId
                                        else -> draft.clientServiceId
                                    }
                                    mpesaAttributionStore.tryRecordLink(
                                        event = linkedMpesa,
                                        target = MpesaInflowLinkTarget.SERVICE,
                                        linkedRecordId = recId,
                                        summary = "${draft.serviceType.label} · Ksh ${draft.effectiveRevenue.toInt()}",
                                    )
                                    activeMpesaServiceEvent = null
                                }
                                isSubmittingService = false
                            }
                        },
                        onBack = {
                            isServiceOperationsMode = false
                        },
                    )
                } else {
                    HomeScreen(
                        todaySalesCount = todaySalesCount,
                        todaySalesTotal = todaySalesTotal,
                        pendingSyncCount = pendingSyncCount,
                        cachedProductCount = cachedProducts.size,
                        dailySummary = dailySummary,
                        onNavigate = { dest ->
                            if (dest == EmofahDestination.Scan) {
                                scanPurpose = ScanPurpose.RECORD_SALE
                            }
                            if (dest == EmofahDestination.Stock) {
                                isReceivingStockMode = false
                            }
                            navigateTo(dest)
                        },
                        onOpenReceiveStock = {
                            isReceivingStockMode = true
                            stockReceiptOutcome = null
                            navigateTo(EmofahDestination.Stock)
                        },
                        onOpenScanReceipt = {
                            receiptDraft = PurchaseReceiptDraft()
                            receiptExtractionError = null
                            purchaseSubmissionResult = null
                            isReceiptCaptureMode = true
                            navigateTo(EmofahDestination.Money)
                        },
                        onOpenServices = {
                            serviceOperationResult = null
                            isServiceOperationsMode = true
                        },
                        lowStockAlertCount = stockAlertSummary.lowStockItems.size,
                        outOfStockCount = stockAlertSummary.outOfStockItems.size,
                        authState = authState,
                        onOpenAuth = { showAuthDialog = true },
                        unlinkedMpesaInflow = latestUnlinkedMpesaInflow,
                        onUseMpesaForSale = ::useMpesaForSale,
                        onUseMpesaForService = ::useMpesaForService,
                        onDismissMpesaInflow = ::dismissMpesaInflow,
                    )
                }
            }
            composable(EmofahDestination.Scan.route) {
                val unknownDraft = registerUnknownDraft
                if (unknownDraft != null) {
                    UnknownProductRegistrationScreen(
                        preservedPhotoPath = unknownDraft.first.takeIf { it.isNotBlank() },
                        extractedOcrText = unknownDraft.second,
                        extractedCloudLabel = unknownDraft.third,
                        existingProducts = cachedProducts,
                        isSubmitting = isRegisteringProduct,
                        lastOutcome = registrationOutcome,
                        onSubmitRegistration = { draftInput, allowNearDuplicate ->
                            if (isRegisteringProduct) return@UnknownProductRegistrationScreen
                            isRegisteringProduct = true
                            scope.launch {
                                registrationOutcome = productRegistrationRepository.registerProduct(
                                    input = draftInput,
                                    allowNearDuplicateOverride = allowNearDuplicate,
                                )
                                isRegisteringProduct = false
                            }
                        },
                        onUseExistingProduct = { existing ->
                            confirmedSaleProduct = existing
                            saleFeedback = null
                            registerUnknownDraft = null
                            registrationOutcome = null
                            navigateTo(EmofahDestination.RecordSale)
                        },
                        onSellRegisteredProduct = { registered ->
                            confirmedSaleProduct = registered
                            saleFeedback = null
                            registerUnknownDraft = null
                            registrationOutcome = null
                            navigateTo(EmofahDestination.RecordSale)
                        },
                        onCancel = {
                            registerUnknownDraft = null
                            registrationOutcome = null
                        },
                    )
                } else if (activeCapture == null) {
                    val isReceiptScan = scanPurpose == ScanPurpose.RECEIPT_CAPTURE
                    CameraCaptureScreen(
                        framingHint = if (isReceiptScan) {
                            "Align full purchase receipt inside frame · Tap to focus"
                        } else {
                            "Place one product inside frame · Tap to focus"
                        },
                        captureButtonLabel = if (isReceiptScan) "Capture Receipt" else "Capture Product",
                        reviewTitle = if (isReceiptScan) "Review Receipt Photo" else "Review Captured Photo",
                        confirmButtonLabel = if (isReceiptScan) "Extract Receipt Items" else "Identify Product",
                        onCancel = {
                            val returnTarget = when (scanPurpose) {
                                ScanPurpose.STOCK_LOOKUP, ScanPurpose.RECEIVE_STOCK, ScanPurpose.ADJUST_STOCK, ScanPurpose.STOCK_REGISTER_PHOTO -> {
                                    scanPurpose = ScanPurpose.RECORD_SALE
                                    EmofahDestination.Stock
                                }
                                ScanPurpose.RECEIPT_CAPTURE -> {
                                    scanPurpose = ScanPurpose.RECORD_SALE
                                    isReceiptCaptureMode = true
                                    EmofahDestination.Money
                                }
                                else -> EmofahDestination.Home
                            }
                            navigateTo(returnTarget)
                        },
                        onCaptureReady = { capture ->
                            if (scanPurpose == ScanPurpose.STOCK_REGISTER_PHOTO) {
                                scanPurpose = ScanPurpose.RECORD_SALE
                                activeCapture = null
                                val existingHint = stockRegisterDraft?.third
                                scope.launch {
                                    val ocrOutcome = runCatching { recognizer.recognizeOffline(capture) }.getOrNull()
                                    stockRegisterDraft = Triple(
                                        capture.originalPath,
                                        ocrOutcome?.extractedOcrText.orEmpty(),
                                        existingHint,
                                    )
                                    navigateTo(EmofahDestination.Stock)
                                }
                            } else if (scanPurpose == ScanPurpose.RECEIPT_CAPTURE) {
                                scanPurpose = ScanPurpose.RECORD_SALE
                                activeCapture = null
                                isReceiptCaptureMode = true
                                isExtractingReceipt = true
                                receiptExtractionError = null
                                purchaseSubmissionResult = null
                                receiptDraft = PurchaseReceiptDraft(photoPath = capture.originalPath)
                                navigateTo(EmofahDestination.Money)
                                scope.launch {
                                    val result = runCatching {
                                        receiptExtractionClient.extractReceipt(
                                            capture = capture,
                                            catalogueProducts = cachedProducts,
                                            isOnline = connectivityChecker.isOnline(),
                                        )
                                    }
                                    result.onSuccess { extracted ->
                                        receiptDraft = extracted.copy(isHumanConfirmed = false)
                                    }.onFailure { err ->
                                        receiptExtractionError =
                                            err.message ?: "Could not extract receipt items automatically."
                                    }
                                    isExtractingReceipt = false
                                }
                            } else {
                                activeCapture = capture
                                isRecognizing = true
                                isCheckingCloud = false
                                recognitionOutcome = null
                                scope.launch {
                                    val localOutcome = recognizer.recognizeOffline(capture)
                                    recognitionOutcome = localOutcome
                                    isRecognizing = false
                                    if (localOutcome.status == MatchConfidenceStatus.UNIDENTIFIED &&
                                        connectivityChecker.isOnline()
                                    ) {
                                        isCheckingCloud = true
                                        recognitionOutcome = recognizer.recognizeWithCloudFallback(
                                            capture = capture,
                                            localOutcome = localOutcome,
                                            forceRequested = false,
                                        )
                                        isCheckingCloud = false
                                    }
                                }
                            }
                        },
                    )
                } else {
                    RecognitionResultScreen(
                        isLoading = isRecognizing,
                        isCheckingCloud = isCheckingCloud,
                        isDeviceOnline = isDeviceOnline,
                        outcome = recognitionOutcome,
                        fallbackProducts = cachedProducts,
                        onConfirmProduct = { product, origin, allowImageLearning ->
                            val confirmedOutcome = recognitionOutcome
                            scope.launch {
                                recognizer.recordConfirmedSelection(
                                    outcome = confirmedOutcome,
                                    confirmedProduct = product,
                                    actorId = identityProvider.getActorId(),
                                    deviceId = identityProvider.getDeviceId(),
                                    selectionOrigin = origin,
                                    allowImageAssociation = allowImageLearning,
                                    isHumanConfirmed = true,
                                )
                            }
                            activeCapture = null
                            recognitionOutcome = null
                            isCheckingCloud = false
                            if (scanPurpose == ScanPurpose.STOCK_LOOKUP) {
                                scanPurpose = ScanPurpose.RECORD_SALE
                                isLoadingStockDetail = true
                                isReceivingStockMode = false
                                isAdjustingStockMode = false
                                navigateTo(EmofahDestination.Stock)
                                scope.launch {
                                    selectedStockSnapshot = stockLookupRepository.lookupProductStock(
                                        productId = product.id,
                                        refreshFromServer = true,
                                    )
                                    isLoadingStockDetail = false
                                }
                            } else if (scanPurpose == ScanPurpose.RECEIVE_STOCK) {
                                scanPurpose = ScanPurpose.RECORD_SALE
                                stockReceivingProduct = product
                                isReceivingStockMode = true
                                isAdjustingStockMode = false
                                stockReceiptOutcome = null
                                navigateTo(EmofahDestination.Stock)
                            } else if (scanPurpose == ScanPurpose.ADJUST_STOCK) {
                                scanPurpose = ScanPurpose.RECORD_SALE
                                stockAdjustingProduct = product
                                isAdjustingStockMode = true
                                isReceivingStockMode = false
                                stockAdjustmentOutcome = null
                                navigateTo(EmofahDestination.Stock)
                            } else {
                                confirmedSaleProduct = product
                                saleFeedback = null
                                navigateTo(EmofahDestination.RecordSale)
                            }
                        },
                        onRetryCloudMatch = {
                            val capture = activeCapture
                            val current = recognitionOutcome
                            if (capture != null && current != null && !isCheckingCloud) {
                                isCheckingCloud = true
                                scope.launch {
                                    recognitionOutcome = recognizer.recognizeWithCloudFallback(
                                        capture = capture,
                                        localOutcome = current,
                                        forceRequested = true,
                                    )
                                    isCheckingCloud = false
                                }
                            }
                        },
                        onRetakePhoto = {
                            activeCapture = null
                            recognitionOutcome = null
                            isRecognizing = false
                            isCheckingCloud = false
                        },
                        onUnidentifiedRoute = { preservedPath, ocr ->
                            val cloudLabel = recognitionOutcome?.extractedCloudLabel
                            registerUnknownDraft = Triple(preservedPath, ocr, cloudLabel)
                            registrationOutcome = null
                            activeCapture = null
                            recognitionOutcome = null
                            isCheckingCloud = false
                        },
                    )
                }
            }
            composable(EmofahDestination.RecordSale.route) {
                RecordSaleScreen(
                    selectedProduct = confirmedSaleProduct,
                    stockProjection = selectedStockProjection,
                    cachedPhotoPath = selectedProductPhotoPath,
                    cachedProducts = cachedProducts,
                    selectedPaymentMethod = selectedPaymentMethod,
                    staffRole = authState.activeRole,
                    autoReturnToScan = autoReturnToScan,
                    isSubmitting = isSubmittingSale,
                    saleSessionNonce = saleSessionNonce,
                    lastSaleFeedback = saleFeedback,
                    pendingMpesaEvent = activeMpesaSaleEvent,
                    onClearPendingMpesa = { activeMpesaSaleEvent = null },
                    onSelectPaymentMethod = { method ->
                        selectedPaymentMethod = method
                        salePrefs.saveLastPaymentMethod(method)
                    },
                    onToggleAutoReturnToScan = { enabled ->
                        autoReturnToScan = enabled
                        salePrefs.saveAutoReturnToScan(enabled)
                    },
                    onSelectProductManually = { product ->
                        confirmedSaleProduct = product
                        saleFeedback = null
                    },
                    onScanAnother = {
                        saleFeedback = null
                        navigateTo(EmofahDestination.Scan)
                    },
                    onRecordSaleClick = { draft ->
                        if (isSubmittingSale) return@RecordSaleScreen
                        isSubmittingSale = true
                        val linkedMpesa = activeMpesaSaleEvent
                        val originalSellPrice =
                            confirmedSaleProduct?.sellPrice ?: draft.unitSellPrice
                        scope.launch {
                            val outcome = saleRepository.recordSale(
                                draft = draft,
                                originalSellPrice = originalSellPrice,
                            )
                            var linkedMpesaNote = ""
                            if (linkedMpesa != null &&
                                (outcome is RecordSaleOutcome.SuccessOnline ||
                                    outcome is RecordSaleOutcome.SavedOffline)
                            ) {
                                val recId = when (outcome) {
                                    is RecordSaleOutcome.SuccessOnline -> outcome.serverSaleId.toString()
                                    is RecordSaleOutcome.SavedOffline -> outcome.clientSaleId
                                    else -> draft.clientSaleId
                                }
                                val linkedOk = mpesaAttributionStore.tryRecordLink(
                                    event = linkedMpesa,
                                    target = MpesaInflowLinkTarget.SALE,
                                    linkedRecordId = recId,
                                    summary = "${draft.productName} x${draft.quantity}",
                                )
                                if (linkedOk) {
                                    linkedMpesaNote = " Linked to M-Pesa ${linkedMpesa.transactionReference ?: "payment"}."
                                }
                                activeMpesaSaleEvent = null
                            }
                            selectedStockProjection =
                                app.database.productDao().getStockProjection(draft.productId)
                            val builtReceipt = DigitalReceiptFormatter.buildFromSaleOutcome(
                                outcome = outcome,
                                draft = draft,
                                actorId = identityProvider.getActorId(),
                            )
                            saleFeedback = when (outcome) {
                                is RecordSaleOutcome.SuccessOnline -> {
                                    saleSessionNonce += 1
                                    SaleFeedbackUi(
                                        kind = SaleFeedbackKind.ONLINE_CONFIRMED,
                                        title = if (outcome.alreadyCommitted) {
                                            "Sale Already Saved · #${outcome.serverSaleId}"
                                        } else {
                                            "Sale Saved Online · #${outcome.serverSaleId}"
                                        },
                                        detail = "Ksh ${outcome.totalAmount.toInt()} (${outcome.paymentMethod.label}) saved.$linkedMpesaNote Stock left: ${outcome.remainingServerQty}.",
                                        receipt = builtReceipt,
                                    )
                                }

                                is RecordSaleOutcome.SavedOffline -> {
                                    saleSessionNonce += 1
                                    SaleFeedbackUi(
                                        kind = SaleFeedbackKind.OFFLINE_QUEUED,
                                        title = "Saved on Phone · Will Upload Online",
                                        detail = "Ksh ${outcome.totalAmount.toInt()} (${outcome.paymentMethod.label}) saved on this phone.$linkedMpesaNote Stock left (est.): ${outcome.provisionalRemainingQty}. ${outcome.reason}",
                                        receipt = builtReceipt,
                                    )
                                }

                                is RecordSaleOutcome.DuplicateIgnored -> {
                                    saleSessionNonce += 1
                                    SaleFeedbackUi(
                                        kind = SaleFeedbackKind.ONLINE_CONFIRMED,
                                        title = "Already Recorded",
                                        detail = "This sale (${outcome.clientSaleId.take(8)}) is already saved (${outcome.syncStatus}).",
                                    )
                                }

                                is RecordSaleOutcome.Rejected -> {
                                    SaleFeedbackUi(
                                        kind = SaleFeedbackKind.REJECTED,
                                        title = "Sale Not Saved",
                                        detail = outcome.message,
                                    )
                                }
                            }
                            isSubmittingSale = false
                            if (autoReturnToScan && (outcome is RecordSaleOutcome.SuccessOnline || outcome is RecordSaleOutcome.SavedOffline)) {
                                saleFeedback = null
                                navigateTo(EmofahDestination.Scan)
                            }
                        }
                    },
                )
            }
            composable(EmofahDestination.Stock.route) {
                val activeStockRegDraft = stockRegisterDraft
                if (activeStockRegDraft != null) {
                    UnknownProductRegistrationScreen(
                        preservedPhotoPath = activeStockRegDraft.first.takeIf { it.isNotBlank() },
                        extractedOcrText = activeStockRegDraft.second,
                        extractedCloudLabel = activeStockRegDraft.third,
                        existingProducts = cachedProducts,
                        isSubmitting = isRegisteringProduct,
                        lastOutcome = registrationOutcome,
                        headerSubtitle = "Add a new shop item. Opening stock updates shared shop inventory and saves on this phone if offline.",
                        backButtonLabel = "Back to Stock Lookup",
                        primarySuccessLabelBuilder = { prod -> "Open ${prod.name} in Stock Lookup" },
                        secondarySuccessLabelBuilder = { _ -> "Receive Additional Units" },
                        onCapturePhotoClick = {
                            scanPurpose = ScanPurpose.STOCK_REGISTER_PHOTO
                            activeCapture = null
                            navigateTo(EmofahDestination.Scan)
                        },
                        onSubmitRegistration = { draftInput, allowNearDuplicate ->
                            if (isRegisteringProduct) return@UnknownProductRegistrationScreen
                            isRegisteringProduct = true
                            scope.launch {
                                registrationOutcome = productRegistrationRepository.registerProduct(
                                    input = draftInput,
                                    allowNearDuplicateOverride = allowNearDuplicate,
                                )
                                isRegisteringProduct = false
                            }
                        },
                        onUseExistingProduct = { existing ->
                            stockRegisterDraft = null
                            registrationOutcome = null
                            isReceivingStockMode = false
                            isAdjustingStockMode = false
                            isPhysicalCountingMode = false
                            isLoadingStockDetail = true
                            scope.launch {
                                selectedStockSnapshot = stockLookupRepository.lookupProductStock(
                                    productId = existing.id,
                                    refreshFromServer = true,
                                )
                                isLoadingStockDetail = false
                            }
                        },
                        onSellRegisteredProduct = { registered ->
                            stockRegisterDraft = null
                            registrationOutcome = null
                            isReceivingStockMode = false
                            isAdjustingStockMode = false
                            isPhysicalCountingMode = false
                            isLoadingStockDetail = true
                            scope.launch {
                                selectedStockSnapshot = stockLookupRepository.lookupProductStock(
                                    productId = registered.id,
                                    refreshFromServer = true,
                                )
                                isLoadingStockDetail = false
                            }
                        },
                        onSecondarySuccessAction = { registered ->
                            stockRegisterDraft = null
                            registrationOutcome = null
                            stockReceivingProduct = registered
                            stockReceiptOutcome = null
                            isReceivingStockMode = true
                            isAdjustingStockMode = false
                            isPhysicalCountingMode = false
                        },
                        onCancel = {
                            stockRegisterDraft = null
                            registrationOutcome = null
                        },
                    )
                } else if (isPhysicalCountingMode) {
                    PhysicalStockCountScreen(
                        onNavigateBack = { isPhysicalCountingMode = false },
                        onCountSubmitted = { isPhysicalCountingMode = false },
                        staffRole = authState.activeRole,
                    )
                } else if (isAdjustingStockMode) {
                    StockAdjustmentScreen(
                        selectedProduct = stockAdjustingProduct,
                        stockProjection = stockAdjustingProjection,
                        cachedPhotoPath = stockAdjustingPhotoPath,
                        cachedProducts = cachedProducts,
                        staffRole = authState.activeRole,
                        isSubmitting = isSubmittingAdjustment,
                        lastOutcome = stockAdjustmentOutcome,
                        onScanToSelect = {
                            scanPurpose = ScanPurpose.ADJUST_STOCK
                            navigateTo(EmofahDestination.Scan)
                        },
                        onSelectProductManually = { product ->
                            stockAdjustingProduct = product
                            stockAdjustmentOutcome = null
                        },
                        onClearSelectedProduct = {
                            stockAdjustingProduct = null
                        },
                        onSubmitAdjustment = { draft ->
                            if (isSubmittingAdjustment) return@StockAdjustmentScreen
                            isSubmittingAdjustment = true
                            scope.launch {
                                val outcome = stockAdjustmentRepository.adjustStock(
                                    draft = draft,
                                    staffRole = authState.activeRole,
                                )
                                stockAdjustmentOutcome = outcome
                                isSubmittingAdjustment = false
                                stockAdjustingProjection =
                                    app.database.productDao().getStockProjection(draft.productId)
                            }
                        },
                        onBackToLookup = {
                            isAdjustingStockMode = false
                        },
                    )
                } else if (isReceivingStockMode) {
                    ReceiveStockScreen(
                        selectedProduct = stockReceivingProduct,
                        stockProjection = stockReceivingProjection,
                        cachedPhotoPath = stockReceivingPhotoPath,
                        cachedProducts = cachedProducts,
                        isSubmitting = isSubmittingReceipt,
                        lastOutcome = stockReceiptOutcome,
                        onScanToSelect = {
                            scanPurpose = ScanPurpose.RECEIVE_STOCK
                            navigateTo(EmofahDestination.Scan)
                        },
                        onSelectProductManually = { product ->
                            stockReceivingProduct = product
                            stockReceiptOutcome = null
                        },
                        onClearSelectedProduct = {
                            stockReceivingProduct = null
                        },
                        onSubmitReceipt = { draft ->
                            if (isSubmittingReceipt) return@ReceiveStockScreen
                            isSubmittingReceipt = true
                            scope.launch {
                                val outcome = stockReceivingRepository.receiveStock(draft)
                                stockReceiptOutcome = outcome
                                isSubmittingReceipt = false
                                stockReceivingProjection =
                                    app.database.productDao().getStockProjection(draft.productId)
                            }
                        },
                        onScanNext = {
                            stockReceivingProduct = null
                            stockReceiptOutcome = null
                            scanPurpose = ScanPurpose.RECEIVE_STOCK
                            navigateTo(EmofahDestination.Scan)
                        },
                        onBackToLookup = {
                            isReceivingStockMode = false
                        },
                        onRegisterNewProduct = { hint ->
                            stockRegisterDraft = Triple("", "", hint.takeIf { it.isNotBlank() })
                            registrationOutcome = null
                            isReceivingStockMode = false
                        },
                    )
                } else {
                    StockLookupScreen(
                        cachedProducts = cachedProducts,
                        stockProjections = cachedStockProjections,
                        selectedSnapshot = selectedStockSnapshot,
                        isLoadingDetail = isLoadingStockDetail,
                        isDeviceOnline = isDeviceOnline,
                        onScanForStock = {
                            scanPurpose = ScanPurpose.STOCK_LOOKUP
                            navigateTo(EmofahDestination.Scan)
                        },
                        onSelectProduct = { product ->
                            isLoadingStockDetail = true
                            scope.launch {
                                selectedStockSnapshot = stockLookupRepository.lookupProductStock(
                                    productId = product.id,
                                    refreshFromServer = true,
                                )
                                isLoadingStockDetail = false
                            }
                        },
                        onRefreshSelected = { productId ->
                            isLoadingStockDetail = true
                            scope.launch {
                                selectedStockSnapshot = stockLookupRepository.lookupProductStock(
                                    productId = productId,
                                    refreshFromServer = true,
                                )
                                isLoadingStockDetail = false
                            }
                        },
                        onClearSelection = {
                            selectedStockSnapshot = null
                        },
                        onSellProduct = { product ->
                            confirmedSaleProduct = product
                            saleFeedback = null
                            navigateTo(EmofahDestination.RecordSale)
                        },
                        onOpenReceiveStock = { product ->
                            stockReceivingProduct = product
                            isReceivingStockMode = true
                            isAdjustingStockMode = false
                            isPhysicalCountingMode = false
                            stockReceiptOutcome = null
                        },
                        onOpenAdjustStock = { product ->
                            stockAdjustingProduct = product
                            isAdjustingStockMode = true
                            isReceivingStockMode = false
                            isPhysicalCountingMode = false
                            stockAdjustmentOutcome = null
                        },
                        onOpenPhysicalCount = {
                            isPhysicalCountingMode = true
                            isReceivingStockMode = false
                            isAdjustingStockMode = false
                        },
                        onRegisterNewProduct = { hint ->
                            stockRegisterDraft = Triple("", "", hint.takeIf { it.isNotBlank() })
                            registrationOutcome = null
                        },
                        alertSummary = stockAlertSummary,
                    )
                }
            }
            composable(EmofahDestination.Money.route) {
                if (isReceiptCaptureMode) {
                    ReceiptCaptureReviewScreen(
                        draft = receiptDraft,
                        cachedProducts = cachedProducts,
                        isExtracting = isExtractingReceipt,
                        extractionError = receiptExtractionError,
                        onSnapReceipt = {
                            scanPurpose = ScanPurpose.RECEIPT_CAPTURE
                            activeCapture = null
                            purchaseSubmissionResult = null
                            navigateTo(EmofahDestination.Scan)
                        },
                        onUpdateDraft = { updated ->
                            receiptDraft = updated
                        },
                        onResetBlank = {
                            receiptDraft = PurchaseReceiptDraft()
                            receiptExtractionError = null
                            purchaseSubmissionResult = null
                        },
                        onBack = {
                            isReceiptCaptureMode = false
                        },
                        isSubmitting = isSubmittingPurchase,
                        recordFinancialOutflow = recordPurchaseOutflow,
                        onToggleFinancialOutflow = { recordPurchaseOutflow = it },
                        submissionResult = purchaseSubmissionResult,
                        onSubmitPurchase = {
                            if (isSubmittingPurchase) return@ReceiptCaptureReviewScreen
                            isSubmittingPurchase = true
                            scope.launch {
                                purchaseSubmissionResult = purchaseRepository.submitConfirmedPurchase(
                                    draft = receiptDraft,
                                    recordFinancialOutflow = recordPurchaseOutflow,
                                )
                                isSubmittingPurchase = false
                            }
                        },
                        onScanNextReceipt = {
                            receiptDraft = PurchaseReceiptDraft()
                            receiptExtractionError = null
                            purchaseSubmissionResult = null
                            scanPurpose = ScanPurpose.RECEIPT_CAPTURE
                            activeCapture = null
                            navigateTo(EmofahDestination.Scan)
                        },
                    )
                } else {
                    MoneyScreen(
                        monitoringState = financialMonitoringState,
                        financialEvents = financialEvents,
                        statusMessage = financialUiMessage,
                        isSyncing = isManualSyncing,
                        mpesaInflowLinks = mpesaLinks,
                        onUseMpesaForSale = ::useMpesaForSale,
                        onUseMpesaForService = ::useMpesaForService,
                        onDismissMpesaInflow = ::dismissMpesaInflow,
                        onGrantConsent = {
                            financialMonitoringState = financialMonitoringPrefs.grantConsent(
                                actorId = identityProvider.getActorId()
                            )
                            financialUiMessage = "M-Pesa message reading turned on."
                        },
                        onRevokeConsent = {
                            financialMonitoringState = financialMonitoringPrefs.revokeConsent()
                            financialUiMessage = "M-Pesa message reading turned off."
                        },
                        onPermissionResult = {
                            financialMonitoringState = financialMonitoringPrefs.readState()
                        },
                        onCapturePastedMpesa = { sender, body ->
                            scope.launch {
                                val outcome = mpesaMonitoringRepository.processIncomingSms(
                                    sender = sender,
                                    body = body,
                                    actorId = identityProvider.getActorId(),
                                    deviceId = identityProvider.getDeviceId(),
                                    requireConsentCheck = false,
                                    allowManualPasteOverride = true,
                                )
                                financialMonitoringState = financialMonitoringPrefs.readState()
                                financialUiMessage = when (outcome) {
                                    is MpesaCaptureOutcome.Captured -> {
                                        val evt = outcome.event
                                        if (MpesaInflowAttributionPolicy.isMpesaInflowEvent(evt)) {
                                            "M-Pesa Received: Ksh ${evt.amount.toInt()} (${evt.transactionReference ?: "M-Pesa"}) saved. Choose Sale or Service below."
                                        } else {
                                            "Saved M-Pesa ${evt.direction} Ksh ${evt.amount.toInt()} (${evt.transactionReference ?: "M-Pesa"})."
                                        }
                                    }
                                    is MpesaCaptureOutcome.DuplicateIgnored ->
                                        "Already saved: ${outcome.referenceCode} is already in your list."
                                    is MpesaCaptureOutcome.IgnoredNonFinancial ->
                                        "Not saved: ${outcome.reason}"
                                    MpesaCaptureOutcome.ConsentNotGranted ->
                                        "Turn on M-Pesa reading first."
                                }
                            }
                        },
                        onRecordManualEvent = { draft ->
                            scope.launch {
                                val outcome = mpesaMonitoringRepository.recordManualFinancialEvent(
                                    draft.copy(
                                        actorId = identityProvider.getActorId(),
                                        deviceId = identityProvider.getDeviceId(),
                                    )
                                )
                                financialUiMessage = when (outcome) {
                                    is ManualFinancialOutcome.SavedLocally ->
                                        "Saved Ksh ${outcome.event.amount.toInt()} (${outcome.event.direction}). Will sync online automatically."
                                    is ManualFinancialOutcome.Rejected ->
                                        outcome.message
                                }
                            }
                        },
                        onSyncNow = {
                            if (isManualSyncing) return@MoneyScreen
                            isManualSyncing = true
                            scope.launch {
                                val result = syncQueueProcessor.processPendingQueue()
                                syncTelemetry.recordAttempt(System.currentTimeMillis(), result)
                                syncCheckpoint = syncTelemetry.readCheckpoint()
                                financialUiMessage = when {
                                    result.skippedOffline -> "No internet — saved records will upload automatically once online."
                                    result.syncedCount > 0 -> "Uploaded ${result.syncedCount} saved record(s)."
                                    result.conflictCount > 0 -> "Sync finished with ${result.conflictCount} issue(s). Check Activity tab."
                                    else -> syncCheckpoint.lastMessage ?: "Everything is up to date."
                                }
                                isManualSyncing = false
                            }
                        },
                        onOpenReceiptScanner = {
                            receiptExtractionError = null
                            isReceiptCaptureMode = true
                        },
                    )
                }
            }
            composable(EmofahDestination.Orders.route) {
                OrdersScreen(
                    snapshot = orderFeedSnapshot,
                    isRefreshing = isRefreshingOrders,
                    isDeviceOnline = isDeviceOnline,
                    catalogueProducts = cachedProducts,
                    actionMessage = orderActionMessage,
                    isUpdatingOrder = isUpdatingOrder,
                    onRefresh = {
                        if (isRefreshingOrders) return@OrdersScreen
                        isRefreshingOrders = true
                        orderActionMessage = null
                        scope.launch {
                            val refreshed = orderRepository.fetchOrders(
                                catalogueProducts = cachedProducts,
                                forceRefresh = true,
                            )
                            orderFeedSnapshot = refreshed
                            if (!refreshed.isFromCache) {
                                EmofahNotificationManager.notifyNewOrdersIfNeeded(app, refreshed.orders)
                            }
                            isRefreshingOrders = false
                        }
                    },
                    onUpdateOrderStatus = { order, targetStatus, reason ->
                        if (isUpdatingOrder) return@OrdersScreen
                        isUpdatingOrder = true
                        scope.launch {
                            when (val res = orderManagementRepository.updateOrderStatus(order, targetStatus, reason)) {
                                is OrderStatusUpdateResult.SuccessOnline -> {
                                    orderActionMessage = "Order ${order.displayNumber} updated to ${res.newStatus.label}."
                                }
                                is OrderStatusUpdateResult.SavedOffline -> {
                                    orderActionMessage = "Order ${order.displayNumber} marked ${res.newStatus.label} offline (${res.reason})"
                                }
                                is OrderStatusUpdateResult.DuplicateIgnored -> {
                                    orderActionMessage = "Order update already recorded (${res.syncStatus})."
                                }
                                is OrderStatusUpdateResult.Rejected -> {
                                    orderActionMessage = res.message
                                }
                            }
                            orderFeedSnapshot = orderRepository.readCachedSnapshotAsync(
                                catalogueProducts = cachedProducts,
                                reason = orderFeedSnapshot.statusMessage,
                            )
                            isUpdatingOrder = false
                        }
                    },
                    onConvertOrderToSale = { order, resolvedLines, paymentMethod ->
                        if (isUpdatingOrder) return@OrdersScreen
                        isUpdatingOrder = true
                        scope.launch {
                            when (val res = orderConversionRepository.convertOrderToSale(order, resolvedLines, paymentMethod)) {
                                is OrderConversionResult.SuccessOnline -> {
                                    orderActionMessage = "Order ${order.displayNumber} converted to shared sale (Ksh ${res.totalAmount.toInt()})."
                                }
                                is OrderConversionResult.SavedOffline -> {
                                    orderActionMessage = "Order ${order.displayNumber} sale queued offline (${res.reason})"
                                }
                                is OrderConversionResult.DuplicateIgnored -> {
                                    orderActionMessage = res.message
                                }
                                is OrderConversionResult.Rejected -> {
                                    orderActionMessage = res.message
                                }
                            }
                            orderFeedSnapshot = orderRepository.readCachedSnapshotAsync(
                                catalogueProducts = cachedProducts,
                                reason = orderFeedSnapshot.statusMessage,
                            )
                            isUpdatingOrder = false
                        }
                    },
                )
            }
            composable(EmofahDestination.Activity.route) {
                ActivitySyncScreen(
                    todaySales = todaySalesUi,
                    earlierSales = earlierSalesUi,
                    staffRole = authState.activeRole,
                    summary = offlineSummary,
                    checkpoint = syncCheckpoint,
                    dailySummary = dailySummary,
                    authState = authState,
                    onOpenAuthDialog = { showAuthDialog = true },
                    onRefreshSession = {
                        scope.launch {
                            when (val outcome = authRepository.ensureFreshSessionIfOnline(forceRefresh = true)) {
                                is AuthActionOutcome.Refreshed ->
                                    syncUiMessage = "Staff session refreshed for ${outcome.session.email}."
                                is AuthActionOutcome.Rejected ->
                                    syncUiMessage = outcome.message
                                is AuthActionOutcome.TransientFailure ->
                                    syncUiMessage = outcome.message
                                else ->
                                    syncUiMessage = "Session check complete."
                            }
                        }
                    },
                    onSignOut = {
                        scope.launch {
                            val res = authRepository.signOut()
                            syncUiMessage = if (res.hadPendingSyncItems) {
                                "Signed out. Offline queued records stay safe locally until next sign-in."
                            } else {
                                "Signed out of Emofah staff account."
                            }
                        }
                    },
                    localAudits = recentLocalAudits,
                    remoteAudits = remoteAuditRecords,
                    pendingAuditCount = pendingAuditCount,
                    isSyncingAudits = isSyncingAudits,
                    isLoadingRemoteAudits = isLoadingRemoteAudits,
                    auditStatusMessage = auditStatusMessage,
                    onSyncAuditsNow = {
                        if (isSyncingAudits) return@ActivitySyncScreen
                        isSyncingAudits = true
                        scope.launch {
                            authRepository.ensureFreshSessionIfOnline()
                            val flushRes = auditSyncRepository.flushPendingAudits()
                            auditStatusMessage = flushRes.message
                            isSyncingAudits = false
                        }
                    },
                    onLoadRemoteAudits = {
                        if (isLoadingRemoteAudits) return@ActivitySyncScreen
                        isLoadingRemoteAudits = true
                        scope.launch {
                            authRepository.ensureFreshSessionIfOnline()
                            when (val res = auditSyncRepository.fetchRemoteAudits(limit = 120)) {
                                is RemoteAuditFetchOutcome.Success -> {
                                    remoteAuditRecords = res.records
                                    val scopeLabel = if (res.scope == "STORE_WIDE") "store-wide" else "personal"
                                    auditStatusMessage = "Loaded ${res.records.size} $scopeLabel cloud audit record(s)."
                                }
                                is RemoteAuditFetchOutcome.AuthRequired -> {
                                    auditStatusMessage = res.message
                                }
                                is RemoteAuditFetchOutcome.RpcMissing -> {
                                    auditStatusMessage = res.message
                                }
                                is RemoteAuditFetchOutcome.Error -> {
                                    auditStatusMessage = res.message
                                }
                            }
                            isLoadingRemoteAudits = false
                        }
                    },
                    reliabilitySnapshot = reliabilitySnapshot,
                    isRunningRecovery = isRunningRecovery,
                    recoveryStatusMessage = recoveryStatusMessage,
                    onRunRecoveryCleanup = {
                        if (isRunningRecovery) return@ActivitySyncScreen
                        isRunningRecovery = true
                        scope.launch {
                            val report = reliabilityRecoveryManager.runManualRecoveryAndCleanup()
                            reliabilitySnapshot = report.snapshotAfter
                            recoveryStatusMessage = report.summaryMessage
                            isRunningRecovery = false
                        }
                    },
                    isSyncing = isManualSyncing,
                    isReversingSale = isReversingSale,
                    lastActionMessage = syncUiMessage,
                    onSyncNow = {
                        if (isManualSyncing) return@ActivitySyncScreen
                        isManualSyncing = true
                        isDeviceOnline = connectivityChecker.isOnline()
                        scope.launch {
                            authRepository.ensureFreshSessionIfOnline()
                            val result = syncQueueProcessor.processPendingQueue()
                            if (result.authBlockedCount > 0) {
                                authRepository.handleServerAuthRejected(
                                    "Server rejected session during manual sync. Sign in again to continue."
                                )
                            }
                            syncTelemetry.recordAttempt(System.currentTimeMillis(), result)
                            syncCheckpoint = syncTelemetry.readCheckpoint()
                            EmofahNotificationManager.notifySyncAttentionIfNeeded(app, result)
                            if (!result.skippedOffline && result.authBlockedCount == 0) {
                                val auditFlush = auditSyncRepository.flushPendingAudits()
                                auditStatusMessage = auditFlush.message
                                repository.syncCatalogueAndRebuildIndex(forceNetwork = true)
                                val refreshed = orderRepository.fetchOrders(
                                    catalogueProducts = cachedProducts,
                                    forceRefresh = true,
                                )
                                orderFeedSnapshot = refreshed
                                if (!refreshed.isFromCache) {
                                    EmofahNotificationManager.notifyNewOrdersIfNeeded(app, refreshed.orders)
                                }
                            } else {
                                orderFeedSnapshot = orderRepository.readCachedSnapshotAsync(
                                    catalogueProducts = cachedProducts,
                                    reason = orderFeedSnapshot.statusMessage,
                                )
                            }
                            syncUiMessage = syncCheckpoint.lastMessage
                            isManualSyncing = false
                        }
                    },
                    onRetryEvent = { evt ->
                        if (isManualSyncing) return@ActivitySyncScreen
                        isManualSyncing = true
                        scope.launch {
                            app.database.syncQueueDao().requeueEventNow(evt.eventId, System.currentTimeMillis())
                            val result = syncQueueProcessor.processPendingQueue()
                            syncTelemetry.recordAttempt(System.currentTimeMillis(), result)
                            syncCheckpoint = syncTelemetry.readCheckpoint()
                            syncUiMessage = "Retried ${evt.eventType} (${evt.entityId.take(8)})."
                            isManualSyncing = false
                        }
                    },
                    onRetryConflictAfterRestock = { conflict ->
                        if (isManualSyncing) return@ActivitySyncScreen
                        isManualSyncing = true
                        scope.launch {
                            conflictManager.retryAfterStockReplenished(
                                eventId = conflict.eventId,
                                actorId = identityProvider.getActorId(),
                                deviceId = identityProvider.getDeviceId(),
                            )
                            val result = syncQueueProcessor.processPendingQueue()
                            syncTelemetry.recordAttempt(System.currentTimeMillis(), result)
                            syncCheckpoint = syncTelemetry.readCheckpoint()
                            syncUiMessage = "Conflict requeued & checked: ${syncCheckpoint.lastMessage.orEmpty()}"
                            isManualSyncing = false
                        }
                    },
                    onReverseConflictLocally = { conflict ->
                        val authCheck = RolePermissionPolicy.authorize(
                            authState.activeRole,
                            EmofahPermission.RESOLVE_SYNC_CONFLICT_REVERSAL,
                        )
                        if (authCheck is PermissionCheckResult.Denied) {
                            syncUiMessage = authCheck.message
                            return@ActivitySyncScreen
                        }
                        if (isManualSyncing) return@ActivitySyncScreen
                        isManualSyncing = true
                        scope.launch {
                            val outcome = conflictManager.markConflictedSaleReversedWithAudit(
                                eventId = conflict.eventId,
                                actorId = identityProvider.getActorId(),
                                deviceId = identityProvider.getDeviceId(),
                                reason = "Resolved stock conflict locally (${authState.activeActorId})",
                            )
                            syncUiMessage = if (outcome == ConflictResolutionOutcome.REVERSED_LOCALLY_WITH_AUDIT) {
                                "Sale marked locally reversed with audit trail preserved."
                            } else {
                                "Conflict item no longer active."
                            }
                            isManualSyncing = false
                        }
                    },
                    onReverseSale = { saleWithItems, reason ->
                        if (isReversingSale) return@ActivitySyncScreen
                        isReversingSale = true
                        scope.launch {
                            val outcome = saleHistoryRepository.reverseSale(
                                saleWithItems = saleWithItems,
                                reason = reason,
                            )
                            syncUiMessage = when (outcome) {
                                is ReverseSaleOutcome.ReversedOnline -> {
                                    val replayNote = if (outcome.alreadyReversed) " (already reversed on server)" else ""
                                    "Sale reversed online$replayNote. Restored ${outcome.restoredQty} unit(s); server stock now ${outcome.remainingServerQty}."
                                }
                                is ReverseSaleOutcome.ReversedOfflineQueued ->
                                    outcome.reason
                                is ReverseSaleOutcome.ReversedUnsyncedLocally ->
                                    outcome.reason
                                is ReverseSaleOutcome.AlreadyReversed ->
                                    "Sale already reversed (${outcome.existingReason ?: "no duplicate reversal"})."
                                is ReverseSaleOutcome.Rejected ->
                                    outcome.message
                            }
                            isReversingSale = false
                        }
                    },
                )
            }
        }
    }

    if (showAuthDialog) {
        StaffSignInDialog(
            authState = authState,
            isOnline = isDeviceOnline,
            authBlockedSyncCount = offlineSummary.authBlockedCount,
            onDismiss = { showAuthDialog = false },
            onRequestOtp = { email, onSent ->
                scope.launch {
                    val result = authRepository.requestOtp(email)
                    onSent(result is AuthOtpResult.OtpSent)
                }
            },
            onVerifyOtp = { email, token ->
                scope.launch {
                    val outcome = authRepository.verifyOtp(email, token)
                    if (outcome is AuthActionOutcome.SignedIn) {
                        showAuthDialog = false
                        syncUiMessage = "Signed in as ${outcome.session.email}."
                    }
                }
            },
            onRefreshExisting = {
                scope.launch {
                    val outcome = authRepository.ensureFreshSessionIfOnline(forceRefresh = true)
                    if (outcome is AuthActionOutcome.Refreshed) {
                        syncUiMessage = "Session refreshed for ${outcome.session.email}."
                    }
                }
            },
            onSignOut = {
                scope.launch {
                    val res = authRepository.signOut()
                    showAuthDialog = false
                    syncUiMessage = if (res.hadPendingSyncItems) {
                        "Signed out. Saved offline records remain safe on this phone."
                    } else {
                        "Signed out."
                    }
                }
            },
            onBootstrapInitialAdmin = {
                scope.launch {
                    val outcome = authRepository.bootstrapInitialAdmin()
                    if (outcome is AuthBootstrapOutcome.Activated) {
                        syncUiMessage = outcome.message
                    }
                }
            },
            onLoadStaffDirectory = { onLoaded ->
                scope.launch {
                    onLoaded(authRepository.listStaffRoles())
                }
            },
            onAssignStaffRole = { email, role, isActive, onDone ->
                scope.launch {
                    onDone(authRepository.assignStaffRole(email, role, isActive))
                }
            },
        )
    }
}

@Composable
private fun EmofahTopAppBar(
    currentDestination: EmofahDestination,
    isOnline: Boolean,
    authStatus: AuthStatus,
    activeOrdersCount: Int,
    attentionSyncCount: Int,
    onOpenAuth: () -> Unit,
    onOpenOrders: () -> Unit,
    onOpenActivity: () -> Unit,
) {
    val colors = MaterialTheme.emofahColors
    Surface(
        color = colors.glassSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
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
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                        modifier = Modifier.size(34.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "E",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Emofah",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary,
                                maxLines = 1,
                            )
                            EmofahStatusBadge(
                                text = if (isOnline) "Online" else "Offline",
                                tone = if (isOnline) EmofahBadgeTone.SUCCESS else EmofahBadgeTone.WARNING,
                                leadingDot = true,
                            )
                        }
                        Text(
                            text = currentDestination.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    IconButton(onClick = onOpenAuth) {
                        Icon(
                            imageVector = when (authStatus) {
                                AuthStatus.AUTHENTICATED -> Icons.Outlined.VerifiedUser
                                AuthStatus.SESSION_EXPIRED -> Icons.Outlined.WarningAmber
                                AuthStatus.OFFLINE_CACHED, AuthStatus.UNAUTHENTICATED -> Icons.Outlined.AccountCircle
                            },
                            contentDescription = "Staff Account",
                            tint = when (authStatus) {
                                AuthStatus.AUTHENTICATED -> colors.success
                                AuthStatus.SESSION_EXPIRED -> colors.danger
                                AuthStatus.OFFLINE_CACHED -> colors.warning
                                AuthStatus.UNAUTHENTICATED -> colors.textMuted
                            },
                        )
                    }

                    IconButton(onClick = onOpenOrders) {
                        if (activeOrdersCount > 0) {
                            BadgedBox(
                                badge = {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                    ) {
                                        Text(if (activeOrdersCount > 9) "9+" else "$activeOrdersCount")
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = EmofahDestination.Orders.icon,
                                    contentDescription = EmofahDestination.Orders.label,
                                    tint = if (currentDestination == EmofahDestination.Orders) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        colors.textPrimary
                                    },
                                )
                            }
                        } else {
                            Icon(
                                imageVector = EmofahDestination.Orders.icon,
                                contentDescription = EmofahDestination.Orders.label,
                                tint = if (currentDestination == EmofahDestination.Orders) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    colors.textPrimary
                                },
                            )
                        }
                    }

                    IconButton(onClick = onOpenActivity) {
                        if (attentionSyncCount > 0) {
                            BadgedBox(
                                badge = {
                                    Badge(
                                        containerColor = if (attentionSyncCount > 0) colors.warning else MaterialTheme.colorScheme.primary,
                                        contentColor = colors.canvas,
                                    ) {
                                        Text(if (attentionSyncCount > 9) "9+" else "$attentionSyncCount")
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = EmofahDestination.Activity.icon,
                                    contentDescription = EmofahDestination.Activity.label,
                                    tint = if (currentDestination == EmofahDestination.Activity) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        colors.textPrimary
                                    },
                                )
                            }
                        } else {
                            Icon(
                                imageVector = EmofahDestination.Activity.icon,
                                contentDescription = EmofahDestination.Activity.label,
                                tint = if (currentDestination == EmofahDestination.Activity) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    colors.textPrimary
                                },
                            )
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle)
        }
    }
}

@Composable
private fun EmofahBottomNavigationBar(
    currentDestination: EmofahDestination,
    onSelectDestination: (EmofahDestination) -> Unit,
) {
    val colors = MaterialTheme.emofahColors
    val navItems = remember { EmofahDestination.entries.filter { it.showInBottomBar } }

    Surface(
        color = colors.glassSurface,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                navItems.forEach { dest ->
                    val isSelected = currentDestination == dest
                    val interactionSource = remember { MutableInteractionSource() }
                    val itemBg = if (isSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    } else {
                        androidx.compose.ui.graphics.Color.Transparent
                    }
                    val contentTint = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        colors.textMuted
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 2.dp)
                            .emofahPressScale(interactionSource, pressedScale = 0.94f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(itemBg)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = ripple(),
                                onClick = { onSelectDestination(dest) },
                            )
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(
                                imageVector = dest.icon,
                                contentDescription = dest.label,
                                tint = contentTint,
                                modifier = Modifier.size(21.dp),
                            )
                            Text(
                                text = dest.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                color = contentTint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

