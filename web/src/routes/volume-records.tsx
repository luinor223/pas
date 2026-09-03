import { createFileRoute } from "@tanstack/react-router";
import { VolumeRecordsRoute } from "@/features/operations/components/VolumeRecordsRoute";

export const Route = createFileRoute("/volume-records")({
  validateSearch: (search: Record<string, unknown>) => ({
    tab: search.tab === "periods" ? "periods" as const : undefined,
  }),
  component: VolumeRecordsRoute,
});
