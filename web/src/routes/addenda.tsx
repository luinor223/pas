import { createFileRoute } from "@tanstack/react-router";
import { AddendumList } from "@/features/contract/components/AddendumList";

export const Route = createFileRoute("/addenda")({
  validateSearch: (search: Record<string, unknown>) => ({
    contractId: search.contractId as string | undefined,
    changeType: search.changeType as string | undefined,
  }),
  component: AddendumList,
});
