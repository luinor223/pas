export type PeriodStatus = "OPEN" | "LOCKED";

export type PeriodResponse = {
  id: string;
  periodCode: string;
  startDate: string;
  endDate: string;
  status: PeriodStatus;
  volumeCount: number;
  lockedBy: string | null;
  lockedByName: string | null;
  lockedAt: string | null;
  createdAt: string;
  updatedAt: string;
};

export type VolumeResponse = {
  id: string;
  recordNo: string;
  periodCode: string;
  contractId: string;
  contractNo: string | null;
  customerName: string;
  serviceCode: string;
  serviceName: string;
  unit: string;
  quantity: number;
  note: string | null;
  createdAt: string;
  createdBy: string | null;
  updatedAt: string;
};

export type CreateVolumeRequest = {
  contractId: string;
  periodCode: string;
  serviceCode: string;
  quantity: number;
  note: string | null;
};

export type UpdateVolumeRequest = {
  quantity: number;
  note: string | null;
};

export type VolumeFilters = {
  periodCode?: string;
  contractId?: string;
  serviceCode?: string;
  q?: string;
  page?: number;
  size?: number;
};

import type { BodyPageResponse } from "@/shared/api/paging";

export type VolumePageResponse = BodyPageResponse<VolumeResponse>;
