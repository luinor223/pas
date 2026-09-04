-- registry D-actor (2026-09-04): non-null SYSTEM principal replaces the null system-actor convention
update esign.status_history set actor_id = '00000000-0000-0000-0000-000000000001' where actor_id is null;
update esign.status_history set actor_name = 'System' where actor_name is null;
alter table esign.status_history alter column actor_id set not null;
