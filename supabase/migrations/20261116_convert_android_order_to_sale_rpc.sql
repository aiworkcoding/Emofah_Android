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
