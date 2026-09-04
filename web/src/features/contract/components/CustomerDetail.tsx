import { useQuery, useQueryClient } from "@tanstack/react-query";
import { customerMetricsQuery, customerQuery, contractsQuery } from "../hooks/contractQueries";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { StatusBadge } from "@/shared/components/status-badge";
import { Button } from "@/shared/components/button";
import { ContactTable } from "./ContactTable";
import { DataTable } from "@/shared/components/data-table";
import type { ColumnDef } from "@tanstack/react-table";
import type { ContractResponse } from "../types/contractTypes";
import { useCallback, useEffect } from "react";
import { Link, useNavigate } from "@tanstack/react-router";
import { formatDate, formatDecimalMoney, formatMoney } from "@/shared/lib/format";
import { DEFAULT_PAGE_SIZE } from "@/shared/api/paging";
import { TabBar, type TabItem } from "@/shared/components/tab-bar";
import { useHasPermission } from "@/features/auth/hooks/usePermissions";
import { useCurrentUser } from "@/features/auth/hooks/useCurrentUser";
import { useRecoverOutOfRangePage } from "@/shared/hooks/use-recover-out-of-range-page";

type Tab = "overview" | "contracts" | "contacts";

const TABS: readonly TabItem<Tab>[] = [
  { value: "overview", label: "Overview" },
  { value: "contracts", label: "Contracts" },
  { value: "contacts", label: "Contacts" },
];

export function CustomerDetail({ id, tab: requestedTab, contractsPage = 0, contractsCursor }: {
  id: string; tab?: Tab; contractsPage?: number; contractsCursor?: string;
}) {
  const queryClient = useQueryClient();
  const navigate = useNavigate({ from: "/customers" });
  const currentUserQ = useCurrentUser();
  const canReadContracts = useHasPermission("contract:read");
  const tab: Tab = requestedTab === "contracts" && !canReadContracts ? "overview" : requestedTab ?? "overview";
  useEffect(() => {
    if (currentUserQ.isSuccess && requestedTab === "contracts" && !canReadContracts) {
      navigate({
        to: "/customers",
        search: (previous) => ({ ...previous, tab: undefined, contractsPage: undefined, contractsCursor: undefined }),
        replace: true,
      });
    }
  }, [canReadContracts, currentUserQ.isSuccess, navigate, requestedTab]);
  const q = useQuery(customerQuery(id));
  const metricsQ = useQuery({ ...customerMetricsQuery(id), enabled: canReadContracts && !!id });
  const recentContractsQ = useQuery({
    ...contractsQuery({ customerId: id, page: 0, size: 5, sort: "createdAt,desc" }),
    enabled: canReadContracts && !!id,
  });
  const contractsQ = useQuery({
    ...contractsQuery({ customerId: id, page: contractsPage, size: DEFAULT_PAGE_SIZE, sort: "createdAt,desc", cursor: contractsCursor }),
    enabled: canReadContracts && tab === "contracts",
  });
  const tabs = canReadContracts ? TABS : TABS.filter((item) => item.value !== "contracts");
  const setTab = (next: Tab) => navigate({
    to: "/customers",
    search: (previous) => ({
      ...previous,
      tab: next === "overview" ? undefined : next,
      contractsPage: next === "contracts" ? previous.contractsPage : undefined,
      contractsCursor: next === "contracts" ? previous.contractsCursor : undefined,
    }),
  });
  const changeContractsPage = (nextPage: number) => {
    navigate({
      to: "/customers",
      search: (previous) => ({
        ...previous,
        contractsPage: nextPage || undefined,
        contractsCursor: previous.contractsCursor ?? contractsQ.data?.cursor,
      }),
    });
  };
  const recoverContracts = useCallback(() => {
    queryClient.removeQueries({ queryKey: ["contracts"] });
    navigate({
      to: "/customers",
      search: (previous) => ({ ...previous, contractsPage: undefined, contractsCursor: undefined }),
      replace: true,
    });
  }, [navigate, queryClient]);
  useRecoverOutOfRangePage({
    ready: contractsQ.isSuccess && tab === "contracts",
    page: contractsPage,
    totalPages: contractsQ.data?.totalPages ?? 0,
    totalItems: contractsQ.data?.totalElements ?? 0,
    recover: recoverContracts,
  });

  const c = q.data;
  if (q.isLoading) return <div className="text-sm text-muted-foreground">Loading...</div>;
  if (q.isError) return <div className="text-sm text-destructive">Failed to load customer</div>;
  if (!c) return null;

  const contracts = contractsQ.data?.content ?? [];
  const recentContracts = recentContractsQ.data?.content ?? [];

  const recentColumns: ColumnDef<ContractResponse>[] = [
    {
      accessorKey: "contractNo", header: "CONTRACT NO.",
      cell: ({ row }) => <Link to="/contracts" search={{ id: row.original.id } as never} className="text-blue-600 hover:underline">{row.original.contractNo}</Link>,
    },
    { accessorKey: "serviceGroup", header: "SERVICE GROUP", cell: ({ row }) => <span className="capitalize">{row.original.serviceGroup.toLowerCase().replace(/_/g, " ")}</span> },
    { accessorKey: "value", header: "VALUE", cell: ({ row }) => <span className="tabular-nums">{formatMoney(row.original.value, row.original.currency)}</span> },
    { accessorKey: "validFrom", header: "EFFECTIVE", cell: ({ row }) => <span className="text-xs">{formatDate(row.original.validFrom)}</span> },
    { accessorKey: "status", header: "STATUS", cell: ({ row }) => <StatusBadge status={row.original.status} /> },
  ];

  return (
    <div>
      <TabBar
        id="customer-detail-tabs"
        panelId="customer-detail-panel"
        tabs={tabs}
        value={tab}
        onChange={setTab}
        className="-mx-4 -mt-4 px-4 sm:-mx-6 sm:-mt-6 sm:px-6"
      />
      <h1 className="sr-only">{c.name}</h1>

      <div id="customer-detail-panel" role="tabpanel" aria-labelledby={`customer-detail-tabs-tab-${tab}`} tabIndex={0} className="pt-5">
      <h2 className="sr-only">{tabs.find((item) => item.value === tab)?.label}</h2>
      {tab === "overview" && (
        <div className="space-y-4">
          {canReadContracts && <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Card><CardContent className="flex min-h-28 flex-col justify-center p-5 sm:p-6"><div className="text-sm text-muted-foreground">Active contracts</div><div className="mt-1 text-2xl font-bold">{metricsQ.isLoading ? "…" : metricsQ.isError ? "Unavailable" : metricsQ.data?.activeContracts ?? 0}</div></CardContent></Card>
            <Card>
              <CardContent className="flex min-h-28 flex-col justify-center p-5 sm:p-6">
                <div className="text-sm text-muted-foreground">Approved contract value</div>
                {metricsQ.isLoading ? (
                  <div className="mt-1 text-2xl font-bold">…</div>
                ) : metricsQ.isError ? (
                  <div className="mt-1 text-sm text-destructive">Failed to load contract metrics</div>
                ) : metricsQ.data?.approvedContractValues.length ? (
                  <div className="mt-1 space-y-1">
                    {metricsQ.data.approvedContractValues.map((total) => (
                      <div key={total.currency} className="text-lg font-semibold tabular-nums sm:text-xl">{formatDecimalMoney(total.value, total.currency)}</div>
                    ))}
                  </div>
                ) : (
                  <div className="mt-1 text-sm text-muted-foreground">No approved contract value</div>
                )}
                <div className="mt-1 text-xs text-muted-foreground">Includes approved and active contracts</div>
              </CardContent>
            </Card>
          </div>}

          <div className="grid grid-cols-1 gap-4 lg:grid-cols-[2fr_1fr]">
            <Card>
              <CardHeader className="pb-5"><CardTitle>Company information</CardTitle></CardHeader>
              <CardContent className="grid grid-cols-1 gap-x-8 gap-y-5 text-sm sm:grid-cols-3">
                <div><div className="text-xs text-muted-foreground">LEGAL NAME</div><div className="mt-1">{c.name}</div></div>
                <div><div className="text-xs text-muted-foreground">SHORT NAME</div><div>{c.shortName ?? "—"}</div></div>
                <div><div className="text-xs text-muted-foreground">TAX ID</div><div>{c.taxCode ?? "—"}</div></div>
                <div><div className="text-xs text-muted-foreground">REPRESENTATIVE</div><div>{c.representativeName ?? "—"}</div></div>
                <div><div className="text-xs text-muted-foreground">POSITION</div><div>{c.representativePosition ?? "—"}</div></div>
                <div><div className="text-xs text-muted-foreground">SEGMENT</div><div>{c.segment ?? "—"}</div></div>
                <div className="sm:col-span-3"><div className="text-xs text-muted-foreground">REGISTERED ADDRESS</div><div>{c.address ?? "—"}</div></div>
              </CardContent>
            </Card>
            <Card>
              <CardHeader className="pb-5"><CardTitle>Primary contact</CardTitle></CardHeader>
              <CardContent className="space-y-1.5 text-sm">
                {c.primaryContact ? (
                  <>
                    <div className="font-semibold">{c.primaryContact.fullName}</div>
                    <div className="text-xs text-muted-foreground">{c.primaryContact.title ?? ""}</div>
                    <div>{c.primaryContact.email ?? "—"}</div>
                    <div>{c.primaryContact.phone ?? ""}</div>
                  </>
                ) : (
                  <div className="text-muted-foreground">No primary contact.</div>
                )}
              </CardContent>
            </Card>
          </div>

          {canReadContracts && <Card>
            <CardHeader className="flex flex-row items-center justify-between">
              <CardTitle>Recent contracts</CardTitle>
              <Link to="/contracts" search={{ customerId: c.id }} className="text-sm text-blue-600 hover:underline">View all</Link>
            </CardHeader>
            <CardContent>
              {recentContractsQ.isLoading ? <div className="text-sm text-muted-foreground">Loading...</div> : recentContractsQ.isError ? <div className="text-sm text-destructive">Failed to load recent contracts</div> : <DataTable columns={recentColumns} data={recentContracts} emptyMessage="No contracts" pageSize={5} />}
            </CardContent>
          </Card>}
        </div>
      )}

      {canReadContracts && tab === "contracts" && (
        <Card>
          <CardHeader><CardTitle className="text-base">Contracts · {c.name}</CardTitle></CardHeader>
          <CardContent>
            {contractsQ.isLoading ? <div className="text-sm text-muted-foreground">Loading...</div> : contractsQ.isError ? <div className="space-y-2"><div className="text-sm text-destructive">Failed to load contracts</div>{contractsCursor && <Button variant="outline" size="sm" onClick={recoverContracts}>Return to first page</Button>}</div> : (
              <DataTable
                columns={recentColumns}
                data={contracts}
                emptyMessage="No contracts"
                pageSize={DEFAULT_PAGE_SIZE}
                serverPagination={{
                  page: contractsQ.data?.number ?? contractsPage,
                  totalPages: contractsQ.data?.totalPages ?? 0,
                  totalItems: contractsQ.data?.totalElements ?? 0,
                  onPageChange: changeContractsPage,
                }}
              />
            )}
          </CardContent>
        </Card>
      )}

      {tab === "contacts" && (
        <Card>
          <CardHeader><CardTitle className="text-base">Contacts</CardTitle></CardHeader>
          <CardContent>
            <ContactTable contacts={c.contacts} />
          </CardContent>
        </Card>
      )}
      </div>
    </div>
  );
}
