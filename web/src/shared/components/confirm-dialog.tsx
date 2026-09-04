import { useId, useState } from "react";
import { AlertTriangle } from "lucide-react";
import { Button, type ButtonProps } from "@/shared/components/button";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/shared/components/dialog";
import { Label } from "@/shared/components/label";
import { Textarea } from "@/shared/components/textarea";
import { getApiErrorMessage } from "@/shared/api/errors";

type ReasonField = { label: string; placeholder?: string; required?: boolean; description?: string };

type ConfirmDialogProps = {
  open: boolean;
  title: string;
  /** What will happen, in the user's terms. Name the record being acted on. */
  body: React.ReactNode;
  confirmLabel: string;
  pendingLabel?: string;
  pending?: boolean;
  error?: unknown;
  cancelLabel?: string;
  confirmVariant?: ButtonProps["variant"];
  /** Captures a reason; it is stored on the record's history, so it is worth asking for. */
  reason?: ReasonField;
  onConfirm: (reason?: string) => void;
  onCancel: () => void;
};

/** Confirmation for an action that cannot be undone from the interface. */
export function ConfirmDialog({ open, ...props }: ConfirmDialogProps) {
  // Mounted only while open, so the reason field starts empty for each record
  // instead of carrying the previous one over.
  if (!open) return null;
  return <ConfirmDialogBody {...props} />;
}

function ConfirmDialogBody({
  title, body, confirmLabel, pendingLabel, pending = false, error, cancelLabel = "Keep",
  confirmVariant = "destructive", reason, onConfirm, onCancel,
}: Omit<ConfirmDialogProps, "open">) {
  const [text, setText] = useState("");
  const reasonId = useId();
  const missingReason = reason?.required && text.trim() === "";

  return (
    <Dialog open onOpenChange={(next) => { if (!next && !pending) onCancel(); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            {confirmVariant === "destructive" && <AlertTriangle size={18} className="text-destructive" />}
            {title}
          </DialogTitle>
        </DialogHeader>

        <div className="space-y-3 text-sm">
          <div>{body}</div>
          {reason && (
            <div>
              <Label htmlFor={reasonId}>
                {reason.label}{reason.required ? " *" : ""}
                {!reason.required && <span className="font-normal text-muted-foreground"> (optional)</span>}
              </Label>
              <Textarea
                data-autofocus
                id={reasonId}
                value={text}
                onChange={(e) => setText(e.target.value)}
                placeholder={reason.placeholder}
                rows={3}
                required={reason.required}
                aria-required={reason.required || undefined}
              />
              {reason.description && <p className="mt-2 text-xs text-muted-foreground">{reason.description}</p>}
            </div>
          )}
          {/* Keep the dialog open on failure: closing it would hide the reason why. */}
          {error != null && (
            <div className="text-sm text-destructive">{getApiErrorMessage(error, "Action failed")}</div>
          )}
        </div>

        <DialogFooter>
          <Button data-autofocus={reason ? undefined : true} variant="outline" disabled={pending} onClick={onCancel}>{cancelLabel}</Button>
          <Button
            variant={confirmVariant}
            disabled={pending || missingReason}
            onClick={() => onConfirm(text.trim() || undefined)}
          >
            {pending ? (pendingLabel ?? "Working...") : confirmLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
