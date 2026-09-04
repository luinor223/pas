-- Registry D-actor decision (2026-09-04): a recorded action credits the SYSTEM principal, never
-- null. AuditRecorder now writes SystemActor.ID for system paths, so actor_id becomes NOT NULL.
-- No backfill: audit_record has no seed and V1 revokes UPDATE on it (append-only), and the wire
-- format lands on a fresh database (down-v). A pre-existing null row here is real corruption and
-- the alter should fail loudly rather than be silently rewritten.
alter table audit.audit_record alter column actor_id set not null;
