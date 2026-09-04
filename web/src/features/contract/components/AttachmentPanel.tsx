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

export function AttachmentPanel({ ownerType, ownerId }: { ownerType: "CONTRACT" | "ADDENDUM"; ownerId: string }) {
  const qc = useQueryClient();
  const canWrite = useHasPermission(ownerType === "CONTRACT" ? "contract:write" : "addendum:write");
  const q = useQuery(attachmentsQuery(ownerType, ownerId));
  const [file, setFile] = useState<File | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<AttachmentResponse | null>(null);

  const uploadMut = useMutation({
    mutationFn: () => {
      if (!file) throw new Error("No file selected");
      return contractApi.uploadAttachment(ownerType, ownerId, file);
    },
    onSuccess: () => {
      setFile(null);
      qc.invalidateQueries({ queryKey: ["attachments", ownerType, ownerId] });
    },
  });

  const deleteMut = useMutation({
    mutationFn: (id: string) => contractApi.deleteAttachment(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["attachments", ownerType, ownerId] });
      setConfirmDelete(null);
    },
  });

  return (
    <Card>
      <CardHeader><CardTitle className="text-base">Attachments</CardTitle></CardHeader>
      <CardContent className="space-y-3">
        {q.isLoading ? <div className="text-sm text-muted-foreground">Loading...</div> : q.isError ? <div className="text-sm text-destructive">{getApiErrorMessage(q.error, "Failed")}</div> : q.data?.length === 0 ? <div className="text-sm text-muted-foreground">No attachments. {canWrite ? "A document needs at least one attachment before it can be submitted." : ""}</div> : (
          <div className="space-y-1">
            {q.data?.map((a) => (
              <div key={a.id} className="flex items-center justify-between border rounded p-2 text-sm">
                <div>
                  <div className="font-medium">{a.fileName}</div>
                  <div className="text-xs text-muted-foreground">{a.contentType} · {(a.sizeBytes / 1024).toFixed(1)} KB · {formatDateTime(a.uploadedAt)}</div>
                </div>
                <div className="flex gap-1">
                  <Button size="sm" variant="outline" onClick={() => window.open(contractApi.downloadAttachmentUrl(a.id), "_blank")}>Download</Button>
                  {canWrite && <Button size="sm" variant="destructive" onClick={() => setConfirmDelete(a)}>Delete</Button>}
                </div>
              </div>
            ))}
          </div>
        )}
        {canWrite && (
          <div className="flex gap-2 items-center">
            <input type="file" onChange={(e) => setFile(e.target.files?.[0] ?? null)} className="text-sm" />
            <Button size="sm" onClick={() => uploadMut.mutate()} disabled={!file || uploadMut.isPending}>{uploadMut.isPending ? "Uploading..." : "Upload"}</Button>
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
          pending={deleteMut.isPending}
          error={deleteMut.isError ? deleteMut.error : undefined}
          onConfirm={() => confirmDelete && deleteMut.mutate(confirmDelete.id)}
          onCancel={() => setConfirmDelete(null)}
        />
        {!canWrite && <div className="text-xs text-muted-foreground">You do not have permission to change attachments.</div>}
      </CardContent>
    </Card>
  );
}
