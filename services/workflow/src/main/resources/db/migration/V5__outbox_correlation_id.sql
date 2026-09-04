-- correlation_id (nullable): the shared OutboxEvent entity now maps this column.
alter table workflow.outbox add column correlation_id varchar(64);
