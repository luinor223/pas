import { Mail, Phone, Star } from "lucide-react";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/shared/components/table";
import type { CustomerContactResponse } from "../types/contractTypes";

// A handful of contacts, so no sorting or pagination. The primary contact leads
// the list and is marked, since that is the one a user is usually looking for.
export function ContactTable({ contacts }: { contacts: CustomerContactResponse[] }) {
  if (contacts.length === 0) {
    return <div className="py-6 text-center text-sm text-muted-foreground">No contacts yet.</div>;
  }

  const ordered = [...contacts].sort((a, b) => Number(b.primary) - Number(a.primary));

  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead className="whitespace-nowrap">NAME</TableHead>
          <TableHead className="whitespace-nowrap">TITLE</TableHead>
          <TableHead className="whitespace-nowrap">CONTACT</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {ordered.map((c) => (
          <TableRow key={c.id}>
            <TableCell className="align-top">
              <div className="flex items-center gap-1.5 font-medium">
                {c.fullName}
                {c.primary && (
                  <span title="Primary contact" className="inline-flex items-center text-accent">
                    <Star size={13} fill="currentColor" />
                  </span>
                )}
              </div>
              {c.primary && <div className="text-xs text-muted-foreground">Primary contact</div>}
            </TableCell>
            <TableCell className="align-top">{c.title ?? "—"}</TableCell>
            <TableCell className="align-top">
              {/* Email and phone stacked: both are long, and side by side they forced
                  the dialog wider than it needs to be. */}
              <div className="space-y-0.5">
                {c.email ? (
                  <a href={`mailto:${c.email}`} className="flex items-center gap-1.5 text-primary hover:underline">
                    <Mail size={13} className="shrink-0 text-muted-foreground" />
                    <span className="break-all">{c.email}</span>
                  </a>
                ) : null}
                {c.phone ? (
                  <a href={`tel:${c.phone}`} className="flex items-center gap-1.5 text-primary hover:underline">
                    <Phone size={13} className="shrink-0 text-muted-foreground" />
                    <span className="whitespace-nowrap">{c.phone}</span>
                  </a>
                ) : null}
                {!c.email && !c.phone && <span className="text-muted-foreground">—</span>}
              </div>
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
