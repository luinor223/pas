import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { signingRequestStateQuery, signingSessionsQuery } from "../hooks/contractQueries";
import { contractApi } from "../services/contractApi";
import { useCurrentUser } from "@/features/auth/hooks/useCurrentUser";
import { getApiErrorMessage } from "@/shared/api/errors";
import { Button } from "@/shared/components/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { StatusBadge } from "@/shared/components/status-badge";
import { formatDateTime } from "@/shared/lib/format";

type DocumentType = "CONTRACT" | "ADDENDUM";

export function DocumentSigningPanel({ documentType, documentId, documentStatus }: {
  documentType: DocumentType;
  documentId: string;
  documentStatus: string;
}) {
  const queryClient = useQueryClient();
  const userQ = useCurrentUser();
  const permissions = userQ.data?.permissions ?? [];
  const readPermission = documentType === "CONTRACT" ? "contract:read" : "addendum:read";
  const canView = permissions.includes(readPermission) || permissions.includes("esign:send");
  const canSend = permissions.includes("esign:send");
  const requestStateQ = useQuery({
    ...signingRequestStateQuery(documentType, documentId),
    enabled: userQ.isSuccess && canView,
    refetchInterval: documentStatus === "APPROVED" ? 5_000 : false,
  });
  const sessionsQ = useQuery({
    ...signingSessionsQuery(documentType, documentId),
    enabled: userQ.isSuccess && canView,
    refetchInterval: (query) => {
      const status = query.state.data?.[0]?.status;
      const currentSessionId = query.state.data?.[0]?.id ?? null;
      const expectedSessionId = requestStateQ.data?.sessionId ?? null;
      const waitingForSession = requestStateQ.data?.requestQueued
        || (expectedSessionId != null && currentSessionId !== expectedSessionId);
      return waitingForSession || status === "PENDING_SEND" || status === "SIGNING" ? 5_000 : false;
    },
  });
  const sessions = sessionsQ.data ?? [];
  const latest = sessions[0];
  const active = latest?.status === "PENDING_SEND" || latest?.status === "SIGNING";
  const waitingForSession = requestStateQ.data?.requestQueued === true
    || (requestStateQ.data?.sessionId != null && latest?.id !== requestStateQ.data.sessionId);
  const sendMut = useMutation({
    mutationFn: () => documentType === "CONTRACT"
      ? contractApi.sendForSigningContract(documentId)
      : contractApi.sendForSigningAddendum(documentId),
    onMutate: () => queryClient.cancelQueries({
      queryKey: ["signing-request-state", documentType, documentId], exact: true,
    }),
    onSuccess: async (state) => {
      await queryClient.cancelQueries({
        queryKey: ["signing-request-state", documentType, documentId], exact: true,
      });
      queryClient.setQueryData(["signing-request-state", documentType, documentId], state);
      await queryClient.invalidateQueries({ queryKey: ["signing-sessions", documentType, documentId] });
    },
  });

  if (userQ.isLoading || !canView) return null;

  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between gap-2">
        <CardTitle className="text-base">E-signature</CardTitle>
        {documentStatus === "APPROVED" && canSend && requestStateQ.data?.canSendForSigning && !active && !waitingForSession && (
          <Button size="sm" onClick={() => sendMut.mutate()} disabled={sendMut.isPending}>
            {sendMut.isPending ? "Queueing..." : "Send for signing"}
          </Button>
        )}
      </CardHeader>
      <CardContent className="space-y-3 text-sm">
        {sessionsQ.isLoading || requestStateQ.isLoading ? (
          <div className="text-muted-foreground">Loading signing status...</div>
        ) : sessionsQ.isError && !sessionsQ.data ? (
          <div className="text-destructive">{getApiErrorMessage(sessionsQ.error, "Signing status could not be loaded")}</div>
        ) : waitingForSession ? (
          <div role="status" className="rounded border border-blue-200 bg-blue-50 p-2 text-blue-800">
            Signature request queued. Signing status will appear shortly.
          </div>
        ) : latest ? (
          <>
            <div className="flex items-center justify-between gap-2">
              <span className="font-medium">{latest.sessionNo}</span>
              <StatusBadge status={latest.status} />
            </div>
            <div><span className="text-muted-foreground">Signer:</span> {latest.signerName} · {latest.signerEmail}</div>
            <div><span className="text-muted-foreground">Requested:</span> {formatDateTime(latest.createdAt)}{latest.requestedByName ? ` by ${latest.requestedByName}` : ""}</div>
            {latest.sentAt && <div><span className="text-muted-foreground">Sent:</span> {formatDateTime(latest.sentAt)}</div>}
            {latest.completedAt && <div><span className="text-muted-foreground">Completed:</span> {formatDateTime(latest.completedAt)}</div>}
            {latest.status === "FAILED" && <div role="alert" className="text-destructive">The signing request failed. Please retry or contact support.</div>}
          </>
        ) : (
          <div className="text-muted-foreground">No signature request yet.</div>
        )}
        {requestStateQ.isError && <div role="alert" className="text-destructive">Signing request state could not be loaded.</div>}
        {sendMut.isError && <div role="alert" className="text-destructive">{getApiErrorMessage(sendMut.error, "Failed to send for signing")}</div>}
      </CardContent>
    </Card>
  );
}
