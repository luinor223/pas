import { createFileRoute } from "@tanstack/react-router";
import { WorkflowDefinitions } from "@/features/workflow";

export const Route = createFileRoute("/admin/workflows")({
  component: () => <WorkflowDefinitions />,
});
