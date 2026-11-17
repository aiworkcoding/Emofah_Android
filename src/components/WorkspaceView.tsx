import { useState } from 'react';
import { Check, Plus, Trash2, X } from 'lucide-react';
import { AnimatePresence, motion } from 'motion/react';
import type { DensityMode, WorkspaceItem } from '../types/app';

interface WorkspaceViewProps {
  items: WorkspaceItem[];
  onAddItem: (title: string, category: WorkspaceItem['category']) => void;
  onToggleItem: (id: string) => void;
  onDeleteItem: (id: string) => void;
  density: DensityMode;
  reducedMotion: boolean;
}

type FilterType = 'all' | 'active' | 'completed';

const CATEGORIES: WorkspaceItem['category'][] = [
  'Specification',
  'Module',
  'Note',
];

export function WorkspaceView({
  items,
  onAddItem,
  onToggleItem,
  onDeleteItem,
  density,
  reducedMotion,
}: WorkspaceViewProps) {
  const [filter, setFilter] = useState<FilterType>('all');
  const [sheetOpen, setSheetOpen] = useState(false);
  const [newTitle, setNewTitle] = useState('');
  const [newCategory, setNewCategory] =
    useState<WorkspaceItem['category']>('Specification');

  const filteredItems = items.filter((item) => {
    if (filter === 'active') return !item.completed;
    if (filter === 'completed') return item.completed;
    return true;
  });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = newTitle.trim();
    if (!trimmed) return;
    onAddItem(trimmed, newCategory);
    setNewTitle('');
    setSheetOpen(false);
  };

  const rowHeight = density === 'compact' ? 'min-h-[52px] py-2.5' : 'min-h-[64px] py-3.5';
  const tapMotion = reducedMotion ? undefined : { scale: 0.97 };

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="font-display text-2xl sm:text-3xl font-bold tracking-tight text-[#103c60] dark:text-[#f1f5f9]">
            Workspace
          </h1>
          <p className="mt-1 text-sm text-[#475569] dark:text-[#94a3b8]">
            Capture incoming Android specifications, module notes, and UI tasks.
          </p>
        </div>

        <motion.button
          type="button"
          whileTap={tapMotion}
          onClick={() => setSheetOpen(true)}
          className="min-h-[44px] px-4 py-2.5 rounded-xl bg-[#14558a] hover:bg-[#103c60] dark:bg-[#43b1d4] dark:hover:bg-[#348fc2] text-white dark:text-[#060d18] text-sm font-semibold inline-flex items-center justify-center gap-2 transition-colors cursor-pointer whitespace-nowrap shrink-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#43b1d4]"
        >
          <Plus className="w-4 h-4" />
          <span>Add Entry</span>
        </motion.button>
      </div>

      <div className="flex items-center justify-between gap-4 flex-wrap">
        <div
          role="tablist"
          aria-label="Filter workspace items"
          className="inline-flex items-center p-1 rounded-xl bg-[#103c60]/[0.06] dark:bg-[#43b1d4]/[0.09] border border-[#103c60]/[0.08] dark:border-[#43b1d4]/[0.14]"
        >
          {(['all', 'active', 'completed'] as const).map((tab) => {
            const active = filter === tab;
            const label =
              tab === 'all' ? 'All' : tab === 'active' ? 'Open' : 'Done';
            return (
              <button
                key={tab}
                type="button"
                role="tab"
                aria-selected={active}
                onClick={() => setFilter(tab)}
                className={`relative min-h-[36px] px-4 py-1.5 text-xs font-medium rounded-lg transition-colors cursor-pointer whitespace-nowrap shrink-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#43b1d4] ${
                  active
                    ? 'text-[#103c60] dark:text-[#f1f5f9]'
                    : 'text-[#64748b] dark:text-[#94a3b8] hover:text-[#103c60] dark:hover:text-[#f1f5f9]'
                }`}
              >
                {active && (
                  <motion.span
                    layoutId="workspace-filter-pill"
                    transition={
                      reducedMotion
                        ? { duration: 0 }
                        : { type: 'spring', stiffness: 450, damping: 35 }
                    }
                    className="absolute inset-0 rounded-lg bg-white dark:bg-[#132945] shadow-xs"
                  />
                )}
                <span className="relative z-10">{label}</span>
              </button>
            );
          })}
        </div>

        <span className="font-mono-data text-xs text-[#64748b] dark:text-[#94a3b8]">
          {filteredItems.length} {filteredItems.length === 1 ? 'entry' : 'entries'}
        </span>
      </div>

      <section className="emofah-glass rounded-2xl overflow-hidden">
        {filteredItems.length === 0 ? (
          <div className="p-10 text-center space-y-3">
            <p className="text-sm font-medium text-[#103c60] dark:text-[#f1f5f9]">
              No entries in this view
            </p>
            <p className="text-xs text-[#64748b] dark:text-[#94a3b8]">
              Tap Add Entry to record a specification or module note.
            </p>
          </div>
        ) : (
          <ul className="divide-y divide-[#103c60]/[0.07] dark:divide-[#43b1d4]/[0.12]">
            {filteredItems.map((item) => (
              <li
                key={item.id}
                className={`flex items-center justify-between gap-3 px-4 sm:px-6 transition-colors hover:bg-[#103c60]/[0.025] dark:hover:bg-[#43b1d4]/[0.04] ${rowHeight}`}
              >
                <button
                  type="button"
                  onClick={() => onToggleItem(item.id)}
                  aria-label={
                    item.completed
                      ? `Mark "${item.title}" as open`
                      : `Mark "${item.title}" as done`
                  }
                  className="flex items-center gap-3.5 min-w-0 flex-1 text-left cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#43b1d4] rounded-lg py-1"
                >
                  <span
                    className={`h-6 w-6 shrink-0 rounded-lg border flex items-center justify-center transition-colors ${
                      item.completed
                        ? 'bg-[#1768a7] border-[#1768a7] dark:bg-[#43b1d4] dark:border-[#43b1d4] text-white dark:text-[#060d18]'
                        : 'border-[#103c60]/25 dark:border-[#43b1d4]/35 bg-white/60 dark:bg-[#0a182b]/60'
                    }`}
                  >
                    {item.completed && <Check className="w-3.5 h-3.5 stroke-[2.5]" />}
                  </span>

                  <div className="min-w-0 flex-1">
                    <p
                      className={`text-sm font-medium truncate transition-colors ${
                        item.completed
                          ? 'line-through text-[#64748b] dark:text-[#64748b]'
                          : 'text-[#0b1f33] dark:text-[#f1f5f9]'
                      }`}
                    >
                      {item.title}
                    </p>
                    <div className="mt-0.5 flex items-center gap-2 text-xs text-[#64748b] dark:text-[#94a3b8]">
                      <span>{item.category}</span>
                      <span aria-hidden="true">·</span>
                      <span>{item.updatedAt}</span>
                    </div>
                  </div>
                </button>

                <button
                  type="button"
                  onClick={() => onDeleteItem(item.id)}
                  aria-label={`Delete "${item.title}"`}
                  className="min-h-[44px] min-w-[44px] flex items-center justify-center rounded-xl text-[#64748b] hover:text-[#bd0707] dark:text-[#94a3b8] dark:hover:text-[#f87171] hover:bg-[#bd0707]/[0.07] transition-colors cursor-pointer shrink-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#43b1d4]"
                >
                  <Trash2 className="w-4 h-4" />
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>

      <AnimatePresence>
        {sheetOpen && (
          <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center">
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={{ duration: reducedMotion ? 0 : 0.16 }}
              onClick={() => setSheetOpen(false)}
              className="fixed inset-0 bg-[#060d18]/45 backdrop-blur-xs"
            />

            <motion.div
              role="dialog"
              aria-modal="true"
              aria-labelledby="add-entry-title"
              initial={reducedMotion ? { opacity: 0 } : { opacity: 0, y: 32 }}
              animate={{ opacity: 1, y: 0 }}
              exit={reducedMotion ? { opacity: 0 } : { opacity: 0, y: 24 }}
              transition={
                reducedMotion
                  ? { duration: 0 }
                  : { duration: 0.2, ease: [0.16, 1, 0.3, 1] }
              }
              className="relative z-10 w-full sm:max-w-md emofah-glass-bar border border-[#103c60]/12 dark:border-[#43b1d4]/20 rounded-t-3xl sm:rounded-3xl p-6 pb-8 sm:pb-6 shadow-2xl"
            >
              <div className="w-10 h-1.5 rounded-full bg-[#103c60]/15 dark:bg-[#43b1d4]/25 mx-auto mb-5 sm:hidden" />

              <div className="flex items-center justify-between mb-4">
                <h2
                  id="add-entry-title"
                  className="font-display text-lg font-semibold text-[#103c60] dark:text-[#f1f5f9]"
                >
                  New Workspace Entry
                </h2>
                <button
                  type="button"
                  onClick={() => setSheetOpen(false)}
                  aria-label="Close modal"
                  className="min-h-[44px] min-w-[44px] -mr-2 flex items-center justify-center rounded-xl text-[#64748b] hover:text-[#103c60] dark:text-[#94a3b8] dark:hover:text-[#f1f5f9] cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#43b1d4]"
                >
                  <X className="w-5 h-5" />
                </button>
              </div>

              <form onSubmit={handleSubmit} className="space-y-4">
                <div>
                  <label
                    htmlFor="entry-title-input"
                    className="block text-xs font-medium text-[#475569] dark:text-[#cbd5e1] mb-1.5"
                  >
                    Title
                  </label>
                  <input
                    id="entry-title-input"
                    type="text"
                    autoFocus
                    value={newTitle}
                    onChange={(e) => setNewTitle(e.target.value)}
                    placeholder="e.g., Add client onboarding flow"
                    className="w-full min-h-[44px] px-3.5 py-2.5 rounded-xl bg-white/80 dark:bg-[#0a182b]/90 border border-[#103c60]/15 dark:border-[#43b1d4]/25 text-sm text-[#0b1f33] dark:text-[#f1f5f9] placeholder:text-[#94a3b8] focus:outline-none focus:ring-2 focus:ring-[#43b1d4]"
                  />
                </div>

                <div>
                  <span className="block text-xs font-medium text-[#475569] dark:text-[#cbd5e1] mb-1.5">
                    Category
                  </span>
                  <div className="grid grid-cols-3 gap-2">
                    {CATEGORIES.map((cat) => {
                      const selected = newCategory === cat;
                      return (
                        <button
                          key={cat}
                          type="button"
                          onClick={() => setNewCategory(cat)}
                          className={`min-h-[40px] px-3 py-2 rounded-xl text-xs font-medium border transition-colors cursor-pointer whitespace-nowrap ${
                            selected
                              ? 'bg-[#14558a] border-[#14558a] text-white dark:bg-[#43b1d4] dark:border-[#43b1d4] dark:text-[#060d18]'
                              : 'bg-white/60 dark:bg-[#0d1f36]/60 border-[#103c60]/12 dark:border-[#43b1d4]/20 text-[#475569] dark:text-[#cbd5e1]'
                          }`}
                        >
                          {cat}
                        </button>
                      );
                    })}
                  </div>
                </div>

                <div className="pt-2 flex items-center justify-end gap-2">
                  <button
                    type="button"
                    onClick={() => setSheetOpen(false)}
                    className="min-h-[44px] px-4 py-2 rounded-xl text-sm font-medium text-[#475569] dark:text-[#cbd5e1] hover:bg-[#103c60]/[0.05] dark:hover:bg-[#43b1d4]/[0.08] cursor-pointer"
                  >
                    Cancel
                  </button>
                  <button
                    type="submit"
                    disabled={!newTitle.trim()}
                    className="min-h-[44px] px-5 py-2 rounded-xl bg-[#14558a] hover:bg-[#103c60] disabled:opacity-45 dark:bg-[#43b1d4] dark:hover:bg-[#348fc2] text-white dark:text-[#060d18] text-sm font-semibold transition-colors cursor-pointer"
                  >
                    Save Entry
                  </button>
                </div>
              </form>
            </motion.div>
          </div>
        )}
      </AnimatePresence>
    </div>
  );
}
