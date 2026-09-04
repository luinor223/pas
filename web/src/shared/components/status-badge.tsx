import { cn } from "@/shared/lib/cn";

// Maps a document/entity status to its badge label + colorway (design Components page).
type Tone = { label: string; cls: string };

const DOT = "before:mr-1.5 before:h-1.5 before:w-1.5 before:rounded-full before:bg-current before:content-['']";

const MAP: Record<string, Tone> = {
  DRAFT: { label: "Draft", cls: "text-st-draft bg-st-draft-bg" },
  SUBMITTED: { label: "Under Review", cls: "text-st-review bg-st-review-bg" },
  UNDER_REVIEW: { label: "Under Review", cls: "text-st-review bg-st-review-bg" },
  IN_PROGRESS: { label: "In progress", cls: "text-st-review bg-st-review-bg" },
  APPROVED: { label: "Approved", cls: "text-st-approved bg-st-approved-bg" },
  EFFECTIVE: { label: "Effective", cls: "text-st-effective bg-st-effective-bg" },
  ISSUED: { label: "Issued", cls: "text-st-effective bg-st-effective-bg" },
  SIGNED: { label: "Signed", cls: "text-st-approved bg-st-approved-bg" },
  PENDING_SEND: { label: "Queued", cls: "text-st-review bg-st-review-bg" },
  FAILED: { label: "Failed", cls: "text-st-rejected bg-st-rejected-bg" },
  ACTIVE: { label: "Active", cls: "text-st-approved bg-st-approved-bg" },
  REJECTED: { label: "Rejected", cls: "text-st-rejected bg-st-rejected-bg" },
  REVISION_REQUESTED: { label: "Revision requested", cls: "text-st-review bg-st-review-bg" },
  CANCELLED: { label: "Cancelled", cls: "text-st-expired bg-st-expired-bg" },
  SIGNING: { label: "Signing", cls: "text-st-signing bg-st-signing-bg" },
  EXPIRED: { label: "Expired", cls: "text-st-expired bg-st-expired-bg" },
  SUPERSEDED: { label: "Superseded", cls: "text-st-expired bg-st-expired-bg" },
  OPEN: { label: "Open", cls: "text-st-effective bg-st-effective-bg" },
  LOCKED: { label: "Locked", cls: "text-st-expired bg-st-expired-bg" },
  SUSPENDED: { label: "Suspended", cls: "text-st-review bg-st-review-bg" },
  DISABLED: { label: "Disabled", cls: "text-st-expired bg-st-expired-bg" },
};

export function StatusBadge({ status, className }: { status: string; className?: string }) {
  const key = status.toUpperCase().replace(/[\s-]+/g, "_");
  const tone = MAP[key] ?? { label: status, cls: "text-st-draft bg-st-draft-bg" };
  return (
    <span
      className={cn(
        "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap",
        DOT,
        tone.cls,
        className
      )}
    >
      {tone.label}
    </span>
  );
}
