-- Operations Service: Create tables
-- Flyway migration V1

CREATE SCHEMA IF NOT EXISTS operations;

CREATE TABLE operations.operation_period (
    id BIGSERIAL PRIMARY KEY,
    period_code VARCHAR(50) NOT NULL UNIQUE,
    period_name VARCHAR(100) NOT NULL,
    month INT NOT NULL,
    year INT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_by UUID,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

COMMENT ON TABLE operations.operation_period IS 'Period of operations for volume and cost tracking';
COMMENT ON COLUMN operations.operation_period.status IS 'LOCKED or DRAFT. Once LOCKED, cannot be changed back.';

CREATE INDEX idx_operation_period_status ON operations.operation_period(status);
CREATE INDEX idx_operation_period_year_month ON operations.operation_period(year DESC, month DESC);

CREATE TABLE operations.volume_record (
    id BIGSERIAL PRIMARY KEY,
    period_code VARCHAR(50) NOT NULL,
    contract_id BIGINT NOT NULL,
    contract_code VARCHAR(50) NOT NULL,
    contract_name VARCHAR(200),
    partner_id BIGINT,
    partner_name VARCHAR(200),
    service_item_id BIGINT,
    service_code VARCHAR(50) NOT NULL,
    service_name VARCHAR(200),
    quantity NUMERIC(15, 4) NOT NULL,
    unit VARCHAR(50),
    unit_price NUMERIC(15, 2) NOT NULL,
    volume_cost_amount NUMERIC(18, 2) NOT NULL,
    note VARCHAR(500),
    created_by UUID,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

COMMENT ON TABLE operations.volume_record IS 'Volume records per contract per period';

ALTER TABLE operations.volume_record
    ADD CONSTRAINT fk_volume_record_period
    FOREIGN KEY (period_code) REFERENCES operations.operation_period(period_code);

CREATE INDEX idx_volume_record_period ON operations.volume_record(period_code);
CREATE INDEX idx_volume_record_contract ON operations.volume_record(contract_code);
CREATE INDEX idx_volume_record_partner ON operations.volume_record(partner_id);

CREATE TABLE operations.audit_payload (
    id BIGSERIAL PRIMARY KEY,
    action VARCHAR(50) NOT NULL,
    entity_type VARCHAR(100) NOT NULL,
    entity_id BIGINT,
    actor_id UUID NOT NULL,
    actor_name VARCHAR(200),
    ip_address VARCHAR(50),
    payload JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

COMMENT ON TABLE operations.audit_payload IS 'Audit trail for all operations with HMAC-SHA256 integrity';

CREATE INDEX idx_audit_payload_entity ON operations.audit_payload(entity_type, entity_id);
CREATE INDEX idx_audit_payload_actor ON operations.audit_payload(actor_id);
CREATE INDEX idx_audit_payload_created ON operations.audit_payload(created_at);

-- outbox (M2 / D15) — one per service, never per event type. The shared OutboxEvent entity maps
-- here; ddl-auto=validate requires it even before operations emits its first event.
CREATE TABLE operations.outbox (
    id              UUID PRIMARY KEY,
    event_type      TEXT NOT NULL,
    aggregate_type  TEXT NOT NULL,
    aggregate_id    UUID NOT NULL,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    claimed_at      TIMESTAMPTZ,
    published_at    TIMESTAMPTZ,
    cancelled_at    TIMESTAMPTZ,
    retry_count     INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_operations_outbox_unpublished
    ON operations.outbox(created_at)
    WHERE published_at IS NULL AND cancelled_at IS NULL;
