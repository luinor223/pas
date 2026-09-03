import { createFileRoute } from "@tanstack/react-router";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
export const Route = createFileRoute("/admin/document-types")({
  component: () => (
    <Card>
      <CardHeader><CardTitle>Document Types</CardTitle></CardHeader>
      <CardContent className="text-sm text-muted-foreground">
        Document type configuration is not available yet.
      </CardContent>
    </Card>
  ),
});
