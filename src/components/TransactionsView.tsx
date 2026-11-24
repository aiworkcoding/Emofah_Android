import { useEffect, useMemo, useState } from 'react';
import {
  ArrowDownLeft,
  ArrowUpRight,
  Ban,
  Briefcase,
  CheckCircle2,
  Clock,
  History,
  Package,
  RefreshCw,
  Search,
  X,
} from 'lucide-react';
import {
  canClassifyTransaction,
  computeTransactionBusinessContribution,
  formatKes,
  isSaleAvailableForLinking,
  SERVICE_OPERATION_OPTIONS,
  type BusinessBalanceSummary,
  type ClassificationHistoryEntry,
  type ClassificationStatus,
  type RecentSaleOption,
  type SharedFinancialTransaction,
} from '../services/financialBalance';
import type { ClassificationActionResult } from '../services/financialTransactions';
import type { ProductIdentity } from '../services/productCatalog';

export type ClassificationModalMode = 'product' | 'service' | 'non_business';

interface TransactionsViewProps {
  transactions: SharedFinancialTransaction[];
  recentSales: RecentSaleOption[];
  history: ClassificationHistoryEntry[];
  products: ProductIdentity[];
  summary: BusinessBalanceSummary;
  isLoading: boolean;
  isRefreshing: boolean;
  error: string | null;
  warning: string | null;
  isRealtimeConnected: boolean;
  lastUpdatedAt: string | null;
  busyEventId: string | null;
  actionFeedback: { tone: 'success' | 'warning' | 'error'; message: string } | null;
  externalActionRequest: { tx: SharedFinancialTransaction; mode: ClassificationModalMode } | null;
  onClearExternalActionRequest: () => void;
  onClearFeedback: () => void;
  onRefresh: () => void;
  onClassifyProduct: (params: {
    eventId: string;
    productId?: number | null;
    quantity?: number;
    existingSaleId?: number | null;
    unitSellPrice?: number | null;
    paymentMethod?: 'CASH' | 'MPESA' | 'BANK';
    note?: string | null;
  }) => Promise<ClassificationActionResult>;
  onClassifyService: (params: {
    eventId: string;
    serviceType: string;
    serviceLabel?: string | null;
    quantityOrDuration: number;
    unitLabel?: string;
    unitPrice?: number | null;
    discountAmount?: number;
    note?: string | null;
  }) => Promise<ClassificationActionResult>;
  onClassifyNonBusiness: (params: {
    eventId: string;
    reason?: string | null;
  }) => Promise<ClassificationActionResult>;
}

type FilterView =
  | 'ALL'
  | 'PENDING_CLASSIFICATION'
  | 'BUSINESS_PRODUCT'
  | 'BUSINESS_SERVICE'
  | 'NON_BUSINESS'
  | 'HISTORY';

function formatTimestamp(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString('en-KE', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function statusDisplayLabel(status: ClassificationStatus): string {
  switch (status) {
    case 'PENDING_CLASSIFICATION':
      return 'Awaiting Classification';
    case 'BUSINESS_PRODUCT':
      return 'Product Sale';
    case 'BUSINESS_SERVICE':
      return 'Service';
    case 'NON_BUSINESS':
      return 'Excluded (Non-Business)';
  }
}

export function TransactionsView({
  transactions,
  recentSales,
  history,
  products,
  summary,
  isLoading,
  isRefreshing,
  error,
  warning,
  isRealtimeConnected,
  lastUpdatedAt,
  busyEventId,
  actionFeedback,
  externalActionRequest,
  onClearExternalActionRequest,
  onClearFeedback,
  onRefresh,
  onClassifyProduct,
  onClassifyService,
  onClassifyNonBusiness,
}: TransactionsViewProps) {
  const [filterView, setFilterView] = useState<FilterView>('ALL');
  const [searchQuery, setSearchQuery] = useState('');
  const [modalTarget, setModalTarget] = useState<{
    tx: SharedFinancialTransaction;
    mode: ClassificationModalMode;
  } | null>(null);

  // Product modal form state
  const [productLinkMode, setProductLinkMode] = useState<'new_sale' | 'existing_sale'>('new_sale');
  const [selectedProductId, setSelectedProductId] = useState<string>('');
  const [productQty, setProductQty] = useState<string>('1');
  const [productUnitPrice, setProductUnitPrice] = useState<string>('');
  const [selectedExistingSaleId, setSelectedExistingSaleId] = useState<string>('');
  const [productSearch, setProductSearch] = useState('');
  const [productNote, setProductNote] = useState('');

  // Service modal form state
  const [serviceType, setServiceType] = useState<string>(SERVICE_OPERATION_OPTIONS[0].code);
  const [serviceQty, setServiceQty] = useState<string>('1');
  const [serviceUnitPrice, setServiceUnitPrice] = useState<string>('');
  const [serviceDiscount, setServiceDiscount] = useState<string>('0');
  const [serviceNote, setServiceNote] = useState<string>('');

  // Non-business modal form state
  const [nonBizReason, setNonBizReason] = useState<string>('Personal / non-business transfer');

  const openModal = (tx: SharedFinancialTransaction, mode: ClassificationModalMode) => {
    setModalTarget({ tx, mode });
    setProductLinkMode('new_sale');
    setProductQty('1');
    setProductUnitPrice(String(tx.amount));
    setSelectedExistingSaleId('');
    setProductSearch('');
    setProductNote('');
    setSelectedProductId(products[0] ? String(products[0].id) : '');

    setServiceType(SERVICE_OPERATION_OPTIONS[0].code);
    setServiceQty('1');
    setServiceUnitPrice(String(tx.amount));
    setServiceDiscount('0');
    setServiceNote(tx.counterparty ? `Payment from ${tx.counterparty}` : '');

    setNonBizReason('Personal / non-business transaction');
  };

  useEffect(() => {
    if (externalActionRequest) {
      openModal(externalActionRequest.tx, externalActionRequest.mode);
      onClearExternalActionRequest();
    }
  }, [externalActionRequest, onClearExternalActionRequest]);

  const filteredProducts = useMemo(() => {
    const q = productSearch.trim().toLowerCase();
    if (!q) return products.slice(0, 60);
    return products
      .filter(
        (p) =>
          p.name.toLowerCase().includes(q) ||
          p.category.toLowerCase().includes(q) ||
          String(p.id) === q
      )
      .slice(0, 60);
  }, [products, productSearch]);

  const unlinkedSales = useMemo(
    () =>
      recentSales.filter((sale) =>
        isSaleAvailableForLinking(sale, modalTarget?.tx.id)
      ),
    [recentSales, modalTarget]
  );

  const filteredTransactions = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    return transactions.filter((tx) => {
      if (filterView !== 'ALL' && filterView !== 'HISTORY') {
        if (tx.classificationStatus !== filterView) return false;
      }
      if (!q) return true;
      return (
        (tx.referenceCode || '').toLowerCase().includes(q) ||
        (tx.counterparty || '').toLowerCase().includes(q) ||
        (tx.counterpartyAccount || '').toLowerCase().includes(q) ||
        (tx.linkedProductName || '').toLowerCase().includes(q) ||
        (tx.serviceLabel || '').toLowerCase().includes(q) ||
        (tx.rawEvidence || '').toLowerCase().includes(q) ||
        String(tx.amount).includes(q)
      );
    });
  }, [transactions, filterView, searchQuery]);

  const selectedProductObj = useMemo(
    () => products.find((p) => String(p.id) === selectedProductId) || null,
    [products, selectedProductId]
  );

  const selectedServiceOption = useMemo(
    () =>
      SERVICE_OPERATION_OPTIONS.find((s) => s.code === serviceType) ||
      SERVICE_OPERATION_OPTIONS[0],
    [serviceType]
  );

  const computedServiceNet = useMemo(() => {
    const q = Math.max(1, Number(serviceQty) || 1);
    const up = Math.max(0, Number(serviceUnitPrice) || 0);
    const disc = Math.max(0, Number(serviceDiscount) || 0);
    return Math.max(0, Math.round((q * up - disc) * 100) / 100);
  }, [serviceQty, serviceUnitPrice, serviceDiscount]);

  const handleSubmitProduct = async () => {
    if (!modalTarget) return;
    const tx = modalTarget.tx;
    if (productLinkMode === 'existing_sale') {
      const saleId = Number(selectedExistingSaleId);
      if (!saleId) return;
      const saleObj = recentSales.find((s) => s.saleId === saleId);
      const res = await onClassifyProduct({
        eventId: tx.id,
        existingSaleId: saleId,
        productId: saleObj?.productId ?? null,
        quantity: saleObj?.qtySold ?? 1,
        note: productNote,
      });
      if (res.status === 'COMMITTED' || res.status === 'ALREADY_CLASSIFIED' || res.status === 'DUPLICATE_IGNORED') {
        setModalTarget(null);
      }
    } else {
      const pid = Number(selectedProductId);
      const qty = Math.max(1, Number(productQty) || 1);
      const unitPrice = Number(productUnitPrice);
      if (!pid) return;
      const res = await onClassifyProduct({
        eventId: tx.id,
        productId: pid,
        quantity: qty,
        unitSellPrice: Number.isFinite(unitPrice) && unitPrice > 0 ? unitPrice : null,
        paymentMethod: tx.source.includes('MPESA') ? 'MPESA' : 'CASH',
        note: productNote,
      });
      if (res.status === 'COMMITTED' || res.status === 'ALREADY_CLASSIFIED' || res.status === 'DUPLICATE_IGNORED') {
        setModalTarget(null);
      }
    }
  };

  const handleSubmitService = async () => {
    if (!modalTarget) return;
    const qty = Math.max(1, Number(serviceQty) || 1);
    const unitPrice = Number(serviceUnitPrice);
    const discount = Math.max(0, Number(serviceDiscount) || 0);
    const res = await onClassifyService({
      eventId: modalTarget.tx.id,
      serviceType,
      serviceLabel: selectedServiceOption.label,
      quantityOrDuration: qty,
      unitLabel: selectedServiceOption.unitLabel,
      unitPrice: Number.isFinite(unitPrice) && unitPrice > 0 ? unitPrice : null,
      discountAmount: discount,
      note: serviceNote,
    });
    if (res.status === 'COMMITTED' || res.status === 'ALREADY_CLASSIFIED' || res.status === 'DUPLICATE_IGNORED') {
      setModalTarget(null);
    }
  };

  const handleSubmitNonBusiness = async () => {
    if (!modalTarget) return;
    const res = await onClassifyNonBusiness({
      eventId: modalTarget.tx.id,
      reason: nonBizReason,
    });
    if (res.status === 'COMMITTED' || res.status === 'ALREADY_CLASSIFIED' || res.status === 'DUPLICATE_IGNORED') {
      setModalTarget(null);
    }
  };

  return (
    <div className="space-y-6">
      {/* Header & Refresh Controls */}
      <header className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="font-display text-2xl sm:text-3xl font-bold tracking-tight text-[#103c60] dark:text-[#f1f5f9]">
            Transactions &amp; Classification
          </h1>
          <div className="mt-1 flex flex-wrap items-center gap-x-2.5 gap-y-1 text-xs text-[#64748b] dark:text-[#94a3b8]">
            <span>Shared Supabase Ledger</span>
            <span aria-hidden="true">·</span>
            <span>{isRealtimeConnected ? 'Live sync active' : 'Auto-refreshing'}</span>
            {lastUpdatedAt && (
              <>
                <span aria-hidden="true">·</span>
                <span className="font-mono-data tabular-nums">Updated {lastUpdatedAt}</span>
              </>
            )}
          </div>
        </div>

        <button
          type="button"
          onClick={onRefresh}
          disabled={isRefreshing}
          className="min-h-[42px] px-4 py-2 rounded-xl bg-white/80 dark:bg-[#0d1f36] border border-[#103c60]/12 dark:border-[#43b1d4]/20 text-xs font-semibold text-[#103c60] dark:text-[#f1f5f9] hover:bg-white dark:hover:bg-[#132945] inline-flex items-center gap-2 transition-colors cursor-pointer whitespace-nowrap shrink-0 disabled:opacity-60"
        >
          <RefreshCw className={`w-3.5 h-3.5 ${isRefreshing ? 'animate-spin' : ''}`} />
          <span>{isRefreshing ? 'Refreshing' : 'Refresh Ledger'}</span>
        </button>
      </header>

      {/* Authoritative Business Balance Strip (Strictly Derived From Classified Business Transactions) */}
      <section
        aria-label="Business balance overview"
        className="emofah-glass rounded-2xl p-5 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-5 divide-y sm:divide-y-0 sm:divide-x divide-[#103c60]/10 dark:divide-[#43b1d4]/15"
      >
        <div className="space-y-1 pr-2">
          <div className="text-xs font-medium text-[#64748b] dark:text-[#94a3b8]">
            Business Balance (Classified)
          </div>
          <div className="font-mono-data tabular-nums text-2xl font-bold text-[#103c60] dark:text-[#f1f5f9]">
            {formatKes(summary.businessBalance)}
          </div>
          <div className="text-xs text-[#64748b] dark:text-[#94a3b8]">
            Excludes pending &amp; personal items
          </div>
        </div>

        <div className="space-y-1 pt-3 sm:pt-0 sm:pl-5 pr-2">
          <div className="text-xs font-medium text-[#64748b] dark:text-[#94a3b8]">
            Product Sales ({summary.businessProductCount})
          </div>
          <div className="font-mono-data tabular-nums text-xl font-semibold text-emerald-700 dark:text-emerald-400">
            {formatKes(summary.businessProductNet)}
          </div>
          <div className="text-xs text-[#64748b] dark:text-[#94a3b8]">
            Single-counted inventory sales
          </div>
        </div>

        <div className="space-y-1 pt-3 sm:pt-0 sm:pl-5 pr-2">
          <div className="text-xs font-medium text-[#64748b] dark:text-[#94a3b8]">
            Service Revenue ({summary.businessServiceCount})
          </div>
          <div className="font-mono-data tabular-nums text-xl font-semibold text-[#14558a] dark:text-[#43b1d4]">
            {formatKes(summary.businessServiceNet)}
          </div>
          <div className="text-xs text-[#64748b] dark:text-[#94a3b8]">
            Net after discounts
          </div>
        </div>

        <div className="space-y-1 pt-3 sm:pt-0 sm:pl-5">
          <div className="text-xs font-medium text-[#64748b] dark:text-[#94a3b8]">
            Excluded From Balance
          </div>
          <div className="font-mono-data tabular-nums text-xl font-semibold text-[#475569] dark:text-[#cbd5e1]">
            {formatKes(summary.pendingExcludedAmount + summary.nonBusinessExcludedAmount)}
          </div>
          <div className="text-xs text-[#64748b] dark:text-[#94a3b8] font-mono-data tabular-nums">
            {summary.pendingCount} pending · {summary.nonBusinessCount} non-business
          </div>
        </div>
      </section>

      {/* Banners for warnings, errors, or action results */}
      {warning && (
        <div className="rounded-xl border border-amber-300 dark:border-amber-700/60 bg-amber-50/90 dark:bg-amber-950/30 px-4 py-3 text-xs text-amber-900 dark:text-amber-200">
          {warning}
        </div>
      )}

      {error && (
        <div className="rounded-xl border border-rose-300 dark:border-rose-700/60 bg-rose-50/90 dark:bg-rose-950/30 px-4 py-3 text-xs text-rose-900 dark:text-rose-200">
          {error}
        </div>
      )}

      {actionFeedback && (
        <div
          className={`rounded-xl border px-4 py-3 text-xs flex items-center justify-between gap-3 ${
            actionFeedback.tone === 'success'
              ? 'border-emerald-300 dark:border-emerald-700/60 bg-emerald-50/90 dark:bg-emerald-950/30 text-emerald-900 dark:text-emerald-200'
              : actionFeedback.tone === 'warning'
              ? 'border-amber-300 dark:border-amber-700/60 bg-amber-50/90 dark:bg-amber-950/30 text-amber-900 dark:text-amber-200'
              : 'border-rose-300 dark:border-rose-700/60 bg-rose-50/90 dark:bg-rose-950/30 text-rose-900 dark:text-rose-200'
          }`}
        >
          <span>{actionFeedback.message}</span>
          <button
            type="button"
            onClick={onClearFeedback}
            className="text-current opacity-75 hover:opacity-100 cursor-pointer"
            aria-label="Dismiss message"
          >
            <X className="w-4 h-4" />
          </button>
        </div>
      )}

      {/* Segmented Filter Controls & Search */}
      <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-3">
        <div className="flex flex-wrap items-center gap-1 p-1 rounded-xl bg-white/75 dark:bg-[#0d1f36]/80 border border-[#103c60]/10 dark:border-[#43b1d4]/15">
          {(
            [
              { id: 'ALL', label: `All (${transactions.length})` },
              { id: 'PENDING_CLASSIFICATION', label: `Pending (${summary.pendingCount})` },
              { id: 'BUSINESS_PRODUCT', label: `Products (${summary.businessProductCount})` },
              { id: 'BUSINESS_SERVICE', label: `Services (${summary.businessServiceCount})` },
              { id: 'NON_BUSINESS', label: `Removed (${summary.nonBusinessCount})` },
              { id: 'HISTORY', label: `History (${history.length})` },
            ] as { id: FilterView; label: string }[]
          ).map((tab) => {
            const active = filterView === tab.id;
            return (
              <button
                key={tab.id}
                type="button"
                onClick={() => setFilterView(tab.id)}
                className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer whitespace-nowrap shrink-0 ${
                  active
                    ? 'bg-[#14558a] dark:bg-[#43b1d4] text-white dark:text-[#060d18]'
                    : 'text-[#475569] dark:text-[#94a3b8] hover:text-[#103c60] dark:hover:text-[#f1f5f9]'
                }`}
              >
                {tab.label}
              </button>
            );
          })}
        </div>

        <label className="relative flex-1 md:max-w-xs">
          <Search className="w-4 h-4 text-[#64748b] dark:text-[#94a3b8] absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" />
          <input
            type="search"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search reference, sender, product..."
            className="w-full min-h-[40px] pl-9 pr-3 rounded-xl bg-white/85 dark:bg-[#0d1f36] border border-[#103c60]/12 dark:border-[#43b1d4]/20 text-xs text-[#103c60] dark:text-[#f1f5f9] placeholder:text-[#94a3b8] focus:outline-none focus:ring-2 focus:ring-[#43b1d4]"
          />
        </label>
      </div>

      {/* Main Content Area: Ledger or Immutable Audit History */}
      {isLoading ? (
        <div className="emofah-glass rounded-2xl p-6 space-y-3">
          {[1, 2, 3, 4].map((row) => (
            <div
              key={row}
              className="h-12 rounded-xl bg-[#103c60]/6 dark:bg-[#43b1d4]/8 animate-pulse"
            />
          ))}
        </div>
      ) : filterView === 'HISTORY' ? (
        <section className="emofah-glass rounded-2xl overflow-hidden">
          <div className="px-5 py-3.5 border-b border-[#103c60]/10 dark:border-[#43b1d4]/15 flex items-center gap-2 text-xs font-semibold text-[#103c60] dark:text-[#f1f5f9]">
            <History className="w-4 h-4" />
            <span>Immutable Classification History ({history.length})</span>
          </div>
          {history.length === 0 ? (
            <div className="p-8 text-center text-sm text-[#64748b] dark:text-[#94a3b8]">
              No classification history entries recorded yet.
            </div>
          ) : (
            <ul className="divide-y divide-[#103c60]/8 dark:divide-[#43b1d4]/12">
              {history.map((entry) => (
                <li key={entry.id} className="px-5 py-3.5 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-2 text-xs">
                  <div className="space-y-1">
                    <div className="font-semibold text-[#103c60] dark:text-[#f1f5f9]">
                      {entry.action.replace(/_/g, ' ')} · {entry.previousStatus || 'NEW'} → {entry.newStatus}
                    </div>
                    <div className="text-[#64748b] dark:text-[#94a3b8]">
                      {[
                        entry.note,
                        entry.linkedSaleId ? `Sale #${entry.linkedSaleId}` : null,
                        entry.actorId ? `By ${entry.actorId}` : null,
                        entry.deviceId ? `Device: ${entry.deviceId}` : null,
                      ]
                        .filter(Boolean)
                        .join(' · ')}
                    </div>
                  </div>
                  <div className="font-mono-data tabular-nums text-[#64748b] dark:text-[#94a3b8] shrink-0">
                    {formatTimestamp(entry.createdAt)}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </section>
      ) : filteredTransactions.length === 0 ? (
        <div className="emofah-glass rounded-2xl p-10 text-center space-y-2">
          <p className="text-sm font-semibold text-[#103c60] dark:text-[#f1f5f9]">
            No transactions match the current filter
          </p>
          <p className="text-xs text-[#64748b] dark:text-[#94a3b8]">
            Transactions captured automatically on Android appear here immediately for shared classification.
          </p>
        </div>
      ) : (
        <section className="emofah-glass rounded-2xl overflow-hidden">
          <ul className="divide-y divide-[#103c60]/10 dark:divide-[#43b1d4]/12">
            {filteredTransactions.map((tx) => {
              const isInflow = tx.direction === 'INFLOW';
              const isPending = canClassifyTransaction(tx);
              const isBusy = busyEventId === tx.id;
              const businessContribution = computeTransactionBusinessContribution(tx);

              return (
                <li
                  key={tx.id}
                  className="px-5 py-4 hover:bg-white/60 dark:hover:bg-[#132945]/45 transition-colors"
                >
                  <div className="flex flex-col lg:flex-row lg:items-center lg:justify-between gap-4">
                    <div className="min-w-0 space-y-1.5">
                      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
                        <span
                          className={`font-mono-data tabular-nums text-base font-bold inline-flex items-center gap-1 ${
                            isInflow
                              ? 'text-emerald-700 dark:text-emerald-400'
                              : tx.direction === 'OUTFLOW'
                              ? 'text-rose-700 dark:text-rose-400'
                              : 'text-[#475569] dark:text-[#cbd5e1]'
                          }`}
                        >
                          {isInflow ? (
                            <ArrowDownLeft className="w-4 h-4 shrink-0" />
                          ) : (
                            <ArrowUpRight className="w-4 h-4 shrink-0" />
                          )}
                          {formatKes(tx.amount)}
                        </span>

                        <span className="text-sm font-semibold text-[#103c60] dark:text-[#f1f5f9]">
                          {isInflow
                            ? 'Received'
                            : tx.direction === 'OUTFLOW'
                            ? 'Sent'
                            : 'Transfer'}
                        </span>

                        <span
                          className={`text-xs font-semibold inline-flex items-center gap-1 ${
                            tx.classificationStatus === 'PENDING_CLASSIFICATION'
                              ? 'text-amber-700 dark:text-amber-300'
                              : tx.classificationStatus === 'BUSINESS_PRODUCT'
                              ? 'text-emerald-700 dark:text-emerald-400'
                              : tx.classificationStatus === 'BUSINESS_SERVICE'
                              ? 'text-[#14558a] dark:text-[#43b1d4]'
                              : 'text-[#64748b] dark:text-[#94a3b8]'
                          }`}
                        >
                          {tx.classificationStatus === 'PENDING_CLASSIFICATION' ? (
                            <Clock className="w-3.5 h-3.5" />
                          ) : tx.classificationStatus === 'NON_BUSINESS' ? (
                            <Ban className="w-3.5 h-3.5" />
                          ) : (
                            <CheckCircle2 className="w-3.5 h-3.5" />
                          )}
                          <span>{statusDisplayLabel(tx.classificationStatus)}</span>
                        </span>
                      </div>

                      <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-[#334155] dark:text-[#cbd5e1]">
                        <span>{tx.counterparty || 'Unidentified counterparty'}</span>
                        {tx.counterpartyAccount && (
                          <>
                            <span aria-hidden="true">·</span>
                            <span className="font-mono-data tabular-nums">{tx.counterpartyAccount}</span>
                          </>
                        )}
                        <span aria-hidden="true">·</span>
                        <span className="font-mono-data tabular-nums">
                          Ref {tx.referenceCode || tx.clientEventId || tx.id.slice(0, 8)}
                        </span>
                        <span aria-hidden="true">·</span>
                        <span className="font-mono-data tabular-nums">{formatTimestamp(tx.occurredAt)}</span>
                        {tx.transactionCost !== null && tx.transactionCost > 0 && (
                          <>
                            <span aria-hidden="true">·</span>
                            <span className="font-mono-data tabular-nums">
                              Fee {formatKes(tx.transactionCost)}
                            </span>
                          </>
                        )}
                      </div>

                      {tx.classificationStatus !== 'PENDING_CLASSIFICATION' && (
                        <div className="text-xs text-[#64748b] dark:text-[#94a3b8] flex flex-wrap items-center gap-x-2 gap-y-1">
                          {tx.classificationStatus === 'BUSINESS_PRODUCT' && (
                            <span className="font-medium text-[#103c60] dark:text-[#f1f5f9]">
                              {tx.linkedProductName || 'Product'}
                              {tx.linkedQuantity ? ` × ${tx.linkedQuantity}` : ''}
                              {tx.linkedSaleId ? ` (Sale #${tx.linkedSaleId})` : ''}
                            </span>
                          )}
                          {tx.classificationStatus === 'BUSINESS_SERVICE' && (
                            <span className="font-medium text-[#103c60] dark:text-[#f1f5f9]">
                              {tx.serviceLabel || tx.linkedProductName || 'Service'}
                              {tx.linkedQuantity ? ` × ${tx.linkedQuantity}` : ''}
                              {tx.serviceDiscountAmount && tx.serviceDiscountAmount > 0
                                ? ` · Discount ${formatKes(tx.serviceDiscountAmount)}`
                                : ''}
                            </span>
                          )}
                          {tx.classificationStatus === 'NON_BUSINESS' && (
                            <span>Excluded from business balance · Original evidence retained</span>
                          )}
                          {tx.classificationNote && (
                            <>
                              <span aria-hidden="true">·</span>
                              <span>{tx.classificationNote}</span>
                            </>
                          )}
                          {tx.classifiedDevice && (
                            <>
                              <span aria-hidden="true">·</span>
                              <span>Classified via {tx.classifiedDevice}</span>
                            </>
                          )}
                        </div>
                      )}

                      {tx.rawEvidence && (
                        <p className="text-xs text-[#64748b] dark:text-[#94a3b8] line-clamp-1">
                          {tx.rawEvidence}
                        </p>
                      )}
                    </div>

                    <div className="flex flex-wrap items-center gap-2 shrink-0">
                      {isPending ? (
                        <>
                          <button
                            type="button"
                            disabled={isBusy}
                            onClick={() => openModal(tx, 'product')}
                            className="min-h-[38px] px-3 py-1.5 rounded-xl bg-[#14558a] hover:bg-[#103c60] dark:bg-[#43b1d4] dark:hover:bg-[#348fc2] text-white dark:text-[#060d18] text-xs font-semibold inline-flex items-center gap-1.5 transition-colors cursor-pointer whitespace-nowrap shrink-0 disabled:opacity-60"
                          >
                            <Package className="w-3.5 h-3.5" />
                            <span>Link Product</span>
                          </button>

                          <button
                            type="button"
                            disabled={isBusy}
                            onClick={() => openModal(tx, 'service')}
                            className="min-h-[38px] px-3 py-1.5 rounded-xl border border-[#103c60]/18 dark:border-[#43b1d4]/28 text-[#103c60] dark:text-[#f1f5f9] hover:bg-[#103c60]/5 dark:hover:bg-[#43b1d4]/10 text-xs font-semibold inline-flex items-center gap-1.5 transition-colors cursor-pointer whitespace-nowrap shrink-0 disabled:opacity-60"
                          >
                            <Briefcase className="w-3.5 h-3.5" />
                            <span>Link Service</span>
                          </button>

                          <button
                            type="button"
                            disabled={isBusy}
                            onClick={() => openModal(tx, 'non_business')}
                            className="min-h-[38px] px-3 py-1.5 rounded-xl border border-slate-200 dark:border-slate-700 text-[#475569] dark:text-[#cbd5e1] hover:bg-slate-100 dark:hover:bg-slate-800/60 text-xs font-medium inline-flex items-center gap-1.5 transition-colors cursor-pointer whitespace-nowrap shrink-0 disabled:opacity-60"
                          >
                            <Ban className="w-3.5 h-3.5" />
                            <span>Remove</span>
                          </button>
                        </>
                      ) : (
                        <div className="text-right font-mono-data tabular-nums">
                          <div className="text-xs font-semibold text-[#103c60] dark:text-[#f1f5f9]">
                            {tx.classificationStatus === 'NON_BUSINESS'
                              ? 'Ksh 0 (excluded)'
                              : formatKes(businessContribution)}
                          </div>
                          <div className="text-[11px] text-[#64748b] dark:text-[#94a3b8]">
                            Balance impact
                          </div>
                        </div>
                      )}
                    </div>
                  </div>
                </li>
              );
            })}
          </ul>
        </section>
      )}

      {/* Shared Classification Action Dialog */}
      {modalTarget && (
        <div
          role="dialog"
          aria-modal="true"
          aria-label="Classify financial transaction"
          className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-950/55 backdrop-blur-xs"
        >
          <div className="w-full max-w-lg rounded-2xl bg-white dark:bg-[#0b192c] border border-[#103c60]/15 dark:border-[#43b1d4]/25 shadow-xl p-6 space-y-5">
            <div className="flex items-start justify-between gap-4">
              <div>
                <div className="text-xs font-medium text-[#64748b] dark:text-[#94a3b8]">
                  {formatKes(modalTarget.tx.amount)} ·{' '}
                  {modalTarget.tx.referenceCode || modalTarget.tx.clientEventId || 'Captured transaction'}
                </div>
                <h2 className="mt-1 font-display text-lg font-bold text-[#103c60] dark:text-[#f1f5f9]">
                  {modalTarget.mode === 'product'
                    ? 'Link Transaction to Product'
                    : modalTarget.mode === 'service'
                    ? 'Link Transaction to Service'
                    : 'Remove From Business Transactions'}
                </h2>
              </div>

              <button
                type="button"
                onClick={() => setModalTarget(null)}
                aria-label="Close classification dialog"
                className="p-1.5 rounded-lg text-[#64748b] hover:text-[#103c60] dark:hover:text-[#f1f5f9] cursor-pointer"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            {/* Mode Switcher */}
            <div className="grid grid-cols-3 gap-1 p-1 rounded-xl bg-slate-100 dark:bg-[#07111e]">
              {(
                [
                  { id: 'product', label: 'Product' },
                  { id: 'service', label: 'Service' },
                  { id: 'non_business', label: 'Non-Business' },
                ] as { id: ClassificationModalMode; label: string }[]
              ).map((m) => (
                <button
                  key={m.id}
                  type="button"
                  onClick={() => setModalTarget({ ...modalTarget, mode: m.id })}
                  className={`py-2 px-3 rounded-lg text-xs font-semibold transition-colors cursor-pointer whitespace-nowrap ${
                    modalTarget.mode === m.id
                      ? 'bg-white dark:bg-[#132945] text-[#103c60] dark:text-[#f1f5f9] shadow-xs'
                      : 'text-[#64748b] dark:text-[#94a3b8]'
                  }`}
                >
                  {m.label}
                </button>
              ))}
            </div>

            {modalTarget.mode === 'product' && (
              <div className="space-y-4">
                <div className="flex items-center gap-2 text-xs">
                  <button
                    type="button"
                    onClick={() => setProductLinkMode('new_sale')}
                    className={`flex-1 py-2 px-3 rounded-xl border font-medium cursor-pointer ${
                      productLinkMode === 'new_sale'
                        ? 'border-[#14558a] dark:border-[#43b1d4] bg-[#14558a]/8 dark:bg-[#43b1d4]/12 text-[#103c60] dark:text-[#f1f5f9]'
                        : 'border-slate-200 dark:border-slate-700 text-[#64748b]'
                    }`}
                  >
                    Create New Sale
                  </button>
                  <button
                    type="button"
                    onClick={() => setProductLinkMode('existing_sale')}
                    className={`flex-1 py-2 px-3 rounded-xl border font-medium cursor-pointer ${
                      productLinkMode === 'existing_sale'
                        ? 'border-[#14558a] dark:border-[#43b1d4] bg-[#14558a]/8 dark:bg-[#43b1d4]/12 text-[#103c60] dark:text-[#f1f5f9]'
                        : 'border-slate-200 dark:border-slate-700 text-[#64748b]'
                    }`}
                  >
                    Associate Existing Sale ({unlinkedSales.length})
                  </button>
                </div>

                {productLinkMode === 'new_sale' ? (
                  <>
                    <div className="space-y-1.5">
                      <label className="block text-xs font-medium text-[#334155] dark:text-[#cbd5e1]">
                        Find &amp; Select Product
                      </label>
                      <input
                        type="search"
                        value={productSearch}
                        onChange={(e) => setProductSearch(e.target.value)}
                        placeholder="Filter products..."
                        className="w-full px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#07111e] text-xs"
                      />
                      <select
                        value={selectedProductId}
                        onChange={(e) => {
                          setSelectedProductId(e.target.value);
                          const p = products.find((item) => String(item.id) === e.target.value);
                          if (p && p.sellPrice > 0) {
                            setProductUnitPrice(String(p.sellPrice));
                          }
                        }}
                        className="w-full px-3 py-2.5 rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#07111e] text-xs"
                      >
                        <option value="">-- Select a product --</option>
                        {filteredProducts.map((p) => (
                          <option key={p.id} value={String(p.id)}>
                            {p.name} · Stock: {p.stockQty} · {formatKes(p.sellPrice)}
                          </option>
                        ))}
                      </select>
                      {selectedProductObj && (
                        <div className="text-[11px] text-[#64748b] dark:text-[#94a3b8] font-mono-data tabular-nums">
                          Available stock: {selectedProductObj.stockQty} · Catalogue price: {formatKes(selectedProductObj.sellPrice)}
                        </div>
                      )}
                    </div>

                    <div className="grid grid-cols-2 gap-3">
                      <div>
                        <label className="block text-xs font-medium text-[#334155] dark:text-[#cbd5e1] mb-1">
                          Quantity
                        </label>
                        <input
                          type="number"
                          min={1}
                          value={productQty}
                          onChange={(e) => setProductQty(e.target.value)}
                          className="w-full px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#07111e] text-xs font-mono-data tabular-nums"
                        />
                      </div>
                      <div>
                        <label className="block text-xs font-medium text-[#334155] dark:text-[#cbd5e1] mb-1">
                          Unit Sell Price (Ksh)
                        </label>
                        <input
                          type="number"
                          min={1}
                          step="0.01"
                          value={productUnitPrice}
                          onChange={(e) => setProductUnitPrice(e.target.value)}
                          className="w-full px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#07111e] text-xs font-mono-data tabular-nums"
                        />
                      </div>
                    </div>
                  </>
                ) : (
                  <div className="space-y-1.5">
                    <label className="block text-xs font-medium text-[#334155] dark:text-[#cbd5e1]">
                      Select Unlinked Existing Sale (No stock double-deduction)
                    </label>
                    {unlinkedSales.length === 0 ? (
                      <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3 text-xs text-[#64748b]">
                        No unlinked recent sales found. Switch to Create New Sale to record and link one atomically.
                      </div>
                    ) : (
                      <select
                        value={selectedExistingSaleId}
                        onChange={(e) => setSelectedExistingSaleId(e.target.value)}
                        className="w-full px-3 py-2.5 rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#07111e] text-xs"
                      >
                        <option value="">-- Select an existing sale --</option>
                        {unlinkedSales.map((s) => (
                          <option key={s.saleId} value={String(s.saleId)}>
                            Sale #{s.saleId} · {s.productName} × {s.qtySold} · {formatKes(s.totalPrice)}
                          </option>
                        ))}
                      </select>
                    )}
                  </div>
                )}

                <div>
                  <label className="block text-xs font-medium text-[#334155] dark:text-[#cbd5e1] mb-1">
                    Note (optional)
                  </label>
                  <input
                    type="text"
                    value={productNote}
                    onChange={(e) => setProductNote(e.target.value)}
                    placeholder="Optional sale note"
                    className="w-full px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#07111e] text-xs"
                  />
                </div>

                <div className="flex justify-end gap-2 pt-2">
                  <button
                    type="button"
                    onClick={() => setModalTarget(null)}
                    className="min-h-[40px] px-4 py-2 rounded-xl text-xs font-medium text-[#64748b] hover:text-[#103c60] cursor-pointer"
                  >
                    Cancel
                  </button>
                  <button
                    type="button"
                    disabled={
                      busyEventId === modalTarget.tx.id ||
                      (productLinkMode === 'new_sale'
                        ? !selectedProductId
                        : !selectedExistingSaleId)
                    }
                    onClick={handleSubmitProduct}
                    className="min-h-[40px] px-4 py-2 rounded-xl bg-[#14558a] hover:bg-[#103c60] dark:bg-[#43b1d4] dark:hover:bg-[#348fc2] text-white dark:text-[#060d18] text-xs font-semibold cursor-pointer disabled:opacity-50"
                  >
                    {busyEventId === modalTarget.tx.id
                      ? 'Saving...'
                      : productLinkMode === 'existing_sale'
                      ? 'Link Existing Sale'
                      : 'Confirm Sale & Link'}
                  </button>
                </div>
              </div>
            )}

            {modalTarget.mode === 'service' && (
              <div className="space-y-4">
                <div>
                  <label className="block text-xs font-medium text-[#334155] dark:text-[#cbd5e1] mb-1">
                    Service Type
                  </label>
                  <select
                    value={serviceType}
                    onChange={(e) => setServiceType(e.target.value)}
                    className="w-full px-3 py-2.5 rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#07111e] text-xs"
                  >
                    {SERVICE_OPERATION_OPTIONS.map((opt) => (
                      <option key={opt.code} value={opt.code}>
                        {opt.label} ({opt.unitLabel})
                      </option>
                    ))}
                  </select>
                </div>

                <div className="grid grid-cols-3 gap-3">
                  <div>
                    <label className="block text-xs font-medium text-[#334155] dark:text-[#cbd5e1] mb-1">
                      Qty ({selectedServiceOption.unitLabel})
                    </label>
                    <input
                      type="number"
                      min={1}
                      value={serviceQty}
                      onChange={(e) => setServiceQty(e.target.value)}
                      className="w-full px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#07111e] text-xs font-mono-data tabular-nums"
                    />
                  </div>
                  <div>
                    <label className="block text-xs font-medium text-[#334155] dark:text-[#cbd5e1] mb-1">
                      Unit Price
                    </label>
                    <input
                      type="number"
                      min={0}
                      step="0.01"
                      value={serviceUnitPrice}
                      onChange={(e) => setServiceUnitPrice(e.target.value)}
                      className="w-full px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#07111e] text-xs font-mono-data tabular-nums"
                    />
                  </div>
                  <div>
                    <label className="block text-xs font-medium text-[#334155] dark:text-[#cbd5e1] mb-1">
                      Discount
                    </label>
                    <input
                      type="number"
                      min={0}
                      step="0.01"
                      value={serviceDiscount}
                      onChange={(e) => setServiceDiscount(e.target.value)}
                      className="w-full px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#07111e] text-xs font-mono-data tabular-nums"
                    />
                  </div>
                </div>

                <div className="text-xs text-[#334155] dark:text-[#cbd5e1] font-mono-data tabular-nums">
                  Net service amount: <strong className="text-[#103c60] dark:text-[#f1f5f9]">{formatKes(computedServiceNet)}</strong> (links existing payment without duplicate record)
                </div>

                <div>
                  <label className="block text-xs font-medium text-[#334155] dark:text-[#cbd5e1] mb-1">
                    Note (optional)
                  </label>
                  <input
                    type="text"
                    value={serviceNote}
                    onChange={(e) => setServiceNote(e.target.value)}
                    placeholder="Customer or service detail"
                    className="w-full px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#07111e] text-xs"
                  />
                </div>

                <div className="flex justify-end gap-2 pt-2">
                  <button
                    type="button"
                    onClick={() => setModalTarget(null)}
                    className="min-h-[40px] px-4 py-2 rounded-xl text-xs font-medium text-[#64748b] hover:text-[#103c60] cursor-pointer"
                  >
                    Cancel
                  </button>
                  <button
                    type="button"
                    disabled={busyEventId === modalTarget.tx.id}
                    onClick={handleSubmitService}
                    className="min-h-[40px] px-4 py-2 rounded-xl bg-[#14558a] hover:bg-[#103c60] dark:bg-[#43b1d4] dark:hover:bg-[#348fc2] text-white dark:text-[#060d18] text-xs font-semibold cursor-pointer disabled:opacity-50"
                  >
                    {busyEventId === modalTarget.tx.id ? 'Linking...' : 'Confirm Service Link'}
                  </button>
                </div>
              </div>
            )}

            {modalTarget.mode === 'non_business' && (
              <div className="space-y-4">
                <p className="text-xs leading-relaxed text-[#475569] dark:text-[#cbd5e1]">
                  This excludes the transaction from the Business Balance and financial totals while preserving the original captured payment record and immutable classification history for auditability.
                </p>

                <div>
                  <label className="block text-xs font-medium text-[#334155] dark:text-[#cbd5e1] mb-1">
                    Reason for Exclusion
                  </label>
                  <input
                    type="text"
                    value={nonBizReason}
                    onChange={(e) => setNonBizReason(e.target.value)}
                    placeholder="Personal transfer, mistaken deposit, etc."
                    className="w-full px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#07111e] text-xs"
                  />
                </div>

                <div className="flex justify-end gap-2 pt-2">
                  <button
                    type="button"
                    onClick={() => setModalTarget(null)}
                    className="min-h-[40px] px-4 py-2 rounded-xl text-xs font-medium text-[#64748b] hover:text-[#103c60] cursor-pointer"
                  >
                    Cancel
                  </button>
                  <button
                    type="button"
                    disabled={busyEventId === modalTarget.tx.id}
                    onClick={handleSubmitNonBusiness}
                    className="min-h-[40px] px-4 py-2 rounded-xl bg-slate-800 hover:bg-slate-900 dark:bg-slate-200 dark:hover:bg-white text-white dark:text-slate-900 text-xs font-semibold cursor-pointer disabled:opacity-50"
                  >
                    {busyEventId === modalTarget.tx.id ? 'Updating...' : 'Remove From Business Records'}
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
