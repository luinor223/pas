export const SERVICE_GROUPS = ["STEVEDORING", "WAREHOUSING", "TRANSPORTATION", "CONTAINER_HANDLING"] as const;

export const ADDENDUM_CHANGE_TYPES = ["UNIT_PRICE_CHANGE", "TERM_EXTENSION", "ADDED_SERVICE", "PAYMENT_TERMS"] as const;

const USER_CANCELLABLE_STATUSES = new Set(["DRAFT", "SUBMITTED", "UNDER_REVIEW", "ACTIVE"]);

export function isUserCancellableStatus(status: string): boolean {
  return USER_CANCELLABLE_STATUSES.has(status);
}
