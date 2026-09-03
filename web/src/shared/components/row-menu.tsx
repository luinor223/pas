import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { Button } from "@/shared/components/button";

export type RowMenuItem = {
  label: string;
  onClick: () => void;
  danger?: boolean;
};

// Row actions menu rendered in a portal (document.body, position: fixed) so it
// is never clipped by the table Card's overflow / horizontal scroll.
// Opens on click (touch + keyboard friendly); closes on outside click, Esc, scroll or resize.
export function RowMenu({ items, title = "Row actions" }: { items: RowMenuItem[]; title?: string }) {
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState<{ top: number; right: number }>({ top: 0, right: 0 });
  const btnRef = useRef<HTMLButtonElement>(null);

  const toggle = () => {
    if (!open && btnRef.current) {
      const r = btnRef.current.getBoundingClientRect();
      setPos({ top: r.bottom + 4, right: Math.max(8, window.innerWidth - r.right) });
    }
    setOpen((o) => !o);
  };

  useEffect(() => {
    if (!open) return;
    const close = () => setOpen(false);
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(false); };
    const onDoc = (e: MouseEvent) => {
      if (btnRef.current && !btnRef.current.contains(e.target as Node)) {
        const el = document.getElementById("row-menu-portal");
        if (!el || !el.contains(e.target as Node)) setOpen(false);
      }
    };
    window.addEventListener("scroll", close, true);
    window.addEventListener("resize", close);
    document.addEventListener("keydown", onKey);
    document.addEventListener("mousedown", onDoc);
    return () => {
      window.removeEventListener("scroll", close, true);
      window.removeEventListener("resize", close);
      document.removeEventListener("keydown", onKey);
      document.removeEventListener("mousedown", onDoc);
    };
  }, [open ]);

  return (
    <>
      <Button ref={btnRef} size="sm" variant="ghost" onClick={toggle} title={title} aria-haspopup="menu" aria-expanded={open}>
        ...
      </Button>
      {open &&
        createPortal(
          <div
            id="row-menu-portal"
            role="menu"
            className="fixed z-50 w-48 rounded-md border bg-white shadow-lg text-left text-sm py-1"
            style={{ top: pos.top, right: pos.right }}
          >
            {items.map((it) => (
              <button
                key={it.label}
                role="menuitem"
                className={`block w-full px-3 py-2 hover:bg-muted text-left ${it.danger ? "text-destructive" : ""}`}
                onClick={() => { setOpen(false); it.onClick(); }}
              >
                {it.label}
              </button>
            ))}
          </div>,
          document.body,
        )}
    </>
  );
}
