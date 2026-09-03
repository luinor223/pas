import { useQuery } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { customersQuery, customerQuery } from "../hooks/contractQueries";
import { Input } from "@/shared/components/input";
import { Label } from "@/shared/components/label";

// Searchable customer picker: server-side type-ahead (GET /customers?q=, size 10)
// instead of loading every customer into a native <select>.
export function CustomerPicker({
  value,
  onChange,
  label = "Customer",
  placeholder = "Type code or name...",
}: {
  value: string;
  onChange: (id: string) => void;
  label?: string;
  placeholder?: string;
}) {
  const [text, setText] = useState("");
  const [debounced, setDebounced] = useState("");
  const [open, setOpen] = useState(false);
  const boxRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const t = setTimeout(() => setDebounced(text.trim()), 300);
    return () => clearTimeout(t);
  }, [text]);

  useEffect(() => {
    const onDoc = (e: MouseEvent) => {
      if (boxRef.current && !boxRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, []);

  const searchQ = useQuery({
    ...customersQuery({ q: debounced || undefined, size: 10 }),
    enabled: open,
  });
  const selectedQ = useQuery({ ...customerQuery(value), enabled: !!value });
  const selected = selectedQ.data;
  const results = searchQ.data?.content ?? [];

  return (
    <div ref={boxRef} className="relative">
      <Label>{label}</Label>
      {value && selected ? (
        <div className="flex items-center gap-1">
          <Input readOnly value={`${selected.code} · ${selected.name}`} onFocus={() => setOpen(true)} className="cursor-pointer" />
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
          placeholder={placeholder}
          value={text}
          onChange={(e) => { setText(e.target.value); setOpen(true); }}
          onFocus={() => setOpen(true)}
        />
      )}
      {open && !value && (
        <div className="absolute z-20 mt-1 w-full max-h-56 overflow-auto rounded-md border bg-white shadow-lg text-sm">
          {searchQ.isLoading ? (
            <div className="px-3 py-2 text-muted-foreground">Searching...</div>
          ) : results.length === 0 ? (
            <div className="px-3 py-2 text-muted-foreground">No customers — type to search.</div>
          ) : (
            results.map((c) => (
              <button
                key={c.id}
                type="button"
                className="block w-full px-3 py-2 text-left hover:bg-muted"
                onClick={() => { onChange(c.id); setOpen(false); setText(""); setDebounced(""); }}
              >
                <span className="font-medium">{c.code}</span>
                <span className="text-muted-foreground"> · {c.name}</span>
              </button>
            ))
          )}
        </div>
      )}
    </div>
  );
}
