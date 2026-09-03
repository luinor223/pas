import { Link } from "@tanstack/react-router";
import { ShieldAlert } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { Button } from "@/shared/components/button";

export function Forbidden({ message = "You do not have permission to view this page." }: { message?: string }) {
  return (
    <Card className="max-w-lg mx-auto mt-12">
      <CardHeader className="flex flex-row items-center gap-3">
        <ShieldAlert className="text-destructive" size={24} />
        <CardTitle>Access denied</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <p className="text-sm text-muted-foreground">{message}</p>
        <p className="text-xs text-muted-foreground">
          If you think you should have access, ask your administrator to review your role.
        </p>
        <Link to="/"><Button variant="outline">Back to Dashboard</Button></Link>
      </CardContent>
    </Card>
  );
}
