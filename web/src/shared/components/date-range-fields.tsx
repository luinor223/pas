import { Input } from "@/shared/components/input";
import { Label } from "@/shared/components/label";

type DateRangeFieldsProps = {
  from: string;
  to: string;
  onFromChange: (value: string) => void;
  onToChange: (value: string) => void;
  type?: "date" | "datetime-local";
  fromLabel?: string;
  toLabel?: string;
  /** "grid" fills two cells of a parent grid; "inline" stays compact inside a FilterBar. */
  layout?: "grid" | "inline";
};

export function isInvalidDateRange(from: string, to: string): boolean {
  return Boolean(from && to && from > to);
}

/** Controlled date pair with native picker limits and a shared cross-field error. */
export function DateRangeFields({
  from,
  to,
  onFromChange,
  onToChange,
  type = "date",
  fromLabel = "From",
  toLabel = "To",
  layout = "grid",
}: DateRangeFieldsProps) {
  const invalid = isInvalidDateRange(from, to);

  if (layout === "inline") {
    return (
      <>
        <label className="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
          <span className="shrink-0">{fromLabel}</span>
          <Input
            type={type}
            className="w-40"
            value={from}
            max={to || undefined}
            aria-invalid={invalid}
            onChange={(event) => onFromChange(event.target.value)}
          />
        </label>
        <label className="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
          <span className="shrink-0">{toLabel}</span>
          <Input
            type={type}
            className="w-40"
            value={to}
            min={from || undefined}
            aria-invalid={invalid}
            onChange={(event) => onToChange(event.target.value)}
          />
        </label>
        {invalid && (
          <p role="alert" className="w-full text-sm text-destructive">
            “{toLabel}” must be the same as or later than “{fromLabel}”.
          </p>
        )}
      </>
    );
  }

  return (
    <div className="contents">
      <div className="min-w-0">
        <Label>{fromLabel}</Label>
        <Input
          type={type}
          value={from}
          max={to || undefined}
          aria-invalid={invalid}
          onChange={(event) => onFromChange(event.target.value)}
        />
      </div>
      <div className="min-w-0">
        <Label>{toLabel}</Label>
        <Input
          type={type}
          value={to}
          min={from || undefined}
          aria-invalid={invalid}
          onChange={(event) => onToChange(event.target.value)}
        />
      </div>
      {invalid && (
        <p role="alert" className="col-span-full text-sm text-destructive">
          “{toLabel}” must be the same as or later than “{fromLabel}”.
        </p>
      )}
    </div>
  );
}
