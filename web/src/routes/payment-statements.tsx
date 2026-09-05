import { createFileRoute } from "@tanstack/react-router";
import { PaymentStatementsPage, PaymentStatementDetail } from "@/features/billing";

export const Route = createFileRoute("/payment-statements")({
  validateSearch: (search: Record<string, unknown>) => ({
    id: typeof search.id === "string" ? search.id : undefined,
  }),
  component: PaymentStatementsRoute,
});

function PaymentStatementsRoute() {
  const { id } = Route.useSearch();
  if (id) return <PaymentStatementDetail key={id} id={id} />;
  return <PaymentStatementsPage />;
}
