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
