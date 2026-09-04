import { Link } from "@tanstack/react-router";
import { ArrowLeft } from "lucide-react";

type DetailBackLinkProps = {
  to: "/customers" | "/contracts";
  children: string;
};

export function DetailBackLink({ to, children }: DetailBackLinkProps) {
  return (
    <Link
      to={to}
      search={to === "/customers"
        ? { id: undefined }
        : { id: undefined, tab: undefined, customerId: undefined }}
      className="inline-flex w-fit items-center gap-1.5 rounded-md px-2 py-1.5 text-sm font-medium text-primary hover:bg-primary/10"
    >
      <ArrowLeft size={16} aria-hidden="true" />
      {children}
    </Link>
  );
}
