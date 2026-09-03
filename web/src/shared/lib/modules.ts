export type BusinessModule = {
  key: string;
  label: string;
  service?: string;
  serviceLabel?: string;
};

/** Shared vocabulary for permission groups and service-owned activity. */
export const BUSINESS_MODULES: BusinessModule[] = [
  { key: "customer", label: "Customers", service: "contract-service" },
  { key: "contract", label: "Contracts", service: "contract-service", serviceLabel: "Contracts" },
  { key: "addendum", label: "Addenda", service: "contract-service" },
  { key: "pricelist", label: "Price lists", service: "pricing-service", serviceLabel: "Pricing" },
  { key: "volume", label: "Volume records", service: "operations-service", serviceLabel: "Operations" },
  { key: "statement", label: "Payment statements", service: "billing-service", serviceLabel: "Payments" },
  { key: "approval", label: "Approvals", service: "workflow-service", serviceLabel: "Approvals" },
  { key: "esign", label: "E-signatures", service: "esign-service", serviceLabel: "E-signatures" },
  { key: "notification", label: "Notifications", service: "notification-service" },
  { key: "user", label: "Users & access", service: "identity-service", serviceLabel: "Users & access" },
  { key: "workflow", label: "Workflows", service: "workflow-service" },
  { key: "doctype", label: "Document types", service: "workflow-service" },
  { key: "audit", label: "Audit log", service: "audit-service" },
];

export const MODULE_LABELS = Object.fromEntries(BUSINESS_MODULES.map(({ key, label }) => [key, label]));

export const AUDIT_MODULES = BUSINESS_MODULES
  .filter((module): module is BusinessModule & { service: string; serviceLabel: string } => !!module.service && !!module.serviceLabel)
  .map(({ service, serviceLabel }) => ({ value: service, label: serviceLabel }));

export const SERVICE_LABELS = Object.fromEntries(AUDIT_MODULES.map(({ value, label }) => [value, label]));
