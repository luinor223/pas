import { createFileRoute } from "@tanstack/react-router";
import { CustomerList } from "@/features/contract/components/CustomerList";
import { CustomerDetail } from "@/features/contract/components/CustomerDetail";

export const Route = createFileRoute("/customers")({
  validateSearch: (search: Record<string, unknown>) => ({ id: search.id as string | undefined }),
  component: () => {
    const { id } = Route.useSearch();
    if (id) {
      return (
        <div className="space-y-3">
          <a href="/customers" className="text-sm text-blue-600 hover:underline">← Back to customers</a>
          <CustomerDetail id={id} />
        </div>
      );
    }
    return <CustomerList />;
  },
});
