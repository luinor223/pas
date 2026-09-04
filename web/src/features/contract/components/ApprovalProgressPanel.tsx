import type { ProgressResponse } from "../types/contractTypes";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { getApiErrorMessage } from "@/shared/api/errors";
import { roleLabel, statusLabel } from "@/shared/lib/labels";
import { withoutInternalRuleCodes } from "@/shared/lib/text";

function emptyWorkflowMessage(progress?: ProgressResponse): string {
  if (progress?.workflowState === "INITIALIZATION_PENDING") {
    return "Your submission is being prepared for approval. This usually takes a moment.";
  }
  switch (progress?.documentStatus) {
    case "DRAFT": return "Submit the document to begin approval.";
    case "REVISION_REQUESTED": return "Edit the document to address the requested changes.";
    case "SUBMITTED":
    case "UNDER_REVIEW": return "Approval is in progress. Step details are not available yet.";
    case "REJECTED": return "This document was rejected. No approval step details are available.";
    case "APPROVED":
    case "ACTIVE":
    case "EXPIRED": return "Approval is complete. No approval step details are available.";
    case "CANCELLED": return "This document is cancelled. No approval steps are pending.";
    default: return "No approval workflow details are available.";
  }
}

export function ApprovalProgressPanel({ progress, isLoading, error }: { progress?: ProgressResponse; isLoading?: boolean; error?: unknown }) {
  const waiting = progress?.currentStep
    ? `Waiting on ${progress.currentStep.name ?? "the current step"} — Assignee: ${progress.currentStep.assigneeNames.join(", ") || "—"}`
    : progress?.workflowState === "INITIALIZATION_PENDING"
      ? "Your submission is being prepared for approval"
      : null;
  const steps = progress?.steps ?? [];

  return (
    <Card>
      <CardHeader><CardTitle className="text-base">Approval workflow</CardTitle></CardHeader>
      <CardContent className="space-y-3 text-sm">
        {isLoading ? <div className="text-muted-foreground">Loading approval progress...</div> : error ? (
          <div className="text-destructive">{getApiErrorMessage(error, "Failed to load approval progress")}</div>
        ) : waiting ? (
          <div className="rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-800">{waiting}</div>
        ) : null}
        {!isLoading && !error && (steps.length === 0 ? (
          <div className="text-xs text-muted-foreground">
            {emptyWorkflowMessage(progress)}
          </div>
        ) : (
          <div className="space-y-2">
            {steps.map((step) => (
              <div key={step.stepNo} className="flex items-start gap-2">
                <span className={`mt-1 flex h-4 w-4 items-center justify-center rounded-full border text-[10px] ${step.status === "APPROVED" ? "border-green-600 bg-green-600 text-white" : step.status === "ACTIVE" ? "border-amber-500 bg-amber-500" : "border-gray-300"}`}>
                  {step.status === "APPROVED" ? "✓" : ""}
                </span>
                <div>
                  <div className="text-sm font-medium">{step.name ?? `Step ${step.stepNo}`} <span className="text-xs text-muted-foreground">· {step.approverRole ? roleLabel(step.approverRole) : "—"}</span></div>
                  <div className="text-xs text-muted-foreground">
                    {step.action
                      ? `${step.action.action ? statusLabel(step.action.action) : "Actioned"} by ${step.action.actorName ?? "—"}${step.action.comment && withoutInternalRuleCodes(step.action.comment) ? ` — “${withoutInternalRuleCodes(step.action.comment)}”` : ""}`
                      : `${step.assigneeNames.join(", ") || "—"}${step.status === "ACTIVE" ? " — in progress" : ""}`}
                  </div>
                </div>
              </div>
            ))}
          </div>
        ))}
      </CardContent>
    </Card>
  );
}
