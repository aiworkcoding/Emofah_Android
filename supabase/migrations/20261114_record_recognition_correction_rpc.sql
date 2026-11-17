-- Emofah Agencies — Stage 1.6 Additive Recognition Learning & Correction RPC
-- Non-destructive: records confirmed human corrections, learned aliases, and optional visual hashes
-- without modifying existing product master semantics or storefront/desktop workflows.

CREATE TABLE IF NOT EXISTS public.android_recognition_corrections (
    id bigserial PRIMARY KEY,
    correction_id text NOT NULL UNIQUE,
    idempotency_key text NOT NULL UNIQUE,
    suggested_product_id integer NULL,
    suggested_product_name text NULL,
    confirmed_product_id integer NOT NULL REFERENCES public.products(id) ON DELETE CASCADE,
    match_status text NOT NULL DEFAULT 'UNIDENTIFIED',
    selection_kind text NOT NULL DEFAULT 'MANUAL_SEARCH_OVERRIDE',
    used_cloud_assist boolean NOT NULL DEFAULT false,
    learned_alias text NULL,
    ocr_snippet text NULL,
    visual_dhash_hex text NULL,
    reference_image_url text NULL,
    actor_id text NOT NULL DEFAULT 'android_staff',
    device_id text NOT NULL DEFAULT 'android',
    source text NOT NULL DEFAULT 'ANDROID',
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_android_recog_corr_confirmed_product
    ON public.android_recognition_corrections (confirmed_product_id, created_at DESC);

ALTER TABLE public.android_recognition_corrections ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.android_recognition_corrections FROM anon, authenticated;

CREATE OR REPLACE FUNCTION public.record_android_recognition_correction(
    p_correction_id text,
    p_idempotency_key text,
    p_suggested_product_id integer DEFAULT NULL,
    p_suggested_product_name text DEFAULT NULL,
    p_confirmed_product_id integer DEFAULT NULL,
    p_match_status text DEFAULT 'UNIDENTIFIED',
    p_selection_kind text DEFAULT 'MANUAL_SEARCH_OVERRIDE',
    p_used_cloud_assist boolean DEFAULT false,
    p_learned_alias text DEFAULT NULL,
    p_ocr_snippet text DEFAULT NULL,
    p_visual_dhash_hex text DEFAULT NULL,
    p_reference_image_url text DEFAULT NULL,
    p_actor_id text DEFAULT 'android_staff',
    p_device_id text DEFAULT 'android'
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_existing public.android_recognition_corrections%ROWTYPE;
    v_product public.products%ROWTYPE;
    v_inserted public.android_recognition_corrections%ROWTYPE;
    v_clean_alias text;
    v_clean_image_url text;
BEGIN
    IF p_correction_id IS NULL OR btrim(p_correction_id) = ''
       OR p_idempotency_key IS NULL OR btrim(p_idempotency_key) = '' THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Stable correction_id and idempotency_key are required.'
        );
    END IF;

    IF p_confirmed_product_id IS NULL OR p_confirmed_product_id <= 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'A valid canonical confirmed_product_id is required.'
        );
    END IF;

    SELECT *
    INTO v_existing
    FROM public.android_recognition_corrections
    WHERE idempotency_key = p_idempotency_key
       OR correction_id = p_correction_id
    LIMIT 1;

    IF FOUND THEN
        RETURN jsonb_build_object(
            'status', 'DUPLICATE_IGNORED',
            'server_correction_id', v_existing.id,
            'confirmed_product_id', v_existing.confirmed_product_id,
            'already_committed', true
        );
    END IF;

    SELECT *
    INTO v_product
    FROM public.products
    WHERE id = p_confirmed_product_id
    LIMIT 1;

    IF NOT FOUND THEN
        RETURN jsonb_build_object(
            'status', 'PRODUCT_NOT_FOUND',
            'message', format('Confirmed product #%s was not found in shared catalogue.', p_confirmed_product_id)
        );
    END IF;

    v_clean_alias := NULLIF(btrim(COALESCE(p_learned_alias, '')), '');
    v_clean_image_url := NULLIF(btrim(COALESCE(p_reference_image_url, '')), '');

    INSERT INTO public.android_recognition_corrections (
        correction_id,
        idempotency_key,
        suggested_product_id,
        suggested_product_name,
        confirmed_product_id,
        match_status,
        selection_kind,
        used_cloud_assist,
        learned_alias,
        ocr_snippet,
        visual_dhash_hex,
        reference_image_url,
        actor_id,
        device_id,
        source
    )
    VALUES (
        btrim(p_correction_id),
        btrim(p_idempotency_key),
        p_suggested_product_id,
        NULLIF(btrim(COALESCE(p_suggested_product_name, '')), ''),
        p_confirmed_product_id,
        COALESCE(NULLIF(btrim(COALESCE(p_match_status, '')), ''), 'UNIDENTIFIED'),
        COALESCE(NULLIF(btrim(COALESCE(p_selection_kind, '')), ''), 'MANUAL_SEARCH_OVERRIDE'),
        COALESCE(p_used_cloud_assist, false),
        v_clean_alias,
        NULLIF(left(btrim(COALESCE(p_ocr_snippet, '')), 180), ''),
        NULLIF(btrim(COALESCE(p_visual_dhash_hex, '')), ''),
        v_clean_image_url,
        COALESCE(NULLIF(btrim(COALESCE(p_actor_id, '')), ''), 'android_staff'),
        COALESCE(NULLIF(btrim(COALESCE(p_device_id, '')), ''), 'android'),
        'ANDROID'
    )
    RETURNING * INTO v_inserted;

    -- Non-destructive enrichment: only fill missing product image_url if none exists yet.
    IF v_clean_image_url IS NOT NULL AND (v_product.image_url IS NULL OR btrim(v_product.image_url) = '') THEN
        UPDATE public.products
        SET image_url = v_clean_image_url
        WHERE id = p_confirmed_product_id
          AND (image_url IS NULL OR btrim(image_url) = '');
    END IF;

    RETURN jsonb_build_object(
        'status', 'COMMITTED',
        'server_correction_id', v_inserted.id,
        'confirmed_product_id', v_inserted.confirmed_product_id,
        'already_committed', false
    );
END;
$$;

REVOKE ALL ON FUNCTION public.record_android_recognition_correction(
    text, text, integer, text, integer, text, text, boolean, text, text, text, text, text, text
) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.record_android_recognition_correction(
    text, text, integer, text, integer, text, text, boolean, text, text, text, text, text, text
) TO anon, authenticated, service_role;
