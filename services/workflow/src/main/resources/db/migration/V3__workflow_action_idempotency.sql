alter table workflow.workflow_action
    add column idempotency_key uuid;

create unique index uq_workflow_action_idempotency_key
    on workflow.workflow_action(idempotency_key)
    where idempotency_key is not null;
