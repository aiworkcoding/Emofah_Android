-- Emofah Agencies — Idempotent Android Financial Event Synchronisation RPC
-- Synchronises parsed M-Pesa transaction events and manual operational money events
-- to the shared Supabase backend with strict duplicate protection.
-- Note: Android captures raw operational evidence, direction (INFLOW/OUTFLOW/TRANSFER),
-- parsed M-Pesa fields, and stamps classification_status = 'PENDING_DESKTOP_RULES' so
-- Desktop retains sole authority over final business accounting classifications.

ALTER TABLE public.android_financial_events
    ADD COLUMN IF NOT EXISTS client_event_id text,
    ADD COLUMN IF NOT EXISTS direction text NOT NULL DEFAULT 'INFLOW',
    ADD COLUMN IF NOT EXISTS channel text,
    ADD COLUMN IF NOT EXISTS currency text NOT NULL DEFAULT 'KES',
    ADD COLUMN IF NOT EXISTS counterparty text,
    ADD COLUMN IF NOT EXISTS counterparty_account text,
    ADD COLUMN IF NOT EXISTS transaction_cost numeric,
    ADD COLUMN IF NOT EXISTS new_balance numeric,
    ADD COLUMN IF NOT EXISTS occurred_text text,
    ADD COLUMN IF NOT EXISTS raw_evidence text,
    ADD COLUMN IF NOT EXISTS parsed_metadata jsonb,
    ADD COLUMN IF NOT EXISTS parse_status text DEFAULT 'VALID_PARSED',
    ADD COLUMN IF NOT EXISTS classification_status text NOT NULL DEFAULT 'PENDING_DESKTOP_RULES';

CREATE UNIQUE INDEX IF NOT EXISTS idx_android_financial_events_client_event_id
    ON public.android_financial_events (client_event_id)
    WHERE client_event_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_android_financial_events_occurred_at
    ON public.android_financial_events (occurred_at DESC);

ALTER TABLE public.android_financial_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.android_financial_idempotency ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON public.android_financial_events FROM anon, authenticated;
REVOKE ALL ON public.android_financial_idempotency FROM anon, authenticated;

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
    v_existing_event_id uuid;
    v_new_event_id uuid;
    v_clean_client_id text;
    v_clean_idem_key text;
    v_clean_ref text;
    v_clean_direction text;
    v_clean_event_type text;
BEGIN
    v_clean_client_id := NULLIF(btrim(COALESCE(p_client_event_id, '')), '');
    v_clean_idem_key := NULLIF(btrim(COALESCE(p_idempotency_key, '')), '');
    v_clean_ref := NULLIF(upper(btrim(COALESCE(p_reference_code, ''))), '');
    v_clean_direction := upper(btrim(COALESCE(p_direction, '')));
    v_clean_event_type := upper(btrim(COALESCE(p_event_type, 'MPESA_EVENT')));

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
        RETURN jsonb_build_object(
            'status', 'DUPLICATE_IGNORED',
            'server_event_id', v_existing_event_id,
            'already_committed', true
        );
    END IF;

    -- 2. Duplicate check by client_event_id or canonical transaction reference_code (e.g. M-Pesa code)
    SELECT id
    INTO v_existing_event_id
    FROM public.android_financial_events
    WHERE client_event_id = v_clean_client_id
       OR (v_clean_ref IS NOT NULL AND upper(reference_code) = v_clean_ref)
    LIMIT 1;

    IF v_existing_event_id IS NOT NULL THEN
        INSERT INTO public.android_financial_idempotency (idempotency_key, event_id)
        VALUES (v_clean_idem_key, v_existing_event_id)
        ON CONFLICT (idempotency_key) DO NOTHING;

        RETURN jsonb_build_object(
            'status', 'DUPLICATE_IGNORED',
            'server_event_id', v_existing_event_id,
            'already_committed', true
        );
    END IF;

    -- 3. Insert new financial event leaving final accounting classification to Desktop rules
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
        'PENDING_DESKTOP_RULES',
        'PENDING_DESKTOP_RULES',
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
        COALESCE(NULLIF(btrim(p_actor_id), ''), 'android_staff'),
        COALESCE(NULLIF(btrim(p_device_id), ''), 'android'),
        NULLIF(btrim(COALESCE(p_notes, '')), ''),
        COALESCE(p_occurred_at, now())
    )
    RETURNING id INTO v_new_event_id;

    INSERT INTO public.android_financial_idempotency (idempotency_key, event_id)
    VALUES (v_clean_idem_key, v_new_event_id)
    ON CONFLICT (idempotency_key) DO NOTHING;

    RETURN jsonb_build_object(
        'status', 'COMMITTED',
        'server_event_id', v_new_event_id,
        'classification_status', 'PENDING_DESKTOP_RULES',
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
