export type PriceListResponse = {
  id: string;
  priceListNo: string;
  customerId: string | null;
  contractId: string | null;
  serviceGroup: string | null;
  scopeKey: string;
  note: string | null;
};

export type PriceListPageResponse = import("@/shared/api/paging").PagedResult<PriceListResponse>;

export type PriceListVersionStatus =
  | "DRAFT"
  | "SUBMITTED"
  | "APPROVED"
  | "EFFECTIVE"
  | "SUPERSEDED"
  | "EXPIRED"
  | "REJECTED";

export type PriceListVersionResponse = {
  id: string;
  priceListId: string;
  versionNo: number;
  status: PriceListVersionStatus;
  validFrom: string;
  validTo: string;
  addendumId: string | null;
};

export type PriceLineResponse = {
  serviceCode: string;
  serviceName: string;
  unit: string;
  unitPrice: number;
};

export type PriceListVersionDetail = {
  version: PriceListVersionResponse;
  lines: PriceLineResponse[];
};

export type ServiceItemResponse = {
  code: string;
  name: string;
  unit: string;
  active: boolean;
};

export type CreatePriceListRequest = {
  customerId: string | null;
  contractId: string | null;
  serviceGroup: string | null;
  note: string | null;
};

export type CreatePriceListVersionRequest = {
  validFrom: string;
  validTo: string;
  addendumId: string | null;
};

export type PriceLineInput = {
  serviceCode: string;
  unitPrice: number;
};
