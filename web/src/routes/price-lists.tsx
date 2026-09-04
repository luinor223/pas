import { createFileRoute } from "@tanstack/react-router";
import { PriceListPage } from "@/features/pricing";

export const Route = createFileRoute("/price-lists")({
  validateSearch: (search: Record<string, unknown>) => ({
    id: typeof search.id === "string" ? search.id : undefined,
    versionId: typeof search.versionId === "string" ? search.versionId : undefined,
    q: typeof search.q === "string" ? search.q : undefined,
    serviceGroup: typeof search.serviceGroup === "string" ? search.serviceGroup : undefined,
    page: typeof search.page === "number" && Number.isInteger(search.page) && search.page > 0 ? search.page : undefined,
  }),
  component: PriceListPage,
});
