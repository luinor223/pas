import { createFileRoute } from "@tanstack/react-router";
import { ContractList } from "@/features/contract/components/ContractList";
import { ContractDetail } from "@/features/contract/components/ContractDetail";

export const Route = createFileRoute("/contracts")({
  validateSearch: (search: Record<string, unknown>) => ({
    id: search.id as string | undefined,
    tab: search.tab as string | undefined,
    customerId: search.customerId as string | undefined,
  }),
  component: () => {
    const { id, tab } = Route.useSearch();
    if (id) {
      return (
        <div className="space-y-3">
          <a href="/contracts" className="text-sm text-blue-600 hover:underline" onClick={(e) => { e.preventDefault(); window.history.back(); }}>← Back to contracts</a>
          <ContractDetail key={id} id={id} initialTab={tab} />
        </div>
      );
    }
    return <ContractList />;
  },
});
