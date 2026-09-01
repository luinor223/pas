import { createFileRoute } from "@tanstack/react-router";
import { Placeholder } from "@/shared/components/Placeholder";

export const Route = createFileRoute("/customers")({
  component: () => <Placeholder title="Customers" note="The customer directory, detail view and contacts." />,
});
