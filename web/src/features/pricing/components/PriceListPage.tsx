import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useSearch } from "@tanstack/react-router";
import type { ColumnDef } from "@tanstack/react-table";
import { Plus } from "lucide-react";
import { Button } from "@/shared/components/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { ClearFiltersButton } from "@/shared/components/clear-filters-button";
import { DataTable } from "@/shared/components/data-table";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/shared/components/dialog";
import { FilterBar } from "@/shared/components/filter-bar";
import { Forbidden } from "@/shared/components/Forbidden";
import { Label } from "@/shared/components/label";
import { SearchInput } from "@/shared/components/search-input";
import { Select } from "@/shared/components/select";
import { Textarea } from "@/shared/components/textarea";
import { getApiErrorMessage } from "@/shared/api/errors";
import { DEFAULT_PAGE_SIZE } from "@/shared/api/paging";
import { useHasPermission } from "@/features/auth/hooks/usePermissions";
import { CustomerPicker } from "@/features/contract/components/CustomerPicker";
import { ContractPicker } from "@/features/contract/components/ContractPicker";
import { contractsQuery, customersQuery } from "@/features/contract/hooks/contractQueries";
import { SERVICE_GROUPS } from "@/features/contract/contractOptions";
import { humanize } from "@/shared/lib/text";
import { priceListQuery, priceListsQuery, priceListVersionByIdQuery } from "../hooks/pricingQueries";
import { pricingApi } from "../services/pricingApi";
import type { CreatePriceListRequest, PriceListResponse } from "../types/pricingTypes";
import { PriceListDetail } from "./PriceListDetail";
import { PriceListScope } from "./PriceListScope";

type ScopeType = "CONTRACT" | "CUSTOMER_GROUP" | "CUSTOMER" | "SERVICE_GROUP";

export function PriceListPage() {
  const canRead = useHasPermission("pricelist:read");
  const canWrite = useHasPermission("pricelist:write");
  const navigate = useNavigate({ from: "/price-lists" });
  const { id: selectedId, versionId: initialVersionId } = useSearch({ from: "/price-lists" });
  const [search, setSearch] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const [serviceGroup, setServiceGroup] = useState("");
  const [page, setPage] = useState(0);
  const [openCreate, setOpenCreate] = useState(false);
  useEffect(() => {
    const timer = setTimeout(() => setDebouncedSearch(search.trim()), 300);
    return () => clearTimeout(timer);
  }, [search]);
  const listsQuery = useQuery({ ...priceListsQuery({
    serviceGroup: serviceGroup || undefined,
    q: debouncedSearch || undefined,
    page,
    size: DEFAULT_PAGE_SIZE,
  }), enabled: canRead });
  const linkedVersionQuery = useQuery({ ...priceListVersionByIdQuery(initialVersionId ?? ""), enabled: canRead && !selectedId && Boolean(initialVersionId) });
  const effectiveSelectedId = selectedId ?? linkedVersionQuery.data?.version.priceListId ?? "";
  const selectedQuery = useQuery({ ...priceListQuery(effectiveSelectedId), enabled: canRead && Boolean(effectiveSelectedId) });
  const priceLists = listsQuery.data?.items ?? [];
  const needsContracts = priceLists.some((list) => Boolean(list.contractId));
  const needsCustomers = priceLists.some((list) => Boolean(list.customerId));
  const contracts = useQuery({ ...contractsQuery({ size: 100 }), enabled: canRead && needsContracts });
  const customers = useQuery({ ...customersQuery({ size: 100 }), enabled: canRead && needsCustomers });
  const contractsById = useMemo(() => new Map((contracts.data?.content ?? []).map((contract) => [contract.id, contract])), [contracts.data]);
  const customersById = useMemo(() => new Map((customers.data?.content ?? []).map((customer) => [customer.id, customer])), [customers.data]);

  const columns = useMemo<ColumnDef<PriceListResponse>[]>(() => [
    {
      accessorKey: "priceListNo",
      header: "PRICE LIST",
      cell: ({ row }) => (
        <button type="button" className="font-semibold text-primary hover:underline" onClick={() => navigate({ search: { id: row.original.id, versionId: undefined } })}>
          {row.original.priceListNo}
        </button>
      ),
    },
    { id: "scope", header: "APPLIES TO", cell: ({ row }) => <PriceListScope priceList={row.original} contract={contractsById.get(row.original.contractId ?? "")} customer={customersById.get(row.original.customerId ?? "")} /> },
    { accessorKey: "note", header: "NOTE", cell: ({ row }) => <span className="text-muted-foreground">{row.original.note || "—"}</span> },
    {
      id: "action",
      header: "ACTION",
      enableSorting: false,
      cell: ({ row }) => (
        <div className="text-right">
          <Button size="sm" variant="outline" onClick={() => navigate({ search: { id: row.original.id, versionId: undefined } })}>View versions</Button>
        </div>
      ),
    },
  ], [contractsById, customersById, navigate]);

  if (!canRead) {
    return <Forbidden message="You do not have access to price lists. An administrator can grant it." />;
  }

  const selected = priceLists.find((list) => list.id === effectiveSelectedId) ?? selectedQuery.data;
  if (selected) {
    return (
      <PriceListDetail
        priceList={selected}
        canWrite={canWrite}
        initialVersionId={initialVersionId ?? ""}
        onVersionChange={(versionId) => navigate({ search: { id: selected.id, versionId } })}
      />
    );
  }
  if (effectiveSelectedId && selectedQuery.isLoading) {
    return <Card><CardContent className="p-6 text-sm text-muted-foreground">Loading price list...</CardContent></Card>;
  }
  if (effectiveSelectedId && selectedQuery.isError) {
    return <Card><CardContent className="p-6 text-sm text-destructive">{getApiErrorMessage(selectedQuery.error, "Could not load this price list")}</CardContent></Card>;
  }

  return (
    <>
      <Card>
        <CardHeader className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <CardTitle>Price lists ({listsQuery.data?.totalItems ?? 0})</CardTitle>
            <p className="mt-1 text-sm text-muted-foreground">Manage where prices apply, their effective dates, and version history.</p>
          </div>
          {canWrite && <Button onClick={() => setOpenCreate(true)}><Plus size={16} className="mr-1.5" /> New price list</Button>}
        </CardHeader>
        <CardContent className="space-y-4">
          <FilterBar>
            <SearchInput
              className="w-full sm:w-72"
              label="Search price lists"
              placeholder="Search number, note, or group"
              value={search}
              onChange={(value) => { setSearch(value); setPage(0); }}
            />
            <Select className="w-full sm:w-52" aria-label="Filter by service group" value={serviceGroup} onChange={(event) => { setServiceGroup(event.target.value); setPage(0); }}>
              <option value="">Service group: All</option>
              {SERVICE_GROUPS.map((group) => <option key={group} value={group}>{humanize(group)}</option>)}
            </Select>
            <ClearFiltersButton className="ml-auto" disabled={!search && !serviceGroup} onClick={() => { setSearch(""); setServiceGroup(""); setPage(0); }} />
          </FilterBar>

          {listsQuery.isLoading ? (
            <p className="py-8 text-center text-sm text-muted-foreground">Loading price lists...</p>
          ) : listsQuery.isError ? (
            <p role="alert" className="text-sm text-destructive">{getApiErrorMessage(listsQuery.error, "Could not load price lists")}</p>
          ) : (
            <DataTable
              columns={columns}
              data={priceLists}
              pageSize={DEFAULT_PAGE_SIZE}
              emptyMessage={search || serviceGroup ? "No price lists match your filters" : "No price lists yet"}
              serverPagination={{
                page,
                totalPages: listsQuery.data?.totalPages ?? 0,
                totalItems: listsQuery.data?.totalItems ?? 0,
                onPageChange: setPage,
              }}
            />
          )}
        </CardContent>
      </Card>

      <CreatePriceListDialog open={openCreate} onClose={() => setOpenCreate(false)} onCreated={(list) => { setOpenCreate(false); navigate({ search: { id: list.id, versionId: undefined } }); }} />
    </>
  );
}

function CreatePriceListDialog({
  open, onClose, onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: (priceList: PriceListResponse) => void;
}) {
  const queryClient = useQueryClient();
  const [scopeType, setScopeType] = useState<ScopeType>("CONTRACT");
  const [customerId, setCustomerId] = useState("");
  const [contractId, setContractId] = useState("");
  const [serviceGroup, setServiceGroup] = useState("");
  const [note, setNote] = useState("");
  const request = createRequest(scopeType, customerId, contractId, serviceGroup, note);
  const missingScope = scopeType === "CONTRACT" ? !contractId
    : scopeType === "CUSTOMER" ? !customerId
      : scopeType === "SERVICE_GROUP" ? !serviceGroup
        : !customerId || !serviceGroup;

  const createMutation = useMutation({
    mutationFn: (payload: CreatePriceListRequest) => pricingApi.createPriceList(payload),
    onSuccess: (priceList) => {
      queryClient.invalidateQueries({ queryKey: ["price-lists"] });
      onCreated(priceList);
    },
  });

  function changeScope(next: ScopeType) {
    createMutation.reset();
    setScopeType(next);
    setCustomerId("");
    setContractId("");
    setServiceGroup("");
  }

  return (
    <Dialog open={open} onOpenChange={(next) => { if (!next && !createMutation.isPending) onClose(); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Create price list</DialogTitle>
          <p className="text-sm text-muted-foreground">Choose exactly where these prices should apply. This scope cannot be changed after versions are created.</p>
        </DialogHeader>
        <div className="space-y-4">
          <div>
            <Label>Applies to</Label>
            <Select value={scopeType} onChange={(event) => changeScope(event.target.value as ScopeType)}>
              <option value="CONTRACT">One contract</option>
              <option value="CUSTOMER_GROUP">One customer and service group</option>
              <option value="CUSTOMER">All services for one customer</option>
              <option value="SERVICE_GROUP">All customers in one service group</option>
            </Select>
          </div>

          {scopeType === "CONTRACT" && (
            <ContractPicker
              label="Approved or active contract *"
              value={contractId}
              onChange={setContractId}
              placeholder="Search approved or active contracts..."
              statuses={["APPROVED", "ACTIVE"]}
              allowClear={false}
            />
          )}

          {(scopeType === "CUSTOMER" || scopeType === "CUSTOMER_GROUP") && (
            <CustomerPicker label="Customer *" value={customerId} onChange={setCustomerId} />
          )}

          {(scopeType === "SERVICE_GROUP" || scopeType === "CUSTOMER_GROUP") && (
            <div>
              <Label>Service group *</Label>
              <Select value={serviceGroup} onChange={(event) => setServiceGroup(event.target.value)}>
                <option value="">Select a service group</option>
                {SERVICE_GROUPS.map((group) => <option key={group} value={group}>{humanize(group)}</option>)}
              </Select>
            </div>
          )}

          <div>
            <Label>Note <span className="font-normal text-muted-foreground">(optional)</span></Label>
            <Textarea rows={3} value={note} onChange={(event) => setNote(event.target.value)} placeholder="Describe when or why this price list is used..." />
          </div>
          {createMutation.isError && <p role="alert" className="text-sm text-destructive">{getApiErrorMessage(createMutation.error, "Could not create the price list")}</p>}
        </div>
        <DialogFooter>
          <Button variant="outline" disabled={createMutation.isPending} onClick={onClose}>Cancel</Button>
          <Button disabled={missingScope || createMutation.isPending} onClick={() => createMutation.mutate(request)}>
            {createMutation.isPending ? "Creating..." : "Create price list"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function createRequest(scopeType: ScopeType, customerId: string, contractId: string, serviceGroup: string, note: string): CreatePriceListRequest {
  return {
    customerId: scopeType === "CUSTOMER" || scopeType === "CUSTOMER_GROUP" ? customerId || null : null,
    contractId: scopeType === "CONTRACT" ? contractId || null : null,
    serviceGroup: scopeType === "SERVICE_GROUP" || scopeType === "CUSTOMER_GROUP" ? serviceGroup || null : null,
    note: note.trim() || null,
  };
}
