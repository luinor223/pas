import { createFileRoute } from "@tanstack/react-router";
import { CustomerList } from "@/features/contract/components/CustomerList";

export const Route = createFileRoute("/customers")({
  component: CustomerList,
});
