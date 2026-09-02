import * as React from "react";
import { cn } from "@/shared/lib/cn";

// KPI card: label, big value, an icon, and an optional delta/footnote.
export function StatCard({
  label,
  value,
  icon,
  foot,
  footTone = "muted",
}: {
  label: string;
  value: React.ReactNode;
  icon?: React.ReactNode;
  foot?: React.ReactNode;
  footTone?: "muted" | "positive" | "danger";
}) {
  const footCls = {
    muted: "text-muted-foreground",
    positive: "text-positive",
    danger: "text-destructive",
  }[footTone];
  return (
    <div className="rounded-xl border border-border bg-card p-5">
      <div className="flex items-start justify-between">
        <span className="text-[13px] font-medium text-muted-foreground">{label}</span>
        {icon && <span className="text-muted-foreground/70">{icon}</span>}
      </div>
      <div className="mt-3 text-[34px] font-bold leading-none tracking-tight tnum">{value}</div>
      {foot && <div className={cn("mt-3 text-xs font-medium", footCls)}>{foot}</div>}
    </div>
  );
}
