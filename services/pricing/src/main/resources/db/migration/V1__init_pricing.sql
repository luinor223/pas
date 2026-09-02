-- Pricing schema (db-pricing.md) — pas_pricing / schema pricing.
-- This migration lays down the service catalog and the shared outbox; price_list tables
-- arrive in a later migration with their own code.
create schema if not exists pricing;

-- service_item — the service catalog. Owned here (price lines are per item); operations and
-- billing reference items by `code`, a stable human-legible business key (registry §6 exception),
-- not by uuid. `unit` is the billing unit (TEU, day, set, ...).
create table pricing.service_item (
    id          uuid primary key default gen_random_uuid(),
    code        text not null unique,
    name        text not null,
    unit        text not null,
    is_active   boolean not null default true,
    created_at  timestamptz not null default now(),
    created_by  uuid,
    updated_at  timestamptz not null default now(),
    updated_by  uuid
);

-- outbox (M2 / D15) — one per service, never per event type. The shared OutboxEvent entity maps
-- here; ddl-auto=validate requires it even before pricing emits its first event.
create table pricing.outbox (
    id              uuid primary key,
    event_type      text not null,
    aggregate_type  text not null,
    aggregate_id    uuid not null,
    payload         jsonb not null,
    created_at      timestamptz not null,
    claimed_at      timestamptz,
    published_at    timestamptz,
    cancelled_at    timestamptz,
    retry_count     int not null default 0
);
create index idx_pricing_outbox_unpublished
    on pricing.outbox(created_at)
    where published_at is null and cancelled_at is null;
