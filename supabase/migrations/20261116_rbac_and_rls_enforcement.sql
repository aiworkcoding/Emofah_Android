-- Emofah Agencies — Stage 9.2 Role-Based Permissions & Backend RLS Enforcement
-- Non-destructive & idempotent:
-- 1. Establishes authoritative `public.android_staff_roles` mapped to `auth.users(id)`.
-- 2. Never trusts client-supplied `p_actor_role` or user-editable `user_metadata` for privileged actions.
-- 3. Locks all `android_*` operational tables behind RLS (no direct REST insert/update/delete).
-- 4. Enforces Supervisor / Owner-Manager server-side authorization inside privileged RPCs
--    while preserving Desktop & Storefront compatibility.

CREATE TABLE IF NOT EXISTS public.android_staff_roles (
    user_id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    email text NOT NULL,
    full_name text NULL,
    staff_role text NOT NULL DEFAULT 'CASHIER'
        CHECK (staff_role IN ('CASHIER', 'SUPERVISOR', 'OWNER_MANAGER')),
    is_active boolean NOT NULL DEFAULT true,
    assigned_by text NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE public.android_staff_roles ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON public.android_staff_roles FROM anon, authenticated;
GRANT SELECT ON public.android_staff_roles TO authenticated;

DROP POLICY IF EXISTS "staff_read_own_role" ON public.android_staff_roles;
CREATE POLICY "staff_read_own_role"
    ON public.android_staff_roles
    FOR SELECT
    TO authenticated
    USING (auth.uid() = user_id);

-- Ensure every Android operational/audit/idempotency table has RLS enabled and direct client access revoked.
DO $$
DECLARE
    tbl text;
    android_tables text[] := ARRAY[
        'android_financial_events',
        'android_financial_idempotency',
        'android_sale_idempotency',
        'android_sale_reversals',
        'android_stock_adjustments',
        'android_stock_receipts',
        'android_stock_count_sessions',
        'android_stock_count_items',
        'android_stock_count_idempotency',
        'android_order_status_events',
        'android_order_sale_conversions',
        'android_order_sale_conversion_items',
        'android_purchases',
        'android_purchase_items',
        'android_service_operations',
        'android_service_consumables',
        'android_product_idempotency',
        'android_recognition_corrections'
    ];
BEGIN
    FOREACH tbl IN ARRAY android_tables LOOP
        IF to_regclass(format('public.%I', tbl)) IS NOT NULL THEN
            EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', tbl);
            EXECUTE format('REVOKE ALL ON public.%I FROM anon, authenticated', tbl);
        END IF;
    END LOOP;
END
$$;

-- Authoritative server-side role resolver.
-- Reads `public.android_staff_roles` first, then JWT `app_metadata` (never `user_metadata`).
CREATE OR REPLACE FUNCTION public.resolve_android_effective_role()
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid uuid;
    v_row public.android_staff_roles%ROWTYPE;
    v_app_meta jsonb;
    v_raw_claim text;
BEGIN
    v_uid := auth.uid();
    IF v_uid IS NULL THEN
        RETURN 'UNAUTHENTICATED';
    END IF;

    SELECT *
    INTO v_row
    FROM public.android_staff_roles
    WHERE user_id = v_uid
    LIMIT 1;

    IF FOUND THEN
        IF NOT v_row.is_active THEN
            RETURN 'DISABLED';
        END IF;
        RETURN upper(v_row.staff_role);
    END IF;

    v_app_meta := COALESCE(auth.jwt() -> 'app_metadata', '{}'::jsonb);
    v_raw_claim := lower(COALESCE(
        NULLIF(btrim(v_app_meta ->> 'staff_role'), ''),
        NULLIF(btrim(v_app_meta ->> 'role'), ''),
        ''
    ));

    IF v_raw_claim IN ('owner', 'manager', 'owner_manager', 'admin', 'super_admin') THEN
        RETURN 'OWNER_MANAGER';
    ELSIF v_raw_claim IN ('supervisor', 'shift_lead', 'lead') THEN
        RETURN 'SUPERVISOR';
    END IF;

    RETURN 'CASHIER';
END;
$$;

REVOKE ALL ON FUNCTION public.resolve_android_effective_role() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.resolve_android_effective_role() TO anon, authenticated, service_role;

-- Returns authoritative role & capability flags for the currently signed-in Android staff user.
CREATE OR REPLACE FUNCTION public.get_my_android_staff_profile()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid uuid;
    v_role text;
    v_row public.android_staff_roles%ROWTYPE;
    v_email text;
    v_full_name text;
    v_is_active boolean := true;
    v_is_privileged boolean := false;
BEGIN
    v_uid := auth.uid();
    IF v_uid IS NULL THEN
        RETURN jsonb_build_object(
            'authenticated', false,
            'staff_role', 'CASHIER',
            'is_active', false,
            'capabilities', jsonb_build_object(
                'can_override_price', false,
                'can_reverse_sale', false,
                'can_adjust_stock', false,
                'can_submit_stock_count', false,
                'can_resolve_conflicts', false,
                'can_manage_security', false
            )
        );
    END IF;

    v_role := public.resolve_android_effective_role();
    v_is_active := (v_role <> 'DISABLED');

    SELECT *
    INTO v_row
    FROM public.android_staff_roles
    WHERE user_id = v_uid
    LIMIT 1;

    v_email := COALESCE(v_row.email, auth.jwt() ->> 'email', '');
    v_full_name := COALESCE(v_row.full_name, v_email);

    IF v_role = 'DISABLED' THEN
        RETURN jsonb_build_object(
            'authenticated', true,
            'user_id', v_uid::text,
            'email', v_email,
            'full_name', v_full_name,
            'staff_role', 'CASHIER',
            'is_active', false,
            'capabilities', jsonb_build_object(
                'can_override_price', false,
                'can_reverse_sale', false,
                'can_adjust_stock', false,
                'can_submit_stock_count', false,
                'can_resolve_conflicts', false,
                'can_manage_security', false
            )
        );
    END IF;

    v_is_privileged := v_role IN ('SUPERVISOR', 'OWNER_MANAGER');

    RETURN jsonb_build_object(
        'authenticated', true,
        'user_id', v_uid::text,
        'email', v_email,
        'full_name', v_full_name,
        'staff_role', v_role,
        'is_active', v_is_active,
        'capabilities', jsonb_build_object(
            'can_override_price', v_is_privileged,
            'can_reverse_sale', v_is_privileged,
            'can_adjust_stock', v_is_privileged,
            'can_submit_stock_count', v_is_privileged,
            'can_resolve_conflicts', v_is_privileged,
            'can_manage_security', (v_role = 'OWNER_MANAGER')
        )
    );
END;
$$;

REVOKE ALL ON FUNCTION public.get_my_android_staff_profile() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_my_android_staff_profile() TO anon, authenticated, service_role;

-- Harden `public.record_android_sale`: server-side authoritative role check for price overrides.
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
    v_server_role text;
    v_clean_role text;
    v_clean_reason text;
    v_is_override boolean;
    v_require_auth text;
BEGIN
    v_server_role := public.resolve_android_effective_role();
    IF v_server_role = 'DISABLED' THEN
        RETURN jsonb_build_object(
            'status', 'UNAUTHORIZED_ROLE',
            'message', 'Your Emofah staff account is deactivated.'
        );
    END IF;

    SELECT value INTO v_require_auth
    FROM public.settings
    WHERE key = 'require_android_auth_for_all_writes'
    LIMIT 1;

    IF lower(COALESCE(v_require_auth, 'false')) = 'true' AND auth.uid() IS NULL THEN
        RETURN jsonb_build_object(
            'status', 'UNAUTHORIZED_ROLE',
            'message', 'Staff sign-in is required before recording transactions.'
        );
    END IF;

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

    v_clean_role := CASE
        WHEN auth.uid() IS NOT NULL THEN v_server_role
        ELSE 'CASHIER'
    END;
    v_clean_reason := NULLIF(btrim(COALESCE(p_price_override_reason, '')), '');
    v_is_override := p_unit_sell_price IS NOT NULL
        AND p_original_sell_price IS NOT NULL
        AND abs(p_unit_sell_price - p_original_sell_price) > 0.009;

    IF v_is_override THEN
        IF auth.uid() IS NULL OR v_server_role NOT IN ('SUPERVISOR', 'OWNER_MANAGER') THEN
            RETURN jsonb_build_object(
                'status', 'UNAUTHORIZED_ROLE',
                'message', 'Price override requires an authenticated Supervisor or Owner/Manager session.'
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

-- Harden `public.reverse_android_sale`: requires authenticated Supervisor or Owner/Manager JWT.
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
    v_server_role text;
    v_clean_reason text;
    v_restore_qty integer;
    v_reverse_total numeric;
BEGIN
    v_server_role := public.resolve_android_effective_role();
    IF auth.uid() IS NULL OR v_server_role NOT IN ('SUPERVISOR', 'OWNER_MANAGER') THEN
        RETURN jsonb_build_object(
            'status', 'UNAUTHORIZED_ROLE',
            'message', 'Reversing a completed sale requires an authenticated Supervisor or Owner/Manager session.'
        );
    END IF;

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
        v_server_role,
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

-- Harden `public.adjust_android_stock`: requires authenticated Supervisor or Owner/Manager JWT.
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
    v_server_role text;
BEGIN
    v_server_role := public.resolve_android_effective_role();
    IF auth.uid() IS NULL OR v_server_role NOT IN ('SUPERVISOR', 'OWNER_MANAGER') THEN
        RETURN jsonb_build_object(
            'status', 'UNAUTHORIZED_ROLE',
            'message', 'Stock adjustments require an authenticated Supervisor or Owner/Manager session.'
        );
    END IF;

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
        v_server_role,
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

-- Harden `public.submit_physical_stock_count`: requires authenticated Supervisor or Owner/Manager JWT.
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
    v_server_role text;
BEGIN
    v_server_role := public.resolve_android_effective_role();
    IF auth.uid() IS NULL OR v_server_role NOT IN ('SUPERVISOR', 'OWNER_MANAGER') THEN
        RETURN jsonb_build_object(
            'success', false,
            'status', 'UNAUTHORIZED_ROLE',
            'message', 'Physical stock count reconciliation requires an authenticated Supervisor or Owner/Manager session.'
        );
    END IF;

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
