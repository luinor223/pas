import { createFileRoute } from "@tanstack/react-router";
import { AddendumList } from "@/features/contract/components/AddendumList";
import { AddendumDetail } from "@/features/contract/components/AddendumDetail";
import {
  ADDENDUM_STATUSES, optionalCursor, optionalEnum, optionalPage, optionalText,
  optionalUuid, type AddendumRouteSearch,
} from "@/features/contract/contractSearchParams";
import { ADDENDUM_CHANGE_TYPES } from "@/features/contract/contractOptions";

export const Route = createFileRoute("/addenda")({
  validateSearch: (search: Record<string, unknown>): AddendumRouteSearch => ({
    id: optionalUuid(search.id),
    contractId: optionalUuid(search.contractId),
    changeType: optionalEnum(search.changeType, ADDENDUM_CHANGE_TYPES),
    status: optionalEnum(search.status, ADDENDUM_STATUSES),
    q: optionalText(search.q),
    page: optionalPage(search.page),
    cursor: optionalCursor(search.cursor),
  }),
  component: AddendaPage,
});

function AddendaPage() {
  const search = Route.useSearch();
  if (search.id) return <AddendumDetail key={search.id} id={search.id} />;
  return <AddendumList search={search} />;
}
