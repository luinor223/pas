import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { attachmentsQuery } from "../hooks/contractQueries";
import { contractApi } from "../services/contractApi";
import { Button } from "@/shared/components/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { getApiErrorMessage } from "@/shared/api/errors";
import { useRef, useState } from "react";
import { ConfirmDialog } from "@/shared/components/confirm-dialog";
import type { AttachmentResponse } from "../types/contractTypes";
import { formatDateTime } from "@/shared/lib/format";
import { FileText, Upload } from "lucide-react";
import { cn } from "@/shared/lib/cn";

export function AttachmentPanel({ ownerType, ownerId, canEdit, mutationsDisabled = false }: { ownerType: "CONTRACT" | "ADDENDUM"; ownerId: string; canEdit: boolean; mutationsDisabled?: boolean }) {
  const qc = useQueryClient();
  const q = useQuery(attachmentsQuery(ownerType, ownerId));
  const [file, setFile] = useState<File | null>(null);
  const [isDragging, setIsDragging] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [confirmDelete, setConfirmDelete] = useState<AttachmentResponse | null>(null);

  const uploadMut = useMutation({
    mutationKey: ["attachment-mutation", ownerType, ownerId],
    mutationFn: () => {
      if (!file) throw new Error("No file selected");
      return contractApi.uploadAttachment(ownerType, ownerId, file);
    },
    onSuccess: (uploaded) => {
      setFile(null);
      if (fileInputRef.current) fileInputRef.current.value = "";
      qc.setQueryData<AttachmentResponse[]>(["attachments", ownerType, ownerId], (current) => [
        ...(current ?? []).filter((attachment) => attachment.id !== uploaded.id),
        uploaded,
      ]);
      return Promise.all([
        qc.invalidateQueries({ queryKey: ["attachments", ownerType, ownerId] }),
        qc.invalidateQueries({ queryKey: [ownerType === "CONTRACT" ? "contract" : "addendum", ownerId] }),
      ]);
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
      return Promise.all([
        qc.invalidateQueries({ queryKey: ["attachments", ownerType, ownerId] }),
        qc.invalidateQueries({ queryKey: [ownerType === "CONTRACT" ? "contract" : "addendum", ownerId] }),
      ]);
    },
  });

  return (
    <Card>
      <CardHeader><CardTitle className="text-base">Attachments</CardTitle></CardHeader>
      <CardContent className="space-y-3">
        {q.isLoading && !q.data ? <div className="text-sm text-muted-foreground">Loading...</div> : !q.data ? <div className="text-sm text-destructive">{getApiErrorMessage(q.error, "Failed")}</div> : q.data.length === 0 ? <div className="text-sm text-muted-foreground">No attachments. {canEdit ? "A document needs at least one attachment before it can be submitted." : ""}</div> : (
          <div className="space-y-1">
            {q.data.map((a) => (
              <div key={a.id} className="flex items-center justify-between border rounded p-2 text-sm">
                <div>
                  <div className="font-medium">{a.fileName}</div>
                  <div className="text-xs text-muted-foreground">{a.contentType} · {(a.sizeBytes / 1024).toFixed(1)} KB · {formatDateTime(a.uploadedAt)}</div>
                </div>
                <div className="flex gap-1">
                  <Button size="sm" variant="outline" onClick={() => window.open(contractApi.downloadAttachmentUrl(a.id), "_blank")}>Download</Button>
                  {canEdit && <Button size="sm" variant="destructive" disabled={mutationsDisabled} onClick={() => setConfirmDelete(a)}>Delete</Button>}
                </div>
              </div>
            ))}
          </div>
        )}
        {q.isError && q.data && <div role="alert" className="text-xs text-amber-700">Attachments were updated, but the latest refresh failed. The confirmed changes are still shown.</div>}
        {canEdit && (
          <div className="space-y-3">
            <input
              ref={fileInputRef}
              aria-label="Choose attachment file"
              type="file"
              disabled={mutationsDisabled || uploadMut.isPending}
              onChange={(event) => setFile(event.target.files?.[0] ?? null)}
              className="sr-only"
            />
            <div
              onDragEnter={(event) => {
                event.preventDefault();
                if (!mutationsDisabled && !uploadMut.isPending) setIsDragging(true);
              }}
              onDragOver={(event) => event.preventDefault()}
              onDragLeave={(event) => {
                if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setIsDragging(false);
              }}
              onDrop={(event) => {
                event.preventDefault();
                setIsDragging(false);
                if (!mutationsDisabled && !uploadMut.isPending) setFile(event.dataTransfer.files?.[0] ?? null);
              }}
              className={cn(
                "flex min-h-36 flex-col items-center justify-center rounded-lg border-2 border-dashed bg-muted/20 px-6 py-5 text-center transition-colors",
                isDragging && "border-primary bg-primary/5",
                mutationsDisabled || uploadMut.isPending ? "opacity-60" : "hover:border-primary/60 hover:bg-primary/5",
              )}
            >
              {file ? <FileText className="mb-2 h-8 w-8 text-primary" aria-hidden="true" /> : <Upload className="mb-2 h-8 w-8 text-primary" aria-hidden="true" />}
              <p className="text-sm font-medium">{file ? file.name : "Add an attachment"}</p>
              <p className="mt-1 text-xs text-muted-foreground">
                {file ? `${(file.size / 1024).toFixed(1)} KB selected` : "Drag and drop a file here, or choose one from your device."}
              </p>
              <Button
                type="button"
                size="sm"
                variant="outline"
                className="mt-3 bg-background"
                disabled={mutationsDisabled || uploadMut.isPending}
                onClick={() => fileInputRef.current?.click()}
              >
                {file ? "Choose a different file" : "Choose file"}
              </Button>
            </div>
            <div className="flex justify-end">
              <Button onClick={() => uploadMut.mutate()} disabled={mutationsDisabled || !file || uploadMut.isPending}>
                <Upload className="mr-1.5 h-4 w-4" aria-hidden="true" />
                {uploadMut.isPending ? "Uploading..." : "Upload attachment"}
              </Button>
            </div>
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
        {!canEdit && <div className="text-xs text-muted-foreground">Attachments cannot be changed for this document.</div>}
      </CardContent>
    </Card>
  );
}
