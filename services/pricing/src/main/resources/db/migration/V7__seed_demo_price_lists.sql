-- Demo price lists for runbook (seeded so billing's PAY-01/PAY-03 can be shown without live creation).
-- One EFFECTIVE list for CONTRACT:CTR-2026-0001, valid 2026-10-01 → 2027-09-30,
-- with the three lines used in Volume demo (LIFT_ON_OFF, STORAGE_OVERTIME, LASHING).
-- The list is ready for `Operations 2026-09 LOCKED + volumes → Billing Calculate` happy path.
-- Demo can still create additional lists/versions live to show PRC-01..06 fail-first.

insert into pricing.price_list (id, price_list_no, customer_id, contract_id, service_group, scope_key, note, created_at, updated_at)
values (
  '0ed60146-15c9-48f4-a2fb-1b5a285ec400', 'PRC-0001',
  null,
  'd1111111-1111-4111-8111-111111111111',  -- CTR-2026-0001 (ACTIVE contract from V3 demo seed)
  null,
  'CONTRACT:d1111111-1111-4111-8111-111111111111',
  'Demo price list — STEVEDORING for CTR-2026-0001 (seeded, EFFECTIVE). Mock for runbook §3+4.',
  now(), now()
) on conflict (id) do nothing;

-- Advance the sequence past the seeded number so the next UI-created list becomes PRC-0002+
select setval('pricing.price_list_no_seq', 10, true);

insert into pricing.price_list_version (id, price_list_id, version_no, status, valid_from, valid_to, scope_key, version, created_at, updated_at)
values (
  '72d08a66-ed07-4762-b0fa-04608bd9519f', '0ed60146-15c9-48f4-a2fb-1b5a285ec400', 1, 'EFFECTIVE',
  '2026-08-01', '2027-09-30',
  'CONTRACT:d1111111-1111-4111-8111-111111111111',
  0, now(), now()
) on conflict (id) do nothing;

-- Status history for the seeded EFFECTIVE version (one row, system grant)
insert into pricing.status_history (id, version_id, from_status, to_status, trigger_kind, note, created_at, created_by)
select gen_random_uuid(), '72d08a66-ed07-4762-b0fa-04608bd9519f'::uuid, null, 'EFFECTIVE', 'S', 'Seeded demo data — EFFECTIVE for runbook', now(), '00000000-0000-0000-0000-000000000001'::uuid
where not exists (select 1 from pricing.status_history where version_id = '72d08a66-ed07-4762-b0fa-04608bd9519f'::uuid);

-- Price lines (DRAFT-only edits normally, but seeded directly for demo)
insert into pricing.price_line (id, version_id, service_item_id, unit_price)
select '5589a2f7-e25c-4928-9c1a-dc6b0bf144b7'::uuid, '72d08a66-ed07-4762-b0fa-04608bd9519f'::uuid, si.id, 1200000
from pricing.service_item si where si.code = 'LIFT_ON_OFF'
on conflict (version_id, service_item_id) do nothing;

insert into pricing.price_line (id, version_id, service_item_id, unit_price)
select '219fab56-7d49-47cf-a521-e81f0ebd0b66'::uuid, '72d08a66-ed07-4762-b0fa-04608bd9519f'::uuid, si.id, 150000
from pricing.service_item si where si.code = 'STORAGE_OVERTIME'
on conflict (version_id, service_item_id) do nothing;

insert into pricing.price_line (id, version_id, service_item_id, unit_price)
select '3ea31bc8-bb1e-49c3-b926-b0b65317f84c'::uuid, '72d08a66-ed07-4762-b0fa-04608bd9519f'::uuid, si.id, 800000
from pricing.service_item si where si.code = 'LASHING'
on conflict (version_id, service_item_id) do nothing;
