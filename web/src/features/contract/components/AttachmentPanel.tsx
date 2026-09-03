import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { attachmentsQuery } from "../hooks/contractQueries";
import { contractApi } from "../services/contractApi";
import { Button } from "@/shared/components/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { getApiErrorMessage } from "@/shared/api/errors";
import { useState } from "react";
import { useHasPermission } from "@/features/auth/hooks/usePermissions";

export function AttachmentPanel({ ownerType, ownerId }: { ownerType: "CONTRACT" | "ADDENDUM"; ownerId: string }) {
  const qc = useQueryClient();
  const canWrite = useHasPermission(ownerType === "CONTRACT" ? "contract:write" : "addendum:write");
  const q = useQuery(attachmentsQuery(ownerType, ownerId));
  const [file, setFile] = useState<File | null>(null);

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
    onSuccess: () => qc.invalidateQueries({ queryKey: ["attachments", ownerType, ownerId] }),
  });

  return (
    <Card>
      <CardHeader><CardTitle className="text-base">Attachments</CardTitle></CardHeader>
      <CardContent className="space-y-3">
        {q.isLoading ? <div className="text-sm text-muted-foreground">Loading...</div> : q.isError ? <div className="text-sm text-destructive">{getApiErrorMessage(q.error, "Failed")}</div> : q.data?.length === 0 ? <div className="text-sm text-muted-foreground">No attachments. {canWrite ? "Upload one to enable submit (CTR-02)." : ""}</div> : (
          <div className="space-y-1">
            {q.data?.map((a) => (
              <div key={a.id} className="flex items-center justify-between border rounded p-2 text-sm">
                <div>
                  <div className="font-medium">{a.fileName}</div>
                  <div className="text-xs text-muted-foreground">{a.contentType} · {(a.sizeBytes / 1024).toFixed(1)} KB · {new Date(a.uploadedAt).toLocaleString()}</div>
                </div>
                <div className="flex gap-1">
                  <Button size="sm" variant="outline" onClick={() => window.open(contractApi.downloadAttachmentUrl(a.id), "_blank")}>Download</Button>
                  {canWrite && <Button size="sm" variant="destructive" onClick={() => deleteMut.mutate(a.id)} disabled={deleteMut.isPending}>Delete</Button>}
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
        {!canWrite && <div className="text-xs text-muted-foreground">You need {ownerType === "CONTRACT" ? "contract:write" : "addendum:write"} to upload.</div>}
      </CardContent>
    </Card>
  );
}
