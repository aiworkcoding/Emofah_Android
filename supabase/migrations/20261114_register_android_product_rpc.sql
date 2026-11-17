-- Emofah Agencies — Stage 1.5 Additive Idempotent Android Product Registration RPC
-- Non-destructive: preserves existing public.products rows and storefront/desktop compatibility.

CREATE TABLE IF NOT EXISTS public.android_product_idempotency (
    idempotency_key text PRIMARY KEY,
    draft_id text NOT NULL UNIQUE,
    server_product_id integer NOT NULL REFERENCES public.products(id) ON DELETE RESTRICT,
    actor_id text NOT NULL DEFAULT 'android_staff',
    device_id text NOT NULL DEFAULT 'android',
    source text NOT NULL DEFAULT 'ANDROID',
    created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE public.android_product_idempotency ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.android_product_idempotency FROM anon, authenticated;

CREATE OR REPLACE FUNCTION public.register_android_product(
    p_draft_id text,
    p_idempotency_key text,
    p_name text,
    p_category text DEFAULT 'General',
    p_sell_price numeric DEFAULT 0,
    p_buy_price numeric DEFAULT 0,
    p_initial_qty integer DEFAULT 0,
    p_description text DEFAULT NULL,
    p_image_url text DEFAULT NULL,
    p_actor_id text DEFAULT 'android_staff',
    p_device_id text DEFAULT 'android'
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_existing public.android_product_idempotency%ROWTYPE;
    v_duplicate_product public.products%ROWTYPE;
    v_clean_name text;
    v_clean_category text;
    v_new_product public.products%ROWTYPE;
BEGIN
    v_clean_name := btrim(COALESCE(p_name, ''));
    v_clean_category := COALESCE(NULLIF(btrim(COALESCE(p_category, '')), ''), 'General');

    IF v_clean_name = '' OR char_length(v_clean_name) < 2 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Product name must be at least 2 characters.'
        );
    END IF;

    IF p_sell_price IS NULL OR p_sell_price <= 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'A selling price greater than zero is required.'
        );
    END IF;

    IF p_buy_price IS NULL OR p_buy_price < 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Cost price cannot be negative.'
        );
    END IF;

    IF p_initial_qty IS NULL OR p_initial_qty < 0 THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Initial stock quantity cannot be negative.'
        );
    END IF;

    IF p_draft_id IS NULL OR btrim(p_draft_id) = '' OR p_idempotency_key IS NULL OR btrim(p_idempotency_key) = '' THEN
        RETURN jsonb_build_object(
            'status', 'INVALID_INPUT',
            'message', 'Stable draft_id and idempotency_key are required.'
        );
    END IF;

    -- Idempotency check: safe retries return the original registered product ID.
    SELECT *
    INTO v_existing
    FROM public.android_product_idempotency
    WHERE idempotency_key = p_idempotency_key
       OR draft_id = p_draft_id
    LIMIT 1;

    IF FOUND THEN
        SELECT * INTO v_new_product FROM public.products WHERE id = v_existing.server_product_id;
        RETURN jsonb_build_object(
            'status', 'DUPLICATE_IGNORED',
            'server_product_id', v_existing.server_product_id,
            'name', COALESCE(v_new_product.name, v_clean_name),
            'qty', COALESCE(v_new_product.qty, p_initial_qty),
            'sell_price', COALESCE(v_new_product.sell_price, p_sell_price),
            'already_committed', true
        );
    END IF;

    -- Prevent accidental duplicate catalogue registration across devices by normalized name.
    SELECT *
    INTO v_duplicate_product
    FROM public.products
    WHERE lower(btrim(name)) = lower(v_clean_name)
    LIMIT 1;

    IF FOUND THEN
        INSERT INTO public.android_product_idempotency (
            idempotency_key,
            draft_id,
            server_product_id,
            actor_id,
            device_id,
            source
        )
        VALUES (
            p_idempotency_key,
            p_draft_id,
            v_duplicate_product.id,
            COALESCE(NULLIF(btrim(p_actor_id), ''), 'android_staff'),
            COALESCE(NULLIF(btrim(p_device_id), ''), 'android'),
            'ANDROID'
        )
        ON CONFLICT (idempotency_key) DO NOTHING;

        RETURN jsonb_build_object(
            'status', 'DUPLICATE_EXISTING',
            'server_product_id', v_duplicate_product.id,
            'name', v_duplicate_product.name,
            'qty', v_duplicate_product.qty,
            'sell_price', v_duplicate_product.sell_price,
            'already_committed', true
        );
    END IF;

    INSERT INTO public.products (
        name,
        qty,
        sell_price,
        buy_price,
        image_url,
        category,
        description,
        discount_price,
        is_featured
    )
    VALUES (
        v_clean_name,
        p_initial_qty,
        p_sell_price,
        p_buy_price,
        NULLIF(btrim(COALESCE(p_image_url, '')), ''),
        v_clean_category,
        NULLIF(btrim(COALESCE(p_description, '')), ''),
        0,
        false
    )
    RETURNING * INTO v_new_product;

    INSERT INTO public.android_product_idempotency (
        idempotency_key,
        draft_id,
        server_product_id,
        actor_id,
        device_id,
        source
    )
    VALUES (
        p_idempotency_key,
        p_draft_id,
        v_new_product.id,
        COALESCE(NULLIF(btrim(p_actor_id), ''), 'android_staff'),
        COALESCE(NULLIF(btrim(p_device_id), ''), 'android'),
        'ANDROID'
    );

    RETURN jsonb_build_object(
        'status', 'COMMITTED',
        'server_product_id', v_new_product.id,
        'name', v_new_product.name,
        'qty', v_new_product.qty,
        'sell_price', v_new_product.sell_price,
        'already_committed', false
    );
END;
$$;

REVOKE ALL ON FUNCTION public.register_android_product(text, text, text, text, numeric, numeric, integer, text, text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.register_android_product(text, text, text, text, numeric, numeric, integer, text, text, text, text) TO anon, authenticated, service_role;
