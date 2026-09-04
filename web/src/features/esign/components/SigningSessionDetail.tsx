import { useState, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { signingSessionQuery } from "../hooks/esignQueries";
import { esignApi } from "../services/esignApi";
import { Button } from "@/shared/components/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { StatusBadge } from "@/shared/components/status-badge";
import { DetailBackButton } from "@/shared/components/detail-back-link";
import { ConfirmDialog } from "@/shared/components/confirm-dialog";
import { getApiErrorMessage } from "@/shared/api/errors";
import { useHasPermission } from "@/features/auth/hooks/usePermissions";
import { formatDateTime } from "@/shared/lib/format";

const CANCELLABLE = new Set(["PENDING_SEND", "SIGNING"]);

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <div className="text-xs uppercase tracking-wide text-muted-foreground">{label}</div>
      <div className="mt-0.5 text-sm break-words">{children}</div>
    </div>
  );
}

export function SigningSessionDetail({ id }: { id: string }) {
  const qc = useQueryClient();
  const canCancel = useHasPermission("esign:cancel");
  const [confirmCancel, setConfirmCancel] = useState(false);

  const detailQ = useQuery(signingSessionQuery(id));
  const session = detailQ.data;

  const cancelMut = useMutation({
    mutationFn: (reason?: string) => esignApi.cancel(id, reason),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["signing-session", id] });
      qc.invalidateQueries({ queryKey: ["signing-sessions"] });
      setConfirmCancel(false);
    },
  });

  if (detailQ.isLoading) return <div className="text-sm text-muted-foreground">Loading...</div>;
  if (detailQ.isError || !session) return <div className="text-sm text-destructive">{getApiErrorMessage(detailQ.error, "Signature request not found")}</div>;

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
          <div className="flex items-center gap-3">
            <DetailBackButton to="/e-signatures" label="Back to e-signatures" />
            <div>
              <CardTitle className="flex items-center gap-2">{session.sessionNo}<StatusBadge status={session.status} /></CardTitle>
              <div className="mt-0.5 text-sm text-muted-foreground">{session.documentNo} · {session.documentTypeCode}</div>
            </div>
          </div>
          {canCancel && CANCELLABLE.has(session.status) && <Button variant="outline" onClick={() => setConfirmCancel(true)}>Cancel request</Button>}
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-3">
            <Field label="Customer">{session.customerName ?? "—"}</Field>
            <Field label="Signer">{session.signerName ?? "—"}</Field>
            <Field label="Signer email">{session.signerEmail ?? "—"}</Field>
            <Field label="Provider">{session.provider ?? "—"}</Field>
            <Field label="Provider ref">{session.providerRef ?? "—"}</Field>
            <Field label="Attempts"><span className="tabular-nums">{session.attempts}</span></Field>
            <Field label="Requested by">{session.requestedByName ?? "—"}</Field>
            <Field label="Sent">{formatDateTime(session.sentAt)}</Field>
            <Field label="Completed">{formatDateTime(session.completedAt)}</Field>
          </div>
          {session.lastError ? (
            <div className="mt-4 rounded-lg bg-st-rejected-bg p-3 text-sm text-st-rejected">
              <div className="text-xs font-medium uppercase tracking-wide">Last error</div>
              <div className="mt-0.5 break-words">{session.lastError}</div>
            </div>
          ) : null}
        </CardContent>
      </Card>

      <ConfirmDialog
        open={confirmCancel}
        title="Cancel this signature request?"
        body={<p>Request <span className="font-medium">{session.sessionNo}</span> for <span className="font-medium">{session.documentNo}</span> will be cancelled with the provider. This cannot be undone here.</p>}
        confirmLabel="Cancel request"
        pendingLabel="Cancelling..."
        pending={cancelMut.isPending}
        error={cancelMut.isError ? cancelMut.error : undefined}
        reason={{ label: "Reason", placeholder: "Why is this request being cancelled?" }}
        onConfirm={(reason) => cancelMut.mutate(reason)}
        onCancel={() => setConfirmCancel(false)}
      />
    </div>
  );
}
