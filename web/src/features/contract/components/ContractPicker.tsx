/* oxlint-disable react/refs -- refs returned by the shared combobox are only read in event handlers/effects. */
import { useQueries, useQuery } from "@tanstack/react-query";
import { Input } from "@/shared/components/input";
import { Label } from "@/shared/components/label";
import { useEntityCombobox } from "@/shared/hooks/use-entity-combobox";
import { contractQuery, contractsQuery } from "../hooks/contractQueries";
import { EmptyFieldHint, RequirementMark, type RequirementKind } from "./FormRequirement";

export function ContractPicker({ value, onChange, label = "Contract", placeholder = "Search contract number or customer...", statuses, eligibleForAddendum = false, allowClear = true, className, requirement, emptyHint }: {
  value: string; onChange: (id: string) => void; label?: string; placeholder?: string;
  statuses?: string[]; eligibleForAddendum?: boolean; allowClear?: boolean; className?: string;
  requirement?: RequirementKind; emptyHint?: string;
}) {
  const selectedQuery = useQuery({ ...contractQuery(value), enabled: Boolean(value) });
  const selected = selectedQuery.data;
  let optionIds: string[] = [];
  const combo = useEntityCombobox({ onChange, getOptionIds: () => optionIds, allowClear });
  const resultQueries = useQueries({
    queries: (statuses?.length ? statuses : [undefined]).map((status) => ({
      ...contractsQuery({ q: combo.debounced || undefined, status, size: 10 }), enabled: combo.open,
    })),
  });
  const results = Array.from(new Map(resultQueries
    .flatMap((query) => query.data?.content ?? [])
    .filter((contract) => !eligibleForAddendum || contract.canCreateAddendum)
    .map((contract) => [contract.id, contract])).values());
  optionIds = results.map((item) => item.id);
  const loading = resultQueries.some((query) => query.isLoading);
  const failed = resultQueries.some((query) => query.isError);
  const offset = allowClear ? 1 : 0;
  const hintId = `${combo.inputId}-hint`;
  const accessibleLabel = `${label || "Contract"}${requirement ? " *" : ""}`;

  return (
    <div ref={combo.boxRef} className={`relative ${className ?? ""}`}>
      {label && <Label htmlFor={combo.inputId}>{label}{requirement && <RequirementMark kind={requirement} />}</Label>}
      {value && !combo.editing ? (
        <div className="flex items-center gap-1">
          <Input id={combo.inputId} readOnly aria-label={accessibleLabel} className="cursor-pointer"
            value={selected ? `${selected.contractNo} · ${selected.customerName}` : selectedQuery.isError ? "Contract unavailable" : "Loading contract..."}
            title="Click to choose another contract" onClick={combo.startSelecting} {...combo.comboboxProps} />
          {allowClear && <button type="button" aria-label="Clear contract" className="px-1 text-xs text-muted-foreground hover:text-foreground" onClick={() => combo.select("")}>×</button>}
        </div>
      ) : (
        <Input id={combo.inputId} ref={combo.inputRef} aria-label={accessibleLabel} placeholder={value && selected ? `${selected.contractNo} · ${selected.customerName} — type to replace...` : placeholder}
          value={combo.text} onChange={(event) => { combo.setText(event.target.value); combo.setOpen(true); combo.setActiveIndex(-1); }} onFocus={() => combo.setOpen(true)} {...combo.comboboxProps} aria-describedby={emptyHint ? hintId : undefined} aria-required={requirement === "draft"} />
      )}
      <EmptyFieldHint id={hintId} show={!value && Boolean(emptyHint)} kind={requirement}>{emptyHint}</EmptyFieldHint>
      {combo.open && (combo.editing || !value) && (
        <div className="absolute z-30 mt-1 w-full rounded-md border bg-background text-sm shadow-lg">
          <div ref={combo.listRef} id={combo.listId} role="listbox" className="max-h-56 overflow-auto">
            {allowClear && <button type="button" {...combo.optionProps(0, !value)} className={`block w-full px-3 py-2 text-left text-muted-foreground hover:bg-muted ${combo.activeIndex === 0 ? "bg-muted" : ""}`} onClick={() => combo.select("")}>All contracts</button>}
            {results.map((contract, index) => {
              const optionIndex = index + offset;
              return <button key={contract.id} type="button" {...combo.optionProps(optionIndex, contract.id === value)} className={`block w-full px-3 py-2 text-left hover:bg-muted ${contract.id === value || combo.activeIndex === optionIndex ? "bg-muted" : ""}`} onClick={() => combo.select(contract.id)}>
                <span className="font-medium">{contract.contractNo}</span><span className="text-muted-foreground"> · {contract.customerName}</span>
              </button>;
            })}
          </div>
          <div aria-live="polite" className="border-t px-3 py-2 text-xs text-muted-foreground">{pickerStatus(loading, failed, results.length, "contract")}</div>
        </div>
      )}
    </div>
  );
}

function pickerStatus(loading: boolean, failed: boolean, count: number, noun: string) {
  if (loading) return "Searching...";
  if (failed && count === 0) return `Could not search ${noun}s.`;
  if (count === 0) return `No matching ${noun}s.`;
  if (failed) return `Some ${noun} results could not be loaded.`;
  if (count >= 10) return "Showing the first matches. Type more to narrow the list.";
  return `${count} matching ${noun}${count === 1 ? "" : "s"}.`;
}
