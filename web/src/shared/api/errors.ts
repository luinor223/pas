import { roleLabel, statusLabel } from "@/shared/lib/labels";

export function getApiErrorMessage(e: unknown, fallback: string): string {
  const message = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
  return message ? userFriendlyMessage(message) : fallback;
}

const BUSINESS_STATUSES = [
  "DRAFT", "SUBMITTED", "UNDER_REVIEW", "APPROVED", "ACTIVE", "EXPIRED",
  "REJECTED", "REVISION_REQUESTED", "CANCELLED", "SUSPENDED", "DISABLED",
  "EFFECTIVE", "SUPERSEDED",
];

/** Removes implementation references and translates common business-rule errors for users. */
function userFriendlyMessage(raw: string): string {
  const withoutReferences = raw
    .replace(/\s*\((?:registry\s*§[^)]*|[A-Z]{2,}-\d+[^)]*|D\d+[^)]*)\)/gi, "")
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
  if (/validity overlaps an existing effective version of the same scope/i.test(withoutReferences)) {
    return "These dates overlap an approved or effective price-list version for the same scope. Choose a non-overlapping period.";
  }
  if (/valid_from must be on or before valid_to/i.test(withoutReferences)) {
    return "Valid to must be on or after Valid from.";
  }
  if (/period is locked; volume:edit_locked required/i.test(withoutReferences)) {
    return "This period is locked. You need special access to change its volume records.";
  }
  if (/invalid period_code, expected yyyy-mm|period_code must be yyyy-mm/i.test(withoutReferences)) {
    return "Choose a valid month.";
  }
  if (/service item not active:/i.test(withoutReferences)) {
    return "This service is no longer active. Choose another service.";
  }
  if (/contract service unavailable/i.test(withoutReferences)) {
    return "Contract information is temporarily unavailable. Try again shortly.";
  }
  if (/pricing service unavailable/i.test(withoutReferences)) {
    return "Service information is temporarily unavailable. Try again shortly.";
  }

  const missingApprover = withoutReferences.match(/no assignee for role:\s*([A-Z_]+)/i);
  if (missingApprover) {
    return `This approval cannot start because no active ${roleLabel(missingApprover[1].toUpperCase())} is assigned. Ask an administrator for help.`;
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
