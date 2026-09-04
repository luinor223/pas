import type { ReactNode } from "react";
import { Label } from "@/shared/components/label";

export type RequirementKind = "draft" | "submit";

export function RequirementMark({ kind }: { kind: RequirementKind }) {
  return (
    <span
      data-requirement={kind}
      className={kind === "draft" ? "text-destructive" : "text-yellow-700 dark:text-yellow-400"}
    >
      {" *"}
    </span>
  );
}

export function RequirementLabel({ htmlFor, kind, children, className }: {
  htmlFor?: string;
  kind?: RequirementKind;
  children: ReactNode;
  className?: string;
}) {
  return <Label htmlFor={htmlFor} className={className}>{children}{kind && <RequirementMark kind={kind} />}</Label>;
}

export function RequirementLegend({ attachmentNote = false }: { attachmentNote?: boolean }) {
  return (
    <div className="rounded-md border bg-muted/30 px-3 py-2 text-xs text-muted-foreground" aria-label="Field requirement guide">
      <span><RequirementMark kind="draft" /> Required to save a draft.</span>{" "}
      <span><RequirementMark kind="submit" /> May be left empty in a draft, but is required before submission.</span>
      {attachmentNote && <div className="mt-1">An attachment can be added after the draft is created and is required before submission.</div>}
    </div>
  );
}

export function EmptyFieldHint({ show, kind, children, id }: {
  show: boolean;
  kind?: RequirementKind;
  children: ReactNode;
  id?: string;
}) {
  if (!show) return null;
  return <p id={id} className={`mt-1 text-xs ${kind === "submit" ? "text-amber-700 dark:text-amber-400" : "text-muted-foreground"}`}>{children}</p>;
}
