import { createFileRoute } from "@tanstack/react-router";
import { CustomerList } from "@/features/contract/components/CustomerList";
import { CustomerDetail } from "@/features/contract/components/CustomerDetail";
import { DetailBackLink } from "@/shared/components/detail-back-link";

export const Route = createFileRoute("/customers")({
  validateSearch: (search: Record<string, unknown>) => ({ id: search.id as string | undefined }),
  component: CustomersPage,
});

function CustomersPage() {
  const { id } = Route.useSearch();
  if (id) {
    return (
      <div className="space-y-3">
        <DetailBackLink to="/customers">Back to customers</DetailBackLink>
        <CustomerDetail id={id} />
      </div>
    );
  }
  return <CustomerList />;
}
