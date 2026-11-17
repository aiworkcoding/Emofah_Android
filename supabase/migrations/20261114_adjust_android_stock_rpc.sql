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
