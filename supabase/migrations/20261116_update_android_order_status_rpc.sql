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
