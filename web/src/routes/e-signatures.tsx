import { createFileRoute } from "@tanstack/react-router";
import { Placeholder } from "@/shared/components/Placeholder";

export const Route = createFileRoute("/e-signatures")({
  component: () => <Placeholder title="E-Signatures" note="Signature requests and their completion status." />,
});
