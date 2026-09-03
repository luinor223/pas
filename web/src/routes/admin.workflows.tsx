import { createFileRoute } from "@tanstack/react-router";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";

export const Route = createFileRoute("/admin/workflows")({
  component: () => (
    <Card>
      <CardHeader><CardTitle>Workflows</CardTitle></CardHeader>
      <CardContent className="text-sm text-muted-foreground">
        Workflow configuration is not available yet.
      </CardContent>
    </Card>
  ),
});
