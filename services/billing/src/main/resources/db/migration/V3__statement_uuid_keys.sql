-- Convert billing aggregate keys from bigint to uuid (registry §6: UUID primary keys).
-- The billing tables were only just introduced and carry no released data, so an in-place
-- type change is safe. Also add the sequence backing PMT-YYYY-NNNN statement numbers.

CREATE SEQUENCE IF NOT EXISTS billing.statement_no_seq;

ALTER TABLE billing.status_history        DROP CONSTRAINT fk_status_history_statement;
ALTER TABLE billing.statement_line_volume DROP CONSTRAINT fk_line_volume_line;
ALTER TABLE billing.statement_line        DROP CONSTRAINT fk_line_statement;
ALTER TABLE billing.payment_statement     DROP CONSTRAINT fk_statement_adjusts;

ALTER TABLE billing.payment_statement
    ALTER COLUMN id DROP DEFAULT,
    ALTER COLUMN id SET DATA TYPE uuid USING gen_random_uuid(),
    ALTER COLUMN adjusts_statement_id SET DATA TYPE uuid USING NULL;

ALTER TABLE billing.statement_line
    ALTER COLUMN id DROP DEFAULT,
    ALTER COLUMN id SET DATA TYPE uuid USING gen_random_uuid(),
    ALTER COLUMN statement_id SET DATA TYPE uuid USING NULL;

ALTER TABLE billing.statement_line_volume
    ALTER COLUMN id DROP DEFAULT,
    ALTER COLUMN id SET DATA TYPE uuid USING gen_random_uuid(),
    ALTER COLUMN line_id SET DATA TYPE uuid USING NULL;

ALTER TABLE billing.status_history
    ALTER COLUMN id DROP DEFAULT,
    ALTER COLUMN id SET DATA TYPE uuid USING gen_random_uuid(),
    ALTER COLUMN statement_id SET DATA TYPE uuid USING NULL;

ALTER TABLE billing.payment_statement
    ADD CONSTRAINT fk_statement_adjusts
    FOREIGN KEY (adjusts_statement_id) REFERENCES billing.payment_statement(id);
ALTER TABLE billing.statement_line
    ADD CONSTRAINT fk_line_statement
    FOREIGN KEY (statement_id) REFERENCES billing.payment_statement(id);
ALTER TABLE billing.statement_line_volume
    ADD CONSTRAINT fk_line_volume_line
    FOREIGN KEY (line_id) REFERENCES billing.statement_line(id);
ALTER TABLE billing.status_history
    ADD CONSTRAINT fk_status_history_statement
    FOREIGN KEY (statement_id) REFERENCES billing.payment_statement(id);
