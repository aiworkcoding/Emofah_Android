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
