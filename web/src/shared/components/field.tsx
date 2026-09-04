import type { ReactNode } from "react";

// Labeled value shown in detail grids (uppercase caption above the value).
export function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <div className="text-xs uppercase tracking-wide text-muted-foreground">{label}</div>
      <div className="mt-0.5 text-sm break-words">{children}</div>
    </div>
  );
}
