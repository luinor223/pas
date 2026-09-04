import { useQuery, useQueryClient } from "@tanstack/react-query";
import { customerMetricsQuery, customerQuery, contractsQuery } from "../hooks/contractQueries";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { StatusBadge } from "@/shared/components/status-badge";
import { Button } from "@/shared/components/button";
import { ContactTable } from "./ContactTable";
import { DataTable } from "@/shared/components/data-table";
import type { ColumnDef } from "@tanstack/react-table";
import type { ContractResponse } from "../types/contractTypes";
import { useState } from "react";
import { Link, useNavigate } from "@tanstack/react-router";
import { formatDate, formatDecimalMoney, formatMoney } from "@/shared/lib/format";
import { DEFAULT_PAGE_SIZE } from "@/shared/api/paging";
import { DetailBackButton } from "@/shared/components/detail-back-link";
import { TabBar, type TabItem } from "@/shared/components/tab-bar";
import { useHasPermission } from "@/features/auth/hooks/usePermissions";

type Tab = "overview" | "contracts" | "contacts";

const TABS: readonly TabItem<Tab>[] = [
  { value: "overview", label: "Overview" },
  { value: "contracts", label: "Contracts" },
  { value: "contacts", label: "Contacts" },
];

export function CustomerDetail({ id, onEdit }: { id: string; onEdit?: () => void }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const canReadContracts = useHasPermission("contract:read");
  const [tab, setTab] = useState<Tab>("overview");
  const [contractsPage, setContractsPage] = useState(0);
  const [contractsCursor, setContractsCursor] = useState<string>();
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
  const changeContractsPage = (nextPage: number) => {
    if (contractsPage === 0 && contractsQ.data?.cursor) setContractsCursor(contractsQ.data.cursor);
    setContractsPage(nextPage);
  };
  const recoverContracts = () => {
    queryClient.removeQueries({ queryKey: ["contracts"] });
    setContractsCursor(undefined);
    setContractsPage(0);
  };

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

  const initials = c.name.split(" ").map((w) => w[0]).slice(0, 3).join("").toUpperCase();

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <DetailBackButton to="/customers" label="Back to customers" />
          <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-blue-100 text-sm font-bold text-blue-700">{initials}</div>
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-xl font-bold">{c.name}</h2>
              <StatusBadge status={c.status} />
            </div>
            <div className="text-xs text-muted-foreground">{c.code} · Tax ID {c.taxCode ?? "—"} · Customer since {new Date(c.createdAt).getFullYear()}</div>
          </div>
        </div>
        <div className="flex gap-2">
          {onEdit && <Button variant="outline" onClick={onEdit}>Edit</Button>}
          {canReadContracts && <Button onClick={() => navigate({ to: "/contracts", search: { customerId: c.id } as never })}>View contracts</Button>}
        </div>
      </div>

      <TabBar tabs={tabs} value={tab} onChange={setTab} />

      {tab === "overview" && (
        <>
          {canReadContracts && <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <Card><CardContent className="p-4"><div className="text-xs text-muted-foreground">Active contracts</div><div className="text-2xl font-bold">{metricsQ.isLoading ? "…" : metricsQ.isError ? "Unavailable" : metricsQ.data?.activeContracts ?? 0}</div></CardContent></Card>
            <Card>
              <CardContent className="p-4">
                <div className="text-xs text-muted-foreground">Approved contract value</div>
                {metricsQ.isLoading ? (
                  <div className="text-2xl font-bold">…</div>
                ) : metricsQ.isError ? (
                  <div className="text-sm text-destructive">Failed to load contract metrics</div>
                ) : metricsQ.data?.approvedContractValues.length ? (
                  <div className="space-y-1">
                    {metricsQ.data.approvedContractValues.map((total) => (
                      <div key={total.currency} className="text-2xl font-bold tabular-nums">{formatDecimalMoney(total.value, total.currency)}</div>
                    ))}
                  </div>
                ) : (
                  <div className="text-sm text-muted-foreground">No approved contract value</div>
                )}
                <div className="mt-1 text-xs text-muted-foreground">Includes approved and active contracts</div>
              </CardContent>
            </Card>
          </div>}

          <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
            <Card className="lg:col-span-2">
              <CardHeader><CardTitle className="text-base">Company information</CardTitle></CardHeader>
              <CardContent className="grid grid-cols-3 gap-3 text-sm">
                <div><div className="text-xs text-muted-foreground">LEGAL NAME</div><div>{c.name}</div></div>
                <div><div className="text-xs text-muted-foreground">SHORT NAME</div><div>{c.shortName ?? "—"}</div></div>
                <div><div className="text-xs text-muted-foreground">TAX ID</div><div>{c.taxCode ?? "—"}</div></div>
                <div><div className="text-xs text-muted-foreground">REPRESENTATIVE</div><div>{c.representativeName ?? "—"}</div></div>
                <div><div className="text-xs text-muted-foreground">POSITION</div><div>{c.representativePosition ?? "—"}</div></div>
                <div><div className="text-xs text-muted-foreground">SEGMENT</div><div>{c.segment ?? "—"}</div></div>
                <div className="col-span-3"><div className="text-xs text-muted-foreground">REGISTERED ADDRESS</div><div>{c.address ?? "—"}</div></div>
              </CardContent>
            </Card>
            <Card>
              <CardHeader><CardTitle className="text-base">Primary contact</CardTitle></CardHeader>
              <CardContent className="text-sm space-y-1">
                {c.primaryContact ? (
                  <>
                    <div className="font-medium">{c.primaryContact.fullName}</div>
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
              <CardTitle className="text-base">Recent contracts</CardTitle>
              <a href={`/contracts?customerId=${c.id}`} className="text-sm text-blue-600 hover:underline">View all</a>
            </CardHeader>
            <CardContent>
              {recentContractsQ.isLoading ? <div className="text-sm text-muted-foreground">Loading...</div> : recentContractsQ.isError ? <div className="text-sm text-destructive">Failed to load recent contracts</div> : <DataTable columns={recentColumns} data={recentContracts} emptyMessage="No contracts" pageSize={5} />}
            </CardContent>
          </Card>}
        </>
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
  );
}
