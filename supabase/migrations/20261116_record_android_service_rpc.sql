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
