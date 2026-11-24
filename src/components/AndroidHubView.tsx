import { useState } from 'react';
import {
  Camera,
  ShoppingCart,
  Package,
  DollarSign,
  ShoppingBag,
  History,
  Home,
  Smartphone,
  Terminal,
  FolderGit2,
  CheckCircle2,
  PlusSquare,
  Receipt,
  ShieldCheck,
  Database,
  ArrowDownLeft,
  ArrowUpRight,
  Clock,
  Ban,
  Briefcase,
} from 'lucide-react';
import { motion } from 'motion/react';
import type {
  BusinessBalanceSummary,
  RecentSaleOption,
  SharedFinancialTransaction,
} from '../services/financialBalance';
import type { captureFinancialTransaction } from '../services/financialTransactions';
import type { ProductIdentity } from '../services/productCatalog';
import type { ClassificationModalMode } from './TransactionsView';
import type { NavTab } from '../types/app';

interface AndroidHubViewProps {
  onNavigate: (tab: NavTab) => void;
  reducedMotion: boolean;
  sharedTx?: {
    transactions: SharedFinancialTransaction[];
    pendingTransactions: SharedFinancialTransaction[];
    recentSales: RecentSaleOption[];
    products: ProductIdentity[];
    summary: BusinessBalanceSummary;
    busyEventId: string | null;
    captureTransaction: (params: Parameters<typeof captureFinancialTransaction>[0]) => Promise<any>;
    classifyAsProduct: (params: any) => Promise<any>;
    classifyAsService: (params: any) => Promise<any>;
    classifyAsNonBusiness: (params: any) => Promise<any>;
  };
  onOpenClassifyModal?: (
    tx: SharedFinancialTransaction,
    mode: ClassificationModalMode
  ) => void;
}

type AndroidDest =
  | 'home'
  | 'scan'
  | 'record_sale'
  | 'stock'
  | 'money'
  | 'orders'
  | 'activity';

const ANDROID_MODULES: {
  id: AndroidDest;
  label: string;
  shortLabel: string;
  icon: typeof Home;
  stageNote: string;
  inBottomBar: boolean;
}[] = [
  {
    id: 'home',
    label: 'Home',
    shortLabel: 'Home',
    icon: Home,
    stageNote: 'Blueprint Part B2 — Action-first Home dashboard.',
    inBottomBar: true,
  },
  {
    id: 'scan',
    label: 'Scan',
    shortLabel: 'Scan',
    icon: Camera,
    stageNote: 'Embedded MobileNetV3 TFLite Vision AI, barcode, ML Kit OCR & dHash product identification offline.',
    inBottomBar: true,
  },
  {
    id: 'record_sale',
    label: 'Record Sale',
    shortLabel: 'Sale',
    icon: ShoppingCart,
    stageNote: 'Rapid checkout, persisted payment choice & unrestricted price adjustments with reason tracking.',
    inBottomBar: true,
  },
  {
    id: 'stock',
    label: 'Stock',
    shortLabel: 'Stock',
    icon: Package,
    stageNote: 'Stage 4.1–4.3: Stock lookup, receiving stock & stock adjustments with reason codes & notes.',
    inBottomBar: true,
  },
  {
    id: 'money',
    label: 'Money',
    shortLabel: 'Money',
    icon: DollarSign,
    stageNote: 'Stage 5.3: Financial message parser & validation (Received, Paybill, Till, Withdrawal, Fee & Balance).',
    inBottomBar: true,
  },
  {
    id: 'orders',
    label: 'Orders',
    shortLabel: 'Orders',
    icon: ShoppingBag,
    stageNote: 'Phase 6: Online orders retrieval & sale conversion.',
    inBottomBar: false,
  },
  {
    id: 'activity',
    label: 'Activity',
    shortLabel: 'Activity',
    icon: History,
    stageNote: 'Stage 2.4 & 3.6: Today’s sales, authorized safe reversals, and offline sync queue.',
    inBottomBar: false,
  },
];

export function AndroidHubView({
  onNavigate,
  reducedMotion,
  sharedTx,
  onOpenClassifyModal,
}: AndroidHubViewProps) {
  const [phoneDest, setPhoneDest] = useState<AndroidDest>('home');
  const [simTorch, setSimTorch] = useState(false);
  const [simStep, setSimStep] = useState<'viewfinder' | 'review' | 'candidates'>('viewfinder');
  const [simQty, setSimQty] = useState(1);
  const [simPayment, setSimPayment] = useState<'Cash' | 'M-Pesa' | 'Other'>('Cash');
  const [simOfflineMode, setSimOfflineMode] = useState(false);
  const [simTodaySales, setSimTodaySales] = useState({ count: 0, total: 0, pending: 0 });
  const [simSaleMsg, setSimSaleMsg] = useState<{ mode: 'online' | 'offline'; text: string } | null>(null);
  const [simStockSubMode, setSimStockSubMode] = useState<'lookup' | 'receive' | 'adjust'>('lookup');
  const [simReceiveQty, setSimReceiveQty] = useState(10);
  const [simReceivedUnits, setSimReceivedUnits] = useState(0);
  const [simReceiveMsg, setSimReceiveMsg] = useState<string | null>(null);
  const [simAdjustDelta, setSimAdjustDelta] = useState(-2);
  const [simAdjustReason, setSimAdjustReason] = useState('DAMAGED');
  const [simAdjustedUnits, setSimAdjustedUnits] = useState(0);
  const [simAdjustMsg, setSimAdjustMsg] = useState<string | null>(null);
  const [simSmsConsent, setSimSmsConsent] = useState(true);
  const [simMpesaEvents, setSimMpesaEvents] = useState<
    { ref: string; direction: 'INFLOW' | 'OUTFLOW'; channel: string; amount: number; fee?: number; balance?: number; party: string }[]
  >([]);
  const [simMoneyMsg, setSimMoneyMsg] = useState<string | null>(null);
  const [customSmsText, setCustomSmsText] = useState('');
  const [isCapturingSms, setIsCapturingSms] = useState(false);
  const tapMotion = reducedMotion ? undefined : { scale: 0.985 };

  const activeModule =
    ANDROID_MODULES.find((m) => m.id === phoneDest) ?? ANDROID_MODULES[0];

  return (
    <div className="space-y-8">
      {/* Header Banner */}
      <section className="emofah-glass rounded-3xl p-6 sm:p-8 border border-[#103c60]/12 dark:border-[#43b1d4]/20">
        <div className="flex flex-col lg:flex-row lg:items-center lg:justify-between gap-6">
          <div className="space-y-3 max-w-2xl">
            <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-[#14558a]/10 dark:bg-[#43b1d4]/15 text-[#14558a] dark:text-[#43b1d4] text-xs font-semibold">
              <Smartphone className="w-3.5 h-3.5" />
              <span>Native Kotlin + Jetpack Compose · Bundled TFLite Vision AI &amp; Offline Receipt OCR</span>
            </div>
            <h1 className="font-display text-2xl sm:text-3xl font-bold text-[#103c60] dark:text-[#f1f5f9] tracking-tight">
              Emofah Native Android Companion &amp; Preview
            </h1>
            <p className="text-sm sm:text-base text-[#475569] dark:text-[#cbd5e1] leading-relaxed">
              The native Android app lives in <code className="font-mono text-xs px-1.5 py-0.5 rounded bg-[#103c60]/10 dark:bg-[#43b1d4]/15">/android</code> (Kotlin, Jetpack Compose Material 3, package <code className="font-mono text-xs px-1.5 py-0.5 rounded bg-[#103c60]/10 dark:bg-[#43b1d4]/15">com.emofah.agencies</code>) with the bundled <code className="font-mono text-xs">emofah_vision_model.tflite</code> weights inside the APK, local receipt layout parsing, and pre-configured Supabase credentials. Use the interactive preview alongside to inspect screens, or build <code className="font-mono text-xs">app-debug.apk</code> directly from <code className="font-mono text-xs">/android</code>.
            </p>
          </div>
        </div>
      </section>

      {/* Interactive Phone Preview + Build Instructions */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
        {/* Interactive Android Screen Preview */}
        <section className="lg:col-span-5 flex flex-col items-center">
          <div className="w-full max-w-[340px] rounded-[2.5rem] border-[6px] border-[#0b1f33] dark:border-[#1e3a5f] bg-[#f8fafc] dark:bg-[#060d18] shadow-2xl overflow-hidden flex flex-col min-h-[620px]">
            {/* Android Status Bar */}
            <div className="px-6 pt-3 pb-1.5 flex items-center justify-between text-[11px] font-mono text-[#475569] dark:text-[#94a3b8]">
              <span>9:41</span>
              <span className="h-3.5 w-16 rounded-full bg-[#0b1f33] dark:bg-[#1e3a5f]" />
              <span>Android 14</span>
            </div>

            {/* Material 3 TopAppBar */}
            <div className="px-4 py-3 border-b border-[#103c60]/10 dark:border-[#43b1d4]/15 flex items-center justify-between bg-white/80 dark:bg-[#0d1f36]/90">
              <span className="font-display text-sm font-bold text-[#103c60] dark:text-[#f1f5f9]">
                Emofah · {activeModule.label}
              </span>
              <div className="flex items-center gap-1">
                <button
                  type="button"
                  onClick={() => setPhoneDest('orders')}
                  title="Orders"
                  className={`p-2 rounded-lg transition-colors cursor-pointer ${
                    phoneDest === 'orders'
                      ? 'bg-[#14558a]/15 text-[#14558a] dark:bg-[#43b1d4]/20 dark:text-[#43b1d4]'
                      : 'text-[#475569] dark:text-[#94a3b8] hover:bg-[#103c60]/5'
                  }`}
                >
                  <ShoppingBag className="w-4 h-4" />
                </button>
                <button
                  type="button"
                  onClick={() => setPhoneDest('activity')}
                  title="Activity"
                  className={`p-2 rounded-lg transition-colors cursor-pointer ${
                    phoneDest === 'activity'
                      ? 'bg-[#14558a]/15 text-[#14558a] dark:bg-[#43b1d4]/20 dark:text-[#43b1d4]'
                      : 'text-[#475569] dark:text-[#94a3b8] hover:bg-[#103c60]/5'
                  }`}
                >
                  <History className="w-4 h-4" />
                </button>
              </div>
            </div>

            {/* Screen Body */}
            <div className="flex-1 p-4 overflow-y-auto space-y-3">
              {phoneDest === 'home' ? (
                <>
                  <h2 className="font-display text-base font-bold text-[#103c60] dark:text-[#f1f5f9]">
                    Daily Operations
                  </h2>

                  {/* Shared Pending-Classification Triage Card */}
                  {sharedTx && sharedTx.pendingTransactions.length > 0 && (() => {
                    const pendingTx = sharedTx.pendingTransactions[0];
                    const isInflow = pendingTx.direction === 'INFLOW';
                    return (
                      <div className="rounded-2xl p-3 bg-[#eaf4fd] dark:bg-[#0c223c] border border-[#14558a]/30 dark:border-[#43b1d4]/35 space-y-2">
                        <div className="flex items-start justify-between gap-1.5">
                          <div className="flex items-center gap-2">
                            <div className="w-7 h-7 rounded-lg bg-[#14558a]/15 dark:bg-[#43b1d4]/20 flex items-center justify-center text-[#14558a] dark:text-[#43b1d4] shrink-0">
                              {isInflow ? (
                                <ArrowDownLeft className="w-4 h-4" />
                              ) : (
                                <ArrowUpRight className="w-4 h-4" />
                              )}
                            </div>
                            <div>
                              <div className="text-xs font-bold text-[#103c60] dark:text-[#f1f5f9]">
                                {isInflow ? 'Received' : 'Sent'} · Ksh {pendingTx.amount.toLocaleString()}
                              </div>
                              <div className="text-[10px] text-[#64748b] dark:text-[#94a3b8] truncate max-w-[150px]">
                                {isInflow ? 'From ' : 'To '}
                                {pendingTx.counterparty || 'Counterparty'} · Ref {pendingTx.referenceCode || pendingTx.id.slice(0, 8)}
                              </div>
                            </div>
                          </div>
                          <span className="px-1.5 py-0.5 rounded text-[9px] font-bold bg-amber-500/20 text-amber-800 dark:text-amber-300 shrink-0">
                            {sharedTx.pendingTransactions.length > 1
                              ? `1 of ${sharedTx.pendingTransactions.length}`
                              : 'Classify'}
                          </span>
                        </div>

                        <div className="text-[10px] text-[#103c60] dark:text-[#f1f5f9] leading-tight">
                          Saved in Supabase · awaiting classification (excluded from Business Balance until classified)
                        </div>

                        <div className="grid grid-cols-2 gap-1.5">
                          <button
                            type="button"
                            onClick={() => onOpenClassifyModal?.(pendingTx, 'product')}
                            className="h-8 rounded-xl bg-[#14558a] dark:bg-[#43b1d4] text-white dark:text-[#060d18] text-[10px] font-bold flex items-center justify-center cursor-pointer"
                          >
                            Link Product
                          </button>
                          <button
                            type="button"
                            onClick={() => onOpenClassifyModal?.(pendingTx, 'service')}
                            className="h-8 rounded-xl border border-[#14558a]/35 dark:border-[#43b1d4]/40 bg-white/60 dark:bg-[#0d1f36]/60 text-[#103c60] dark:text-[#f1f5f9] text-[10px] font-bold flex items-center justify-center cursor-pointer"
                          >
                            Link Service
                          </button>
                        </div>

                        <div className="flex items-center justify-between pt-0.5 text-[10px]">
                          <span className="text-[#64748b] dark:text-[#94a3b8]">Personal or non-business?</span>
                          <button
                            type="button"
                            onClick={() => onOpenClassifyModal?.(pendingTx, 'non_business')}
                            className="text-[#14558a] dark:text-[#43b1d4] font-bold hover:underline cursor-pointer"
                          >
                            Remove from Business
                          </button>
                        </div>
                      </div>
                    );
                  })()}

                  <motion.button
                    type="button"
                    whileTap={tapMotion}
                    onClick={() => setPhoneDest('scan')}
                    className="w-full h-13 rounded-2xl bg-[#14558a] dark:bg-[#43b1d4] text-white dark:text-[#060d18] font-semibold text-sm flex items-center justify-center gap-2.5 shadow-md cursor-pointer"
                  >
                    <Camera className="w-5 h-5" />
                    <span>Scan Product</span>
                  </motion.button>

                  <motion.button
                    type="button"
                    whileTap={tapMotion}
                    onClick={() => setPhoneDest('record_sale')}
                    className="w-full h-12 rounded-2xl bg-[#1768a7] dark:bg-[#348fc2] text-white dark:text-[#060d18] font-semibold text-sm flex items-center justify-center gap-2 cursor-pointer"
                  >
                    <ShoppingCart className="w-4 h-4" />
                    <span>Record Sale</span>
                  </motion.button>

                  <div className="grid grid-cols-2 gap-2">
                    <button
                      type="button"
                      onClick={() => setPhoneDest('stock')}
                      className="h-11 px-2.5 rounded-xl border border-[#103c60]/15 dark:border-[#43b1d4]/25 bg-white dark:bg-[#0d1f36] text-xs font-medium text-[#103c60] dark:text-[#f1f5f9] flex items-center justify-center gap-1.5 cursor-pointer"
                    >
                      <PlusSquare className="w-3.5 h-3.5 text-[#14558a] dark:text-[#43b1d4]" />
                      <span>Receive Stock</span>
                    </button>
                    <button
                      type="button"
                      onClick={() => setPhoneDest('money')}
                      className="h-11 px-2.5 rounded-xl border border-[#103c60]/15 dark:border-[#43b1d4]/25 bg-white dark:bg-[#0d1f36] text-xs font-medium text-[#103c60] dark:text-[#f1f5f9] flex items-center justify-center gap-1.5 cursor-pointer"
                    >
                      <Receipt className="w-3.5 h-3.5 text-[#14558a] dark:text-[#43b1d4]" />
                      <span>Money &amp; M-Pesa</span>
                    </button>
                    <button
                      type="button"
                      onClick={() => setPhoneDest('orders')}
                      className="h-11 px-2.5 rounded-xl border border-[#103c60]/15 dark:border-[#43b1d4]/25 bg-white dark:bg-[#0d1f36] text-xs font-medium text-[#103c60] dark:text-[#f1f5f9] flex items-center justify-center gap-1.5 cursor-pointer"
                    >
                      <ShoppingBag className="w-3.5 h-3.5 text-[#14558a] dark:text-[#43b1d4]" />
                      <span>View Orders</span>
                    </button>
                    <button
                      type="button"
                      onClick={() => setPhoneDest('activity')}
                      className="h-11 px-2.5 rounded-xl border border-[#103c60]/15 dark:border-[#43b1d4]/25 bg-white dark:bg-[#0d1f36] text-xs font-medium text-[#103c60] dark:text-[#f1f5f9] flex items-center justify-center gap-1.5 cursor-pointer"
                    >
                      <History className="w-3.5 h-3.5 text-[#14558a] dark:text-[#43b1d4]" />
                      <span>Activity &amp; Sync</span>
                    </button>
                  </div>

                  {/* Honest Today & Business Balance Card */}
                  <div className="rounded-2xl p-3.5 bg-[#ebf2f7] dark:bg-[#132945] space-y-2">
                    <div className="text-xs font-semibold text-[#103c60] dark:text-[#f1f5f9]">
                      Business Balance &amp; Sync Status
                    </div>
                    {sharedTx ? (
                      <>
                        <div className="rounded-lg px-3 py-2 bg-white dark:bg-[#0d1f36] flex items-center justify-between text-[11px]">
                          <span className="text-[#475569] dark:text-[#94a3b8]">Business Balance</span>
                          <span className="font-bold text-[#103c60] dark:text-[#f1f5f9]">
                            Ksh {sharedTx.summary.businessBalance.toLocaleString()}
                          </span>
                        </div>
                        <div className="rounded-lg px-3 py-2 bg-white dark:bg-[#0d1f36] flex items-center justify-between text-[11px]">
                          <span className="text-[#475569] dark:text-[#94a3b8]">Classified Products</span>
                          <span className="font-medium text-emerald-600 dark:text-emerald-400">
                            Ksh {sharedTx.summary.businessProductNet.toLocaleString()}
                          </span>
                        </div>
                        <div className="rounded-lg px-3 py-2 bg-white dark:bg-[#0d1f36] flex items-center justify-between text-[11px]">
                          <span className="text-[#475569] dark:text-[#94a3b8]">Classified Services</span>
                          <span className="font-medium text-[#14558a] dark:text-[#43b1d4]">
                            Ksh {sharedTx.summary.businessServiceNet.toLocaleString()}
                          </span>
                        </div>
                        <div className="rounded-lg px-3 py-2 bg-white dark:bg-[#0d1f36] flex items-center justify-between text-[11px]">
                          <span className="text-[#475569] dark:text-[#94a3b8]">Pending Classification</span>
                          <span className="font-medium text-amber-600 dark:text-amber-400">
                            {sharedTx.summary.pendingCount} (excluded)
                          </span>
                        </div>
                      </>
                    ) : (
                      <div className="rounded-lg px-3 py-2 bg-white dark:bg-[#0d1f36] flex items-center justify-between text-[11px]">
                        <span className="text-[#475569] dark:text-[#94a3b8]">Sales today</span>
                        <span className="font-medium text-[#103c60] dark:text-[#f1f5f9]">
                          {simTodaySales.count} sale(s) · Ksh {simTodaySales.total.toLocaleString()}
                        </span>
                      </div>
                    )}
                    <div className="rounded-lg px-3 py-2 bg-white dark:bg-[#0d1f36] flex items-center justify-between text-[11px]">
                      <span className="text-[#475569] dark:text-[#94a3b8]">Pending queue</span>
                      <span className="font-medium text-emerald-600 dark:text-emerald-400">
                        {simTodaySales.pending === 0
                          ? 'All synced (0 pending)'
                          : `${simTodaySales.pending} pending sync`}
                      </span>
                    </div>
                  </div>
                </>
              ) : phoneDest === 'scan' ? (
                <div className="h-full flex flex-col justify-between gap-3">
                  {simStep === 'viewfinder' ? (
                    <>
                      <div className="flex items-center justify-between">
                        <span className="text-[11px] font-medium text-[#103c60] dark:text-[#f1f5f9]">
                          CameraX Viewfinder (Offline Ready)
                        </span>
                        <button
                          type="button"
                          onClick={() => setSimTorch((t) => !t)}
                          className="px-2 py-1 rounded-lg text-[10px] font-semibold bg-[#103c60]/10 dark:bg-[#43b1d4]/15 text-[#14558a] dark:text-[#43b1d4] cursor-pointer"
                        >
                          Flash: {simTorch ? 'ON' : 'OFF'}
                        </button>
                      </div>
                      <div className="flex-1 min-h-[260px] rounded-2xl bg-[#0b1f33] relative flex items-center justify-center p-4 overflow-hidden">
                        <div className="w-full h-44 rounded-2xl border-2 border-[#43b1d4] flex items-end justify-center p-3">
                          <span className="text-[10px] text-white bg-black/60 px-2 py-1 rounded-md">
                            Place one product inside frame · Tap to focus
                          </span>
                        </div>
                      </div>
                      <div className="flex gap-2">
                        <button
                          type="button"
                          onClick={() => setPhoneDest('home')}
                          className="px-3 h-11 rounded-xl border border-[#103c60]/20 dark:border-[#43b1d4]/25 text-xs font-medium text-[#103c60] dark:text-[#f1f5f9] cursor-pointer"
                        >
                          Cancel
                        </button>
                        <button
                          type="button"
                          onClick={() => setSimStep('review')}
                          className="flex-1 h-11 rounded-xl bg-[#14558a] dark:bg-[#43b1d4] text-white dark:text-[#060d18] text-xs font-semibold flex items-center justify-center gap-1.5 cursor-pointer"
                        >
                          <Camera className="w-4 h-4" />
                          <span>Capture Product</span>
                        </button>
                      </div>
                    </>
                  ) : simStep === 'review' ? (
                    <>
                      <div className="text-xs font-bold text-[#103c60] dark:text-[#f1f5f9]">
                        Review Captured Photo
                      </div>
                      <div className="flex-1 min-h-[220px] rounded-2xl bg-[#ebf2f7] dark:bg-[#132945] flex flex-col items-center justify-center p-4 text-center space-y-2">
                        <Camera className="w-10 h-10 text-[#14558a] dark:text-[#43b1d4]" />
                        <div className="text-xs font-medium text-[#103c60] dark:text-[#f1f5f9]">
                          EXIF Upright · 640×480 Recognition Copy
                        </div>
                        <div className="text-[10px] text-emerald-600 dark:text-emerald-400 font-medium">
                          Quality OK · Lighting &amp; Sharpness Passed
                        </div>
                      </div>
                      <div className="grid grid-cols-2 gap-2">
                        <button
                          type="button"
                          onClick={() => setSimStep('viewfinder')}
                          className="h-10 rounded-xl border border-[#103c60]/20 dark:border-[#43b1d4]/25 text-xs font-medium text-[#103c60] dark:text-[#f1f5f9] cursor-pointer"
                        >
                          Retake
                        </button>
                        <button
                          type="button"
                          onClick={() => setSimStep('candidates')}
                          className="h-10 rounded-xl bg-[#14558a] dark:bg-[#43b1d4] text-white dark:text-[#060d18] text-xs font-semibold cursor-pointer"
                        >
                          Identify Product
                        </button>
                      </div>
                    </>
                  ) : (
                    <>
                      <div className="rounded-xl p-2.5 bg-[#14558a]/10 dark:bg-[#43b1d4]/15 text-[11px] text-[#103c60] dark:text-[#f1f5f9]">
                        <div className="font-bold">Choose Exact Product</div>
                        <div className="text-[10px] opacity-80">Local OCR + dHash first · optional Cloud fallback</div>
                      </div>
                      <div className="space-y-2 flex-1">
                        <div className="p-2.5 rounded-xl border-2 border-[#14558a] dark:border-[#43b1d4] bg-white dark:bg-[#0d1f36] flex items-center justify-between text-[11px]">
                          <div>
                            <div className="font-semibold text-[#103c60] dark:text-[#f1f5f9]">Masking tape (medium)</div>
                            <div className="text-[10px] text-[#64748b]">ID #18 · Stock synced</div>
                          </div>
                          <span className="font-bold text-[#14558a] dark:text-[#43b1d4]">Ksh 150</span>
                        </div>
                        <div className="p-2.5 rounded-xl border border-[#103c60]/15 dark:border-[#43b1d4]/20 bg-white dark:bg-[#0d1f36] flex items-center justify-between text-[11px]">
                          <div>
                            <div className="font-semibold text-[#103c60] dark:text-[#f1f5f9]">Masking tape (large)</div>
                            <div className="text-[10px] text-[#64748b]">ID #20 · Alternative variant</div>
                          </div>
                          <span className="font-bold text-[#14558a] dark:text-[#43b1d4]">Ksh 220</span>
                        </div>
                      </div>
                      <div className="grid grid-cols-2 gap-2">
                        <button
                          type="button"
                          onClick={() => setSimStep('viewfinder')}
                          className="h-9 rounded-xl border border-[#103c60]/20 dark:border-[#43b1d4]/25 text-xs font-medium text-[#103c60] dark:text-[#f1f5f9] cursor-pointer"
                        >
                          Retake
                        </button>
                        <button
                          type="button"
                          onClick={() => {
                            setSimStep('viewfinder');
                            setSimQty(1);
                            setSimSaleMsg(null);
                            setPhoneDest('record_sale');
                          }}
                          className="h-9 rounded-xl bg-[#14558a] dark:bg-[#43b1d4] text-white dark:text-[#060d18] text-xs font-semibold cursor-pointer"
                        >
                          Confirm Match
                        </button>
                      </div>
                    </>
                  )}
                </div>
              ) : phoneDest === 'record_sale' ? (
                <div className="space-y-3">
                  <div className="p-3 rounded-2xl bg-white dark:bg-[#0d1f36] border border-[#103c60]/10 dark:border-[#43b1d4]/15 space-y-1">
                    <div className="text-xs font-bold text-[#103c60] dark:text-[#f1f5f9]">
                      Masking tape (medium)
                    </div>
                    <div className="text-[11px] font-semibold text-[#14558a] dark:text-[#43b1d4]">
                      Unit price: Ksh 150
                    </div>
                    <div className="text-[10px] text-[#64748b] dark:text-[#94a3b8]">
                      ID #18 · Cached stock: 1
                    </div>
                  </div>

                  <div className="p-3 rounded-2xl bg-[#ebf2f7] dark:bg-[#132945] space-y-2">
                    <div className="text-[11px] font-semibold text-[#103c60] dark:text-[#f1f5f9]">
                      Quantity Sold
                    </div>
                    <div className="flex items-center gap-2">
                      <button
                        type="button"
                        onClick={() => setSimQty((q) => Math.max(1, q - 1))}
                        className="w-10 h-10 rounded-xl bg-white dark:bg-[#0d1f36] font-bold text-base text-[#103c60] dark:text-[#f1f5f9] cursor-pointer"
                      >
                        -
                      </button>
                      <div className="flex-1 h-10 rounded-xl bg-white dark:bg-[#0d1f36] flex items-center justify-center font-bold text-base text-[#103c60] dark:text-[#f1f5f9]">
                        {simQty}
                      </div>
                      <button
                        type="button"
                        onClick={() => setSimQty((q) => q + 1)}
                        className="w-10 h-10 rounded-xl bg-white dark:bg-[#0d1f36] font-bold text-base text-[#103c60] dark:text-[#f1f5f9] cursor-pointer"
                      >
                        +
                      </button>
                    </div>
                  </div>

                  <div className="flex gap-1.5">
                    {(['Cash', 'M-Pesa', 'Other'] as const).map((method) => (
                      <button
                        key={method}
                        type="button"
                        onClick={() => setSimPayment(method)}
                        className={`flex-1 py-1.5 rounded-lg text-[10px] font-semibold cursor-pointer ${
                          simPayment === method
                            ? 'bg-[#14558a] dark:bg-[#43b1d4] text-white dark:text-[#060d18]'
                            : 'bg-white dark:bg-[#0d1f36] text-[#475569] dark:text-[#94a3b8]'
                        }`}
                      >
                        {method}
                      </button>
                    ))}
                  </div>

                  <div className="p-3 rounded-xl bg-[#14558a]/10 dark:bg-[#43b1d4]/15 flex items-center justify-between text-xs font-bold text-[#103c60] dark:text-[#f1f5f9]">
                    <span>{simQty} × Ksh 150</span>
                    <span>Total: Ksh {(simQty * 150).toLocaleString()}</span>
                  </div>

                  <div className="flex items-center justify-between px-1 text-[10px]">
                    <span className="text-[#64748b] dark:text-[#94a3b8]">Simulated Network</span>
                    <button
                      type="button"
                      onClick={() => setSimOfflineMode((v) => !v)}
                      className={`px-2 py-0.5 rounded-md font-semibold cursor-pointer ${
                        simOfflineMode
                          ? 'bg-amber-500/20 text-amber-700 dark:text-amber-300'
                          : 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300'
                      }`}
                    >
                      {simOfflineMode ? 'Offline (Room Queue)' : 'Online (Atomic RPC)'}
                    </button>
                  </div>

                  <button
                    type="button"
                    onClick={() => {
                      const amount = simQty * 150;
                      setSimTodaySales((prev) => ({
                        count: prev.count + 1,
                        total: prev.total + amount,
                        pending: simOfflineMode ? prev.pending + 1 : prev.pending,
                      }));
                      setSimSaleMsg(
                        simOfflineMode
                          ? {
                              mode: 'offline',
                              text: `Saved Locally Offline · Ksh ${amount.toLocaleString()} (${simPayment}). Queued in Room sync_queue_events with idempotency key.`,
                            }
                          : {
                              mode: 'online',
                              text: `Sale Synced Online · Ksh ${amount.toLocaleString()} (${simPayment}). Atomic record_android_sale RPC + Room updated.`,
                            }
                      );
                    }}
                    className="w-full h-12 rounded-2xl bg-[#14558a] dark:bg-[#43b1d4] text-white dark:text-[#060d18] text-xs font-bold cursor-pointer"
                  >
                    RECORD SALE · Ksh {(simQty * 150).toLocaleString()}
                  </button>

                  {simSaleMsg && (
                    <div
                      className={`p-2.5 rounded-xl text-[10px] leading-relaxed ${
                        simSaleMsg.mode === 'online'
                          ? 'bg-emerald-500/10 text-emerald-700 dark:text-emerald-300'
                          : 'bg-amber-500/15 text-amber-800 dark:text-amber-200'
                      }`}
                    >
                      {simSaleMsg.text}
                    </div>
                  )}
                </div>
              ) : phoneDest === 'activity' ? (
                <div className="space-y-3">
                  <div
                    className={`p-3 rounded-2xl text-[11px] space-y-1 ${
                      simOfflineMode
                        ? 'bg-amber-500/15 text-amber-900 dark:text-amber-200'
                        : 'bg-emerald-500/15 text-emerald-900 dark:text-emerald-200'
                    }`}
                  >
                    <div className="font-bold">
                      {simOfflineMode ? 'Offline Mode' : 'Connected'}
                    </div>
                    <div className="text-[10px]">
                      {simTodaySales.pending > 0
                        ? `${simTodaySales.pending} sale(s) queued safely in Room`
                        : 'All queued items synced'}
                    </div>
                  </div>

                  <div className="grid grid-cols-3 gap-1.5 text-center">
                    <div className="p-2 rounded-xl bg-white dark:bg-[#0d1f36]">
                      <div className="font-bold text-sm text-[#103c60] dark:text-[#f1f5f9]">
                        {simTodaySales.pending}
                      </div>
                      <div className="text-[9px] text-[#64748b]">Pending</div>
                    </div>
                    <div className="p-2 rounded-xl bg-white dark:bg-[#0d1f36]">
                      <div className="font-bold text-sm text-[#103c60] dark:text-[#f1f5f9]">0</div>
                      <div className="text-[9px] text-[#64748b]">Conflicts</div>
                    </div>
                    <div className="p-2 rounded-xl bg-white dark:bg-[#0d1f36]">
                      <div className="font-bold text-sm text-[#103c60] dark:text-[#f1f5f9]">
                        {simTodaySales.count - simTodaySales.pending}
                      </div>
                      <div className="text-[9px] text-[#64748b]">Synced</div>
                    </div>
                  </div>

                  <div className="rounded-xl p-2.5 bg-white dark:bg-[#0d1f36] border border-[#103c60]/10 dark:border-[#43b1d4]/15 space-y-1.5">
                    <div className="flex items-center justify-between text-[11px] font-bold text-[#103c60] dark:text-[#f1f5f9]">
                      <span>Today&apos;s Recorded Sales</span>
                      <span className="text-[#14558a] dark:text-[#43b1d4]">
                        {simTodaySales.count} · Ksh {simTodaySales.total.toLocaleString()}
                      </span>
                    </div>
                    <div className="text-[10px] text-[#64748b] dark:text-[#94a3b8]">
                      Tap a sale in Android to inspect items, payment method, sync badge &amp; authorised reversal (non-destructive).
                    </div>
                  </div>

                  <button
                    type="button"
                    onClick={() => {
                      setSimOfflineMode(false);
                      setSimTodaySales((prev) => ({ ...prev, pending: 0 }));
                    }}
                    className="w-full h-10 rounded-xl bg-[#14558a] dark:bg-[#43b1d4] text-white dark:text-[#060d18] text-xs font-semibold cursor-pointer"
                  >
                    Sync Pending Items Now
                  </button>

                  <button
                    type="button"
                    onClick={() => setPhoneDest('home')}
                    className="w-full h-9 rounded-xl border border-[#103c60]/20 dark:border-[#43b1d4]/25 text-xs font-medium text-[#103c60] dark:text-[#f1f5f9] cursor-pointer"
                  >
                    Back to Home
                  </button>
                </div>
              ) : phoneDest === 'stock' ? (
                <div className="space-y-3">
                  <div className="grid grid-cols-3 gap-1 p-1 rounded-xl bg-[#ebf2f7] dark:bg-[#132945]">
                    <button
                      type="button"
                      onClick={() => setSimStockSubMode('lookup')}
                      className={`py-1 rounded-lg text-[9px] font-bold cursor-pointer transition-colors ${
                        simStockSubMode === 'lookup'
                          ? 'bg-white dark:bg-[#0d1f36] text-[#14558a] dark:text-[#43b1d4] shadow-xs'
                          : 'text-[#64748b] dark:text-[#94a3b8]'
                      }`}
                    >
                      Lookup
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        setSimStockSubMode('receive');
                        setSimReceiveMsg(null);
                      }}
                      className={`py-1 rounded-lg text-[9px] font-bold cursor-pointer transition-colors ${
                        simStockSubMode === 'receive'
                          ? 'bg-white dark:bg-[#0d1f36] text-[#14558a] dark:text-[#43b1d4] shadow-xs'
                          : 'text-[#64748b] dark:text-[#94a3b8]'
                      }`}
                    >
                      Receive
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        setSimStockSubMode('adjust');
                        setSimAdjustMsg(null);
                      }}
                      className={`py-1 rounded-lg text-[9px] font-bold cursor-pointer transition-colors ${
                        simStockSubMode === 'adjust'
                          ? 'bg-white dark:bg-[#0d1f36] text-[#14558a] dark:text-[#43b1d4] shadow-xs'
                          : 'text-[#64748b] dark:text-[#94a3b8]'
                      }`}
                    >
                      Adjust
                    </button>
                  </div>

                  {simStockSubMode === 'lookup' ? (
                    <>
                      <button
                        type="button"
                        onClick={() => setPhoneDest('scan')}
                        className="w-full h-11 rounded-2xl bg-[#14558a] dark:bg-[#43b1d4] text-white dark:text-[#060d18] text-xs font-bold flex items-center justify-center gap-2 cursor-pointer"
                      >
                        <Camera className="w-4 h-4" />
                        <span>Scan Product for Stock</span>
                      </button>

                      <div className="p-3 rounded-2xl bg-white dark:bg-[#0d1f36] border border-[#103c60]/10 dark:border-[#43b1d4]/15 space-y-2">
                        <div className="flex items-start justify-between gap-2">
                          <div>
                            <div className="text-xs font-bold text-[#103c60] dark:text-[#f1f5f9]">
                              Masking tape (medium)
                            </div>
                            <div className="text-[10px] text-[#64748b] dark:text-[#94a3b8]">
                              Stationery · Sell Ksh 150 · Buy Ksh 90
                            </div>
                          </div>
                          <span className={`px-2 py-0.5 rounded-md text-[9px] font-bold ${
                            4 + simReceivedUnits - simTodaySales.pending <= 3
                              ? 'bg-amber-500/20 text-amber-800 dark:text-amber-300'
                              : 'bg-emerald-500/15 text-emerald-800 dark:text-emerald-300'
                          }`}>
                            {4 + simReceivedUnits - simTodaySales.pending <= 3 ? 'Low Stock' : 'In Stock'}
                          </span>
                        </div>

                        <div className="p-2.5 rounded-xl bg-[#ebf2f7] dark:bg-[#132945] space-y-1">
                          <div className="text-[10px] text-[#475569] dark:text-[#94a3b8]">Available Stock</div>
                          <div className="text-lg font-bold text-[#103c60] dark:text-[#f1f5f9]">
                            {Math.max(0, 4 + simReceivedUnits + simAdjustedUnits - simTodaySales.pending)} units
                          </div>
                          <div className="text-[10px] text-[#64748b] dark:text-[#94a3b8]">
                            Server updated just now · Location: Shelf B2
                          </div>
                        </div>

                        <div className="grid grid-cols-2 gap-1.5 pt-1">
                          <button
                            type="button"
                            onClick={() => {
                              setSimStockSubMode('receive');
                              setSimReceiveMsg(null);
                            }}
                            className="h-8 rounded-xl bg-[#14558a]/10 dark:bg-[#43b1d4]/15 text-[#14558a] dark:text-[#43b1d4] text-[10px] font-bold cursor-pointer"
                          >
                            + Receive
                          </button>
                          <button
                            type="button"
                            onClick={() => {
                              setSimStockSubMode('adjust');
                              setSimAdjustMsg(null);
                            }}
                            className="h-8 rounded-xl bg-[#14558a]/10 dark:bg-[#43b1d4]/15 text-[#14558a] dark:text-[#43b1d4] text-[10px] font-bold cursor-pointer"
                          >
                            ± Adjust
                          </button>
                        </div>
                      </div>
                    </>
                  ) : simStockSubMode === 'receive' ? (
                    <div className="space-y-2.5">
                      <div className="p-3 rounded-2xl bg-white dark:bg-[#0d1f36] border border-[#103c60]/10 dark:border-[#43b1d4]/15 space-y-2">
                        <div className="text-xs font-bold text-[#103c60] dark:text-[#f1f5f9]">
                          Masking tape (medium)
                        </div>
                        <div className="text-[10px] text-[#64748b] dark:text-[#94a3b8]">
                          Current stock: {4 + simReceivedUnits - simTodaySales.pending} units · Buy price: Ksh 90
                        </div>

                        <div className="space-y-1 pt-1">
                          <div className="text-[10px] font-semibold text-[#103c60] dark:text-[#f1f5f9]">
                            Quantity Arriving
                          </div>
                          <div className="flex items-center gap-2">
                            <button
                              type="button"
                              onClick={() => setSimReceiveQty((q) => Math.max(1, q - 5))}
                              className="w-8 h-8 rounded-lg bg-[#ebf2f7] dark:bg-[#132945] font-bold text-xs cursor-pointer"
                            >
                              -5
                            </button>
                            <div className="flex-1 h-8 rounded-lg bg-[#ebf2f7] dark:bg-[#132945] flex items-center justify-center font-bold text-xs">
                              +{simReceiveQty} units
                            </div>
                            <button
                              type="button"
                              onClick={() => setSimReceiveQty((q) => q + 5)}
                              className="w-8 h-8 rounded-lg bg-[#ebf2f7] dark:bg-[#132945] font-bold text-xs cursor-pointer"
                            >
                              +5
                            </button>
                          </div>
                        </div>

                        <div className="p-2 rounded-xl bg-[#f8fafc] dark:bg-[#081322] text-[10px] space-y-1">
                          <div className="flex justify-between">
                            <span className="text-[#64748b]">Supplier:</span>
                            <span className="font-semibold text-[#103c60] dark:text-[#f1f5f9]">Crown Paints Ltd</span>
                          </div>
                          <div className="flex justify-between">
                            <span className="text-[#64748b]">Invoice:</span>
                            <span className="font-semibold text-[#103c60] dark:text-[#f1f5f9]">INV-2026-9901</span>
                          </div>
                          <div className="flex justify-between border-t border-[#103c60]/10 dark:border-[#43b1d4]/15 pt-1">
                            <span className="text-[#64748b]">Total Inflow Value:</span>
                            <span className="font-bold text-[#14558a] dark:text-[#43b1d4]">
                              Ksh {(simReceiveQty * 90).toLocaleString()}
                            </span>
                          </div>
                        </div>
                      </div>

                      <button
                        type="button"
                        onClick={() => {
                          setSimReceivedUnits((u) => u + simReceiveQty);
                          setSimReceiveMsg(
                            `Stock Received Online · Synced (+${simReceiveQty} units recorded in Supabase & Room with idempotency key). Available stock is now ${
                              4 + simReceivedUnits + simReceiveQty - simTodaySales.pending
                            } units.`
                          );
                        }}
                        className="w-full h-11 rounded-2xl bg-[#14558a] dark:bg-[#43b1d4] text-white dark:text-[#060d18] text-xs font-bold cursor-pointer"
                      >
                        RECEIVE {simReceiveQty} UNITS · Ksh {(simReceiveQty * 90).toLocaleString()}
                      </button>

                      {simReceiveMsg && (
                        <div className="p-2.5 rounded-xl bg-emerald-500/10 text-emerald-700 dark:text-emerald-300 text-[10px] leading-relaxed">
                          {simReceiveMsg}
                        </div>
                      )}
                    </div>
                  ) : (
                    <div className="space-y-2.5">
                      <div className="p-3 rounded-2xl bg-white dark:bg-[#0d1f36] border border-[#103c60]/10 dark:border-[#43b1d4]/15 space-y-2">
                        <div className="text-xs font-bold text-[#103c60] dark:text-[#f1f5f9]">
                          Masking tape (medium)
                        </div>
                        <div className="text-[10px] text-[#64748b] dark:text-[#94a3b8]">
                          Current available: {4 + simReceivedUnits - simTodaySales.pending + simAdjustedUnits} units
                        </div>

                        <div className="space-y-1">
                          <div className="text-[10px] font-semibold text-[#103c60] dark:text-[#f1f5f9]">
                            Reason Code
                          </div>
                          <div className="grid grid-cols-2 gap-1">
                            {[
                              { code: 'DAMAGED', label: 'Damaged' },
                              { code: 'EXPIRED', label: 'Expired' },
                              { code: 'THEFT_OR_SHRINKAGE', label: 'Shrinkage' },
                              { code: 'COUNT_CORRECTION_INCREASE', label: 'Count (+)' },
                            ].map((r) => (
                              <button
                                key={r.code}
                                type="button"
                                onClick={() => {
                                  setSimAdjustReason(r.code);
                                  setSimAdjustDelta(r.code === 'COUNT_CORRECTION_INCREASE' ? 2 : -2);
                                }}
                                className={`py-1 px-2 rounded-md text-[9px] font-semibold cursor-pointer ${
                                  simAdjustReason === r.code
                                    ? 'bg-[#14558a] dark:bg-[#43b1d4] text-white dark:text-[#060d18]'
                                    : 'bg-[#ebf2f7] dark:bg-[#132945] text-[#475569] dark:text-[#94a3b8]'
                                }`}
                              >
                                {r.label}
                              </button>
                            ))}
                          </div>
                        </div>

                        <div className="space-y-1">
                          <div className="text-[10px] font-semibold text-[#103c60] dark:text-[#f1f5f9]">
                            Adjustment Delta
                          </div>
                          <div className="flex items-center gap-2">
                            <button
                              type="button"
                              onClick={() => setSimAdjustDelta((d) => d - 1)}
                              className="w-8 h-8 rounded-lg bg-[#ebf2f7] dark:bg-[#132945] font-bold text-xs cursor-pointer"
                            >
                              -
                            </button>
                            <div className="flex-1 h-8 rounded-lg bg-[#ebf2f7] dark:bg-[#132945] flex items-center justify-center font-bold text-xs">
                              {simAdjustDelta > 0 ? `+${simAdjustDelta}` : simAdjustDelta} units
                            </div>
                            <button
                              type="button"
                              onClick={() => setSimAdjustDelta((d) => d + 1)}
                              className="w-8 h-8 rounded-lg bg-[#ebf2f7] dark:bg-[#132945] font-bold text-xs cursor-pointer"
                            >
                              +
                            </button>
                          </div>
                        </div>

                        <div className="text-[10px] text-[#64748b]">
                          Notes: Lids dented in transit (audit verified)
                        </div>
                      </div>

                      <button
                        type="button"
                        onClick={() => {
                          setSimAdjustedUnits((u) => u + simAdjustDelta);
                          setSimAdjustMsg(
                            `Stock Adjusted Online · Synced (${simAdjustReason}: ${simAdjustDelta} units committed to Supabase & Room with audit trail).`
                          );
                        }}
                        className="w-full h-11 rounded-2xl bg-[#14558a] dark:bg-[#43b1d4] text-white dark:text-[#060d18] text-xs font-bold cursor-pointer"
                      >
                        APPLY ADJUSTMENT ({simAdjustDelta > 0 ? `+${simAdjustDelta}` : simAdjustDelta})
                      </button>

                      {simAdjustMsg && (
                        <div className="p-2.5 rounded-xl bg-emerald-500/10 text-emerald-700 dark:text-emerald-300 text-[10px] leading-relaxed">
                          {simAdjustMsg}
                        </div>
                      )}
                    </div>
                  )}
                </div>
              ) : phoneDest === 'money' ? (
                <div className="space-y-3">
                  <div className="p-3 rounded-2xl bg-white dark:bg-[#0d1f36] border border-[#103c60]/10 dark:border-[#43b1d4]/15 space-y-2">
                    <div className="flex items-center justify-between gap-2">
                      <div>
                        <div className="text-xs font-bold text-[#103c60] dark:text-[#f1f5f9]">
                          M-Pesa SMS Monitoring
                        </div>
                        <div className="text-[10px] text-[#475569] dark:text-[#94a3b8]">
                          {simSmsConsent ? 'Active · MPESA sender only' : 'Paused · Opt-in required'}
                        </div>
                      </div>
                      <button
                        type="button"
                        onClick={() => {
                          const next = !simSmsConsent;
                          setSimSmsConsent(next);
                          setSimMoneyMsg(
                            next
                              ? 'Explicit consent granted. Listening locally for MPESA alerts.'
                              : 'SMS monitoring paused.'
                          );
                        }}
                        className={`px-2.5 py-1 rounded-lg text-[10px] font-bold cursor-pointer ${
                          simSmsConsent
                            ? 'bg-emerald-600 text-white'
                            : 'bg-[#ebf2f7] dark:bg-[#132945] text-[#103c60] dark:text-[#f1f5f9]'
                        }`}
                      >
                        {simSmsConsent ? 'ON' : 'Enable'}
                      </button>
                    </div>
                    <p className="text-[10px] text-[#64748b] dark:text-[#94a3b8] leading-relaxed">
                      Personal SMS, OTPs, and promos are ignored locally on-device.
                    </p>
                  </div>

                  <div className="grid grid-cols-2 gap-1.5">
                    <button
                      type="button"
                      disabled={isCapturingSms}
                      onClick={async () => {
                        setIsCapturingSms(true);
                        try {
                          const ref = `QG${Math.random().toString(36).substring(2, 6).toUpperCase()}${Math.floor(1000 + Math.random() * 9000)}`;
                          if (sharedTx?.captureTransaction) {
                            await sharedTx.captureTransaction({
                              amount: 1500,
                              direction: 'INFLOW',
                              referenceCode: ref,
                              channel: 'Received Money',
                              counterparty: 'JANE WANJIKU',
                              rawEvidence: `${ref} Confirmed. Ksh1,500.00 received from JANE WANJIKU 254712345678 on ${new Date().toLocaleDateString()}. New M-PESA balance is Ksh14,200.00.`,
                              newBalance: 14200,
                              source: 'ANDROID_PHONE_SIMULATOR',
                            });
                            setSimMoneyMsg(`Saved in Supabase: Ref ${ref} (Ksh 1,500). PENDING_CLASSIFICATION.`);
                          }
                        } finally {
                          setIsCapturingSms(false);
                        }
                      }}
                      className="h-9 rounded-xl bg-[#14558a] dark:bg-[#43b1d4] text-white dark:text-[#060d18] text-[10px] font-bold cursor-pointer disabled:opacity-60"
                    >
                      {isCapturingSms ? 'Saving...' : 'Parse Inflow SMS'}
                    </button>
                    <button
                      type="button"
                      disabled={isCapturingSms}
                      onClick={async () => {
                        setIsCapturingSms(true);
                        try {
                          const ref = `QH${Math.random().toString(36).substring(2, 6).toUpperCase()}${Math.floor(1000 + Math.random() * 9000)}`;
                          if (sharedTx?.captureTransaction) {
                            await sharedTx.captureTransaction({
                              amount: 850,
                              direction: 'OUTFLOW',
                              referenceCode: ref,
                              channel: 'Paybill · Acc 142233',
                              counterparty: 'KPLC PREPAID',
                              counterpartyAccount: '142233',
                              transactionCost: 23,
                              rawEvidence: `${ref} Confirmed. Ksh850.00 paid to KPLC PREPAID for account 142233. M-PESA balance is Ksh13,350.00. Transaction cost, Ksh23.00.`,
                              newBalance: 13350,
                              source: 'ANDROID_PHONE_SIMULATOR',
                            });
                            setSimMoneyMsg(`Saved in Supabase: Ref ${ref} (Ksh 850). PENDING_CLASSIFICATION.`);
                          }
                        } finally {
                          setIsCapturingSms(false);
                        }
                      }}
                      className="h-9 rounded-xl bg-[#1768a7] dark:bg-[#348fc2] text-white dark:text-[#060d18] text-[10px] font-bold cursor-pointer disabled:opacity-60"
                    >
                      {isCapturingSms ? 'Saving...' : 'Parse Paybill SMS'}
                    </button>
                  </div>

                  {/* Manual / Paste M-Pesa SMS Bar */}
                  <div className="space-y-1.5 p-2 rounded-xl bg-[#ebf2f7] dark:bg-[#132945]">
                    <div className="text-[10px] font-semibold text-[#103c60] dark:text-[#f1f5f9]">
                      Paste SMS / Manual Entry
                    </div>
                    <div className="flex gap-1.5">
                      <input
                        type="text"
                        value={customSmsText}
                        onChange={(e) => setCustomSmsText(e.target.value)}
                        placeholder="e.g. QK890 Confirmed. Ksh2,000 received..."
                        className="flex-1 px-2 py-1 rounded-lg bg-white dark:bg-[#0d1f36] border border-[#103c60]/10 dark:border-[#43b1d4]/15 text-[10px] text-[#103c60] dark:text-[#f1f5f9] placeholder:text-[#94a3b8] focus:outline-none"
                      />
                      <button
                        type="button"
                        disabled={!customSmsText.trim() || isCapturingSms}
                        onClick={async () => {
                          if (!customSmsText.trim()) return;
                          setIsCapturingSms(true);
                          try {
                            const text = customSmsText.trim();
                            const amountMatch = text.match(/Ksh\s*([0-9,]+(?:\.[0-9]{2})?)/i);
                            const amount = amountMatch
                              ? parseFloat(amountMatch[1].replace(/,/g, ''))
                              : 2000;
                            const refMatch = text.match(/\b([A-Z0-9]{8,12})\b/);
                            const ref = refMatch ? refMatch[1] : `QG${Date.now().toString(36).toUpperCase()}`;
                            const isInflow =
                              !text.toLowerCase().includes('paid to') &&
                              !text.toLowerCase().includes('sent to');

                            if (sharedTx?.captureTransaction) {
                              await sharedTx.captureTransaction({
                                amount: amount || 2000,
                                direction: isInflow ? 'INFLOW' : 'OUTFLOW',
                                referenceCode: ref,
                                channel: isInflow ? 'Received Money' : 'Paybill',
                                counterparty: isInflow ? 'CUSTOMER' : 'SUPPLIER',
                                rawEvidence: text,
                                source: 'ANDROID_SMS_PASTE',
                              });
                              setSimMoneyMsg(`Saved in Supabase: Ref ${ref} (Ksh ${amount.toLocaleString()}). Awaiting classification.`);
                              setCustomSmsText('');
                            }
                          } finally {
                            setIsCapturingSms(false);
                          }
                        }}
                        className="px-2.5 py-1 rounded-lg bg-[#14558a] dark:bg-[#43b1d4] text-white dark:text-[#060d18] text-[10px] font-bold cursor-pointer disabled:opacity-50 shrink-0"
                      >
                        Auto-Save
                      </button>
                    </div>
                  </div>

                  {simMoneyMsg && (
                    <div className="p-2 rounded-xl bg-emerald-500/10 text-emerald-800 dark:text-emerald-200 text-[10px] leading-relaxed">
                      {simMoneyMsg}
                    </div>
                  )}

                  <div className="space-y-1.5">
                    <div className="flex items-center justify-between text-[10px] font-bold text-[#103c60] dark:text-[#f1f5f9]">
                      <span>Shared Supabase Transactions</span>
                      <span className="font-mono text-[#64748b] dark:text-[#94a3b8]">
                        {sharedTx ? sharedTx.transactions.length : 0} item(s)
                      </span>
                    </div>

                    {(!sharedTx || sharedTx.transactions.length === 0) ? (
                      <div className="p-3 rounded-xl bg-white/70 dark:bg-[#0d1f36]/60 text-[10px] text-[#64748b] dark:text-[#94a3b8] text-center">
                        No transactions captured yet. Tap Parse Inflow SMS above to test automatic capture.
                      </div>
                    ) : (
                      sharedTx.transactions.slice(0, 8).map((tx) => {
                        const isPending = tx.classificationStatus === 'PENDING_CLASSIFICATION';
                        const isInflow = tx.direction === 'INFLOW';
                        return (
                          <div
                            key={tx.id}
                            className="p-2.5 rounded-xl bg-white dark:bg-[#0d1f36] border border-[#103c60]/10 dark:border-[#43b1d4]/15 space-y-2 text-[10px]"
                          >
                            <div className="flex items-start justify-between gap-1">
                              <div>
                                <div className="font-bold text-[#103c60] dark:text-[#f1f5f9] flex items-center gap-1">
                                  {isInflow ? (
                                    <ArrowDownLeft className="w-3 h-3 text-emerald-600 dark:text-emerald-400" />
                                  ) : (
                                    <ArrowUpRight className="w-3 h-3 text-rose-600 dark:text-rose-400" />
                                  )}
                                  <span>{isInflow ? '+' : '-'}Ksh {tx.amount.toLocaleString()}</span>
                                  <span className="text-[#64748b] dark:text-[#94a3b8]">· {tx.referenceCode || tx.id.slice(0, 8)}</span>
                                </div>
                                <div className="text-[#64748b] dark:text-[#94a3b8] truncate max-w-[190px]">
                                  {tx.channel || 'Transfer'} · {tx.counterparty || 'Counterparty'}
                                </div>
                              </div>
                              <span
                                className={`px-1.5 py-0.5 rounded text-[9px] font-bold shrink-0 ${
                                  isPending
                                    ? 'bg-amber-500/15 text-amber-800 dark:text-amber-300'
                                    : tx.classificationStatus === 'BUSINESS_PRODUCT'
                                    ? 'bg-emerald-500/15 text-emerald-800 dark:text-emerald-300'
                                    : tx.classificationStatus === 'BUSINESS_SERVICE'
                                    ? 'bg-[#14558a]/15 text-[#14558a] dark:text-[#43b1d4]'
                                    : 'bg-slate-200 dark:bg-slate-800 text-[#475569] dark:text-[#94a3b8]'
                                }`}
                              >
                                {isPending
                                  ? 'Awaiting'
                                  : tx.classificationStatus === 'BUSINESS_PRODUCT'
                                  ? 'Product'
                                  : tx.classificationStatus === 'BUSINESS_SERVICE'
                                  ? 'Service'
                                  : 'Excluded'}
                              </span>
                            </div>

                            {isPending ? (
                              <div className="flex items-center gap-1 pt-1">
                                <button
                                  type="button"
                                  onClick={() => onOpenClassifyModal?.(tx, 'product')}
                                  className="flex-1 py-1 px-1 rounded-lg bg-[#14558a] dark:bg-[#43b1d4] text-white dark:text-[#060d18] text-[9px] font-bold cursor-pointer"
                                >
                                  Product
                                </button>
                                <button
                                  type="button"
                                  onClick={() => onOpenClassifyModal?.(tx, 'service')}
                                  className="flex-1 py-1 px-1 rounded-lg border border-[#14558a]/25 dark:border-[#43b1d4]/30 text-[#103c60] dark:text-[#f1f5f9] text-[9px] font-bold cursor-pointer"
                                >
                                  Service
                                </button>
                                <button
                                  type="button"
                                  onClick={() => onOpenClassifyModal?.(tx, 'non_business')}
                                  className="py-1 px-1.5 rounded-lg border border-slate-200 dark:border-slate-700 text-[#64748b] dark:text-[#94a3b8] text-[9px] font-medium cursor-pointer"
                                >
                                  Remove
                                </button>
                              </div>
                            ) : (
                              <div className="text-[9px] text-[#64748b] dark:text-[#94a3b8] flex items-center justify-between">
                                <span>
                                  {tx.classificationStatus === 'BUSINESS_PRODUCT'
                                    ? tx.linkedProductName || 'Product sale linked'
                                    : tx.classificationStatus === 'BUSINESS_SERVICE'
                                    ? tx.serviceLabel || 'Service linked'
                                    : 'Personal / non-business transfer'}
                                </span>
                                <span className="font-bold text-[#103c60] dark:text-[#f1f5f9]">
                                  {tx.classificationStatus === 'NON_BUSINESS'
                                    ? 'Ksh 0'
                                    : `+Ksh ${tx.amount.toLocaleString()}`}
                                </span>
                              </div>
                            )}
                          </div>
                        );
                      })
                    )}
                  </div>
                </div>
              ) : (
                <div className="h-full flex flex-col items-center justify-center text-center py-12 px-4 space-y-3">
                  <div className="w-12 h-12 rounded-2xl bg-[#14558a]/10 dark:bg-[#43b1d4]/15 text-[#14558a] dark:text-[#43b1d4] flex items-center justify-center">
                    <activeModule.icon className="w-6 h-6" />
                  </div>
                  <h3 className="font-display text-base font-bold text-[#103c60] dark:text-[#f1f5f9]">
                    {activeModule.label}
                  </h3>
                  <p className="text-xs text-[#475569] dark:text-[#94a3b8] leading-relaxed">
                    {activeModule.stageNote}
                  </p>
                  <button
                    type="button"
                    onClick={() => setPhoneDest('home')}
                    className="px-4 py-2 rounded-xl bg-[#14558a] dark:bg-[#43b1d4] text-white dark:text-[#060d18] text-xs font-semibold cursor-pointer"
                  >
                    Back to Home
                  </button>
                </div>
              )}
            </div>

            {/* Material 3 Bottom NavigationBar (5 primary destinations) */}
            <div className="grid grid-cols-5 border-t border-[#103c60]/10 dark:border-[#43b1d4]/15 bg-white/90 dark:bg-[#0d1f36]/95 py-2 px-1">
              {ANDROID_MODULES.filter((m) => m.inBottomBar).map((m) => {
                const Icon = m.icon;
                const active = phoneDest === m.id;
                return (
                  <button
                    key={m.id}
                    type="button"
                    onClick={() => setPhoneDest(m.id)}
                    className={`flex flex-col items-center gap-0.5 py-1 rounded-xl text-[10px] font-medium cursor-pointer ${
                      active
                        ? 'text-[#14558a] dark:text-[#43b1d4]'
                        : 'text-[#64748b] dark:text-[#94a3b8]'
                    }`}
                  >
                    <Icon className="w-4 h-4" />
                    <span>{m.shortLabel}</span>
                  </button>
                );
              })}
            </div>
          </div>
          <p className="mt-3 text-xs text-[#64748b] dark:text-[#94a3b8] text-center">
            Interactive Compose Shell Preview · Mirrors <code className="font-mono">MainActivity.kt</code>
          </p>
        </section>

        {/* Real APK Build & Project Files */}
        <section className="lg:col-span-7 space-y-5">
          <div className="emofah-glass rounded-3xl p-6 space-y-4">
            <div className="flex items-center gap-2.5">
              <Terminal className="w-5 h-5 text-[#14558a] dark:text-[#43b1d4]" />
              <h2 className="font-display text-lg font-semibold text-[#103c60] dark:text-[#f1f5f9]">
                How to Build &amp; Install the Real `.apk`
              </h2>
            </div>

            <div className="space-y-3 text-sm text-[#475569] dark:text-[#cbd5e1]">
              <div className="p-4 rounded-2xl bg-gradient-to-r from-[#14558a]/10 to-[#43b1d4]/10 border border-[#14558a]/20 dark:border-[#43b1d4]/30 space-y-3">
                <div className="flex items-center gap-2 text-[#103c60] dark:text-[#f1f5f9] font-bold text-sm">
                  <Smartphone className="w-4 h-4 text-[#14558a] dark:text-[#43b1d4]" />
                  <span>Simple 4-Step Guide (How to Get This on Your Phone Without Coding)</span>
                </div>
                <ol className="text-xs text-[#475569] dark:text-[#cbd5e1] space-y-2 list-decimal list-inside leading-relaxed">
                  <li><strong>Automated Cloud Build:</strong> Put this project into your GitHub account. Click the <em>Actions</em> tab at the top &rarr; click <em>Build Emofah Android Debug APK</em> &rarr; click <em>Run workflow</em>. GitHub will build the phone install file for you in ~2 minutes!</li>
                  <li><strong>Download Install File:</strong> Once finished, click on <em>emofah-android-debug</em> to download the zip file to your computer. Unzip it to get <code className="font-mono">app-debug.apk</code>.</li>
                  <li><strong>Send to Phone:</strong> Send <code className="font-mono">app-debug.apk</code> to yourself on WhatsApp, Telegram, Google Drive, or USB cable.</li>
                  <li><strong>Tap to Install:</strong> Open the file on your Android phone and tap <em>Install</em>. If prompted, toggle <em>Allow from this source</em> to ON. The Emofah app icon will appear on your phone screen!</li>
                </ol>
              </div>

              <div className="p-4 rounded-2xl bg-white/70 dark:bg-[#0a182b]/80 border border-[#103c60]/10 dark:border-[#43b1d4]/15 space-y-2">
                <div className="font-semibold text-[#103c60] dark:text-[#f1f5f9] flex items-center gap-2">
                  <CheckCircle2 className="w-4 h-4 text-emerald-600 dark:text-emerald-400" />
                  <span>Option A — GitHub Actions CI (Automated Cloud APK Build)</span>
                </div>
                <p className="text-xs leading-relaxed">
                  Push this repository to GitHub with optional <code className="font-mono">SUPABASE_URL</code> and <code className="font-mono">SUPABASE_ANON_KEY</code> Repository Variables. The workflow <code className="font-mono">.github/workflows/android-apk.yml</code> installs JDK 17 + Android SDK, runs <code className="font-mono">./gradlew testDebugUnitTest assembleDebug</code>, verifies the APK manifest and signature, and outputs a downloadable <code className="font-mono">emofah-android-debug</code> artifact.
                </p>
              </div>

              <div className="p-4 rounded-2xl bg-white/70 dark:bg-[#0a182b]/80 border border-[#103c60]/10 dark:border-[#43b1d4]/15 space-y-2">
                <div className="font-semibold text-[#103c60] dark:text-[#f1f5f9] flex items-center gap-2">
                  <FolderGit2 className="w-4 h-4 text-[#14558a] dark:text-[#43b1d4]" />
                  <span>Option B — Android Studio / Local CLI</span>
                </div>
                <pre className="text-[11px] font-mono bg-[#060d18] text-[#e2e8f0] p-3 rounded-xl overflow-x-auto leading-relaxed">
{`# Supabase URL & anon key are pre-configured in build.gradle.kts
cd android
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk`}
                </pre>
              </div>
            </div>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div className="emofah-glass rounded-2xl p-5 space-y-2">
              <div className="h-9 w-9 rounded-xl bg-[#14558a]/10 dark:bg-[#43b1d4]/15 text-[#14558a] dark:text-[#43b1d4] flex items-center justify-center">
                <Database className="w-4 h-4" />
              </div>
              <h3 className="font-display text-sm font-semibold text-[#103c60] dark:text-[#f1f5f9]">
                Stage 1.1 · Catalogue Reference Live
              </h3>
              <p className="text-xs text-[#475569] dark:text-[#94a3b8] leading-relaxed">
                Connected to live <code className="font-mono">public.products</code> (111 items), reference images (<code className="font-mono">product-images</code>), and recognition index.
              </p>
              <button
                type="button"
                onClick={() => onNavigate('catalog')}
                className="text-xs font-semibold text-[#14558a] dark:text-[#43b1d4] hover:underline cursor-pointer"
              >
                Inspect Live Catalogue →
              </button>
            </div>

            <div className="emofah-glass rounded-2xl p-5 space-y-2">
              <div className="h-9 w-9 rounded-xl bg-[#14558a]/10 dark:bg-[#43b1d4]/15 text-[#14558a] dark:text-[#43b1d4] flex items-center justify-center">
                <ShieldCheck className="w-4 h-4" />
              </div>
              <h3 className="font-display text-sm font-semibold text-[#103c60] dark:text-[#f1f5f9]">
                Strict Native &amp; No-PWA Policy
              </h3>
              <p className="text-xs text-[#475569] dark:text-[#94a3b8] leading-relaxed">
                PWA manifest removed. Fake APK text blob removed. Target deliverable is purely native Kotlin / Jetpack Compose Android.
              </p>
            </div>
          </div>
        </section>
      </div>
    </div>
  );
}
