import { statusLabel } from "@/shared/lib/labels";

export function getApiErrorMessage(e: unknown, fallback: string): string {
  const message = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
  return message ? userFriendlyMessage(message) : fallback;
}

const BUSINESS_STATUSES = [
  "DRAFT", "SUBMITTED", "UNDER_REVIEW", "APPROVED", "ACTIVE", "EXPIRED",
  "REJECTED", "REVISION_REQUESTED", "CANCELLED", "SUSPENDED", "DISABLED",
];

/** Removes implementation references and translates common business-rule errors for users. */
function userFriendlyMessage(raw: string): string {
  const withoutReferences = raw
    .replace(/\s*\((?:registry\s*§[^)]*|CTR-\d+[^)]*|D\d+[^)]*)\)/gi, "")
    .replace(/\s+/g, " ")
    .trim();

  const cancellation = withoutReferences.match(/^(contract|addendum)\s+(\S+)\s+is\s+([A-Z_]+)\s+and cannot be cancelled\.?$/i);
  if (cancellation) {
    const [, type, number, status] = cancellation;
    return `${capitalize(type)} ${number} cannot be cancelled because its status is ${statusLabel(status.toUpperCase())}.`;
  }

  return BUSINESS_STATUSES.reduce(
    (message, status) => message.replace(new RegExp(`\\b${status}\\b`, "g"), statusLabel(status)),
    withoutReferences,
  );
}

function capitalize(value: string): string {
  return value.charAt(0).toUpperCase() + value.slice(1).toLowerCase();
}
