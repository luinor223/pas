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

// Values intentionally match action fragments accepted by audit-service.
export const AUDIT_ACTIVITIES = [
  { value: "create", label: "Created" },
  { value: "update", label: "Updated" },
  { value: "STATUS_CHANGE", label: "Status changed" },
  { value: "submit", label: "Submitted" },
  { value: "approve", label: "Approved" },
  { value: "reject", label: "Rejected" },
  { value: "cancel", label: "Cancelled" },
  { value: "activate", label: "Activated" },
  { value: "suspend", label: "Suspended" },
  { value: "enable", label: "User enabled" },
  { value: "disable", label: "User disabled" },
  { value: "roles", label: "User roles changed" },
  { value: "permissions", label: "Role permissions changed" },
  { value: "signing", label: "Sent for signing" },
];
