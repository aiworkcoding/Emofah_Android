-- Emofah Agencies: Financial Activity Foundation Migration (Stage 5.1)
-- Adds financial_events and expenses tables for M-Pesa monitoring, cash flows, and expense tracking.

create table if not exists public.android_financial_events (
    id uuid primary key default gen_random_uuid(),
    event_type text not null, -- CASH_SALE, M_PESA_RECEIVED, EXPENSE_OUTFLOW, SUPPLIER_PAYMENT, REVERSAL_ADJUSTMENT
    amount numeric not null,
    category text not null,
    reference_code text unique, -- e.g. M-Pesa code QG78... or sale UUID
    source text not null default 'ANDROID',
    actor_id text,
    device_id text,
    notes text,
    occurred_at timestamptz not null default now(),
    created_at timestamptz not null default now()
);

create table if not exists public.android_financial_idempotency (
    idempotency_key text primary key,
    event_id uuid references public.android_financial_events(id) on delete cascade,
    created_at timestamptz not null default now()
);
