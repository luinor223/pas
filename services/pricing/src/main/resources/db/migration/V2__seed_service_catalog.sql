-- Service catalog seeds (db-pricing.md figma adoptions). Codes are the stable business keys
-- operations and billing reference as `service_code`.
insert into pricing.service_item (code, name, unit) values
    ('LIFT_ON_OFF',        'Container lift on/off',    'TEU'),
    ('STORAGE_OVERTIME',   'Storage beyond free time', 'day'),
    ('LASHING',            'Lashing & securing',       'TEU'),
    ('REEFER_MONITOR',     'Reefer monitoring',        'day'),
    ('DOC_HANDLING',       'Documentation handling',   'set'),
    ('WEIGHING_VGM',       'Weighing (VGM)',           'TEU');
