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
