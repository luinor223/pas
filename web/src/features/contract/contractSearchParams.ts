const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

export const CONTRACT_STATUSES = [
  "DRAFT", "SUBMITTED", "UNDER_REVIEW", "APPROVED", "ACTIVE", "EXPIRED",
  "REJECTED", "REVISION_REQUESTED", "CANCELLED",
] as const;
export const CUSTOMER_STATUSES = ["ACTIVE", "SUSPENDED"] as const;
export const ADDENDUM_STATUSES = CONTRACT_STATUSES;
export const CONTRACT_TABS = ["overview", "addenda", "approval-history", "attachments"] as const;
export const CUSTOMER_TABS = ["overview", "contracts", "contacts"] as const;

export type ContractRouteSearch = {
  id?: string; tab?: typeof CONTRACT_TABS[number]; customerId?: string; q?: string;
  status?: typeof CONTRACT_STATUSES[number]; serviceGroup?: string; validFromFrom?: string;
  validToTo?: string; page?: number; cursor?: string; relatedPage?: number; relatedCursor?: string;
};
export type CustomerRouteSearch = {
  id?: string; tab?: typeof CUSTOMER_TABS[number]; q?: string; status?: typeof CUSTOMER_STATUSES[number];
  page?: number; cursor?: string; contractsPage?: number; contractsCursor?: string;
};
export type AddendumRouteSearch = {
  id?: string; contractId?: string; changeType?: (typeof ADDENDUM_CHANGE_TYPES)[number];
  status?: typeof ADDENDUM_STATUSES[number]; q?: string; page?: number; cursor?: string;
};

export function optionalText(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() ? value.trim().slice(0, 200) : undefined;
}

export function optionalUuid(value: unknown): string | undefined {
  return typeof value === "string" && UUID.test(value) ? value : undefined;
}

export function optionalDate(value: unknown): string | undefined {
  if (typeof value !== "string" || !ISO_DATE.test(value)) return undefined;
  const parsed = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value
    ? value
    : undefined;
}

export function optionalPage(value: unknown): number | undefined {
  const parsed = typeof value === "number" ? value : typeof value === "string" ? Number(value) : NaN;
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : undefined;
}

export function optionalCursor(value: unknown): string | undefined {
  return typeof value === "string" && value.length > 0 && value.length <= 1024 ? value : undefined;
}

export function optionalEnum<const T extends readonly string[]>(value: unknown, allowed: T): T[number] | undefined {
  return typeof value === "string" && allowed.includes(value) ? value as T[number] : undefined;
}
import { ADDENDUM_CHANGE_TYPES } from "./contractOptions";
