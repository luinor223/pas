import type { PageResponse } from "@/shared/api/paging";

export type StatementStatus =
  | "DRAFT"
  | "CALCULATED"
  | "RECONCILED"
  | "SUBMITTED"
  | "APPROVED"
  | "SIGNING"
  | "SIGNED"
  | "ISSUED"
  | "REJECTED"
  | "REVISION"
  | "CANCELLED";

export type VolumeLinkResponse = {
  id: string;
  volumeRecordId: string;
  recordNo: string;
  quantity: number;
};

export type StatementLineResponse = {
  id: string;
  lineNo: number;
  serviceCode: string;
  serviceName: string;
  unit: string;
  unitPrice: number;
  quantity: number;
  amount: number;
  source: string;
  note: string | null;
  volumeLinks: VolumeLinkResponse[];
};

export type StatementResponse = {
  id: string;
  statementNo: string;
  contractId: string;
  contractNo: string;
  customerId: string | null;
  customerName: string | null;
  periodCode: string;
  periodStart: string;
  periodEnd: string;
  priceListNo: string | null;
  priceListVersionNo: number | null;
  paymentTerm: string | null;
  vatRate: number | null;
  subtotal: number;
  taxAmount: number;
  totalAmount: number;
  currency: string | null;
  status: StatementStatus;
  adjustsStatementId: string | null;
  reconciledAt: string | null;
  issuedAt: string | null;
  dueDate: string | null;
  version: number;
  lines: StatementLineResponse[];
};

export type StatementPageResponse = PageResponse<StatementResponse>;

export type WorkflowProgressResponse = {
  id: string;
  workflowInstance: unknown;
};

export type CalculateStatementRequest = {
  contractId: string;
  periodCode: string;
};

export type AddLineRequest = {
  serviceCode: string;
  serviceName?: string | null;
  unit?: string | null;
  unitPrice: number;
  quantity: number;
  note?: string | null;
  version: number;
};

export type EditLineRequest = {
  lineNo: number;
  unitPrice: number;
  quantity: number;
  note?: string | null;
  version: number;
};

export type AdjustmentLineInput = {
  serviceCode: string;
  serviceName?: string | null;
  unit?: string | null;
  unitPrice: number;
  quantity: number;
  note?: string | null;
};

export type AdjustmentRequest = {
  reason?: string | null;
  lines: AdjustmentLineInput[];
};
