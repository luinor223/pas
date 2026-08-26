-- Seed document types + default workflow definitions (registry §7, db-workflow.md)
-- CONTRACT: Sales review → Legal review → Director sign-off (3 steps)
-- ADDENDUM: Legal review → Director (2)
-- PRICE_LIST: Commercial approval (SALES_MANAGER) → Director (2)
-- PAYMENT_STATEMENT: Accounting check → Director (2)

insert into workflow.document_type_config (id, code, name, number_prefix, esign_enabled, esign_provider) values
    ('11111111-1111-1111-1111-111111111111', 'CONTRACT',          'Contract',          'CTR', true,  'mock'),
    ('22222222-2222-2222-2222-222222222222', 'ADDENDUM',          'Addendum',          'ADD', true,  'mock'),
    ('33333333-3333-3333-3333-333333333333', 'PRICE_LIST',        'Price List',        'PRC', false, null),
    ('44444444-4444-4444-4444-444444444444', 'PAYMENT_STATEMENT', 'Payment Statement', 'PMT', true,  'mock');

-- Definitions (active versions)
insert into workflow.workflow_definition (id, document_type_id, version_no, name, is_active) values
    ('a1111111-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111', 1, 'Contract Approval v1', true),
    ('a2222222-2222-2222-2222-222222222222', '22222222-2222-2222-2222-222222222222', 1, 'Addendum Approval v1', true),
    ('a3333333-3333-3333-3333-333333333333', '33333333-3333-3333-3333-333333333333', 1, 'Price List Approval v1', true),
    ('a4444444-4444-4444-4444-444444444444', '44444444-4444-4444-4444-444444444444', 1, 'Payment Statement Approval v1', true);

-- CONTRACT steps (3)
insert into workflow.workflow_step_definition (id, definition_id, step_order, name, approver_role, sla_hours) values
    (gen_random_uuid(), 'a1111111-1111-1111-1111-111111111111', 1, 'Sales review',      'SALES_MANAGER',  48),
    (gen_random_uuid(), 'a1111111-1111-1111-1111-111111111111', 2, 'Legal review',      'LEGAL_REVIEWER', 48),
    (gen_random_uuid(), 'a1111111-1111-1111-1111-111111111111', 3, 'Director sign-off', 'DIRECTOR',       72);

-- ADDENDUM steps (2)
insert into workflow.workflow_step_definition (id, definition_id, step_order, name, approver_role, sla_hours) values
    (gen_random_uuid(), 'a2222222-2222-2222-2222-222222222222', 1, 'Legal review',      'LEGAL_REVIEWER', 48),
    (gen_random_uuid(), 'a2222222-2222-2222-2222-222222222222', 2, 'Director sign-off', 'DIRECTOR',       72);

-- PRICE_LIST steps (2)
insert into workflow.workflow_step_definition (id, definition_id, step_order, name, approver_role, sla_hours) values
    (gen_random_uuid(), 'a3333333-3333-3333-3333-333333333333', 1, 'Commercial approval', 'SALES_MANAGER', 48),
    (gen_random_uuid(), 'a3333333-3333-3333-3333-333333333333', 2, 'Director sign-off',   'DIRECTOR',      72);

-- PAYMENT_STATEMENT steps (2)
insert into workflow.workflow_step_definition (id, definition_id, step_order, name, approver_role, sla_hours) values
    (gen_random_uuid(), 'a4444444-4444-4444-4444-444444444444', 1, 'Accounting check',  'ACCOUNTANT', 48),
    (gen_random_uuid(), 'a4444444-4444-4444-4444-444444444444', 2, 'Director sign-off', 'DIRECTOR',   72);
