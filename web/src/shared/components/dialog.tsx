import * as React from "react";
import { createPortal } from "react-dom";
import { cn } from "@/shared/lib/cn";

const DialogContext = React.createContext<{ titleId: string; close: () => void } | null>(null);

type DialogProps = { open: boolean; onOpenChange: (open: boolean) => void; children: React.ReactNode };
export function Dialog({ open, onOpenChange, children }: DialogProps) {
  const titleId = React.useId();
  const onOpenChangeRef = React.useRef(onOpenChange);
  React.useEffect(() => { onOpenChangeRef.current = onOpenChange; }, [onOpenChange]);
  const close = React.useCallback(() => onOpenChangeRef.current(false), []);
  const contextValue = React.useMemo(() => ({ titleId, close }), [titleId, close]);
  React.useEffect(() => {
    if (!open) return;
    const app = document.getElementById("root");
    if (!app) return;
    app.inert = true;
    app.setAttribute("aria-hidden", "true");
    return () => {
      app.inert = false;
      app.removeAttribute("aria-hidden");
    };
  }, [open]);
  if (!open) return null;
  return createPortal(
    <DialogContext.Provider value={contextValue}>
      <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
        <div aria-hidden="true" className="fixed inset-0 bg-black/50" onClick={() => onOpenChange(false)} />
        {/* Full-width wrapper: clicks left/right of the box land here, not on the
            backdrop above — so it closes too. Content stops propagation below. */}
        <div className="relative z-50 flex max-h-full w-full justify-center" onClick={() => onOpenChange(false)}>{children}</div>
      </div>
    </DialogContext.Provider>,
    document.body,
  );
}
export function DialogContent({ className, children, onClick, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  const context = React.useContext(DialogContext);
  const contentRef = React.useRef<HTMLDivElement>(null);

  React.useEffect(() => {
    const content = contentRef.current;
    if (!content || !context) return;
    const dialogContent = content;
    const closeDialog = context.close;
    const previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const focusable = () => Array.from(content.querySelectorAll<HTMLElement>(
      'button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])',
    )).filter((element) => !element.hasAttribute("hidden"));
    const initial = content.querySelector<HTMLElement>("[data-autofocus]") ?? focusable()[0] ?? content;
    initial.focus();

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        closeDialog();
        return;
      }
      if (event.key !== "Tab") return;
      const elements = focusable();
      if (elements.length === 0) {
        event.preventDefault();
        dialogContent.focus();
        return;
      }
      const first = elements[0];
      const last = elements[elements.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }

    content.addEventListener("keydown", handleKeyDown);
    return () => {
      content.removeEventListener("keydown", handleKeyDown);
      queueMicrotask(() => previouslyFocused?.focus());
    };
  }, [context]);

  return <div ref={contentRef} role="dialog" aria-modal="true" aria-labelledby={context?.titleId} tabIndex={-1} className={cn("flex max-h-[calc(100vh-2rem)] w-full max-w-lg flex-col overflow-y-auto rounded-lg border bg-card p-6 text-card-foreground shadow-lg", className)} onClick={(e) => { e.stopPropagation(); onClick?.(e); }} {...props}>{children}</div>;
}
export function DialogHeader({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("flex flex-col space-y-2 mb-4", className)} {...props} />;
}
export function DialogTitle({ className, ...props }: React.HTMLAttributes<HTMLHeadingElement>) {
  const context = React.useContext(DialogContext);
  return <h2 id={context?.titleId} className={cn("text-lg font-semibold", className)} {...props} />;
}
export function DialogFooter({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("flex justify-end gap-2 mt-4", className)} {...props} />;
}
