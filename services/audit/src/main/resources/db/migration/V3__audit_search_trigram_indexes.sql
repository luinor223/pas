create extension if not exists pg_trgm;

create index if not exists idx_audit_entity_no_trgm
    on audit.audit_record using gin (lower(entity_no) gin_trgm_ops);

create index if not exists idx_audit_actor_name_trgm
    on audit.audit_record using gin (lower(actor_name) gin_trgm_ops);
