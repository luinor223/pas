import type { StatusHistoryResponse } from "../types/contractTypes";
import { Badge } from "@/shared/components/badge";
import { formatDateTime } from "@/shared/lib/format";
import { statusLabel } from "@/shared/lib/labels";
import { withoutInternalRuleCodes } from "@/shared/lib/text";

const TRIGGER_LABELS: Record<string, string> = {
  U: "User action",
  W: "Approval workflow",
  E: "External event",
  S: "System update",
};

function publicNote(note: string | null): string | null {
  if (!note) return null;
  const cleaned = withoutInternalRuleCodes(note);
  return cleaned || null;
}

export function HistoryTimeline({ history, isLoading }: { history?: StatusHistoryResponse[]; isLoading?: boolean }) {
  if (isLoading) return <div className="text-sm text-muted-foreground">Loading history...</div>;
  if (!history || history.length === 0) return <div className="text-sm text-muted-foreground">No history yet.</div>;
  return (
    <div className="space-y-3">
      {history.map((h) => (
        <div key={h.id} className="flex gap-3 border-l-2 py-1 pl-3 text-sm" style={{ borderColor: "#e2e8f0" }}>
          <div className="flex-1">
            <div className="flex items-center gap-2">
              <Badge variant="outline" className="text-xs">
                {h.fromStatus ? `${statusLabel(h.fromStatus)} → ${statusLabel(h.toStatus)}` : `Created as ${statusLabel(h.toStatus)}`}
              </Badge>
              <span className="text-xs text-muted-foreground">{TRIGGER_LABELS[h.trigger] ?? "Status update"}</span>
            </div>
            <div className="mt-1 text-xs text-muted-foreground">
              {h.actorName ?? "System"} · {formatDateTime(h.occurredAt)} {publicNote(h.note) ? `· ${publicNote(h.note)}` : ""}
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}
