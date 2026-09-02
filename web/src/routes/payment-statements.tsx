import { createFileRoute } from "@tanstack/react-router";
import { Placeholder } from "@/shared/components/Placeholder";

export const Route = createFileRoute("/payment-statements")({
  component: () => <Placeholder title="Payment Statements" note="Generated statements priced from effective price lists." />,
});
