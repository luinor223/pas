import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/shared/components/table";
import { Badge } from "@/shared/components/badge";
import type { CustomerContactResponse } from "../types/contractTypes";

// Labeled contacts table — plain (no sorting/pagination needed for a handful
// of contacts) so headers never wrap and columns breathe. Used by the customer
// detail Contacts tab and the list's View contacts dialog.
export function ContactTable({ contacts }: { contacts: CustomerContactResponse[] }) {
  if (contacts.length === 0) {
    return <div className="py-6 text-center text-sm text-muted-foreground">No contacts.</div>;
  }
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead className="whitespace-nowrap">FULL NAME</TableHead>
          <TableHead className="whitespace-nowrap">TITLE</TableHead>
          <TableHead className="whitespace-nowrap">EMAIL</TableHead>
          <TableHead className="whitespace-nowrap">PHONE</TableHead>
          <TableHead className="whitespace-nowrap">ROLE</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {contacts.map((c) => (
          <TableRow key={c.id}>
            <TableCell className="font-medium whitespace-nowrap">{c.fullName}</TableCell>
            <TableCell>{c.title ?? "—"}</TableCell>
            <TableCell>{c.email ?? "—"}</TableCell>
            <TableCell className="whitespace-nowrap">{c.phone ?? "—"}</TableCell>
            <TableCell>{c.primary ? <Badge variant="secondary">primary</Badge> : <span className="text-xs text-muted-foreground">secondary</span>}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
