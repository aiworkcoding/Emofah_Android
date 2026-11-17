-- =============================================================================
-- EMOFAH AGENCIES — COMPLETE ALL-IN-ONE SUPABASE BACKEND SETUP SCRIPT
-- =============================================================================
-- Project: Emofah Agencies (nbmmfdiqywmkjaltezro)
-- Purpose: Execute this SINGLE script in Supabase Dashboard -> SQL Editor to
--          provision all base tables, 16 migrations, RPCs, RLS rules, & triggers.
-- Safe to re-run: All statements are 100% idempotent (IF NOT EXISTS / OR REPLACE).
-- DATA PROTECTION GUARANTEE: Never drops, truncates, or deletes any past data.
-- If tables/rows already exist (e.g. products, sales), they remain 100% intact!
-- =============================================================================

-- -----------------------------------------------------------------------------
-- SECTION 1 of 17: supabase/migrations/20261113_foundational_schema.sql
-- -----------------------------------------------------------------------------
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



-- -----------------------------------------------------------------------------
-- SECTION 2 of 17: supabase/migrations/20261114_record_android_sale_rpc.sql
-- -----------------------------------------------------------------------------
-- Emofah Agencies — Stage 2.2 Additive Idempotent Android Sale RPC
-- Non-destructive: leaves existing public.sales, public.products, and public.record_sale intact
-- for Desktop and Web Storefront compatibility.

CREATE TABLE IF NOT EXISTS public.android_sale_idempotency (
    idempotency_key text PRIMARY KEY,
    client_sale_id text NOT NULL UNIQUE,
    server_sale_id integer NOT NULL REFERENCES public.sales(id) ON DELETE RESTRICT,
    product_id integer NOT NULL REFERENCES public.products(id) ON DELETE RESTRICT,
    qty_sold integer NOT NULL CHECK (qty_sold > 0),
    total_price numeric NOT NULL CHECK (total_price > 0),
    payment_method text NOT NULL DEFAULT 'CASH',
    actor_id text NOT NULL DEFAULT 'android_staff',
    device_id text NOT NULL DEFAULT 'android',
    source text NOT NULL DEFAULT 'ANDROID',
    remaining_qty integer NOT NULL,
    sale_date timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_android_sale_idempotency_product
    ON public.android_sale_idempotency (product_id, created_at DESC);

ALTER TABLE public.android_sale_idempotency ENABLE ROW LEVEL SECURITY;

-- Keep direct table access locked down; clients execute via SECURITY DEFINER RPC below.
REVOKE ALL ON public.android_sale_idempotency FROM anon, authenticated;

CREATE OR REPLACE FUNCTION public.record_android_sale(
    p_product_id bigint,
    p_qty integer,
    p_total numeric,
    p_client_sale_id text,
    p_idempotency_key text,
    p_payment_method text DEFAULT 'CASH',
    p_actor_id text DEFAULT 'android_staff',
    p_device_id text DEFAULT 'android',
    p_sale_date timestamptz DEFAULT now()
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_existing public.android_sale_idempotency%ROWTYPE;
    v_current_qty integer;
    v_remaining_qty integer;
    v_sale_id integer;
BEGIN
    IF p_product_id IS NULL OR p_product_id <= 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Valid product ID is required.'
        );
    END IF;

    IF p_qty IS NULL OR p_qty <= 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Quantity sold must be at least 1.'
        );
    END IF;

    IF p_total IS NULL OR p_total <= 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Sale total must be greater than zero.'
        );
    END IF;

    IF p_idempotency_key IS NULL OR btrim(p_idempotency_key) = '' OR p_client_sale_id IS NULL OR btrim(p_client_sale_id) = '' THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Stable client_sale_id and idempotency_key are required.'
        );
    END IF;

    -- Idempotency check first: retry of the exact same sale returns existing server ACK without double deduction.
    SELECT *
    INTO v_existing
    FROM public.android_sale_idempotency
    WHERE idempotency_key = p_idempotency_key
       OR client_sale_id = p_client_sale_id
    LIMIT 1;

    IF FOUND THEN
        SELECT qty INTO v_current_qty FROM public.products WHERE id = v_existing.product_id;
        RETURN jsonb_build_object(
            'status', 'DUPLICATE_IGNORED',
            'server_sale_id', v_existing.server_sale_id,
            'remaining_qty', COALESCE(v_current_qty, v_existing.remaining_qty),
            'already_committed', true
        );
    END IF;

    -- Lock the canonical product row for atomic stock check & deduction.
    SELECT qty
    INTO v_current_qty
    FROM public.products
    WHERE id = p_product_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object(
            'status', 'PRODUCT_NOT_FOUND',
            'message', format('Product #%s does not exist in catalogue.', p_product_id)
        );
    END IF;

    IF COALESCE(v_current_qty, 0) < p_qty THEN
        RETURN jsonb_build_object(
            'status', 'INSUFFICIENT_STOCK',
            'available_qty', COALESCE(v_current_qty, 0),
            'message', format('Insufficient stock: requested %s but only %s remaining.', p_qty, COALESCE(v_current_qty, 0))
        );
    END IF;

    INSERT INTO public.sales (product_id, qty_sold, total_price, sale_date)
    VALUES (p_product_id::integer, p_qty, p_total, COALESCE(p_sale_date, now()))
    RETURNING id INTO v_sale_id;

    UPDATE public.products
    SET qty = qty - p_qty
    WHERE id = p_product_id
    RETURNING qty INTO v_remaining_qty;

    INSERT INTO public.android_sale_idempotency (
        idempotency_key,
        client_sale_id,
        server_sale_id,
        product_id,
        qty_sold,
        total_price,
        payment_method,
        actor_id,
        device_id,
        source,
        remaining_qty,
        sale_date
    )
    VALUES (
        p_idempotency_key,
        p_client_sale_id,
        v_sale_id,
        p_product_id::integer,
        p_qty,
        p_total,
        COALESCE(NULLIF(btrim(p_payment_method), ''), 'CASH'),
        COALESCE(NULLIF(btrim(p_actor_id), ''), 'android_staff'),
        COALESCE(NULLIF(btrim(p_device_id), ''), 'android'),
        'ANDROID',
        v_remaining_qty,
        COALESCE(p_sale_date, now())
    );

    RETURN jsonb_build_object(
        'status', 'COMMITTED',
        'server_sale_id', v_sale_id,
        'remaining_qty', v_remaining_qty,
        'already_committed', false
    );
END;
$$;

REVOKE ALL ON FUNCTION public.record_android_sale(bigint, integer, numeric, text, text, text, text, text, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.record_android_sale(bigint, integer, numeric, text, text, text, text, text, timestamptz) TO anon, authenticated, service_role;



-- -----------------------------------------------------------------------------
-- SECTION 3 of 17: supabase/migrations/20261114_record_android_sale_price_override.sql
-- -----------------------------------------------------------------------------
-- Emofah Agencies — Stage 2.3 Additive Price Override Audit Columns & RPC Enforcement
-- Non-destructive: preserves all existing sales, products, and 9-arg default-price callers.

ALTER TABLE public.android_sale_idempotency
    ADD COLUMN IF NOT EXISTS unit_sell_price numeric NULL,
    ADD COLUMN IF NOT EXISTS original_sell_price numeric NULL,
    ADD COLUMN IF NOT EXISTS price_override_reason text NULL,
    ADD COLUMN IF NOT EXISTS actor_role text NOT NULL DEFAULT 'CASHIER';

DROP FUNCTION IF EXISTS public.record_android_sale(
    bigint, integer, numeric, text, text, text, text, text, timestamptz
);

CREATE OR REPLACE FUNCTION public.record_android_sale(
    p_product_id bigint,
    p_qty integer,
    p_total numeric,
    p_client_sale_id text,
    p_idempotency_key text,
    p_payment_method text DEFAULT 'CASH',
    p_actor_id text DEFAULT 'android_staff',
    p_device_id text DEFAULT 'android',
    p_sale_date timestamptz DEFAULT now(),
    p_unit_sell_price numeric DEFAULT NULL,
    p_original_sell_price numeric DEFAULT NULL,
    p_price_override_reason text DEFAULT NULL,
    p_actor_role text DEFAULT 'CASHIER'
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_existing public.android_sale_idempotency%ROWTYPE;
    v_current_qty integer;
    v_remaining_qty integer;
    v_sale_id integer;
    v_clean_role text;
    v_clean_reason text;
    v_is_override boolean;
BEGIN
    IF p_product_id IS NULL OR p_product_id <= 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Valid product ID is required.'
        );
    END IF;

    IF p_qty IS NULL OR p_qty <= 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Quantity sold must be at least 1.'
        );
    END IF;

    IF p_total IS NULL OR p_total <= 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Sale total must be greater than zero.'
        );
    END IF;

    IF p_idempotency_key IS NULL OR btrim(p_idempotency_key) = '' OR p_client_sale_id IS NULL OR btrim(p_client_sale_id) = '' THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Stable client_sale_id and idempotency_key are required.'
        );
    END IF;

    v_clean_role := upper(COALESCE(NULLIF(btrim(p_actor_role), ''), 'CASHIER'));
    v_clean_reason := NULLIF(btrim(COALESCE(p_price_override_reason, '')), '');
    v_is_override := p_unit_sell_price IS NOT NULL
        AND p_original_sell_price IS NOT NULL
        AND abs(p_unit_sell_price - p_original_sell_price) > 0.009;

    IF v_is_override THEN
        IF v_clean_role NOT IN ('SUPERVISOR', 'OWNER_MANAGER') THEN
            RETURN jsonb_build_object(
                'status', 'INVALID_INPUT',
                'message', 'Price override requires Supervisor or Owner/Manager authorization.'
            );
        END IF;
        IF p_unit_sell_price <= 0 THEN
            RETURN jsonb_build_object(
                'status', 'INVALID_INPUT',
                'message', 'Overridden unit price must be greater than zero.'
            );
        END IF;
        IF v_clean_reason IS NULL THEN
            RETURN jsonb_build_object(
                'status', 'INVALID_INPUT',
                'message', 'A reason is required for authorized selling price exceptions.'
            );
        END IF;
    END IF;

    -- Idempotency check first: retry of the exact same sale returns existing server ACK without double deduction.
    SELECT *
    INTO v_existing
    FROM public.android_sale_idempotency
    WHERE idempotency_key = p_idempotency_key
       OR client_sale_id = p_client_sale_id
    LIMIT 1;

    IF FOUND THEN
        SELECT qty INTO v_current_qty FROM public.products WHERE id = v_existing.product_id;
        RETURN jsonb_build_object(
            'status', 'DUPLICATE_IGNORED',
            'server_sale_id', v_existing.server_sale_id,
            'remaining_qty', COALESCE(v_current_qty, v_existing.remaining_qty),
            'already_committed', true
        );
    END IF;

    -- Lock the canonical product row for atomic stock check & deduction.
    SELECT qty
    INTO v_current_qty
    FROM public.products
    WHERE id = p_product_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object(
            'status', 'PRODUCT_NOT_FOUND',
            'message', format('Product #%s does not exist in catalogue.', p_product_id)
        );
    END IF;

    IF COALESCE(v_current_qty, 0) < p_qty THEN
        RETURN jsonb_build_object(
            'status', 'INSUFFICIENT_STOCK',
            'available_qty', COALESCE(v_current_qty, 0),
            'message', format('Insufficient stock: requested %s but only %s remaining.', p_qty, COALESCE(v_current_qty, 0))
        );
    END IF;

    INSERT INTO public.sales (product_id, qty_sold, total_price, sale_date)
    VALUES (p_product_id::integer, p_qty, p_total, COALESCE(p_sale_date, now()))
    RETURNING id INTO v_sale_id;

    UPDATE public.products
    SET qty = qty - p_qty
    WHERE id = p_product_id
    RETURNING qty INTO v_remaining_qty;

    INSERT INTO public.android_sale_idempotency (
        idempotency_key,
        client_sale_id,
        server_sale_id,
        product_id,
        qty_sold,
        total_price,
        payment_method,
        actor_id,
        device_id,
        source,
        remaining_qty,
        sale_date,
        unit_sell_price,
        original_sell_price,
        price_override_reason,
        actor_role
    )
    VALUES (
        p_idempotency_key,
        p_client_sale_id,
        v_sale_id,
        p_product_id::integer,
        p_qty,
        p_total,
        COALESCE(NULLIF(btrim(p_payment_method), ''), 'CASH'),
        COALESCE(NULLIF(btrim(p_actor_id), ''), 'android_staff'),
        COALESCE(NULLIF(btrim(p_device_id), ''), 'android'),
        'ANDROID',
        v_remaining_qty,
        COALESCE(p_sale_date, now()),
        p_unit_sell_price,
        p_original_sell_price,
        v_clean_reason,
        v_clean_role
    );

    RETURN jsonb_build_object(
        'status', 'COMMITTED',
        'server_sale_id', v_sale_id,
        'remaining_qty', v_remaining_qty,
        'already_committed', false
    );
END;
$$;

REVOKE ALL ON FUNCTION public.record_android_sale(
    bigint, integer, numeric, text, text, text, text, text, timestamptz, numeric, numeric, text, text
) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.record_android_sale(
    bigint, integer, numeric, text, text, text, text, text, timestamptz, numeric, numeric, text, text
) TO anon, authenticated, service_role;



-- -----------------------------------------------------------------------------
-- SECTION 4 of 17: supabase/migrations/20261114_reverse_android_sale_rpc.sql
-- -----------------------------------------------------------------------------
-- Emofah Agencies — Stage 2.4 Additive Authorised Sale Reversal RPC
-- Non-destructive: never deletes public.sales or financial records; restores product stock atomically
-- and records an idempotent reversal & accounting treatment ledger entry.

CREATE TABLE IF NOT EXISTS public.android_sale_reversals (
    idempotency_key text PRIMARY KEY,
    client_sale_id text NOT NULL UNIQUE,
    server_sale_id integer NULL,
    product_id integer NOT NULL REFERENCES public.products(id) ON DELETE RESTRICT,
    restored_qty integer NOT NULL CHECK (restored_qty > 0),
    reversed_amount numeric NOT NULL CHECK (reversed_amount > 0),
    payment_method text NOT NULL DEFAULT 'CASH',
    accounting_treatment text NOT NULL DEFAULT 'SALE_REVERSAL_CREDIT_ADJUSTMENT',
    reason text NOT NULL,
    actor_id text NOT NULL DEFAULT 'android_staff',
    actor_role text NOT NULL DEFAULT 'SUPERVISOR',
    device_id text NOT NULL DEFAULT 'android',
    remaining_qty integer NOT NULL,
    reversed_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_android_sale_reversals_product
    ON public.android_sale_reversals (product_id, created_at DESC);

ALTER TABLE public.android_sale_reversals ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON public.android_sale_reversals FROM anon, authenticated;

CREATE OR REPLACE FUNCTION public.reverse_android_sale(
    p_client_sale_id text,
    p_server_sale_id integer,
    p_product_id bigint,
    p_qty integer,
    p_total numeric,
    p_idempotency_key text,
    p_reason text,
    p_payment_method text DEFAULT 'CASH',
    p_actor_id text DEFAULT 'android_staff',
    p_actor_role text DEFAULT 'CASHIER',
    p_device_id text DEFAULT 'android',
    p_reversed_at timestamptz DEFAULT now()
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_existing public.android_sale_reversals%ROWTYPE;
    v_sale_record public.sales%ROWTYPE;
    v_current_qty integer;
    v_remaining_qty integer;
    v_clean_role text;
    v_clean_reason text;
    v_restore_qty integer;
    v_reverse_total numeric;
BEGIN
    IF p_client_sale_id IS NULL OR btrim(p_client_sale_id) = '' OR
       p_idempotency_key IS NULL OR btrim(p_idempotency_key) = '' THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Valid client_sale_id and idempotency_key are required.'
        );
    END IF;

    v_clean_role := upper(COALESCE(NULLIF(btrim(p_actor_role), ''), 'CASHIER'));
    IF v_clean_role NOT IN ('SUPERVISOR', 'OWNER_MANAGER') THEN
        RETURN jsonb_build_object(
            'status', 'UNAUTHORIZED_ROLE',
            'message', 'Reversing a completed sale requires Supervisor or Owner/Manager authorization.'
        );
    END IF;

    v_clean_reason := NULLIF(btrim(COALESCE(p_reason, '')), '');
    IF v_clean_reason IS NULL OR char_length(v_clean_reason) < 3 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'A clear reversal reason is required.'
        );
    END IF;

    -- Idempotency check: never apply a reversal twice for the same sale or key.
    SELECT *
    INTO v_existing
    FROM public.android_sale_reversals
    WHERE idempotency_key = p_idempotency_key
       OR client_sale_id = p_client_sale_id
    LIMIT 1;

    IF FOUND THEN
        SELECT qty INTO v_current_qty FROM public.products WHERE id = v_existing.product_id;
        RETURN jsonb_build_object(
            'status', 'ALREADY_REVERSED',
            'server_sale_id', v_existing.server_sale_id,
            'remaining_qty', COALESCE(v_current_qty, v_existing.remaining_qty),
            'already_reversed', true
        );
    END IF;

    IF p_product_id IS NULL OR p_product_id <= 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Valid product ID is required for stock restoration.'
        );
    END IF;

    v_restore_qty := COALESCE(p_qty, 0);
    v_reverse_total := COALESCE(p_total, 0);

    IF p_server_sale_id IS NOT NULL AND p_server_sale_id > 0 THEN
        SELECT *
        INTO v_sale_record
        FROM public.sales
        WHERE id = p_server_sale_id
        LIMIT 1;

        IF FOUND THEN
            v_restore_qty := COALESCE(NULLIF(v_sale_record.qty_sold, 0), v_restore_qty);
            v_reverse_total := COALESCE(NULLIF(v_sale_record.total_price, 0), v_reverse_total);
        END IF;
    END IF;

    IF v_restore_qty <= 0 OR v_reverse_total <= 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Reversed quantity and amount must be greater than zero.'
        );
    END IF;

    -- Lock the canonical product row and restore stock atomically without deleting public.sales.
    SELECT qty
    INTO v_current_qty
    FROM public.products
    WHERE id = p_product_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object(
            'status', 'PRODUCT_NOT_FOUND',
            'message', format('Product #%s does not exist in catalogue.', p_product_id)
        );
    END IF;

    UPDATE public.products
    SET qty = COALESCE(qty, 0) + v_restore_qty
    WHERE id = p_product_id
    RETURNING qty INTO v_remaining_qty;

    INSERT INTO public.android_sale_reversals (
        idempotency_key,
        client_sale_id,
        server_sale_id,
        product_id,
        restored_qty,
        reversed_amount,
        payment_method,
        accounting_treatment,
        reason,
        actor_id,
        actor_role,
        device_id,
        remaining_qty,
        reversed_at
    )
    VALUES (
        p_idempotency_key,
        p_client_sale_id,
        p_server_sale_id,
        p_product_id::integer,
        v_restore_qty,
        v_reverse_total,
        COALESCE(NULLIF(btrim(p_payment_method), ''), 'CASH'),
        'SALE_REVERSAL_CREDIT_ADJUSTMENT',
        v_clean_reason,
        COALESCE(NULLIF(btrim(p_actor_id), ''), 'android_staff'),
        v_clean_role,
        COALESCE(NULLIF(btrim(p_device_id), ''), 'android'),
        v_remaining_qty,
        COALESCE(p_reversed_at, now())
    );

    RETURN jsonb_build_object(
        'status', 'COMMITTED',
        'server_sale_id', p_server_sale_id,
        'restored_qty', v_restore_qty,
        'remaining_qty', v_remaining_qty,
        'already_reversed', false
    );
END;
$$;

REVOKE ALL ON FUNCTION public.reverse_android_sale(
    text, integer, bigint, integer, numeric, text, text, text, text, text, text, timestamptz
) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.reverse_android_sale(
    text, integer, bigint, integer, numeric, text, text, text, text, text, text, timestamptz
) TO anon, authenticated, service_role;



-- -----------------------------------------------------------------------------
-- SECTION 5 of 17: supabase/migrations/20261114_register_android_product_rpc.sql
-- -----------------------------------------------------------------------------
-- Emofah Agencies — Stage 1.5 Additive Idempotent Android Product Registration RPC
-- Non-destructive: preserves existing public.products rows and storefront/desktop compatibility.

CREATE TABLE IF NOT EXISTS public.android_product_idempotency (
    idempotency_key text PRIMARY KEY,
    draft_id text NOT NULL UNIQUE,
    server_product_id integer NOT NULL REFERENCES public.products(id) ON DELETE RESTRICT,
    actor_id text NOT NULL DEFAULT 'android_staff',
    device_id text NOT NULL DEFAULT 'android',
    source text NOT NULL DEFAULT 'ANDROID',
    created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE public.android_product_idempotency ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.android_product_idempotency FROM anon, authenticated;

CREATE OR REPLACE FUNCTION public.register_android_product(
    p_draft_id text,
    p_idempotency_key text,
    p_name text,
    p_category text DEFAULT 'General',
    p_sell_price numeric DEFAULT 0,
    p_buy_price numeric DEFAULT 0,
    p_initial_qty integer DEFAULT 0,
    p_description text DEFAULT NULL,
    p_image_url text DEFAULT NULL,
    p_actor_id text DEFAULT 'android_staff',
    p_device_id text DEFAULT 'android'
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_existing public.android_product_idempotency%ROWTYPE;
    v_duplicate_product public.products%ROWTYPE;
    v_clean_name text;
    v_clean_category text;
    v_new_product public.products%ROWTYPE;
BEGIN
    v_clean_name := btrim(COALESCE(p_name, ''));
    v_clean_category := COALESCE(NULLIF(btrim(COALESCE(p_category, '')), ''), 'General');

    IF v_clean_name = '' OR char_length(v_clean_name) < 2 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Product name must be at least 2 characters.'
        );
    END IF;

    IF p_sell_price IS NULL OR p_sell_price <= 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'A selling price greater than zero is required.'
        );
    END IF;

    IF p_buy_price IS NULL OR p_buy_price < 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Cost price cannot be negative.'
        );
    END IF;

    IF p_initial_qty IS NULL OR p_initial_qty < 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Initial stock quantity cannot be negative.'
        );
    END IF;

    IF p_draft_id IS NULL OR btrim(p_draft_id) = '' OR p_idempotency_key IS NULL OR btrim(p_idempotency_key) = '' THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Stable draft_id and idempotency_key are required.'
        );
    END IF;

    -- Idempotency check: safe retries return the original registered product ID.
    SELECT *
    INTO v_existing
    FROM public.android_product_idempotency
    WHERE idempotency_key = p_idempotency_key
       OR draft_id = p_draft_id
    LIMIT 1;

    IF FOUND THEN
        SELECT * INTO v_new_product FROM public.products WHERE id = v_existing.server_product_id;
        RETURN jsonb_build_object(
            'status', 'DUPLICATE_IGNORED',
            'server_product_id', v_existing.server_product_id,
            'name', COALESCE(v_new_product.name, v_clean_name),
            'qty', COALESCE(v_new_product.qty, p_initial_qty),
            'sell_price', COALESCE(v_new_product.sell_price, p_sell_price),
            'already_committed', true
        );
    END IF;

    -- Prevent accidental duplicate catalogue registration across devices by normalized name.
    SELECT *
    INTO v_duplicate_product
    FROM public.products
    WHERE lower(btrim(name)) = lower(v_clean_name)
    LIMIT 1;

    IF FOUND THEN
        INSERT INTO public.android_product_idempotency (
            idempotency_key,
            draft_id,
            server_product_id,
            actor_id,
            device_id,
            source
        )
        VALUES (
            p_idempotency_key,
            p_draft_id,
            v_duplicate_product.id,
            COALESCE(NULLIF(btrim(p_actor_id), ''), 'android_staff'),
            COALESCE(NULLIF(btrim(p_device_id), ''), 'android'),
            'ANDROID'
        )
        ON CONFLICT (idempotency_key) DO NOTHING;

        RETURN jsonb_build_object(
            'status', 'DUPLICATE_EXISTING',
            'server_product_id', v_duplicate_product.id,
            'name', v_duplicate_product.name,
            'qty', v_duplicate_product.qty,
            'sell_price', v_duplicate_product.sell_price,
            'already_committed', true
        );
    END IF;

    INSERT INTO public.products (
        name,
        qty,
        sell_price,
        buy_price,
        image_url,
        category,
        description,
        discount_price,
        is_featured
    )
    VALUES (
        v_clean_name,
        p_initial_qty,
        p_sell_price,
        p_buy_price,
        NULLIF(btrim(COALESCE(p_image_url, '')), ''),
        v_clean_category,
        NULLIF(btrim(COALESCE(p_description, '')), ''),
        0,
        false
    )
    RETURNING * INTO v_new_product;

    INSERT INTO public.android_product_idempotency (
        idempotency_key,
        draft_id,
        server_product_id,
        actor_id,
        device_id,
        source
    )
    VALUES (
        p_idempotency_key,
        p_draft_id,
        v_new_product.id,
        COALESCE(NULLIF(btrim(p_actor_id), ''), 'android_staff'),
        COALESCE(NULLIF(btrim(p_device_id), ''), 'android'),
        'ANDROID'
    );

    RETURN jsonb_build_object(
        'status', 'COMMITTED',
        'server_product_id', v_new_product.id,
        'name', v_new_product.name,
        'qty', v_new_product.qty,
        'sell_price', v_new_product.sell_price,
        'already_committed', false
    );
END;
$$;

REVOKE ALL ON FUNCTION public.register_android_product(text, text, text, text, numeric, numeric, integer, text, text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.register_android_product(text, text, text, text, numeric, numeric, integer, text, text, text, text) TO anon, authenticated, service_role;



-- -----------------------------------------------------------------------------
-- SECTION 6 of 17: supabase/migrations/20261114_record_recognition_correction_rpc.sql
-- -----------------------------------------------------------------------------
-- Emofah Agencies — Stage 1.6 Additive Recognition Learning & Correction RPC
-- Non-destructive: records confirmed human corrections, learned aliases, and optional visual hashes
-- without modifying existing product master semantics or storefront/desktop workflows.

CREATE TABLE IF NOT EXISTS public.android_recognition_corrections (
    id bigserial PRIMARY KEY,
    correction_id text NOT NULL UNIQUE,
    idempotency_key text NOT NULL UNIQUE,
    suggested_product_id integer NULL,
    suggested_product_name text NULL,
    confirmed_product_id integer NOT NULL REFERENCES public.products(id) ON DELETE CASCADE,
    match_status text NOT NULL DEFAULT 'UNIDENTIFIED',
    selection_kind text NOT NULL DEFAULT 'MANUAL_SEARCH_OVERRIDE',
    used_cloud_assist boolean NOT NULL DEFAULT false,
    learned_alias text NULL,
    ocr_snippet text NULL,
    visual_dhash_hex text NULL,
    reference_image_url text NULL,
    actor_id text NOT NULL DEFAULT 'android_staff',
    device_id text NOT NULL DEFAULT 'android',
    source text NOT NULL DEFAULT 'ANDROID',
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_android_recog_corr_confirmed_product
    ON public.android_recognition_corrections (confirmed_product_id, created_at DESC);

ALTER TABLE public.android_recognition_corrections ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.android_recognition_corrections FROM anon, authenticated;

CREATE OR REPLACE FUNCTION public.record_android_recognition_correction(
    p_correction_id text,
    p_idempotency_key text,
    p_suggested_product_id integer DEFAULT NULL,
    p_suggested_product_name text DEFAULT NULL,
    p_confirmed_product_id integer DEFAULT NULL,
    p_match_status text DEFAULT 'UNIDENTIFIED',
    p_selection_kind text DEFAULT 'MANUAL_SEARCH_OVERRIDE',
    p_used_cloud_assist boolean DEFAULT false,
    p_learned_alias text DEFAULT NULL,
    p_ocr_snippet text DEFAULT NULL,
    p_visual_dhash_hex text DEFAULT NULL,
    p_reference_image_url text DEFAULT NULL,
    p_actor_id text DEFAULT 'android_staff',
    p_device_id text DEFAULT 'android'
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_existing public.android_recognition_corrections%ROWTYPE;
    v_product public.products%ROWTYPE;
    v_inserted public.android_recognition_corrections%ROWTYPE;
    v_clean_alias text;
    v_clean_image_url text;
BEGIN
    IF p_correction_id IS NULL OR btrim(p_correction_id) = ''
       OR p_idempotency_key IS NULL OR btrim(p_idempotency_key) = '' THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Stable correction_id and idempotency_key are required.'
        );
    END IF;

    IF p_confirmed_product_id IS NULL OR p_confirmed_product_id <= 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'A valid canonical confirmed_product_id is required.'
        );
    END IF;

    SELECT *
    INTO v_existing
    FROM public.android_recognition_corrections
    WHERE idempotency_key = p_idempotency_key
       OR correction_id = p_correction_id
    LIMIT 1;

    IF FOUND THEN
        RETURN jsonb_build_object(
            'status', 'DUPLICATE_IGNORED',
            'server_correction_id', v_existing.id,
            'confirmed_product_id', v_existing.confirmed_product_id,
            'already_committed', true
        );
    END IF;

    SELECT *
    INTO v_product
    FROM public.products
    WHERE id = p_confirmed_product_id
    LIMIT 1;

    IF NOT FOUND THEN
        RETURN jsonb_build_object(
            'status', 'PRODUCT_NOT_FOUND',
            'message', format('Confirmed product #%s was not found in shared catalogue.', p_confirmed_product_id)
        );
    END IF;

    v_clean_alias := NULLIF(btrim(COALESCE(p_learned_alias, '')), '');
    v_clean_image_url := NULLIF(btrim(COALESCE(p_reference_image_url, '')), '');

    INSERT INTO public.android_recognition_corrections (
        correction_id,
        idempotency_key,
        suggested_product_id,
        suggested_product_name,
        confirmed_product_id,
        match_status,
        selection_kind,
        used_cloud_assist,
        learned_alias,
        ocr_snippet,
        visual_dhash_hex,
        reference_image_url,
        actor_id,
        device_id,
        source
    )
    VALUES (
        btrim(p_correction_id),
        btrim(p_idempotency_key),
        p_suggested_product_id,
        NULLIF(btrim(COALESCE(p_suggested_product_name, '')), ''),
        p_confirmed_product_id,
        COALESCE(NULLIF(btrim(COALESCE(p_match_status, '')), ''), 'UNIDENTIFIED'),
        COALESCE(NULLIF(btrim(COALESCE(p_selection_kind, '')), ''), 'MANUAL_SEARCH_OVERRIDE'),
        COALESCE(p_used_cloud_assist, false),
        v_clean_alias,
        NULLIF(left(btrim(COALESCE(p_ocr_snippet, '')), 180), ''),
        NULLIF(btrim(COALESCE(p_visual_dhash_hex, '')), ''),
        v_clean_image_url,
        COALESCE(NULLIF(btrim(COALESCE(p_actor_id, '')), ''), 'android_staff'),
        COALESCE(NULLIF(btrim(COALESCE(p_device_id, '')), ''), 'android'),
        'ANDROID'
    )
    RETURNING * INTO v_inserted;

    -- Non-destructive enrichment: only fill missing product image_url if none exists yet.
    IF v_clean_image_url IS NOT NULL AND (v_product.image_url IS NULL OR btrim(v_product.image_url) = '') THEN
        UPDATE public.products
        SET image_url = v_clean_image_url
        WHERE id = p_confirmed_product_id
          AND (image_url IS NULL OR btrim(image_url) = '');
    END IF;

    RETURN jsonb_build_object(
        'status', 'COMMITTED',
        'server_correction_id', v_inserted.id,
        'confirmed_product_id', v_inserted.confirmed_product_id,
        'already_committed', false
    );
END;
$$;

REVOKE ALL ON FUNCTION public.record_android_recognition_correction(
    text, text, integer, text, integer, text, text, boolean, text, text, text, text, text, text
) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.record_android_recognition_correction(
    text, text, integer, text, integer, text, text, boolean, text, text, text, text, text, text
) TO anon, authenticated, service_role;



-- -----------------------------------------------------------------------------
-- SECTION 7 of 17: supabase/migrations/20261114_receive_android_stock_rpc.sql
-- -----------------------------------------------------------------------------
-- Emofah Agencies — Stage 4.2 Additive Idempotent Stock Receiving RPC
-- Non-destructive: atomically increments public.products.qty, optionally updates buy_price,
-- and preserves receiving receipt records and invoice evidence for Desktop and Storefront compatibility.

CREATE TABLE IF NOT EXISTS public.android_stock_receipts (
    idempotency_key text PRIMARY KEY,
    client_receipt_id text NOT NULL UNIQUE,
    product_id integer NOT NULL REFERENCES public.products(id) ON DELETE RESTRICT,
    qty_received integer NOT NULL CHECK (qty_received > 0),
    unit_cost numeric NULL,
    supplier text NULL,
    invoice_ref text NULL,
    invoice_photo_url text NULL,
    actor_id text NOT NULL DEFAULT 'android_staff',
    device_id text NOT NULL DEFAULT 'android',
    server_qty_after integer NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_android_stock_receipts_product
    ON public.android_stock_receipts (product_id, created_at DESC);

ALTER TABLE public.android_stock_receipts ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON public.android_stock_receipts FROM anon, authenticated;

CREATE OR REPLACE FUNCTION public.receive_android_stock(
    p_product_id bigint,
    p_qty integer,
    p_client_receipt_id text,
    p_idempotency_key text,
    p_unit_cost numeric DEFAULT NULL,
    p_supplier text DEFAULT NULL,
    p_invoice_ref text DEFAULT NULL,
    p_invoice_photo_url text DEFAULT NULL,
    p_actor_id text DEFAULT 'android_staff',
    p_device_id text DEFAULT 'android',
    p_received_at timestamptz DEFAULT now(),
    p_update_buy_price boolean DEFAULT false
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_existing public.android_stock_receipts%ROWTYPE;
    v_current_qty integer;
    v_new_qty integer;
    v_clean_supplier text;
    v_clean_invoice text;
BEGIN
    IF p_product_id IS NULL OR p_product_id <= 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Valid product ID is required.'
        );
    END IF;

    IF p_qty IS NULL OR p_qty <= 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Received quantity must be at least 1 unit.'
        );
    END IF;

    IF p_idempotency_key IS NULL OR btrim(p_idempotency_key) = '' OR
       p_client_receipt_id IS NULL OR btrim(p_client_receipt_id) = '' THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Stable client_receipt_id and idempotency_key are required.'
        );
    END IF;

    v_clean_supplier := NULLIF(btrim(COALESCE(p_supplier, '')), '');
    v_clean_invoice := NULLIF(btrim(COALESCE(p_invoice_ref, '')), '');

    -- Idempotency check: retry of the exact same receipt returns existing server ACK without double increment.
    SELECT *
    INTO v_existing
    FROM public.android_stock_receipts
    WHERE idempotency_key = p_idempotency_key
       OR client_receipt_id = p_client_receipt_id
    LIMIT 1;

    IF FOUND THEN
        SELECT qty INTO v_current_qty FROM public.products WHERE id = v_existing.product_id;
        RETURN jsonb_build_object(
            'status', 'DUPLICATE_IGNORED',
            'server_qty', COALESCE(v_current_qty, v_existing.server_qty_after),
            'already_committed', true
        );
    END IF;

    -- Lock the canonical product row for atomic stock increment.
    SELECT qty
    INTO v_current_qty
    FROM public.products
    WHERE id = p_product_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object(
            'status', 'PRODUCT_NOT_FOUND',
            'message', format('Product #%s does not exist in catalogue.', p_product_id)
        );
    END IF;

    UPDATE public.products
    SET qty = COALESCE(qty, 0) + p_qty,
        buy_price = CASE
            WHEN p_update_buy_price AND p_unit_cost IS NOT NULL AND p_unit_cost > 0
            THEN p_unit_cost
            ELSE buy_price
        END
    WHERE id = p_product_id
    RETURNING qty INTO v_new_qty;

    INSERT INTO public.android_stock_receipts (
        idempotency_key,
        client_receipt_id,
        product_id,
        qty_received,
        unit_cost,
        supplier,
        invoice_ref,
        invoice_photo_url,
        actor_id,
        device_id,
        server_qty_after,
        received_at
    )
    VALUES (
        p_idempotency_key,
        p_client_receipt_id,
        p_product_id::integer,
        p_qty,
        p_unit_cost,
        v_clean_supplier,
        v_clean_invoice,
        p_invoice_photo_url,
        COALESCE(NULLIF(btrim(p_actor_id), ''), 'android_staff'),
        COALESCE(NULLIF(btrim(p_device_id), ''), 'android'),
        v_new_qty,
        COALESCE(p_received_at, now())
    );

    RETURN jsonb_build_object(
        'status', 'COMMITTED',
        'server_qty', v_new_qty,
        'already_committed', false
    );
END;
$$;

REVOKE ALL ON FUNCTION public.receive_android_stock(
    bigint, integer, text, text, numeric, text, text, text, text, text, timestamptz, boolean
) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.receive_android_stock(
    bigint, integer, text, text, numeric, text, text, text, text, text, timestamptz, boolean
) TO anon, authenticated, service_role;



-- -----------------------------------------------------------------------------
-- SECTION 8 of 17: supabase/migrations/20261114_adjust_android_stock_rpc.sql
-- -----------------------------------------------------------------------------
-- Emofah Agencies — Stage 4.3 Additive Idempotent Stock Adjustment RPC
-- Supports inventory discrepancy adjustments: damaged goods, expired products,
-- theft/shrinkage, internal store use, and physical count corrections.
-- Atomically adjusts public.products.qty, clamps non-negative, records audit records,
-- and preserves double-tap idempotency protection.

CREATE TABLE IF NOT EXISTS public.android_stock_adjustments (
    idempotency_key text PRIMARY KEY,
    client_adjustment_id text NOT NULL UNIQUE,
    product_id integer NOT NULL REFERENCES public.products(id) ON DELETE RESTRICT,
    delta_qty integer NOT NULL CHECK (delta_qty <> 0),
    reason_code text NOT NULL CHECK (reason_code IN (
        'DAMAGED',
        'EXPIRED',
        'THEFT_OR_SHRINKAGE',
        'INTERNAL_USE',
        'COUNT_CORRECTION_DECREASE',
        'COUNT_CORRECTION_INCREASE'
    )),
    notes text NOT NULL CHECK (btrim(notes) <> ''),
    actor_id text NOT NULL DEFAULT 'android_staff',
    actor_role text NOT NULL DEFAULT 'CASHIER',
    device_id text NOT NULL DEFAULT 'android',
    server_qty_before integer NOT NULL,
    server_qty_after integer NOT NULL,
    is_clamped boolean NOT NULL DEFAULT false,
    adjusted_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_android_stock_adjustments_product
    ON public.android_stock_adjustments (product_id, created_at DESC);

ALTER TABLE public.android_stock_adjustments ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON public.android_stock_adjustments FROM anon, authenticated;

CREATE OR REPLACE FUNCTION public.adjust_android_stock(
    p_product_id bigint,
    p_delta_qty integer,
    p_reason_code text,
    p_notes text,
    p_client_adjustment_id text,
    p_idempotency_key text,
    p_actor_id text DEFAULT 'android_staff',
    p_actor_role text DEFAULT 'CASHIER',
    p_device_id text DEFAULT 'android',
    p_adjusted_at timestamptz DEFAULT now()
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_existing public.android_stock_adjustments%ROWTYPE;
    v_current_qty integer;
    v_new_qty integer;
    v_clean_reason text;
    v_clean_notes text;
    v_clamped boolean := false;
BEGIN
    IF p_product_id IS NULL OR p_product_id <= 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Valid product ID is required.'
        );
    END IF;

    IF p_delta_qty IS NULL OR p_delta_qty = 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Quantity delta cannot be zero.'
        );
    END IF;

    v_clean_reason := upper(btrim(COALESCE(p_reason_code, '')));
    IF v_clean_reason NOT IN (
        'DAMAGED',
        'EXPIRED',
        'THEFT_OR_SHRINKAGE',
        'INTERNAL_USE',
        'COUNT_CORRECTION_DECREASE',
        'COUNT_CORRECTION_INCREASE'
    ) THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', format('Unsupported reason code: %s', p_reason_code)
        );
    END IF;

    v_clean_notes := btrim(COALESCE(p_notes, ''));
    IF length(v_clean_notes) < 3 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Detailed explanation notes (minimum 3 characters) are required for stock adjustments.'
        );
    END IF;

    IF p_idempotency_key IS NULL OR btrim(p_idempotency_key) = '' OR
       p_client_adjustment_id IS NULL OR btrim(p_client_adjustment_id) = '' THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Stable client_adjustment_id and idempotency_key are required.'
        );
    END IF;

    -- Idempotency check: return existing server ACK without double applying adjustment.
    SELECT *
    INTO v_existing
    FROM public.android_stock_adjustments
    WHERE idempotency_key = p_idempotency_key
       OR client_adjustment_id = p_client_adjustment_id
    LIMIT 1;

    IF FOUND THEN
        SELECT qty INTO v_current_qty FROM public.products WHERE id = v_existing.product_id;
        RETURN jsonb_build_object(
            'status', 'DUPLICATE_IGNORED',
            'server_qty', COALESCE(v_current_qty, v_existing.server_qty_after),
            'already_committed', true,
            'is_clamped', v_existing.is_clamped
        );
    END IF;

    -- Lock the canonical product row for atomic update.
    SELECT qty
    INTO v_current_qty
    FROM public.products
    WHERE id = p_product_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object(
            'status', 'PRODUCT_NOT_FOUND',
            'message', format('Product #%s does not exist in catalogue.', p_product_id)
        );
    END IF;

    v_current_qty := COALESCE(v_current_qty, 0);

    -- Calculate new stock with non-negative clamp
    IF (v_current_qty + p_delta_qty) < 0 THEN
        v_new_qty := 0;
        v_clamped := true;
    ELSE
        v_new_qty := v_current_qty + p_delta_qty;
    END IF;

    UPDATE public.products
    SET qty = v_new_qty
    WHERE id = p_product_id;

    INSERT INTO public.android_stock_adjustments (
        idempotency_key,
        client_adjustment_id,
        product_id,
        delta_qty,
        reason_code,
        notes,
        actor_id,
        actor_role,
        device_id,
        server_qty_before,
        server_qty_after,
        is_clamped,
        adjusted_at
    )
    VALUES (
        p_idempotency_key,
        p_client_adjustment_id,
        p_product_id::integer,
        p_delta_qty,
        v_clean_reason,
        v_clean_notes,
        COALESCE(NULLIF(btrim(p_actor_id), ''), 'android_staff'),
        COALESCE(NULLIF(btrim(p_actor_role), ''), 'CASHIER'),
        COALESCE(NULLIF(btrim(p_device_id), ''), 'android'),
        v_current_qty,
        v_new_qty,
        v_clamped,
        COALESCE(p_adjusted_at, now())
    );

    RETURN jsonb_build_object(
        'status', 'COMMITTED',
        'server_qty', v_new_qty,
        'previous_qty', v_current_qty,
        'delta_qty', p_delta_qty,
        'already_committed', false,
        'is_clamped', v_clamped
    );
END;
$$;

REVOKE ALL ON FUNCTION public.adjust_android_stock(
    bigint, integer, text, text, text, text, text, text, text, timestamptz
) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.adjust_android_stock(
    bigint, integer, text, text, text, text, text, text, text, timestamptz
) TO anon, authenticated, service_role;



-- -----------------------------------------------------------------------------
-- SECTION 9 of 17: supabase/migrations/20261114_physical_stock_count_rpc.sql
-- -----------------------------------------------------------------------------
-- Emofah Agencies: Physical Stock Count & Variance Reconciliation Migration (Stage 4.4)
-- Adds android_stock_count_sessions, android_stock_count_items, and submit_physical_stock_count RPC.

create table if not exists public.android_stock_count_sessions (
    id uuid primary key default gen_random_uuid(),
    session_name text not null,
    status text not null default 'IN_PROGRESS', -- IN_PROGRESS, COMPLETED, CANCELLED
    created_by text,
    created_at timestamptz not null default now(),
    completed_at timestamptz
);

create table if not exists public.android_stock_count_items (
    id uuid primary key default gen_random_uuid(),
    session_id uuid not null references public.android_stock_count_sessions(id) on delete cascade,
    product_id integer not null references public.products(id) on delete restrict,
    system_qty integer not null,
    counted_qty integer not null,
    variance integer not null, -- counted_qty - system_qty
    reason_code text, -- COUNT_CORRECTION, DAMAGE_DISCREPANCY, THEFT_DISCREPANCY, etc.
    note text,
    client_item_id text unique,
    created_at timestamptz not null default now()
);

create table if not exists public.android_stock_count_idempotency (
    idempotency_key text primary key,
    session_id uuid references public.android_stock_count_sessions(id) on delete cascade,
    created_at timestamptz not null default now()
);

alter table public.android_stock_count_sessions enable row level security;
alter table public.android_stock_count_items enable row level security;
alter table public.android_stock_count_idempotency enable row level security;

revoke all on public.android_stock_count_sessions from anon, authenticated;
revoke all on public.android_stock_count_items from anon, authenticated;
revoke all on public.android_stock_count_idempotency from anon, authenticated;

create or replace function public.submit_physical_stock_count(
    p_session_name text,
    p_created_by text,
    p_items jsonb, -- array of { product_id, system_qty, counted_qty, reason_code, note, client_item_id }
    p_idempotency_key text
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_session_id uuid;
    v_item jsonb;
    v_prod_id integer;
    v_sys_qty integer;
    v_counted_qty integer;
    v_variance integer;
    v_reason text;
    v_note text;
    v_client_item_id text;
    v_existing_session_id uuid;
    v_has_stock_movements boolean;
begin
    if p_items is null or jsonb_typeof(p_items) <> 'array' or jsonb_array_length(p_items) = 0 then
        return jsonb_build_object(
            'success', false,
            'status', 'INVALID_INPUT',
            'message', 'Stock count session must include at least one item.'
        );
    end if;

    -- Check idempotency
    if p_idempotency_key is not null and trim(p_idempotency_key) <> '' then
        select session_id into v_existing_session_id
        from public.android_stock_count_idempotency
        where idempotency_key = p_idempotency_key;

        if v_existing_session_id is not null then
            return jsonb_build_object(
                'success', true,
                'status', 'DUPLICATE_IGNORED',
                'already_committed', true,
                'session_id', v_existing_session_id,
                'message', 'Duplicate request replayed successfully (idempotent).'
            );
        end if;
    end if;

    v_has_stock_movements := (to_regclass('public.stock_movements') is not null);

    -- Create session
    insert into public.android_stock_count_sessions (session_name, status, created_by, completed_at)
    values (coalesce(nullif(btrim(p_session_name), ''), 'Physical Stock Count'), 'COMPLETED', coalesce(nullif(btrim(p_created_by), ''), 'android_staff'), now())
    returning id into v_session_id;

    -- Process items
    for v_item in select * from jsonb_array_elements(p_items)
    loop
        v_prod_id := (v_item->>'product_id')::integer;
        v_sys_qty := (v_item->>'system_qty')::integer;
        v_counted_qty := greatest(0, (v_item->>'counted_qty')::integer);
        v_variance := v_counted_qty - v_sys_qty;
        v_reason := v_item->>'reason_code';
        v_note := v_item->>'note';
        v_client_item_id := v_item->>'client_item_id';

        -- Insert count item
        insert into public.android_stock_count_items (
            session_id, product_id, system_qty, counted_qty, variance, reason_code, note, client_item_id
        ) values (
            v_session_id, v_prod_id, v_sys_qty, v_counted_qty, v_variance, v_reason, v_note, v_client_item_id
        )
        on conflict (client_item_id) do nothing;

        -- Reconcile product stock to physical count
        update public.products
        set qty = v_counted_qty
        where id = v_prod_id;

        -- Record stock movement ledger entry if shared stock_movements table is present and variance != 0
        if v_has_stock_movements and v_variance <> 0 then
            insert into public.stock_movements (
                product_id, delta, reason, reference_id, created_at
            ) values (
                v_prod_id,
                v_variance,
                coalesce(v_reason, 'PHYSICAL_COUNT_RECONCILIATION'),
                'SESSION:' || v_session_id::text,
                now()
            );
        end if;
    end loop;

    -- Record idempotency
    if p_idempotency_key is not null and trim(p_idempotency_key) <> '' then
        insert into public.android_stock_count_idempotency (idempotency_key, session_id)
        values (p_idempotency_key, v_session_id)
        on conflict (idempotency_key) do nothing;
    end if;

    return jsonb_build_object(
        'success', true,
        'status', 'COMMITTED',
        'already_committed', false,
        'session_id', v_session_id,
        'message', 'Physical stock count submitted and reconciled successfully.'
    );
end;
$$;

revoke all on function public.submit_physical_stock_count(text, text, jsonb, text) from public;
grant execute on function public.submit_physical_stock_count(text, text, jsonb, text) to anon, authenticated, service_role;



-- -----------------------------------------------------------------------------
-- SECTION 10 of 17: supabase/migrations/20261114_financial_events_table.sql
-- -----------------------------------------------------------------------------
-- Emofah Agencies: Financial Activity Foundation Migration (Stage 5.1)
-- Adds financial_events and expenses tables for M-Pesa monitoring, cash flows, and expense tracking.

create table if not exists public.android_financial_events (
    id uuid primary key default gen_random_uuid(),
    event_type text not null, -- CASH_SALE, M_PESA_RECEIVED, EXPENSE_OUTFLOW, SUPPLIER_PAYMENT, REVERSAL_ADJUSTMENT
    amount numeric not null,
    category text not null,
    reference_code text unique, -- e.g. M-Pesa code QG78... or sale UUID
    source text not null default 'ANDROID',
    actor_id text,
    device_id text,
    notes text,
    occurred_at timestamptz not null default now(),
    created_at timestamptz not null default now()
);

create table if not exists public.android_financial_idempotency (
    idempotency_key text primary key,
    event_id uuid references public.android_financial_events(id) on delete cascade,
    created_at timestamptz not null default now()
);



-- -----------------------------------------------------------------------------
-- SECTION 11 of 17: supabase/migrations/20261116_record_android_financial_event_rpc.sql
-- -----------------------------------------------------------------------------
-- Emofah Agencies — Idempotent Android Financial Event Synchronisation RPC
-- Synchronises parsed M-Pesa transaction events and manual operational money events
-- to the shared Supabase backend with strict duplicate protection.
-- Note: Android captures raw operational evidence, direction (INFLOW/OUTFLOW/TRANSFER),
-- parsed M-Pesa fields, and stamps classification_status = 'PENDING_DESKTOP_RULES' so
-- Desktop retains sole authority over final business accounting classifications.

ALTER TABLE public.android_financial_events
    ADD COLUMN IF NOT EXISTS client_event_id text,
    ADD COLUMN IF NOT EXISTS direction text NOT NULL DEFAULT 'INFLOW',
    ADD COLUMN IF NOT EXISTS channel text,
    ADD COLUMN IF NOT EXISTS currency text NOT NULL DEFAULT 'KES',
    ADD COLUMN IF NOT EXISTS counterparty text,
    ADD COLUMN IF NOT EXISTS counterparty_account text,
    ADD COLUMN IF NOT EXISTS transaction_cost numeric,
    ADD COLUMN IF NOT EXISTS new_balance numeric,
    ADD COLUMN IF NOT EXISTS occurred_text text,
    ADD COLUMN IF NOT EXISTS raw_evidence text,
    ADD COLUMN IF NOT EXISTS parsed_metadata jsonb,
    ADD COLUMN IF NOT EXISTS parse_status text DEFAULT 'VALID_PARSED',
    ADD COLUMN IF NOT EXISTS classification_status text NOT NULL DEFAULT 'PENDING_DESKTOP_RULES';

CREATE UNIQUE INDEX IF NOT EXISTS idx_android_financial_events_client_event_id
    ON public.android_financial_events (client_event_id)
    WHERE client_event_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_android_financial_events_occurred_at
    ON public.android_financial_events (occurred_at DESC);

ALTER TABLE public.android_financial_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.android_financial_idempotency ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON public.android_financial_events FROM anon, authenticated;
REVOKE ALL ON public.android_financial_idempotency FROM anon, authenticated;

CREATE OR REPLACE FUNCTION public.record_android_financial_event(
    p_client_event_id text,
    p_idempotency_key text,
    p_event_type text,
    p_direction text,
    p_amount numeric,
    p_currency text DEFAULT 'KES',
    p_reference_code text DEFAULT NULL,
    p_channel text DEFAULT NULL,
    p_counterparty text DEFAULT NULL,
    p_counterparty_account text DEFAULT NULL,
    p_transaction_cost numeric DEFAULT NULL,
    p_new_balance numeric DEFAULT NULL,
    p_occurred_text text DEFAULT NULL,
    p_raw_evidence text DEFAULT NULL,
    p_parsed_metadata jsonb DEFAULT NULL,
    p_parse_status text DEFAULT 'VALID_PARSED',
    p_source text DEFAULT 'ANDROID',
    p_actor_id text DEFAULT 'android_staff',
    p_device_id text DEFAULT 'android',
    p_notes text DEFAULT NULL,
    p_occurred_at timestamptz DEFAULT now()
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_existing_event_id uuid;
    v_new_event_id uuid;
    v_clean_client_id text;
    v_clean_idem_key text;
    v_clean_ref text;
    v_clean_direction text;
    v_clean_event_type text;
BEGIN
    v_clean_client_id := NULLIF(btrim(COALESCE(p_client_event_id, '')), '');
    v_clean_idem_key := NULLIF(btrim(COALESCE(p_idempotency_key, '')), '');
    v_clean_ref := NULLIF(upper(btrim(COALESCE(p_reference_code, ''))), '');
    v_clean_direction := upper(btrim(COALESCE(p_direction, '')));
    v_clean_event_type := upper(btrim(COALESCE(p_event_type, 'MPESA_EVENT')));

    IF v_clean_client_id IS NULL OR v_clean_idem_key IS NULL THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Stable client_event_id and idempotency_key are required.'
        );
    END IF;

    IF p_amount IS NULL OR p_amount <= 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Financial amount must be greater than zero.'
        );
    END IF;

    IF v_clean_direction NOT IN ('INFLOW', 'OUTFLOW', 'TRANSFER') THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', format('Unsupported direction: %s', p_direction)
        );
    END IF;

    -- 1. Idempotency check by idempotency_key
    SELECT event_id
    INTO v_existing_event_id
    FROM public.android_financial_idempotency
    WHERE idempotency_key = v_clean_idem_key
    LIMIT 1;

    IF v_existing_event_id IS NOT NULL THEN
        RETURN jsonb_build_object(
            'status', 'DUPLICATE_IGNORED',
            'server_event_id', v_existing_event_id,
            'already_committed', true
        );
    END IF;

    -- 2. Duplicate check by client_event_id or canonical transaction reference_code (e.g. M-Pesa code)
    SELECT id
    INTO v_existing_event_id
    FROM public.android_financial_events
    WHERE client_event_id = v_clean_client_id
       OR (v_clean_ref IS NOT NULL AND upper(reference_code) = v_clean_ref)
    LIMIT 1;

    IF v_existing_event_id IS NOT NULL THEN
        INSERT INTO public.android_financial_idempotency (idempotency_key, event_id)
        VALUES (v_clean_idem_key, v_existing_event_id)
        ON CONFLICT (idempotency_key) DO NOTHING;

        RETURN jsonb_build_object(
            'status', 'DUPLICATE_IGNORED',
            'server_event_id', v_existing_event_id,
            'already_committed', true
        );
    END IF;

    -- 3. Insert new financial event leaving final accounting classification to Desktop rules
    INSERT INTO public.android_financial_events (
        client_event_id,
        event_type,
        direction,
        amount,
        currency,
        category,
        classification_status,
        reference_code,
        channel,
        counterparty,
        counterparty_account,
        transaction_cost,
        new_balance,
        occurred_text,
        raw_evidence,
        parsed_metadata,
        parse_status,
        source,
        actor_id,
        device_id,
        notes,
        occurred_at
    )
    VALUES (
        v_clean_client_id,
        v_clean_event_type,
        v_clean_direction,
        p_amount,
        COALESCE(NULLIF(upper(btrim(p_currency)), ''), 'KES'),
        'PENDING_DESKTOP_RULES',
        'PENDING_DESKTOP_RULES',
        v_clean_ref,
        NULLIF(btrim(COALESCE(p_channel, '')), ''),
        NULLIF(btrim(COALESCE(p_counterparty, '')), ''),
        NULLIF(btrim(COALESCE(p_counterparty_account, '')), ''),
        p_transaction_cost,
        p_new_balance,
        NULLIF(btrim(COALESCE(p_occurred_text, '')), ''),
        NULLIF(btrim(COALESCE(p_raw_evidence, '')), ''),
        p_parsed_metadata,
        COALESCE(NULLIF(btrim(p_parse_status), ''), 'VALID_PARSED'),
        COALESCE(NULLIF(btrim(p_source), ''), 'ANDROID'),
        COALESCE(NULLIF(btrim(p_actor_id), ''), 'android_staff'),
        COALESCE(NULLIF(btrim(p_device_id), ''), 'android'),
        NULLIF(btrim(COALESCE(p_notes, '')), ''),
        COALESCE(p_occurred_at, now())
    )
    RETURNING id INTO v_new_event_id;

    INSERT INTO public.android_financial_idempotency (idempotency_key, event_id)
    VALUES (v_clean_idem_key, v_new_event_id)
    ON CONFLICT (idempotency_key) DO NOTHING;

    RETURN jsonb_build_object(
        'status', 'COMMITTED',
        'server_event_id', v_new_event_id,
        'classification_status', 'PENDING_DESKTOP_RULES',
        'already_committed', false
    );
END;
$$;

REVOKE ALL ON FUNCTION public.record_android_financial_event(
    text, text, text, text, numeric, text, text, text, text, text, numeric, numeric, text, text, jsonb, text, text, text, text, text, timestamptz
) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.record_android_financial_event(
    text, text, text, text, numeric, text, text, text, text, text, numeric, numeric, text, text, jsonb, text, text, text, text, text, timestamptz
) TO anon, authenticated, service_role;



-- -----------------------------------------------------------------------------
-- SECTION 12 of 17: supabase/migrations/20261116_record_android_purchase_rpc.sql
-- -----------------------------------------------------------------------------
-- Emofah Agencies — Section 2 (Part 2) Idempotent Confirmed Purchase Receipt RPC
-- Atomically records human-confirmed purchase receipts, distinguishes Resale Products vs
-- Consumables vs Equipment, registers new resale products with purchase cost/qty/selling price,
-- updates shared public.products inventory (and stock_movements when present), and prevents duplicates.

CREATE TABLE IF NOT EXISTS public.android_purchases (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    client_purchase_id text NOT NULL UNIQUE,
    idempotency_key text NOT NULL UNIQUE,
    supplier_name text NULL,
    invoice_ref text NULL,
    receipt_date_text text NULL,
    total_amount numeric NOT NULL DEFAULT 0,
    computed_items_total numeric NOT NULL DEFAULT 0,
    extraction_source text NOT NULL DEFAULT 'MANUAL_ENTRY',
    is_human_confirmed boolean NOT NULL DEFAULT false,
    actor_id text NOT NULL DEFAULT 'android_staff',
    device_id text NOT NULL DEFAULT 'android',
    recorded_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_android_purchases_recorded_at
    ON public.android_purchases (recorded_at DESC);

CREATE TABLE IF NOT EXISTS public.android_purchase_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    purchase_id uuid NOT NULL REFERENCES public.android_purchases(id) ON DELETE CASCADE,
    line_index integer NOT NULL,
    item_category text NOT NULL CHECK (item_category IN ('RESALE_PRODUCT', 'CONSUMABLE', 'EQUIPMENT')),
    raw_name text NOT NULL,
    product_id integer NULL REFERENCES public.products(id) ON DELETE RESTRICT,
    registered_new_product boolean NOT NULL DEFAULT false,
    qty integer NOT NULL CHECK (qty > 0),
    unit_cost numeric NOT NULL CHECK (unit_cost >= 0),
    line_total numeric NOT NULL CHECK (line_total >= 0),
    new_sell_price numeric NULL,
    new_product_category text NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_android_purchase_items_purchase
    ON public.android_purchase_items (purchase_id, line_index);

ALTER TABLE public.android_purchases ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.android_purchase_items ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON public.android_purchases FROM anon, authenticated;
REVOKE ALL ON public.android_purchase_items FROM anon, authenticated;

CREATE OR REPLACE FUNCTION public.record_android_purchase(
    p_client_purchase_id text,
    p_idempotency_key text,
    p_supplier_name text DEFAULT NULL,
    p_invoice_ref text DEFAULT NULL,
    p_receipt_date_text text DEFAULT NULL,
    p_total_amount numeric DEFAULT 0,
    p_extraction_source text DEFAULT 'MANUAL_ENTRY',
    p_is_human_confirmed boolean DEFAULT false,
    p_items jsonb DEFAULT '[]'::jsonb,
    p_actor_id text DEFAULT 'android_staff',
    p_device_id text DEFAULT 'android',
    p_recorded_at timestamptz DEFAULT now(),
    p_record_financial_outflow boolean DEFAULT true
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_existing public.android_purchases%ROWTYPE;
    v_purchase_id uuid;
    v_clean_client_id text;
    v_clean_idem_key text;
    v_clean_supplier text;
    v_clean_invoice text;
    v_clean_date text;
    v_item jsonb;
    v_idx integer := 0;
    v_raw_name text;
    v_cat text;
    v_qty integer;
    v_unit_cost numeric;
    v_line_total numeric;
    v_matched_id bigint;
    v_register_new boolean;
    v_new_sell_price numeric;
    v_new_prod_cat text;
    v_local_temp_id bigint;
    v_prod_row public.products%ROWTYPE;
    v_resolved_pid integer;
    v_server_qty_after integer;
    v_computed_total numeric := 0;
    v_effective_total numeric := 0;
    v_resolved_products jsonb := '[]'::jsonb;
    v_fin_client_id text;
    v_fin_idem_key text;
    v_existing_fin uuid;
BEGIN
    v_clean_client_id := NULLIF(btrim(COALESCE(p_client_purchase_id, '')), '');
    v_clean_idem_key := NULLIF(btrim(COALESCE(p_idempotency_key, '')), '');

    IF v_clean_client_id IS NULL OR v_clean_idem_key IS NULL THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Stable client_purchase_id and idempotency_key are required.'
        );
    END IF;

    IF COALESCE(p_is_human_confirmed, false) = false THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Purchase receipt must be explicitly reviewed and confirmed before submission.'
        );
    END IF;

    IF p_items IS NULL OR jsonb_typeof(p_items) <> 'array' OR jsonb_array_length(p_items) = 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'At least one purchase item is required.'
        );
    END IF;

    -- Idempotency check: safe retry of same purchase returns canonical server ACK & resolved products.
    SELECT *
    INTO v_existing
    FROM public.android_purchases
    WHERE idempotency_key = v_clean_idem_key
       OR client_purchase_id = v_clean_client_id
    LIMIT 1;

    IF FOUND THEN
        SELECT COALESCE(
            jsonb_agg(
                jsonb_build_object(
                    'line_index', pi.line_index,
                    'item_category', pi.item_category,
                    'product_id', pi.product_id,
                    'product_name', COALESCE(p.name, pi.raw_name),
                    'category', COALESCE(p.category, pi.new_product_category, 'General'),
                    'sell_price', COALESCE(p.sell_price, pi.new_sell_price, 0),
                    'buy_price', COALESCE(p.buy_price, pi.unit_cost),
                    'server_qty_after', p.qty,
                    'registered_new_product', pi.registered_new_product
                )
                ORDER BY pi.line_index
            ),
            '[]'::jsonb
        )
        INTO v_resolved_products
        FROM public.android_purchase_items pi
        LEFT JOIN public.products p ON p.id = pi.product_id
        WHERE pi.purchase_id = v_existing.id;

        RETURN jsonb_build_object(
            'status', 'DUPLICATE_IGNORED',
            'server_purchase_id', v_existing.id,
            'total_amount', v_existing.total_amount,
            'already_committed', true,
            'resolved_products', v_resolved_products
        );
    END IF;

    v_clean_supplier := NULLIF(btrim(COALESCE(p_supplier_name, '')), '');
    v_clean_invoice := NULLIF(btrim(COALESCE(p_invoice_ref, '')), '');
    v_clean_date := NULLIF(btrim(COALESCE(p_receipt_date_text, '')), '');

    INSERT INTO public.android_purchases (
        client_purchase_id,
        idempotency_key,
        supplier_name,
        invoice_ref,
        receipt_date_text,
        total_amount,
        computed_items_total,
        extraction_source,
        is_human_confirmed,
        actor_id,
        device_id,
        recorded_at
    )
    VALUES (
        v_clean_client_id,
        v_clean_idem_key,
        v_clean_supplier,
        v_clean_invoice,
        v_clean_date,
        GREATEST(COALESCE(p_total_amount, 0), 0),
        0,
        COALESCE(NULLIF(btrim(COALESCE(p_extraction_source, '')), ''), 'MANUAL_ENTRY'),
        true,
        COALESCE(NULLIF(btrim(COALESCE(p_actor_id, '')), ''), 'android_staff'),
        COALESCE(NULLIF(btrim(COALESCE(p_device_id, '')), ''), 'android'),
        COALESCE(p_recorded_at, now())
    )
    RETURNING id INTO v_purchase_id;

    FOR v_item IN SELECT * FROM jsonb_array_elements(p_items)
    LOOP
        v_idx := v_idx + 1;
        v_raw_name := btrim(COALESCE(v_item->>'raw_name', ''));
        v_cat := upper(btrim(COALESCE(v_item->>'item_category', 'RESALE_PRODUCT')));
        v_qty := COALESCE((v_item->>'qty')::integer, 0);
        v_unit_cost := COALESCE((v_item->>'unit_cost')::numeric, 0);
        v_line_total := COALESCE((v_item->>'line_total')::numeric, v_qty * v_unit_cost);
        v_register_new := COALESCE((v_item->>'register_as_new_product')::boolean, false);
        v_new_sell_price := CASE
            WHEN v_item ? 'new_product_sell_price' AND NULLIF(btrim(COALESCE(v_item->>'new_product_sell_price', '')), '') IS NOT NULL
            THEN (v_item->>'new_product_sell_price')::numeric
            ELSE NULL
        END;
        v_new_prod_cat := COALESCE(NULLIF(btrim(COALESCE(v_item->>'new_product_category', '')), ''), 'General');
        v_matched_id := CASE
            WHEN v_item ? 'matched_product_id' AND NULLIF(btrim(COALESCE(v_item->>'matched_product_id', '')), '') IS NOT NULL
            THEN (v_item->>'matched_product_id')::bigint
            ELSE NULL
        END;
        v_local_temp_id := CASE
            WHEN v_item ? 'local_temp_product_id' AND NULLIF(btrim(COALESCE(v_item->>'local_temp_product_id', '')), '') IS NOT NULL
            THEN (v_item->>'local_temp_product_id')::bigint
            ELSE NULL
        END;

        IF char_length(v_raw_name) < 2 THEN
            RAISE EXCEPTION 'Line %: Item description must be at least 2 characters.', v_idx;
        END IF;

        IF v_cat NOT IN ('RESALE_PRODUCT', 'CONSUMABLE', 'EQUIPMENT') THEN
            RAISE EXCEPTION 'Line %: Unsupported item_category %.', v_idx, v_cat;
        END IF;

        IF v_qty <= 0 OR v_unit_cost < 0 THEN
            RAISE EXCEPTION 'Line %: Quantity must be >= 1 and unit_cost >= 0.', v_idx;
        END IF;

        IF v_line_total <= 0 AND v_unit_cost > 0 THEN
            v_line_total := v_qty * v_unit_cost;
        END IF;
        v_computed_total := v_computed_total + GREATEST(v_line_total, 0);

        v_resolved_pid := NULL;
        v_server_qty_after := NULL;

        IF v_cat = 'RESALE_PRODUCT' THEN
            IF v_register_new THEN
                IF v_new_sell_price IS NULL OR v_new_sell_price <= 0 THEN
                    RAISE EXCEPTION 'Line % (%): Positive selling price required for new resale product.', v_idx, v_raw_name;
                END IF;

                -- Duplicate name guard across shared catalogue
                SELECT *
                INTO v_prod_row
                FROM public.products
                WHERE lower(btrim(name)) = lower(v_raw_name)
                LIMIT 1
                FOR UPDATE;

                IF FOUND THEN
                    UPDATE public.products
                    SET qty = COALESCE(qty, 0) + v_qty,
                        buy_price = CASE WHEN v_unit_cost > 0 THEN v_unit_cost ELSE buy_price END
                    WHERE id = v_prod_row.id
                    RETURNING * INTO v_prod_row;
                ELSE
                    INSERT INTO public.products (
                        name,
                        qty,
                        sell_price,
                        buy_price,
                        category,
                        discount_price,
                        is_featured
                    )
                    VALUES (
                        v_raw_name,
                        v_qty,
                        v_new_sell_price,
                        v_unit_cost,
                        v_new_prod_cat,
                        0,
                        false
                    )
                    RETURNING * INTO v_prod_row;
                END IF;

                v_resolved_pid := v_prod_row.id;
                v_server_qty_after := v_prod_row.qty;
            ELSE
                IF v_matched_id IS NULL OR v_matched_id <= 0 THEN
                    RAISE EXCEPTION 'Line % (%): Must match an existing product or toggle new product registration.', v_idx, v_raw_name;
                END IF;

                SELECT *
                INTO v_prod_row
                FROM public.products
                WHERE id = v_matched_id
                FOR UPDATE;

                IF NOT FOUND THEN
                    RAISE EXCEPTION 'PRODUCT_NOT_FOUND:%', v_matched_id;
                END IF;

                UPDATE public.products
                SET qty = COALESCE(qty, 0) + v_qty,
                    buy_price = CASE WHEN v_unit_cost > 0 THEN v_unit_cost ELSE buy_price END
                WHERE id = v_prod_row.id
                RETURNING * INTO v_prod_row;

                v_resolved_pid := v_prod_row.id;
                v_server_qty_after := v_prod_row.qty;
            END IF;

            -- Record ledger movement in public.stock_movements if table exists
            IF to_regclass('public.stock_movements') IS NOT NULL THEN
                BEGIN
                    INSERT INTO public.stock_movements (
                        product_id,
                        qty_change,
                        movement_type,
                        reference,
                        notes,
                        created_at
                    )
                    VALUES (
                        v_resolved_pid,
                        v_qty,
                        'PURCHASE_RECEIPT',
                        COALESCE(v_clean_invoice, v_clean_client_id),
                        COALESCE(v_clean_supplier, 'Supplier purchase'),
                        COALESCE(p_recorded_at, now())
                    );
                EXCEPTION WHEN OTHERS THEN
                    NULL;
                END;
            END IF;
        END IF;

        INSERT INTO public.android_purchase_items (
            purchase_id,
            line_index,
            item_category,
            raw_name,
            product_id,
            registered_new_product,
            qty,
            unit_cost,
            line_total,
            new_sell_price,
            new_product_category
        )
        VALUES (
            v_purchase_id,
            v_idx,
            v_cat,
            v_raw_name,
            v_resolved_pid,
            v_register_new,
            v_qty,
            v_unit_cost,
            GREATEST(v_line_total, 0),
            v_new_sell_price,
            CASE WHEN v_register_new THEN v_new_prod_cat ELSE NULL END
        );

        v_resolved_products := v_resolved_products || jsonb_build_array(
            jsonb_build_object(
                'line_index', v_idx,
                'item_category', v_cat,
                'local_temp_product_id', v_local_temp_id,
                'product_id', v_resolved_pid,
                'product_name', COALESCE(v_prod_row.name, v_raw_name),
                'category', COALESCE(v_prod_row.category, v_new_prod_cat, 'General'),
                'sell_price', COALESCE(v_prod_row.sell_price, v_new_sell_price, 0),
                'buy_price', COALESCE(v_prod_row.buy_price, v_unit_cost),
                'qty', v_qty,
                'server_qty_after', v_server_qty_after,
                'registered_new_product', v_register_new
            )
        );
    END LOOP;

    v_effective_total := CASE
        WHEN COALESCE(p_total_amount, 0) > 0 THEN p_total_amount
        ELSE v_computed_total
    END;

    UPDATE public.android_purchases
    SET total_amount = v_effective_total,
        computed_items_total = v_computed_total
    WHERE id = v_purchase_id;

    -- Optional financial outflow record (leaving classification_status = PENDING_DESKTOP_RULES for Desktop)
    IF COALESCE(p_record_financial_outflow, true) AND v_effective_total > 0
       AND to_regclass('public.android_financial_events') IS NOT NULL THEN
        v_fin_client_id := 'fin-purch-' || v_clean_client_id;
        v_fin_idem_key := 'idem-purch-fin-' || v_clean_idem_key;

        SELECT id INTO v_existing_fin
        FROM public.android_financial_events
        WHERE client_event_id = v_fin_client_id
        LIMIT 1;

        IF v_existing_fin IS NULL THEN
            INSERT INTO public.android_financial_events (
                client_event_id,
                event_type,
                direction,
                amount,
                currency,
                category,
                classification_status,
                reference_code,
                channel,
                counterparty,
                raw_evidence,
                parse_status,
                source,
                actor_id,
                device_id,
                notes,
                occurred_at
            )
            VALUES (
                v_fin_client_id,
                'PURCHASE_RECEIPT_OUTFLOW',
                'OUTFLOW',
                v_effective_total,
                'KES',
                'PENDING_DESKTOP_RULES',
                'PENDING_DESKTOP_RULES',
                v_clean_invoice,
                'PURCHASE_RECEIPT',
                v_clean_supplier,
                format('Receipt %s · %s (%s item(s))', COALESCE(v_clean_invoice, v_clean_client_id), COALESCE(v_clean_supplier, 'Supplier'), v_idx),
                'VALID_PARSED',
                'ANDROID_RECEIPT',
                COALESCE(NULLIF(btrim(COALESCE(p_actor_id, '')), ''), 'android_staff'),
                COALESCE(NULLIF(btrim(COALESCE(p_device_id, '')), ''), 'android'),
                format('Purchase receipt %s', COALESCE(v_clean_invoice, v_clean_client_id)),
                COALESCE(p_recorded_at, now())
            );
        END IF;
    END IF;

    RETURN jsonb_build_object(
        'status', 'COMMITTED',
        'server_purchase_id', v_purchase_id,
        'total_amount', v_effective_total,
        'already_committed', false,
        'resolved_products', v_resolved_products
    );
EXCEPTION WHEN OTHERS THEN
    IF SQLERRM LIKE 'PRODUCT_NOT_FOUND:%' THEN
        RETURN jsonb_build_object(
            'status', 'PRODUCT_NOT_FOUND',
            'message', format('Referenced product #%s no longer exists in catalogue.', split_part(SQLERRM, ':', 2))
        );
    END IF;
    RETURN jsonb_build_object(
        'status', 'INVALID_INPUT',
        'message', SQLERRM
    );
END;
$$;

REVOKE ALL ON FUNCTION public.record_android_purchase(
    text, text, text, text, text, numeric, text, boolean, jsonb, text, text, timestamptz, boolean
) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.record_android_purchase(
    text, text, text, text, text, numeric, text, boolean, jsonb, text, text, timestamptz, boolean
) TO anon, authenticated, service_role;



-- -----------------------------------------------------------------------------
-- SECTION 13 of 17: supabase/migrations/20261116_record_android_service_rpc.sql
-- -----------------------------------------------------------------------------
-- Emofah Agencies — Section 4 Idempotent Android Service Operations RPC
-- Records cyber / bureau service operations (Printing, Photocopying, Scanning, Internet Browsing,
-- E-Government Assistance), logs consumables used, atomically deducts linked shared stock items
-- when specified, records raw SERVICE_REVENUE financial events (classification left to Desktop),
-- and prevents duplicate submissions via idempotency_key / client_service_id.

CREATE TABLE IF NOT EXISTS public.android_service_operations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    client_service_id text NOT NULL UNIQUE,
    idempotency_key text NOT NULL UNIQUE,
    service_type text NOT NULL CHECK (
        service_type IN ('PRINTING', 'PHOTOCOPYING', 'SCANNING', 'INTERNET_BROWSING', 'EGOV_ASSISTANCE')
    ),
    service_label text NOT NULL,
    quantity_or_duration integer NOT NULL CHECK (quantity_or_duration > 0),
    unit_label text NOT NULL DEFAULT 'units',
    unit_price numeric NOT NULL CHECK (unit_price >= 0),
    total_revenue numeric NOT NULL CHECK (total_revenue >= 0),
    payment_method text NOT NULL DEFAULT 'CASH',
    customer_note text NULL,
    consumables_summary text NULL,
    actor_id text NOT NULL DEFAULT 'android_staff',
    device_id text NOT NULL DEFAULT 'android',
    performed_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_android_service_operations_performed_at
    ON public.android_service_operations (performed_at DESC);

CREATE TABLE IF NOT EXISTS public.android_service_consumables (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id uuid NOT NULL REFERENCES public.android_service_operations(id) ON DELETE CASCADE,
    consumable_name text NOT NULL,
    usage_qty numeric NOT NULL DEFAULT 1 CHECK (usage_qty >= 0),
    usage_unit text NOT NULL DEFAULT 'units',
    linked_product_id integer NULL REFERENCES public.products(id) ON DELETE RESTRICT,
    stock_deduct_qty integer NOT NULL DEFAULT 0 CHECK (stock_deduct_qty >= 0),
    server_qty_after integer NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_android_service_consumables_service
    ON public.android_service_consumables (service_id);

ALTER TABLE public.android_service_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.android_service_consumables ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON public.android_service_operations FROM anon, authenticated;
REVOKE ALL ON public.android_service_consumables FROM anon, authenticated;

CREATE OR REPLACE FUNCTION public.record_android_service_operation(
    p_client_service_id text,
    p_idempotency_key text,
    p_service_type text,
    p_service_label text,
    p_quantity_or_duration integer,
    p_unit_label text DEFAULT 'units',
    p_unit_price numeric DEFAULT 0,
    p_total_revenue numeric DEFAULT 0,
    p_payment_method text DEFAULT 'CASH',
    p_customer_note text DEFAULT NULL,
    p_consumables jsonb DEFAULT '[]'::jsonb,
    p_actor_id text DEFAULT 'android_staff',
    p_device_id text DEFAULT 'android',
    p_performed_at timestamptz DEFAULT now()
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_existing public.android_service_operations%ROWTYPE;
    v_service_id uuid;
    v_clean_client_id text;
    v_clean_idem_key text;
    v_clean_type text;
    v_clean_label text;
    v_clean_unit text;
    v_clean_note text;
    v_clean_payment text;
    v_effective_revenue numeric;
    v_cons jsonb;
    v_cons_name text;
    v_usage_qty numeric;
    v_usage_unit text;
    v_linked_pid bigint;
    v_deduct_qty integer;
    v_prod_row public.products%ROWTYPE;
    v_new_qty integer;
    v_updated_products jsonb := '[]'::jsonb;
    v_summary_parts text[] := ARRAY[]::text[];
    v_summary_text text;
    v_fin_client_id text;
    v_existing_fin uuid;
BEGIN
    v_clean_client_id := NULLIF(btrim(COALESCE(p_client_service_id, '')), '');
    v_clean_idem_key := NULLIF(btrim(COALESCE(p_idempotency_key, '')), '');
    v_clean_type := upper(btrim(COALESCE(p_service_type, '')));
    v_clean_label := COALESCE(NULLIF(btrim(COALESCE(p_service_label, '')), ''), v_clean_type);
    v_clean_unit := COALESCE(NULLIF(btrim(COALESCE(p_unit_label, '')), ''), 'units');
    v_clean_note := NULLIF(btrim(COALESCE(p_customer_note, '')), '');
    v_clean_payment := upper(COALESCE(NULLIF(btrim(COALESCE(p_payment_method, '')), ''), 'CASH'));

    IF v_clean_client_id IS NULL OR v_clean_idem_key IS NULL THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Stable client_service_id and idempotency_key are required.'
        );
    END IF;

    IF v_clean_type NOT IN ('PRINTING', 'PHOTOCOPYING', 'SCANNING', 'INTERNET_BROWSING', 'EGOV_ASSISTANCE') THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', format('Unsupported service type: %s', p_service_type)
        );
    END IF;

    IF p_quantity_or_duration IS NULL OR p_quantity_or_duration <= 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Quantity or duration must be at least 1.'
        );
    END IF;

    IF COALESCE(p_unit_price, 0) < 0 OR COALESCE(p_total_revenue, 0) < 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Price and revenue cannot be negative.'
        );
    END IF;

    v_effective_revenue := CASE
        WHEN COALESCE(p_total_revenue, 0) > 0 THEN p_total_revenue
        ELSE p_quantity_or_duration * COALESCE(p_unit_price, 0)
    END;

    -- Idempotency check
    SELECT *
    INTO v_existing
    FROM public.android_service_operations
    WHERE idempotency_key = v_clean_idem_key
       OR client_service_id = v_clean_client_id
    LIMIT 1;

    IF FOUND THEN
        SELECT COALESCE(
            jsonb_agg(
                jsonb_build_object(
                    'product_id', sc.linked_product_id,
                    'qty_deducted', sc.stock_deduct_qty,
                    'server_qty_after', COALESCE(p.qty, sc.server_qty_after)
                )
            ),
            '[]'::jsonb
        )
        INTO v_updated_products
        FROM public.android_service_consumables sc
        LEFT JOIN public.products p ON p.id = sc.linked_product_id
        WHERE sc.service_id = v_existing.id
          AND sc.linked_product_id IS NOT NULL
          AND sc.stock_deduct_qty > 0;

        RETURN jsonb_build_object(
            'status', 'DUPLICATE_IGNORED',
            'server_service_id', v_existing.id,
            'total_revenue', v_existing.total_revenue,
            'already_committed', true,
            'updated_products', v_updated_products
        );
    END IF;

    INSERT INTO public.android_service_operations (
        client_service_id,
        idempotency_key,
        service_type,
        service_label,
        quantity_or_duration,
        unit_label,
        unit_price,
        total_revenue,
        payment_method,
        customer_note,
        actor_id,
        device_id,
        performed_at
    )
    VALUES (
        v_clean_client_id,
        v_clean_idem_key,
        v_clean_type,
        v_clean_label,
        p_quantity_or_duration,
        v_clean_unit,
        COALESCE(p_unit_price, 0),
        v_effective_revenue,
        v_clean_payment,
        v_clean_note,
        COALESCE(NULLIF(btrim(COALESCE(p_actor_id, '')), ''), 'android_staff'),
        COALESCE(NULLIF(btrim(COALESCE(p_device_id, '')), ''), 'android'),
        COALESCE(p_performed_at, now())
    )
    RETURNING id INTO v_service_id;

    IF p_consumables IS NOT NULL AND jsonb_typeof(p_consumables) = 'array' THEN
        FOR v_cons IN SELECT * FROM jsonb_array_elements(p_consumables)
        LOOP
            v_cons_name := btrim(COALESCE(v_cons->>'consumable_name', ''));
            IF char_length(v_cons_name) < 2 THEN
                CONTINUE;
            END IF;
            v_usage_qty := GREATEST(COALESCE((v_cons->>'usage_qty')::numeric, 0), 0);
            v_usage_unit := COALESCE(NULLIF(btrim(COALESCE(v_cons->>'usage_unit', '')), ''), 'units');
            v_deduct_qty := GREATEST(COALESCE((v_cons->>'stock_deduct_qty')::integer, 0), 0);
            v_linked_pid := CASE
                WHEN v_cons ? 'linked_product_id' AND NULLIF(btrim(COALESCE(v_cons->>'linked_product_id', '')), '') IS NOT NULL
                THEN (v_cons->>'linked_product_id')::bigint
                ELSE NULL
            END;

            v_new_qty := NULL;
            IF v_linked_pid IS NOT NULL AND v_linked_pid > 0 AND v_deduct_qty > 0 THEN
                SELECT *
                INTO v_prod_row
                FROM public.products
                WHERE id = v_linked_pid
                FOR UPDATE;

                IF NOT FOUND THEN
                    RAISE EXCEPTION 'PRODUCT_NOT_FOUND:%', v_linked_pid;
                END IF;

                UPDATE public.products
                SET qty = GREATEST(COALESCE(qty, 0) - v_deduct_qty, 0)
                WHERE id = v_linked_pid
                RETURNING qty INTO v_new_qty;

                IF to_regclass('public.stock_movements') IS NOT NULL THEN
                    BEGIN
                        INSERT INTO public.stock_movements (
                            product_id,
                            qty_change,
                            movement_type,
                            reference,
                            notes,
                            created_at
                        )
                        VALUES (
                            v_linked_pid,
                            -v_deduct_qty,
                            'SERVICE_CONSUMABLE_USE',
                            v_clean_client_id,
                            format('%s consumable: %s', v_clean_label, v_cons_name),
                            COALESCE(p_performed_at, now())
                        );
                    EXCEPTION WHEN OTHERS THEN
                        NULL;
                    END;
                END IF;

                v_updated_products := v_updated_products || jsonb_build_array(
                    jsonb_build_object(
                        'product_id', v_linked_pid,
                        'qty_deducted', v_deduct_qty,
                        'server_qty_after', v_new_qty
                    )
                );
            END IF;

            INSERT INTO public.android_service_consumables (
                service_id,
                consumable_name,
                usage_qty,
                usage_unit,
                linked_product_id,
                stock_deduct_qty,
                server_qty_after
            )
            VALUES (
                v_service_id,
                v_cons_name,
                v_usage_qty,
                v_usage_unit,
                CASE WHEN v_linked_pid IS NOT NULL AND v_linked_pid > 0 THEN v_linked_pid::integer ELSE NULL END,
                v_deduct_qty,
                v_new_qty
            );

            v_summary_parts := array_append(
                v_summary_parts,
                format('%s (%s %s)', v_cons_name, v_usage_qty, v_usage_unit)
            );
        END LOOP;
    END IF;

    v_summary_text := NULLIF(array_to_string(v_summary_parts, ', '), '');
    IF v_summary_text IS NOT NULL THEN
        UPDATE public.android_service_operations
        SET consumables_summary = v_summary_text
        WHERE id = v_service_id;
    END IF;

    -- Record raw INFLOW service revenue event (Desktop manages accounting classification rules)
    IF v_effective_revenue > 0 AND to_regclass('public.android_financial_events') IS NOT NULL THEN
        v_fin_client_id := 'fin-svc-' || v_clean_client_id;
        SELECT id INTO v_existing_fin
        FROM public.android_financial_events
        WHERE client_event_id = v_fin_client_id
        LIMIT 1;

        IF v_existing_fin IS NULL THEN
            INSERT INTO public.android_financial_events (
                client_event_id,
                event_type,
                direction,
                amount,
                currency,
                category,
                classification_status,
                reference_code,
                channel,
                counterparty,
                raw_evidence,
                parse_status,
                source,
                actor_id,
                device_id,
                notes,
                occurred_at
            )
            VALUES (
                v_fin_client_id,
                'SERVICE_REVENUE',
                'INFLOW',
                v_effective_revenue,
                'KES',
                'PENDING_DESKTOP_RULES',
                'PENDING_DESKTOP_RULES',
                NULL,
                v_clean_type,
                v_clean_label,
                format('%s: %s %s · %s', v_clean_label, p_quantity_or_duration, v_clean_unit, COALESCE(v_summary_text, 'No tracked consumables')),
                'VALID_PARSED',
                'ANDROID_SERVICE',
                COALESCE(NULLIF(btrim(COALESCE(p_actor_id, '')), ''), 'android_staff'),
                COALESCE(NULLIF(btrim(COALESCE(p_device_id, '')), ''), 'android'),
                v_clean_note,
                COALESCE(p_performed_at, now())
            );
        END IF;
    END IF;

    RETURN jsonb_build_object(
        'status', 'COMMITTED',
        'server_service_id', v_service_id,
        'total_revenue', v_effective_revenue,
        'already_committed', false,
        'updated_products', v_updated_products
    );
EXCEPTION WHEN OTHERS THEN
    IF SQLERRM LIKE 'PRODUCT_NOT_FOUND:%' THEN
        RETURN jsonb_build_object(
            'status', 'PRODUCT_NOT_FOUND',
            'message', format('Consumable catalogue product #%s no longer exists.', split_part(SQLERRM, ':', 2))
        );
    END IF;
    RETURN jsonb_build_object(
        'status', 'INVALID_INPUT',
        'message', SQLERRM
    );
END;
$$;

REVOKE ALL ON FUNCTION public.record_android_service_operation(
    text, text, text, text, integer, text, numeric, numeric, text, text, jsonb, text, text, timestamptz
) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.record_android_service_operation(
    text, text, text, text, integer, text, numeric, numeric, text, text, jsonb, text, text, timestamptz
) TO anon, authenticated, service_role;



-- -----------------------------------------------------------------------------
-- SECTION 14 of 17: supabase/migrations/20261116_update_android_order_status_rpc.sql
-- -----------------------------------------------------------------------------
-- Emofah Agencies — Stage 6.2 Idempotent Android Order Status Management RPC
-- Transitions storefront orders in shared `public.orders` cleanly through:
--   pending -> confirmed -> ready -> fulfilled (or cancelled with reason)
-- Validates allowed transitions under row lock, prevents invalid double/terminal mutations,
-- records an audit trail in `public.android_order_status_events`, and ensures idempotency.

CREATE TABLE IF NOT EXISTS public.android_order_status_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    client_update_id text NOT NULL UNIQUE,
    idempotency_key text NOT NULL UNIQUE,
    order_id uuid NOT NULL REFERENCES public.orders(id) ON DELETE CASCADE,
    previous_status text NOT NULL,
    new_status text NOT NULL,
    reason text NULL,
    actor_id text NOT NULL DEFAULT 'android_staff',
    device_id text NOT NULL DEFAULT 'android',
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_android_order_status_events_order_id
    ON public.android_order_status_events (order_id, updated_at DESC);

ALTER TABLE public.android_order_status_events ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON public.android_order_status_events FROM anon, authenticated;

CREATE OR REPLACE FUNCTION public.update_android_order_status(
    p_client_update_id text,
    p_idempotency_key text,
    p_order_id text,
    p_target_status text,
    p_reason text DEFAULT NULL,
    p_actor_id text DEFAULT 'android_staff',
    p_device_id text DEFAULT 'android',
    p_updated_at timestamptz DEFAULT now()
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_clean_update_id text;
    v_clean_idem_key text;
    v_clean_order_text text;
    v_order_uuid uuid;
    v_target_raw text;
    v_target_norm text;
    v_target_store_value text;
    v_clean_reason text;
    v_existing public.android_order_status_events%ROWTYPE;
    v_order public.orders%ROWTYPE;
    v_current_norm text;
    v_event_id uuid;
BEGIN
    v_clean_update_id := NULLIF(btrim(COALESCE(p_client_update_id, '')), '');
    v_clean_idem_key := NULLIF(btrim(COALESCE(p_idempotency_key, '')), '');
    v_clean_order_text := NULLIF(btrim(COALESCE(p_order_id, '')), '');
    v_target_raw := lower(btrim(COALESCE(p_target_status, '')));
    v_clean_reason := NULLIF(btrim(COALESCE(p_reason, '')), '');

    IF v_clean_update_id IS NULL OR v_clean_idem_key IS NULL OR v_clean_order_text IS NULL THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'client_update_id, idempotency_key, and order_id are required.'
        );
    END IF;

    BEGIN
        v_order_uuid := v_clean_order_text::uuid;
    EXCEPTION WHEN invalid_text_representation THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', format('Invalid order_id UUID format: %s', v_clean_order_text)
        );
    END;

    v_target_norm := CASE
        WHEN v_target_raw IN ('confirmed', 'paid', 'processing', 'accepted', 'preparing') THEN 'CONFIRMED'
        WHEN v_target_raw IN ('ready', 'ready_for_pickup', 'packed', 'dispatched') THEN 'READY'
        WHEN v_target_raw IN ('fulfilled', 'completed', 'delivered', 'converted', 'closed') THEN 'FULFILLED'
        WHEN v_target_raw IN ('cancelled', 'canceled', 'rejected', 'void') THEN 'CANCELLED'
        ELSE NULL
    END;

    IF v_target_norm IS NULL THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', format('Unsupported target order status: %s', p_target_status)
        );
    END IF;

    IF v_target_norm = 'CANCELLED' AND (v_clean_reason IS NULL OR char_length(v_clean_reason) < 3) THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'A clear reason (at least 3 characters) is required when cancelling an order.'
        );
    END IF;

    -- Idempotency check
    SELECT *
    INTO v_existing
    FROM public.android_order_status_events
    WHERE idempotency_key = v_clean_idem_key
       OR client_update_id = v_clean_update_id
    LIMIT 1;

    IF FOUND THEN
        RETURN jsonb_build_object(
            'status', 'DUPLICATE_IGNORED',
            'server_event_id', v_existing.id,
            'order_id', v_existing.order_id,
            'previous_status', v_existing.previous_status,
            'new_status', v_existing.new_status,
            'already_committed', true
        );
    END IF;

    -- Lock target order row
    SELECT *
    INTO v_order
    FROM public.orders
    WHERE id = v_order_uuid
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object(
            'status', 'ORDER_NOT_FOUND',
            'message', format('Order %s no longer exists.', v_clean_order_text)
        );
    END IF;

    v_current_norm := CASE
        WHEN lower(btrim(COALESCE(v_order.status, 'pending'))) IN ('pending', 'new', 'placed', 'awaiting_payment', 'awaiting_confirmation', 'unpaid') THEN 'PENDING'
        WHEN lower(btrim(COALESCE(v_order.status, ''))) IN ('confirmed', 'paid', 'processing', 'accepted', 'preparing') THEN 'CONFIRMED'
        WHEN lower(btrim(COALESCE(v_order.status, ''))) IN ('ready', 'ready_for_pickup', 'packed', 'dispatched') THEN 'READY'
        WHEN lower(btrim(COALESCE(v_order.status, ''))) IN ('fulfilled', 'completed', 'delivered', 'converted', 'closed') THEN 'FULFILLED'
        WHEN lower(btrim(COALESCE(v_order.status, ''))) IN ('cancelled', 'canceled', 'rejected', 'void') THEN 'CANCELLED'
        ELSE 'UNKNOWN'
    END;

    -- Allowed operational workflow:
    --   PENDING / UNKNOWN -> CONFIRMED, READY, CANCELLED
    --   CONFIRMED -> READY, CANCELLED
    --   READY -> FULFILLED, CANCELLED
    --   FULFILLED / CANCELLED -> terminal (no further transitions)
    IF NOT (
        (v_current_norm IN ('PENDING', 'UNKNOWN') AND v_target_norm IN ('CONFIRMED', 'READY', 'CANCELLED')) OR
        (v_current_norm = 'CONFIRMED' AND v_target_norm IN ('READY', 'CANCELLED')) OR
        (v_current_norm = 'READY' AND v_target_norm IN ('FULFILLED', 'CANCELLED'))
    ) THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_TRANSITION',
            'current_status', COALESCE(v_order.status, 'pending'),
            'message', format('Cannot change order from %s to %s.', COALESCE(v_order.status, 'pending'), lower(v_target_norm))
        );
    END IF;

    v_target_store_value := lower(v_target_norm);

    UPDATE public.orders
    SET status = v_target_store_value
    WHERE id = v_order_uuid;

    INSERT INTO public.android_order_status_events (
        client_update_id,
        idempotency_key,
        order_id,
        previous_status,
        new_status,
        reason,
        actor_id,
        device_id,
        updated_at
    )
    VALUES (
        v_clean_update_id,
        v_clean_idem_key,
        v_order_uuid,
        COALESCE(v_order.status, 'pending'),
        v_target_store_value,
        v_clean_reason,
        COALESCE(NULLIF(btrim(COALESCE(p_actor_id, '')), ''), 'android_staff'),
        COALESCE(NULLIF(btrim(COALESCE(p_device_id, '')), ''), 'android'),
        COALESCE(p_updated_at, now())
    )
    RETURNING id INTO v_event_id;

    RETURN jsonb_build_object(
        'status', 'COMMITTED',
        'server_event_id', v_event_id,
        'order_id', v_order_uuid,
        'previous_status', COALESCE(v_order.status, 'pending'),
        'new_status', v_target_store_value,
        'already_committed', false
    );
END;
$$;

REVOKE ALL ON FUNCTION public.update_android_order_status(
    text, text, text, text, text, text, text, timestamptz
) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.update_android_order_status(
    text, text, text, text, text, text, text, timestamptz
) TO anon, authenticated, service_role;



-- -----------------------------------------------------------------------------
-- SECTION 15 of 17: supabase/migrations/20261116_convert_android_order_to_sale_rpc.sql
-- -----------------------------------------------------------------------------
-- Emofah Agencies — Stage 6.3 Idempotent Order-to-Sale Conversion RPC
-- Converts a confirmed / ready / fulfilled storefront order in `public.orders` into real
-- `public.sales` records and `public.products.qty` stock deductions atomically.
-- Enforces unique one-time conversion per order (`order_id UNIQUE`) and idempotency_key safety,
-- verifies live stock under row locks before writing, updates `public.orders.status = 'fulfilled'`,
-- and logs raw financial inflow (`PENDING_DESKTOP_RULES`) when `android_financial_events` exists.

CREATE TABLE IF NOT EXISTS public.android_order_sale_conversions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id uuid NOT NULL UNIQUE REFERENCES public.orders(id) ON DELETE CASCADE,
    client_conversion_id text NOT NULL UNIQUE,
    idempotency_key text NOT NULL UNIQUE,
    payment_method text NOT NULL DEFAULT 'CASH',
    total_amount numeric NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    converted_items_count integer NOT NULL DEFAULT 0 CHECK (converted_items_count >= 0),
    sale_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    actor_id text NOT NULL DEFAULT 'android_staff',
    device_id text NOT NULL DEFAULT 'android',
    converted_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.android_order_sale_conversion_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    conversion_id uuid NOT NULL REFERENCES public.android_order_sale_conversions(id) ON DELETE CASCADE,
    product_id integer NOT NULL REFERENCES public.products(id) ON DELETE RESTRICT,
    server_sale_id integer NOT NULL REFERENCES public.sales(id) ON DELETE RESTRICT,
    qty integer NOT NULL CHECK (qty > 0),
    line_total numeric NOT NULL CHECK (line_total >= 0),
    remaining_qty integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_android_order_sale_conversion_items_conv
    ON public.android_order_sale_conversion_items (conversion_id);

ALTER TABLE public.android_order_sale_conversions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.android_order_sale_conversion_items ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON public.android_order_sale_conversions FROM anon, authenticated;
REVOKE ALL ON public.android_order_sale_conversion_items FROM anon, authenticated;

CREATE OR REPLACE FUNCTION public.convert_android_order_to_sale(
    p_client_conversion_id text,
    p_idempotency_key text,
    p_order_id text,
    p_items jsonb,
    p_payment_method text DEFAULT 'CASH',
    p_actor_id text DEFAULT 'android_staff',
    p_device_id text DEFAULT 'android',
    p_converted_at timestamptz DEFAULT now()
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_clean_conv_id text;
    v_clean_idem_key text;
    v_clean_order_text text;
    v_order_uuid uuid;
    v_clean_payment text;
    v_clean_actor text;
    v_clean_device text;
    v_existing public.android_order_sale_conversions%ROWTYPE;
    v_order public.orders%ROWTYPE;
    v_current_norm text;
    v_item jsonb;
    v_pid bigint;
    v_qty integer;
    v_line_total numeric;
    v_prod public.products%ROWTYPE;
    v_remaining_qty integer;
    v_sale_id integer;
    v_conversion_id uuid;
    v_sale_ids jsonb := '[]'::jsonb;
    v_updated_products jsonb := '[]'::jsonb;
    v_total_amount numeric := 0;
    v_items_count integer := 0;
    v_effective_ts timestamptz;
    v_fin_client_id text;
    v_existing_fin uuid;
BEGIN
    v_clean_conv_id := NULLIF(btrim(COALESCE(p_client_conversion_id, '')), '');
    v_clean_idem_key := NULLIF(btrim(COALESCE(p_idempotency_key, '')), '');
    v_clean_order_text := NULLIF(btrim(COALESCE(p_order_id, '')), '');
    v_clean_payment := upper(COALESCE(NULLIF(btrim(COALESCE(p_payment_method, '')), ''), 'CASH'));
    v_clean_actor := COALESCE(NULLIF(btrim(COALESCE(p_actor_id, '')), ''), 'android_staff');
    v_clean_device := COALESCE(NULLIF(btrim(COALESCE(p_device_id, '')), ''), 'android');
    v_effective_ts := COALESCE(p_converted_at, now());

    IF v_clean_conv_id IS NULL OR v_clean_idem_key IS NULL OR v_clean_order_text IS NULL THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'client_conversion_id, idempotency_key, and order_id are required.'
        );
    END IF;

    BEGIN
        v_order_uuid := v_clean_order_text::uuid;
    EXCEPTION WHEN invalid_text_representation THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', format('Invalid order_id UUID format: %s', v_clean_order_text)
        );
    END;

    IF p_items IS NULL OR jsonb_typeof(p_items) <> 'array' OR jsonb_array_length(p_items) = 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Order must include at least one resolved catalogue item to convert to a sale.'
        );
    END IF;

    -- Idempotency check by request ID or key
    SELECT *
    INTO v_existing
    FROM public.android_order_sale_conversions
    WHERE idempotency_key = v_clean_idem_key
       OR client_conversion_id = v_clean_conv_id
    LIMIT 1;

    IF FOUND THEN
        SELECT COALESCE(
            jsonb_agg(
                jsonb_build_object(
                    'product_id', ci.product_id,
                    'qty_deducted', ci.qty,
                    'server_qty_after', COALESCE(p.qty, ci.remaining_qty),
                    'server_sale_id', ci.server_sale_id
                )
            ),
            '[]'::jsonb
        )
        INTO v_updated_products
        FROM public.android_order_sale_conversion_items ci
        LEFT JOIN public.products p ON p.id = ci.product_id
        WHERE ci.conversion_id = v_existing.id;

        RETURN jsonb_build_object(
            'status', 'DUPLICATE_IGNORED',
            'server_conversion_id', v_existing.id,
            'order_id', v_existing.order_id,
            'total_amount', v_existing.total_amount,
            'sale_ids', v_existing.sale_ids,
            'updated_products', v_updated_products,
            'already_committed', true
        );
    END IF;

    -- Guard against double-selling the same order from a different request/device
    SELECT *
    INTO v_existing
    FROM public.android_order_sale_conversions
    WHERE order_id = v_order_uuid
    LIMIT 1;

    IF FOUND THEN
        RETURN jsonb_build_object(
            'status', 'ALREADY_CONVERTED',
            'server_conversion_id', v_existing.id,
            'order_id', v_existing.order_id,
            'current_status', 'fulfilled',
            'message', 'This order has already been converted into a sale.'
        );
    END IF;

    -- Lock target order row
    SELECT *
    INTO v_order
    FROM public.orders
    WHERE id = v_order_uuid
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object(
            'status', 'ORDER_NOT_FOUND',
            'message', format('Order %s no longer exists.', v_clean_order_text)
        );
    END IF;

    v_current_norm := CASE
        WHEN lower(btrim(COALESCE(v_order.status, 'pending'))) IN ('pending', 'new', 'placed', 'awaiting_payment', 'awaiting_confirmation', 'unpaid') THEN 'PENDING'
        WHEN lower(btrim(COALESCE(v_order.status, ''))) IN ('confirmed', 'paid', 'processing', 'accepted', 'preparing') THEN 'CONFIRMED'
        WHEN lower(btrim(COALESCE(v_order.status, ''))) IN ('ready', 'ready_for_pickup', 'packed', 'dispatched') THEN 'READY'
        WHEN lower(btrim(COALESCE(v_order.status, ''))) IN ('fulfilled', 'completed', 'delivered', 'converted', 'closed') THEN 'FULFILLED'
        WHEN lower(btrim(COALESCE(v_order.status, ''))) IN ('cancelled', 'canceled', 'rejected', 'void') THEN 'CANCELLED'
        ELSE 'UNKNOWN'
    END;

    IF v_current_norm IN ('PENDING', 'CANCELLED', 'UNKNOWN') THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_TRANSITION',
            'current_status', COALESCE(v_order.status, 'pending'),
            'message', format('Order status %s must be Confirmed, Ready, or Fulfilled before converting to a sale.', COALESCE(v_order.status, 'pending'))
        );
    END IF;

    INSERT INTO public.android_order_sale_conversions (
        order_id,
        client_conversion_id,
        idempotency_key,
        payment_method,
        total_amount,
        converted_items_count,
        sale_ids,
        actor_id,
        device_id,
        converted_at
    )
    VALUES (
        v_order_uuid,
        v_clean_conv_id,
        v_clean_idem_key,
        v_clean_payment,
        0,
        0,
        '[]'::jsonb,
        v_clean_actor,
        v_clean_device,
        v_effective_ts
    )
    RETURNING id INTO v_conversion_id;

    -- Process each item ordered consistently by product_id to minimize row-lock deadlocks
    FOR v_item IN
        SELECT elem
        FROM jsonb_array_elements(p_items) AS elem
        ORDER BY COALESCE((elem->>'product_id')::bigint, 0) ASC
    LOOP
        v_pid := COALESCE((v_item->>'product_id')::bigint, -1);
        v_qty := COALESCE((v_item->>'qty')::integer, 0);
        v_line_total := COALESCE((v_item->>'line_total')::numeric, 0);

        IF v_pid <= 0 OR v_qty <= 0 OR v_line_total <= 0 THEN
            RAISE EXCEPTION 'INVALID_ITEM:%', v_pid;
        END IF;

        SELECT *
        INTO v_prod
        FROM public.products
        WHERE id = v_pid
        FOR UPDATE;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'PRODUCT_NOT_FOUND:%', v_pid;
        END IF;

        IF COALESCE(v_prod.qty, 0) < v_qty THEN
            RAISE EXCEPTION 'INSUFFICIENT_STOCK:%:%:%', COALESCE(v_prod.name, 'Product #' || v_pid), COALESCE(v_prod.qty, 0), v_qty;
        END IF;

        INSERT INTO public.sales (product_id, qty_sold, total_price, sale_date)
        VALUES (v_pid::integer, v_qty, v_line_total, v_effective_ts)
        RETURNING id INTO v_sale_id;

        UPDATE public.products
        SET qty = qty - v_qty
        WHERE id = v_pid
        RETURNING qty INTO v_remaining_qty;

        IF to_regclass('public.stock_movements') IS NOT NULL THEN
            BEGIN
                INSERT INTO public.stock_movements (
                    product_id,
                    qty_change,
                    movement_type,
                    reference,
                    notes,
                    created_at
                )
                VALUES (
                    v_pid,
                    -v_qty,
                    'ORDER_SALE_CONVERSION',
                    v_clean_order_text,
                    format('Converted storefront order %s', v_clean_order_text),
                    v_effective_ts
                );
            EXCEPTION WHEN OTHERS THEN
                NULL;
            END;
        END IF;

        INSERT INTO public.android_order_sale_conversion_items (
            conversion_id,
            product_id,
            server_sale_id,
            qty,
            line_total,
            remaining_qty
        )
        VALUES (
            v_conversion_id,
            v_pid::integer,
            v_sale_id,
            v_qty,
            v_line_total,
            v_remaining_qty
        );

        v_total_amount := v_total_amount + v_line_total;
        v_items_count := v_items_count + 1;
        v_sale_ids := v_sale_ids || jsonb_build_array(v_sale_id);
        v_updated_products := v_updated_products || jsonb_build_array(
            jsonb_build_object(
                'product_id', v_pid,
                'qty_deducted', v_qty,
                'server_qty_after', v_remaining_qty,
                'server_sale_id', v_sale_id
            )
        );
    END LOOP;

    UPDATE public.android_order_sale_conversions
    SET total_amount = v_total_amount,
        converted_items_count = v_items_count,
        sale_ids = v_sale_ids
    WHERE id = v_conversion_id;

    IF lower(btrim(COALESCE(v_order.status, ''))) <> 'fulfilled' THEN
        UPDATE public.orders
        SET status = 'fulfilled'
        WHERE id = v_order_uuid;

        IF to_regclass('public.android_order_status_events') IS NOT NULL THEN
            BEGIN
                INSERT INTO public.android_order_status_events (
                    client_update_id,
                    idempotency_key,
                    order_id,
                    previous_status,
                    new_status,
                    reason,
                    actor_id,
                    device_id,
                    updated_at
                )
                VALUES (
                    'ordful-' || v_clean_conv_id,
                    'idem-ordful-' || v_clean_conv_id,
                    v_order_uuid,
                    COALESCE(v_order.status, 'ready'),
                    'fulfilled',
                    'Converted to shared sales transaction',
                    v_clean_actor,
                    v_clean_device,
                    v_effective_ts
                )
                ON CONFLICT DO NOTHING;
            EXCEPTION WHEN OTHERS THEN
                NULL;
            END;
        END IF;
    END IF;

    -- Log raw inflow financial event (classification rules managed by Desktop)
    IF v_total_amount > 0 AND to_regclass('public.android_financial_events') IS NOT NULL THEN
        v_fin_client_id := 'fin-ordconv-' || v_clean_conv_id;
        SELECT id INTO v_existing_fin
        FROM public.android_financial_events
        WHERE client_event_id = v_fin_client_id
        LIMIT 1;

        IF v_existing_fin IS NULL THEN
            INSERT INTO public.android_financial_events (
                client_event_id,
                event_type,
                direction,
                amount,
                currency,
                category,
                classification_status,
                reference_code,
                channel,
                counterparty,
                raw_evidence,
                parse_status,
                source,
                actor_id,
                device_id,
                notes,
                occurred_at
            )
            VALUES (
                v_fin_client_id,
                'ORDER_SALE_CONVERSION',
                'INFLOW',
                v_total_amount,
                'KES',
                'PENDING_DESKTOP_RULES',
                'PENDING_DESKTOP_RULES',
                v_clean_order_text,
                v_clean_payment,
                'Online Order #' || left(v_clean_order_text, 8),
                format('Converted %s line item(s) from order %s', v_items_count, v_clean_order_text),
                'VALID_PARSED',
                'ANDROID_ORDER_SALE',
                v_clean_actor,
                v_clean_device,
                NULL,
                v_effective_ts
            );
        END IF;
    END IF;

    RETURN jsonb_build_object(
        'status', 'COMMITTED',
        'server_conversion_id', v_conversion_id,
        'order_id', v_order_uuid,
        'total_amount', v_total_amount,
        'sale_ids', v_sale_ids,
        'updated_products', v_updated_products,
        'already_committed', false
    );
EXCEPTION WHEN OTHERS THEN
    IF SQLERRM LIKE 'INSUFFICIENT_STOCK:%' THEN
        RETURN jsonb_build_object(
            'status', 'INSUFFICIENT_STOCK',
            'message', format(
                'Insufficient stock for %s: %s left, need %s.',
                split_part(SQLERRM, ':', 2),
                split_part(SQLERRM, ':', 3),
                split_part(SQLERRM, ':', 4)
            )
        );
    ELSIF SQLERRM LIKE 'PRODUCT_NOT_FOUND:%' THEN
        RETURN jsonb_build_object(
            'status', 'PRODUCT_NOT_FOUND',
            'message', format('Catalogue product #%s no longer exists.', split_part(SQLERRM, ':', 2))
        );
    ELSIF SQLERRM LIKE 'INVALID_ITEM:%' THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'All order items require a valid product ID, positive quantity, and positive line total.'
        );
    END IF;
    RETURN jsonb_build_object(
        'status', 'INVALID_INPUT',
        'message', SQLERRM
    );
END;
$$;

REVOKE ALL ON FUNCTION public.convert_android_order_to_sale(
    text, text, text, jsonb, text, text, text, timestamptz
) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.convert_android_order_to_sale(
    text, text, text, jsonb, text, text, text, timestamptz
) TO anon, authenticated, service_role;



-- -----------------------------------------------------------------------------
-- SECTION 16 of 17: supabase/migrations/20261116_rbac_and_rls_enforcement.sql
-- -----------------------------------------------------------------------------
-- Emofah Agencies — Stage 9.2 Role-Based Permissions & Backend RLS Enforcement
-- Non-destructive & idempotent:
-- 1. Establishes authoritative `public.android_staff_roles` mapped to `auth.users(id)`.
-- 2. Never trusts client-supplied `p_actor_role` or user-editable `user_metadata` for privileged actions.
-- 3. Locks all `android_*` operational tables behind RLS (no direct REST insert/update/delete).
-- 4. Enforces Supervisor / Owner-Manager server-side authorization inside privileged RPCs
--    while preserving Desktop & Storefront compatibility.

CREATE TABLE IF NOT EXISTS public.android_staff_roles (
    user_id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    email text NOT NULL,
    full_name text NULL,
    staff_role text NOT NULL DEFAULT 'CASHIER'
        CHECK (staff_role IN ('CASHIER', 'SUPERVISOR', 'OWNER_MANAGER')),
    is_active boolean NOT NULL DEFAULT true,
    assigned_by text NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE public.android_staff_roles ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON public.android_staff_roles FROM anon, authenticated;
GRANT SELECT ON public.android_staff_roles TO authenticated;

DROP POLICY IF EXISTS "staff_read_own_role" ON public.android_staff_roles;
CREATE POLICY "staff_read_own_role"
    ON public.android_staff_roles
    FOR SELECT
    TO authenticated
    USING (auth.uid() = user_id);

-- Ensure every Android operational/audit/idempotency table has RLS enabled and direct client access revoked.
DO $$
DECLARE
    tbl text;
    android_tables text[] := ARRAY[
        'android_financial_events',
        'android_financial_idempotency',
        'android_sale_idempotency',
        'android_sale_reversals',
        'android_stock_adjustments',
        'android_stock_receipts',
        'android_stock_count_sessions',
        'android_stock_count_items',
        'android_stock_count_idempotency',
        'android_order_status_events',
        'android_order_sale_conversions',
        'android_order_sale_conversion_items',
        'android_purchases',
        'android_purchase_items',
        'android_service_operations',
        'android_service_consumables',
        'android_product_idempotency',
        'android_recognition_corrections'
    ];
BEGIN
    FOREACH tbl IN ARRAY android_tables LOOP
        IF to_regclass(format('public.%I', tbl)) IS NOT NULL THEN
            EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', tbl);
            EXECUTE format('REVOKE ALL ON public.%I FROM anon, authenticated', tbl);
        END IF;
    END LOOP;
END
$$;

-- Authoritative server-side role resolver.
-- Reads `public.android_staff_roles` first, then JWT `app_metadata` (never `user_metadata`).
CREATE OR REPLACE FUNCTION public.resolve_android_effective_role()
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid uuid;
    v_row public.android_staff_roles%ROWTYPE;
    v_app_meta jsonb;
    v_raw_claim text;
BEGIN
    v_uid := auth.uid();
    IF v_uid IS NULL THEN
        RETURN 'UNAUTHENTICATED';
    END IF;

    SELECT *
    INTO v_row
    FROM public.android_staff_roles
    WHERE user_id = v_uid
    LIMIT 1;

    IF FOUND THEN
        IF NOT v_row.is_active THEN
            RETURN 'DISABLED';
        END IF;
        RETURN upper(v_row.staff_role);
    END IF;

    v_app_meta := COALESCE(auth.jwt() -> 'app_metadata', '{}'::jsonb);
    v_raw_claim := lower(COALESCE(
        NULLIF(btrim(v_app_meta ->> 'staff_role'), ''),
        NULLIF(btrim(v_app_meta ->> 'role'), ''),
        ''
    ));

    IF v_raw_claim IN ('owner', 'manager', 'owner_manager', 'admin', 'super_admin') THEN
        RETURN 'OWNER_MANAGER';
    ELSIF v_raw_claim IN ('supervisor', 'shift_lead', 'lead') THEN
        RETURN 'SUPERVISOR';
    END IF;

    RETURN 'CASHIER';
END;
$$;

REVOKE ALL ON FUNCTION public.resolve_android_effective_role() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.resolve_android_effective_role() TO anon, authenticated, service_role;

-- Returns authoritative role & capability flags for the currently signed-in Android staff user.
CREATE OR REPLACE FUNCTION public.get_my_android_staff_profile()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid uuid;
    v_role text;
    v_row public.android_staff_roles%ROWTYPE;
    v_email text;
    v_full_name text;
    v_is_active boolean := true;
    v_is_privileged boolean := false;
BEGIN
    v_uid := auth.uid();
    IF v_uid IS NULL THEN
        RETURN jsonb_build_object(
            'authenticated', false,
            'staff_role', 'CASHIER',
            'is_active', false,
            'capabilities', jsonb_build_object(
                'can_override_price', false,
                'can_reverse_sale', false,
                'can_adjust_stock', false,
                'can_submit_stock_count', false,
                'can_resolve_conflicts', false,
                'can_manage_security', false
            )
        );
    END IF;

    v_role := public.resolve_android_effective_role();
    v_is_active := (v_role <> 'DISABLED');

    SELECT *
    INTO v_row
    FROM public.android_staff_roles
    WHERE user_id = v_uid
    LIMIT 1;

    v_email := COALESCE(v_row.email, auth.jwt() ->> 'email', '');
    v_full_name := COALESCE(v_row.full_name, v_email);

    IF v_role = 'DISABLED' THEN
        RETURN jsonb_build_object(
            'authenticated', true,
            'user_id', v_uid::text,
            'email', v_email,
            'full_name', v_full_name,
            'staff_role', 'CASHIER',
            'is_active', false,
            'capabilities', jsonb_build_object(
                'can_override_price', false,
                'can_reverse_sale', false,
                'can_adjust_stock', false,
                'can_submit_stock_count', false,
                'can_resolve_conflicts', false,
                'can_manage_security', false
            )
        );
    END IF;

    v_is_privileged := v_role IN ('SUPERVISOR', 'OWNER_MANAGER');

    RETURN jsonb_build_object(
        'authenticated', true,
        'user_id', v_uid::text,
        'email', v_email,
        'full_name', v_full_name,
        'staff_role', v_role,
        'is_active', v_is_active,
        'capabilities', jsonb_build_object(
            'can_override_price', v_is_privileged,
            'can_reverse_sale', v_is_privileged,
            'can_adjust_stock', v_is_privileged,
            'can_submit_stock_count', v_is_privileged,
            'can_resolve_conflicts', v_is_privileged,
            'can_manage_security', (v_role = 'OWNER_MANAGER')
        )
    );
END;
$$;

REVOKE ALL ON FUNCTION public.get_my_android_staff_profile() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_my_android_staff_profile() TO anon, authenticated, service_role;

-- Harden `public.record_android_sale`: server-side authoritative role check for price overrides.
CREATE OR REPLACE FUNCTION public.record_android_sale(
    p_product_id bigint,
    p_qty integer,
    p_total numeric,
    p_client_sale_id text,
    p_idempotency_key text,
    p_payment_method text DEFAULT 'CASH',
    p_actor_id text DEFAULT 'android_staff',
    p_device_id text DEFAULT 'android',
    p_sale_date timestamptz DEFAULT now(),
    p_unit_sell_price numeric DEFAULT NULL,
    p_original_sell_price numeric DEFAULT NULL,
    p_price_override_reason text DEFAULT NULL,
    p_actor_role text DEFAULT 'CASHIER'
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_existing public.android_sale_idempotency%ROWTYPE;
    v_current_qty integer;
    v_remaining_qty integer;
    v_sale_id integer;
    v_server_role text;
    v_clean_role text;
    v_clean_reason text;
    v_is_override boolean;
    v_require_auth text;
BEGIN
    v_server_role := public.resolve_android_effective_role();
    IF v_server_role = 'DISABLED' THEN
        RETURN jsonb_build_object(
            'status', 'UNAUTHORIZED_ROLE',
            'message', 'Your Emofah staff account is deactivated.'
        );
    END IF;

    SELECT value INTO v_require_auth
    FROM public.settings
    WHERE key = 'require_android_auth_for_all_writes'
    LIMIT 1;

    IF lower(COALESCE(v_require_auth, 'false')) = 'true' AND auth.uid() IS NULL THEN
        RETURN jsonb_build_object(
            'status', 'UNAUTHORIZED_ROLE',
            'message', 'Staff sign-in is required before recording transactions.'
        );
    END IF;

    IF p_product_id IS NULL OR p_product_id <= 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Valid product ID is required.'
        );
    END IF;

    IF p_qty IS NULL OR p_qty <= 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Quantity sold must be at least 1.'
        );
    END IF;

    IF p_total IS NULL OR p_total <= 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Sale total must be greater than zero.'
        );
    END IF;

    IF p_idempotency_key IS NULL OR btrim(p_idempotency_key) = '' OR p_client_sale_id IS NULL OR btrim(p_client_sale_id) = '' THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Stable client_sale_id and idempotency_key are required.'
        );
    END IF;

    v_clean_role := CASE
        WHEN auth.uid() IS NOT NULL THEN v_server_role
        ELSE 'CASHIER'
    END;
    v_clean_reason := NULLIF(btrim(COALESCE(p_price_override_reason, '')), '');
    v_is_override := p_unit_sell_price IS NOT NULL
        AND p_original_sell_price IS NOT NULL
        AND abs(p_unit_sell_price - p_original_sell_price) > 0.009;

    IF v_is_override THEN
        IF auth.uid() IS NULL OR v_server_role NOT IN ('SUPERVISOR', 'OWNER_MANAGER') THEN
            RETURN jsonb_build_object(
                'status', 'UNAUTHORIZED_ROLE',
                'message', 'Price override requires an authenticated Supervisor or Owner/Manager session.'
            );
        END IF;
        IF p_unit_sell_price <= 0 THEN
            RETURN jsonb_build_object(
                'status', 'INVALID_INPUT',
                'message', 'Overridden unit price must be greater than zero.'
            );
        END IF;
        IF v_clean_reason IS NULL THEN
            RETURN jsonb_build_object(
                'status', 'INVALID_INPUT',
                'message', 'A reason is required for authorized selling price exceptions.'
            );
        END IF;
    END IF;

    SELECT *
    INTO v_existing
    FROM public.android_sale_idempotency
    WHERE idempotency_key = p_idempotency_key
       OR client_sale_id = p_client_sale_id
    LIMIT 1;

    IF FOUND THEN
        SELECT qty INTO v_current_qty FROM public.products WHERE id = v_existing.product_id;
        RETURN jsonb_build_object(
            'status', 'DUPLICATE_IGNORED',
            'server_sale_id', v_existing.server_sale_id,
            'remaining_qty', COALESCE(v_current_qty, v_existing.remaining_qty),
            'already_committed', true
        );
    END IF;

    SELECT qty
    INTO v_current_qty
    FROM public.products
    WHERE id = p_product_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object(
            'status', 'PRODUCT_NOT_FOUND',
            'message', format('Product #%s does not exist in catalogue.', p_product_id)
        );
    END IF;

    IF COALESCE(v_current_qty, 0) < p_qty THEN
        RETURN jsonb_build_object(
            'status', 'INSUFFICIENT_STOCK',
            'available_qty', COALESCE(v_current_qty, 0),
            'message', format('Insufficient stock: requested %s but only %s remaining.', p_qty, COALESCE(v_current_qty, 0))
        );
    END IF;

    INSERT INTO public.sales (product_id, qty_sold, total_price, sale_date)
    VALUES (p_product_id::integer, p_qty, p_total, COALESCE(p_sale_date, now()))
    RETURNING id INTO v_sale_id;

    UPDATE public.products
    SET qty = qty - p_qty
    WHERE id = p_product_id
    RETURNING qty INTO v_remaining_qty;

    INSERT INTO public.android_sale_idempotency (
        idempotency_key,
        client_sale_id,
        server_sale_id,
        product_id,
        qty_sold,
        total_price,
        payment_method,
        actor_id,
        device_id,
        source,
        remaining_qty,
        sale_date,
        unit_sell_price,
        original_sell_price,
        price_override_reason,
        actor_role
    )
    VALUES (
        p_idempotency_key,
        p_client_sale_id,
        v_sale_id,
        p_product_id::integer,
        p_qty,
        p_total,
        COALESCE(NULLIF(btrim(p_payment_method), ''), 'CASH'),
        COALESCE(NULLIF(btrim(auth.jwt() ->> 'email'), ''), NULLIF(btrim(p_actor_id), ''), 'android_staff'),
        COALESCE(NULLIF(btrim(p_device_id), ''), 'android'),
        'ANDROID',
        v_remaining_qty,
        COALESCE(p_sale_date, now()),
        p_unit_sell_price,
        p_original_sell_price,
        v_clean_reason,
        v_clean_role
    );

    RETURN jsonb_build_object(
        'status', 'COMMITTED',
        'server_sale_id', v_sale_id,
        'remaining_qty', v_remaining_qty,
        'already_committed', false
    );
END;
$$;

-- Harden `public.reverse_android_sale`: requires authenticated Supervisor or Owner/Manager JWT.
CREATE OR REPLACE FUNCTION public.reverse_android_sale(
    p_client_sale_id text,
    p_server_sale_id integer,
    p_product_id bigint,
    p_qty integer,
    p_total numeric,
    p_idempotency_key text,
    p_reason text,
    p_payment_method text DEFAULT 'CASH',
    p_actor_id text DEFAULT 'android_staff',
    p_actor_role text DEFAULT 'CASHIER',
    p_device_id text DEFAULT 'android',
    p_reversed_at timestamptz DEFAULT now()
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_existing public.android_sale_reversals%ROWTYPE;
    v_sale_record public.sales%ROWTYPE;
    v_current_qty integer;
    v_remaining_qty integer;
    v_server_role text;
    v_clean_reason text;
    v_restore_qty integer;
    v_reverse_total numeric;
BEGIN
    v_server_role := public.resolve_android_effective_role();
    IF auth.uid() IS NULL OR v_server_role NOT IN ('SUPERVISOR', 'OWNER_MANAGER') THEN
        RETURN jsonb_build_object(
            'status', 'UNAUTHORIZED_ROLE',
            'message', 'Reversing a completed sale requires an authenticated Supervisor or Owner/Manager session.'
        );
    END IF;

    IF p_client_sale_id IS NULL OR btrim(p_client_sale_id) = '' OR
       p_idempotency_key IS NULL OR btrim(p_idempotency_key) = '' THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Valid client_sale_id and idempotency_key are required.'
        );
    END IF;

    v_clean_reason := NULLIF(btrim(COALESCE(p_reason, '')), '');
    IF v_clean_reason IS NULL OR char_length(v_clean_reason) < 3 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'A clear reversal reason is required.'
        );
    END IF;

    SELECT *
    INTO v_existing
    FROM public.android_sale_reversals
    WHERE idempotency_key = p_idempotency_key
       OR client_sale_id = p_client_sale_id
    LIMIT 1;

    IF FOUND THEN
        SELECT qty INTO v_current_qty FROM public.products WHERE id = v_existing.product_id;
        RETURN jsonb_build_object(
            'status', 'ALREADY_REVERSED',
            'server_sale_id', v_existing.server_sale_id,
            'remaining_qty', COALESCE(v_current_qty, v_existing.remaining_qty),
            'already_reversed', true
        );
    END IF;

    IF p_product_id IS NULL OR p_product_id <= 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Valid product ID is required for stock restoration.'
        );
    END IF;

    v_restore_qty := COALESCE(p_qty, 0);
    v_reverse_total := COALESCE(p_total, 0);

    IF p_server_sale_id IS NOT NULL AND p_server_sale_id > 0 THEN
        SELECT *
        INTO v_sale_record
        FROM public.sales
        WHERE id = p_server_sale_id
        LIMIT 1;

        IF FOUND THEN
            v_restore_qty := COALESCE(NULLIF(v_sale_record.qty_sold, 0), v_restore_qty);
            v_reverse_total := COALESCE(NULLIF(v_sale_record.total_price, 0), v_reverse_total);
        END IF;
    END IF;

    IF v_restore_qty <= 0 OR v_reverse_total <= 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Reversed quantity and amount must be greater than zero.'
        );
    END IF;

    SELECT qty
    INTO v_current_qty
    FROM public.products
    WHERE id = p_product_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object(
            'status', 'PRODUCT_NOT_FOUND',
            'message', format('Product #%s does not exist in catalogue.', p_product_id)
        );
    END IF;

    UPDATE public.products
    SET qty = COALESCE(qty, 0) + v_restore_qty
    WHERE id = p_product_id
    RETURNING qty INTO v_remaining_qty;

    INSERT INTO public.android_sale_reversals (
        idempotency_key,
        client_sale_id,
        server_sale_id,
        product_id,
        restored_qty,
        reversed_amount,
        payment_method,
        accounting_treatment,
        reason,
        actor_id,
        actor_role,
        device_id,
        remaining_qty,
        reversed_at
    )
    VALUES (
        p_idempotency_key,
        p_client_sale_id,
        p_server_sale_id,
        p_product_id::integer,
        v_restore_qty,
        v_reverse_total,
        COALESCE(NULLIF(btrim(p_payment_method), ''), 'CASH'),
        'SALE_REVERSAL_CREDIT_ADJUSTMENT',
        v_clean_reason,
        COALESCE(NULLIF(btrim(auth.jwt() ->> 'email'), ''), NULLIF(btrim(p_actor_id), ''), 'android_staff'),
        v_server_role,
        COALESCE(NULLIF(btrim(p_device_id), ''), 'android'),
        v_remaining_qty,
        COALESCE(p_reversed_at, now())
    );

    RETURN jsonb_build_object(
        'status', 'COMMITTED',
        'server_sale_id', p_server_sale_id,
        'restored_qty', v_restore_qty,
        'remaining_qty', v_remaining_qty,
        'already_reversed', false
    );
END;
$$;

-- Harden `public.adjust_android_stock`: requires authenticated Supervisor or Owner/Manager JWT.
CREATE OR REPLACE FUNCTION public.adjust_android_stock(
    p_product_id bigint,
    p_delta_qty integer,
    p_reason_code text,
    p_notes text,
    p_client_adjustment_id text,
    p_idempotency_key text,
    p_actor_id text DEFAULT 'android_staff',
    p_actor_role text DEFAULT 'CASHIER',
    p_device_id text DEFAULT 'android',
    p_adjusted_at timestamptz DEFAULT now()
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_existing public.android_stock_adjustments%ROWTYPE;
    v_current_qty integer;
    v_new_qty integer;
    v_clean_reason text;
    v_clean_notes text;
    v_clamped boolean := false;
    v_server_role text;
BEGIN
    v_server_role := public.resolve_android_effective_role();
    IF auth.uid() IS NULL OR v_server_role NOT IN ('SUPERVISOR', 'OWNER_MANAGER') THEN
        RETURN jsonb_build_object(
            'status', 'UNAUTHORIZED_ROLE',
            'message', 'Stock adjustments require an authenticated Supervisor or Owner/Manager session.'
        );
    END IF;

    IF p_product_id IS NULL OR p_product_id <= 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Valid product ID is required.'
        );
    END IF;

    IF p_delta_qty IS NULL OR p_delta_qty = 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Quantity delta cannot be zero.'
        );
    END IF;

    v_clean_reason := upper(btrim(COALESCE(p_reason_code, '')));
    IF v_clean_reason NOT IN (
        'DAMAGED',
        'EXPIRED',
        'THEFT_OR_SHRINKAGE',
        'INTERNAL_USE',
        'COUNT_CORRECTION_DECREASE',
        'COUNT_CORRECTION_INCREASE'
    ) THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', format('Unsupported reason code: %s', p_reason_code)
        );
    END IF;

    v_clean_notes := btrim(COALESCE(p_notes, ''));
    IF length(v_clean_notes) < 3 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Detailed explanation notes (minimum 3 characters) are required for stock adjustments.'
        );
    END IF;

    IF p_idempotency_key IS NULL OR btrim(p_idempotency_key) = '' OR
       p_client_adjustment_id IS NULL OR btrim(p_client_adjustment_id) = '' THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Stable client_adjustment_id and idempotency_key are required.'
        );
    END IF;

    SELECT *
    INTO v_existing
    FROM public.android_stock_adjustments
    WHERE idempotency_key = p_idempotency_key
       OR client_adjustment_id = p_client_adjustment_id
    LIMIT 1;

    IF FOUND THEN
        SELECT qty INTO v_current_qty FROM public.products WHERE id = v_existing.product_id;
        RETURN jsonb_build_object(
            'status', 'DUPLICATE_IGNORED',
            'server_qty', COALESCE(v_current_qty, v_existing.server_qty_after),
            'already_committed', true,
            'is_clamped', v_existing.is_clamped
        );
    END IF;

    SELECT qty
    INTO v_current_qty
    FROM public.products
    WHERE id = p_product_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object(
            'status', 'PRODUCT_NOT_FOUND',
            'message', format('Product #%s does not exist in catalogue.', p_product_id)
        );
    END IF;

    v_current_qty := COALESCE(v_current_qty, 0);

    IF (v_current_qty + p_delta_qty) < 0 THEN
        v_new_qty := 0;
        v_clamped := true;
    ELSE
        v_new_qty := v_current_qty + p_delta_qty;
    END IF;

    UPDATE public.products
    SET qty = v_new_qty
    WHERE id = p_product_id;

    INSERT INTO public.android_stock_adjustments (
        idempotency_key,
        client_adjustment_id,
        product_id,
        delta_qty,
        reason_code,
        notes,
        actor_id,
        actor_role,
        device_id,
        server_qty_before,
        server_qty_after,
        is_clamped,
        adjusted_at
    )
    VALUES (
        p_idempotency_key,
        p_client_adjustment_id,
        p_product_id::integer,
        p_delta_qty,
        v_clean_reason,
        v_clean_notes,
        COALESCE(NULLIF(btrim(auth.jwt() ->> 'email'), ''), NULLIF(btrim(p_actor_id), ''), 'android_staff'),
        v_server_role,
        COALESCE(NULLIF(btrim(p_device_id), ''), 'android'),
        v_current_qty,
        v_new_qty,
        v_clamped,
        COALESCE(p_adjusted_at, now())
    );

    RETURN jsonb_build_object(
        'status', 'COMMITTED',
        'server_qty', v_new_qty,
        'previous_qty', v_current_qty,
        'delta_qty', p_delta_qty,
        'already_committed', false,
        'is_clamped', v_clamped
    );
END;
$$;

-- Harden `public.submit_physical_stock_count`: requires authenticated Supervisor or Owner/Manager JWT.
CREATE OR REPLACE FUNCTION public.submit_physical_stock_count(
    p_session_name text,
    p_created_by text,
    p_items jsonb,
    p_idempotency_key text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_session_id uuid;
    v_item jsonb;
    v_prod_id integer;
    v_sys_qty integer;
    v_counted_qty integer;
    v_variance integer;
    v_reason text;
    v_note text;
    v_client_item_id text;
    v_existing_session_id uuid;
    v_has_stock_movements boolean;
    v_server_role text;
BEGIN
    v_server_role := public.resolve_android_effective_role();
    IF auth.uid() IS NULL OR v_server_role NOT IN ('SUPERVISOR', 'OWNER_MANAGER') THEN
        RETURN jsonb_build_object(
            'success', false,
            'status', 'UNAUTHORIZED_ROLE',
            'message', 'Physical stock count reconciliation requires an authenticated Supervisor or Owner/Manager session.'
        );
    END IF;

    IF p_items IS NULL OR jsonb_typeof(p_items) <> 'array' OR jsonb_array_length(p_items) = 0 THEN
        RETURN jsonb_build_object(
            'success', false,
            'status', 'INVALID_INPUT',
            'message', 'Stock count session must include at least one item.'
        );
    END IF;

    IF p_idempotency_key IS NOT NULL AND trim(p_idempotency_key) <> '' THEN
        SELECT session_id INTO v_existing_session_id
        FROM public.android_stock_count_idempotency
        WHERE idempotency_key = p_idempotency_key;

        IF v_existing_session_id IS NOT NULL THEN
            RETURN jsonb_build_object(
                'success', true,
                'status', 'DUPLICATE_IGNORED',
                'already_committed', true,
                'session_id', v_existing_session_id,
                'message', 'Duplicate request replayed successfully (idempotent).'
            );
        END IF;
    END IF;

    v_has_stock_movements := (to_regclass('public.stock_movements') IS NOT NULL);

    INSERT INTO public.android_stock_count_sessions (session_name, status, created_by, completed_at)
    VALUES (
        COALESCE(NULLIF(btrim(p_session_name), ''), 'Physical Stock Count'),
        'COMPLETED',
        COALESCE(NULLIF(btrim(auth.jwt() ->> 'email'), ''), NULLIF(btrim(p_created_by), ''), 'android_staff'),
        now()
    )
    RETURNING id INTO v_session_id;

    FOR v_item IN SELECT * FROM jsonb_array_elements(p_items)
    LOOP
        v_prod_id := (v_item->>'product_id')::integer;
        v_sys_qty := (v_item->>'system_qty')::integer;
        v_counted_qty := GREATEST(0, (v_item->>'counted_qty')::integer);
        v_variance := v_counted_qty - v_sys_qty;
        v_reason := v_item->>'reason_code';
        v_note := v_item->>'note';
        v_client_item_id := v_item->>'client_item_id';

        INSERT INTO public.android_stock_count_items (
            session_id, product_id, system_qty, counted_qty, variance, reason_code, note, client_item_id
        ) VALUES (
            v_session_id, v_prod_id, v_sys_qty, v_counted_qty, v_variance, v_reason, v_note, v_client_item_id
        )
        ON CONFLICT (client_item_id) DO NOTHING;

        UPDATE public.products
        SET qty = v_counted_qty
        WHERE id = v_prod_id;

        IF v_has_stock_movements AND v_variance <> 0 THEN
            INSERT INTO public.stock_movements (product_id, change_qty, reason, notes)
            VALUES (
                v_prod_id,
                v_variance,
                COALESCE(NULLIF(btrim(v_reason), ''), 'PHYSICAL_COUNT'),
                format('Physical count (%s): %s', COALESCE(NULLIF(btrim(p_session_name), ''), 'Count'), COALESCE(v_note, ''))
            );
        END IF;
    END LOOP;

    IF p_idempotency_key IS NOT NULL AND trim(p_idempotency_key) <> '' THEN
        INSERT INTO public.android_stock_count_idempotency (idempotency_key, session_id)
        VALUES (p_idempotency_key, v_session_id)
        ON CONFLICT (idempotency_key) DO NOTHING;
    END IF;

    RETURN jsonb_build_object(
        'success', true,
        'status', 'COMMITTED',
        'already_committed', false,
        'session_id', v_session_id,
        'message', 'Physical stock count reconciled successfully.'
    );
END;
$$;



-- -----------------------------------------------------------------------------
-- SECTION 17 of 17: supabase/migrations/20261116_android_audit_events_rpc.sql
-- -----------------------------------------------------------------------------
-- Emofah Agencies — Stage 9.3 Immutable Audit & Accountability Trail
-- Additive & idempotent:
-- 1. Creates append-only `public.android_audit_events` storing actor, role, device, before/after JSON, reason, and timestamps.
-- 2. Enforces RLS + immutability trigger (no UPDATE or DELETE allowed on committed audit events).
-- 3. Provides idempotent `record_android_audit_events(p_events jsonb)` and role-scoped `get_android_audit_events(p_limit integer)`.

CREATE TABLE IF NOT EXISTS public.android_audit_events (
    audit_id text PRIMARY KEY,
    actor_id text NOT NULL,
    actor_role text NOT NULL DEFAULT 'CASHIER',
    auth_user_id uuid NULL,
    device_id text NOT NULL,
    operation text NOT NULL,
    entity_type text NOT NULL,
    entity_id text NOT NULL,
    before_json text NULL,
    after_json text NULL,
    reason text NULL,
    occurred_at timestamptz NOT NULL,
    synced_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_android_audit_events_occurred_at
    ON public.android_audit_events (occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_android_audit_events_entity
    ON public.android_audit_events (entity_type, entity_id);

CREATE INDEX IF NOT EXISTS idx_android_audit_events_actor
    ON public.android_audit_events (actor_id);

CREATE INDEX IF NOT EXISTS idx_android_audit_events_auth_user
    ON public.android_audit_events (auth_user_id);

ALTER TABLE public.android_audit_events ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON public.android_audit_events FROM anon, authenticated;

-- Append-only guard: audit records cannot be altered or deleted once recorded.
CREATE OR REPLACE FUNCTION public.prevent_android_audit_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Audit trail records in public.android_audit_events are immutable (append-only).';
END;
$$;

DROP TRIGGER IF EXISTS trg_prevent_android_audit_mutation ON public.android_audit_events;
CREATE TRIGGER trg_prevent_android_audit_mutation
    BEFORE UPDATE OR DELETE ON public.android_audit_events
    FOR EACH ROW
    EXECUTE FUNCTION public.prevent_android_audit_mutation();

-- Idempotent batch ingestion RPC for local Android audit events.
CREATE OR REPLACE FUNCTION public.record_android_audit_events(
    p_events jsonb
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_server_role text;
    v_require_auth text;
    v_uid uuid;
    v_item jsonb;
    v_audit_id text;
    v_actor_id text;
    v_actor_role text;
    v_device_id text;
    v_operation text;
    v_entity_type text;
    v_entity_id text;
    v_before_json text;
    v_after_json text;
    v_reason text;
    v_occurred_ms bigint;
    v_occurred_at timestamptz;
    v_row_count integer;
    v_accepted_count integer := 0;
    v_duplicate_count integer := 0;
    v_confirmed_ids jsonb := '[]'::jsonb;
BEGIN
    v_uid := auth.uid();
    v_server_role := public.resolve_android_effective_role();

    IF v_server_role = 'DISABLED' THEN
        RETURN jsonb_build_object(
            'status', 'UNAUTHORIZED_ROLE',
            'message', 'Your Emofah staff account is deactivated.'
        );
    END IF;

    SELECT value INTO v_require_auth
    FROM public.settings
    WHERE key = 'require_android_auth_for_all_writes'
    LIMIT 1;

    IF lower(COALESCE(v_require_auth, 'false')) = 'true' AND v_uid IS NULL THEN
        RETURN jsonb_build_object(
            'status', 'UNAUTHORIZED_ROLE',
            'message', 'Staff sign-in is required before syncing audit records.'
        );
    END IF;

    IF p_events IS NULL OR jsonb_typeof(p_events) <> 'array' THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'p_events must be a JSON array.'
        );
    END IF;

    IF jsonb_array_length(p_events) = 0 THEN
        RETURN jsonb_build_object(
            'status', 'COMMITTED',
            'accepted_count', 0,
            'duplicate_count', 0,
            'confirmed_audit_ids', '[]'::jsonb
        );
    END IF;

    IF jsonb_array_length(p_events) > 100 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Maximum 100 audit events per batch.'
        );
    END IF;

    FOR v_item IN SELECT * FROM jsonb_array_elements(p_events)
    LOOP
        v_audit_id := NULLIF(btrim(COALESCE(v_item ->> 'audit_id', '')), '');
        v_operation := NULLIF(btrim(COALESCE(v_item ->> 'operation', '')), '');
        v_entity_type := NULLIF(btrim(COALESCE(v_item ->> 'entity_type', '')), '');
        v_entity_id := NULLIF(btrim(COALESCE(v_item ->> 'entity_id', '')), '');

        IF v_audit_id IS NULL OR v_operation IS NULL OR v_entity_type IS NULL OR v_entity_id IS NULL THEN
            CONTINUE;
        END IF;

        v_actor_id := COALESCE(
            NULLIF(btrim(v_item ->> 'actor_id'), ''),
            NULLIF(btrim(auth.jwt() ->> 'email'), ''),
            'android_staff'
        );

        -- Preserve authenticated server role attribution when signed in; otherwise fall back safely.
        v_actor_role := CASE
            WHEN v_uid IS NOT NULL THEN v_server_role
            WHEN upper(btrim(COALESCE(v_item ->> 'actor_role', ''))) IN ('CASHIER', 'SUPERVISOR', 'OWNER_MANAGER')
                THEN upper(btrim(v_item ->> 'actor_role'))
            ELSE 'CASHIER'
        END;

        v_device_id := COALESCE(NULLIF(btrim(v_item ->> 'device_id'), ''), 'android');
        v_before_json := NULLIF(v_item ->> 'before_json', '');
        v_after_json := NULLIF(v_item ->> 'after_json', '');
        v_reason := NULLIF(btrim(COALESCE(v_item ->> 'reason', '')), '');

        v_occurred_at := now();
        IF (v_item ? 'occurred_at_epoch_ms') AND NULLIF(btrim(v_item ->> 'occurred_at_epoch_ms'), '') IS NOT NULL THEN
            BEGIN
                v_occurred_ms := (v_item ->> 'occurred_at_epoch_ms')::bigint;
                IF v_occurred_ms > 0 THEN
                    v_occurred_at := to_timestamp(v_occurred_ms::double precision / 1000.0);
                END IF;
            EXCEPTION WHEN OTHERS THEN
                v_occurred_at := now();
            END;
        ELSIF (v_item ? 'occurred_at') AND NULLIF(btrim(v_item ->> 'occurred_at'), '') IS NOT NULL THEN
            BEGIN
                v_occurred_at := (v_item ->> 'occurred_at')::timestamptz;
            EXCEPTION WHEN OTHERS THEN
                v_occurred_at := now();
            END;
        END IF;

        INSERT INTO public.android_audit_events (
            audit_id,
            actor_id,
            actor_role,
            auth_user_id,
            device_id,
            operation,
            entity_type,
            entity_id,
            before_json,
            after_json,
            reason,
            occurred_at
        )
        VALUES (
            v_audit_id,
            v_actor_id,
            v_actor_role,
            v_uid,
            v_device_id,
            v_operation,
            v_entity_type,
            v_entity_id,
            v_before_json,
            v_after_json,
            v_reason,
            v_occurred_at
        )
        ON CONFLICT (audit_id) DO NOTHING;

        GET DIAGNOSTICS v_row_count = ROW_COUNT;
        IF v_row_count > 0 THEN
            v_accepted_count := v_accepted_count + 1;
        ELSE
            v_duplicate_count := v_duplicate_count + 1;
        END IF;

        v_confirmed_ids := v_confirmed_ids || jsonb_build_array(v_audit_id);
    END LOOP;

    RETURN jsonb_build_object(
        'status', 'COMMITTED',
        'accepted_count', v_accepted_count,
        'duplicate_count', v_duplicate_count,
        'confirmed_audit_ids', v_confirmed_ids
    );
END;
$$;

REVOKE ALL ON FUNCTION public.record_android_audit_events(jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.record_android_audit_events(jsonb) TO anon, authenticated, service_role;

-- Role-scoped read RPC:
-- Supervisors & Owner/Managers inspect store-wide audit events across all devices;
-- Cashiers only see their own authenticated user records.
CREATE OR REPLACE FUNCTION public.get_android_audit_events(
    p_limit integer DEFAULT 100
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid uuid;
    v_role text;
    v_bounded_limit integer;
    v_events jsonb;
BEGIN
    v_uid := auth.uid();
    IF v_uid IS NULL THEN
        RETURN jsonb_build_object(
            'status', 'UNAUTHORIZED_ROLE',
            'message', 'Staff sign-in is required to read cloud audit history.'
        );
    END IF;

    v_role := public.resolve_android_effective_role();
    IF v_role = 'DISABLED' THEN
        RETURN jsonb_build_object(
            'status', 'UNAUTHORIZED_ROLE',
            'message', 'Your Emofah staff account is deactivated.'
        );
    END IF;

    v_bounded_limit := LEAST(GREATEST(COALESCE(p_limit, 100), 1), 300);

    IF v_role IN ('SUPERVISOR', 'OWNER_MANAGER') THEN
        SELECT COALESCE(jsonb_agg(row_obj), '[]'::jsonb)
        INTO v_events
        FROM (
            SELECT jsonb_build_object(
                'audit_id', e.audit_id,
                'actor_id', e.actor_id,
                'actor_role', e.actor_role,
                'device_id', e.device_id,
                'operation', e.operation,
                'entity_type', e.entity_type,
                'entity_id', e.entity_id,
                'before_json', e.before_json,
                'after_json', e.after_json,
                'reason', e.reason,
                'occurred_at_epoch_ms', (extract(epoch FROM e.occurred_at) * 1000)::bigint,
                'synced_at', e.synced_at
            ) AS row_obj
            FROM public.android_audit_events e
            ORDER BY e.occurred_at DESC
            LIMIT v_bounded_limit
        ) sub;
    ELSE
        SELECT COALESCE(jsonb_agg(row_obj), '[]'::jsonb)
        INTO v_events
        FROM (
            SELECT jsonb_build_object(
                'audit_id', e.audit_id,
                'actor_id', e.actor_id,
                'actor_role', e.actor_role,
                'device_id', e.device_id,
                'operation', e.operation,
                'entity_type', e.entity_type,
                'entity_id', e.entity_id,
                'before_json', e.before_json,
                'after_json', e.after_json,
                'reason', e.reason,
                'occurred_at_epoch_ms', (extract(epoch FROM e.occurred_at) * 1000)::bigint,
                'synced_at', e.synced_at
            ) AS row_obj
            FROM public.android_audit_events e
            WHERE e.auth_user_id = v_uid
            ORDER BY e.occurred_at DESC
            LIMIT v_bounded_limit
        ) sub;
    END IF;

    RETURN jsonb_build_object(
        'status', 'OK',
        'scope', CASE WHEN v_role IN ('SUPERVISOR', 'OWNER_MANAGER') THEN 'STORE_WIDE' ELSE 'SELF_ONLY' END,
        'events', v_events
    );
END;
$$;

REVOKE ALL ON FUNCTION public.get_android_audit_events(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_android_audit_events(integer) TO authenticated, service_role;



