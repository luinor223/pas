-- Fix P0-7: DB CHECK was ^[0-9]{4}-[0-9]{2}$ (allowed 2026-13), DTO is 0[1-9]|1[0-2]. Make DB match DTO.
alter table operations.operation_period drop constraint if exists operation_period_period_code_check;
-- PG auto-named check may also be period_code_check depending on version, try both
alter table operations.operation_period drop constraint if exists period_code_check;
alter table operations.operation_period add constraint chk_period_code_format check (period_code ~ '^[0-9]{4}-(0[1-9]|1[0-2])$');
