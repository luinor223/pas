import type { StatusHistoryResponse } from "../types/contractTypes";
import { Badge } from "@/shared/components/badge";

export function HistoryTimeline({ history, isLoading }: { history?: StatusHistoryResponse[]; isLoading?: boolean }) {
  if (isLoading) return <div className="text-sm text-muted-foreground">Loading history...</div>;
  if (!history || history.length === 0) return <div className="text-sm text-muted-foreground">No history yet.</div>;
  return (
    <div className="space-y-3">
      {history.map((h) => (
        <div key={h.id} className="flex gap-3 border-l-2 pl-3 py-1 text-sm" style={{ borderColor: "#e2e8f0" }}>
          <div className="flex-1">
            <div className="flex items-center gap-2">
              <Badge variant="outline" className="text-xs">{h.fromStatus ?? "∅"} → {h.toStatus}</Badge>
              <span className="text-xs text-muted-foreground">{h.trigger}{h.triggerRef ? ` · ${h.triggerRef.slice(0, 8)}` : ""}</span>
            </div>
            <div className="text-xs text-muted-foreground mt-1">
              {h.actorName ?? "system"} · {new Date(h.occurredAt).toLocaleString()} {h.note ? `· ${h.note}` : ""}
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}
