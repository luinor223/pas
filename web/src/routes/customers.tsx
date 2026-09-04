import { createFileRoute } from "@tanstack/react-router";
import { CustomerList } from "@/features/contract/components/CustomerList";
import { CustomerDetail } from "@/features/contract/components/CustomerDetail";

export const Route = createFileRoute("/customers")({
  validateSearch: (search: Record<string, unknown>) => ({ id: search.id as string | undefined }),
  component: CustomersPage,
});

function CustomersPage() {
  const { id } = Route.useSearch();
  if (id) return <CustomerDetail id={id} />;
  return <CustomerList />;
}
