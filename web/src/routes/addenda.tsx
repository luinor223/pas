import { createFileRoute } from "@tanstack/react-router";
import { AddendumList } from "@/features/contract/components/AddendumList";

export const Route = createFileRoute("/addenda")({
  component: AddendumList,
});
