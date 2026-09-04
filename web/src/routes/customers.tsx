import { createFileRoute } from "@tanstack/react-router";
import { CustomerList } from "@/features/contract/components/CustomerList";
import { CustomerDetail } from "@/features/contract/components/CustomerDetail";
import {
  CUSTOMER_STATUSES, CUSTOMER_TABS, optionalCursor, optionalEnum, optionalPage,
  optionalText, optionalUuid, type CustomerRouteSearch,
} from "@/features/contract/contractSearchParams";

export const Route = createFileRoute("/customers")({
  validateSearch: (search: Record<string, unknown>): CustomerRouteSearch => {
    const id = optionalUuid(search.id);
    const tab = id ? optionalEnum(search.tab, CUSTOMER_TABS) : undefined;
    return {
      id,
      tab,
      q: optionalText(search.q),
      status: optionalEnum(search.status, CUSTOMER_STATUSES),
      page: optionalPage(search.page),
      cursor: optionalCursor(search.cursor),
      contractsPage: tab === "contracts" ? optionalPage(search.contractsPage) : undefined,
      contractsCursor: tab === "contracts" ? optionalCursor(search.contractsCursor) : undefined,
    };
  },
  component: CustomersPage,
});

function CustomersPage() {
  const search = Route.useSearch();
  if (search.id) return <CustomerDetail key={search.id} id={search.id} tab={search.tab} contractsPage={search.contractsPage} contractsCursor={search.contractsCursor} />;
  return <CustomerList search={search} />;
}
