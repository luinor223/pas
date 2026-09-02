-- Billing Service: Add constraints
-- Flyway migration V2

-- 8a: Status CHECK constraint (valid lifecycle states)
ALTER TABLE billing.payment_statement
    ADD CONSTRAINT chk_statement_status
    CHECK (status IN (
        'DRAFT', 'CALCULATED', 'RECONCILED', 'SUBMITTED',
        'APPROVED', 'SIGNING', 'SIGNED', 'ISSUED',
        'REJECTED', 'REVISION', 'CANCELLED'
    ));

-- 8b: Partial unique index — one live statement per contract+period
-- "Live" = not CANCELLED and not an adjustment (adjusts_statement_id IS NULL)
CREATE UNIQUE INDEX uk_live_statement_per_period
    ON billing.payment_statement(contract_id, period_code)
    WHERE adjusts_statement_id IS NULL
      AND status NOT IN ('CANCELLED', 'REJECTED');

-- 8c: created_by NOT NULL constraint
-- NOTE: Existing rows may have NULL created_by; enforce for new rows only via trigger
CREATE OR REPLACE FUNCTION billing.set_created_by_on_insert()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.created_by IS NULL THEN
        NEW.created_by = current_setting('app.current_user_id', TRUE)::UUID;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_set_created_by
    BEFORE INSERT ON billing.payment_statement
    FOR EACH ROW
    EXECUTE FUNCTION billing.set_created_by_on_insert();
