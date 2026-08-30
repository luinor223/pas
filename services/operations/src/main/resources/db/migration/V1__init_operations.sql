-- Operations schema (db-operations.md) — pas_operations / schema operations
create schema if not exists operations;

-- operation_period — global monthly period OPEN -> LOCKED (no unlock)
create table operations.operation_period (
    id              uuid primary key default gen_random_uuid(),
    period_code     text not null unique check (period_code ~ '^[0-9]{4}-[0-9]{2}$'),
    start_date      date not null,
    end_date        date not null,
    status          text not null check (status in ('OPEN','LOCKED')),
    locked_by       uuid,
    locked_by_name  text,
    locked_at       timestamptz,
    created_at      timestamptz not null default now(),
    created_by      uuid,
    updated_at      timestamptz not null default now(),
    updated_by      uuid,
    constraint chk_period_dates check (start_date <= end_date)
);

create index idx_operation_period_status on operations.operation_period(status);
create index idx_operation_period_dates on operations.operation_period(start_date, end_date);

-- volume_record — snapshots at entry time (D7), no per-record status, period's LOCKED is confirmation
create table operations.volume_record (
    id              uuid primary key default gen_random_uuid(),
    record_no       text not null unique,
    period_id       uuid not null references operations.operation_period(id),
    contract_id     uuid not null,
    customer_id     uuid,
    customer_name   text not null,
    service_code    text not null,
    service_name    text not null,
    unit            text not null,
    quantity        numeric(18,3) not null check (quantity >= 0),
    note            text,
    created_at      timestamptz not null default now(),
    created_by      uuid,
    updated_at      timestamptz not null default now(),
    updated_by      uuid
);

create index idx_volume_record_period on operations.volume_record(period_id);
create index idx_volume_record_contract_period on operations.volume_record(contract_id, period_id);
create index idx_volume_record_service_code on operations.volume_record(service_code);

-- outbox (M2/D6/D15) — audit.recorded via pas.audit, no workflow.* in this service, no processed_event (consumes nothing)
create table operations.outbox (
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

create index idx_operations_outbox_unpublished
    on operations.outbox(created_at)
    where published_at is null and cancelled_at is null;
