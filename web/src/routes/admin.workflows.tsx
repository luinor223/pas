import { createFileRoute } from "@tanstack/react-router";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";

export const Route = createFileRoute("/admin/workflows")({
  component: () => (
    <Card>
      <CardHeader><CardTitle>Workflows (stub)</CardTitle></CardHeader>
      <CardContent className="text-sm text-muted-foreground">
        Read-only placeholder for <code>workflow-service</code> definitions. Will call <code>GET /workflow-definitions</code> after Session 2.
      </CardContent>
    </Card>
  ),
});
