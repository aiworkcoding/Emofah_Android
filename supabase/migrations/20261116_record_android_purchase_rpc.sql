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
