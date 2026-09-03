import { createFileRoute } from "@tanstack/react-router";
import { ApprovalInbox } from "@/features/approval";

export const Route = createFileRoute("/approvals")({
  component: ApprovalInbox,
});
