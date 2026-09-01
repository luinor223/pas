import { createFileRoute } from "@tanstack/react-router";
import { Placeholder } from "@/shared/components/Placeholder";

export const Route = createFileRoute("/volume-records")({
  component: () => <Placeholder title="Volume Records" note="Monthly volume capture with period locking." />,
});
