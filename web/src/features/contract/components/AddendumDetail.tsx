import { useState } from "react";
import { useIsMutating, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { addendumHistoryQuery, addendumProgressQuery, addendumQuery, attachmentsQuery } from "../hooks/contractQueries";
import { contractApi } from "../services/contractApi";
import { AttachmentPanel } from "./AttachmentPanel";
import { AddendumEditDialog } from "./AddendumEditDialog";
import { ApprovalProgressPanel } from "./ApprovalProgressPanel";
import { HistoryTimeline } from "./HistoryTimeline";
import { addendumChangeTypeLabel, isUserCancellableStatus } from "../contractOptions";
import { useCurrentUser } from "@/features/auth/hooks/useCurrentUser";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { Badge } from "@/shared/components/badge";
import { Button } from "@/shared/components/button";
import { StatusBadge } from "@/shared/components/status-badge";
import { DetailBackButton } from "@/shared/components/detail-back-link";
import { ConfirmDialog } from "@/shared/components/confirm-dialog";
import { DocumentSigningPanel } from "./DocumentSigningPanel";
import { getApiErrorMessage } from "@/shared/api/errors";
import { formatDate } from "@/shared/lib/format";

function responseStatus(error: unknown): number | undefined {
  return (error as { response?: { status?: number } })?.response?.status;
}

export function AddendumDetail({ id }: { id: string }) {
  const queryClient = useQueryClient();
  const [editOpen, setEditOpen] = useState(false);
  const [cancelOpen, setCancelOpen] = useState(false);
  const [cancelNotice, setCancelNotice] = useState<string | null>(null);
  const userQ = useCurrentUser();
  const permissions = userQ.data?.permissions ?? [];
  const canRead = permissions.includes("addendum:read");
  const canWrite = permissions.includes("addendum:write");
  const q = useQuery({
    ...addendumQuery(id),
    enabled: userQ.isSuccess && canRead,
    retry: (failureCount, error) => responseStatus(error) !== 404 && failureCount < 1,
  });
  const attachmentsQ = useQuery({
    ...attachmentsQuery("ADDENDUM", id),
    enabled: userQ.isSuccess && canRead,
  });
  const progressQ = useQuery({
    ...addendumProgressQuery(id),
    enabled: userQ.isSuccess && canRead,
  });
  const historyQ = useQuery({
    ...addendumHistoryQuery(id),
    enabled: userQ.isSuccess && canRead,
  });
  const attachmentMutations = useIsMutating({ mutationKey: ["attachment-mutation", "ADDENDUM", id] });
  const submitMut = useMutation({
    mutationFn: () => contractApi.submitAddendum(id),
    onSuccess: async (response) => {
      queryClient.setQueryData(["addendum", id], (current: typeof q.data) =>
        current ? { ...current, status: response.status } : current,
      );
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["addendum", id] }),
        queryClient.invalidateQueries({ queryKey: ["addenda"] }),
        queryClient.invalidateQueries({ queryKey: ["addendum-progress", id] }),
        queryClient.invalidateQueries({ queryKey: ["addendum-history", id] }),
        queryClient.invalidateQueries({ queryKey: ["attachments", "ADDENDUM", id] }),
      ]);
    },
  });
  const reviseMut = useMutation({
    mutationFn: () => contractApi.reviseAddendum(id),
    onSuccess: async (updated) => {
      queryClient.setQueryData(["addendum", id], updated);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["addendum", id] }),
        queryClient.invalidateQueries({ queryKey: ["addenda"] }),
        queryClient.invalidateQueries({ queryKey: ["addendum-progress", id] }),
        queryClient.invalidateQueries({ queryKey: ["addendum-history", id] }),
        queryClient.invalidateQueries({ queryKey: ["attachments", "ADDENDUM", id] }),
      ]);
    },
  });
  const cancelMut = useMutation({
    mutationFn: (reason?: string) => contractApi.cancelAddendum(id, reason),
    onMutate: () => setCancelNotice(null),
    onSuccess: async (response) => {
      if (response.status === "CANCELLED") {
        queryClient.setQueryData(["addendum", id], (current: typeof q.data) =>
          current ? { ...current, status: "CANCELLED" } : current,
        );
      } else {
        setCancelNotice(response.detail
          ?? "Cancellation is still pending. The addendum has not changed status; please try again shortly.");
      }
      setCancelOpen(false);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["addendum", id] }),
        queryClient.invalidateQueries({ queryKey: ["addenda"] }),
        queryClient.invalidateQueries({ queryKey: ["addendum-progress", id] }),
        queryClient.invalidateQueries({ queryKey: ["addendum-history", id] }),
        queryClient.invalidateQueries({ queryKey: ["attachments", "ADDENDUM", id] }),
      ]);
    },
  });

  if (userQ.isLoading || (canRead && q.isLoading)) {
    return <div className="text-sm text-muted-foreground">Loading addendum...</div>;
  }
  if (!canRead) {
    return <Card><CardContent className="p-6 text-sm">You do not have access to addenda.</CardContent></Card>;
  }
  if (q.isError && !q.data) {
    return responseStatus(q.error) === 404
      ? <Card><CardContent className="p-6 text-sm">Addendum not found.</CardContent></Card>
      : <Card><CardContent className="p-6 text-sm text-destructive">Failed to load addendum.</CardContent></Card>;
  }

  const addendum = q.data;
  if (!addendum) return null;
  const editable = addendum.status === "DRAFT" || addendum.status === "REVISION_REQUESTED";
  const canSubmit = canWrite && addendum.status === "DRAFT";
  const canRevise = canWrite && addendum.status === "REJECTED";
  const canCancel = canWrite && isUserCancellableStatus(addendum.status);
  const hasAttachments = (attachmentsQ.data?.length ?? 0) > 0;
  const lifecyclePending = submitMut.isPending || reviseMut.isPending || cancelMut.isPending;

  const onEditSaved = async (updated: typeof addendum) => {
    queryClient.setQueryData(["addendum", id], updated);
    setEditOpen(false);
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["addendum", id] }),
      queryClient.invalidateQueries({ queryKey: ["addenda"] }),
      queryClient.invalidateQueries({ queryKey: ["addendum-progress", id] }),
      queryClient.invalidateQueries({ queryKey: ["addendum-history", id] }),
      queryClient.invalidateQueries({ queryKey: ["attachments", "ADDENDUM", id] }),
    ]);
  };

  return (
    <div className="space-y-4">
      {q.isError && (
        <div role="alert" className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
          The addendum was updated, but its latest refresh failed. The confirmed update is still shown.
        </div>
      )}
      <div className="flex items-start justify-between gap-3">
        <div className="flex items-center gap-3">
          <DetailBackButton to="/addenda" label="Back to addenda" />
          <div>
            <div className="flex items-center gap-2">
              <h1 className="text-xl font-bold">{addendum.addendumNo}</h1>
              <StatusBadge status={addendum.status} />
            </div>
            <div className="text-sm text-muted-foreground">
              {addendumChangeTypeLabel(addendum.changeType)} · Contract {addendum.contractNo}
            </div>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {canWrite && editable && <Button size="sm" variant="outline" disabled={lifecyclePending} onClick={() => setEditOpen(true)}>Edit</Button>}
          {canSubmit && (
            <Button
              size="sm"
              disabled={attachmentsQ.isLoading || attachmentMutations > 0 || !hasAttachments || submitMut.isPending}
              onClick={() => submitMut.mutate()}
            >
              {submitMut.isPending ? "Submitting..." : "Submit for approval"}
            </Button>
          )}
          {canRevise && <Button size="sm" disabled={lifecyclePending} onClick={() => reviseMut.mutate()}>Revise</Button>}
          {canCancel && <Button size="sm" variant="destructive" disabled={lifecyclePending || attachmentMutations > 0} onClick={() => setCancelOpen(true)}>Cancel</Button>}
          {!canWrite && <Badge variant="secondary">Read-only access</Badge>}
        </div>
      </div>

      {canSubmit && !attachmentsQ.isLoading && !hasAttachments && !attachmentsQ.isError && (
        <div role="status" className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
          Upload at least one attachment before submitting this addendum for approval.
        </div>
      )}
      {(submitMut.isError || reviseMut.isError) && (
        <div role="alert" className="text-sm text-destructive">
          {getApiErrorMessage(submitMut.error ?? reviseMut.error, "Addendum action failed")}
        </div>
      )}
      {cancelNotice && (
        <div role="status" className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
          {cancelNotice}
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <div className="space-y-4 lg:col-span-2">
          <Card>
            <CardHeader><CardTitle className="text-base">General information</CardTitle></CardHeader>
            <CardContent className="grid grid-cols-1 gap-3 text-sm sm:grid-cols-2">
              <div><div className="text-xs text-muted-foreground">ADDENDUM NUMBER</div><div>{addendum.addendumNo}</div></div>
              <div>
                <div className="text-xs text-muted-foreground">PARENT CONTRACT</div>
                <Link to="/contracts" search={{ id: addendum.contractId } as never} className="text-blue-600 hover:underline">
                  {addendum.contractNo}
                </Link>
              </div>
              <div><div className="text-xs text-muted-foreground">CHANGE TYPE</div><div>{addendumChangeTypeLabel(addendum.changeType)}</div></div>
              <div><div className="text-xs text-muted-foreground">EFFECTIVE FROM</div><div>{formatDate(addendum.effectiveFrom)}</div></div>
              {addendum.newValidTo && <div><div className="text-xs text-muted-foreground">NEW VALID TO</div><div>{formatDate(addendum.newValidTo)}</div></div>}
              {addendum.paymentTermOverride && <div><div className="text-xs text-muted-foreground">PAYMENT TERM</div><div>{addendum.paymentTermOverride}</div></div>}
              <div className="sm:col-span-2"><div className="text-xs text-muted-foreground">DESCRIPTION</div><div>{addendum.description ?? "—"}</div></div>
            </CardContent>
          </Card>

          <AttachmentPanel
            ownerType="ADDENDUM"
            ownerId={addendum.id}
            editable={editable}
            mutationsDisabled={lifecyclePending}
          />

          {addendum.services.length > 0 && (
            <Card>
              <CardHeader><CardTitle className="text-base">Added services</CardTitle></CardHeader>
              <CardContent className="space-y-3">
                {addendum.services.map((service) => (
                  <div key={service.id} className="grid grid-cols-1 gap-2 rounded-lg border p-3 text-sm sm:grid-cols-3">
                    <div><div className="text-xs text-muted-foreground">SERVICE</div><div>{service.serviceCode} · {service.serviceName}</div></div>
                    <div><div className="text-xs text-muted-foreground">UNIT</div><div>{service.unit ?? "—"}</div></div>
                    <div><div className="text-xs text-muted-foreground">SCOPE</div><div>{service.scopeNote ?? "—"}</div></div>
                  </div>
                ))}
              </CardContent>
            </Card>
          )}
        </div>

        <div className="space-y-4">
          <ApprovalProgressPanel progress={progressQ.data} isLoading={progressQ.isLoading} error={progressQ.error} />
          <DocumentSigningPanel key={`ADDENDUM:${addendum.id}`} documentType="ADDENDUM" documentId={addendum.id} documentStatus={addendum.status} />
          <Card>
            <CardHeader><CardTitle className="text-base">Record status</CardTitle></CardHeader>
            <CardContent className="space-y-3 text-sm">
              <div><div className="text-xs text-muted-foreground">CURRENT STATUS</div><div className="mt-1"><StatusBadge status={addendum.status} /></div></div>
              <div><div className="text-xs text-muted-foreground">RECORD VERSION</div><div>{addendum.version}</div></div>
            </CardContent>
          </Card>
        </div>
      </div>

      <Card>
        <CardHeader><CardTitle className="text-base">Status history</CardTitle></CardHeader>
        <CardContent>
          {historyQ.isError ? <div className="text-sm text-destructive">{getApiErrorMessage(historyQ.error, "Failed to load status history")}</div> : <HistoryTimeline history={historyQ.data} isLoading={historyQ.isLoading} />}
        </CardContent>
      </Card>

      {editOpen && <AddendumEditDialog addendum={addendum} onClose={() => setEditOpen(false)} onSaved={onEditSaved} />}
      <ConfirmDialog
        open={cancelOpen}
        title="Cancel this addendum?"
        body={<p>Addendum <span className="font-medium">{addendum.addendumNo}</span> will be cancelled. Any approval in progress will stop, and this cannot be undone here.</p>}
        confirmLabel="Cancel addendum"
        pendingLabel="Cancelling..."
        pending={cancelMut.isPending}
        error={cancelMut.isError ? cancelMut.error : undefined}
        reason={{ label: "Reason", placeholder: "Why is this addendum being cancelled?" }}
        onConfirm={(reason) => cancelMut.mutate(reason)}
        onCancel={() => setCancelOpen(false)}
      />
    </div>
  );
}
