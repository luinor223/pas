-- Operations Service: Seed data
-- Flyway migration V2

-- Seed operation periods
INSERT INTO operations.operation_period (period_code, period_name, month, year, start_date, end_date, status)
VALUES
    ('2025-09', 'Tháng 9/2025', 9, 2025, '2025-09-01', '2025-09-30', 'LOCKED'),
    ('2025-10', 'Tháng 10/2025', 10, 2025, '2025-10-01', '2025-10-31', 'LOCKED'),
    ('2025-11', 'Tháng 11/2025', 11, 2025, '2025-11-01', '2025-11-30', 'DRAFT'),
    ('2025-12', 'Tháng 12/2025', 12, 2025, '2025-12-01', '2025-12-31', 'DRAFT')
ON CONFLICT (period_code) DO NOTHING;

-- Seed volume records for 2025-09
INSERT INTO operations.volume_record (
    period_code, contract_id, contract_code, contract_name,
    partner_id, partner_name, service_item_id, service_code, service_name,
    quantity, unit, unit_price, volume_cost_amount
)
VALUES
    ('2025-09', 1, 'HD-2025-001', 'Hợp đồng vận chuyển ABC',
     101, 'Công ty TNHH ABC', 1, 'XPL', 'Xe_PICKUP_1T',
     150, 'CHUYẾN', 150000.00, 22500000.00),
    ('2025-09', 1, 'HD-2025-001', 'Hợp đồng vận chuyển ABC',
     101, 'Công ty TNHH ABC', 2, 'WH', 'WAREHOUSE_100M2',
     3, 'THÁNG', 5000000.00, 15000000.00),
    ('2025-09', 2, 'HD-2025-002', 'Hợp đồng kho vận DEF',
     102, 'Công ty CP DEF', 3, 'HT', 'HUấn_anh_tập_kích',
     5, 'BUỔI', 800000.00, 4000000.00)
ON CONFLICT DO NOTHING;
