export const SERVICE_GROUPS = ["STEVEDORING", "WAREHOUSING", "TRANSPORTATION", "CONTAINER_HANDLING"] as const;

export const ADDENDUM_CHANGE_TYPES = ["UNIT_PRICE_CHANGE", "TERM_EXTENSION", "ADDED_SERVICE", "PAYMENT_TERMS"] as const;

type AddendumChangeType = (typeof ADDENDUM_CHANGE_TYPES)[number];

const ADDENDUM_CHANGE_TYPE_LABELS = {
  UNIT_PRICE_CHANGE: "Unit price change",
  TERM_EXTENSION: "Term extension",
  ADDED_SERVICE: "Added service",
  PAYMENT_TERMS: "Payment terms",
} satisfies Record<AddendumChangeType, string>;

export function addendumChangeTypeLabel(changeType: string): string {
  return (ADDENDUM_CHANGE_TYPE_LABELS as Record<string, string>)[changeType] ?? "Other change";
}
