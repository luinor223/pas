import { useIsMutating, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { addendumQuery, attachmentsQuery } from "../hooks/contractQueries";
import { contractApi } from "../services/contractApi";
import { AttachmentPanel } from "./AttachmentPanel";
import { useCurrentUser } from "@/features/auth/hooks/useCurrentUser";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { Badge } from "@/shared/components/badge";
import { Button } from "@/shared/components/button";
import { StatusBadge } from "@/shared/components/status-badge";
import { DetailBackButton } from "@/shared/components/detail-back-link";
import { getApiErrorMessage } from "@/shared/api/errors";
import { formatDate } from "@/shared/lib/format";
import { humanize } from "@/shared/lib/text";

function responseStatus(error: unknown): number | undefined {
  return (error as { response?: { status?: number } })?.response?.status;
}

export function AddendumDetail({ id }: { id: string }) {
  const queryClient = useQueryClient();
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
  const hasAttachments = (attachmentsQ.data?.length ?? 0) > 0;

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
              <h2 className="text-xl font-bold">{addendum.addendumNo}</h2>
              <StatusBadge status={addendum.status} />
            </div>
            <div className="text-sm text-muted-foreground">
              {humanize(addendum.changeType)} · Contract {addendum.contractNo}
            </div>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {canSubmit && (
            <Button
              size="sm"
              disabled={attachmentsQ.isLoading || attachmentMutations > 0 || !hasAttachments || submitMut.isPending}
              onClick={() => submitMut.mutate()}
            >
              {submitMut.isPending ? "Submitting..." : "Submit for approval"}
            </Button>
          )}
          {!canWrite && <Badge variant="secondary">Read-only access</Badge>}
        </div>
      </div>

      {canSubmit && !attachmentsQ.isLoading && !hasAttachments && !attachmentsQ.isError && (
        <div role="status" className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
          Upload at least one attachment before submitting this addendum for approval.
        </div>
      )}
      {submitMut.isError && (
        <div role="alert" className="text-sm text-destructive">
          {getApiErrorMessage(submitMut.error, "Failed to submit addendum")}
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
              <div><div className="text-xs text-muted-foreground">CHANGE TYPE</div><div>{humanize(addendum.changeType)}</div></div>
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
            mutationsDisabled={submitMut.isPending}
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

        <Card className="h-fit">
          <CardHeader><CardTitle className="text-base">Record status</CardTitle></CardHeader>
          <CardContent className="space-y-3 text-sm">
            <div><div className="text-xs text-muted-foreground">CURRENT STATUS</div><div className="mt-1"><StatusBadge status={addendum.status} /></div></div>
            <div><div className="text-xs text-muted-foreground">RECORD VERSION</div><div>{addendum.version}</div></div>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
