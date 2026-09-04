import { useEffect, useId, useRef, useState, type FocusEvent, type KeyboardEvent } from "react";
import { useQueries, useQuery } from "@tanstack/react-query";
import { Input } from "@/shared/components/input";
import { Label } from "@/shared/components/label";
import { contractQuery, contractsQuery } from "../hooks/contractQueries";
import { useDebouncedSearch } from "@/shared/lib/use-debounced-search";

export function ContractPicker({
  value,
  onChange,
  label = "Contract",
  placeholder = "Search contract number or customer...",
  statuses,
  allowClear = true,
  className,
}: {
  value: string;
  onChange: (id: string) => void;
  label?: string;
  placeholder?: string;
  statuses?: string[];
  allowClear?: boolean;
  className?: string;
}) {
  const [text, setText] = useState("");
  const debounced = useDebouncedSearch(text);
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);
  const boxRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const listId = useId();

  useEffect(() => {
    function closeOnOutsideClick(event: MouseEvent) {
      if (boxRef.current && !boxRef.current.contains(event.target as Node)) {
        setOpen(false);
        setEditing(false);
        setActiveIndex(-1);
      }
    }
    document.addEventListener("mousedown", closeOnOutsideClick);
    return () => document.removeEventListener("mousedown", closeOnOutsideClick);
  }, []);

  const resultQueries = useQueries({
    queries: (statuses?.length ? statuses : [undefined]).map((status) => ({
      ...contractsQuery({ q: debounced || undefined, status, size: 10 }),
      enabled: open,
    })),
  });
  const selectedQuery = useQuery({ ...contractQuery(value), enabled: Boolean(value) });
  const selected = selectedQuery.data;
  const results = Array.from(new Map(
    resultQueries.flatMap((query) => query.data?.content ?? []).map((contract) => [contract.id, contract]),
  ).values());
  const resultsLoading = resultQueries.some((query) => query.isLoading);
  const resultsError = resultQueries.some((query) => query.isError);
  const optionOffset = allowClear ? 1 : 0;
  const optionCount = results.length + optionOffset;

  function startSelecting() {
    setText("");
    setEditing(true);
    setOpen(true);
    setActiveIndex(-1);
    requestAnimationFrame(() => inputRef.current?.focus());
  }

  function select(id: string) {
    onChange(id);
    setText("");
    setOpen(false);
    setEditing(false);
    setActiveIndex(-1);
  }

  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === "Escape") {
      event.preventDefault();
      setOpen(false);
      setEditing(false);
      setActiveIndex(-1);
      return;
    }
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      if (!open) setOpen(true);
      if (optionCount === 0) return;
      setActiveIndex((current) => {
        if (event.key === "ArrowDown") return current >= optionCount - 1 ? 0 : current + 1;
        return current <= 0 ? optionCount - 1 : current - 1;
      });
      return;
    }
    if (event.key === "Enter" && open && activeIndex >= 0) {
      event.preventDefault();
      if (allowClear && activeIndex === 0) select("");
      else select(results[activeIndex - optionOffset]?.id ?? "");
    }
  }

  const comboboxProps = {
    role: "combobox",
    "aria-expanded": open,
    "aria-controls": listId,
    "aria-autocomplete": "list" as const,
    "aria-activedescendant": open && activeIndex >= 0 ? `${listId}-option-${activeIndex}` : undefined,
    onKeyDown: handleKeyDown,
    onBlur: (event: FocusEvent<HTMLInputElement>) => {
      if (!boxRef.current?.contains(event.relatedTarget as Node)) {
        setOpen(false);
        setEditing(false);
        setActiveIndex(-1);
      }
    },
  };

  return (
    <div ref={boxRef} className={`relative ${className ?? ""}`}>
      {label && <Label>{label}</Label>}
      {value && !editing ? (
        <div className="flex items-center gap-1">
          <Input
            readOnly
            aria-label={label || "Contract"}
            className="cursor-pointer"
            value={selected ? `${selected.contractNo} · ${selected.customerName}` : selectedQuery.isError ? "Contract unavailable" : "Loading contract..."}
            title="Click to choose another contract"
            onClick={startSelecting}
            onFocus={startSelecting}
            {...comboboxProps}
          />
          {allowClear && (
            <button
              type="button"
              aria-label="Clear contract"
              className="px-1 text-xs text-muted-foreground hover:text-foreground"
              title="Clear contract"
              onClick={() => select("")}
            >
              ×
            </button>
          )}
        </div>
      ) : (
        <Input
          ref={inputRef}
          aria-label={label || "Contract"}
          placeholder={value && selected ? `${selected.contractNo} · ${selected.customerName} — type to replace...` : placeholder}
          value={text}
          onChange={(event) => { setText(event.target.value); setOpen(true); setActiveIndex(-1); }}
          onFocus={() => setOpen(true)}
          {...comboboxProps}
        />
      )}
      {open && (editing || !value) && (
        <div id={listId} role="listbox" className="absolute z-30 mt-1 max-h-56 w-full overflow-auto rounded-md border bg-background text-sm shadow-lg">
          {allowClear && (
            <button
              id={`${listId}-option-0`}
              type="button"
              role="option"
              aria-selected={!value}
              className={`block w-full px-3 py-2 text-left text-muted-foreground hover:bg-muted ${activeIndex === 0 ? "bg-muted" : ""}`}
              onMouseEnter={() => setActiveIndex(0)}
              onClick={() => select("")}
            >
              All contracts
            </button>
          )}
          {resultsLoading ? (
            <div className="px-3 py-2 text-muted-foreground">Searching...</div>
          ) : resultsError ? (
            <div className="px-3 py-2 text-destructive">Could not search contracts.</div>
          ) : results.length === 0 ? (
            <div className="px-3 py-2 text-muted-foreground">No matching contracts.</div>
          ) : (
            results.map((contract, index) => {
              const optionIndex = index + optionOffset;
              return (
              <button
                key={contract.id}
                id={`${listId}-option-${optionIndex}`}
                type="button"
                role="option"
                aria-selected={contract.id === value}
                className={`block w-full px-3 py-2 text-left hover:bg-muted ${contract.id === value ? "bg-muted font-medium" : ""} ${activeIndex === optionIndex ? "bg-muted" : ""}`}
                onMouseEnter={() => setActiveIndex(optionIndex)}
                onClick={() => select(contract.id)}
              >
                <span className="font-medium">{contract.contractNo}</span>
                <span className="text-muted-foreground"> · {contract.customerName}</span>
              </button>
              );
            })
          )}
          {!resultsLoading && results.length >= 10 && (
            <div className="border-t px-3 py-2 text-xs text-muted-foreground">Showing the first matches. Type more to narrow the list.</div>
          )}
        </div>
      )}
    </div>
  );
}
