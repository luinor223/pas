import { humanize } from "@/shared/lib/text";

export const AUDIT_RECORD_TYPES = [
  { value: "CUSTOMER", label: "Customer", module: "contract-service" },
  { value: "CONTRACT", label: "Contract", module: "contract-service" },
  { value: "ADDENDUM", label: "Addendum", module: "contract-service" },
  { value: "PRICE_LIST", label: "Price list", module: "pricing-service" },
  { value: "PRICE_LIST_VERSION", label: "Price list version", module: "pricing-service" },
  { value: "VOLUME_RECORD", label: "Volume record", module: "operations-service" },
  { value: "OPERATION_PERIOD", label: "Operation period", module: "operations-service" },
  { value: "PAYMENT_STATEMENT", label: "Payment statement", module: "billing-service" },
  { value: "WORKFLOW_DEFINITION", label: "Workflow definition", module: "workflow-service" },
  { value: "WORKFLOW_INSTANCE", label: "Approval workflow", module: "workflow-service" },
  { value: "WORKFLOW_STEP", label: "Approval step", module: "workflow-service" },
  { value: "USER", label: "User", module: "identity-service" },
  { value: "ROLE", label: "Role", module: "identity-service" },
];

// This is the user-facing vocabulary for both the filter and audit rows. Keep
// it aligned with audit.record(...) actions emitted by the services.
export const AUDIT_ACTIVITIES = [
  { value: "CREATE", label: "Created" },
  { value: "UPDATE", label: "Updated" },
  { value: "STATUS_CHANGE", label: "Status changed" },
  { value: "ACTIVATE", label: "Customer activated" },
  { value: "SUSPEND", label: "Customer suspended" },
  { value: "REASSIGN_CUSTOMER", label: "Customer reassigned" },
  { value: "ATTACH", label: "Attachment added" },
  { value: "DETACH", label: "Attachment removed" },
  { value: "EDIT_LINES", label: "Price lines edited" },
  { value: "CANCEL_PENDING", label: "Cancellation pending" },
  { value: "SEND_FOR_SIGNING", label: "Sent for signing" },
  { value: "ADDENDUM_APPLIED", label: "Addendum applied" },
  { value: "ADDENDUM_SUPERSEDED", label: "Addendum superseded" },
  { value: "user.created", label: "User created" },
  { value: "user.updated", label: "User updated" },
  { value: "user.enabled", label: "User enabled" },
  { value: "user.disabled", label: "User disabled" },
  { value: "user.roles_updated", label: "User roles updated" },
  { value: "role.permissions_replaced", label: "Role permissions updated" },
  { value: "workflow.instance_started", label: "Approval workflow started" },
  { value: "workflow.instance_cancelled", label: "Approval workflow cancelled" },
  { value: "workflow.step_approved", label: "Approval step approved" },
  { value: "workflow.step_rejected", label: "Approval step rejected" },
  { value: "workflow.step_revision_requested", label: "Revision requested" },
  { value: "workflow.definition_created", label: "Workflow definition created" },
  { value: "workflow.definition_steps_updated", label: "Workflow steps updated" },
  { value: "workflow.definition_activated", label: "Workflow definition activated" },
  { value: "period.created", label: "Operation period created" },
  { value: "period.locked", label: "Operation period locked" },
  { value: "volume.created", label: "Volume record created" },
  { value: "volume.updated", label: "Volume record updated" },
  { value: "statement.calculated", label: "Payment statement calculated" },
  { value: "statement.reconciled", label: "Payment statement reconciled" },
  { value: "statement.submitted", label: "Payment statement submitted" },
  { value: "statement.status_changed", label: "Payment statement status changed" },
  { value: "statement.line_edited", label: "Payment statement line edited" },
  { value: "statement.adjustment_created", label: "Payment adjustment created" },
  { value: "statement.cancelled", label: "Payment statement cancelled" },
  { value: "WORKFLOW_INITIALIZATION_FAILED", label: "Approval workflow failed to start" },
  { value: "SEND_FOR_SIGNING_FAILED", label: "Sending for signature failed" },
  { value: "DISPATCH_FAILED", label: "Background delivery failed" },
] as const;

const ACTIVITY_LABELS = Object.fromEntries(
  AUDIT_ACTIVITIES.map(({ value, label }) => [value, label])
) as Record<string, string>;

export function auditActivityLabel(action: string): string {
  return ACTIVITY_LABELS[action] ?? humanize(action);
}
