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
