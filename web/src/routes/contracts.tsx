import { createFileRoute } from "@tanstack/react-router";
import { ContractList } from "@/features/contract/components/ContractList";
import { ContractDetail } from "@/features/contract/components/ContractDetail";

export const Route = createFileRoute("/contracts")({
  validateSearch: (search: Record<string, unknown>) => ({
    id: search.id as string | undefined,
    tab: search.tab as string | undefined,
    customerId: search.customerId as string | undefined,
  }),
  component: ContractsPage,
});

function ContractsPage() {
  const { id, tab } = Route.useSearch();
  if (id) return <ContractDetail key={id} id={id} initialTab={tab} />;
  return <ContractList />;
}
