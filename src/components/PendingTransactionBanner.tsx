import { ArrowDownLeft, ArrowUpRight, BellRing, Briefcase, Package, Ban } from 'lucide-react';
import { formatKes, type SharedFinancialTransaction } from '../services/financialBalance';

interface PendingTransactionBannerProps {
  pendingTransactions: SharedFinancialTransaction[];
  onReviewAll: () => void;
  onQuickAction: (
    tx: SharedFinancialTransaction,
    mode: 'product' | 'service' | 'non_business'
  ) => void;
}

function formatDateTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleString('en-KE', {
    day: '2-digit',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function PendingTransactionBanner({
  pendingTransactions,
  onReviewAll,
  onQuickAction,
}: PendingTransactionBannerProps) {
  if (pendingTransactions.length === 0) return null;

  const latest = pendingTransactions[0];
  const isInflow = latest.direction === 'INFLOW';
  const directionLabel =
    latest.direction === 'INFLOW'
      ? 'Money Received'
      : latest.direction === 'OUTFLOW'
      ? 'Money Sent'
      : 'Transfer';

  return (
    <section
      aria-label="Pending transaction classification notification"
      className="mb-6 rounded-2xl border border-[#14558a]/25 dark:border-[#43b1d4]/30 bg-white/90 dark:bg-[#0c1d34]/90 p-4 sm:px-5 sm:py-4 shadow-xs"
    >
      <div className="flex flex-col lg:flex-row lg:items-center lg:justify-between gap-4">
        <div className="min-w-0 space-y-1">
          <div className="flex flex-wrap items-center gap-x-2.5 gap-y-1 text-xs font-medium text-[#14558a] dark:text-[#43b1d4]">
            <BellRing className="w-3.5 h-3.5 shrink-0" />
            <span>
              {pendingTransactions.length === 1
                ? '1 transaction awaiting classification'
                : `${pendingTransactions.length} transactions awaiting classification`}
            </span>
            <span aria-hidden="true">·</span>
            <span className="text-[#475569] dark:text-[#94a3b8]">Saved in Supabase</span>
          </div>

          <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
            <span
              className={`font-mono-data tabular-nums text-lg font-bold inline-flex items-center gap-1 ${
                isInflow
                  ? 'text-emerald-700 dark:text-emerald-400'
                  : 'text-rose-700 dark:text-rose-400'
              }`}
            >
              {isInflow ? (
                <ArrowDownLeft className="w-4 h-4 shrink-0" />
              ) : (
                <ArrowUpRight className="w-4 h-4 shrink-0" />
              )}
              {formatKes(latest.amount)}
            </span>

            <span className="text-sm font-semibold text-[#103c60] dark:text-[#f1f5f9]">
              {directionLabel}
            </span>

            {latest.counterparty && (
              <>
                <span aria-hidden="true" className="text-[#94a3b8]">·</span>
                <span className="text-sm text-[#334155] dark:text-[#cbd5e1] truncate max-w-[260px]">
                  {latest.counterparty}
                </span>
              </>
            )}
          </div>

          <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-[#64748b] dark:text-[#94a3b8] font-mono-data tabular-nums">
            <span>Ref: {latest.referenceCode || latest.clientEventId || latest.id.slice(0, 8)}</span>
            <span aria-hidden="true">·</span>
            <span>{formatDateTime(latest.occurredAt)}</span>
            {latest.counterpartyAccount && (
              <>
                <span aria-hidden="true">·</span>
                <span>{latest.counterpartyAccount}</span>
              </>
            )}
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2 shrink-0">
          <button
            type="button"
            onClick={() => onQuickAction(latest, 'product')}
            className="min-h-[40px] px-3.5 py-2 rounded-xl bg-[#14558a] hover:bg-[#103c60] dark:bg-[#43b1d4] dark:hover:bg-[#348fc2] text-white dark:text-[#060d18] text-xs font-semibold inline-flex items-center gap-1.5 transition-colors cursor-pointer whitespace-nowrap shrink-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#43b1d4]"
          >
            <Package className="w-3.5 h-3.5" />
            <span>Link Product</span>
          </button>

          <button
            type="button"
            onClick={() => onQuickAction(latest, 'service')}
            className="min-h-[40px] px-3.5 py-2 rounded-xl border border-[#103c60]/18 dark:border-[#43b1d4]/28 text-[#103c60] dark:text-[#f1f5f9] hover:bg-[#103c60]/5 dark:hover:bg-[#43b1d4]/10 text-xs font-semibold inline-flex items-center gap-1.5 transition-colors cursor-pointer whitespace-nowrap shrink-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#43b1d4]"
          >
            <Briefcase className="w-3.5 h-3.5" />
            <span>Link Service</span>
          </button>

          <button
            type="button"
            onClick={() => onQuickAction(latest, 'non_business')}
            className="min-h-[40px] px-3.5 py-2 rounded-xl border border-slate-200 dark:border-slate-700 text-[#475569] dark:text-[#cbd5e1] hover:bg-slate-100 dark:hover:bg-slate-800/60 text-xs font-medium inline-flex items-center gap-1.5 transition-colors cursor-pointer whitespace-nowrap shrink-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#43b1d4]"
          >
            <Ban className="w-3.5 h-3.5" />
            <span>Remove</span>
          </button>

          {pendingTransactions.length > 1 && (
            <button
              type="button"
              onClick={onReviewAll}
              className="min-h-[40px] px-3 py-2 text-xs font-semibold text-[#14558a] dark:text-[#43b1d4] hover:underline cursor-pointer whitespace-nowrap shrink-0"
            >
              View all ({pendingTransactions.length})
            </button>
          )}
        </div>
      </div>
    </section>
  );
}
