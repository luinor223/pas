/* oxlint-disable react/refs -- refs returned by the shared combobox are only read in event handlers/effects. */
import { useQuery } from "@tanstack/react-query";
import { Input } from "@/shared/components/input";
import { Label } from "@/shared/components/label";
import { useEntityCombobox } from "@/shared/hooks/use-entity-combobox";
import { customersQuery, customerQuery } from "../hooks/contractQueries";
import { EmptyFieldHint, RequirementMark, type RequirementKind } from "./FormRequirement";

export function CustomerPicker({ value, onChange, label = "Customer", placeholder = "Type code or name...", status, className, requirement, emptyHint }: {
  value: string; onChange: (id: string) => void; label?: string; placeholder?: string; status?: string; className?: string;
  requirement?: RequirementKind; emptyHint?: string;
}) {
  let optionIds: string[] = [];
  const combo = useEntityCombobox({ onChange, getOptionIds: () => optionIds, allowClear: true });
  const searchQuery = useQuery({ ...customersQuery({ q: combo.debounced || undefined, status, size: 10 }), enabled: combo.open });
  const selectedQuery = useQuery({ ...customerQuery(value), enabled: Boolean(value) });
  const selected = selectedQuery.data;
  const results = searchQuery.data?.content ?? [];
  const hintId = `${combo.inputId}-hint`;
  const accessibleLabel = `${label || "Customer"}${requirement ? " *" : ""}`;
  optionIds = results.map((item) => item.id);

  return (
    <div ref={combo.boxRef} className={`relative ${className ?? ""}`}>
      {label && <Label htmlFor={combo.inputId}>{label}{requirement && <RequirementMark kind={requirement} />}</Label>}
      {value && !combo.editing ? (
        <div className="flex items-center gap-1">
          <Input id={combo.inputId} readOnly aria-label={accessibleLabel} className="cursor-pointer"
            value={selected ? `${selected.code} · ${selected.name}` : selectedQuery.isError ? "Customer unavailable" : "Loading customer..."}
            title="Click to choose another customer" onFocus={combo.startSelecting} onClick={combo.startSelecting} {...combo.comboboxProps} />
          <button type="button" aria-label="Clear customer" className="px-1 text-xs text-muted-foreground hover:text-foreground" onClick={() => combo.select("")}>×</button>
        </div>
      ) : (
        <Input id={combo.inputId} ref={combo.inputRef} aria-label={accessibleLabel} placeholder={value && selected ? `${selected.code} · ${selected.name} — type to replace...` : placeholder}
          value={combo.text} onChange={(event) => { combo.setText(event.target.value); combo.setOpen(true); combo.setActiveIndex(-1); }} onFocus={() => combo.setOpen(true)} {...combo.comboboxProps} aria-describedby={emptyHint ? hintId : undefined} aria-required={requirement === "draft"} />
      )}
      <EmptyFieldHint id={hintId} show={!value && Boolean(emptyHint)} kind={requirement}>{emptyHint}</EmptyFieldHint>
      {combo.open && (combo.editing || !value) && (
        <div className="absolute z-30 mt-1 w-full rounded-md border bg-background text-sm shadow-lg">
          <div ref={combo.listRef} id={combo.listId} role="listbox" className="max-h-56 overflow-auto">
            <button type="button" {...combo.optionProps(0, !value)} className={`block w-full px-3 py-2 text-left text-muted-foreground hover:bg-muted ${combo.activeIndex === 0 ? "bg-muted" : ""}`} onClick={() => combo.select("")}>All customers</button>
            {results.map((customer, index) => (
              <button key={customer.id} type="button" {...combo.optionProps(index + 1, customer.id === value)} className={`block w-full px-3 py-2 text-left hover:bg-muted ${customer.id === value || combo.activeIndex === index + 1 ? "bg-muted" : ""}`} onClick={() => combo.select(customer.id)}>
                <span className="font-medium">{customer.code}</span><span className="text-muted-foreground"> · {customer.name}</span>
              </button>
            ))}
          </div>
          <div aria-live="polite" className="border-t px-3 py-2 text-xs text-muted-foreground">
            {searchQuery.isLoading ? "Searching..." : searchQuery.isError ? "Could not search customers." : results.length === 0 ? "No matching customers." : results.length >= 10 ? "Showing the first matches. Type more to narrow the list." : `${results.length} matching customer${results.length === 1 ? "" : "s"}.`}
          </div>
        </div>
      )}
    </div>
  );
}
