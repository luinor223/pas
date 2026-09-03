import { useMemo } from "react";
import type { ColumnDef } from "@tanstack/react-table";
import { DataTable } from "@/shared/components/data-table";
import { Badge } from "@/shared/components/badge";
import type { CustomerContactResponse } from "../types/contractTypes";

// Labeled contacts table — used by the customer detail Contacts tab and the
// list's View contacts dialog (replaces the compact unlabeled rows).
export function ContactTable({ contacts }: { contacts: CustomerContactResponse[] }) {
  const columns = useMemo<ColumnDef<CustomerContactResponse>[]>(() => [
    {
      accessorKey: "fullName", header: "FULL NAME",
      cell: ({ row }) => (
        <span className="font-medium">
          {row.original.fullName}{" "}
          {row.original.primary && <Badge variant="secondary" className="ml-1">primary</Badge>}
        </span>
      ),
    },
    { accessorKey: "title", header: "TITLE", cell: ({ row }) => <span>{row.original.title ?? "—"}</span> },
    { accessorKey: "email", header: "EMAIL", cell: ({ row }) => <span>{row.original.email ?? "—"}</span> },
    { accessorKey: "phone", header: "PHONE", cell: ({ row }) => <span>{row.original.phone ?? "—"}</span> },
  ], []);

  return <DataTable columns={columns} data={contacts} emptyMessage="No contacts" pageSize={25} />;
}
