-- Emofah Agencies — Initial Administrator Bootstrap Migration
-- Automatically provisions the first authenticated user as OWNER_MANAGER if no active owner/manager exists in the system.
-- This permanently resolves the authentication bootstrap deadlock without weakening RLS or exposing service role credentials.

CREATE OR REPLACE FUNCTION public.resolve_android_effective_role()
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid uuid;
    v_row public.android_staff_roles%ROWTYPE;
    v_owner_exists boolean;
    v_app_meta jsonb;
    v_raw_claim text;
    v_user_email text;
BEGIN
    v_uid := auth.uid();
    IF v_uid IS NULL THEN
        RETURN 'UNAUTHENTICATED';
    END IF;

    -- Check if any OWNER_MANAGER exists in the system
    SELECT EXISTS (
        SELECT 1 FROM public.android_staff_roles WHERE staff_role = 'OWNER_MANAGER' AND is_active = true
    ) INTO v_owner_exists;

    -- If no owner exists, bootstrap current authenticated user as initial administrator!
    IF NOT v_owner_exists THEN
        v_user_email := COALESCE(NULLIF(btrim(auth.jwt() ->> 'email'), ''), 'admin@emofah.local');
        INSERT INTO public.android_staff_roles (
            user_id,
            email,
            full_name,
            staff_role,
            is_active,
            assigned_by
        )
        VALUES (
            v_uid,
            v_user_email,
            'Initial Administrator',
            'OWNER_MANAGER',
            true,
            'SYSTEM_AUTO_BOOTSTRAP'
        )
        ON CONFLICT (user_id) DO UPDATE
        SET staff_role = 'OWNER_MANAGER',
            is_active = true,
            updated_at = now();
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

-- Dedicated explicit bootstrap RPC function for administrative initialization if needed
CREATE OR REPLACE FUNCTION public.bootstrap_initial_admin(
    p_email text DEFAULT NULL,
    p_full_name text DEFAULT 'Initial Administrator'
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid uuid;
    v_owner_exists boolean;
    v_email text;
BEGIN
    v_uid := auth.uid();
    IF v_uid IS NULL THEN
        RETURN jsonb_build_object(
            'success', false,
            'message', 'Authentication required for initial admin bootstrap.'
        );
    END IF;

    SELECT EXISTS (
        SELECT 1 FROM public.android_staff_roles WHERE staff_role = 'OWNER_MANAGER' AND is_active = true
    ) INTO v_owner_exists;

    IF v_owner_exists THEN
        RETURN jsonb_build_object(
            'success', false,
            'message', 'An administrator already exists in the system. Bootstrap is disabled.'
        );
    END IF;

    v_email := COALESCE(NULLIF(btrim(p_email), ''), NULLIF(btrim(auth.jwt() ->> 'email'), ''), 'admin@emofah.local');

    INSERT INTO public.android_staff_roles (
        user_id,
        email,
        full_name,
        staff_role,
        is_active,
        assigned_by
    )
    VALUES (
        v_uid,
        v_email,
        COALESCE(NULLIF(btrim(p_full_name), ''), 'Initial Administrator'),
        'OWNER_MANAGER',
        true,
        'MANUAL_BOOTSTRAP_RPC'
    )
    ON CONFLICT (user_id) DO UPDATE
    SET staff_role = 'OWNER_MANAGER',
        is_active = true,
        updated_at = now();

    RETURN jsonb_build_object(
        'success', true,
        'user_id', v_uid::text,
        'email', v_email,
        'staff_role', 'OWNER_MANAGER',
        'message', 'Initial administrator bootstrapped successfully.'
    );
END;
$$;

REVOKE ALL ON FUNCTION public.bootstrap_initial_admin(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.bootstrap_initial_admin(text, text) TO authenticated, service_role;
