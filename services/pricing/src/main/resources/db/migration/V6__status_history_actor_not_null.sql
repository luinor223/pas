-- Registry D-actor decision (2026-09-04): system-initiated actions are credited to the SYSTEM principal, never null.
update pricing.status_history set created_by = '00000000-0000-0000-0000-000000000001' where created_by is null;
alter table pricing.status_history alter column created_by set not null;
