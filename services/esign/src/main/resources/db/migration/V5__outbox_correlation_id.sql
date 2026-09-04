-- correlation_id (nullable): the shared OutboxEvent entity now maps this column.
ALTER TABLE esign.outbox ADD COLUMN correlation_id VARCHAR(64);
