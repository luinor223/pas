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

-- ---------------------------------------------------------------------------
-- Demo seed (dev UX only): diversified customers / contacts / contracts so a
-- fresh `down-v + up` boots with a usable directory. Statuses are chosen to be
-- scheduler-inert (APPROVED rows have future valid_from, ACTIVE rows future
-- valid_to), history chains follow registry §9 edges, and no outbox row is
-- seeded (nothing dispatches on boot). Counters are set past the seeds.
-- ---------------------------------------------------------------------------

-- customers (8: 7 ACTIVE, 1 SUSPENDED)
insert into contract.customer
    (id, code, name, short_name, tax_code, address, representative_name,
     representative_position, segment, status, created_by_name, created_at)
values
    ('11111111-1111-4111-8111-111111111111', 'CUS-0001', 'Saigon Port Services JSC', 'SPS',
     '0301234567', 'No. 4 Nguyen Tat Thanh, District 4, Ho Chi Minh City',
     'Nguyen Van Hai', 'Deputy Director', 'Key account', 'ACTIVE', 'Le Ngoc Vi',
     '2024-03-10 09:00:00+00'),
    ('22222222-2222-4222-8222-222222222222', 'CUS-0002', 'Cat Lai Terminal Co', 'CLL',
     '0302987651', 'Cat Lai Industrial Zone, Thu Duc, Ho Chi Minh City',
     'Tran Thi Mai', 'Director', 'Key account', 'ACTIVE', 'Le Ngoc Vi',
     '2024-05-22 09:00:00+00'),
    ('33333333-3333-4333-8333-333333333333', 'CUS-0003', 'VN Logistics JSC', 'VNL',
     '0303445566', '123 Le Loi, District 1, Ho Chi Minh City',
     'Le Quoc Anh', 'CEO', 'Strategic', 'ACTIVE', 'Nguyen Minh',
     '2024-06-15 09:00:00+00'),
    ('44444444-4444-4444-8444-444444444444', 'CUS-0004', 'Hai Phong Depot Ltd', 'HPD',
     '0200334455', 'Dinh Vu Industrial Zone, Hai Phong',
     'Pham Thu Trang', 'Manager', 'SME', 'ACTIVE', 'Nguyen Minh',
     '2025-01-12 09:00:00+00'),
    ('55555555-5555-4555-8555-555555555555', 'CUS-0005', 'Tan Cang Logistics', 'TCL',
     '0304556677', 'Cat Lai, Ho Chi Minh City',
     'Do Minh Khoa', 'Deputy Director', 'Key account', 'ACTIVE', 'Le Ngoc Vi',
     '2025-02-08 09:00:00+00'),
    ('66666666-6666-4666-8666-666666666666', 'CUS-0006', 'Mekong Freight Co', 'MFC',
     '0305667788', 'Ninh Kieu, Can Tho',
     'Vu Hoang Nam', 'Owner', 'SME', 'SUSPENDED', 'Le Ngoc Vi',
     '2025-04-19 09:00:00+00'),
    ('77777777-7777-4777-8777-777777777777', 'CUS-0007', 'Danang Cargo Services', 'DCS',
     '0401778899', 'Lien Chieu, Da Nang',
     'Hoang Thi Lan', 'Director', 'SME', 'ACTIVE', 'Tran Thu Ha',
     '2025-06-30 09:00:00+00'),
    ('88888888-8888-4888-8888-888888888888', 'CUS-0008', 'Binh Duong Warehouse', 'BDW',
     '0306889900', 'Di An, Binh Duong',
     'Ngo Duc Thang', 'Manager', 'SME', 'ACTIVE', 'Tran Thu Ha',
     '2025-09-14 09:00:00+00');

-- contacts (every customer has a primary contact with an email — D10 addressing)
insert into contract.customer_contact
    (id, customer_id, full_name, title, email, phone, is_primary)
values
    ('c1111111-1111-4111-8111-111111111101', '11111111-1111-4111-8111-111111111111',
     'Nguyen Van Hai', 'Deputy Director', 'hai.nv@sps.vn', '+84 28 3826 1234', true),
    ('c1111111-1111-4111-8111-111111111102', '11111111-1111-4111-8111-111111111111',
     'Tran Van Duc', 'Sales Manager', 'duc.tv@sps.vn', '+84 90 123 4567', false),
    ('c2222222-2222-4222-8222-222222222201', '22222222-2222-4222-8222-222222222222',
     'Tran Thi Mai', 'Director', 'mai.tt@catlai.vn', '+84 28 3900 1122', true),
    ('c3333333-3333-4333-8333-333333333301', '33333333-3333-4333-8333-333333333333',
     'Le Quoc Anh', 'CEO', 'anh.lq@vnlog.vn', '+84 91 234 5678', true),
    ('c3333333-3333-4333-8333-333333333302', '33333333-3333-4333-8333-333333333333',
     'Pham Thi Hoa', 'Accountant', 'hoa.pt@vnlog.vn', '+84 91 234 5679', false),
    ('c3333333-3333-4333-8333-333333333303', '33333333-3333-4333-8333-333333333333',
     'Bui Van Cuong', 'Operations', 'cuong.bv@vnlog.vn', null, false),
    ('c4444444-4444-4444-8444-444444444401', '44444444-4444-4444-8444-444444444444',
     'Pham Thu Trang', 'Manager', 'trang.pt@hpd.vn', '+84 225 3999 888', true),
    ('c5555555-5555-4555-8555-555555555501', '55555555-5555-4555-8555-555555555555',
     'Do Minh Khoa', 'Deputy Director', 'khoa.dm@tancang.vn', '+84 28 3899 7741', true),
    ('c5555555-5555-4555-8555-555555555502', '55555555-5555-4555-8555-555555555555',
     'Ly Thi Nhung', 'Sales', 'nhung.lt@tancang.vn', null, false),
    ('c6666666-6666-4666-8666-666666666601', '66666666-6666-4666-8666-666666666666',
     'Vu Hoang Nam', 'Owner', 'nam.vh@mekong.vn', '+84 292 3891 455', true),
    ('c7777777-7777-4777-8777-777777777701', '77777777-7777-4777-8777-777777777777',
     'Hoang Thi Lan', 'Director', 'lan.ht@dncargo.vn', '+84 236 3777 210', true),
    ('c8888888-8888-4888-8888-888888888801', '88888888-8888-4888-8888-888888888888',
     'Ngo Duc Thang', 'Manager', 'thang.nd@bdw.vn', '+84 274 3790 650', true);

-- contracts (12: c1 x3, c2 x2, c3 x2, c4/c5 APPROVED with future valid_from so the
-- D14d sweep leaves them; c6 DRAFT is incomplete + parent SUSPENDED for CTR-02 demos)
insert into contract.contract
    (id, contract_no, customer_id, description, service_group, value, currency,
     valid_from, valid_to, payment_term, billing_cycle, vat_rate, penalty_terms,
     service_clause, status, created_by_name, created_at)
values
    ('d1111111-1111-4111-8111-111111111111', 'CTR-2026-0001',
     '11111111-1111-4111-8111-111111111111', 'Stevedoring at Cat Lai and Tan Cang terminals',
     'STEVEDORING', 2450000000, 'VND', '2026-03-01', '2027-02-28', 'NET30', 'MONTHLY', 8,
     '0.05%/day overdue',
     'Contractor shall provide stevedoring, lashing and container handling services.',
     'ACTIVE', 'Le Ngoc Vi', '2026-01-15 09:00:00+00'),
    ('d2222222-2222-4222-8222-222222222222', 'CTR-2026-0002',
     '22222222-2222-4222-8222-222222222222', 'Warehousing at Cat Lai terminal',
     'WAREHOUSING', 1180000000, 'VND', '2026-02-15', '2027-02-14', 'NET30', 'MONTHLY', 8,
     null, null,
     'ACTIVE', 'Le Ngoc Vi', '2026-01-18 09:00:00+00'),
    ('d3333333-3333-4333-8333-333333333333', 'CTR-2026-0003',
     '33333333-3333-4333-8333-333333333333', 'North-South transportation lanes',
     'TRANSPORTATION', 3920000000, 'VND', '2026-02-01', '2027-01-31', 'NET45', 'MONTHLY', 10,
     null, null,
     'ACTIVE', 'Nguyen Minh', '2026-01-20 09:00:00+00'),
    ('d4444444-4444-4444-8444-444444444444', 'CTR-2026-0004',
     '44444444-4444-4444-8444-444444444444', 'Depot warehousing Hai Phong',
     'WAREHOUSING', 860000000, 'VND', '2026-11-01', '2027-10-31', 'NET30', 'MONTHLY', 8,
     null, null,
     'APPROVED', 'Nguyen Minh', '2026-08-02 09:00:00+00'),
    ('d5555555-5555-4555-8555-555555555555', 'CTR-2026-0005',
     '55555555-5555-4555-8555-555555555555', 'Container handling Tan Cang',
     'CONTAINER_HANDLING', 5600000000, 'VND', '2026-10-10', '2027-10-09', 'NET30', 'MONTHLY', 8,
     null, null,
     'APPROVED', 'Le Ngoc Vi', '2026-08-11 09:00:00+00'),
    ('d6666666-6666-4666-8666-666666666666', 'CTR-2026-0006',
     '66666666-6666-4666-8666-666666666666', 'Mekong delta trucking (incomplete draft)',
     'TRANSPORTATION', 1340000000, 'VND', '2026-09-01', '2027-08-31', null, 'MONTHLY', null,
     null, null,
     'DRAFT', 'Le Ngoc Vi', '2026-09-01 09:00:00+00'),
    ('d7777777-7777-4777-8777-777777777777', 'CTR-2025-0001',
     '77777777-7777-4777-8777-777777777777', 'Da Nang stevedoring',
     'STEVEDORING', 2010000000, 'VND', '2025-12-01', '2026-11-30', 'NET30', 'MONTHLY', 8,
     null, null,
     'ACTIVE', 'Tran Thu Ha', '2025-10-20 09:00:00+00'),
    ('d8888888-8888-4888-8888-888888888888', 'CTR-2025-0002',
     '88888888-8888-4888-8888-888888888888', 'Binh Duong warehousing (rejected scope)',
     'WAREHOUSING', 640000000, 'VND', '2025-11-01', '2026-10-31', 'NET30', 'MONTHLY', 8,
     null, null,
     'REJECTED', 'Tran Thu Ha', '2025-09-25 09:00:00+00'),
    ('d9999999-9999-4999-8999-999999999999', 'CTR-2025-0003',
     '11111111-1111-4111-8111-111111111111', 'Container handling 2025 season',
     'CONTAINER_HANDLING', 4120000000, 'VND', '2025-09-01', '2026-08-31', 'NET30', 'MONTHLY', 8,
     null, null,
     'EXPIRED', 'Le Ngoc Vi', '2025-07-14 09:00:00+00'),
    ('daaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1', 'CTR-2026-0007',
     '11111111-1111-4111-8111-111111111111', 'Overflow warehousing 2026',
     'WAREHOUSING', 1860000000, 'VND', '2026-09-01', '2027-08-31', 'NET30', 'MONTHLY', 8,
     null, null,
     'DRAFT', 'Le Ngoc Vi', '2026-09-02 09:00:00+00'),
    ('dbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1', 'CTR-2026-0008',
     '22222222-2222-4222-8222-222222222222', 'Cross-dock transportation',
     'TRANSPORTATION', 920000000, 'VND', '2026-09-05', '2027-09-04', 'NET15', 'MONTHLY', 5,
     null, null,
     'DRAFT', 'Nguyen Minh', '2026-09-02 09:00:00+00'),
    ('dccccccc-cccc-4ccc-8ccc-ccccccccccc1', 'CTR-2024-0001',
     '33333333-3333-4333-8333-333333333333', 'Legacy stevedoring agreement',
     'STEVEDORING', 3240000000, 'VND', '2024-06-01', '2025-05-31', 'NET30', 'MONTHLY', 10,
     null, null,
     'EXPIRED', 'Nguyen Minh', '2024-04-20 09:00:00+00');

-- addendum (TERM_EXTENSION already applied to CTR-2026-0001: parent valid_to matches)
insert into contract.addendum
    (id, addendum_no, contract_id, change_type, description, effective_from,
     new_valid_to, payment_term_override, status, created_by_name, created_at)
values
    ('e1111111-1111-4111-8111-111111111111', 'ADD-2026-0001',
     'd1111111-1111-4111-8111-111111111111', 'TERM_EXTENSION', 'Gia han den 28/02/2027',
     '2026-06-01', '2027-02-28', null, 'ACTIVE', 'Le Ngoc Vi', '2026-05-10 09:00:00+00'),
-- DRAFT ADDED_SERVICE on an ACTIVE contract (submittable after attachment upload)
    ('e2222222-2222-4222-8222-222222222222', 'ADD-2026-0002',
     'd3333333-3333-4333-8333-333333333333', 'ADDED_SERVICE', 'Bo sung dich vu tang ca va trucking',
     '2026-09-10', null, null, 'DRAFT', 'Nguyen Minh', '2026-09-03 09:00:00+00'),
-- APPROVED PAYMENT_TERMS with future effective_from (D14d sweep leaves it until 15/10)
    ('e3333333-3333-4333-8333-333333333333', 'ADD-2026-0003',
     'd5555555-5555-4555-8555-555555555555', 'PAYMENT_TERMS', 'Doi dieu khoan thanh toan NET45',
     '2026-10-15', null, 'NET45', 'APPROVED', 'Le Ngoc Vi', '2026-08-20 09:00:00+00'),
-- ACTIVE UNIT_PRICE_CHANGE (record-only per D8, no parent effect)
    ('e4444444-4444-4444-8444-444444444444', 'ADD-2026-0004',
     'd7777777-7777-4777-8777-777777777777', 'UNIT_PRICE_CHANGE', 'Dieu chinh don gia theo mua cao diem',
     '2026-02-01', null, null, 'ACTIVE', 'Tran Thu Ha', '2026-01-12 09:00:00+00');

-- service lines for the ADDED_SERVICE draft
insert into contract.addendum_service
    (id, addendum_id, service_code, service_name, unit, scope_note)
values
    ('f2222222-2222-4222-8222-222222222221',
     'e2222222-2222-4222-8222-222222222222', 'STV-004',
     'Stevedoring - overtime gang', 'TEU', 'Night shift only'),
    ('f2222222-2222-4222-8222-222222222222',
     'e2222222-2222-4222-8222-222222222222', 'TRP-011',
     'Last-mile trucking', 'trip', 'Cat Lai - ICD Trang Bom');

-- status_history chains (registry §9 edges; abbreviated chains carry an explicit note)
-- CTR-2026-0001: full lifecycle to ACTIVE
insert into contract.status_history
    (entity_type, entity_id, from_status, to_status, trigger_kind, trigger_ref,
     actor_name, note, occurred_at)
values
    ('CONTRACT', 'd1111111-1111-4111-8111-111111111111', null, 'DRAFT', 'U', null,
     'Le Ngoc Vi', 'Seeded demo data', '2026-01-15 09:00:00+00'),
    ('CONTRACT', 'd1111111-1111-4111-8111-111111111111', 'DRAFT', 'SUBMITTED', 'U', null,
     'Le Ngoc Vi', 'Seeded demo data', '2026-01-20 09:00:00+00'),
    ('CONTRACT', 'd1111111-1111-4111-8111-111111111111', 'SUBMITTED', 'UNDER_REVIEW', 'W', gen_random_uuid(),
     'Le Ngoc Vi', 'Seeded demo data', '2026-01-21 09:00:00+00'),
    ('CONTRACT', 'd1111111-1111-4111-8111-111111111111', 'UNDER_REVIEW', 'APPROVED', 'W', gen_random_uuid(),
     'Tran Thu Ha', 'Seeded demo data', '2026-02-10 09:00:00+00'),
    ('CONTRACT', 'd1111111-1111-4111-8111-111111111111', 'APPROVED', 'ACTIVE', 'S', null,
     'system', 'Seeded demo data', '2026-03-01 09:00:00+00'),
-- CTR-2026-0002 / 0003 / CTR-2025-0001: same shape, abbreviated timestamps
    ('CONTRACT', 'd2222222-2222-4222-8222-222222222222', null, 'DRAFT', 'U', null,
     'Le Ngoc Vi', 'Seeded demo data', '2026-01-18 09:00:00+00'),
    ('CONTRACT', 'd2222222-2222-4222-8222-222222222222', 'DRAFT', 'SUBMITTED', 'U', null,
     'Le Ngoc Vi', 'Seeded demo data', '2026-01-22 09:00:00+00'),
    ('CONTRACT', 'd2222222-2222-4222-8222-222222222222', 'SUBMITTED', 'UNDER_REVIEW', 'W', gen_random_uuid(),
     'Le Ngoc Vi', 'Seeded demo data', '2026-01-23 09:00:00+00'),
    ('CONTRACT', 'd2222222-2222-4222-8222-222222222222', 'UNDER_REVIEW', 'APPROVED', 'W', gen_random_uuid(),
     'Tran Thu Ha', 'Seeded demo data', '2026-02-05 09:00:00+00'),
    ('CONTRACT', 'd2222222-2222-4222-8222-222222222222', 'APPROVED', 'ACTIVE', 'S', null,
     'system', 'Seeded demo data', '2026-02-15 09:00:00+00'),
    ('CONTRACT', 'd3333333-3333-4333-8333-333333333333', null, 'DRAFT', 'U', null,
     'Nguyen Minh', 'Seeded demo data', '2026-01-20 09:00:00+00'),
    ('CONTRACT', 'd3333333-3333-4333-8333-333333333333', 'DRAFT', 'SUBMITTED', 'U', null,
     'Nguyen Minh', 'Seeded demo data', '2026-01-24 09:00:00+00'),
    ('CONTRACT', 'd3333333-3333-4333-8333-333333333333', 'SUBMITTED', 'UNDER_REVIEW', 'W', gen_random_uuid(),
     'Nguyen Minh', 'Seeded demo data', '2026-01-25 09:00:00+00'),
    ('CONTRACT', 'd3333333-3333-4333-8333-333333333333', 'UNDER_REVIEW', 'APPROVED', 'W', gen_random_uuid(),
     'Tran Thu Ha', 'Seeded demo data', '2026-01-30 09:00:00+00'),
    ('CONTRACT', 'd3333333-3333-4333-8333-333333333333', 'APPROVED', 'ACTIVE', 'S', null,
     'system', 'Seeded demo data', '2026-02-01 09:00:00+00'),
    ('CONTRACT', 'd7777777-7777-4777-8777-777777777777', null, 'DRAFT', 'U', null,
     'Tran Thu Ha', 'Seeded demo data', '2025-10-20 09:00:00+00'),
    ('CONTRACT', 'd7777777-7777-4777-8777-777777777777', 'DRAFT', 'SUBMITTED', 'U', null,
     'Tran Thu Ha', 'Seeded demo data', '2025-10-25 09:00:00+00'),
    ('CONTRACT', 'd7777777-7777-4777-8777-777777777777', 'SUBMITTED', 'UNDER_REVIEW', 'W', gen_random_uuid(),
     'Tran Thu Ha', 'Seeded demo data', '2025-10-26 09:00:00+00'),
    ('CONTRACT', 'd7777777-7777-4777-8777-777777777777', 'UNDER_REVIEW', 'APPROVED', 'W', gen_random_uuid(),
     'Tran Thu Ha', 'Seeded demo data', '2025-11-10 09:00:00+00'),
    ('CONTRACT', 'd7777777-7777-4777-8777-777777777777', 'APPROVED', 'ACTIVE', 'S', null,
     'system', 'Seeded demo data', '2025-12-01 09:00:00+00'),
-- APPROVED seeds: chain stops at APPROVED
    ('CONTRACT', 'd4444444-4444-4444-8444-444444444444', null, 'DRAFT', 'U', null,
     'Nguyen Minh', 'Seeded demo data', '2026-08-02 09:00:00+00'),
    ('CONTRACT', 'd4444444-4444-4444-8444-444444444444', 'DRAFT', 'SUBMITTED', 'U', null,
     'Nguyen Minh', 'Seeded demo data', '2026-08-10 09:00:00+00'),
    ('CONTRACT', 'd4444444-4444-4444-8444-444444444444', 'SUBMITTED', 'UNDER_REVIEW', 'W', gen_random_uuid(),
     'Nguyen Minh', 'Seeded demo data', '2026-08-11 09:00:00+00'),
    ('CONTRACT', 'd4444444-4444-4444-8444-444444444444', 'UNDER_REVIEW', 'APPROVED', 'W', gen_random_uuid(),
     'Tran Thu Ha', 'Seeded demo data', '2026-08-20 09:00:00+00'),
    ('CONTRACT', 'd5555555-5555-4555-8555-555555555555', null, 'DRAFT', 'U', null,
     'Le Ngoc Vi', 'Seeded demo data', '2026-08-11 09:00:00+00'),
    ('CONTRACT', 'd5555555-5555-4555-8555-555555555555', 'DRAFT', 'SUBMITTED', 'U', null,
     'Le Ngoc Vi', 'Seeded demo data', '2026-08-15 09:00:00+00'),
    ('CONTRACT', 'd5555555-5555-4555-8555-555555555555', 'SUBMITTED', 'UNDER_REVIEW', 'W', gen_random_uuid(),
     'Le Ngoc Vi', 'Seeded demo data', '2026-08-16 09:00:00+00'),
    ('CONTRACT', 'd5555555-5555-4555-8555-555555555555', 'UNDER_REVIEW', 'APPROVED', 'W', gen_random_uuid(),
     'Tran Thu Ha', 'Seeded demo data', '2026-08-25 09:00:00+00'),
-- REJECTED seed
    ('CONTRACT', 'd8888888-8888-4888-8888-888888888888', null, 'DRAFT', 'U', null,
     'Tran Thu Ha', 'Seeded demo data', '2025-09-25 09:00:00+00'),
    ('CONTRACT', 'd8888888-8888-4888-8888-888888888888', 'DRAFT', 'SUBMITTED', 'U', null,
     'Tran Thu Ha', 'Seeded demo data', '2025-10-01 09:00:00+00'),
    ('CONTRACT', 'd8888888-8888-4888-8888-888888888888', 'SUBMITTED', 'UNDER_REVIEW', 'W', gen_random_uuid(),
     'Tran Thu Ha', 'Seeded demo data', '2025-10-02 09:00:00+00'),
    ('CONTRACT', 'd8888888-8888-4888-8888-888888888888', 'UNDER_REVIEW', 'REJECTED', 'W', gen_random_uuid(),
     'Tran Thu Ha', 'Seeded demo data', '2025-10-08 09:00:00+00'),
-- EXPIRED seeds
    ('CONTRACT', 'd9999999-9999-4999-8999-999999999999', null, 'DRAFT', 'U', null,
     'Le Ngoc Vi', 'Seeded demo data', '2025-07-14 09:00:00+00'),
    ('CONTRACT', 'd9999999-9999-4999-8999-999999999999', 'DRAFT', 'SUBMITTED', 'U', null,
     'Le Ngoc Vi', 'Seeded demo data', '2025-07-20 09:00:00+00'),
    ('CONTRACT', 'd9999999-9999-4999-8999-999999999999', 'SUBMITTED', 'UNDER_REVIEW', 'W', gen_random_uuid(),
     'Le Ngoc Vi', 'Seeded demo data', '2025-07-21 09:00:00+00'),
    ('CONTRACT', 'd9999999-9999-4999-8999-999999999999', 'UNDER_REVIEW', 'APPROVED', 'W', gen_random_uuid(),
     'Tran Thu Ha', 'Seeded demo data', '2025-08-05 09:00:00+00'),
    ('CONTRACT', 'd9999999-9999-4999-8999-999999999999', 'APPROVED', 'ACTIVE', 'S', null,
     'system', 'Seeded demo data', '2025-09-01 09:00:00+00'),
    ('CONTRACT', 'd9999999-9999-4999-8999-999999999999', 'ACTIVE', 'EXPIRED', 'S', null,
     'system', 'Seeded demo data', '2026-09-01 09:00:00+00'),
    ('CONTRACT', 'dccccccc-cccc-4ccc-8ccc-ccccccccccc1', null, 'DRAFT', 'U', null,
     'Nguyen Minh', 'Seeded demo data', '2024-04-20 09:00:00+00'),
    ('CONTRACT', 'dccccccc-cccc-4ccc-8ccc-ccccccccccc1', 'DRAFT', 'SUBMITTED', 'U', null,
     'Nguyen Minh', 'Seeded demo data', '2024-04-25 09:00:00+00'),
    ('CONTRACT', 'dccccccc-cccc-4ccc-8ccc-ccccccccccc1', 'SUBMITTED', 'UNDER_REVIEW', 'W', gen_random_uuid(),
     'Nguyen Minh', 'Seeded demo data', '2024-04-26 09:00:00+00'),
    ('CONTRACT', 'dccccccc-cccc-4ccc-8ccc-ccccccccccc1', 'UNDER_REVIEW', 'APPROVED', 'W', gen_random_uuid(),
     'Tran Thu Ha', 'Seeded demo data', '2024-05-10 09:00:00+00'),
    ('CONTRACT', 'dccccccc-cccc-4ccc-8ccc-ccccccccccc1', 'APPROVED', 'ACTIVE', 'S', null,
     'system', 'Seeded demo data', '2024-06-01 09:00:00+00'),
    ('CONTRACT', 'dccccccc-cccc-4ccc-8ccc-ccccccccccc1', 'ACTIVE', 'EXPIRED', 'S', null,
     'system', 'Seeded demo data', '2025-06-01 09:00:00+00'),
-- DRAFT seeds: creation row only
    ('CONTRACT', 'd6666666-6666-4666-8666-666666666666', null, 'DRAFT', 'U', null,
     'Le Ngoc Vi', 'Seeded demo data', '2026-09-01 09:00:00+00'),
    ('CONTRACT', 'daaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1', null, 'DRAFT', 'U', null,
     'Le Ngoc Vi', 'Seeded demo data', '2026-09-02 09:00:00+00'),
    ('CONTRACT', 'dbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1', null, 'DRAFT', 'U', null,
     'Nguyen Minh', 'Seeded demo data', '2026-09-02 09:00:00+00'),
-- ADD-2026-0001: full chain to ACTIVE
    ('ADDENDUM', 'e1111111-1111-4111-8111-111111111111', null, 'DRAFT', 'U', null,
     'Le Ngoc Vi', 'Seeded demo data', '2026-05-10 09:00:00+00'),
    ('ADDENDUM', 'e1111111-1111-4111-8111-111111111111', 'DRAFT', 'SUBMITTED', 'U', null,
     'Le Ngoc Vi', 'Seeded demo data', '2026-05-12 09:00:00+00'),
    ('ADDENDUM', 'e1111111-1111-4111-8111-111111111111', 'SUBMITTED', 'UNDER_REVIEW', 'W', gen_random_uuid(),
     'Le Ngoc Vi', 'Seeded demo data', '2026-05-13 09:00:00+00'),
    ('ADDENDUM', 'e1111111-1111-4111-8111-111111111111', 'UNDER_REVIEW', 'APPROVED', 'W', gen_random_uuid(),
     'Tran Thu Ha', 'Seeded demo data', '2026-05-20 09:00:00+00'),
    ('ADDENDUM', 'e1111111-1111-4111-8111-111111111111', 'APPROVED', 'ACTIVE', 'S', null,
     'system', 'Seeded demo data', '2026-06-01 09:00:00+00'),
-- ADD-2026-0002: DRAFT creation row only
    ('ADDENDUM', 'e2222222-2222-4222-8222-222222222222', null, 'DRAFT', 'U', null,
     'Nguyen Minh', 'Seeded demo data', '2026-09-03 09:00:00+00'),
-- ADD-2026-0003: chain stops at APPROVED (future effective_from)
    ('ADDENDUM', 'e3333333-3333-4333-8333-333333333333', null, 'DRAFT', 'U', null,
     'Le Ngoc Vi', 'Seeded demo data', '2026-08-20 09:00:00+00'),
    ('ADDENDUM', 'e3333333-3333-4333-8333-333333333333', 'DRAFT', 'SUBMITTED', 'U', null,
     'Le Ngoc Vi', 'Seeded demo data', '2026-08-22 09:00:00+00'),
    ('ADDENDUM', 'e3333333-3333-4333-8333-333333333333', 'SUBMITTED', 'UNDER_REVIEW', 'W', gen_random_uuid(),
     'Le Ngoc Vi', 'Seeded demo data', '2026-08-23 09:00:00+00'),
    ('ADDENDUM', 'e3333333-3333-4333-8333-333333333333', 'UNDER_REVIEW', 'APPROVED', 'W', gen_random_uuid(),
     'Tran Thu Ha', 'Seeded demo data', '2026-08-28 09:00:00+00'),
-- ADD-2026-0004: full chain to ACTIVE
    ('ADDENDUM', 'e4444444-4444-4444-8444-444444444444', null, 'DRAFT', 'U', null,
     'Tran Thu Ha', 'Seeded demo data', '2026-01-12 09:00:00+00'),
    ('ADDENDUM', 'e4444444-4444-4444-8444-444444444444', 'DRAFT', 'SUBMITTED', 'U', null,
     'Tran Thu Ha', 'Seeded demo data', '2026-01-15 09:00:00+00'),
    ('ADDENDUM', 'e4444444-4444-4444-8444-444444444444', 'SUBMITTED', 'UNDER_REVIEW', 'W', gen_random_uuid(),
     'Tran Thu Ha', 'Seeded demo data', '2026-01-16 09:00:00+00'),
    ('ADDENDUM', 'e4444444-4444-4444-8444-444444444444', 'UNDER_REVIEW', 'APPROVED', 'W', gen_random_uuid(),
     'Tran Thu Ha', 'Seeded demo data', '2026-01-22 09:00:00+00'),
    ('ADDENDUM', 'e4444444-4444-4444-8444-444444444444', 'APPROVED', 'ACTIVE', 'S', null,
     'system', 'Seeded demo data', '2026-02-01 09:00:00+00');

-- counters past the seeds
update contract.customer_counter set next_seq = 9 where id = true;
insert into contract.document_counter (doc_type, year, next_seq) values
    ('CONTRACT', 2026, 9),
    ('CONTRACT', 2025, 4),
    ('CONTRACT', 2024, 2),
    ('ADDENDUM', 2026, 5);
