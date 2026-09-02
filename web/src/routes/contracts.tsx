import { createFileRoute } from "@tanstack/react-router";
import { Placeholder } from "@/shared/components/Placeholder";

export const Route = createFileRoute("/contracts")({
  component: () => <Placeholder title="Contracts" note="Contract list, lifecycle states and detail with addenda." />,
});
