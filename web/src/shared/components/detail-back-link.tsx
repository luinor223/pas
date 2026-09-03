import { Link } from "@tanstack/react-router";
import { ChevronLeft } from "lucide-react";

type DetailBackButtonProps = {
  to: "/customers" | "/contracts" | "/price-lists";
  /** Names the destination for assistive tech, since the chevron carries no text. */
  label: string;
};

/** Square chevron that sits beside a detail title and clears the row selection from the URL. */
export function DetailBackButton({ to, label }: DetailBackButtonProps) {
  return (
    <Link
      to={to}
      search={to === "/customers"
        ? { id: undefined }
        : to === "/contracts"
          ? { id: undefined, tab: undefined, customerId: undefined }
          : { id: undefined, versionId: undefined }}
      aria-label={label}
      title={label}
      className="inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-border bg-card text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
    >
      <ChevronLeft size={18} aria-hidden="true" />
    </Link>
  );
}
