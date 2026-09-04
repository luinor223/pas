import { Badge } from "@/shared/components/badge";
import { cn } from "@/shared/lib/cn";
import { useId, type KeyboardEvent } from "react";

export type TabItem<T extends string> = { value: T; label: string; count?: number };

/** Underlined tab row used across detail screens and the approvals inbox. */
export function TabBar<T extends string>({
  tabs,
  value,
  onChange,
  className,
  panelId,
}: {
  tabs: readonly TabItem<T>[];
  value: T;
  onChange: (next: T) => void;
  className?: string;
  panelId?: string;
}) {
  const generatedId = useId();

  function handleKeyDown(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    let next = index;
    if (event.key === "ArrowRight") next = (index + 1) % tabs.length;
    else if (event.key === "ArrowLeft") next = (index - 1 + tabs.length) % tabs.length;
    else if (event.key === "Home") next = 0;
    else if (event.key === "End") next = tabs.length - 1;
    else return;
    event.preventDefault();
    onChange(tabs[next].value);
    document.getElementById(`${generatedId}-tab-${next}`)?.focus();
  }

  return (
    <div role="tablist" className={cn("flex gap-6 overflow-x-auto border-b border-border", className)}>
      {tabs.map((tab, index) => {
        const active = tab.value === value;
        return (
          <button
            key={tab.value}
            type="button"
            id={`${generatedId}-tab-${index}`}
            role="tab"
            aria-selected={active}
            aria-controls={panelId}
            tabIndex={active ? 0 : -1}
            onKeyDown={(event) => handleKeyDown(event, index)}
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
