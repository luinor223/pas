import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { attachmentsQuery } from "../hooks/contractQueries";
import { contractApi } from "../services/contractApi";
import { Button } from "@/shared/components/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { getApiErrorMessage } from "@/shared/api/errors";
import { useState } from "react";
import { useHasPermission } from "@/features/auth/hooks/usePermissions";
import { ConfirmDialog } from "@/shared/components/confirm-dialog";
import type { AttachmentResponse } from "../types/contractTypes";
import { formatDateTime } from "@/shared/lib/format";

export function AttachmentPanel({ ownerType, ownerId, editable = true, mutationsDisabled = false }: { ownerType: "CONTRACT" | "ADDENDUM"; ownerId: string; editable?: boolean; mutationsDisabled?: boolean }) {
  const qc = useQueryClient();
  const canWrite = useHasPermission(ownerType === "CONTRACT" ? "contract:write" : "addendum:write");
  const canManage = canWrite && editable;
  const q = useQuery(attachmentsQuery(ownerType, ownerId));
  const [file, setFile] = useState<File | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<AttachmentResponse | null>(null);

  const uploadMut = useMutation({
    mutationKey: ["attachment-mutation", ownerType, ownerId],
    mutationFn: () => {
      if (!file) throw new Error("No file selected");
      return contractApi.uploadAttachment(ownerType, ownerId, file);
    },
    onSuccess: (uploaded) => {
      setFile(null);
      qc.setQueryData<AttachmentResponse[]>(["attachments", ownerType, ownerId], (current) => [
        ...(current ?? []).filter((attachment) => attachment.id !== uploaded.id),
        uploaded,
      ]);
      return qc.invalidateQueries({ queryKey: ["attachments", ownerType, ownerId] });
    },
  });

  const deleteMut = useMutation({
    mutationKey: ["attachment-mutation", ownerType, ownerId],
    mutationFn: (id: string) => contractApi.deleteAttachment(id),
    onSuccess: (_response, deletedId) => {
      qc.setQueryData<AttachmentResponse[]>(["attachments", ownerType, ownerId], (current) =>
        (current ?? []).filter((attachment) => attachment.id !== deletedId),
      );
      setConfirmDelete(null);
      return qc.invalidateQueries({ queryKey: ["attachments", ownerType, ownerId] });
    },
  });

  return (
    <Card>
      <CardHeader><CardTitle className="text-base">Attachments</CardTitle></CardHeader>
      <CardContent className="space-y-3">
        {q.isLoading && !q.data ? <div className="text-sm text-muted-foreground">Loading...</div> : !q.data ? <div className="text-sm text-destructive">{getApiErrorMessage(q.error, "Failed")}</div> : q.data.length === 0 ? <div className="text-sm text-muted-foreground">No attachments. {canManage ? "A document needs at least one attachment before it can be submitted." : ""}</div> : (
          <div className="space-y-1">
            {q.data.map((a) => (
              <div key={a.id} className="flex items-center justify-between border rounded p-2 text-sm">
                <div>
                  <div className="font-medium">{a.fileName}</div>
                  <div className="text-xs text-muted-foreground">{a.contentType} · {(a.sizeBytes / 1024).toFixed(1)} KB · {formatDateTime(a.uploadedAt)}</div>
                </div>
                <div className="flex gap-1">
                  <Button size="sm" variant="outline" onClick={() => window.open(contractApi.downloadAttachmentUrl(a.id), "_blank")}>Download</Button>
                  {canManage && <Button size="sm" variant="destructive" disabled={mutationsDisabled} onClick={() => setConfirmDelete(a)}>Delete</Button>}
                </div>
              </div>
            ))}
          </div>
        )}
        {q.isError && q.data && <div role="alert" className="text-xs text-amber-700">Attachments were updated, but the latest refresh failed. The confirmed changes are still shown.</div>}
        {canManage && (
          <div className="flex gap-2 items-center">
            <input aria-label="Choose attachment file" type="file" disabled={mutationsDisabled} onChange={(e) => setFile(e.target.files?.[0] ?? null)} className="text-sm" />
            <Button size="sm" onClick={() => uploadMut.mutate()} disabled={mutationsDisabled || !file || uploadMut.isPending}>{uploadMut.isPending ? "Uploading..." : "Upload"}</Button>
          </div>
        )}
        {uploadMut.isError && <div className="text-xs text-destructive">{getApiErrorMessage(uploadMut.error, "Upload failed")}</div>}
        <ConfirmDialog
          open={!!confirmDelete}
          title="Delete this attachment?"
          body={
            <p>
              <span className="font-medium">{confirmDelete?.fileName}</span> will be removed from this
              document. The file cannot be restored here, and a submit may require an attachment.
            </p>
          }
          confirmLabel="Delete file"
          pendingLabel="Deleting..."
          pending={deleteMut.isPending || mutationsDisabled}
          error={deleteMut.isError ? deleteMut.error : undefined}
          onConfirm={() => confirmDelete && !mutationsDisabled && deleteMut.mutate(confirmDelete.id)}
          onCancel={() => setConfirmDelete(null)}
        />
        {!canWrite && <div className="text-xs text-muted-foreground">You do not have permission to change attachments.</div>}
        {canWrite && !editable && <div className="text-xs text-muted-foreground">Attachments cannot be changed in this status.</div>}
      </CardContent>
    </Card>
  );
}
