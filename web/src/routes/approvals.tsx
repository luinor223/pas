import { createFileRoute } from "@tanstack/react-router";
import { Placeholder } from "@/shared/components/Placeholder";

export const Route = createFileRoute("/approvals")({
  component: () => <Placeholder title="Approvals Inbox" note="Your approval queue across every document type." />,
});
