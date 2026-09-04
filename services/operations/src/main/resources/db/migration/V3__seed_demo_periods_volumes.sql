-- Demo periods and volumes for runbook (seeded so billing Calculate can be shown without live period/volume creation).
-- Period 2026-08 is LOCKED with 3 volumes for CTR-2026-0001 (ACTIVE contract) — used for PAY-02/03 happy path without live lock.
-- Period 2026-11 is OPEN (empty) for live demo of "New period → New volume → Lock" (2026-09/10 remain free for live demo).
-- Volumes snapshot service_name/unit at entry time (D7), so billing's PAY-03 snapshot is demonstrable.
-- See docs/demo-runbook.md "Bước 5 — Sản lượng & khóa kỳ".

insert into operations.operation_period (id, period_code, start_date, end_date, status, locked_by, locked_by_name, locked_at, created_at, updated_at)
values (
  '60fcab36-5837-4f5d-85d5-84d476629c58', '2026-08', '2026-08-01', '2026-08-31', 'LOCKED',
  'fbdf6a6f-8f7b-4114-9d6e-9507960c4cfc', 'Do Thi Ops', now(), now(), now()
) on conflict (id) do nothing;

insert into operations.operation_period (id, period_code, start_date, end_date, status, created_at, updated_at)
values (
  '6bbc2397-db7c-4a7d-930b-0b351a5c6861', '2026-11', '2026-11-01', '2026-11-30', 'OPEN',
  now(), now()
) on conflict (id) do nothing;

-- Avoid unique violation on period_code (unique constraint)
-- If re-running, the above on conflict (id) handles it, but period_code unique would still conflict if id differs.
-- Use ON CONFLICT (period_code) alternative by inserting via id conflict only; period_code conflict is same id here so fine.

insert into operations.volume_record (id, record_no, period_id, contract_id, customer_id, customer_name, service_code, service_name, unit, quantity, note, created_at, updated_at)
values (
  '2991ec66-22c6-4595-b0f8-aa01d0ae4da1', 'VOL-2026-0001',
  '60fcab36-5837-4f5d-85d5-84d476629c58',
  'd1111111-1111-4111-8111-111111111111',  -- CTR-2026-0001
  '11111111-1111-4111-8111-111111111111',  -- CUS-0001
  'Saigon Port Services JSC', 'LIFT_ON_OFF', 'Container lift on/off', 'TEU', 120, 'Demo volume Aug 2026 — vessel ABC (seeded)', now(), now()
) on conflict (id) do nothing;

insert into operations.volume_record (id, record_no, period_id, contract_id, customer_id, customer_name, service_code, service_name, unit, quantity, note, created_at, updated_at)
values (
  'a9ea34f5-cb46-464e-a8f2-194da1d5f427', 'VOL-2026-0002',
  '60fcab36-5837-4f5d-85d5-84d476629c58',
  'd1111111-1111-4111-8111-111111111111',
  '11111111-1111-4111-8111-111111111111',
  'Saigon Port Services JSC', 'STORAGE_OVERTIME', 'Storage beyond free time', 'day', 45, 'Overtime storage Aug (seeded)', now(), now()
) on conflict (id) do nothing;

insert into operations.volume_record (id, record_no, period_id, contract_id, customer_id, customer_name, service_code, service_name, unit, quantity, note, created_at, updated_at)
values (
  '4a0183fa-0735-4384-8c41-ce853d18d5fb', 'VOL-2026-0003',
  '60fcab36-5837-4f5d-85d5-84d476629c58',
  'd1111111-1111-4111-8111-111111111111',
  '11111111-1111-4111-8111-111111111111',
  'Saigon Port Services JSC', 'LASHING', 'Lashing & securing', 'TEU', 80, 'Lashing Aug (seeded)', now(), now()
) on conflict (id) do nothing;

-- Advance the volume seq past the seeded numbers so the next UI-created volume becomes VOL-2026-0004+
select setval('operations.volume_record_no_seq', 10, true);
