import { cn } from "@/shared/lib/cn";
import { statusLabel } from "@/shared/lib/labels";

// Colorway per status (design Components page); the name itself comes from statusLabel, so a
// badge, a filter option, and a status timeline never disagree about what a status is called.
const DOT = "before:mr-1.5 before:h-1.5 before:w-1.5 before:rounded-full before:bg-current before:content-['']";

const DRAFT = "text-st-draft bg-st-draft-bg";
const REVIEW = "text-st-review bg-st-review-bg";
const APPROVED = "text-st-approved bg-st-approved-bg";
const EFFECTIVE = "text-st-effective bg-st-effective-bg";
const REJECTED = "text-st-rejected bg-st-rejected-bg";
const EXPIRED = "text-st-expired bg-st-expired-bg";

const TONES: Record<string, string> = {
  DRAFT,
  CALCULATED: DRAFT,
  SUBMITTED: REVIEW,
  UNDER_REVIEW: REVIEW,
  IN_PROGRESS: REVIEW,
  RECONCILED: REVIEW,
  REVISION: REVIEW,
  REVISION_REQUESTED: REVIEW,
  PENDING_SEND: REVIEW,
  SUSPENDED: REVIEW,
  APPROVED,
  SIGNED: APPROVED,
  ACTIVE: APPROVED,
  EFFECTIVE,
  ISSUED: EFFECTIVE,
  OPEN: EFFECTIVE,
  SIGNING: "text-st-signing bg-st-signing-bg",
  REJECTED,
  FAILED: REJECTED,
  CANCELLED: EXPIRED,
  EXPIRED,
  SUPERSEDED: EXPIRED,
  LOCKED: EXPIRED,
  DISABLED: EXPIRED,
};

export function StatusBadge({ status, className }: { status: string; className?: string }) {
  const key = status.toUpperCase().replace(/[\s-]+/g, "_");
  return (
    <span
      className={cn(
        "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap",
        DOT,
        TONES[key] ?? DRAFT,
        className
      )}
    >
      {statusLabel(key)}
    </span>
  );
}
