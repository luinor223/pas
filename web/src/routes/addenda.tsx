import { createFileRoute } from "@tanstack/react-router";
import { AddendumList } from "@/features/contract/components/AddendumList";
import { AddendumDetail } from "@/features/contract/components/AddendumDetail";

export const Route = createFileRoute("/addenda")({
  validateSearch: (search: Record<string, unknown>) => ({
    id: typeof search.id === "string" ? search.id : undefined,
    contractId: search.contractId as string | undefined,
    changeType: search.changeType as string | undefined,
  }),
  component: AddendaPage,
});

function AddendaPage() {
  const { id } = Route.useSearch();
  if (id) return <AddendumDetail key={id} id={id} />;
  return <AddendumList />;
}
