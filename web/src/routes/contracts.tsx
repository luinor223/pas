import { createFileRoute } from "@tanstack/react-router";
import { ContractList } from "@/features/contract/components/ContractList";
import { ContractDetail } from "@/features/contract/components/ContractDetail";
import {
  CONTRACT_STATUSES, CONTRACT_TABS, optionalCursor, optionalDate, optionalEnum,
  optionalPage, optionalText, optionalUuid, type ContractRouteSearch,
} from "@/features/contract/contractSearchParams";
import { SERVICE_GROUPS } from "@/features/contract/contractOptions";

export const Route = createFileRoute("/contracts")({
  validateSearch: (search: Record<string, unknown>): ContractRouteSearch => {
    const id = optionalUuid(search.id);
    const tab = id ? optionalEnum(search.tab, CONTRACT_TABS) : undefined;
    return {
      id,
      tab,
      customerId: optionalUuid(search.customerId),
      q: optionalText(search.q),
      status: optionalEnum(search.status, CONTRACT_STATUSES),
      serviceGroup: optionalEnum(search.serviceGroup, SERVICE_GROUPS),
      validFromFrom: optionalDate(search.validFromFrom),
      validToTo: optionalDate(search.validToTo),
      page: optionalPage(search.page),
      cursor: optionalCursor(search.cursor),
      relatedPage: tab === "addenda" ? optionalPage(search.relatedPage) : undefined,
      relatedCursor: tab === "addenda" ? optionalCursor(search.relatedCursor) : undefined,
    };
  },
  component: ContractsPage,
});

function ContractsPage() {
  const search = Route.useSearch();
  if (search.id) return <ContractDetail key={search.id} id={search.id} tab={search.tab} relatedPage={search.relatedPage} relatedCursor={search.relatedCursor} />;
  return <ContractList search={search} />;
}
