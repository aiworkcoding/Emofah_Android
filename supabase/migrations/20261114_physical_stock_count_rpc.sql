-- Emofah Agencies: Physical Stock Count & Variance Reconciliation Migration (Stage 4.4)
-- Adds android_stock_count_sessions, android_stock_count_items, and submit_physical_stock_count RPC.

create table if not exists public.android_stock_count_sessions (
    id uuid primary key default gen_random_uuid(),
    session_name text not null,
    status text not null default 'IN_PROGRESS', -- IN_PROGRESS, COMPLETED, CANCELLED
    created_by text,
    created_at timestamptz not null default now(),
    completed_at timestamptz
);

create table if not exists public.android_stock_count_items (
    id uuid primary key default gen_random_uuid(),
    session_id uuid not null references public.android_stock_count_sessions(id) on delete cascade,
    product_id integer not null references public.products(id) on delete restrict,
    system_qty integer not null,
    counted_qty integer not null,
    variance integer not null, -- counted_qty - system_qty
    reason_code text, -- COUNT_CORRECTION, DAMAGE_DISCREPANCY, THEFT_DISCREPANCY, etc.
    note text,
    client_item_id text unique,
    created_at timestamptz not null default now()
);

create table if not exists public.android_stock_count_idempotency (
    idempotency_key text primary key,
    session_id uuid references public.android_stock_count_sessions(id) on delete cascade,
    created_at timestamptz not null default now()
);

alter table public.android_stock_count_sessions enable row level security;
alter table public.android_stock_count_items enable row level security;
alter table public.android_stock_count_idempotency enable row level security;

revoke all on public.android_stock_count_sessions from anon, authenticated;
revoke all on public.android_stock_count_items from anon, authenticated;
revoke all on public.android_stock_count_idempotency from anon, authenticated;

create or replace function public.submit_physical_stock_count(
    p_session_name text,
    p_created_by text,
    p_items jsonb, -- array of { product_id, system_qty, counted_qty, reason_code, note, client_item_id }
    p_idempotency_key text
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
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
begin
    if p_items is null or jsonb_typeof(p_items) <> 'array' or jsonb_array_length(p_items) = 0 then
        return jsonb_build_object(
            'success', false,
            'status', 'INVALID_INPUT',
            'message', 'Stock count session must include at least one item.'
        );
    end if;

    -- Check idempotency
    if p_idempotency_key is not null and trim(p_idempotency_key) <> '' then
        select session_id into v_existing_session_id
        from public.android_stock_count_idempotency
        where idempotency_key = p_idempotency_key;

        if v_existing_session_id is not null then
            return jsonb_build_object(
                'success', true,
                'status', 'DUPLICATE_IGNORED',
                'already_committed', true,
                'session_id', v_existing_session_id,
                'message', 'Duplicate request replayed successfully (idempotent).'
            );
        end if;
    end if;

    v_has_stock_movements := (to_regclass('public.stock_movements') is not null);

    -- Create session
    insert into public.android_stock_count_sessions (session_name, status, created_by, completed_at)
    values (coalesce(nullif(btrim(p_session_name), ''), 'Physical Stock Count'), 'COMPLETED', coalesce(nullif(btrim(p_created_by), ''), 'android_staff'), now())
    returning id into v_session_id;

    -- Process items
    for v_item in select * from jsonb_array_elements(p_items)
    loop
        v_prod_id := (v_item->>'product_id')::integer;
        v_sys_qty := (v_item->>'system_qty')::integer;
        v_counted_qty := greatest(0, (v_item->>'counted_qty')::integer);
        v_variance := v_counted_qty - v_sys_qty;
        v_reason := v_item->>'reason_code';
        v_note := v_item->>'note';
        v_client_item_id := v_item->>'client_item_id';

        -- Insert count item
        insert into public.android_stock_count_items (
            session_id, product_id, system_qty, counted_qty, variance, reason_code, note, client_item_id
        ) values (
            v_session_id, v_prod_id, v_sys_qty, v_counted_qty, v_variance, v_reason, v_note, v_client_item_id
        )
        on conflict (client_item_id) do nothing;

        -- Reconcile product stock to physical count
        update public.products
        set qty = v_counted_qty
        where id = v_prod_id;

        -- Record stock movement ledger entry if shared stock_movements table is present and variance != 0
        if v_has_stock_movements and v_variance <> 0 then
            insert into public.stock_movements (
                product_id, delta, reason, reference_id, created_at
            ) values (
                v_prod_id,
                v_variance,
                coalesce(v_reason, 'PHYSICAL_COUNT_RECONCILIATION'),
                'SESSION:' || v_session_id::text,
                now()
            );
        end if;
    end loop;

    -- Record idempotency
    if p_idempotency_key is not null and trim(p_idempotency_key) <> '' then
        insert into public.android_stock_count_idempotency (idempotency_key, session_id)
        values (p_idempotency_key, v_session_id)
        on conflict (idempotency_key) do nothing;
    end if;

    return jsonb_build_object(
        'success', true,
        'status', 'COMMITTED',
        'already_committed', false,
        'session_id', v_session_id,
        'message', 'Physical stock count submitted and reconciled successfully.'
    );
end;
$$;

revoke all on function public.submit_physical_stock_count(text, text, jsonb, text) from public;
grant execute on function public.submit_physical_stock_count(text, text, jsonb, text) to anon, authenticated, service_role;
