-- =============================================================================
-- Emofah Agencies: Automatic Transaction Capture & Shared Classification
-- Migration: 20261122_shared_transaction_classification.sql
-- =============================================================================
-- Target workflow:
--   1. Android auto-captures financial events -> persisted in Supabase first as
--      PENDING_CLASSIFICATION (even before user categorizes).
--   2. Both Android & Desktop observe identical pending/classified transactions
--      via get_financial_transactions + Supabase Realtime (postgres_changes).
--   3. Either device can classify atomically once (FOR UPDATE + status guard +
--      idempotency key) into:
--        - BUSINESS_PRODUCT (link existing sale OR create new sale + deduct stock once)
--        - BUSINESS_SERVICE (create linked service operation with qty/price/discount, no duplicate payment)
--        - NON_BUSINESS (exclude from business balance while preserving original evidence & history)
--   4. Business balance derives strictly from classified business transactions
--      (PENDING_CLASSIFICATION and NON_BUSINESS contribute 0).
-- =============================================================================

-- 1. Extend android_financial_events with canonical classification & linkage columns
ALTER TABLE public.android_financial_events
    ADD COLUMN IF NOT EXISTS linked_sale_id integer,
    ADD COLUMN IF NOT EXISTS linked_client_sale_id text,
    ADD COLUMN IF NOT EXISTS linked_service_id uuid,
    ADD COLUMN IF NOT EXISTS linked_client_service_id text,
    ADD COLUMN IF NOT EXISTS linked_product_id bigint,
    ADD COLUMN IF NOT EXISTS linked_product_name text,
    ADD COLUMN IF NOT EXISTS linked_quantity integer,
    ADD COLUMN IF NOT EXISTS classification_note text,
    ADD COLUMN IF NOT EXISTS classified_at timestamptz,
    ADD COLUMN IF NOT EXISTS classified_by text,
    ADD COLUMN IF NOT EXISTS classified_device text,
    ADD COLUMN IF NOT EXISTS classification_idempotency_key text,
    ADD COLUMN IF NOT EXISTS excludes_from_balance boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

ALTER TABLE public.android_financial_events
    ALTER COLUMN classification_status SET DEFAULT 'PENDING_CLASSIFICATION';

-- Migrate legacy statuses cleanly without losing existing service links
UPDATE public.android_financial_events e
SET classification_status = 'BUSINESS_SERVICE',
    linked_client_service_id = COALESCE(e.linked_client_service_id, substring(e.client_event_id FROM 9)),
    classified_at = COALESCE(e.classified_at, e.occurred_at, now()),
    classified_by = COALESCE(e.classified_by, e.actor_id, 'android_staff'),
    classified_device = COALESCE(e.classified_device, e.device_id, 'android'),
    excludes_from_balance = false,
    updated_at = now()
WHERE e.source = 'ANDROID_SERVICE'
  AND e.classification_status IN ('PENDING_DESKTOP_RULES', 'UNCLASSIFIED', '');

UPDATE public.android_financial_events e
SET linked_service_id = s.id
FROM public.android_service_operations s
WHERE e.source = 'ANDROID_SERVICE'
  AND e.linked_service_id IS NULL
  AND e.linked_client_service_id = s.client_service_id;

UPDATE public.android_financial_events
SET classification_status = 'PENDING_CLASSIFICATION',
    category = CASE
        WHEN category IN ('PENDING_DESKTOP_RULES', 'UNCLASSIFIED', '') THEN 'PENDING_CLASSIFICATION'
        ELSE category
    END,
    updated_at = now()
WHERE classification_status IN ('PENDING_DESKTOP_RULES', 'UNCLASSIFIED', '')
   OR classification_status NOT IN ('PENDING_CLASSIFICATION', 'BUSINESS_PRODUCT', 'BUSINESS_SERVICE', 'NON_BUSINESS');

DO $$
BEGIN
    ALTER TABLE public.android_financial_events
        DROP CONSTRAINT IF EXISTS chk_android_financial_classification_status;
    ALTER TABLE public.android_financial_events
        ADD CONSTRAINT chk_android_financial_classification_status
        CHECK (classification_status IN ('PENDING_CLASSIFICATION', 'BUSINESS_PRODUCT', 'BUSINESS_SERVICE', 'NON_BUSINESS'));
END $$;

-- Partial unique indexes preventing two payments from claiming the same sale/service or replaying keys
CREATE UNIQUE INDEX IF NOT EXISTS idx_fin_events_unique_sale
    ON public.android_financial_events (linked_sale_id)
    WHERE linked_sale_id IS NOT NULL AND classification_status = 'BUSINESS_PRODUCT';

CREATE UNIQUE INDEX IF NOT EXISTS idx_fin_events_unique_service
    ON public.android_financial_events (linked_service_id)
    WHERE linked_service_id IS NOT NULL AND classification_status = 'BUSINESS_SERVICE';

CREATE UNIQUE INDEX IF NOT EXISTS idx_fin_events_unique_class_idem
    ON public.android_financial_events (classification_idempotency_key)
    WHERE classification_idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_fin_events_classification_status
    ON public.android_financial_events (classification_status, occurred_at DESC);

-- 2. Immutable Classification Audit History Table
CREATE TABLE IF NOT EXISTS public.android_financial_classification_history (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id uuid NOT NULL REFERENCES public.android_financial_events(id) ON DELETE RESTRICT,
    previous_status text,
    new_status text NOT NULL,
    action text NOT NULL,
    idempotency_key text UNIQUE,
    linked_product_id bigint,
    linked_sale_id integer,
    linked_service_id uuid,
    note text,
    actor_id text,
    device_id text,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_fin_class_history_event
    ON public.android_financial_classification_history (event_id, created_at DESC);

-- Prevent mutation or deletion of classification history rows (audit immutability)
CREATE OR REPLACE FUNCTION public.prevent_fin_classification_history_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'android_financial_classification_history records are immutable.';
END;
$$;

DROP TRIGGER IF EXISTS trg_fin_class_history_immutable ON public.android_financial_classification_history;
CREATE TRIGGER trg_fin_class_history_immutable
    BEFORE UPDATE OR DELETE ON public.android_financial_classification_history
    FOR EACH ROW EXECUTE FUNCTION public.prevent_fin_classification_history_mutation();

-- Protect captured source transaction columns from mutation/deletion while allowing classification updates
CREATE OR REPLACE FUNCTION public.protect_android_financial_event_source_columns()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Captured financial transactions cannot be deleted; use NON_BUSINESS classification instead.';
    END IF;

    IF NEW.amount IS DISTINCT FROM OLD.amount
       OR NEW.direction IS DISTINCT FROM OLD.direction
       OR NEW.reference_code IS DISTINCT FROM OLD.reference_code
       OR NEW.source IS DISTINCT FROM OLD.source
       OR NEW.raw_evidence IS DISTINCT FROM OLD.raw_evidence
       OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at THEN
        RAISE EXCEPTION 'Original captured transaction evidence (amount, direction, reference, source, raw_evidence, occurred_at) is immutable.';
    END IF;

    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_fin_events_protect_source ON public.android_financial_events;
CREATE TRIGGER trg_fin_events_protect_source
    BEFORE UPDATE OR DELETE ON public.android_financial_events
    FOR EACH ROW EXECUTE FUNCTION public.protect_android_financial_event_source_columns();

-- 3. RLS read access + Realtime publication for cross-device live updates
ALTER TABLE public.android_financial_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.android_financial_classification_history ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON public.android_financial_events FROM anon, authenticated;
REVOKE ALL ON public.android_financial_classification_history FROM anon, authenticated;
GRANT SELECT ON public.android_financial_events TO anon, authenticated;
GRANT SELECT ON public.android_financial_classification_history TO anon, authenticated;

DROP POLICY IF EXISTS "Allow read financial events for shared classification" ON public.android_financial_events;
CREATE POLICY "Allow read financial events for shared classification"
    ON public.android_financial_events
    FOR SELECT
    TO anon, authenticated
    USING (true);

DROP POLICY IF EXISTS "Allow read financial classification history" ON public.android_financial_classification_history;
CREATE POLICY "Allow read financial classification history"
    ON public.android_financial_classification_history
    FOR SELECT
    TO anon, authenticated
    USING (true);

DO $$
BEGIN
    ALTER PUBLICATION supabase_realtime ADD TABLE public.android_financial_events;
EXCEPTION WHEN duplicate_object THEN NULL;
          WHEN undefined_object THEN NULL;
END $$;

DO $$
BEGIN
    ALTER PUBLICATION supabase_realtime ADD TABLE public.android_financial_classification_history;
EXCEPTION WHEN duplicate_object THEN NULL;
          WHEN undefined_object THEN NULL;
END $$;

-- Ensure REPLICA IDENTITY FULL so Realtime payloads carry updated columns reliably
ALTER TABLE public.android_financial_events REPLICA IDENTITY FULL;

-- 4. Update record_android_financial_event to mark captured items as PENDING_CLASSIFICATION & log CAPTURED history
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
    v_existing_event public.android_financial_events%ROWTYPE;
    v_existing_event_id uuid;
    v_new_event_id uuid;
    v_clean_client_id text;
    v_clean_idem_key text;
    v_clean_ref text;
    v_clean_direction text;
    v_clean_event_type text;
    v_clean_actor text;
    v_clean_device text;
BEGIN
    v_clean_client_id := NULLIF(btrim(COALESCE(p_client_event_id, '')), '');
    v_clean_idem_key := NULLIF(btrim(COALESCE(p_idempotency_key, '')), '');
    v_clean_ref := NULLIF(upper(btrim(COALESCE(p_reference_code, ''))), '');
    v_clean_direction := upper(btrim(COALESCE(p_direction, '')));
    v_clean_event_type := upper(btrim(COALESCE(p_event_type, 'MPESA_EVENT')));
    v_clean_actor := COALESCE(NULLIF(btrim(COALESCE(p_actor_id, '')), ''), 'android_staff');
    v_clean_device := COALESCE(NULLIF(btrim(COALESCE(p_device_id, '')), ''), 'android');

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
        SELECT * INTO v_existing_event FROM public.android_financial_events WHERE id = v_existing_event_id LIMIT 1;
        RETURN jsonb_build_object(
            'status', 'DUPLICATE_IGNORED',
            'server_event_id', v_existing_event_id,
            'classification_status', COALESCE(v_existing_event.classification_status, 'PENDING_CLASSIFICATION'),
            'already_committed', true
        );
    END IF;

    -- 2. Duplicate check by client_event_id or canonical transaction reference_code
    SELECT *
    INTO v_existing_event
    FROM public.android_financial_events
    WHERE client_event_id = v_clean_client_id
       OR (v_clean_ref IS NOT NULL AND upper(reference_code) = v_clean_ref)
    LIMIT 1;

    IF FOUND THEN
        INSERT INTO public.android_financial_idempotency (idempotency_key, event_id)
        VALUES (v_clean_idem_key, v_existing_event.id)
        ON CONFLICT (idempotency_key) DO NOTHING;

        RETURN jsonb_build_object(
            'status', 'DUPLICATE_IGNORED',
            'server_event_id', v_existing_event.id,
            'classification_status', v_existing_event.classification_status,
            'already_committed', true
        );
    END IF;

    -- 3. Insert newly detected transaction immediately as PENDING_CLASSIFICATION
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
        'PENDING_CLASSIFICATION',
        'PENDING_CLASSIFICATION',
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
        v_clean_actor,
        v_clean_device,
        NULLIF(btrim(COALESCE(p_notes, '')), ''),
        COALESCE(p_occurred_at, now())
    )
    RETURNING id INTO v_new_event_id;

    INSERT INTO public.android_financial_idempotency (idempotency_key, event_id)
    VALUES (v_clean_idem_key, v_new_event_id)
    ON CONFLICT (idempotency_key) DO NOTHING;

    INSERT INTO public.android_financial_classification_history (
        event_id,
        previous_status,
        new_status,
        action,
        idempotency_key,
        note,
        actor_id,
        device_id,
        metadata
    )
    VALUES (
        v_new_event_id,
        NULL,
        'PENDING_CLASSIFICATION',
        'CAPTURED',
        'capture-' || v_clean_idem_key,
        COALESCE(NULLIF(btrim(COALESCE(p_notes, '')), ''), 'Automatically captured and queued for classification'),
        v_clean_actor,
        v_clean_device,
        jsonb_build_object(
            'source', COALESCE(NULLIF(btrim(p_source), ''), 'ANDROID'),
            'direction', v_clean_direction,
            'amount', p_amount,
            'reference_code', v_clean_ref
        )
    )
    ON CONFLICT (idempotency_key) DO NOTHING;

    RETURN jsonb_build_object(
        'status', 'COMMITTED',
        'server_event_id', v_new_event_id,
        'classification_status', 'PENDING_CLASSIFICATION',
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

-- 5. Update record_android_service_operation so standalone service rows are BUSINESS_SERVICE
--    and linked to their service operation instead of creating an unclassified duplicate pending item.
CREATE OR REPLACE FUNCTION public.record_android_service_operation(
    p_client_service_id text,
    p_idempotency_key text,
    p_service_type text,
    p_service_label text DEFAULT NULL,
    p_quantity_or_duration integer DEFAULT 1,
    p_unit_label text DEFAULT 'units',
    p_unit_price numeric DEFAULT 0,
    p_total_revenue numeric DEFAULT 0,
    p_payment_method text DEFAULT 'CASH',
    p_customer_note text DEFAULT NULL,
    p_consumables jsonb DEFAULT '[]'::jsonb,
    p_actor_id text DEFAULT 'android_staff',
    p_device_id text DEFAULT 'android',
    p_performed_at timestamptz DEFAULT now(),
    p_discount_amount numeric DEFAULT 0
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_existing public.android_service_idempotency%ROWTYPE;
    v_clean_client_id text;
    v_clean_idem_key text;
    v_clean_type text;
    v_clean_label text;
    v_clean_unit text;
    v_clean_pay text;
    v_clean_note text;
    v_clean_actor text;
    v_clean_device text;
    v_service_id uuid;
    v_item jsonb;
    v_cons_name text;
    v_usage_qty numeric;
    v_usage_unit text;
    v_linked_pid bigint;
    v_deduct_qty integer;
    v_current_qty integer;
    v_new_qty integer;
    v_updated_products jsonb := '[]'::jsonb;
    v_summary_parts text[] := ARRAY[]::text[];
    v_summary_text text;
    v_fin_client_id text;
    v_existing_fin uuid;
    v_new_fin_id uuid;
    v_clean_discount numeric;
    v_gross_amount numeric;
    v_effective_revenue numeric;
BEGIN
    v_clean_client_id := NULLIF(btrim(COALESCE(p_client_service_id, '')), '');
    v_clean_idem_key := NULLIF(btrim(COALESCE(p_idempotency_key, '')), '');
    v_clean_type := upper(btrim(COALESCE(p_service_type, 'OTHER_SERVICE')));
    v_clean_label := COALESCE(NULLIF(btrim(COALESCE(p_service_label, '')), ''), v_clean_type);
    v_clean_unit := COALESCE(NULLIF(btrim(COALESCE(p_unit_label, '')), ''), 'units');
    v_clean_pay := upper(btrim(COALESCE(p_payment_method, 'CASH')));
    v_clean_note := NULLIF(btrim(COALESCE(p_customer_note, '')), '');
    v_clean_actor := COALESCE(NULLIF(btrim(COALESCE(p_actor_id, '')), ''), 'android_staff');
    v_clean_device := COALESCE(NULLIF(btrim(COALESCE(p_device_id, '')), ''), 'android');
    v_clean_discount := GREATEST(COALESCE(p_discount_amount, 0), 0);

    IF v_clean_client_id IS NULL OR v_clean_idem_key IS NULL THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Stable client_service_id and idempotency_key are required.'
        );
    END IF;

    IF p_quantity_or_duration IS NULL OR p_quantity_or_duration <= 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Quantity or duration must be greater than zero.'
        );
    END IF;

    IF p_unit_price IS NULL OR p_unit_price < 0 OR p_total_revenue IS NULL OR p_total_revenue < 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Service prices and totals cannot be negative.'
        );
    END IF;

    v_gross_amount := COALESCE(p_quantity_or_duration, 1) * COALESCE(p_unit_price, 0);
    IF p_total_revenue > 0 THEN
        v_effective_revenue := p_total_revenue;
    ELSE
        v_effective_revenue := GREATEST(v_gross_amount - v_clean_discount, 0);
    END IF;

    IF v_clean_type NOT IN (
        'PRINTING', 'PHOTOCOPYING', 'SCANNING', 'INTERNET_BROWSING',
        'EGOV_ASSISTANCE', 'LAMINATION_BINDING', 'DOCUMENT_TYPING', 'OTHER_SERVICE'
    ) THEN
        v_clean_type := 'OTHER_SERVICE';
    END IF;

    IF v_clean_pay NOT IN ('CASH', 'MPESA', 'BANK') THEN
        v_clean_pay := 'CASH';
    END IF;

    SELECT *
    INTO v_existing
    FROM public.android_service_idempotency
    WHERE idempotency_key = v_clean_idem_key
       OR client_service_id = v_clean_client_id
    LIMIT 1;

    IF FOUND THEN
        RETURN jsonb_build_object(
            'status', 'DUPLICATE_IGNORED',
            'server_service_id', v_existing.server_service_id,
            'total_revenue', v_existing.total_revenue,
            'already_committed', true,
            'updated_products', '[]'::jsonb
        );
    END IF;

    INSERT INTO public.android_service_operations (
        client_service_id,
        service_type,
        service_label,
        quantity_or_duration,
        unit_label,
        unit_price,
        discount_amount,
        total_revenue,
        payment_method,
        customer_note,
        consumables_summary,
        actor_id,
        device_id,
        performed_at
    )
    VALUES (
        v_clean_client_id,
        v_clean_type,
        v_clean_label,
        p_quantity_or_duration,
        v_clean_unit,
        p_unit_price,
        v_clean_discount,
        v_effective_revenue,
        v_clean_pay,
        v_clean_note,
        NULL,
        v_clean_actor,
        v_clean_device,
        COALESCE(p_performed_at, now())
    )
    RETURNING id INTO v_service_id;

    INSERT INTO public.android_service_idempotency (
        idempotency_key,
        client_service_id,
        server_service_id,
        total_revenue
    )
    VALUES (
        v_clean_idem_key,
        v_clean_client_id,
        v_service_id,
        v_effective_revenue
    )
    ON CONFLICT (idempotency_key) DO NOTHING;

    IF p_consumables IS NOT NULL AND jsonb_typeof(p_consumables) = 'array' THEN
        FOR v_item IN SELECT * FROM jsonb_array_elements(p_consumables)
        LOOP
            v_cons_name := NULLIF(btrim(COALESCE(v_item ->> 'consumable_name', '')), '');
            IF v_cons_name IS NULL OR length(v_cons_name) < 2 THEN
                CONTINUE;
            END IF;

            v_usage_qty := GREATEST(COALESCE((v_item ->> 'usage_qty')::numeric, 1), 0);
            v_usage_unit := COALESCE(NULLIF(btrim(COALESCE(v_item ->> 'usage_unit', '')), ''), 'units');
            v_linked_pid := NULLIF(btrim(COALESCE(v_item ->> 'linked_product_id', '')), '')::bigint;
            v_deduct_qty := GREATEST(COALESCE((v_item ->> 'stock_deduct_qty')::integer, 0), 0);
            v_new_qty := NULL;

            IF v_linked_pid IS NOT NULL AND v_linked_pid > 0 AND v_deduct_qty > 0 THEN
                SELECT qty
                INTO v_current_qty
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
                            product_id, qty_change, movement_type, reference, notes, created_at
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
                service_id, consumable_name, usage_qty, usage_unit,
                linked_product_id, stock_deduct_qty, server_qty_after
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
                occurred_at,
                linked_service_id,
                linked_client_service_id,
                linked_quantity,
                classification_note,
                classified_at,
                classified_by,
                classified_device,
                classification_idempotency_key,
                excludes_from_balance
            )
            VALUES (
                v_fin_client_id,
                'SERVICE_REVENUE',
                'INFLOW',
                v_effective_revenue,
                'KES',
                'BUSINESS_SERVICE',
                'BUSINESS_SERVICE',
                NULL,
                v_clean_type,
                v_clean_label,
                format('%s: %s %s · %s', v_clean_label, p_quantity_or_duration, v_clean_unit, COALESCE(v_summary_text, 'No tracked consumables')),
                'VALID_PARSED',
                'ANDROID_SERVICE',
                v_clean_actor,
                v_clean_device,
                v_clean_note,
                COALESCE(p_performed_at, now()),
                v_service_id,
                v_clean_client_id,
                p_quantity_or_duration,
                v_clean_note,
                COALESCE(p_performed_at, now()),
                v_clean_actor,
                v_clean_device,
                'auto-svc-' || v_clean_idem_key,
                false
            )
            RETURNING id INTO v_new_fin_id;

            INSERT INTO public.android_financial_classification_history (
                event_id,
                previous_status,
                new_status,
                action,
                idempotency_key,
                linked_service_id,
                note,
                actor_id,
                device_id,
                metadata
            )
            VALUES (
                v_new_fin_id,
                NULL,
                'BUSINESS_SERVICE',
                'AUTO_CLASSIFIED_SERVICE',
                'auto-svc-' || v_clean_idem_key,
                v_service_id,
                v_clean_note,
                v_clean_actor,
                v_clean_device,
                jsonb_build_object(
                    'service_type', v_clean_type,
                    'service_label', v_clean_label,
                    'quantity', p_quantity_or_duration,
                    'discount_amount', v_clean_discount,
                    'total_revenue', v_effective_revenue
                )
            )
            ON CONFLICT (idempotency_key) DO NOTHING;
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
    text, text, text, text, integer, text, numeric, numeric, text, text, jsonb, text, text, timestamptz, numeric
) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.record_android_service_operation(
    text, text, text, text, integer, text, numeric, numeric, text, text, jsonb, text, text, timestamptz, numeric
) TO anon, authenticated, service_role;

-- 6. Helper function to serialize one financial event cleanly for RPC responses
CREATE OR REPLACE FUNCTION public._serialize_financial_event(p_event public.android_financial_events)
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT jsonb_build_object(
        'id', p_event.id,
        'client_event_id', p_event.client_event_id,
        'event_type', p_event.event_type,
        'direction', p_event.direction,
        'amount', p_event.amount,
        'currency', p_event.currency,
        'category', p_event.category,
        'classification_status', p_event.classification_status,
        'reference_code', p_event.reference_code,
        'channel', p_event.channel,
        'counterparty', p_event.counterparty,
        'counterparty_account', p_event.counterparty_account,
        'transaction_cost', p_event.transaction_cost,
        'new_balance', p_event.new_balance,
        'occurred_text', p_event.occurred_text,
        'raw_evidence', p_event.raw_evidence,
        'parsed_metadata', p_event.parsed_metadata,
        'parse_status', p_event.parse_status,
        'source', p_event.source,
        'actor_id', p_event.actor_id,
        'device_id', p_event.device_id,
        'notes', p_event.notes,
        'occurred_at', p_event.occurred_at,
        'created_at', p_event.created_at,
        'updated_at', p_event.updated_at,
        'linked_sale_id', p_event.linked_sale_id,
        'linked_client_sale_id', p_event.linked_client_sale_id,
        'linked_service_id', p_event.linked_service_id,
        'linked_client_service_id', p_event.linked_client_service_id,
        'linked_product_id', p_event.linked_product_id,
        'linked_product_name', p_event.linked_product_name,
        'linked_quantity', p_event.linked_quantity,
        'classification_note', p_event.classification_note,
        'classified_at', p_event.classified_at,
        'classified_by', p_event.classified_by,
        'classified_device', p_event.classified_device,
        'excludes_from_balance', p_event.excludes_from_balance
    );
$$;

-- 7. Classify captured transaction as BUSINESS_PRODUCT
--    Supports associating an existing sale (p_existing_sale_id) without creating duplicate sales/stock deductions,
--    OR creating a single new sale + stock deduction atomically.
CREATE OR REPLACE FUNCTION public.classify_financial_transaction_as_product(
    p_event_id uuid,
    p_classification_idempotency_key text,
    p_product_id bigint DEFAULT NULL,
    p_quantity integer DEFAULT 1,
    p_existing_sale_id integer DEFAULT NULL,
    p_unit_sell_price numeric DEFAULT NULL,
    p_payment_method text DEFAULT 'MPESA',
    p_note text DEFAULT NULL,
    p_actor_id text DEFAULT 'desktop_staff',
    p_device_id text DEFAULT 'desktop'
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_event public.android_financial_events%ROWTYPE;
    v_clean_idem text;
    v_clean_actor text;
    v_clean_device text;
    v_clean_note text;
    v_clean_pay text;
    v_product public.products%ROWTYPE;
    v_existing_sale public.sales%ROWTYPE;
    v_already_claimed_event uuid;
    v_qty integer;
    v_sale_id integer;
    v_client_sale_id text;
    v_remaining_qty integer;
    v_sale_total numeric;
    v_unit_price numeric;
    v_now timestamptz := now();
    v_updated public.android_financial_events%ROWTYPE;
BEGIN
    v_clean_idem := NULLIF(btrim(COALESCE(p_classification_idempotency_key, '')), '');
    v_clean_actor := COALESCE(NULLIF(btrim(COALESCE(p_actor_id, '')), ''), 'desktop_staff');
    v_clean_device := COALESCE(NULLIF(btrim(COALESCE(p_device_id, '')), ''), 'desktop');
    v_clean_note := NULLIF(btrim(COALESCE(p_note, '')), '');
    v_clean_pay := upper(btrim(COALESCE(p_payment_method, 'MPESA')));
    IF v_clean_pay NOT IN ('CASH', 'MPESA', 'BANK') THEN
        v_clean_pay := 'MPESA';
    END IF;

    IF p_event_id IS NULL OR v_clean_idem IS NULL THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Event ID and classification_idempotency_key are required.'
        );
    END IF;

    SELECT *
    INTO v_event
    FROM public.android_financial_events
    WHERE id = p_event_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object(
            'status', 'NOT_FOUND',
            'message', 'Captured financial transaction was not found.'
        );
    END IF;

    -- Idempotent replay check
    IF v_event.classification_idempotency_key = v_clean_idem
       OR EXISTS (
           SELECT 1 FROM public.android_financial_classification_history
           WHERE idempotency_key = v_clean_idem
       ) THEN
        RETURN jsonb_build_object(
            'status', 'DUPLICATE_IGNORED',
            'already_classified', true,
            'event', public._serialize_financial_event(v_event)
        );
    END IF;

    -- Concurrency guard: only PENDING_CLASSIFICATION transactions can transition cleanly
    IF v_event.classification_status <> 'PENDING_CLASSIFICATION' THEN
        RETURN jsonb_build_object(
            'status', 'ALREADY_CLASSIFIED',
            'current_status', v_event.classification_status,
            'classified_by', v_event.classified_by,
            'classified_device', v_event.classified_device,
            'message', format('Transaction was already classified as %s by %s.', v_event.classification_status, COALESCE(v_event.classified_device, 'another device')),
            'event', public._serialize_financial_event(v_event)
        );
    END IF;

    IF p_existing_sale_id IS NOT NULL THEN
        SELECT *
        INTO v_existing_sale
        FROM public.sales
        WHERE id = p_existing_sale_id
        FOR UPDATE;

        IF NOT FOUND THEN
            RETURN jsonb_build_object(
                'status', 'SALE_NOT_FOUND',
                'message', format('Existing sale #%s was not found.', p_existing_sale_id)
            );
        END IF;

        IF p_product_id IS NOT NULL AND v_existing_sale.product_id <> p_product_id::integer THEN
            RETURN jsonb_build_object(
                'status', 'INVALID_INPUT',
                'message', format('Existing sale #%s belongs to another product.', p_existing_sale_id)
            );
        END IF;

        SELECT id
        INTO v_already_claimed_event
        FROM public.android_financial_events
        WHERE linked_sale_id = p_existing_sale_id
          AND classification_status = 'BUSINESS_PRODUCT'
          AND id <> p_event_id
        LIMIT 1;

        IF v_already_claimed_event IS NOT NULL THEN
            RETURN jsonb_build_object(
                'status', 'SALE_ALREADY_LINKED',
                'message', format('Sale #%s is already linked to another payment transaction.', p_existing_sale_id)
            );
        END IF;

        SELECT * INTO v_product FROM public.products WHERE id = v_existing_sale.product_id LIMIT 1;
        v_sale_id := v_existing_sale.id;
        v_qty := COALESCE(v_existing_sale.qty_sold, GREATEST(COALESCE(p_quantity, 1), 1));
        v_remaining_qty := v_product.qty;
        SELECT client_sale_id
        INTO v_client_sale_id
        FROM public.android_sale_idempotency
        WHERE server_sale_id = v_sale_id
        LIMIT 1;
    ELSE
        IF p_product_id IS NULL OR p_product_id <= 0 THEN
            RETURN jsonb_build_object(
                'status', 'INVALID_INPUT',
                'message', 'Select a product or existing sale to link this transaction.'
            );
        END IF;

        v_qty := COALESCE(p_quantity, 1);
        IF v_qty <= 0 THEN
            RETURN jsonb_build_object(
                'status', 'INVALID_INPUT',
                'message', 'Quantity must be at least 1.'
            );
        END IF;

        SELECT *
        INTO v_product
        FROM public.products
        WHERE id = p_product_id
        FOR UPDATE;

        IF NOT FOUND THEN
            RETURN jsonb_build_object(
                'status', 'PRODUCT_NOT_FOUND',
                'message', format('Product #%s no longer exists in the catalogue.', p_product_id)
            );
        END IF;

        IF COALESCE(v_product.qty, 0) < v_qty THEN
            RETURN jsonb_build_object(
                'status', 'INSUFFICIENT_STOCK',
                'available_qty', COALESCE(v_product.qty, 0),
                'message', format('Insufficient stock for %s: requested %s, available %s.', v_product.name, v_qty, COALESCE(v_product.qty, 0))
            );
        END IF;

        v_unit_price := CASE
            WHEN p_unit_sell_price IS NOT NULL AND p_unit_sell_price > 0 THEN p_unit_sell_price
            WHEN COALESCE(v_product.sell_price, 0) > 0 THEN v_product.sell_price
            ELSE GREATEST(v_event.amount / v_qty, 0.01)
        END;
        v_sale_total := CASE
            WHEN p_unit_sell_price IS NOT NULL AND p_unit_sell_price > 0 THEN round((p_unit_sell_price * v_qty)::numeric, 2)
            ELSE v_event.amount
        END;

        INSERT INTO public.sales (product_id, qty_sold, total_price, sale_date)
        VALUES (p_product_id::integer, v_qty, v_sale_total, COALESCE(v_event.occurred_at, v_now))
        RETURNING id INTO v_sale_id;

        UPDATE public.products
        SET qty = qty - v_qty
        WHERE id = p_product_id
        RETURNING qty INTO v_remaining_qty;

        v_client_sale_id := 'cls-sale-' || v_event.id::text;
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
            'sale-' || v_clean_idem,
            v_client_sale_id,
            v_sale_id,
            p_product_id::integer,
            v_qty,
            v_sale_total,
            v_clean_pay,
            v_clean_actor,
            v_clean_device,
            CASE WHEN lower(v_clean_device) LIKE '%desktop%' THEN 'DESKTOP_CLASSIFICATION' ELSE 'ANDROID_CLASSIFICATION' END,
            v_remaining_qty,
            COALESCE(v_event.occurred_at, v_now),
            round((v_sale_total / v_qty)::numeric, 2),
            v_product.sell_price,
            v_clean_note,
            'STAFF'
        )
        ON CONFLICT (idempotency_key) DO NOTHING;
    END IF;

    UPDATE public.android_financial_events
    SET classification_status = 'BUSINESS_PRODUCT',
        category = 'BUSINESS_PRODUCT',
        linked_sale_id = v_sale_id,
        linked_client_sale_id = v_client_sale_id,
        linked_product_id = v_product.id,
        linked_product_name = v_product.name,
        linked_quantity = v_qty,
        classification_note = v_clean_note,
        classified_at = v_now,
        classified_by = v_clean_actor,
        classified_device = v_clean_device,
        classification_idempotency_key = v_clean_idem,
        excludes_from_balance = false
    WHERE id = p_event_id
    RETURNING * INTO v_updated;

    INSERT INTO public.android_financial_classification_history (
        event_id,
        previous_status,
        new_status,
        action,
        idempotency_key,
        linked_product_id,
        linked_sale_id,
        note,
        actor_id,
        device_id,
        metadata
    )
    VALUES (
        p_event_id,
        v_event.classification_status,
        'BUSINESS_PRODUCT',
        CASE WHEN p_existing_sale_id IS NOT NULL THEN 'LINKED_EXISTING_SALE' ELSE 'CREATED_PRODUCT_SALE' END,
        v_clean_idem,
        v_product.id,
        v_sale_id,
        v_clean_note,
        v_clean_actor,
        v_clean_device,
        jsonb_build_object(
            'product_name', v_product.name,
            'quantity', v_qty,
            'existing_sale_linked', p_existing_sale_id IS NOT NULL,
            'remaining_qty', v_remaining_qty
        )
    );

    RETURN jsonb_build_object(
        'status', 'COMMITTED',
        'already_classified', false,
        'created_new_sale', p_existing_sale_id IS NULL,
        'sale_id', v_sale_id,
        'remaining_qty', v_remaining_qty,
        'event', public._serialize_financial_event(v_updated)
    );
END;
$$;

REVOKE ALL ON FUNCTION public.classify_financial_transaction_as_product(
    uuid, text, bigint, integer, integer, numeric, text, text, text, text
) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.classify_financial_transaction_as_product(
    uuid, text, bigint, integer, integer, numeric, text, text, text, text
) TO anon, authenticated, service_role;

-- 8. Classify captured transaction as BUSINESS_SERVICE
--    Creates linked android_service_operations (+ consumables) without creating a duplicate payment event.
CREATE OR REPLACE FUNCTION public.classify_financial_transaction_as_service(
    p_event_id uuid,
    p_classification_idempotency_key text,
    p_service_type text,
    p_service_label text DEFAULT NULL,
    p_quantity_or_duration integer DEFAULT 1,
    p_unit_label text DEFAULT 'units',
    p_unit_price numeric DEFAULT NULL,
    p_discount_amount numeric DEFAULT 0,
    p_consumables jsonb DEFAULT '[]'::jsonb,
    p_note text DEFAULT NULL,
    p_actor_id text DEFAULT 'desktop_staff',
    p_device_id text DEFAULT 'desktop'
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_event public.android_financial_events%ROWTYPE;
    v_updated public.android_financial_events%ROWTYPE;
    v_clean_idem text;
    v_clean_actor text;
    v_clean_device text;
    v_clean_note text;
    v_clean_type text;
    v_clean_label text;
    v_clean_unit text;
    v_qty integer;
    v_discount numeric;
    v_unit_price numeric;
    v_total_revenue numeric;
    v_client_service_id text;
    v_service_id uuid;
    v_pay_method text;
    v_now timestamptz := now();
    v_item jsonb;
    v_cons_name text;
    v_usage_qty numeric;
    v_usage_unit text;
    v_linked_pid bigint;
    v_deduct_qty integer;
    v_current_qty integer;
    v_new_qty integer;
    v_summary_parts text[] := ARRAY[]::text[];
    v_summary_text text;
BEGIN
    v_clean_idem := NULLIF(btrim(COALESCE(p_classification_idempotency_key, '')), '');
    v_clean_actor := COALESCE(NULLIF(btrim(COALESCE(p_actor_id, '')), ''), 'desktop_staff');
    v_clean_device := COALESCE(NULLIF(btrim(COALESCE(p_device_id, '')), ''), 'desktop');
    v_clean_note := NULLIF(btrim(COALESCE(p_note, '')), '');
    v_clean_type := upper(btrim(COALESCE(p_service_type, 'OTHER_SERVICE')));
    IF v_clean_type NOT IN (
        'PRINTING', 'PHOTOCOPYING', 'SCANNING', 'INTERNET_BROWSING',
        'EGOV_ASSISTANCE', 'LAMINATION_BINDING', 'DOCUMENT_TYPING', 'OTHER_SERVICE'
    ) THEN
        v_clean_type := 'OTHER_SERVICE';
    END IF;
    v_clean_label := COALESCE(NULLIF(btrim(COALESCE(p_service_label, '')), ''), initcap(replace(v_clean_type, '_', ' ')));
    v_clean_unit := COALESCE(NULLIF(btrim(COALESCE(p_unit_label, '')), ''), 'units');
    v_qty := GREATEST(COALESCE(p_quantity_or_duration, 1), 1);
    v_discount := GREATEST(COALESCE(p_discount_amount, 0), 0);

    IF p_event_id IS NULL OR v_clean_idem IS NULL THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Event ID and classification_idempotency_key are required.'
        );
    END IF;

    SELECT *
    INTO v_event
    FROM public.android_financial_events
    WHERE id = p_event_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object(
            'status', 'NOT_FOUND',
            'message', 'Captured financial transaction was not found.'
        );
    END IF;

    IF v_event.classification_idempotency_key = v_clean_idem
       OR EXISTS (
           SELECT 1 FROM public.android_financial_classification_history
           WHERE idempotency_key = v_clean_idem
       ) THEN
        RETURN jsonb_build_object(
            'status', 'DUPLICATE_IGNORED',
            'already_classified', true,
            'event', public._serialize_financial_event(v_event)
        );
    END IF;

    IF v_event.classification_status <> 'PENDING_CLASSIFICATION' THEN
        RETURN jsonb_build_object(
            'status', 'ALREADY_CLASSIFIED',
            'current_status', v_event.classification_status,
            'classified_by', v_event.classified_by,
            'classified_device', v_event.classified_device,
            'message', format('Transaction was already classified as %s by %s.', v_event.classification_status, COALESCE(v_event.classified_device, 'another device')),
            'event', public._serialize_financial_event(v_event)
        );
    END IF;

    IF p_unit_price IS NOT NULL AND p_unit_price > 0 THEN
        v_unit_price := p_unit_price;
        v_total_revenue := GREATEST(round(((v_unit_price * v_qty) - v_discount)::numeric, 2), 0);
    ELSE
        v_total_revenue := GREATEST(round((v_event.amount - v_discount)::numeric, 2), 0);
        v_unit_price := round(((v_total_revenue + v_discount) / v_qty)::numeric, 2);
    END IF;

    v_pay_method := CASE
        WHEN upper(COALESCE(v_event.source, '')) LIKE '%MPESA%' OR upper(COALESCE(v_event.event_type, '')) LIKE '%MPESA%' THEN 'MPESA'
        WHEN upper(COALESCE(v_event.event_type, '')) LIKE '%BANK%' THEN 'BANK'
        ELSE 'CASH'
    END;

    v_client_service_id := 'cls-svc-' || v_event.id::text;

    INSERT INTO public.android_service_operations (
        client_service_id,
        service_type,
        service_label,
        quantity_or_duration,
        unit_label,
        unit_price,
        discount_amount,
        total_revenue,
        payment_method,
        customer_note,
        consumables_summary,
        actor_id,
        device_id,
        performed_at
    )
    VALUES (
        v_client_service_id,
        v_clean_type,
        v_clean_label,
        v_qty,
        v_clean_unit,
        v_unit_price,
        v_discount,
        v_total_revenue,
        v_pay_method,
        v_clean_note,
        NULL,
        v_clean_actor,
        v_clean_device,
        COALESCE(v_event.occurred_at, v_now)
    )
    ON CONFLICT (client_service_id) DO UPDATE
    SET discount_amount = EXCLUDED.discount_amount,
        total_revenue = EXCLUDED.total_revenue
    RETURNING id INTO v_service_id;

    INSERT INTO public.android_service_idempotency (
        idempotency_key,
        client_service_id,
        server_service_id,
        total_revenue
    )
    VALUES (
        'svc-' || v_clean_idem,
        v_client_service_id,
        v_service_id,
        v_total_revenue
    )
    ON CONFLICT (idempotency_key) DO NOTHING;

    IF p_consumables IS NOT NULL AND jsonb_typeof(p_consumables) = 'array' THEN
        FOR v_item IN SELECT * FROM jsonb_array_elements(p_consumables)
        LOOP
            v_cons_name := NULLIF(btrim(COALESCE(v_item ->> 'consumable_name', '')), '');
            IF v_cons_name IS NULL OR length(v_cons_name) < 2 THEN
                CONTINUE;
            END IF;
            v_usage_qty := GREATEST(COALESCE((v_item ->> 'usage_qty')::numeric, 1), 0);
            v_usage_unit := COALESCE(NULLIF(btrim(COALESCE(v_item ->> 'usage_unit', '')), ''), 'units');
            v_linked_pid := NULLIF(btrim(COALESCE(v_item ->> 'linked_product_id', '')), '')::bigint;
            v_deduct_qty := GREATEST(COALESCE((v_item ->> 'stock_deduct_qty')::integer, 0), 0);
            v_new_qty := NULL;

            IF v_linked_pid IS NOT NULL AND v_linked_pid > 0 AND v_deduct_qty > 0 THEN
                SELECT qty INTO v_current_qty FROM public.products WHERE id = v_linked_pid FOR UPDATE;
                IF FOUND THEN
                    UPDATE public.products
                    SET qty = GREATEST(COALESCE(qty, 0) - v_deduct_qty, 0)
                    WHERE id = v_linked_pid
                    RETURNING qty INTO v_new_qty;
                END IF;
            END IF;

            INSERT INTO public.android_service_consumables (
                service_id, consumable_name, usage_qty, usage_unit,
                linked_product_id, stock_deduct_qty, server_qty_after
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

            v_summary_parts := array_append(v_summary_parts, format('%s (%s %s)', v_cons_name, v_usage_qty, v_usage_unit));
        END LOOP;
    END IF;

    v_summary_text := NULLIF(array_to_string(v_summary_parts, ', '), '');
    IF v_summary_text IS NOT NULL THEN
        UPDATE public.android_service_operations
        SET consumables_summary = v_summary_text
        WHERE id = v_service_id;
    END IF;

    UPDATE public.android_financial_events
    SET classification_status = 'BUSINESS_SERVICE',
        category = 'BUSINESS_SERVICE',
        linked_service_id = v_service_id,
        linked_client_service_id = v_client_service_id,
        linked_product_name = v_clean_label,
        linked_quantity = v_qty,
        classification_note = v_clean_note,
        classified_at = v_now,
        classified_by = v_clean_actor,
        classified_device = v_clean_device,
        classification_idempotency_key = v_clean_idem,
        excludes_from_balance = false
    WHERE id = p_event_id
    RETURNING * INTO v_updated;

    INSERT INTO public.android_financial_classification_history (
        event_id,
        previous_status,
        new_status,
        action,
        idempotency_key,
        linked_service_id,
        note,
        actor_id,
        device_id,
        metadata
    )
    VALUES (
        p_event_id,
        v_event.classification_status,
        'BUSINESS_SERVICE',
        'LINKED_SERVICE',
        v_clean_idem,
        v_service_id,
        v_clean_note,
        v_clean_actor,
        v_clean_device,
        jsonb_build_object(
            'service_type', v_clean_type,
            'service_label', v_clean_label,
            'quantity', v_qty,
            'unit_price', v_unit_price,
            'discount_amount', v_discount,
            'net_service_revenue', v_total_revenue
        )
    );

    RETURN jsonb_build_object(
        'status', 'COMMITTED',
        'already_classified', false,
        'service_id', v_service_id,
        'total_revenue', v_total_revenue,
        'event', public._serialize_financial_event(v_updated)
    );
END;
$$;

REVOKE ALL ON FUNCTION public.classify_financial_transaction_as_service(
    uuid, text, text, text, integer, text, numeric, numeric, jsonb, text, text, text
) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.classify_financial_transaction_as_service(
    uuid, text, text, text, integer, text, numeric, numeric, jsonb, text, text, text
) TO anon, authenticated, service_role;

-- 9. Classify captured transaction as NON_BUSINESS (Remove from business calculations while preserving original record & audit history)
CREATE OR REPLACE FUNCTION public.classify_financial_transaction_as_non_business(
    p_event_id uuid,
    p_classification_idempotency_key text,
    p_reason text DEFAULT NULL,
    p_actor_id text DEFAULT 'desktop_staff',
    p_device_id text DEFAULT 'desktop'
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_event public.android_financial_events%ROWTYPE;
    v_updated public.android_financial_events%ROWTYPE;
    v_clean_idem text;
    v_clean_reason text;
    v_clean_actor text;
    v_clean_device text;
    v_now timestamptz := now();
BEGIN
    v_clean_idem := NULLIF(btrim(COALESCE(p_classification_idempotency_key, '')), '');
    v_clean_reason := COALESCE(NULLIF(btrim(COALESCE(p_reason, '')), ''), 'Marked personal / non-business');
    v_clean_actor := COALESCE(NULLIF(btrim(COALESCE(p_actor_id, '')), ''), 'desktop_staff');
    v_clean_device := COALESCE(NULLIF(btrim(COALESCE(p_device_id, '')), ''), 'desktop');

    IF p_event_id IS NULL OR v_clean_idem IS NULL THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Event ID and classification_idempotency_key are required.'
        );
    END IF;

    SELECT *
    INTO v_event
    FROM public.android_financial_events
    WHERE id = p_event_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object(
            'status', 'NOT_FOUND',
            'message', 'Captured financial transaction was not found.'
        );
    END IF;

    IF v_event.classification_idempotency_key = v_clean_idem
       OR EXISTS (
           SELECT 1 FROM public.android_financial_classification_history
           WHERE idempotency_key = v_clean_idem
       ) THEN
        RETURN jsonb_build_object(
            'status', 'DUPLICATE_IGNORED',
            'already_classified', true,
            'event', public._serialize_financial_event(v_event)
        );
    END IF;

    IF v_event.classification_status <> 'PENDING_CLASSIFICATION' THEN
        RETURN jsonb_build_object(
            'status', 'ALREADY_CLASSIFIED',
            'current_status', v_event.classification_status,
            'classified_by', v_event.classified_by,
            'classified_device', v_event.classified_device,
            'message', format('Transaction was already classified as %s by %s.', v_event.classification_status, COALESCE(v_event.classified_device, 'another device')),
            'event', public._serialize_financial_event(v_event)
        );
    END IF;

    UPDATE public.android_financial_events
    SET classification_status = 'NON_BUSINESS',
        category = 'NON_BUSINESS',
        classification_note = v_clean_reason,
        classified_at = v_now,
        classified_by = v_clean_actor,
        classified_device = v_clean_device,
        classification_idempotency_key = v_clean_idem,
        excludes_from_balance = true
    WHERE id = p_event_id
    RETURNING * INTO v_updated;

    INSERT INTO public.android_financial_classification_history (
        event_id,
        previous_status,
        new_status,
        action,
        idempotency_key,
        note,
        actor_id,
        device_id,
        metadata
    )
    VALUES (
        p_event_id,
        v_event.classification_status,
        'NON_BUSINESS',
        'REMOVED_FROM_BUSINESS',
        v_clean_idem,
        v_clean_reason,
        v_clean_actor,
        v_clean_device,
        jsonb_build_object(
            'original_preserved', true,
            'excludes_from_balance', true,
            'amount', v_event.amount,
            'reference_code', v_event.reference_code
        )
    );

    RETURN jsonb_build_object(
        'status', 'COMMITTED',
        'already_classified', false,
        'event', public._serialize_financial_event(v_updated)
    );
END;
$$;

REVOKE ALL ON FUNCTION public.classify_financial_transaction_as_non_business(
    uuid, text, text, text, text
) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.classify_financial_transaction_as_non_business(
    uuid, text, text, text, text
) TO anon, authenticated, service_role;

-- 10. Query shared financial transactions, unlinked recent sales, classification history,
--     and authoritative business balance (strictly excluding PENDING_CLASSIFICATION & NON_BUSINESS).
CREATE OR REPLACE FUNCTION public.get_financial_transactions(
    p_limit integer DEFAULT 120,
    p_status_filter text DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_bounded_limit integer;
    v_clean_filter text;
    v_transactions jsonb;
    v_recent_sales jsonb;
    v_history jsonb;
    v_summary jsonb;
BEGIN
    v_bounded_limit := LEAST(GREATEST(COALESCE(p_limit, 120), 1), 400);
    v_clean_filter := NULLIF(upper(btrim(COALESCE(p_status_filter, ''))), '');

    SELECT COALESCE(jsonb_agg(tx_obj), '[]'::jsonb)
    INTO v_transactions
    FROM (
        SELECT
            public._serialize_financial_event(e)
            || jsonb_build_object(
                'service_unit_price', svc.unit_price,
                'service_discount_amount', svc.discount_amount,
                'service_total_revenue', svc.total_revenue,
                'service_type', svc.service_type,
                'service_label', svc.service_label,
                'sale_total_price', sl.total_price,
                'sale_qty_sold', sl.qty_sold
            ) AS tx_obj
        FROM public.android_financial_events e
        LEFT JOIN public.android_service_operations svc
            ON svc.id = e.linked_service_id
        LEFT JOIN public.sales sl
            ON sl.id = e.linked_sale_id
        WHERE v_clean_filter IS NULL OR e.classification_status = v_clean_filter
        ORDER BY
            CASE WHEN e.classification_status = 'PENDING_CLASSIFICATION' THEN 0 ELSE 1 END,
            e.occurred_at DESC
        LIMIT v_bounded_limit
    ) sub;

    SELECT COALESCE(jsonb_agg(sale_obj), '[]'::jsonb)
    INTO v_recent_sales
    FROM (
        SELECT jsonb_build_object(
            'sale_id', s.id,
            'product_id', s.product_id,
            'product_name', COALESCE(p.name, format('Product #%s', s.product_id)),
            'qty_sold', s.qty_sold,
            'total_price', s.total_price,
            'sale_date', s.sale_date,
            'already_linked_event_id', fe.id
        ) AS sale_obj
        FROM public.sales s
        LEFT JOIN public.products p ON p.id = s.product_id
        LEFT JOIN public.android_financial_events fe
            ON fe.linked_sale_id = s.id AND fe.classification_status = 'BUSINESS_PRODUCT'
        ORDER BY s.sale_date DESC, s.id DESC
        LIMIT 60
    ) sub_sales;

    SELECT COALESCE(jsonb_agg(hist_obj), '[]'::jsonb)
    INTO v_history
    FROM (
        SELECT jsonb_build_object(
            'id', h.id,
            'event_id', h.event_id,
            'previous_status', h.previous_status,
            'new_status', h.new_status,
            'action', h.action,
            'linked_product_id', h.linked_product_id,
            'linked_sale_id', h.linked_sale_id,
            'linked_service_id', h.linked_service_id,
            'note', h.note,
            'actor_id', h.actor_id,
            'device_id', h.device_id,
            'metadata', h.metadata,
            'created_at', h.created_at
        ) AS hist_obj
        FROM public.android_financial_classification_history h
        ORDER BY h.created_at DESC
        LIMIT 120
    ) sub_hist;

    SELECT jsonb_build_object(
        'pending_count', COUNT(*) FILTER (WHERE e.classification_status = 'PENDING_CLASSIFICATION'),
        'pending_amount', COALESCE(SUM(e.amount) FILTER (WHERE e.classification_status = 'PENDING_CLASSIFICATION'), 0),
        'business_product_count', COUNT(*) FILTER (WHERE e.classification_status = 'BUSINESS_PRODUCT'),
        'business_product_net', COALESCE(SUM(
            CASE
                WHEN e.classification_status <> 'BUSINESS_PRODUCT' OR e.excludes_from_balance THEN 0
                WHEN upper(e.direction) = 'INFLOW' THEN COALESCE(sl.total_price, e.amount) - COALESCE(e.transaction_cost, 0)
                WHEN upper(e.direction) = 'OUTFLOW' THEN -(e.amount + COALESCE(e.transaction_cost, 0))
                ELSE 0
            END
        ), 0),
        'business_service_count', COUNT(*) FILTER (WHERE e.classification_status = 'BUSINESS_SERVICE'),
        'business_service_net', COALESCE(SUM(
            CASE
                WHEN e.classification_status <> 'BUSINESS_SERVICE' OR e.excludes_from_balance THEN 0
                WHEN upper(e.direction) = 'INFLOW' THEN COALESCE(svc.total_revenue, e.amount) - COALESCE(e.transaction_cost, 0)
                WHEN upper(e.direction) = 'OUTFLOW' THEN -(e.amount + COALESCE(e.transaction_cost, 0))
                ELSE 0
            END
        ), 0),
        'non_business_count', COUNT(*) FILTER (WHERE e.classification_status = 'NON_BUSINESS'),
        'non_business_excluded_amount', COALESCE(SUM(e.amount) FILTER (WHERE e.classification_status = 'NON_BUSINESS'), 0),
        'business_balance', COALESCE(SUM(
            CASE
                WHEN e.classification_status NOT IN ('BUSINESS_PRODUCT', 'BUSINESS_SERVICE') OR e.excludes_from_balance THEN 0
                WHEN upper(e.direction) = 'INFLOW' THEN
                    CASE
                        WHEN e.classification_status = 'BUSINESS_SERVICE' THEN COALESCE(svc.total_revenue, e.amount)
                        WHEN e.classification_status = 'BUSINESS_PRODUCT' THEN COALESCE(sl.total_price, e.amount)
                        ELSE e.amount
                    END - COALESCE(e.transaction_cost, 0)
                WHEN upper(e.direction) = 'OUTFLOW' THEN -(e.amount + COALESCE(e.transaction_cost, 0))
                ELSE 0
            END
        ), 0)
    )
    INTO v_summary
    FROM public.android_financial_events e
    LEFT JOIN public.android_service_operations svc ON svc.id = e.linked_service_id
    LEFT JOIN public.sales sl ON sl.id = e.linked_sale_id;

    RETURN jsonb_build_object(
        'status', 'OK',
        'transactions', v_transactions,
        'recent_sales', v_recent_sales,
        'history', v_history,
        'summary', v_summary
    );
END;
$$;

REVOKE ALL ON FUNCTION public.get_financial_transactions(integer, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_financial_transactions(integer, text) TO anon, authenticated, service_role;
