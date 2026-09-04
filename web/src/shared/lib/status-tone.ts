// Single source for status colorways (design Components page). text/bg are full literal
// classes so Tailwind's scanner keeps them; text doubles as the standalone tone marker color.
export type StatusTone = { label: string; text: string; bg: string };

const MAP: Record<string, StatusTone> = {
  DRAFT: { label: "Draft", text: "text-st-draft", bg: "bg-st-draft-bg" },
  SUBMITTED: { label: "Submitted", text: "text-st-review", bg: "bg-st-review-bg" },
  UNDER_REVIEW: { label: "Under Review", text: "text-st-review", bg: "bg-st-review-bg" },
  IN_PROGRESS: { label: "In progress", text: "text-st-review", bg: "bg-st-review-bg" },
  APPROVED: { label: "Approved", text: "text-st-approved", bg: "bg-st-approved-bg" },
  EFFECTIVE: { label: "Effective", text: "text-st-effective", bg: "bg-st-effective-bg" },
  ISSUED: { label: "Issued", text: "text-st-effective", bg: "bg-st-effective-bg" },
  SIGNED: { label: "Signed", text: "text-st-approved", bg: "bg-st-approved-bg" },
  CALCULATED: { label: "Calculated", text: "text-st-draft", bg: "bg-st-draft-bg" },
  RECONCILED: { label: "Reconciled", text: "text-st-review", bg: "bg-st-review-bg" },
  REVISION: { label: "Revision requested", text: "text-st-review", bg: "bg-st-review-bg" },
  PENDING_SEND: { label: "Pending send", text: "text-st-review", bg: "bg-st-review-bg" },
  FAILED: { label: "Failed", text: "text-st-rejected", bg: "bg-st-rejected-bg" },
  ACTIVE: { label: "Active", text: "text-st-approved", bg: "bg-st-approved-bg" },
  REJECTED: { label: "Rejected", text: "text-st-rejected", bg: "bg-st-rejected-bg" },
  REVISION_REQUESTED: { label: "Revision requested", text: "text-st-review", bg: "bg-st-review-bg" },
  CANCELLED: { label: "Cancelled", text: "text-st-expired", bg: "bg-st-expired-bg" },
  SIGNING: { label: "Signing", text: "text-st-signing", bg: "bg-st-signing-bg" },
  EXPIRED: { label: "Expired", text: "text-st-expired", bg: "bg-st-expired-bg" },
  SUPERSEDED: { label: "Superseded", text: "text-st-expired", bg: "bg-st-expired-bg" },
  OPEN: { label: "Open", text: "text-st-effective", bg: "bg-st-effective-bg" },
  LOCKED: { label: "Locked", text: "text-st-expired", bg: "bg-st-expired-bg" },
  SUSPENDED: { label: "Suspended", text: "text-st-review", bg: "bg-st-review-bg" },
  DISABLED: { label: "Disabled", text: "text-st-expired", bg: "bg-st-expired-bg" },
};

const DEFAULT_TONE: StatusTone = { label: "", text: "text-st-draft", bg: "bg-st-draft-bg" };

export function statusTone(status: string): StatusTone {
  return MAP[status.toUpperCase().replace(/[\s-]+/g, "_")] ?? DEFAULT_TONE;
}

/** The status' text-color token, for rendering a standalone tone marker (dot) with `bg-current`. */
export function statusTextTone(status: string): string {
  return statusTone(status).text;
}
