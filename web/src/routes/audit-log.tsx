import { createFileRoute } from "@tanstack/react-router";
import { Placeholder } from "@/shared/components/Placeholder";

export const Route = createFileRoute("/audit-log")({
  component: () => <Placeholder title="Audit Log" note="The complete, immutable activity trail." />,
});
