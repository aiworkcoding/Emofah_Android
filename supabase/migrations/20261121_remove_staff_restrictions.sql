-- Emofah Agencies — Remove Staff Profile & Role Restrictions
-- Non-destructive & idempotent:
-- 1. Eliminates initial-admin bootstrap lockouts (`is_initial_admin_bootstrap_needed` -> false).
-- 2. Grants every staff member full operational capabilities while keeping actor/device/email attribution.
-- 3. Ensures `resolve_android_effective_role()` never returns 'DISABLED' and never blocks operations on role hierarchy.
-- 4. Removes CASHIER/SUPERVISOR/OWNER_MANAGER and account-deactivation gates across sale overrides, reversals, stock adjustments, stock counts, and audit log viewing.

CREATE OR REPLACE FUNCTION public.is_initial_admin_bootstrap_needed()
RETURNS boolean
LANGUAGE sql
SECURITY DEFINER
STABLE
SET search_path = public
AS $$
    SELECT false;
$$;

REVOKE ALL ON FUNCTION public.is_initial_admin_bootstrap_needed() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.is_initial_admin_bootstrap_needed() TO anon, authenticated, service_role;

CREATE OR REPLACE FUNCTION public.resolve_android_effective_role()
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
STABLE
SET search_path = public
AS $$
DECLARE
    v_uid uuid;
BEGIN
    v_uid := auth.uid();
    IF v_uid IS NULL THEN
        RETURN 'UNAUTHENTICATED';
    END IF;
    RETURN 'OWNER_MANAGER';
END;
$$;

REVOKE ALL ON FUNCTION public.resolve_android_effective_role() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.resolve_android_effective_role() TO anon, authenticated, service_role;

CREATE OR REPLACE FUNCTION public.get_my_android_staff_profile()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
STABLE
SET search_path = public
AS $$
DECLARE
    v_uid uuid;
    v_row public.android_staff_roles%ROWTYPE;
    v_email text;
    v_full_name text;
BEGIN
    v_uid := auth.uid();
    IF v_uid IS NULL THEN
        RETURN jsonb_build_object(
            'authenticated', false,
            'staff_role', 'OWNER_MANAGER',
            'is_active', true,
            'bootstrap_needed', false,
            'capabilities', jsonb_build_object(
                'can_override_price', true,
                'can_reverse_sale', true,
                'can_adjust_stock', true,
                'can_submit_stock_count', true,
                'can_resolve_conflicts', true,
                'can_manage_security', true
            )
        );
    END IF;

    SELECT *
    INTO v_row
    FROM public.android_staff_roles
    WHERE user_id = v_uid
    LIMIT 1;

    v_email := COALESCE(v_row.email, auth.jwt() ->> 'email', '');
    v_full_name := COALESCE(v_row.full_name, auth.jwt() -> 'user_metadata' ->> 'full_name', v_email);

    RETURN jsonb_build_object(
        'authenticated', true,
        'user_id', v_uid::text,
        'email', v_email,
        'full_name', v_full_name,
        'staff_role', 'OWNER_MANAGER',
        'is_active', true,
        'bootstrap_needed', false,
        'capabilities', jsonb_build_object(
            'can_override_price', true,
            'can_reverse_sale', true,
            'can_adjust_stock', true,
            'can_submit_stock_count', true,
            'can_resolve_conflicts', true,
            'can_manage_security', true
        )
    );
END;
$$;

REVOKE ALL ON FUNCTION public.get_my_android_staff_profile() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_my_android_staff_profile() TO anon, authenticated, service_role;

-- Unrestricted `public.record_android_sale`: allows price overrides with reason for all staff without role blocking.
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

    v_clean_role := COALESCE(NULLIF(upper(btrim(p_actor_role)), ''), 'STAFF');
    v_clean_reason := NULLIF(btrim(COALESCE(p_price_override_reason, '')), '');
    v_is_override := p_unit_sell_price IS NOT NULL
        AND p_original_sell_price IS NOT NULL
        AND abs(p_unit_sell_price - p_original_sell_price) > 0.009;

    IF v_is_override THEN
        IF p_unit_sell_price <= 0 THEN
            RETURN jsonb_build_object(
                'status', 'INVALID_INPUT',
                'message', 'Overridden unit price must be greater than zero.'
            );
        END IF;
        IF v_clean_reason IS NULL THEN
            RETURN jsonb_build_object(
                'status', 'INVALID_INPUT',
                'message', 'A reason is required for selling price exceptions.'
            );
        END IF;
    END IF;

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
        COALESCE(NULLIF(btrim(auth.jwt() ->> 'email'), ''), NULLIF(btrim(p_actor_id), ''), 'android_staff'),
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

-- Unrestricted `public.reverse_android_sale`: removes role gates while preserving audit reason & idempotency.
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

    v_clean_reason := NULLIF(btrim(COALESCE(p_reason, '')), '');
    IF v_clean_reason IS NULL OR char_length(v_clean_reason) < 3 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'A clear reversal reason is required.'
        );
    END IF;

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
        COALESCE(NULLIF(btrim(auth.jwt() ->> 'email'), ''), NULLIF(btrim(p_actor_id), ''), 'android_staff'),
        COALESCE(NULLIF(upper(btrim(p_actor_role)), ''), 'STAFF'),
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

-- Unrestricted `public.adjust_android_stock`: removes role gates while keeping reason codes & audit notes.
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
        COALESCE(NULLIF(btrim(auth.jwt() ->> 'email'), ''), NULLIF(btrim(p_actor_id), ''), 'android_staff'),
        COALESCE(NULLIF(upper(btrim(p_actor_role)), ''), 'STAFF'),
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

-- Unrestricted `public.submit_physical_stock_count`: removes Supervisor/Owner role lock.
CREATE OR REPLACE FUNCTION public.submit_physical_stock_count(
    p_session_name text,
    p_created_by text,
    p_items jsonb,
    p_idempotency_key text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_session_id uuid;
    v_item jsonb;
    v_prod_id integer;
    v_sys_qty integer;
    v_counted_qty integer;
    v_variance integer;
    v_reason text;
    v_note text;
    v_client_item_id text;
    v_existing_session_id uuid;
    v_has_stock_movements boolean;
BEGIN
    IF p_items IS NULL OR jsonb_typeof(p_items) <> 'array' OR jsonb_array_length(p_items) = 0 THEN
        RETURN jsonb_build_object(
            'success', false,
            'status', 'INVALID_INPUT',
            'message', 'Stock count session must include at least one item.'
        );
    END IF;

    IF p_idempotency_key IS NOT NULL AND trim(p_idempotency_key) <> '' THEN
        SELECT session_id INTO v_existing_session_id
        FROM public.android_stock_count_idempotency
        WHERE idempotency_key = p_idempotency_key;

        IF v_existing_session_id IS NOT NULL THEN
            RETURN jsonb_build_object(
                'success', true,
                'status', 'DUPLICATE_IGNORED',
                'already_committed', true,
                'session_id', v_existing_session_id,
                'message', 'Duplicate request replayed successfully (idempotent).'
            );
        END IF;
    END IF;

    v_has_stock_movements := (to_regclass('public.stock_movements') IS NOT NULL);

    INSERT INTO public.android_stock_count_sessions (session_name, status, created_by, completed_at)
    VALUES (
        COALESCE(NULLIF(btrim(p_session_name), ''), 'Physical Stock Count'),
        'COMPLETED',
        COALESCE(NULLIF(btrim(auth.jwt() ->> 'email'), ''), NULLIF(btrim(p_created_by), ''), 'android_staff'),
        now()
    )
    RETURNING id INTO v_session_id;

    FOR v_item IN SELECT * FROM jsonb_array_elements(p_items)
    LOOP
        v_prod_id := (v_item->>'product_id')::integer;
        v_sys_qty := (v_item->>'system_qty')::integer;
        v_counted_qty := GREATEST(0, (v_item->>'counted_qty')::integer);
        v_variance := v_counted_qty - v_sys_qty;
        v_reason := v_item->>'reason_code';
        v_note := v_item->>'note';
        v_client_item_id := v_item->>'client_item_id';

        INSERT INTO public.android_stock_count_items (
            session_id, product_id, system_qty, counted_qty, variance, reason_code, note, client_item_id
        ) VALUES (
            v_session_id, v_prod_id, v_sys_qty, v_counted_qty, v_variance, v_reason, v_note, v_client_item_id
        )
        ON CONFLICT (client_item_id) DO NOTHING;

        UPDATE public.products
        SET qty = v_counted_qty
        WHERE id = v_prod_id;

        IF v_has_stock_movements AND v_variance <> 0 THEN
            INSERT INTO public.stock_movements (product_id, change_qty, reason, notes)
            VALUES (
                v_prod_id,
                v_variance,
                COALESCE(NULLIF(btrim(v_reason), ''), 'PHYSICAL_COUNT'),
                format('Physical count (%s): %s', COALESCE(NULLIF(btrim(p_session_name), ''), 'Count'), COALESCE(v_note, ''))
            );
        END IF;
    END LOOP;

    IF p_idempotency_key IS NOT NULL AND trim(p_idempotency_key) <> '' THEN
        INSERT INTO public.android_stock_count_idempotency (idempotency_key, session_id)
        VALUES (p_idempotency_key, v_session_id)
        ON CONFLICT (idempotency_key) DO NOTHING;
    END IF;

    RETURN jsonb_build_object(
        'success', true,
        'status', 'COMMITTED',
        'already_committed', false,
        'session_id', v_session_id,
        'message', 'Physical stock count reconciled successfully.'
    );
END;
$$;

-- Unrestricted `record_android_audit_events` & store-wide `get_android_audit_events`.
CREATE OR REPLACE FUNCTION public.record_android_audit_events(
    p_events jsonb
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid uuid;
    v_item jsonb;
    v_audit_id text;
    v_actor_id text;
    v_actor_role text;
    v_device_id text;
    v_operation text;
    v_entity_type text;
    v_entity_id text;
    v_before_json text;
    v_after_json text;
    v_reason text;
    v_occurred_ms bigint;
    v_occurred_at timestamptz;
    v_row_count integer;
    v_accepted_count integer := 0;
    v_duplicate_count integer := 0;
    v_confirmed_ids jsonb := '[]'::jsonb;
BEGIN
    v_uid := auth.uid();

    IF p_events IS NULL OR jsonb_typeof(p_events) <> 'array' THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'p_events must be a JSON array.'
        );
    END IF;

    IF jsonb_array_length(p_events) = 0 THEN
        RETURN jsonb_build_object(
            'status', 'COMMITTED',
            'accepted_count', 0,
            'duplicate_count', 0,
            'confirmed_audit_ids', '[]'::jsonb
        );
    END IF;

    IF jsonb_array_length(p_events) > 100 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Maximum 100 audit events per batch.'
        );
    END IF;

    FOR v_item IN SELECT * FROM jsonb_array_elements(p_events)
    LOOP
        v_audit_id := NULLIF(btrim(COALESCE(v_item ->> 'audit_id', '')), '');
        v_operation := NULLIF(btrim(COALESCE(v_item ->> 'operation', '')), '');
        v_entity_type := NULLIF(btrim(COALESCE(v_item ->> 'entity_type', '')), '');
        v_entity_id := NULLIF(btrim(COALESCE(v_item ->> 'entity_id', '')), '');

        IF v_audit_id IS NULL OR v_operation IS NULL OR v_entity_type IS NULL OR v_entity_id IS NULL THEN
            CONTINUE;
        END IF;

        v_actor_id := COALESCE(
            NULLIF(btrim(v_item ->> 'actor_id'), ''),
            NULLIF(btrim(auth.jwt() ->> 'email'), ''),
            'android_staff'
        );
        v_actor_role := COALESCE(NULLIF(upper(btrim(v_item ->> 'actor_role')), ''), 'STAFF');
        v_device_id := COALESCE(NULLIF(btrim(v_item ->> 'device_id'), ''), 'android');
        v_before_json := NULLIF(v_item ->> 'before_json', '');
        v_after_json := NULLIF(v_item ->> 'after_json', '');
        v_reason := NULLIF(btrim(COALESCE(v_item ->> 'reason', '')), '');

        v_occurred_at := now();
        IF (v_item ? 'occurred_at_epoch_ms') AND NULLIF(btrim(v_item ->> 'occurred_at_epoch_ms'), '') IS NOT NULL THEN
            BEGIN
                v_occurred_ms := (v_item ->> 'occurred_at_epoch_ms')::bigint;
                IF v_occurred_ms > 0 THEN
                    v_occurred_at := to_timestamp(v_occurred_ms::double precision / 1000.0);
                END IF;
            EXCEPTION WHEN OTHERS THEN
                v_occurred_at := now();
            END;
        ELSIF (v_item ? 'occurred_at') AND NULLIF(btrim(v_item ->> 'occurred_at'), '') IS NOT NULL THEN
            BEGIN
                v_occurred_at := (v_item ->> 'occurred_at')::timestamptz;
            EXCEPTION WHEN OTHERS THEN
                v_occurred_at := now();
            END;
        END IF;

        INSERT INTO public.android_audit_events (
            audit_id,
            actor_id,
            actor_role,
            auth_user_id,
            device_id,
            operation,
            entity_type,
            entity_id,
            before_json,
            after_json,
            reason,
            occurred_at
        )
        VALUES (
            v_audit_id,
            v_actor_id,
            v_actor_role,
            v_uid,
            v_device_id,
            v_operation,
            v_entity_type,
            v_entity_id,
            v_before_json,
            v_after_json,
            v_reason,
            v_occurred_at
        )
        ON CONFLICT (audit_id) DO NOTHING;

        GET DIAGNOSTICS v_row_count = ROW_COUNT;
        IF v_row_count > 0 THEN
            v_accepted_count := v_accepted_count + 1;
        ELSE
            v_duplicate_count := v_duplicate_count + 1;
        END IF;

        v_confirmed_ids := v_confirmed_ids || jsonb_build_array(v_audit_id);
    END LOOP;

    RETURN jsonb_build_object(
        'status', 'COMMITTED',
        'accepted_count', v_accepted_count,
        'duplicate_count', v_duplicate_count,
        'confirmed_audit_ids', v_confirmed_ids
    );
END;
$$;

REVOKE ALL ON FUNCTION public.record_android_audit_events(jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.record_android_audit_events(jsonb) TO anon, authenticated, service_role;

CREATE OR REPLACE FUNCTION public.get_android_audit_events(
    p_limit integer DEFAULT 100
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_bounded_limit integer;
    v_events jsonb;
BEGIN
    v_bounded_limit := LEAST(GREATEST(COALESCE(p_limit, 100), 1), 300);

    SELECT COALESCE(jsonb_agg(row_obj), '[]'::jsonb)
    INTO v_events
    FROM (
        SELECT jsonb_build_object(
            'audit_id', e.audit_id,
            'actor_id', e.actor_id,
            'actor_role', e.actor_role,
            'device_id', e.device_id,
            'operation', e.operation,
            'entity_type', e.entity_type,
            'entity_id', e.entity_id,
            'before_json', e.before_json,
            'after_json', e.after_json,
            'reason', e.reason,
            'occurred_at_epoch_ms', (extract(epoch FROM e.occurred_at) * 1000)::bigint,
            'synced_at', e.synced_at
        ) AS row_obj
        FROM public.android_audit_events e
        ORDER BY e.occurred_at DESC
        LIMIT v_bounded_limit
    ) sub;

    RETURN jsonb_build_object(
        'status', 'OK',
        'scope', 'STORE_WIDE',
        'events', v_events
    );
END;
$$;

REVOKE ALL ON FUNCTION public.get_android_audit_events(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_android_audit_events(integer) TO anon, authenticated, service_role;
