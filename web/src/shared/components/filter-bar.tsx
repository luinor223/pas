import { cn } from "@/shared/lib/cn";

/**
 * One wrapping row of filter controls. A fixed column grid forces controls onto
 * new rows with empty cells beside them; wrapping keeps the block as short as
 * the controls actually need.
 *
 * Controls carry their own accessible name (aria-label) and describe themselves
 * in place ("All modules", a placeholder), so no label row is rendered.
 */
export function FilterBar({ className, children }: { className?: string; children: React.ReactNode }) {
  return <div className={cn("flex min-w-0 flex-wrap items-center gap-2", className)}>{children}</div>;
}

/** A control that needs a word in front of it to make sense, such as a bare date input. */
export function InlineFilter({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
      <span className="shrink-0">{label}</span>
      {children}
    </label>
  );
}
