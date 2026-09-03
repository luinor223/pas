import { createFileRoute } from "@tanstack/react-router";
import { PriceListPage } from "@/features/pricing";

export const Route = createFileRoute("/price-lists")({
  validateSearch: (search: Record<string, unknown>) => ({
    id: typeof search.id === "string" ? search.id : undefined,
    versionId: typeof search.versionId === "string" ? search.versionId : undefined,
  }),
  component: PriceListPage,
});
