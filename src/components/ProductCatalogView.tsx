import { useEffect, useMemo, useState } from 'react';
import {
  Package,
  Search,
  RefreshCw,
  ImageOff,
  CheckCircle2,
  AlertCircle,
  Tag,
  Layers,
  Plus,
} from 'lucide-react';
import { motion } from 'motion/react';
import {
  buildRecognitionIndex,
  fetchLiveSupabaseCatalog,
  loadCachedIndex,
  loadCachedProducts,
  loadConfirmedAliasesMap,
  saveConfirmedAlias,
  searchCatalogueAndIndex,
  type ProductIdentity,
  type RecognitionIndexSnapshot,
} from '../services/productCatalog';

interface ProductCatalogViewProps {
  reducedMotion: boolean;
}

export function ProductCatalogView({ reducedMotion }: ProductCatalogViewProps) {
  const [products, setProducts] = useState<ProductIdentity[]>(loadCachedProducts);
  const [indexSnapshot, setIndexSnapshot] =
    useState<RecognitionIndexSnapshot | null>(loadCachedIndex);
  const [aliasesMap, setAliasesMap] = useState<Record<number, string[]>>(
    loadConfirmedAliasesMap
  );
  const [searchQuery, setSearchQuery] = useState('');
  const [categoryFilter, setCategoryFilter] = useState<string>('All');
  const [selectedProduct, setSelectedProduct] =
    useState<ProductIdentity | null>(null);
  const [newAlias, setNewAlias] = useState('');
  const [loading, setLoading] = useState(true);
  const [syncError, setSyncError] = useState<string | null>(null);
  const [fromCache, setFromCache] = useState(false);
  const [brokenImageIds, setBrokenImageIds] = useState<Record<number, boolean>>(
    {}
  );

  const runSync = async () => {
    setLoading(true);
    setSyncError(null);
    const result = await fetchLiveSupabaseCatalog();
    setProducts(result.products);
    setIndexSnapshot(result.index);
    setFromCache(result.fromCache);
    if (result.error) setSyncError(result.error);
    setLoading(false);
  };

  useEffect(() => {
    void runSync();
  }, []);

  const handleRebuildIndex = () => {
    const nextIndex = buildRecognitionIndex(products, aliasesMap);
    setIndexSnapshot(nextIndex);
  };

  const handleAddAlias = (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedProduct || !newAlias.trim()) return;
    const updatedMap = saveConfirmedAlias(selectedProduct.id, newAlias);
    setAliasesMap({ ...updatedMap });
    const nextIndex = buildRecognitionIndex(products, updatedMap);
    setIndexSnapshot(nextIndex);
    setNewAlias('');
  };

  const categories = useMemo(() => {
    const set = new Set(products.map((p) => p.category));
    return ['All', ...Array.from(set)];
  }, [products]);

  const filteredItems = useMemo(() => {
    const searched = searchCatalogueAndIndex(
      products,
      indexSnapshot,
      searchQuery
    );
    if (categoryFilter === 'All') return searched;
    return searched.filter((p) => p.category === categoryFilter);
  }, [products, indexSnapshot, searchQuery, categoryFilter]);

  const tapMotion = reducedMotion ? undefined : { scale: 0.985 };

  const selectedIndexEntry = useMemo(() => {
    if (!selectedProduct || !indexSnapshot) return null;
    return (
      indexSnapshot.entries.find((e) => e.productId === selectedProduct.id) ??
      null
    );
  }, [selectedProduct, indexSnapshot]);

  return (
    <div className="space-y-6">
      {/* Header & Index Summary */}
      <section className="emofah-glass rounded-3xl p-6 sm:p-8 space-y-5">
        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
          <div>
            <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-[#14558a]/10 dark:bg-[#43b1d4]/15 text-[#14558a] dark:text-[#43b1d4] text-xs font-semibold">
              <Package className="w-3.5 h-3.5" />
              <span>Stage 1.1 · Live Supabase Catalogue &amp; Recognition Index</span>
            </div>
            <h1 className="font-display text-2xl sm:text-3xl font-bold text-[#103c60] dark:text-[#f1f5f9] tracking-tight mt-2">
              Authoritative Product Catalogue
            </h1>
            <p className="text-sm text-[#475569] dark:text-[#cbd5e1] mt-1">
              Connected to live <code className="font-mono text-xs">public.products</code> using canonical integer <code className="font-mono text-xs">id</code>, reference images (<code className="font-mono text-xs">product-images</code>), extracted variants, and confirmed aliases.
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <motion.button
              type="button"
              whileTap={tapMotion}
              onClick={runSync}
              disabled={loading}
              className="min-h-[42px] px-4 py-2 rounded-xl bg-[#14558a] hover:bg-[#103c60] dark:bg-[#43b1d4] dark:hover:bg-[#348fc2] text-white dark:text-[#060d18] text-xs font-semibold inline-flex items-center gap-2 cursor-pointer disabled:opacity-60"
            >
              <RefreshCw
                className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`}
              />
              <span>{loading ? 'Syncing...' : 'Sync Supabase'}</span>
            </motion.button>

            <motion.button
              type="button"
              whileTap={tapMotion}
              onClick={handleRebuildIndex}
              className="min-h-[42px] px-4 py-2 rounded-xl bg-white/80 dark:bg-[#0d1f36] border border-[#103c60]/15 dark:border-[#43b1d4]/25 text-[#103c60] dark:text-[#f1f5f9] text-xs font-semibold inline-flex items-center gap-2 cursor-pointer"
            >
              <Layers className="w-3.5 h-3.5" />
              <span>Rebuild Index</span>
            </motion.button>
          </div>
        </div>

        {/* Status Bar */}
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
          <div className="p-3 rounded-2xl bg-white/70 dark:bg-[#0a182b]/70 border border-[#103c60]/10 dark:border-[#43b1d4]/15">
            <div className="text-[11px] text-[#64748b] dark:text-[#94a3b8]">
              Catalogue Records
            </div>
            <div className="font-display text-lg font-bold text-[#103c60] dark:text-[#f1f5f9]">
              {products.length}
            </div>
          </div>
          <div className="p-3 rounded-2xl bg-white/70 dark:bg-[#0a182b]/70 border border-[#103c60]/10 dark:border-[#43b1d4]/15">
            <div className="text-[11px] text-[#64748b] dark:text-[#94a3b8]">
              With Reference Photo
            </div>
            <div className="font-display text-lg font-bold text-[#14558a] dark:text-[#43b1d4]">
              {indexSnapshot?.withImageCount ?? 0}
            </div>
          </div>
          <div className="p-3 rounded-2xl bg-white/70 dark:bg-[#0a182b]/70 border border-[#103c60]/10 dark:border-[#43b1d4]/15">
            <div className="text-[11px] text-[#64748b] dark:text-[#94a3b8]">
              Index Version
            </div>
            <div className="font-mono text-base font-bold text-[#103c60] dark:text-[#f1f5f9]">
              v{indexSnapshot?.version ?? 1}
            </div>
          </div>
          <div className="p-3 rounded-2xl bg-white/70 dark:bg-[#0a182b]/70 border border-[#103c60]/10 dark:border-[#43b1d4]/15">
            <div className="text-[11px] text-[#64748b] dark:text-[#94a3b8]">
              Source State
            </div>
            <div className="text-xs font-semibold mt-1 inline-flex items-center gap-1.5 text-[#103c60] dark:text-[#f1f5f9]">
              {syncError || fromCache ? (
                <>
                  <AlertCircle className="w-3.5 h-3.5 text-amber-500" />
                  <span>Local Cache</span>
                </>
              ) : (
                <>
                  <CheckCircle2 className="w-3.5 h-3.5 text-emerald-500" />
                  <span>Live Supabase</span>
                </>
              )}
            </div>
          </div>
        </div>

        {syncError && (
          <div className="p-3 rounded-xl bg-amber-500/10 border border-amber-500/20 text-xs text-amber-700 dark:text-amber-300">
            {syncError}
          </div>
        )}

        {/* Search & Category Filters */}
        <div className="space-y-3">
          <div className="relative">
            <Search className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-[#64748b]" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Search by ID, product name, variant, brand hint, or confirmed alias..."
              className="w-full min-h-[44px] pl-10 pr-4 rounded-xl bg-white/85 dark:bg-[#0d1f36]/90 border border-[#103c60]/12 dark:border-[#43b1d4]/20 text-sm text-[#103c60] dark:text-[#f1f5f9] focus:outline-none focus:ring-2 focus:ring-[#43b1d4]"
            />
          </div>

          <div className="flex flex-wrap gap-2">
            {categories.map((cat) => (
              <button
                key={cat}
                type="button"
                onClick={() => setCategoryFilter(cat)}
                className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer ${
                  categoryFilter === cat
                    ? 'bg-[#14558a] text-white dark:bg-[#43b1d4] dark:text-[#060d18]'
                    : 'bg-white/70 dark:bg-[#0d1f36] text-[#475569] dark:text-[#94a3b8] border border-[#103c60]/10 dark:border-[#43b1d4]/15'
                }`}
              >
                {cat}
              </button>
            ))}
          </div>
        </div>
      </section>

      {/* Product Grid */}
      {loading && products.length === 0 ? (
        <div className="emofah-glass rounded-2xl p-10 text-center text-sm text-[#64748b]">
          Loading live products from Supabase...
        </div>
      ) : filteredItems.length === 0 ? (
        <div className="emofah-glass rounded-2xl p-10 text-center text-sm text-[#64748b]">
          No products match "{searchQuery}".
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {filteredItems.map((item) => {
            const hasValidImg =
              Boolean(item.imageUrl) && !brokenImageIds[item.id];
            const aliases = aliasesMap[item.id] || [];
            return (
              <motion.div
                key={item.id}
                whileTap={tapMotion}
                onClick={() => setSelectedProduct(item)}
                className="emofah-glass rounded-2xl p-4 flex items-center gap-4 hover:border-[#1768a7]/40 dark:hover:border-[#43b1d4]/40 transition-colors cursor-pointer group"
              >
                <div className="w-16 h-16 rounded-xl bg-white dark:bg-[#0d1f36] border border-[#103c60]/10 dark:border-[#43b1d4]/15 flex items-center justify-center shrink-0 overflow-hidden">
                  {hasValidImg ? (
                    <img
                      src={item.imageUrl!}
                      alt={item.name}
                      loading="lazy"
                      onError={() =>
                        setBrokenImageIds((prev) => ({
                          ...prev,
                          [item.id]: true,
                        }))
                      }
                      className="w-full h-full object-contain p-1"
                    />
                  ) : (
                    <ImageOff className="w-5 h-5 text-[#94a3b8]" />
                  )}
                </div>

                <div className="min-w-0 flex-1 space-y-1">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-[#103c60]/8 dark:bg-[#43b1d4]/15 text-[#14558a] dark:text-[#43b1d4]">
                      ID #{item.id}
                    </span>
                    <span className="text-[10px] text-[#64748b] dark:text-[#94a3b8]">
                      {item.category}
                    </span>
                    {item.variantLabel && (
                      <span className="text-[10px] px-1.5 py-0.5 rounded bg-emerald-500/10 text-emerald-700 dark:text-emerald-300">
                        {item.variantLabel}
                      </span>
                    )}
                  </div>

                  <h3 className="font-display text-sm font-semibold text-[#103c60] dark:text-[#f1f5f9] truncate group-hover:text-[#1768a7] dark:group-hover:text-[#43b1d4]">
                    {item.name}
                  </h3>

                  <div className="flex items-center justify-between text-xs">
                    <span className="font-bold text-[#14558a] dark:text-[#43b1d4]">
                      Ksh {item.sellPrice.toLocaleString()}
                    </span>
                    <span className="text-[#64748b] dark:text-[#94a3b8]">
                      Qty: <strong className="text-[#103c60] dark:text-[#f1f5f9]">{item.stockQty}</strong>
                      {aliases.length > 0 && ` · ${aliases.length} alias`}
                    </span>
                  </div>
                </div>
              </motion.div>
            );
          })}
        </div>
      )}

      {/* Product Detail & Confirmed Alias Modal */}
      {selectedProduct && (
        <div className="fixed inset-0 z-50 bg-black/50 backdrop-blur-xs flex items-center justify-center p-4">
          <motion.div
            initial={{ opacity: 0, scale: 0.96 }}
            animate={{ opacity: 1, scale: 1 }}
            className="emofah-glass rounded-3xl p-6 sm:p-8 max-w-lg w-full max-h-[90vh] overflow-y-auto space-y-5 bg-white dark:bg-[#0a182b]"
          >
            <div className="flex items-start justify-between gap-3">
              <div>
                <span className="text-xs font-mono text-[#14558a] dark:text-[#43b1d4]">
                  Canonical ID #{selectedProduct.id} · {selectedProduct.category}
                </span>
                <h2 className="font-display text-xl font-bold text-[#103c60] dark:text-[#f1f5f9] mt-0.5">
                  {selectedProduct.name}
                </h2>
              </div>
              <button
                type="button"
                onClick={() => setSelectedProduct(null)}
                className="w-8 h-8 rounded-full bg-[#103c60]/10 dark:bg-[#43b1d4]/15 flex items-center justify-center text-[#103c60] dark:text-[#f1f5f9] font-bold cursor-pointer"
              >
                ✕
              </button>
            </div>

            {selectedProduct.imageUrl && !brokenImageIds[selectedProduct.id] ? (
              <div className="h-40 rounded-2xl bg-white dark:bg-[#0d1f36] border border-[#103c60]/10 dark:border-[#43b1d4]/15 flex items-center justify-center p-3">
                <img
                  src={selectedProduct.imageUrl}
                  alt={selectedProduct.name}
                  className="max-h-full max-w-full object-contain"
                />
              </div>
            ) : (
              <div className="h-24 rounded-2xl bg-[#103c60]/5 dark:bg-[#43b1d4]/10 flex items-center justify-center gap-2 text-xs text-[#64748b]">
                <ImageOff className="w-4 h-4" />
                <span>No reference photograph stored in Supabase yet</span>
              </div>
            )}

            <div className="grid grid-cols-3 gap-2.5 text-center">
              <div className="p-2.5 rounded-xl bg-[#103c60]/5 dark:bg-[#0d1f36]">
                <div className="text-[11px] text-[#64748b]">Sell Price</div>
                <div className="font-bold text-sm text-[#14558a] dark:text-[#43b1d4]">
                  Ksh {selectedProduct.sellPrice.toLocaleString()}
                </div>
              </div>
              <div className="p-2.5 rounded-xl bg-[#103c60]/5 dark:bg-[#0d1f36]">
                <div className="text-[11px] text-[#64748b]">Buy Price</div>
                <div className="font-bold text-sm text-[#103c60] dark:text-[#f1f5f9]">
                  Ksh {selectedProduct.buyPrice.toLocaleString()}
                </div>
              </div>
              <div className="p-2.5 rounded-xl bg-[#103c60]/5 dark:bg-[#0d1f36]">
                <div className="text-[11px] text-[#64748b]">Stock (qty)</div>
                <div className="font-bold text-sm text-[#103c60] dark:text-[#f1f5f9]">
                  {selectedProduct.stockQty}
                </div>
              </div>
            </div>

            <div className="space-y-1.5 text-xs text-[#475569] dark:text-[#94a3b8]">
              <div>
                <strong>Base Name:</strong> {selectedProduct.baseName}
              </div>
              <div>
                <strong>Extracted Variant:</strong>{' '}
                {selectedProduct.variantLabel || 'None'}
              </div>
              <div>
                <strong>Brand Hint:</strong>{' '}
                {selectedProduct.brandHint || 'None detected'}
              </div>
            </div>

            {/* Recognition Tokens & Confirmed Aliases */}
            <div className="space-y-2">
              <div className="text-xs font-semibold text-[#103c60] dark:text-[#f1f5f9] flex items-center gap-1.5">
                <Tag className="w-3.5 h-3.5" />
                <span>Index Tokens &amp; Confirmed Aliases</span>
              </div>
              <div className="flex flex-wrap gap-1.5">
                {(selectedIndexEntry?.searchTokens || []).map((tok) => (
                  <span
                    key={tok}
                    className="text-[11px] font-mono px-2 py-0.5 rounded bg-[#103c60]/8 dark:bg-[#43b1d4]/15 text-[#103c60] dark:text-[#cbd5e1]"
                  >
                    {tok}
                  </span>
                ))}
                {(aliasesMap[selectedProduct.id] || []).map((alias) => (
                  <span
                    key={alias}
                    className="text-[11px] px-2 py-0.5 rounded bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 font-medium"
                  >
                    alias: {alias}
                  </span>
                ))}
              </div>

              <form onSubmit={handleAddAlias} className="flex gap-2 pt-2">
                <input
                  type="text"
                  value={newAlias}
                  onChange={(e) => setNewAlias(e.target.value)}
                  placeholder="Add human-confirmed recognition alias..."
                  className="flex-1 min-h-[38px] px-3 rounded-xl bg-white dark:bg-[#0d1f36] border border-[#103c60]/15 dark:border-[#43b1d4]/20 text-xs text-[#103c60] dark:text-[#f1f5f9]"
                />
                <button
                  type="submit"
                  className="px-3 min-h-[38px] rounded-xl bg-[#14558a] dark:bg-[#43b1d4] text-white dark:text-[#060d18] text-xs font-semibold inline-flex items-center gap-1 cursor-pointer"
                >
                  <Plus className="w-3.5 h-3.5" />
                  <span>Save</span>
                </button>
              </form>
            </div>
          </motion.div>
        </div>
      )}
    </div>
  );
}
