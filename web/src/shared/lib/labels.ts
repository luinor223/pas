import { humanize } from "@/shared/lib/text";

const DEPARTMENTS: Record<string, string> = {
  SALES: "Sales",
  LEGAL: "Legal",
  ACCOUNTING: "Accounting",
  OPERATIONS: "Operations",
  BOARD: "Executive Board",
  IT: "Information Technology",
};

const ROLES: Record<string, string> = {
  SALES_OFFICER: "Sales Officer",
  SALES_MANAGER: "Sales Manager",
  LEGAL_REVIEWER: "Legal Reviewer",
  ACCOUNTANT: "Accountant",
  OPS_OFFICER: "Operations Officer",
  DIRECTOR: "Director",
  SYSTEM_ADMIN: "System Administrator",
};

const STATUSES: Record<string, string> = {
  DRAFT: "Draft",
  SUBMITTED: "Submitted",
  UNDER_REVIEW: "Under review",
  APPROVED: "Approved",
  ACTIVE: "Active",
  EXPIRED: "Expired",
  REJECTED: "Rejected",
  REVISION_REQUESTED: "Revision requested",
  CANCELLED: "Cancelled",
  SUSPENDED: "Suspended",
  DISABLED: "Disabled",
};

// Keep these English labels aligned with permissions seeded by identity-service.
// Unknown future codes intentionally fall back to a readable generated label.
const PERMISSIONS: Record<string, string> = {
  "customer:read": "View customers",
  "customer:write": "Create and edit customers",
  "contract:read": "View contracts",
  "contract:write": "Create and edit contracts",
  "contract:cancel_active": "Cancel active contracts",
  "addendum:read": "View addenda",
  "addendum:write": "Create and edit addenda",
  "pricelist:read": "View price lists",
  "pricelist:write": "Create and edit price lists",
  "volume:read": "View volume records",
  "volume:write": "Create and edit volume records",
  "volume:lock_period": "Close volume periods",
  "volume:edit_locked": "Edit records in closed periods",
  "statement:read": "View payment statements",
  "statement:write": "Create and edit payment statements",
  "statement:cancel_approved": "Cancel approved payment statements",
  "approval:act": "Review and decide approvals",
  "esign:send": "Send documents for e-signature",
  "esign:cancel": "Cancel e-signature requests",
  "notification:read": "View notifications",
  "user:manage": "Manage users and access",
  "workflow:configure": "Configure approval workflows",
  "doctype:configure": "Configure document types",
  "audit:view_all": "View the complete activity history",
};

export const departmentLabel = (code: string) => DEPARTMENTS[code] ?? humanize(code);
export const roleLabel = (code: string) => ROLES[code] ?? humanize(code);
export const statusLabel = (code: string) => STATUSES[code] ?? humanize(code);
export const permissionLabel = (code: string) => PERMISSIONS[code] ?? humanize(code.split(":").pop() ?? code);
