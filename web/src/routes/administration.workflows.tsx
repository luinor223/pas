import { createFileRoute } from "@tanstack/react-router";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/ui/card";

export const Route = createFileRoute("/administration/workflows")({
  component: () => (
    <Card>
      <CardHeader><CardTitle>Workflows (stub)</CardTitle></CardHeader>
      <CardContent className="text-sm text-muted-foreground">
        Read-only placeholder for <code>workflow-service</code> definitions (16-administration.png right pane). Will call <code>GET /workflow-definitions</code> after Session 2.
      </CardContent>
    </Card>
  ),
});
