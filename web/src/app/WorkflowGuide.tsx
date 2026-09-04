import { useState } from "react";
import { BookOpen } from "lucide-react";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/shared/components/dialog";

const FLOW = [
  {
    title: "Customers",
    owner: "Sales",
    description: "Create the customer and make sure it is active before starting a contract.",
  },
  {
    title: "Contracts",
    owner: "Sales, Legal & Director",
    description: "Create the contract, add its services and attachment, then submit it for approval. It must be approved and cover the service period.",
  },
  {
    title: "Addenda (when needed)",
    owner: "Sales, Legal & Director",
    description: "Use an addendum to change an approved or active contract. Approve it before relying on the changed terms. A price change also needs a new price-list version.",
  },
  {
    title: "Price Lists",
    owner: "Sales & approvers",
    description: "Create the correct customer or contract scope, add a version, price every service that will be recorded, and complete approval. Its dates must cover the billing month.",
  },
  {
    title: "Volume Records",
    owner: "Operations",
    description: "Create the monthly period, record the contract's actual service volumes, confirm them, then lock the period.",
  },
  {
    title: "Payment Statements",
    owner: "Accounting",
    description: "Choose the contract and locked month to calculate. Reconcile the calculated lines, then submit the statement for approval.",
  },
  {
    title: "E-Signatures",
    owner: "Document owner & signers",
    description: "When signing is required, send the approved document for signature and wait until every signer has completed it.",
  },
  {
    title: "Issue & archive",
    owner: "Accounting",
    description: "After the payment statement is signed, publish it as issued. The audit log keeps the complete history.",
  },
] as const;

export function WorkflowGuide({ collapsed }: { collapsed: boolean }) {
  const [open, setOpen] = useState(false);

  return (
    <>
      <div className={collapsed ? "px-2 pb-3" : "px-3 pb-3"}>
        <button
          type="button"
          onClick={() => setOpen(true)}
          title={collapsed ? "Open process guide" : undefined}
          aria-label={collapsed ? "Open process guide" : undefined}
          className={`flex w-full items-center rounded-lg border border-white/15 bg-white/8 py-2 text-sm font-medium text-white/80 transition-colors hover:bg-white/15 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white/70 ${
            collapsed ? "justify-center px-2" : "gap-3 px-3"
          }`}
        >
          <BookOpen size={18} aria-hidden="true" className="shrink-0" />
          {!collapsed && <span>Process guide</span>}
        </button>
      </div>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-w-3xl">
          <DialogHeader>
            <DialogTitle>Business record process guide</DialogTitle>
            <p className="text-sm text-muted-foreground">
              Complete the records in this order. Each downstream step depends on the records before it.
            </p>
          </DialogHeader>

          <ol className="space-y-3" aria-label="Complete business record flow">
            {FLOW.map((step, index) => (
              <li key={step.title} className="flex gap-3 rounded-lg border bg-muted/20 p-3">
                <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary text-xs font-semibold text-primary-foreground" aria-hidden="true">
                  {index + 1}
                </span>
                <div className="min-w-0">
                  <div className="flex flex-wrap items-baseline gap-x-2">
                    <h3 className="text-sm font-semibold text-foreground">{step.title}</h3>
                    <span className="text-xs text-muted-foreground">{step.owner}</span>
                  </div>
                  <p className="mt-1 text-sm leading-5 text-muted-foreground">{step.description}</p>
                </div>
              </li>
            ))}
          </ol>

          <div className="mt-4 flex justify-end">
            <button
              type="button"
              onClick={() => setOpen(false)}
              className="h-9 rounded-md border border-input bg-background px-4 text-sm font-medium hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              Close
            </button>
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
}
