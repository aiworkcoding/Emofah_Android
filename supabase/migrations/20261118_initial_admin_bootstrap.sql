-- Emofah Agencies — Race-Safe Initial Administrator Bootstrap & Staff Role Lifecycle
-- Resolves initial admin bootstrap deadlock cleanly without weakening RLS,
-- without auto-promoting inside read-only role resolution, and without trusting email strings.

CREATE OR REPLACE FUNCTION public.is_initial_admin_bootstrap_needed()
RETURNS boolean
LANGUAGE sql
SECURITY DEFINER
STABLE
SET search_path = public
AS $$
    SELECT NOT EXISTS (
        SELECT 1
        FROM public.android_staff_roles
        WHERE staff_role = 'OWNER_MANAGER'
          AND is_active = true
    );
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

    -- Fall back strictly to server-signed JWT app_metadata (never user_metadata or email text)
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

-- Update get_my_android_staff_profile so client knows whether first-owner setup is available
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
    v_effective text;
    v_bootstrap_needed boolean;
BEGIN
    v_uid := auth.uid();
    v_bootstrap_needed := public.is_initial_admin_bootstrap_needed();
    IF v_uid IS NULL THEN
        RETURN jsonb_build_object(
            'authenticated', false,
            'staff_role', 'UNAUTHENTICATED',
            'is_active', false,
            'bootstrap_needed', v_bootstrap_needed
        );
    END IF;

    SELECT *
    INTO v_row
    FROM public.android_staff_roles
    WHERE user_id = v_uid
    LIMIT 1;

    v_effective := public.resolve_android_effective_role();

    RETURN jsonb_build_object(
        'authenticated', true,
        'user_id', v_uid::text,
        'email', COALESCE(v_row.email, auth.jwt() ->> 'email'),
        'full_name', COALESCE(v_row.full_name, auth.jwt() -> 'user_metadata' ->> 'full_name', auth.jwt() ->> 'email'),
        'staff_role', CASE WHEN v_effective = 'DISABLED' THEN upper(COALESCE(v_row.staff_role, 'CASHIER')) ELSE v_effective END,
        'is_active', COALESCE(v_row.is_active, true),
        'bootstrap_needed', v_bootstrap_needed
    );
END;
$$;

REVOKE ALL ON FUNCTION public.get_my_android_staff_profile() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_my_android_staff_profile() TO anon, authenticated, service_role;

-- Atomic, advisory-locked initial administrator bootstrap for the very first authenticated owner
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
    v_full_name text;
BEGIN
    v_uid := auth.uid();
    IF v_uid IS NULL THEN
        RETURN jsonb_build_object(
            'success', false,
            'code', 'AUTH_REQUIRED',
            'message', 'Sign in with your email verification code before claiming initial owner access.'
        );
    END IF;

    -- Serialize concurrent bootstrap attempts across transactions
    PERFORM pg_advisory_xact_lock(hashtext('emofah_initial_admin_bootstrap'));

    SELECT EXISTS (
        SELECT 1
        FROM public.android_staff_roles
        WHERE staff_role = 'OWNER_MANAGER'
          AND is_active = true
    ) INTO v_owner_exists;

    IF v_owner_exists THEN
        RETURN jsonb_build_object(
            'success', false,
            'code', 'ADMIN_ALREADY_EXISTS',
            'message', 'An Owner/Manager already exists. Ask them to assign your staff role.'
        );
    END IF;

    v_email := COALESCE(NULLIF(btrim(auth.jwt() ->> 'email'), ''), NULLIF(btrim(p_email), ''), 'owner@emofah.local');
    v_full_name := COALESCE(NULLIF(btrim(p_full_name), ''), v_email);

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
        v_full_name,
        'OWNER_MANAGER',
        true,
        'INITIAL_ADMIN_BOOTSTRAP'
    )
    ON CONFLICT (user_id) DO UPDATE
    SET email = EXCLUDED.email,
        full_name = COALESCE(NULLIF(btrim(EXCLUDED.full_name), ''), public.android_staff_roles.full_name),
        staff_role = 'OWNER_MANAGER',
        is_active = true,
        assigned_by = 'INITIAL_ADMIN_BOOTSTRAP',
        updated_at = now();

    -- Keep auth.users.app_metadata aligned when available
    UPDATE auth.users
    SET raw_app_meta_data = COALESCE(raw_app_meta_data, '{}'::jsonb) || jsonb_build_object('staff_role', 'OWNER_MANAGER')
    WHERE id = v_uid;

    IF to_regclass('public.android_audit_log') IS NOT NULL THEN
        INSERT INTO public.android_audit_log (
            audit_id, actor_id, actor_role, device_id, operation,
            entity_type, entity_id, before_json, after_json, reason, occurred_at_epoch_ms
        )
        VALUES (
            'audit-bootstrap-' || gen_random_uuid()::text,
            v_email,
            'OWNER_MANAGER',
            'server-rpc',
            'INITIAL_ADMIN_BOOTSTRAPPED',
            'STAFF_ROLE',
            v_uid::text,
            NULL,
            jsonb_build_object('user_id', v_uid::text, 'email', v_email, 'staff_role', 'OWNER_MANAGER')::text,
            'First active Owner/Manager established securely',
            (extract(epoch FROM now()) * 1000)::bigint
        )
        ON CONFLICT (audit_id) DO NOTHING;
    END IF;

    RETURN jsonb_build_object(
        'success', true,
        'user_id', v_uid::text,
        'email', v_email,
        'staff_role', 'OWNER_MANAGER',
        'message', 'Owner/Manager access activated for this account.'
    );
END;
$$;

REVOKE ALL ON FUNCTION public.bootstrap_initial_admin(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.bootstrap_initial_admin(text, text) TO authenticated, service_role;

-- Owner-only staff directory listing for role assignment in Android settings
CREATE OR REPLACE FUNCTION public.list_android_staff_roles()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
STABLE
SET search_path = public
AS $$
DECLARE
    v_role text;
    v_staff jsonb;
BEGIN
    v_role := public.resolve_android_effective_role();
    IF v_role <> 'OWNER_MANAGER' THEN
        RETURN jsonb_build_object(
            'success', false,
            'code', 'UNAUTHORIZED_ROLE',
            'message', 'Only an Owner/Manager can view staff role assignments.'
        );
    END IF;

    SELECT COALESCE(
        jsonb_agg(
            jsonb_build_object(
                'user_id', u.id::text,
                'email', COALESCE(r.email, u.email),
                'full_name', COALESCE(r.full_name, u.raw_user_meta_data ->> 'full_name', u.email),
                'staff_role', COALESCE(upper(r.staff_role), 'CASHIER'),
                'is_active', COALESCE(r.is_active, true),
                'updated_at', COALESCE(r.updated_at, u.created_at)
            )
            ORDER BY COALESCE(r.is_active, true) DESC, COALESCE(r.email, u.email) ASC
        ),
        '[]'::jsonb
    )
    INTO v_staff
    FROM auth.users u
    LEFT JOIN public.android_staff_roles r ON r.user_id = u.id;

    RETURN jsonb_build_object(
        'success', true,
        'staff', v_staff
    );
END;
$$;

REVOKE ALL ON FUNCTION public.list_android_staff_roles() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.list_android_staff_roles() TO authenticated, service_role;

-- Owner-only role assignment & account activation/deactivation RPC
CREATE OR REPLACE FUNCTION public.assign_android_staff_role(
    p_email text,
    p_staff_role text,
    p_full_name text DEFAULT NULL,
    p_is_active boolean DEFAULT true
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_caller_uid uuid;
    v_caller_role text;
    v_clean_email text;
    v_clean_role text;
    v_target_user auth.users%ROWTYPE;
    v_other_owners integer;
BEGIN
    v_caller_uid := auth.uid();
    v_caller_role := public.resolve_android_effective_role();
    IF v_caller_uid IS NULL OR v_caller_role <> 'OWNER_MANAGER' THEN
        RETURN jsonb_build_object(
            'success', false,
            'code', 'UNAUTHORIZED_ROLE',
            'message', 'Only an Owner/Manager can change staff roles.'
        );
    END IF;

    v_clean_email := lower(btrim(COALESCE(p_email, '')));
    v_clean_role := upper(btrim(COALESCE(p_staff_role, '')));
    IF v_clean_email = '' OR position('@' IN v_clean_email) = 0 THEN
        RETURN jsonb_build_object(
            'success', false,
            'code', 'INVALID_INPUT',
            'message', 'Enter a valid staff email address.'
        );
    END IF;

    IF v_clean_role NOT IN ('CASHIER', 'SUPERVISOR', 'OWNER_MANAGER') THEN
        RETURN jsonb_build_object(
            'success', false,
            'code', 'INVALID_INPUT',
            'message', 'Role must be CASHIER, SUPERVISOR, or OWNER_MANAGER.'
        );
    END IF;

    SELECT *
    INTO v_target_user
    FROM auth.users
    WHERE lower(email) = v_clean_email
    LIMIT 1;

    IF NOT FOUND THEN
        RETURN jsonb_build_object(
            'success', false,
            'code', 'USER_NOT_FOUND',
            'message', 'No account exists for that email yet. Ask the staff member to sign in once with their email code first.'
        );
    END IF;

    -- Prevent removing or disabling the last active Owner/Manager
    IF v_target_user.id = v_caller_uid AND (v_clean_role <> 'OWNER_MANAGER' OR NOT COALESCE(p_is_active, true)) THEN
        SELECT count(*)
        INTO v_other_owners
        FROM public.android_staff_roles
        WHERE staff_role = 'OWNER_MANAGER'
          AND is_active = true
          AND user_id <> v_caller_uid;

        IF v_other_owners = 0 THEN
            RETURN jsonb_build_object(
                'success', false,
                'code', 'LAST_OWNER_PROTECTED',
                'message', 'Assign another active Owner/Manager before removing your own owner role.'
            );
        END IF;
    END IF;

    INSERT INTO public.android_staff_roles (
        user_id,
        email,
        full_name,
        staff_role,
        is_active,
        assigned_by
    )
    VALUES (
        v_target_user.id,
        v_target_user.email,
        COALESCE(NULLIF(btrim(p_full_name), ''), v_target_user.raw_user_meta_data ->> 'full_name', v_target_user.email),
        v_clean_role,
        COALESCE(p_is_active, true),
        COALESCE(NULLIF(btrim(auth.jwt() ->> 'email'), ''), v_caller_uid::text)
    )
    ON CONFLICT (user_id) DO UPDATE
    SET email = EXCLUDED.email,
        full_name = COALESCE(NULLIF(btrim(EXCLUDED.full_name), ''), public.android_staff_roles.full_name),
        staff_role = EXCLUDED.staff_role,
        is_active = EXCLUDED.is_active,
        assigned_by = EXCLUDED.assigned_by,
        updated_at = now();

    UPDATE auth.users
    SET raw_app_meta_data = COALESCE(raw_app_meta_data, '{}'::jsonb) || jsonb_build_object('staff_role', v_clean_role)
    WHERE id = v_target_user.id;

    IF to_regclass('public.android_audit_log') IS NOT NULL THEN
        INSERT INTO public.android_audit_log (
            audit_id, actor_id, actor_role, device_id, operation,
            entity_type, entity_id, before_json, after_json, reason, occurred_at_epoch_ms
        )
        VALUES (
            'audit-role-' || gen_random_uuid()::text,
            COALESCE(NULLIF(btrim(auth.jwt() ->> 'email'), ''), v_caller_uid::text),
            'OWNER_MANAGER',
            'server-rpc',
            'STAFF_ROLE_UPDATED',
            'STAFF_ROLE',
            v_target_user.id::text,
            NULL,
            jsonb_build_object(
                'email', v_target_user.email,
                'staff_role', v_clean_role,
                'is_active', COALESCE(p_is_active, true)
            )::text,
            format('Role updated to %s (active=%s)', v_clean_role, COALESCE(p_is_active, true)),
            (extract(epoch FROM now()) * 1000)::bigint
        )
        ON CONFLICT (audit_id) DO NOTHING;
    END IF;

    RETURN jsonb_build_object(
        'success', true,
        'user_id', v_target_user.id::text,
        'email', v_target_user.email,
        'staff_role', v_clean_role,
        'is_active', COALESCE(p_is_active, true),
        'message', format('Updated %s to %s.', v_target_user.email, v_clean_role)
    );
END;
$$;

REVOKE ALL ON FUNCTION public.assign_android_staff_role(text, text, text, boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.assign_android_staff_role(text, text, text, boolean) TO authenticated, service_role;
