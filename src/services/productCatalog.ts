/**
 * Emofah Agencies — Stage 1.1 Product Recognition Reference Foundation
 * Live Supabase `public.products` mirror + local cache + Recognition Reference Index.
 *
 * Verified live schema columns (2026-11-14):
 * id (int), name (text), qty (int), sell_price (num), buy_price (num),
 * image_url (text|null), category (text), created_at (timestamptz),
 * description (text|null), discount_price (num), is_featured (bool)
 */

export interface SupabaseProductRow {
  id: number;
  name: string;
  qty: number;
  sell_price: number;
  buy_price: number;
  image_url: string | null;
  category: string | null;
  created_at: string | null;
  description: string | null;
  discount_price: number;
  is_featured: boolean;
}

export interface ProductIdentity {
  id: number; // Canonical PK from public.products
  name: string;
  baseName: string;
  variantLabel: string | null;
  brandHint: string | null;
  category: string;
  sellPrice: number;
  discountPrice: number;
  buyPrice: number;
  stockQty: number;
  imageUrl: string | null;
  description: string | null;
  isFeatured: boolean;
  createdAt: string | null;
}

export interface RecognitionReferenceEntry {
  indexVersion: number;
  productId: number;
  canonicalName: string;
  baseName: string;
  variantLabel: string | null;
  brandHint: string | null;
  category: string;
  confirmedAliases: string[];
  searchTokens: string[];
  hasReferenceImage: boolean;
  updatedAt: string;
}

export interface RecognitionIndexSnapshot {
  version: number;
  builtAt: string;
  totalProducts: number;
  withImageCount: number;
  entries: RecognitionReferenceEntry[];
}

const SUPABASE_URL =
  import.meta.env.VITE_SUPABASE_URL ||
  'https://nbmmfdiqywmkjaltezro.supabase.co';
const SUPABASE_ANON_KEY =
  import.meta.env.VITE_SUPABASE_ANON_KEY ||
  'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im5ibW1mZGlxeXdta2phbHRlenJvIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Njc1NTcwNzQsImV4cCI6MjA4MzEzMzA3NH0.LvAJ0xmBohmOKW-7Va8poTsbqJACNMY87SAnZnzqqTs';

const CATALOG_CACHE_KEY = 'emofah-supabase-products-cache-v1';
const ALIASES_CACHE_KEY = 'emofah-confirmed-aliases-v1';
const INDEX_CACHE_KEY = 'emofah-recognition-index-v1';
const INDEX_VERSION = 1;

const KNOWN_BRANDS = [
  'Bic',
  'Express',
  'Obama',
  'Kasuku',
  'Kartasi',
  'Deli',
  'Epson',
  'HP',
  'Faber-Castell',
  'Staedtler',
  'Pelikan',
  'A4',
  'A5',
];

export function extractProductIdentity(row: SupabaseProductRow): ProductIdentity {
  const trimmedName = (row.name || '').trim();
  const parenMatches = [...trimmedName.matchAll(/\(([^)]+)\)/g)]
    .map((m) => m[1].trim())
    .filter(Boolean);
  const sizeMatches = [
    ...trimmedName.matchAll(
      /\b(\d+\s?(?:pgs|pages|ml|l|g|kg|mm|cm|m|pcs|pack))\b/gi
    ),
  ].map((m) => m[1].trim());

  const variantParts = Array.from(
    new Map(
      [...parenMatches, ...sizeMatches].map((p) => [p.toLowerCase(), p])
    ).values()
  );

  const baseName =
    trimmedName
      .replace(/\(([^)]+)\)/g, '')
      .replace(/\s+/g, ' ')
      .trim() || trimmedName;

  const brandHint =
    KNOWN_BRANDS.find((b) =>
      trimmedName.toLowerCase().includes(b.toLowerCase())
    ) ?? null;

  return {
    id: row.id,
    name: trimmedName,
    baseName,
    variantLabel: variantParts.length > 0 ? variantParts.join(' · ') : null,
    brandHint,
    category: row.category?.trim() || 'General',
    sellPrice: Number(row.sell_price) || 0,
    discountPrice: Number(row.discount_price) || 0,
    buyPrice: Number(row.buy_price) || 0,
    stockQty: Number(row.qty) || 0,
    imageUrl: row.image_url?.trim() || null,
    description: row.description?.trim() || null,
    isFeatured: Boolean(row.is_featured),
    createdAt: row.created_at || null,
  };
}

function tokenize(...sources: (string | null | undefined)[]): string[] {
  const tokens = new Set<string>();
  for (const src of sources) {
    if (!src) continue;
    const parts = src
      .toLowerCase()
      .replace(/[^a-z0-9\s]/g, ' ')
      .split(/\s+/)
      .filter((t) => t.length >= 2);
    for (const part of parts) {
      tokens.add(part);
    }
  }
  return Array.from(tokens);
}

export function loadConfirmedAliasesMap(): Record<number, string[]> {
  try {
    const raw = window.localStorage.getItem(ALIASES_CACHE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      if (parsed && typeof parsed === 'object') return parsed;
    }
  } catch {
    // Ignore storage errors
  }
  return {};
}

export function saveConfirmedAlias(
  productId: number,
  alias: string
): Record<number, string[]> {
  const clean = alias.trim();
  const map = loadConfirmedAliasesMap();
  if (!clean) return map;

  const current = map[productId] || [];
  if (!current.some((a) => a.toLowerCase() === clean.toLowerCase())) {
    map[productId] = [...current, clean];
    try {
      window.localStorage.setItem(ALIASES_CACHE_KEY, JSON.stringify(map));
    } catch {
      // Ignore quota errors
    }
  }
  return map;
}

export function buildRecognitionIndex(
  products: ProductIdentity[],
  aliasesMap: Record<number, string[]> = loadConfirmedAliasesMap()
): RecognitionIndexSnapshot {
  const builtAt = new Date().toISOString();
  const entries: RecognitionReferenceEntry[] = products.map((p) => {
    const confirmedAliases = aliasesMap[p.id] || [];
    return {
      indexVersion: INDEX_VERSION,
      productId: p.id,
      canonicalName: p.name,
      baseName: p.baseName,
      variantLabel: p.variantLabel,
      brandHint: p.brandHint,
      category: p.category,
      confirmedAliases,
      searchTokens: tokenize(
        p.name,
        p.baseName,
        p.variantLabel,
        p.brandHint,
        p.category,
        p.description,
        ...confirmedAliases
      ),
      hasReferenceImage: Boolean(p.imageUrl),
      updatedAt: builtAt,
    };
  });

  const snapshot: RecognitionIndexSnapshot = {
    version: INDEX_VERSION,
    builtAt,
    totalProducts: products.length,
    withImageCount: products.filter((p) => Boolean(p.imageUrl)).length,
    entries,
  };

  try {
    window.localStorage.setItem(INDEX_CACHE_KEY, JSON.stringify(snapshot));
  } catch {
    // Ignore quota errors
  }
  return snapshot;
}

export function loadCachedProducts(): ProductIdentity[] {
  try {
    const raw = window.localStorage.getItem(CATALOG_CACHE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) return parsed;
    }
  } catch {
    // Ignore fallback
  }
  return [];
}

export function loadCachedIndex(): RecognitionIndexSnapshot | null {
  try {
    const raw = window.localStorage.getItem(INDEX_CACHE_KEY);
    if (raw) return JSON.parse(raw);
  } catch {
    // Ignore fallback
  }
  return null;
}

export async function fetchLiveSupabaseCatalog(): Promise<{
  products: ProductIdentity[];
  index: RecognitionIndexSnapshot;
  fromCache: boolean;
  error?: string;
}> {
  try {
    const url =
      `${SUPABASE_URL.replace(/\/$/, '')}/rest/v1/products` +
      `?select=id,name,qty,sell_price,buy_price,image_url,category,created_at,description,discount_price,is_featured` +
      `&order=id.asc&limit=1000`;

    const res = await fetch(url, {
      headers: {
        apikey: SUPABASE_ANON_KEY,
        Authorization: `Bearer ${SUPABASE_ANON_KEY}`,
        Accept: 'application/json',
      },
    });

    if (!res.ok) {
      throw new Error(`Supabase HTTP ${res.status}`);
    }

    const rows: SupabaseProductRow[] = await res.json();
    const products = rows.map(extractProductIdentity);
    try {
      window.localStorage.setItem(CATALOG_CACHE_KEY, JSON.stringify(products));
    } catch {
      // Ignore quota errors
    }
    const index = buildRecognitionIndex(products);
    return { products, index, fromCache: false };
  } catch (err) {
    const cached = loadCachedProducts();
    const index = buildRecognitionIndex(cached);
    return {
      products: cached,
      index,
      fromCache: true,
      error: err instanceof Error ? err.message : 'Unable to reach Supabase',
    };
  }
}

export function searchCatalogueAndIndex(
  products: ProductIdentity[],
  index: RecognitionIndexSnapshot | null,
  query: string
): ProductIdentity[] {
  const q = query.trim().toLowerCase();
  if (!q) return products;

  const indexMap = new Map<number, RecognitionReferenceEntry>();
  if (index) {
    for (const e of index.entries) indexMap.set(e.productId, e);
  }

  return products.filter((p) => {
    if (String(p.id) === q) return true;
    if (p.name.toLowerCase().includes(q)) return true;
    if (p.category.toLowerCase().includes(q)) return true;
    if (p.variantLabel?.toLowerCase().includes(q)) return true;
    if (p.brandHint?.toLowerCase().includes(q)) return true;
    const entry = indexMap.get(p.id);
    if (entry) {
      if (entry.confirmedAliases.some((a) => a.toLowerCase().includes(q))) {
        return true;
      }
      if (entry.searchTokens.some((t) => t.includes(q))) return true;
    }
    return false;
  });
}
