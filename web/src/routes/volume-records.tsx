import { createFileRoute } from "@tanstack/react-router";
import { VolumeRecordsRoute } from "@/features/operations/components/VolumeRecordsRoute";

export const Route = createFileRoute("/volume-records")({
  validateSearch: (search: Record<string, unknown>) => ({
    tab: search.tab === "periods" ? "periods" as const : undefined,
    q: typeof search.q === "string" ? search.q : undefined,
    periodCode: typeof search.periodCode === "string" ? search.periodCode : undefined,
    contractId: typeof search.contractId === "string" ? search.contractId : undefined,
    serviceCode: typeof search.serviceCode === "string" ? search.serviceCode : undefined,
    page: typeof search.page === "number" && Number.isInteger(search.page) && search.page > 0 ? search.page : undefined,
  }),
  component: VolumeRecordsRoute,
});
