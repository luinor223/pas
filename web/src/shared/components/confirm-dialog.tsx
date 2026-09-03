import { useState } from "react";
import { AlertTriangle } from "lucide-react";
import { Button } from "@/shared/components/button";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/shared/components/dialog";
import { Label } from "@/shared/components/label";
import { Textarea } from "@/shared/components/textarea";
import { getApiErrorMessage } from "@/shared/api/errors";

type ReasonField = { label: string; placeholder?: string; required?: boolean };

type ConfirmDialogProps = {
  open: boolean;
  title: string;
  /** What will happen, in the user's terms. Name the record being acted on. */
  body: React.ReactNode;
  confirmLabel: string;
  pendingLabel?: string;
  pending?: boolean;
  error?: unknown;
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
  title, body, confirmLabel, pendingLabel, pending = false, error, reason, onConfirm, onCancel,
}: Omit<ConfirmDialogProps, "open">) {
  const [text, setText] = useState("");
  const missingReason = reason?.required && text.trim() === "";

  return (
    <Dialog open onOpenChange={(next) => { if (!next && !pending) onCancel(); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <AlertTriangle size={18} className="text-destructive" />
            {title}
          </DialogTitle>
        </DialogHeader>

        <div className="space-y-3 text-sm">
          <div>{body}</div>
          {reason && (
            <div>
              <Label>{reason.label}{reason.required ? " *" : ""}</Label>
              <Textarea
                value={text}
                onChange={(e) => setText(e.target.value)}
                placeholder={reason.placeholder}
                rows={3}
              />
            </div>
          )}
          {/* Keep the dialog open on failure: closing it would hide the reason why. */}
          {error != null && (
            <div className="text-sm text-destructive">{getApiErrorMessage(error, "Action failed")}</div>
          )}
        </div>

        <DialogFooter>
          <Button variant="outline" disabled={pending} onClick={onCancel}>Keep</Button>
          <Button
            variant="destructive"
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
