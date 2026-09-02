-- Versioned, time-bounded price lists (db-pricing.md, PRC-01..06, §9³).
create extension if not exists btree_gist;   -- text equality inside a gist EXCLUDE

-- price_list — the scoped container. Scope (PRC-01) is nullable customer/contract/service_group
-- with a CHECK that at least one is set; scope_key is the derived normalization used for overlap
-- and lookup. Scope columns are frozen once a version exists (else scope_key desyncs).
create table pricing.price_list (
    id            uuid primary key default gen_random_uuid(),
    price_list_no text not null unique,
    customer_id   uuid,
    contract_id   uuid,
    service_group text,
    scope_key     text not null,
    note          text,
    created_at    timestamptz not null default now(),
    created_by    uuid,
    updated_at    timestamptz not null default now(),
    updated_by    uuid,
    constraint chk_price_list_scope
        check (customer_id is not null or contract_id is not null or service_group is not null)
);
create sequence if not exists pricing.price_list_no_seq;

-- price_list_version — one row per version (PRC-04/05). scope_key is copied from the list so the
-- EXCLUDE constraint can enforce PRC-03: no two APPROVED/EFFECTIVE versions of the same scope may
-- have overlapping validity. addendum_id (D8) records provenance only.
create table pricing.price_list_version (
    id            uuid primary key default gen_random_uuid(),
    price_list_id uuid not null references pricing.price_list(id) on delete cascade,
    version_no    int  not null check (version_no > 0),
    status        text not null
        check (status in ('DRAFT','SUBMITTED','APPROVED','EFFECTIVE','SUPERSEDED','EXPIRED','REJECTED')),
    valid_from    date not null,
    valid_to      date not null,
    scope_key     text not null,
    addendum_id   uuid,
    version       int  not null default 0,
    created_at    timestamptz not null default now(),
    created_by    uuid,
    updated_at    timestamptz not null default now(),
    updated_by    uuid,
    constraint chk_plv_dates check (valid_from <= valid_to),                    -- PRC-02
    constraint uq_plv_list_version unique (price_list_id, version_no),
    constraint excl_plv_overlap exclude using gist (                            -- PRC-03
        scope_key with =,
        daterange(valid_from, valid_to, '[]') with &&
    ) where (status in ('APPROVED','EFFECTIVE'))
);
create index idx_plv_list on pricing.price_list_version(price_list_id);
create index idx_plv_status on pricing.price_list_version(status);
-- historical effective-at-date lookup (billing): any ever-effective version whose range holds date
create index idx_plv_effective_lookup on pricing.price_list_version(scope_key, valid_from, valid_to)
    where status in ('APPROVED','EFFECTIVE','SUPERSEDED','EXPIRED');

-- price_line — the priced items of a version. unit_price never negative.
create table pricing.price_line (
    id              uuid primary key default gen_random_uuid(),
    version_id      uuid not null references pricing.price_list_version(id) on delete cascade,
    service_item_id uuid not null references pricing.service_item(id),
    unit_price      numeric(18,2) not null check (unit_price >= 0),
    constraint uq_price_line unique (version_id, service_item_id)
);
create index idx_price_line_version on pricing.price_line(version_id);

-- status_history (D17) — append-only, one row per status change of a version, written in the same
-- transaction as the status column update. trigger_kind: U user, S system/scheduler, W workflow.
create table pricing.status_history (
    id           uuid primary key default gen_random_uuid(),
    version_id   uuid not null references pricing.price_list_version(id) on delete cascade,
    from_status  text,
    to_status    text not null,
    trigger_kind text not null check (trigger_kind in ('U','S','W')),
    trigger_ref  uuid,
    note         text,
    created_at   timestamptz not null default now(),
    created_by   uuid
);
create index idx_pricing_status_history_version on pricing.status_history(version_id, created_at);

-- processed_event — consumer dedup for the Kafka listener (event_id from the message header).
create table pricing.processed_event (
    event_id     uuid primary key,
    processed_at timestamptz not null default now()
);
