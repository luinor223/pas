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

  if (/workflow instance not in progress|step (?:is )?not active/i.test(withoutReferences)) {
    return "This approval is no longer awaiting action. Refresh the list to see its current status.";
  }
  if (/concurrently modified|\bABORTED\b/i.test(withoutReferences)) {
    return "Someone else updated this approval. Refresh the list and try again if it still needs action.";
  }
  if (/user not assignee/i.test(withoutReferences)) {
    return "This approval is no longer assigned to you. Refresh the list to see your current tasks.";
  }
  if (/comment required for (?:action: )?(?:REJECT|REQUEST_REVISION)/i.test(withoutReferences)) {
    return "Enter a reason before completing this action.";
  }

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
