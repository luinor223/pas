import type { StatementStatus } from "./types/billingTypes";

// Single source of truth for the statement lifecycle actions: which billingApi
// call each triggers, the permission it needs, and the statuses it applies to.
// Both the detail header buttons and the list row menu render from this.
export type LifecycleKey = "recalculate" | "reconcile" | "submit" | "revise" | "sendForSigning" | "publish";

export type LifecycleAction = {
  key: LifecycleKey;
  label: string;
  perm: "statement:write" | "esign:send";
  statuses: StatementStatus[];
  primary?: boolean;
};

export const LIFECYCLE_ACTIONS: LifecycleAction[] = [
  { key: "recalculate", label: "Recalculate", perm: "statement:write", statuses: ["DRAFT", "CALCULATED"] },
  { key: "reconcile", label: "Reconcile", perm: "statement:write", statuses: ["CALCULATED"] },
  { key: "submit", label: "Submit for approval", perm: "statement:write", statuses: ["RECONCILED"], primary: true },
  { key: "revise", label: "Revise", perm: "statement:write", statuses: ["REJECTED", "REVISION"], primary: true },
  { key: "sendForSigning", label: "Send for signing", perm: "esign:send", statuses: ["APPROVED"], primary: true },
  { key: "publish", label: "Publish", perm: "statement:write", statuses: ["SIGNED"], primary: true },
];
