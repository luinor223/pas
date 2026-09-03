import { useQuery } from "@tanstack/react-query";
import { customerQuery, contractsQuery } from "../hooks/contractQueries";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { Badge } from "@/shared/components/badge";
import { StatusBadge } from "@/shared/components/status-badge";
import { Button } from "@/shared/components/button";
import { DataTable } from "@/shared/components/data-table";
import type { ColumnDef } from "@tanstack/react-table";
import type { ContractResponse } from "../types/contractTypes";
import { useState } from "react";
import { Link } from "@tanstack/react-router";

export function CustomerDetail({ id, onEdit }: { id: string; onEdit?: () => void }) {
  const q = useQuery(customerQuery(id));
  const contractsQ = useQuery(contractsQuery({ customerId: id, size: 25 }));
  const [tab, setTab] = useState<"overview" | "contracts" | "contacts">("overview");

  const c = q.data;
  if (q.isLoading) return <div className="text-sm text-muted-foreground">Loading...</div>;
  if (q.isError) return <div className="text-sm text-destructive">Failed to load customer</div>;
  if (!c) return null;

  const contracts = contractsQ.data?.content ?? [];
  const activeContracts = contracts.filter((x) => x.status === "ACTIVE").length;
  const totalValue = contracts.reduce((s, x) => s + (x.value ?? 0), 0);

  const recentColumns: ColumnDef<ContractResponse>[] = [
    {
      accessorKey: "contractNo", header: "CONTRACT NO.",
      cell: ({ row }) => <Link to="/contracts" search={{ id: row.original.id } as never} className="text-blue-600 hover:underline">{row.original.contractNo}</Link>,
    },
    { accessorKey: "serviceGroup", header: "SERVICE GROUP" },
    { accessorKey: "value", header: "VALUE (VND)", cell: ({ row }) => <span className="tabular-nums">{row.original.value?.toLocaleString("vi-VN") ?? "—"}</span> },
    { accessorKey: "validFrom", header: "EFFECTIVE", cell: ({ row }) => <span className="text-xs">{row.original.validFrom}</span> },
    { accessorKey: "status", header: "STATUS", cell: ({ row }) => <StatusBadge status={row.original.status} /> },
  ];

  const initials = c.name.split(" ").map((w) => w[0]).slice(0, 3).join("").toUpperCase();

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
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
          <Button onClick={() => (window.location.href = `/contracts?customerId=${c.id}`)}>New Contract</Button>
        </div>
      </div>

      <div className="flex gap-2 border-b pb-2">
        {(["overview", "contracts", "contacts"] as const).map((t) => (
          <Button key={t} size="sm" variant={tab === t ? "default" : "ghost"} onClick={() => setTab(t)}>
            {t === "overview" ? "Overview" : t === "contracts" ? "Contracts" : "Contacts"}
          </Button>
        ))}
        <span className="ml-2 text-xs text-muted-foreground self-center">Price Lists / Statements / Activity → pending pricing / billing services</span>
      </div>

      {tab === "overview" && (
        <>
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
            <Card><CardContent className="p-4"><div className="text-xs text-muted-foreground">Active contracts</div><div className="text-2xl font-bold">{contractsQ.isLoading ? "…" : activeContracts}</div></CardContent></Card>
            <Card><CardContent className="p-4"><div className="text-xs text-muted-foreground">Total contract value</div><div className="text-2xl font-bold">{contractsQ.isLoading ? "…" : `${(totalValue / 1e9).toFixed(1)}B VND`}</div></CardContent></Card>
            <Card><CardContent className="p-4"><div className="text-xs text-muted-foreground">Outstanding balance</div><div className="text-sm text-muted-foreground mt-1">Pending billing-service — no mock balance shown.</div></CardContent></Card>
            <Card><CardContent className="p-4"><div className="text-xs text-muted-foreground">Avg. payment delay</div><div className="text-sm text-muted-foreground mt-1">Pending billing-service.</div></CardContent></Card>
          </div>

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

          <Card>
            <CardHeader className="flex flex-row items-center justify-between">
              <CardTitle className="text-base">Recent contracts</CardTitle>
              <a href={`/contracts?customerId=${c.id}`} className="text-sm text-blue-600 hover:underline">View all</a>
            </CardHeader>
            <CardContent>
              {contractsQ.isLoading ? <div className="text-sm text-muted-foreground">Loading...</div> : <DataTable columns={recentColumns} data={contracts.slice(0, 5)} emptyMessage="No contracts" pageSize={25} />}
            </CardContent>
          </Card>
        </>
      )}

      {tab === "contracts" && (
        <Card>
          <CardHeader><CardTitle className="text-base">Contracts · {c.name}</CardTitle></CardHeader>
          <CardContent>
            {contractsQ.isLoading ? <div className="text-sm text-muted-foreground">Loading...</div> : <DataTable columns={recentColumns} data={contracts} emptyMessage="No contracts" pageSize={25} />}
          </CardContent>
        </Card>
      )}

      {tab === "contacts" && (
        <Card>
          <CardHeader><CardTitle className="text-base">Contacts</CardTitle></CardHeader>
          <CardContent className="space-y-2 text-sm">
            {c.contacts.length === 0 ? <div className="text-muted-foreground">No contacts.</div> : c.contacts.map((x) => (
              <div key={x.id} className="border rounded p-2 flex justify-between">
                <div>
                  <div className="font-medium">{x.fullName} {x.primary && <Badge variant="secondary" className="ml-1">primary</Badge>}</div>
                  <div className="text-xs text-muted-foreground">{x.title} · {x.email} · {x.phone}</div>
                </div>
              </div>
            ))}
          </CardContent>
        </Card>
      )}
    </div>
  );
}
