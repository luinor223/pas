import { createFileRoute } from "@tanstack/react-router";
import { ContractList } from "@/features/contract/components/ContractList";
import { ContractDetail } from "@/features/contract/components/ContractDetail";
import { DetailBackLink } from "@/shared/components/detail-back-link";

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
  if (id) {
    return (
      <div className="space-y-3">
        <DetailBackLink to="/contracts">Back to contracts</DetailBackLink>
        <ContractDetail key={id} id={id} initialTab={tab} />
      </div>
    );
  }
  return <ContractList />;
}
