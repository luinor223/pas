-- registry D-actor (2026-09-04): replace null system actor with non-null SYSTEM principal
update billing.status_history set actor_id = '00000000-0000-0000-0000-000000000001' where actor_id is null;
update billing.status_history set actor_name = 'System' where actor_name is null;
alter table billing.status_history alter column actor_id set not null;
