import type { PageResponse } from "@/shared/api/paging";

export type SessionStatus = "PENDING_SEND" | "SIGNING" | "SIGNED" | "FAILED" | "CANCELLED";

export type SigningSessionResponse = {
  id: string;
  sessionNo: string;
  documentTypeCode: string;
  documentId: string;
  documentNo: string;
  customerName: string | null;
  signerName: string | null;
  signerEmail: string | null;
  provider: string | null;
  providerRef: string | null;
  status: SessionStatus;
  attempts: number;
  lastError: string | null;
  requestedByName: string | null;
  sentAt: string | null;
  completedAt: string | null;
  createdAt: string | null;
};

export type SigningSessionPageResponse = PageResponse<SigningSessionResponse>;
