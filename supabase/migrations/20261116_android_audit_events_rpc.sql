-- Emofah Agencies — Stage 9.3 Immutable Audit & Accountability Trail
-- Additive & idempotent:
-- 1. Creates append-only `public.android_audit_events` storing actor, role, device, before/after JSON, reason, and timestamps.
-- 2. Enforces RLS + immutability trigger (no UPDATE or DELETE allowed on committed audit events).
-- 3. Provides idempotent `record_android_audit_events(p_events jsonb)` and role-scoped `get_android_audit_events(p_limit integer)`.

CREATE TABLE IF NOT EXISTS public.android_audit_events (
    audit_id text PRIMARY KEY,
    actor_id text NOT NULL,
    actor_role text NOT NULL DEFAULT 'CASHIER',
    auth_user_id uuid NULL,
    device_id text NOT NULL,
    operation text NOT NULL,
    entity_type text NOT NULL,
    entity_id text NOT NULL,
    before_json text NULL,
    after_json text NULL,
    reason text NULL,
    occurred_at timestamptz NOT NULL,
    synced_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_android_audit_events_occurred_at
    ON public.android_audit_events (occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_android_audit_events_entity
    ON public.android_audit_events (entity_type, entity_id);

CREATE INDEX IF NOT EXISTS idx_android_audit_events_actor
    ON public.android_audit_events (actor_id);

CREATE INDEX IF NOT EXISTS idx_android_audit_events_auth_user
    ON public.android_audit_events (auth_user_id);

ALTER TABLE public.android_audit_events ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON public.android_audit_events FROM anon, authenticated;

-- Append-only guard: audit records cannot be altered or deleted once recorded.
CREATE OR REPLACE FUNCTION public.prevent_android_audit_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Audit trail records in public.android_audit_events are immutable (append-only).';
END;
$$;

DROP TRIGGER IF EXISTS trg_prevent_android_audit_mutation ON public.android_audit_events;
CREATE TRIGGER trg_prevent_android_audit_mutation
    BEFORE UPDATE OR DELETE ON public.android_audit_events
    FOR EACH ROW
    EXECUTE FUNCTION public.prevent_android_audit_mutation();

-- Idempotent batch ingestion RPC for local Android audit events.
CREATE OR REPLACE FUNCTION public.record_android_audit_events(
    p_events jsonb
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_server_role text;
    v_require_auth text;
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

    IF lower(COALESCE(v_require_auth, 'false')) = 'true' AND v_uid IS NULL THEN
        RETURN jsonb_build_object(
            'status', 'UNAUTHORIZED_ROLE',
            'message', 'Staff sign-in is required before syncing audit records.'
        );
    END IF;

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

        -- Preserve authenticated server role attribution when signed in; otherwise fall back safely.
        v_actor_role := CASE
            WHEN v_uid IS NOT NULL THEN v_server_role
            WHEN upper(btrim(COALESCE(v_item ->> 'actor_role', ''))) IN ('CASHIER', 'SUPERVISOR', 'OWNER_MANAGER')
                THEN upper(btrim(v_item ->> 'actor_role'))
            ELSE 'CASHIER'
        END;

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

-- Role-scoped read RPC:
-- Supervisors & Owner/Managers inspect store-wide audit events across all devices;
-- Cashiers only see their own authenticated user records.
CREATE OR REPLACE FUNCTION public.get_android_audit_events(
    p_limit integer DEFAULT 100
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid uuid;
    v_role text;
    v_bounded_limit integer;
    v_events jsonb;
BEGIN
    v_uid := auth.uid();
    IF v_uid IS NULL THEN
        RETURN jsonb_build_object(
            'status', 'UNAUTHORIZED_ROLE',
            'message', 'Staff sign-in is required to read cloud audit history.'
        );
    END IF;

    v_role := public.resolve_android_effective_role();
    IF v_role = 'DISABLED' THEN
        RETURN jsonb_build_object(
            'status', 'UNAUTHORIZED_ROLE',
            'message', 'Your Emofah staff account is deactivated.'
        );
    END IF;

    v_bounded_limit := LEAST(GREATEST(COALESCE(p_limit, 100), 1), 300);

    IF v_role IN ('SUPERVISOR', 'OWNER_MANAGER') THEN
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
    ELSE
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
            WHERE e.auth_user_id = v_uid
            ORDER BY e.occurred_at DESC
            LIMIT v_bounded_limit
        ) sub;
    END IF;

    RETURN jsonb_build_object(
        'status', 'OK',
        'scope', CASE WHEN v_role IN ('SUPERVISOR', 'OWNER_MANAGER') THEN 'STORE_WIDE' ELSE 'SELF_ONLY' END,
        'events', v_events
    );
END;
$$;

REVOKE ALL ON FUNCTION public.get_android_audit_events(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_android_audit_events(integer) TO authenticated, service_role;
