import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  computeBusinessBalanceSummary,
  type BusinessBalanceSummary,
  type ClassificationHistoryEntry,
  type RecentSaleOption,
  type SharedFinancialTransaction,
} from '../services/financialBalance';
import {
  captureFinancialTransaction,
  classifyTransactionAsNonBusiness,
  classifyTransactionAsProduct,
  classifyTransactionAsService,
  fetchSharedTransactions,
  subscribeToTransactionUpdates,
  type ClassificationActionResult,
} from '../services/financialTransactions';
import { fetchLiveSupabaseCatalog, type ProductIdentity } from '../services/productCatalog';

export function useSharedTransactions() {
  const [transactions, setTransactions] = useState<SharedFinancialTransaction[]>([]);
  const [recentSales, setRecentSales] = useState<RecentSaleOption[]>([]);
  const [history, setHistory] = useState<ClassificationHistoryEntry[]>([]);
  const [products, setProducts] = useState<ProductIdentity[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [warning, setWarning] = useState<string | null>(null);
  const [rpcDeployed, setRpcDeployed] = useState(true);
  const [isRealtimeConnected, setIsRealtimeConnected] = useState(false);
  const [lastUpdatedAt, setLastUpdatedAt] = useState<string | null>(null);
  const [busyEventId, setBusyEventId] = useState<string | null>(null);
  const [actionFeedback, setActionFeedback] = useState<{
    tone: 'success' | 'warning' | 'error';
    message: string;
  } | null>(null);

  const isMountedRef = useRef(true);
  const inFlightRefreshRef = useRef<Promise<void> | null>(null);

  const refresh = useCallback(async (opts?: { includeCatalog?: boolean; silent?: boolean }) => {
    if (inFlightRefreshRef.current) {
      return inFlightRefreshRef.current;
    }

    const promise = (async () => {
      if (!opts?.silent) {
        setIsRefreshing(true);
      }
      try {
        const [txResult, catalogResult] = await Promise.all([
          fetchSharedTransactions(150),
          opts?.includeCatalog ? fetchLiveSupabaseCatalog() : Promise.resolve(null),
        ]);
        if (!isMountedRef.current) return;
        setTransactions(txResult.transactions);
        setRecentSales(txResult.recentSales);
        setHistory(txResult.history);
        setRpcDeployed(txResult.rpcDeployed);
        setWarning(txResult.warning || null);
        setError(null);
        setLastUpdatedAt(new Date().toLocaleTimeString());
        if (catalogResult) {
          setProducts(catalogResult.products);
        }
      } catch (err) {
        if (!isMountedRef.current) return;
        setError(err instanceof Error ? err.message : 'Failed to load shared transactions.');
      } finally {
        if (isMountedRef.current) {
          setIsLoading(false);
          setIsRefreshing(false);
        }
        inFlightRefreshRef.current = null;
      }
    })();

    inFlightRefreshRef.current = promise;
    return promise;
  }, []);

  useEffect(() => {
    isMountedRef.current = true;
    void refresh({ includeCatalog: true });

    const sub = subscribeToTransactionUpdates({
      onChange: () => {
        void refresh({ includeCatalog: true, silent: true });
      },
      onConnectionChange: (connected) => {
        if (isMountedRef.current) {
          setIsRealtimeConnected(connected);
        }
      },
    });

    // Reliable recovery polling fallback in case realtime WebSocket delivery drops
    const pollId = window.setInterval(() => {
      if (document.visibilityState === 'visible') {
        void refresh({ silent: true });
      }
    }, 12_000);

    const handleVisibilityOrFocus = () => {
      if (document.visibilityState === 'visible') {
        void refresh({ includeCatalog: true, silent: true });
      }
    };
    window.addEventListener('focus', handleVisibilityOrFocus);
    document.addEventListener('visibilitychange', handleVisibilityOrFocus);

    return () => {
      isMountedRef.current = false;
      sub.close();
      window.clearInterval(pollId);
      window.removeEventListener('focus', handleVisibilityOrFocus);
      document.removeEventListener('visibilitychange', handleVisibilityOrFocus);
    };
  }, [refresh]);

  const applyClassificationOutcome = useCallback(
    async (result: ClassificationActionResult) => {
      if (result.event) {
        setTransactions((prev) =>
          prev.map((tx) => (tx.id === result.event!.id ? result.event! : tx))
        );
      }

      if (result.status === 'COMMITTED') {
        setActionFeedback({ tone: 'success', message: result.message });
        await refresh({ includeCatalog: true, silent: true });
      } else if (
        result.status === 'ALREADY_CLASSIFIED' ||
        result.status === 'DUPLICATE_IGNORED'
      ) {
        setActionFeedback({ tone: 'warning', message: result.message });
        await refresh({ includeCatalog: true, silent: true });
      } else {
        setActionFeedback({ tone: 'error', message: result.message });
      }
      return result;
    },
    [refresh]
  );

  const classifyAsProduct = useCallback(
    async (params: Parameters<typeof classifyTransactionAsProduct>[0]) => {
      setBusyEventId(params.eventId);
      try {
        const result = await classifyTransactionAsProduct(params);
        return await applyClassificationOutcome(result);
      } finally {
        if (isMountedRef.current) setBusyEventId(null);
      }
    },
    [applyClassificationOutcome]
  );

  const classifyAsService = useCallback(
    async (params: Parameters<typeof classifyTransactionAsService>[0]) => {
      setBusyEventId(params.eventId);
      try {
        const result = await classifyTransactionAsService(params);
        return await applyClassificationOutcome(result);
      } finally {
        if (isMountedRef.current) setBusyEventId(null);
      }
    },
    [applyClassificationOutcome]
  );

  const classifyAsNonBusiness = useCallback(
    async (params: Parameters<typeof classifyTransactionAsNonBusiness>[0]) => {
      setBusyEventId(params.eventId);
      try {
        const result = await classifyTransactionAsNonBusiness(params);
        return await applyClassificationOutcome(result);
      } finally {
        if (isMountedRef.current) setBusyEventId(null);
      }
    },
    [applyClassificationOutcome]
  );

  const pendingTransactions = useMemo(
    () => transactions.filter((t) => t.classificationStatus === 'PENDING_CLASSIFICATION'),
    [transactions]
  );

  const summary: BusinessBalanceSummary = useMemo(
    () => computeBusinessBalanceSummary(transactions),
    [transactions]
  );

  const captureTransaction = useCallback(
    async (params: Parameters<typeof captureFinancialTransaction>[0]) => {
      const newTx = await captureFinancialTransaction(params);
      setTransactions((prev) => [
        newTx,
        ...prev.filter(
          (t) =>
            t.id !== newTx.id &&
            (!newTx.referenceCode || t.referenceCode !== newTx.referenceCode)
        ),
      ]);
      setActionFeedback({
        tone: 'success',
        message: `Captured ${newTx.direction === 'INFLOW' ? 'received' : 'sent'} Ksh ${newTx.amount.toLocaleString()} (${newTx.referenceCode || 'New'}). Awaiting classification.`,
      });
      void refresh({ silent: true });
      return newTx;
    },
    [refresh]
  );

  return {
    transactions,
    pendingTransactions,
    recentSales,
    history,
    products,
    summary,
    isLoading,
    isRefreshing,
    error,
    warning,
    rpcDeployed,
    isRealtimeConnected,
    lastUpdatedAt,
    busyEventId,
    actionFeedback,
    clearActionFeedback: () => setActionFeedback(null),
    refresh,
    captureTransaction,
    classifyAsProduct,
    classifyAsService,
    classifyAsNonBusiness,
  };
}
