import {
  ArrowRight,
  Briefcase,
  Clock,
  Layers,
  Package,
  Palette,
  Receipt,
  SlidersHorizontal,
} from 'lucide-react';
import { motion } from 'motion/react';
import { formatKes, type BusinessBalanceSummary } from '../services/financialBalance';
import type { NavTab } from '../types/app';
import { BrandMark } from './BrandMark';

interface OverviewViewProps {
  onNavigate: (tab: NavTab) => void;
  itemCount: number;
  completedCount: number;
  reducedMotion: boolean;
  summary?: BusinessBalanceSummary;
  pendingCount?: number;
}

export function OverviewView({
  onNavigate,
  itemCount,
  completedCount,
  reducedMotion,
  summary,
  pendingCount = 0,
}: OverviewViewProps) {
  const tapMotion = reducedMotion ? undefined : { scale: 0.985 };

  return (
    <div className="space-y-8">
      {/* Authoritative Classified Business Balance Card */}
      {summary && (
        <section
          aria-label="Authoritative Store Financial Status"
          className="emofah-glass rounded-3xl p-6 sm:p-8 space-y-5"
        >
          <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
            <div>
              <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-[#14558a] dark:text-[#43b1d4]">
                <Receipt className="w-3.5 h-3.5" />
                <span>Classified Business Balance · Shared Supabase Ledger</span>
              </div>
              <h2 className="mt-1 font-display text-2xl sm:text-3xl font-bold text-[#103c60] dark:text-[#f1f5f9]">
                {formatKes(summary.businessBalance)}
              </h2>
              <p className="text-xs text-[#64748b] dark:text-[#94a3b8] mt-0.5">
                Excludes pending and personal transfers · Derived only from confirmed business transactions
              </p>
            </div>

            <button
              type="button"
              onClick={() => onNavigate('money')}
              className="min-h-[42px] px-4 py-2 rounded-xl bg-[#14558a] hover:bg-[#103c60] dark:bg-[#43b1d4] dark:hover:bg-[#348fc2] text-white dark:text-[#060d18] text-xs font-semibold inline-flex items-center gap-2 transition-colors cursor-pointer self-start sm:self-auto"
            >
              <span>Open Transactions &amp; Classify</span>
              <ArrowRight className="w-4 h-4" />
            </button>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 pt-2">
            <div className="p-3.5 rounded-2xl bg-white/70 dark:bg-[#0d1f36]/70 border border-[#103c60]/10 dark:border-[#43b1d4]/15">
              <div className="flex items-center justify-between text-xs text-[#64748b] dark:text-[#94a3b8]">
                <span>Product Sales</span>
                <Package className="w-3.5 h-3.5 text-emerald-600 dark:text-emerald-400" />
              </div>
              <div className="font-mono-data tabular-nums text-lg font-bold text-emerald-700 dark:text-emerald-400 mt-1">
                {formatKes(summary.businessProductNet)}
              </div>
              <div className="text-[11px] text-[#64748b] dark:text-[#94a3b8] mt-0.5 font-mono-data tabular-nums">
                {summary.businessProductCount} sale(s) · Single-counted inventory
              </div>
            </div>

            <div className="p-3.5 rounded-2xl bg-white/70 dark:bg-[#0d1f36]/70 border border-[#103c60]/10 dark:border-[#43b1d4]/15">
              <div className="flex items-center justify-between text-xs text-[#64748b] dark:text-[#94a3b8]">
                <span>Service Revenue</span>
                <Briefcase className="w-3.5 h-3.5 text-[#14558a] dark:text-[#43b1d4]" />
              </div>
              <div className="font-mono-data tabular-nums text-lg font-bold text-[#14558a] dark:text-[#43b1d4] mt-1">
                {formatKes(summary.businessServiceNet)}
              </div>
              <div className="text-[11px] text-[#64748b] dark:text-[#94a3b8] mt-0.5 font-mono-data tabular-nums">
                {summary.businessServiceCount} service operation(s) net
              </div>
            </div>

            <div className="p-3.5 rounded-2xl bg-white/70 dark:bg-[#0d1f36]/70 border border-[#103c60]/10 dark:border-[#43b1d4]/15">
              <div className="flex items-center justify-between text-xs text-[#64748b] dark:text-[#94a3b8]">
                <span>Excluded / Pending</span>
                <Clock className="w-3.5 h-3.5 text-amber-600 dark:text-amber-400" />
              </div>
              <div className="font-mono-data tabular-nums text-lg font-bold text-[#475569] dark:text-[#cbd5e1] mt-1">
                {formatKes(summary.pendingExcludedAmount + summary.nonBusinessExcludedAmount)}
              </div>
              <div className="text-[11px] text-[#64748b] dark:text-[#94a3b8] mt-0.5 font-mono-data tabular-nums">
                {summary.pendingCount} pending · {summary.nonBusinessCount} non-business
              </div>
            </div>
          </div>

          {pendingCount > 0 && (
            <div className="p-3 rounded-xl border border-amber-300 dark:border-amber-700/60 bg-amber-50/90 dark:bg-amber-950/30 flex items-center justify-between gap-3 text-xs">
              <div className="flex items-center gap-2 text-amber-900 dark:text-amber-200">
                <Clock className="w-4 h-4 shrink-0 text-amber-600 dark:text-amber-400" />
                <span>
                  <strong>{pendingCount} transaction{pendingCount > 1 ? 's' : ''}</strong> captured automatically and awaiting classification (excluded from balance).
                </span>
              </div>
              <button
                type="button"
                onClick={() => onNavigate('money')}
                className="font-semibold text-amber-900 dark:text-amber-300 hover:underline shrink-0 cursor-pointer"
              >
                Classify Now →
              </button>
            </div>
          )}
        </section>
      )}

      <section className="emofah-glass rounded-3xl p-6 sm:p-10">
        <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-8">
          <div className="space-y-4 max-w-xl">
            <div className="flex items-center gap-2 text-xs font-medium text-[#14558a] dark:text-[#43b1d4]">
              <span>Emofah Agencies</span>
              <span aria-hidden="true">·</span>
              <span>Application Foundation</span>
            </div>

            <h1 className="font-display text-3xl sm:text-4xl font-bold tracking-tight text-[#103c60] dark:text-[#f1f5f9] [text-wrap:balance]">
              Equipping Work. Enabling Progress.
            </h1>

            <p className="text-[15px] sm:text-base leading-relaxed text-[#475569] dark:text-[#cbd5e1]">
              A responsive Android-ready workspace shell crafted with Emofah
              brand colors, adaptive light and dark surfaces, frosted glass
              depth, and tactile motion—ready for your functional modules.
            </p>

            <div className="pt-2 flex flex-wrap items-center gap-3">
              <motion.button
                type="button"
                whileTap={tapMotion}
                onClick={() => onNavigate('workspace')}
                className="min-h-[44px] px-5 py-2.5 rounded-xl bg-[#14558a] hover:bg-[#103c60] dark:bg-[#43b1d4] dark:hover:bg-[#348fc2] text-white dark:text-[#060d18] text-sm font-semibold inline-flex items-center gap-2 transition-colors cursor-pointer whitespace-nowrap shrink-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#43b1d4]"
              >
                <span>Open Workspace</span>
                <ArrowRight className="w-4 h-4" />
              </motion.button>

              <motion.button
                type="button"
                whileTap={tapMotion}
                onClick={() => onNavigate('brand')}
                className="min-h-[44px] px-5 py-2.5 rounded-xl bg-white/70 hover:bg-white dark:bg-[#0d1f36]/80 dark:hover:bg-[#122a48] text-[#103c60] dark:text-[#f1f5f9] border border-[#103c60]/12 dark:border-[#43b1d4]/20 text-sm font-medium inline-flex items-center gap-2 transition-colors cursor-pointer whitespace-nowrap shrink-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#43b1d4]"
              >
                <span>Explore Brand Kit</span>
              </motion.button>
            </div>
          </div>

          <div className="shrink-0 flex items-center justify-center md:justify-end">
            <div className="relative flex flex-col items-center justify-center p-6 sm:p-8 rounded-3xl bg-white/60 dark:bg-[#0a182b]/70 border border-[#103c60]/10 dark:border-[#43b1d4]/15">
              <BrandMark size="xl" />
              <div className="mt-4 text-center">
                <div className="font-display text-sm font-bold tracking-wide text-[#103c60] dark:text-[#f1f5f9]">
                  EMOFAH
                </div>
                <div className="font-display text-xs font-medium tracking-[0.22em] text-[#1768a7] dark:text-[#43b1d4]">
                  AGENCIES
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section aria-label="Architecture Foundations">
        <div className="flex items-baseline justify-between mb-4 px-1">
          <h2 className="font-display text-lg font-semibold text-[#103c60] dark:text-[#f1f5f9]">
            Core Foundations
          </h2>
          <span className="text-xs text-[#64748b] dark:text-[#94a3b8]">
            Ready for specifications
          </span>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 sm:gap-5">
          <motion.button
            type="button"
            whileTap={tapMotion}
            onClick={() => onNavigate('workspace')}
            className="emofah-glass rounded-2xl p-6 text-left flex flex-col justify-between gap-4 hover:border-[#1768a7]/30 dark:hover:border-[#43b1d4]/35 transition-colors cursor-pointer group focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#43b1d4]"
          >
            <div className="flex items-center justify-between">
              <div className="h-10 w-10 rounded-xl bg-[#14558a]/10 dark:bg-[#43b1d4]/15 text-[#14558a] dark:text-[#43b1d4] flex items-center justify-center">
                <Layers className="w-5 h-5" />
              </div>
              <span className="font-mono-data text-xs text-[#64748b] dark:text-[#94a3b8]">
                {completedCount}/{itemCount} ready
              </span>
            </div>
            <div>
              <h3 className="font-display text-base font-semibold text-[#103c60] dark:text-[#f1f5f9] group-hover:text-[#1768a7] dark:group-hover:text-[#43b1d4] transition-colors">
                Modular Workspace
              </h3>
              <p className="mt-1.5 text-sm leading-relaxed text-[#475569] dark:text-[#94a3b8]">
                Notion-inspired list rows, filter segments, and bottom-sheet
                capture ready to host upcoming features.
              </p>
            </div>
          </motion.button>

          <motion.button
            type="button"
            whileTap={tapMotion}
            onClick={() => onNavigate('brand')}
            className="emofah-glass rounded-2xl p-6 text-left flex flex-col justify-between gap-4 hover:border-[#1768a7]/30 dark:hover:border-[#43b1d4]/35 transition-colors cursor-pointer group focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#43b1d4]"
          >
            <div className="flex items-center justify-between">
              <div className="h-10 w-10 rounded-xl bg-[#1768a7]/10 dark:bg-[#43b1d4]/15 text-[#1768a7] dark:text-[#43b1d4] flex items-center justify-center">
                <Palette className="w-5 h-5" />
              </div>
              <span className="font-mono-data text-xs text-[#64748b] dark:text-[#94a3b8]">
                5 brand tones
              </span>
            </div>
            <div>
              <h3 className="font-display text-base font-semibold text-[#103c60] dark:text-[#f1f5f9] group-hover:text-[#1768a7] dark:group-hover:text-[#43b1d4] transition-colors">
                Emofah Color System
              </h3>
              <p className="mt-1.5 text-sm leading-relaxed text-[#475569] dark:text-[#94a3b8]">
                Deep Navy to Sky Cyan palette calibrated for daylight clarity
                and low-glare dark mode contrast.
              </p>
            </div>
          </motion.button>

          <motion.button
            type="button"
            whileTap={tapMotion}
            onClick={() => onNavigate('settings')}
            className="emofah-glass rounded-2xl p-6 text-left flex flex-col justify-between gap-4 hover:border-[#1768a7]/30 dark:hover:border-[#43b1d4]/35 transition-colors cursor-pointer group focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#43b1d4]"
          >
            <div className="flex items-center justify-between">
              <div className="h-10 w-10 rounded-xl bg-[#348fc2]/15 dark:bg-[#43b1d4]/15 text-[#14558a] dark:text-[#43b1d4] flex items-center justify-center">
                <SlidersHorizontal className="w-5 h-5" />
              </div>
              <span className="font-mono-data text-xs text-[#64748b] dark:text-[#94a3b8]">
                Custom controls
              </span>
            </div>
            <div>
              <h3 className="font-display text-base font-semibold text-[#103c60] dark:text-[#f1f5f9] group-hover:text-[#1768a7] dark:group-hover:text-[#43b1d4] transition-colors">
                Appearance & Motion
              </h3>
              <p className="mt-1.5 text-sm leading-relaxed text-[#475569] dark:text-[#94a3b8]">
                Tune glass blur depth, light or dark surfaces, layout density,
                and reduced-motion accessibility.
              </p>
            </div>
          </motion.button>
        </div>
      </section>
    </div>
  );
}
