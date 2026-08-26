-- Workflow engine schema (db-workflow.md) — pas_workflow / schema workflow
create schema if not exists workflow;

-- document_type_config (D10 — esign_enabled + provider)
create table workflow.document_type_config (
    id              uuid primary key default gen_random_uuid(),
    code            text not null unique check (code in ('CONTRACT','ADDENDUM','PRICE_LIST','PAYMENT_STATEMENT')),
    name            text not null,
    number_prefix   text not null,
    esign_enabled   boolean not null default false,
    esign_provider  text
);

-- workflow_definition — versioned, one active per document_type (partial unique)
create table workflow.workflow_definition (
    id                  uuid primary key default gen_random_uuid(),
    document_type_id    uuid not null references workflow.document_type_config(id),
    version_no          int  not null check (version_no > 0),
    name                text not null,
    is_active           boolean not null default false,
    created_at          timestamptz not null default now(),
    created_by          uuid
);
create unique index uq_workflow_definition_type_version
    on workflow.workflow_definition(document_type_id, version_no);
create unique index uq_workflow_definition_active
    on workflow.workflow_definition(document_type_id) where is_active;

-- workflow_step_definition — ordered steps within a definition
create table workflow.workflow_step_definition (
    id              uuid primary key default gen_random_uuid(),
    definition_id   uuid not null references workflow.workflow_definition(id) on delete cascade,
    step_order      int  not null check (step_order > 0),
    name            text not null,
    approver_role   text not null,
    sla_hours       int  not null default 72 check (sla_hours > 0)
);
create unique index uq_workflow_step_definition_order
    on workflow.workflow_step_definition(definition_id, step_order);

-- workflow_instance — one per document submit attempt (idempotency_key permanent)
create table workflow.workflow_instance (
    id                  uuid primary key default gen_random_uuid(),
    definition_id       uuid not null references workflow.workflow_definition(id),
    idempotency_key     uuid not null unique,
    document_type_code  text not null check (document_type_code in ('CONTRACT','ADDENDUM','PRICE_LIST','PAYMENT_STATEMENT')),
    document_id         uuid not null,
    document_no         text not null,
    customer_name       text,
    priority            text not null default 'NORMAL' check (priority in ('LOW','NORMAL','HIGH','URGENT')),
    status              text not null check (status in ('IN_PROGRESS','APPROVED','REJECTED','REVISION_REQUESTED','CANCELLED')),
    current_step_order  int,
    requested_by        uuid,
    requested_by_name   text,
    created_at          timestamptz not null default now(),
    completed_at        timestamptz
);
-- D4: concurrent different-key submissions blocked only while IN_PROGRESS
create unique index uq_workflow_instance_doc_in_progress
    on workflow.workflow_instance(document_type_code, document_id) where status = 'IN_PROGRESS';
create index idx_workflow_instance_doc
    on workflow.workflow_instance(document_type_code, document_id, created_at desc);
create index idx_workflow_instance_requested_by
    on workflow.workflow_instance(requested_by);
create index idx_workflow_instance_status
    on workflow.workflow_instance(status);

-- workflow_step_instance — snapshot of a step definition at instance creation
create table workflow.workflow_step_instance (
    id                  uuid primary key default gen_random_uuid(),
    instance_id         uuid not null references workflow.workflow_instance(id) on delete cascade,
    step_order          int  not null check (step_order > 0),
    name                text not null,
    approver_role       text not null,
    sla_hours           int  not null,
    status              text not null check (status in ('PENDING','ACTIVE','APPROVED','REJECTED','REVISION_REQUESTED','CANCELLED')),
    version             int  not null default 0,
    activated_at        timestamptz,
    completed_at        timestamptz,
    overdue_notified_at timestamptz,
    acted_by            uuid,
    acted_by_name       text
);
create unique index uq_workflow_step_instance_order
    on workflow.workflow_step_instance(instance_id, step_order);
create index idx_workflow_step_instance_status
    on workflow.workflow_step_instance(instance_id, status);

-- step_assignee — snapshot of users who may act on this step (APR-01, whole chain at creation)
create table workflow.step_assignee (
    id                  uuid primary key default gen_random_uuid(),
    step_instance_id    uuid not null references workflow.workflow_step_instance(id) on delete cascade,
    user_id             uuid not null,
    user_name           text not null
);
create index idx_step_assignee_step
    on workflow.step_assignee(step_instance_id);
create index idx_step_assignee_user
    on workflow.step_assignee(user_id);

-- workflow_action — approval history (display), APR-03 comment guard
create table workflow.workflow_action (
    id                  uuid primary key default gen_random_uuid(),
    step_instance_id    uuid not null references workflow.workflow_step_instance(id) on delete cascade,
    action              text not null check (action in ('APPROVE','REJECT','REQUEST_REVISION')),
    actor_id            uuid,
    actor_name          text,
    comment             text,
    created_at          timestamptz not null default now(),
    constraint chk_workflow_action_comment
        check (action = 'APPROVE' or (comment is not null and btrim(comment) <> ''))
);
create index idx_workflow_action_step
    on workflow.workflow_action(step_instance_id);
create index idx_workflow_action_actor
    on workflow.workflow_action(actor_id);

-- outbox (M2 / D6) — one per service, never per event type
create table workflow.outbox (
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
create index idx_workflow_outbox_unpublished
    on workflow.outbox(created_at)
    where published_at is null and cancelled_at is null;
