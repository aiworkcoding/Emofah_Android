import { useEffect, useState } from 'react';
import { Home, Layers, Moon, Palette, SlidersHorizontal, Sun, Smartphone, Camera, ShoppingCart, Package, DollarSign, Activity, Database } from 'lucide-react';
import { AnimatePresence, motion } from 'motion/react';
import { BrandKitView } from './components/BrandKitView';
import { BrandMark } from './components/BrandMark';
import { OverviewView } from './components/OverviewView';
import { PreferencesView } from './components/PreferencesView';
import { WorkspaceView } from './components/WorkspaceView';
import { AndroidHubView } from './components/AndroidHubView';
import { ProductCatalogView } from './components/ProductCatalogView';
import { BackendSetupView } from './components/BackendSetupView';
import { useTheme } from './hooks/useTheme';
import type { AppPreferences, NavTab, WorkspaceItem } from './types/app';

const PREFS_STORAGE_KEY = 'emofah-preferences';
const ITEMS_STORAGE_KEY = 'emofah-workspace-items';

const DEFAULT_PREFERENCES: AppPreferences = {
  glassIntensity: 'balanced',
  reducedMotion: false,
  density: 'comfortable',
};

const INITIAL_ITEMS: WorkspaceItem[] = [
  {
    id: 'stage-0',
    title: 'Stage 0: Native Android APK Build & Install Center',
    category: 'Module',
    completed: true,
    updatedAt: 'Completed',
  },
  {
    id: 'spec-theme',
    title: 'Brand light & dark surface tokens',
    category: 'Specification',
    completed: true,
    updatedAt: 'Ready',
  },
  {
    id: 'spec-nav',
    title: 'Thumb-zone Android bottom navigation',
    category: 'Module',
    completed: true,
    updatedAt: 'Ready',
  },
  {
    id: 'spec-next',
    title: 'Stage 1.1: Product Catalogue Reference Foundation',
    category: 'Specification',
    completed: false,
    updatedAt: 'Pending authorization',
  },
];

const NAV_ITEMS: { id: NavTab; label: string; icon: typeof Home }[] = [
  { id: 'home', label: 'Home', icon: Home },
  { id: 'android-hub', label: 'Android Preview', icon: Smartphone },
  { id: 'catalog', label: 'Catalogue', icon: Package },
  { id: 'backend', label: 'Backend Setup', icon: Database },
  { id: 'workspace', label: 'Workspace', icon: Layers },
  { id: 'brand', label: 'Brand Kit', icon: Palette },
  { id: 'settings', label: 'Settings', icon: SlidersHorizontal },
];

const BLUR_VALUE_MAP = {
  subtle: '10px',
  balanced: '20px',
  frosted: '32px',
};

export default function App() {
  const [activeTab, setActiveTab] = useState<NavTab>('home');
  const { preference, resolvedTheme, setPreference, toggleQuick } = useTheme();

  const [preferences, setPreferences] = useState<AppPreferences>(() => {
    try {
      const saved = window.localStorage.getItem(PREFS_STORAGE_KEY);
      if (saved) {
        return { ...DEFAULT_PREFERENCES, ...JSON.parse(saved) };
      }
    } catch {
      // Ignore storage parsing errors
    }
    return DEFAULT_PREFERENCES;
  });

  const [items, setItems] = useState<WorkspaceItem[]>(() => {
    try {
      const saved = window.localStorage.getItem(ITEMS_STORAGE_KEY);
      if (saved) {
        const parsed = JSON.parse(saved);
        if (Array.isArray(parsed)) return parsed;
      }
    } catch {
      // Ignore storage parsing errors
    }
    return INITIAL_ITEMS;
  });

  useEffect(() => {
    document.documentElement.style.setProperty(
      '--glass-blur',
      BLUR_VALUE_MAP[preferences.glassIntensity]
    );
    try {
      window.localStorage.setItem(
        PREFS_STORAGE_KEY,
        JSON.stringify(preferences)
      );
    } catch {
      // Ignore storage quota errors
    }
  }, [preferences]);

  useEffect(() => {
    try {
      window.localStorage.setItem(ITEMS_STORAGE_KEY, JSON.stringify(items));
    } catch {
      // Ignore storage quota errors
    }
  }, [items]);

  const handleAddItem = (
    title: string,
    category: WorkspaceItem['category']
  ) => {
    const nextItem: WorkspaceItem = {
      id: `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
      title,
      category,
      completed: false,
      updatedAt: 'Just now',
    };
    setItems((prev) => [nextItem, ...prev]);
  };

  const handleToggleItem = (id: string) => {
    setItems((prev) =>
      prev.map((item) =>
        item.id === id
          ? {
              ...item,
              completed: !item.completed,
              updatedAt: !item.completed ? 'Done' : 'Open',
            }
          : item
      )
    );
  };

  const handleDeleteItem = (id: string) => {
    setItems((prev) => prev.filter((item) => item.id !== id));
  };

  const handleResetDefaults = () => {
    setPreferences(DEFAULT_PREFERENCES);
    setPreference('system');
  };

  const activeNav = NAV_ITEMS.find((n) => n.id === activeTab) ?? NAV_ITEMS[0];
  const completedCount = items.filter((i) => i.completed).length;

  return (
    <div className="relative min-h-screen bg-[#f8fafc] dark:bg-[#060d18] text-[#0b1f33] dark:text-[#f1f5f9] transition-colors duration-200">
      {/* Subtle Emofah ambient depth backdrop for glassmorphism refraction */}
      <div
        aria-hidden="true"
        className="pointer-events-none fixed inset-0 overflow-hidden"
      >
        <div className="absolute -top-40 -left-32 h-[420px] w-[420px] rounded-full bg-[#43b1d4]/14 dark:bg-[#43b1d4]/12 blur-[100px]" />
        <div className="absolute top-1/3 -right-28 h-[460px] w-[460px] rounded-full bg-[#1768a7]/12 dark:bg-[#1768a7]/16 blur-[120px]" />
        <div className="absolute -bottom-32 left-1/4 h-[360px] w-[360px] rounded-full bg-[#348fc2]/10 dark:bg-[#14558a]/18 blur-[110px]" />
      </div>

      <div className="relative z-10 flex min-h-screen flex-col lg:flex-row">
        {/* Desktop Sidebar (Notion + Apple Glass Rail) */}
        <aside className="hidden lg:flex lg:w-64 lg:flex-col lg:fixed lg:inset-y-0 lg:left-0 emofah-glass-bar border-r border-[#103c60]/[0.08] dark:border-[#43b1d4]/[0.14] px-4 py-6 justify-between">
          <div className="space-y-8">
            <button
              type="button"
              onClick={() => setActiveTab('home')}
              className="w-full text-left px-2 py-1 rounded-xl focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#43b1d4] cursor-pointer"
            >
              <BrandMark size="md" showWordmark showTagline />
            </button>

            <nav aria-label="Primary Navigation" className="space-y-1">
              {NAV_ITEMS.map(({ id, label, icon: Icon }) => {
                const isActive = activeTab === id;
                return (
                  <button
                    key={id}
                    type="button"
                    onClick={() => setActiveTab(id)}
                    className={`relative w-full min-h-[44px] px-3.5 py-2.5 rounded-xl flex items-center gap-3 text-sm font-medium transition-colors cursor-pointer whitespace-nowrap focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#43b1d4] ${
                      isActive
                        ? 'text-[#103c60] dark:text-[#f1f5f9]'
                        : 'text-[#475569] dark:text-[#94a3b8] hover:text-[#103c60] dark:hover:text-[#f1f5f9] hover:bg-[#103c60]/[0.04] dark:hover:bg-[#43b1d4]/[0.06]'
                    }`}
                  >
                    {isActive && (
                      <motion.span
                        layoutId="desktop-nav-active"
                        transition={
                          preferences.reducedMotion
                            ? { duration: 0 }
                            : { type: 'spring', stiffness: 420, damping: 34 }
                        }
                        className="absolute inset-0 rounded-xl bg-white/85 dark:bg-[#132945]/90 border border-[#103c60]/[0.08] dark:border-[#43b1d4]/[0.18] shadow-xs"
                      />
                    )}
                    <Icon
                      className={`w-4 h-4 relative z-10 shrink-0 ${
                        isActive
                          ? 'text-[#14558a] dark:text-[#43b1d4]'
                          : 'text-current'
                      }`}
                    />
                    <span className="relative z-10">{label}</span>
                  </button>
                );
              })}
            </nav>
          </div>

          <div className="pt-4 border-t border-[#103c60]/[0.08] dark:border-[#43b1d4]/[0.12] space-y-3">
            <button
              type="button"
              onClick={() => setActiveTab('android-hub')}
              className="w-full min-h-[44px] px-3 py-2.5 rounded-xl bg-[#14558a]/10 dark:bg-[#43b1d4]/15 text-[#14558a] dark:text-[#43b1d4] hover:bg-[#14558a]/20 text-xs font-semibold flex items-center justify-center gap-2 transition-colors cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#43b1d4]"
            >
              <Smartphone className="w-4 h-4" />
              <span>Android Phone Preview</span>
            </button>

            <button
              type="button"
              onClick={toggleQuick}
              className="w-full min-h-[44px] px-3.5 py-2.5 rounded-xl flex items-center justify-between text-xs font-medium text-[#475569] dark:text-[#cbd5e1] hover:bg-[#103c60]/[0.05] dark:hover:bg-[#43b1d4]/[0.08] transition-colors cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#43b1d4]"
            >
              <span className="inline-flex items-center gap-2.5">
                {resolvedTheme === 'dark' ? (
                  <Moon className="w-4 h-4 text-[#43b1d4]" />
                ) : (
                  <Sun className="w-4 h-4 text-[#14558a]" />
                )}
                <span>{resolvedTheme === 'dark' ? 'Dark Mode' : 'Light Mode'}</span>
              </span>
              <span className="text-[11px] text-[#64748b] dark:text-[#94a3b8]">
                Switch
              </span>
            </button>
          </div>
        </aside>

        {/* Mobile Sticky Top App Bar (<= 52px height) */}
        <header className="lg:hidden sticky top-0 z-30 h-13 px-4 emofah-glass-bar border-b border-[#103c60]/[0.08] dark:border-[#43b1d4]/[0.14] flex items-center justify-between gap-3">
          <button
            type="button"
            onClick={() => setActiveTab('home')}
            aria-label="Go to Home"
            className="min-h-[44px] flex items-center gap-2.5 min-w-0 cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#43b1d4] rounded-lg"
          >
            <BrandMark size="sm" />
            <span className="font-display text-sm font-semibold text-[#103c60] dark:text-[#f1f5f9] truncate">
              {activeTab === 'android-hub' ? 'Android APK Hub' : activeNav.label}
            </span>
          </button>

          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => setActiveTab('android-hub')}
              aria-label="Open Android Preview"
              className="min-h-[38px] px-3 rounded-xl bg-[#14558a] dark:bg-[#43b1d4] text-white dark:text-[#060d18] text-xs font-medium inline-flex items-center gap-1.5 cursor-pointer"
            >
              <Smartphone className="w-3.5 h-3.5" />
              <span>Preview</span>
            </button>

            <button
              type="button"
              onClick={toggleQuick}
              aria-label={
                resolvedTheme === 'dark'
                  ? 'Switch to light mode'
                  : 'Switch to dark mode'
              }
              className="min-h-[44px] min-w-[44px] flex items-center justify-center rounded-xl text-[#103c60] dark:text-[#43b1d4] hover:bg-[#103c60]/[0.06] dark:hover:bg-[#43b1d4]/[0.1] transition-colors cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#43b1d4]"
            >
              {resolvedTheme === 'dark' ? (
                <Sun className="w-5 h-5" />
              ) : (
                <Moon className="w-5 h-5" />
              )}
            </button>
          </div>
        </header>

        {/* Main Scrollable Content Canvas */}
        <main className="flex-1 lg:pl-64 pb-24 lg:pb-12">
          <div className="mx-auto max-w-5xl px-4 sm:px-6 lg:px-10 py-6 sm:py-10">
            <AnimatePresence mode="wait">
              <motion.div
                key={activeTab}
                initial={
                  preferences.reducedMotion ? { opacity: 1 } : { opacity: 0, y: 6 }
                }
                animate={{ opacity: 1, y: 0 }}
                exit={
                  preferences.reducedMotion ? { opacity: 1 } : { opacity: 0, y: -4 }
                }
                transition={{
                  duration: preferences.reducedMotion ? 0 : 0.16,
                  ease: [0.16, 1, 0.3, 1],
                }}
              >
                {activeTab === 'home' && (
                  <OverviewView
                    onNavigate={setActiveTab}
                    itemCount={items.length}
                    completedCount={completedCount}
                    reducedMotion={preferences.reducedMotion}
                  />
                )}
                {activeTab === 'android-hub' && (
                  <AndroidHubView
                    onNavigate={setActiveTab}
                    reducedMotion={preferences.reducedMotion}
                  />
                )}
                {activeTab === 'catalog' && (
                  <ProductCatalogView
                    reducedMotion={preferences.reducedMotion}
                  />
                )}
                {activeTab === 'scan' && (
                  <div className="emofah-glass rounded-3xl p-8 space-y-4 text-center">
                    <Camera className="w-12 h-12 mx-auto text-[#14558a] dark:text-[#43b1d4]" />
                    <h2 className="font-display text-xl font-bold text-[#103c60] dark:text-[#f1f5f9]">
                      Stage 1.2 — Camera & Image Recognition Module
                    </h2>
                    <p className="text-sm text-[#475569] dark:text-[#94a3b8] max-w-md mx-auto">
                      CameraX capture and on-device recognition will be implemented in Phase 1 once Stage 1.1 catalogue reference foundation is authorized.
                    </p>
                    <button
                      type="button"
                      onClick={() => setActiveTab('android-hub')}
                      className="px-4 py-2 rounded-xl bg-[#14558a] dark:bg-[#43b1d4] text-white dark:text-[#060d18] text-xs font-semibold cursor-pointer"
                    >
                      Return to Android Hub
                    </button>
                  </div>
                )}
                {activeTab === 'sales' && (
                  <div className="emofah-glass rounded-3xl p-8 space-y-4 text-center">
                    <ShoppingCart className="w-12 h-12 mx-auto text-[#14558a] dark:text-[#43b1d4]" />
                    <h2 className="font-display text-xl font-bold text-[#103c60] dark:text-[#f1f5f9]">
                      Phase 2 — Frictionless Sales Capture Module
                    </h2>
                    <p className="text-sm text-[#475569] dark:text-[#94a3b8] max-w-md mx-auto">
                      One-touch Record Sale and atomic Supabase / Room offline sync will be implemented in Phase 2.
                    </p>
                    <button
                      type="button"
                      onClick={() => setActiveTab('android-hub')}
                      className="px-4 py-2 rounded-xl bg-[#14558a] dark:bg-[#43b1d4] text-white dark:text-[#060d18] text-xs font-semibold cursor-pointer"
                    >
                      Return to Android Hub
                    </button>
                  </div>
                )}
                {activeTab === 'stock' && (
                  <div className="emofah-glass rounded-3xl p-8 space-y-4 text-center">
                    <Package className="w-12 h-12 mx-auto text-[#14558a] dark:text-[#43b1d4]" />
                    <h2 className="font-display text-xl font-bold text-[#103c60] dark:text-[#f1f5f9]">
                      Phase 4 — Stock Operations Module
                    </h2>
                    <p className="text-sm text-[#475569] dark:text-[#94a3b8] max-w-md mx-auto">
                      Receiving stock, adjustments, and counting will be implemented in Phase 4.
                    </p>
                    <button
                      type="button"
                      onClick={() => setActiveTab('android-hub')}
                      className="px-4 py-2 rounded-xl bg-[#14558a] dark:bg-[#43b1d4] text-white dark:text-[#060d18] text-xs font-semibold cursor-pointer"
                    >
                      Return to Android Hub
                    </button>
                  </div>
                )}
                {activeTab === 'money' && (
                  <div className="emofah-glass rounded-3xl p-8 space-y-4 text-center">
                    <DollarSign className="w-12 h-12 mx-auto text-[#14558a] dark:text-[#43b1d4]" />
                    <h2 className="font-display text-xl font-bold text-[#103c60] dark:text-[#f1f5f9]">
                      Phase 5 — Financial & M-Pesa Monitoring Module
                    </h2>
                    <p className="text-sm text-[#475569] dark:text-[#94a3b8] max-w-md mx-auto">
                      M-Pesa message parser and transaction classification will be implemented in Phase 5.
                    </p>
                    <button
                      type="button"
                      onClick={() => setActiveTab('android-hub')}
                      className="px-4 py-2 rounded-xl bg-[#14558a] dark:bg-[#43b1d4] text-white dark:text-[#060d18] text-xs font-semibold cursor-pointer"
                    >
                      Return to Android Hub
                    </button>
                  </div>
                )}
                {activeTab === 'orders' && (
                  <div className="emofah-glass rounded-3xl p-8 space-y-4 text-center">
                    <Activity className="w-12 h-12 mx-auto text-[#14558a] dark:text-[#43b1d4]" />
                    <h2 className="font-display text-xl font-bold text-[#103c60] dark:text-[#f1f5f9]">
                      Phase 6 — Online Orders & Customer Fulfillment
                    </h2>
                    <p className="text-sm text-[#475569] dark:text-[#94a3b8] max-w-md mx-auto">
                      Order retrieval and conversion into sales will be implemented in Phase 6.
                    </p>
                    <button
                      type="button"
                      onClick={() => setActiveTab('android-hub')}
                      className="px-4 py-2 rounded-xl bg-[#14558a] dark:bg-[#43b1d4] text-white dark:text-[#060d18] text-xs font-semibold cursor-pointer"
                    >
                      Return to Android Hub
                    </button>
                  </div>
                )}
                {activeTab === 'activity' && (
                  <div className="emofah-glass rounded-3xl p-8 space-y-4 text-center">
                    <Activity className="w-12 h-12 mx-auto text-[#14558a] dark:text-[#43b1d4]" />
                    <h2 className="font-display text-xl font-bold text-[#103c60] dark:text-[#f1f5f9]">
                      Phase 8 — Activity & Sync State
                    </h2>
                    <p className="text-sm text-[#475569] dark:text-[#94a3b8] max-w-md mx-auto">
                      Sync queue status and daily activity logs will be tracked here.
                    </p>
                    <button
                      type="button"
                      onClick={() => setActiveTab('android-hub')}
                      className="px-4 py-2 rounded-xl bg-[#14558a] dark:bg-[#43b1d4] text-white dark:text-[#060d18] text-xs font-semibold cursor-pointer"
                    >
                      Return to Android Hub
                    </button>
                  </div>
                )}
                {activeTab === 'backend' && (
                  <BackendSetupView reducedMotion={preferences.reducedMotion} />
                )}
                {activeTab === 'workspace' && (
                  <WorkspaceView
                    items={items}
                    onAddItem={handleAddItem}
                    onToggleItem={handleToggleItem}
                    onDeleteItem={handleDeleteItem}
                    density={preferences.density}
                    reducedMotion={preferences.reducedMotion}
                  />
                )}
                {activeTab === 'brand' && (
                  <BrandKitView reducedMotion={preferences.reducedMotion} />
                )}
                {activeTab === 'settings' && (
                  <PreferencesView
                    themePreference={preference}
                    onChangeTheme={setPreference}
                    preferences={preferences}
                    onChangePreferences={setPreferences}
                    onResetDefaults={handleResetDefaults}
                  />
                )}
              </motion.div>
            </AnimatePresence>
          </div>
        </main>

        {/* Mobile Fixed Bottom Tab Bar (56px height, Thumb Zone) */}
        <nav
          aria-label="Mobile Navigation"
          className="lg:hidden fixed bottom-0 left-0 right-0 z-30 emofah-glass-bar border-t border-[#103c60]/[0.08] dark:border-[#43b1d4]/[0.14] pb-safe"
        >
          <div className="grid grid-cols-5 h-14 items-center px-1">
            {[
              { id: 'home', label: 'Home', icon: Home },
              { id: 'android-hub', label: 'Android', icon: Smartphone },
              { id: 'scan', label: 'Scan', icon: Camera },
              { id: 'sales', label: 'Sales', icon: ShoppingCart },
              { id: 'settings', label: 'Settings', icon: SlidersHorizontal },
            ].map(({ id, label, icon: Icon }) => {
              const isActive = activeTab === id;
              return (
                <button
                  key={id}
                  type="button"
                  onClick={() => setActiveTab(id as NavTab)}
                  className={`relative min-h-[44px] flex flex-col items-center justify-center gap-0.5 rounded-xl transition-colors cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#43b1d4] ${
                    isActive
                      ? 'text-[#14558a] dark:text-[#43b1d4]'
                      : 'text-[#64748b] dark:text-[#94a3b8]'
                  }`}
                >
                  {isActive && (
                    <motion.span
                      layoutId="mobile-tab-indicator"
                      transition={
                        preferences.reducedMotion
                          ? { duration: 0 }
                          : { type: 'spring', stiffness: 450, damping: 35 }
                      }
                      className="absolute top-1 h-0.5 w-6 rounded-full bg-[#14558a] dark:bg-[#43b1d4]"
                    />
                  )}
                  <Icon className="w-5 h-5" />
                  <span className="text-[10px] font-medium tracking-tight leading-none truncate max-w-[56px]">
                    {label}
                  </span>
                </button>
              );
            })}
          </div>
        </nav>
      </div>
    </div>
  );
}
