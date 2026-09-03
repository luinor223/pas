import { useQuery } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { customersQuery, customerQuery } from "../hooks/contractQueries";
import { Input } from "@/shared/components/input";
import { Label } from "@/shared/components/label";

// Searchable customer picker: server-side type-ahead (GET /customers?q=, size 10)
// instead of loading every customer into a native <select>.
// Clicking the selected chip re-opens search so another customer can be picked
// without clearing first.
export function CustomerPicker({
  value,
  onChange,
  label = "Customer",
  placeholder = "Type code or name...",
  className,
}: {
  value: string;
  onChange: (id: string) => void;
  label?: string;
  placeholder?: string;
  className?: string;
}) {
  const [text, setText] = useState("");
  const [debounced, setDebounced] = useState("");
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState(false);
  const boxRef = useRef<HTMLDivElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const t = setTimeout(() => setDebounced(text.trim()), 300);
    return () => clearTimeout(t);
  }, [text]);

  useEffect(() => {
    const onDoc = (e: MouseEvent) => {
      if (boxRef.current && !boxRef.current.contains(e.target as Node)) {
        setOpen(false);
        setEditing(false);
      }
    };
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, []);

  useEffect(() => {
    if (editing) searchRef.current?.focus();
  }, [editing]);

  // A new pick replaces the value — no need to clear first.
  useEffect(() => {
    if (!value) setEditing(false);
  }, [value]);

  const searchQ = useQuery({
    ...customersQuery({ q: debounced || undefined, size: 10 }),
    enabled: open,
  });
  const selectedQ = useQuery({ ...customerQuery(value), enabled: !!value });
  const selected = selectedQ.data;
  const results = searchQ.data?.content ?? [];

  const startReselect = () => {
    setText("");
    setDebounced("");
    setEditing(true);
    setOpen(true);
  };

  return (
    <div ref={boxRef} className={`relative ${className ?? ""}`}>
      {label && <Label>{label}</Label>}
      {value && selected && !editing ? (
        <div className="flex items-center gap-1">
          <Input
            readOnly
            aria-label={label || "Customer"}
            value={`${selected.code} · ${selected.name}`}
            onFocus={startReselect}
            onClick={startReselect}
            title="Click to choose another customer"
            className="cursor-pointer"
          />
          <button
            type="button"
            className="text-xs text-muted-foreground hover:text-foreground px-1"
            title="Clear customer filter"
            onClick={() => { onChange(""); setText(""); setDebounced(""); }}
          >
            ×
          </button>
        </div>
      ) : (
        <Input
          ref={searchRef}
          aria-label={label || "Customer"}
          placeholder={value && selected ? `${selected.code} · ${selected.name} — type to replace...` : placeholder}
          value={text}
          onChange={(e) => { setText(e.target.value); setOpen(true); }}
          onFocus={() => setOpen(true)}
        />
      )}
      {open && (editing || !value) && (
        <div className="absolute z-20 mt-1 w-full max-h-56 overflow-auto rounded-md border bg-white shadow-lg text-sm">
          <button
            type="button"
            className="block w-full px-3 py-2 text-left hover:bg-muted text-muted-foreground"
            onClick={() => { onChange(""); setOpen(false); setEditing(false); setText(""); setDebounced(""); }}
          >
            All customers
          </button>
          {searchQ.isLoading ? (
            <div className="px-3 py-2 text-muted-foreground">Searching...</div>
          ) : results.length === 0 ? (
            <div className="px-3 py-2 text-muted-foreground">No customers — type to search.</div>
          ) : (
            results.map((c) => (
              <button
                key={c.id}
                type="button"
                className={`block w-full px-3 py-2 text-left hover:bg-muted ${c.id === value ? "bg-blue-50/50" : ""}`}
                onClick={() => { onChange(c.id); setOpen(false); setEditing(false); setText(""); setDebounced(""); }}
              >
                <span className="font-medium">{c.code}</span>
                <span className="text-muted-foreground"> · {c.name}</span>
                {c.id === value && <span className="ml-1 text-xs text-muted-foreground">(current)</span>}
              </button>
            ))
          )}
        </div>
      )}
    </div>
  );
}
