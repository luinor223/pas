import { createFileRoute } from "@tanstack/react-router";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/ui/card";
export const Route = createFileRoute("/administration/document-types")({
  component: () => (
    <Card>
      <CardHeader><CardTitle>Document Types (stub)</CardTitle></CardHeader>
      <CardContent className="text-sm text-muted-foreground">
        Placeholder for <code>document_type_config</code> CRUD (<code>doctype:configure</code>). Shows CONTRACT 3 steps, etc from 16-administration.png.
      </CardContent>
    </Card>
  ),
});
