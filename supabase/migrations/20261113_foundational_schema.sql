-- =============================================================================
-- Emofah Agencies — Foundational Base Schema
-- SAFE & NON-DESTRUCTIVE: All tables use IF NOT EXISTS.
-- GUARANTEES: Never drops tables, never truncates, never deletes any past data.
-- If tables already exist in your database, PostgreSQL leaves them 100% intact.
-- =============================================================================

-- 1. Master Product Catalogue
CREATE TABLE IF NOT EXISTS public.products (
    id serial PRIMARY KEY,
    name text NOT NULL,
    qty integer NOT NULL DEFAULT 0,
    sell_price numeric NOT NULL DEFAULT 0,
    buy_price numeric NOT NULL DEFAULT 0,
    image_url text NULL,
    category text NULL,
    description text NULL,
    discount_price numeric NOT NULL DEFAULT 0,
    is_featured boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_products_name ON public.products (name);
CREATE INDEX IF NOT EXISTS idx_products_category ON public.products (category);

-- 2. Master Sales Ledger
CREATE TABLE IF NOT EXISTS public.sales (
    id serial PRIMARY KEY,
    product_id integer NOT NULL REFERENCES public.products(id) ON DELETE RESTRICT,
    qty_sold integer NOT NULL CHECK (qty_sold > 0),
    total_price numeric NOT NULL CHECK (total_price >= 0),
    sale_date timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_sales_product_id ON public.sales (product_id);
CREATE INDEX IF NOT EXISTS idx_sales_sale_date ON public.sales (sale_date DESC);

-- 3. Customer Storefront Orders
CREATE TABLE IF NOT EXISTS public.orders (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    status text NOT NULL DEFAULT 'pending',
    total_price numeric NOT NULL DEFAULT 0,
    items jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_orders_status ON public.orders (status);
CREATE INDEX IF NOT EXISTS idx_orders_created_at ON public.orders (created_at DESC);

-- 4. Shared Stock Movement Ledger
CREATE TABLE IF NOT EXISTS public.stock_movements (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id integer NOT NULL REFERENCES public.products(id) ON DELETE RESTRICT,
    change_qty integer NULL,
    delta integer NULL,
    movement_type text NULL,
    reason text NULL,
    reference_id text NULL,
    reference text NULL,
    notes text NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_stock_movements_product ON public.stock_movements (product_id, created_at DESC);

-- 5. Business Expenses
CREATE TABLE IF NOT EXISTS public.expenses (
    id serial PRIMARY KEY,
    category text NOT NULL,
    amount numeric NOT NULL CHECK (amount >= 0),
    note text NULL,
    expense_date timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now()
);

-- 6. System & Hardware Settings
CREATE TABLE IF NOT EXISTS public.settings (
    key text PRIMARY KEY,
    value text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Safe RLS & Grants for foundational tables
ALTER TABLE public.products ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.sales ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.orders ENABLE ROW LEVEL SECURITY;

-- Allow reading public products catalogue
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies WHERE tablename = 'products' AND policyname = 'Allow public read access to products'
    ) THEN
        CREATE POLICY "Allow public read access to products" ON public.products
            FOR SELECT USING (true);
    END IF;
END $$;

-- Allow reading orders
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies WHERE tablename = 'orders' AND policyname = 'Allow public read access to orders'
    ) THEN
        CREATE POLICY "Allow public read access to orders" ON public.orders
            FOR SELECT USING (true);
    END IF;
END $$;

-- Allow reading sales
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies WHERE tablename = 'sales' AND policyname = 'Allow public read access to sales'
    ) THEN
        CREATE POLICY "Allow public read access to sales" ON public.sales
            FOR SELECT USING (true);
    END IF;
END $$;

GRANT SELECT ON public.products TO anon, authenticated;
GRANT SELECT ON public.sales TO anon, authenticated;
GRANT SELECT ON public.orders TO anon, authenticated;
