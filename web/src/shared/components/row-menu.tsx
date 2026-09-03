import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { Button } from "@/shared/components/button";
import { MoreHorizontal } from "lucide-react";

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
  const [pos, setPos] = useState({ top: 0, right: 8, ready: false });
  const btnRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);

  const toggle = () => {
    if (!open) setPos((current) => ({ ...current, ready: false }));
    setOpen((o) => !o);
  };

  useLayoutEffect(() => {
    if (!open || !btnRef.current || !menuRef.current) return;

    const button = btnRef.current.getBoundingClientRect();
    const menu = menuRef.current.getBoundingClientRect();
    const gap = 4;
    const edge = 8;
    const spaceBelow = window.innerHeight - button.bottom;
    const preferredTop = spaceBelow >= menu.height + gap
      ? button.bottom + gap
      : button.top - menu.height - gap;
    const top = Math.min(
      Math.max(edge, preferredTop),
      Math.max(edge, window.innerHeight - menu.height - edge),
    );

    setPos({
      top,
      right: Math.max(edge, window.innerWidth - button.right),
      ready: true,
    });
  }, [open, items.length]);

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
      <Button ref={btnRef} size="icon" variant="ghost" className="h-8 w-8" onClick={toggle} title={title} aria-label={title} aria-haspopup="menu" aria-expanded={open}>
        <MoreHorizontal size={18} aria-hidden="true" />
      </Button>
      {open &&
        createPortal(
          <div
            ref={menuRef}
            id="row-menu-portal"
            role="menu"
            className="fixed z-50 max-h-[calc(100vh-1rem)] w-48 overflow-y-auto rounded-md border bg-white py-1 text-left text-sm shadow-lg"
            style={{ top: pos.top, right: pos.right, visibility: pos.ready ? "visible" : "hidden" }}
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
