-- Audit schema (db-audit.md) — pas_audit / schema audit.
-- The single centralized trail (4.10, D15): a read model, not a bounded context.
create schema if not exists audit;

-- audit_record — id IS the producer's outbox row id, i.e. the envelope event_id. That makes the
-- primary key the dedup key (INSERT … ON CONFLICT DO NOTHING) and is why this service needs no
-- processed_event table. Rows are immutable: no updated_at, no version.
create table audit.audit_record (
    id               uuid primary key,
    source_service   text not null check (source_service in (
                         'identity-service','contract-service','pricing-service',
                         'operations-service','billing-service','workflow-service','esign-service')),
    -- free text: each context's own vocabulary, not centrally enumerable
    entity_type      text not null,
    entity_id        uuid not null,
    entity_no        text,
    action           text not null,
    -- null for system/scheduler actions; the *_name columns are write-time snapshots and are
    -- never resolved against identity, so a renamed user cannot change a past record (4.10)
    actor_id         uuid,
    actor_name       text,
    actor_department text,
    before_status    text,
    after_status     text,
    -- stored, indexed and returned, never interpreted — what stops this becoming a god-service
    changes          jsonb not null default '{}'::jsonb,
    note             text,
    ip_address       text,
    occurred_at      timestamptz not null
);

-- the hot path: every document's History tab
create index idx_audit_entity
    on audit.audit_record(entity_type, entity_id, occurred_at desc);
-- the cross-entity admin search (seq-02)
create index idx_audit_occurred on audit.audit_record(occurred_at desc);
create index idx_audit_actor on audit.audit_record(actor_id, occurred_at desc);
create index idx_audit_source on audit.audit_record(source_service, occurred_at desc);
create index idx_audit_action on audit.audit_record(action);
create index idx_audit_entity_no on audit.audit_record(entity_no);

-- INSERT + SELECT only (db-audit.md). The real gain of centralizing audit is that a business
-- service can no longer rewrite its own history, so the guarantee is enforced by the grant and
-- not only by AuditRecordRepository's method surface. Revoked from the owner too: owning the
-- table is exactly what would otherwise make the restriction cosmetic.
revoke update, delete on audit.audit_record from current_user;
