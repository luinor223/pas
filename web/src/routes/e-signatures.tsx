import { createFileRoute } from "@tanstack/react-router";
import { ESignaturesPage, SigningSessionDetail } from "@/features/esign";

export const Route = createFileRoute("/e-signatures")({
  validateSearch: (search: Record<string, unknown>) => ({
    id: typeof search.id === "string" ? search.id : undefined,
  }),
  component: ESignaturesRoute,
});

function ESignaturesRoute() {
  const { id } = Route.useSearch();
  if (id) return <SigningSessionDetail key={id} id={id} />;
  return <ESignaturesPage />;
}
