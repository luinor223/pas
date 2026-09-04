-- Registry D-actor decision (2026-09-04): a system-initiated flip credits the SYSTEM principal, never null.
update contract.status_history set actor_id = '00000000-0000-0000-0000-000000000001' where actor_id is null;
update contract.status_history set actor_name = 'System' where actor_name is null;
alter table contract.status_history alter column actor_id set not null;
