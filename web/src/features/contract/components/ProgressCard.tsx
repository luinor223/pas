import type { ProgressResponse } from "../types/contractTypes";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { Badge } from "@/shared/components/badge";
import { StatusBadge } from "@/shared/components/status-badge";

export function ProgressCard({ progress, isLoading, error }: { progress?: ProgressResponse; isLoading?: boolean; error?: unknown }) {
  if (isLoading) return <Card><CardContent className="text-sm text-muted-foreground p-4">Loading progress...</CardContent></Card>;
  if (error) return <Card><CardContent className="text-sm text-destructive p-4">Failed to load progress: {String((error as {message?:string})?.message ?? error)}</CardContent></Card>;
  if (!progress) return null;

  const isPending = progress.workflowState === "INITIALIZATION_PENDING";
  const hasInstance = !!progress.instanceId;

  return (
    <Card>
      <CardHeader><CardTitle className="text-base">Approval Progress</CardTitle></CardHeader>
      <CardContent className="space-y-3 text-sm">
        <div className="flex flex-wrap gap-2 items-center">
          <span>Document: <StatusBadge status={progress.documentStatus} /></span>
          <span>Workflow: <Badge variant="secondary">{progress.workflowState}</Badge></span>
          {progress.priority && <Badge variant="outline">Priority: {progress.priority}</Badge>}
        </div>

        {isPending && (
          <div className="rounded bg-amber-50 border border-amber-200 p-3 text-amber-800 text-xs">
            <div className="font-medium">Workflow initialization pending</div>
            <div>Submitted but workflow not yet started (D4 outbox dispatch). Will auto-resolve; retry.</div>
            <div className="mt-1 text-muted-foreground">This is expected for a moment after submit — not an error.</div>
          </div>
        )}

        {!hasInstance && !isPending && (
          <div className="text-xs text-muted-foreground">No workflow instance yet. Submit the document to start approval.</div>
        )}

        {hasInstance && (
          <>
            <div className="text-xs text-muted-foreground">Instance {progress.instanceId?.slice(0, 8)} · v{progress.definitionVersionNo} · {progress.requestedByName} · {progress.startedAt ? new Date(progress.startedAt).toLocaleString() : ""}</div>
            {progress.currentStep && (
              <div className="rounded border p-3 bg-blue-50/50">
                <div className="font-medium">Current: Step {progress.currentStep.stepNo} · {progress.currentStep.name} · {progress.currentStep.approverRole}</div>
                <div className="text-xs text-muted-foreground">Assignees: {progress.currentStep.assigneeNames.join(", ") || "—"} {progress.currentStep.overdue && <span className="text-destructive">· Overdue</span>}</div>
                {progress.currentStep.activatedAt && <div className="text-xs">Activated {new Date(progress.currentStep.activatedAt).toLocaleString()} {progress.currentStep.slaHours ? `· SLA ${progress.currentStep.slaHours}h` : ""}</div>}
              </div>
            )}
            <div className="space-y-1">
              {progress.steps.map((s) => (
                <div key={s.stepNo} className="flex gap-2 text-xs border rounded p-2">
                  <span className="font-medium">#{s.stepNo} {s.name}</span>
                  <Badge variant="outline" className="text-xs">{s.status}</Badge>
                  <span>{s.approverRole}</span>
                  <span className="text-muted-foreground">{s.assigneeNames.join(", ")}</span>
                  {s.action && <span>· {s.action.action} by {s.action.actorName} {s.action.comment ? `— "${s.action.comment}"` : ""}</span>}
                </div>
              ))}
            </div>
          </>
        )}

        <div className="rounded bg-muted p-3 text-xs text-muted-foreground">
          <div className="font-medium">E-signatures</div>
          <div>Signing service not configured (esign-service 8007 pending). Document stays APPROVED until signing service lands; use Approvals to continue.</div>
        </div>
      </CardContent>
    </Card>
  );
}
