import { createFileRoute } from "@tanstack/react-router";
import { ContractList } from "@/features/contract/components/ContractList";
import { ContractDetail } from "@/features/contract/components/ContractDetail";

export const Route = createFileRoute("/contracts")({
  validateSearch: (search: Record<string, unknown>) => ({ id: search.id as string | undefined }),
  component: () => {
    const { id } = Route.useSearch();
    if (id) {
      return (
        <div className="space-y-3">
          <a href="/contracts" className="text-sm text-blue-600 hover:underline">← Back to contracts</a>
          <ContractDetail id={id} />
        </div>
      );
    }
    return <ContractList />;
  },
});
