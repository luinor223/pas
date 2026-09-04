import { createFileRoute } from "@tanstack/react-router";
import { Placeholder } from "@/shared/components/Placeholder";

export const Route = createFileRoute("/price-lists")({
  component: () => <Placeholder title="Price Lists" note="Manage prices and their effective dates." />,
});
