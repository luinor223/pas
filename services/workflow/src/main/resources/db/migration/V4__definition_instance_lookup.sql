-- Lookup for the definition-delete guard: "no instance references this definition"
-- must be a single indexed EXISTS, not a scan over growing approval history.
create index idx_workflow_instance_definition
    on workflow.workflow_instance(definition_id);
