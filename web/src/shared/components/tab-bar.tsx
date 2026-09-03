import { Badge } from "@/shared/components/badge";
import { cn } from "@/shared/lib/cn";

export type TabItem<T extends string> = { value: T; label: string; count?: number };

/** Underlined tab row used across detail screens and the approvals inbox. */
export function TabBar<T extends string>({
  tabs,
  value,
  onChange,
  className,
}: {
  tabs: readonly TabItem<T>[];
  value: T;
  onChange: (next: T) => void;
  className?: string;
}) {
  return (
    <div role="tablist" className={cn("flex gap-6 overflow-x-auto border-b border-border", className)}>
      {tabs.map((tab) => {
        const active = tab.value === value;
        return (
          <button
            key={tab.value}
            type="button"
            role="tab"
            aria-selected={active}
            onClick={() => onChange(tab.value)}
            className={cn(
              "-mb-px flex items-center gap-2 whitespace-nowrap border-b-2 px-1 py-3 text-sm font-medium transition-colors",
              active
                ? "border-primary text-primary"
                : "border-transparent text-muted-foreground hover:text-foreground",
            )}
          >
            {tab.label}
            {tab.count !== undefined && <Badge variant={active ? "default" : "secondary"}>{tab.count}</Badge>}
          </button>
        );
      })}
    </div>
  );
}
