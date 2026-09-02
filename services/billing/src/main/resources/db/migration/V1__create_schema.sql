-- Billing Service: Create schema and tables
-- Flyway migration V1

CREATE SCHEMA IF NOT EXISTS billing;

CREATE TABLE billing.payment_statement (
    id BIGSERIAL PRIMARY KEY,
    statement_no VARCHAR(50) NOT NULL UNIQUE,
    contract_id UUID NOT NULL,
    contract_no VARCHAR(50) NOT NULL,
    customer_id UUID,
    customer_name VARCHAR(200),
    period_code VARCHAR(20) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    price_list_version_id UUID,
    price_list_no VARCHAR(50),
    price_list_version_no INT,
    payment_term VARCHAR(50),
    vat_rate NUMERIC(5, 2),
    subtotal NUMERIC(18, 2) NOT NULL DEFAULT 0,
    tax_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    total_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    currency VARCHAR(10),
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    adjusts_statement_id BIGINT,
    reconciled_at TIMESTAMPTZ,
    reconciled_by UUID,
    issued_at TIMESTAMPTZ,
    due_date DATE,
    version INT NOT NULL DEFAULT 0,
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CHECK (total_amount >= 0)
);

COMMENT ON TABLE billing.payment_statement IS 'Payment statements — the integration hub (PAY-03 snapshots)';
COMMENT ON COLUMN billing.payment_statement.status IS 'DRAFT→CALCULATED→RECONCILED→SUBMITTED→APPROVED→SIGNING→SIGNED→ISSUED plus REJECTED/REVISION/CANCELLED';

CREATE INDEX idx_payment_statement_contract ON billing.payment_statement(contract_id);
CREATE INDEX idx_payment_statement_status ON billing.payment_statement(status);

ALTER TABLE billing.payment_statement
    ADD CONSTRAINT fk_statement_adjusts
    FOREIGN KEY (adjusts_statement_id) REFERENCES billing.payment_statement(id);

CREATE TABLE billing.statement_line (
    id BIGSERIAL PRIMARY KEY,
    statement_id BIGINT NOT NULL,
    line_no INT NOT NULL,
    service_code VARCHAR(50) NOT NULL,
    service_name VARCHAR(200),
    unit VARCHAR(50),
    unit_price NUMERIC(15, 2) NOT NULL,
    quantity NUMERIC(15, 4) NOT NULL,
    amount NUMERIC(18, 2) NOT NULL,
    source VARCHAR(20) NOT NULL DEFAULT 'CALCULATED',
    note VARCHAR(500),
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CHECK (source IN ('CALCULATED', 'MANUAL'))
);

ALTER TABLE billing.statement_line
    ADD CONSTRAINT fk_line_statement
    FOREIGN KEY (statement_id) REFERENCES billing.payment_statement(id);

CREATE UNIQUE INDEX uk_statement_line_no ON billing.statement_line(statement_id, line_no);

CREATE TABLE billing.statement_line_volume (
    id BIGSERIAL PRIMARY KEY,
    line_id BIGINT NOT NULL,
    volume_record_id VARCHAR(50) NOT NULL,
    record_no VARCHAR(50),
    quantity NUMERIC(15, 4) NOT NULL,
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

ALTER TABLE billing.statement_line_volume
    ADD CONSTRAINT fk_line_volume_line
    FOREIGN KEY (line_id) REFERENCES billing.statement_line(id);

CREATE TABLE billing.processed_event (
    event_id UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE billing.status_history (
    id BIGSERIAL PRIMARY KEY,
    statement_id BIGINT NOT NULL,
    from_status VARCHAR(30),
    to_status VARCHAR(30) NOT NULL,
    trigger_kind VARCHAR(10) NOT NULL,
    trigger_ref VARCHAR(200),
    actor_id UUID,
    actor_name VARCHAR(200),
    note VARCHAR(500),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

ALTER TABLE billing.status_history
    ADD CONSTRAINT fk_status_history_statement
    FOREIGN KEY (statement_id) REFERENCES billing.payment_statement(id);

CREATE INDEX idx_status_history_statement ON billing.status_history(statement_id);

-- outbox (M2 / D15)
CREATE TABLE billing.outbox (
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
CREATE INDEX idx_billing_outbox_unpublished
    ON billing.outbox(created_at)
    WHERE published_at IS NULL AND cancelled_at IS NULL;
