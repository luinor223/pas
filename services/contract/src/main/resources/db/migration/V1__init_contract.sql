-- Contract / customer / addendum schema (db-contract.md) — pas_contract / schema contract
-- Columns are the authoritative list from db-contract.drawio; constraints per db-contract.md
-- "Constraints & indexes" and registry §3 (status enums) / §6 (common conventions).
create schema if not exists contract;

-- customer — 4.1. Status is ACTIVE|SUSPENDED only (customers activate/suspend, never approve).
create table contract.customer (
    id                      uuid primary key default gen_random_uuid(),
    code                    text not null unique,
    name                    text not null,
    short_name              text,
    tax_code                text,
    address                 text,
    representative_name     text,
    representative_position text,
    segment                 text,
    status                  text not null default 'ACTIVE' check (status in ('ACTIVE','SUSPENDED')),
    created_at              timestamptz not null default now(),
    created_by              uuid,
    created_by_name         text,
    created_by_department   text,
    updated_at              timestamptz not null default now(),
    updated_by              uuid,
    updated_by_name         text
);
create index idx_customer_name on contract.customer(name);
create index idx_customer_status on contract.customer(status);

-- customer_contact — Figma "Contacts" tab + primary-contact card (4.1 "thông tin liên hệ")
create table contract.customer_contact (
    id          uuid primary key default gen_random_uuid(),
    customer_id uuid not null references contract.customer(id) on delete cascade,
    full_name   text not null,
    title       text,
    email       text,
    phone       text,
    is_primary  boolean not null default false
);
create index idx_customer_contact_customer on contract.customer_contact(customer_id);
-- at most one primary contact per customer
create unique index uq_customer_contact_primary
    on contract.customer_contact(customer_id) where is_primary;

-- contract — commercial fields adopted from Figma Contract Detail (db-contract.md).
-- version int = CTR-01 optimistic lock (D5 target). vat_rate/payment_term feed billing's PAY-03 snapshot.
-- vat_rate/payment_term are NULLABLE by design: a DRAFT may be saved incomplete. Both become
-- REQUIRED at submit (CTR-02, app check) — a NOT NULL here would block saving a partial DRAFT.
-- A null vat_rate is never coerced to 0: 0% is a deliberate value, null is "not yet stated",
-- and silently billing the two alike is the invoice drift the system exists to prevent.
-- vat_rate is bounded 0..100 at submit (numeric(5,2) only bounds it to +/-999.99).
-- payment_term shares its value space with addendum.payment_term_override, which overwrites
-- this column when a PAYMENT_TERMS addendum takes effect (registry §9 footnote ²) — constrain
-- both together or neither, or billing can be handed a term it cannot parse.
create table contract.contract (
    id             uuid primary key default gen_random_uuid(),
    contract_no    text not null unique,
    customer_id    uuid not null references contract.customer(id),
    description    text,
    service_group  text not null check (service_group in ('STEVEDORING','WAREHOUSING','TRANSPORTATION','CONTAINER_HANDLING')),
    value          numeric(18,2),
    currency       text not null default 'VND',
    valid_from     date not null,
    valid_to       date not null,
    payment_term   text,
    billing_cycle  text not null default 'MONTHLY' check (billing_cycle in ('MONTHLY')),
    vat_rate       numeric(5,2),
    penalty_terms  text,
    service_clause text,
    status         text not null default 'DRAFT'
                   check (status in ('DRAFT','SUBMITTED','UNDER_REVIEW','APPROVED','ACTIVE','EXPIRED','REJECTED','REVISION_REQUESTED','CANCELLED')),
    version        int  not null default 0,
    created_at     timestamptz not null default now(),
    created_by     uuid,
    created_by_name text,
    created_by_department text,
    updated_at     timestamptz not null default now(),
    updated_by     uuid,
    updated_by_name text,
    -- CTR-02 validity window
    constraint chk_contract_validity check (valid_from <= valid_to)
);
create index idx_contract_customer on contract.contract(customer_id);
create index idx_contract_status on contract.contract(status);
-- D14d scheduler sweeps APPROVED at valid_from and ACTIVE at valid_to
create index idx_contract_status_valid_from on contract.contract(status, valid_from);
create index idx_contract_status_valid_to on contract.contract(status, valid_to);

-- addendum — own row, own workflow instance, same status enum as CONTRACT.
-- TERM_EXTENSION + new_valid_to IS renewal (D14b); no separate renewal mechanism.
-- Carries no price data: a price change is realised as a pricing version created from the
-- approved addendum by Sales (D8). No own end date — shares the parent's expiry.
create table contract.addendum (
    id                    uuid primary key default gen_random_uuid(),
    addendum_no           text not null unique,
    contract_id           uuid not null references contract.contract(id),
    change_type           text not null check (change_type in ('UNIT_PRICE_CHANGE','TERM_EXTENSION','ADDED_SERVICE','PAYMENT_TERMS')),
    description           text,
    effective_from        date not null,
    new_valid_to          date,
    payment_term_override text,
    status                text not null default 'DRAFT'
                          check (status in ('DRAFT','SUBMITTED','UNDER_REVIEW','APPROVED','ACTIVE','EXPIRED','REJECTED','REVISION_REQUESTED','CANCELLED')),
    version               int  not null default 0,
    created_at            timestamptz not null default now(),
    created_by            uuid,
    created_by_name       text,
    created_by_department text,
    updated_at            timestamptz not null default now(),
    updated_by            uuid,
    updated_by_name       text
    -- change_type completeness (TERM_EXTENSION needs new_valid_to, PAYMENT_TERMS needs
    -- payment_term_override) is an APP check at submit, not a DB CHECK: db-contract.md lists
    -- both columns as "Nullable by design", and a CHECK would block saving a partial DRAFT.
);
create index idx_addendum_contract on contract.addendum(contract_id);
create index idx_addendum_status on contract.addendum(status);
create index idx_addendum_status_effective_from on contract.addendum(status, effective_from);

-- addendum_service — services added by an ADDED_SERVICE addendum (requirement 4.3
-- "bổ sung dịch vụ"). RECORD/DISPLAY ONLY: this table is deliberately NOT an input to scope
-- enforcement. contract.service_group remains the single scope value that
-- ContractInternal.GetContract returns and operations-service validates volume entries against.
-- Composing scope from addenda would change that proto response and operations' validation
-- logic (session 5) — if it is ever wanted it needs its own registry decision, not a
-- side effect of this table. Carries no price data (D8): a price for an added service still
-- requires a separate pricing version created by Sales.
create table contract.addendum_service (
    id              uuid primary key default gen_random_uuid(),
    addendum_id     uuid not null references contract.addendum(id) on delete cascade,
    service_item_id uuid,
    service_code    text not null,
    service_name    text not null,
    unit            text,
    scope_note      text,
    created_at      timestamptz not null default now()
);
create index idx_addendum_service_addendum on contract.addendum_service(addendum_id);
-- one line per service per addendum
create unique index uq_addendum_service_code on contract.addendum_service(addendum_id, service_code);

-- attachment — polymorphic owner (CONTRACT|ADDENDUM only), one upload UI, two owner kinds.
-- Files on a mounted volume; metadata here. CTR-02's ">=1 attachment" is an app check at submit.
create table contract.attachment (
    id           uuid primary key default gen_random_uuid(),
    owner_type   text not null check (owner_type in ('CONTRACT','ADDENDUM')),
    owner_id     uuid not null,
    file_name    text not null,
    content_type text,
    size_bytes   bigint,
    storage_path text not null,
    uploaded_by  uuid,
    uploaded_at  timestamptz not null default now()
);
create index idx_attachment_owner on contract.attachment(owner_type, owner_id);

-- status_history (D17) — append-only, one row in the SAME transaction as every status change.
-- Polymorphic over CONTRACT|ADDENDUM, same pattern as attachment. The only history a business
-- rule may read (audit-service must not be). trigger_kind U|W|E|S per registry §9.
create table contract.status_history (
    id          uuid primary key default gen_random_uuid(),
    entity_type text not null check (entity_type in ('CONTRACT','ADDENDUM')),
    entity_id   uuid not null,
    from_status text,
    to_status   text not null,
    trigger_kind text not null check (trigger_kind in ('U','W','E','S')),
    trigger_ref uuid,
    actor_id    uuid,
    actor_name  text,
    note        text,
    occurred_at timestamptz not null default now()
);
create index idx_status_history_entity
    on contract.status_history(entity_type, entity_id, occurred_at);

-- outbox (M2 / D6) — one per service, never per event type.
-- Three uses here: audit.recorded (D15), workflow.start_requested (D4), esign.session_requested (D10).
-- document.expiring stays a DIRECT publish (D9) — a lost warning re-fires next run.
create table contract.outbox (
    id             uuid primary key,
    event_type     text not null,
    aggregate_type text not null,
    aggregate_id   uuid not null,
    payload        jsonb not null,
    created_at     timestamptz not null,
    claimed_at     timestamptz,
    published_at   timestamptz,
    cancelled_at   timestamptz,
    retry_count    int not null default 0
);
create index idx_contract_outbox_unpublished
    on contract.outbox(created_at)
    where published_at is null and cancelled_at is null;
-- the cancel path (M2 step 1) looks the row up by the document it was written for
create index idx_contract_outbox_aggregate
    on contract.outbox(aggregate_id, event_type)
    where published_at is null and cancelled_at is null;

-- processed_event — consumer dedup (workflow.instance_started / workflow.completed).
-- Still required under Kafka: offsets commit after processing, so a mid-batch death re-reads.
create table contract.processed_event (
    event_id     uuid primary key,
    processed_at timestamptz not null default now()
);

-- document_counter — registry §2 numbering {PREFIX}-{YYYY}-{seq}, per type per year, generated
-- by the owning service. Not in the drawio (which shows business tables only); the counter is
-- the mechanism behind contract_no (CTR-{YYYY}-{seq}) and addendum_no (ADD-{YYYY}-{seq}).
create table contract.document_counter (
    doc_type text not null check (doc_type in ('CONTRACT','ADDENDUM')),
    year     int  not null,
    next_seq bigint not null default 1,
    primary key (doc_type, year)
);

-- customer_counter — registry §2 lists customers under "other visible numbers" as CUS-{seq},
-- with NO year segment, so it cannot share document_counter's (doc_type, year) key: the
-- sequence must run unbroken across years or the codes collide on customer.code's UNIQUE.
create table contract.customer_counter (
    id       boolean primary key default true check (id),
    next_seq bigint not null default 1
);
insert into contract.customer_counter (id, next_seq) values (true, 1);
