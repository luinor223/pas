import { createFileRoute } from "@tanstack/react-router";
import { Placeholder } from "@/shared/components/Placeholder";

export const Route = createFileRoute("/addenda")({
  component: () => <Placeholder title="Addenda" note="Contract addenda and their effective dates." />,
});
