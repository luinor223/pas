import { Link } from "@tanstack/react-router";
import { ShieldAlert } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { Button } from "@/shared/components/button";

export function Forbidden({ message = "You do not have permission to view this page." }: { message?: string }) {
  return (
    <Card className="max-w-lg mx-auto mt-12">
      <CardHeader className="flex flex-row items-center gap-3">
        <ShieldAlert className="text-destructive" size={24} />
        <CardTitle>403 Forbidden</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <p className="text-sm text-muted-foreground">{message}</p>
        <p className="text-xs text-muted-foreground">
          Missing permission or authorization service unavailable (fail-closed). Contact your administrator.
        </p>
        <Link to="/"><Button variant="outline">Back to Dashboard</Button></Link>
      </CardContent>
    </Card>
  );
}
