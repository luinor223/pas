import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Button } from "@/shared/components/ui/button";
import { Input } from "@/shared/components/ui/input";
import { Label } from "@/shared/components/ui/label";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/ui/card";
import { api } from "@/api/client";
import { useAuthStore } from "@/stores/auth.store";
import { useState } from "react";

export const Route = createFileRoute("/login")({ component: LoginPage });

const schema = z.object({ username: z.string().min(1, "Required"), password: z.string().min(1, "Required") });
type Form = z.infer<typeof schema>;

function LoginPage() {
  const navigate = useNavigate();
  const setAuth = useAuthStore((s) => s.setAuth);
  const [err, setErr] = useState<string | null>(null);
  const { register, handleSubmit, formState: { isSubmitting, errors } } = useForm<Form>({ resolver: zodResolver(schema), defaultValues: { username: "admin", password: "admin12345" } });

  async function onSubmit(data: Form) {
    setErr(null);
    try {
      const res = await api.post("/auth/login", data);
      const d = res.data as { accessToken: string; refreshToken: string; expiresAt: string; user: { id: string; username: string; fullName: string; department: string; roles: string[] } };
      setAuth({ accessToken: d.accessToken, refreshToken: d.refreshToken, expiresAt: d.expiresAt, user: d.user });
      navigate({ to: "/" });
    } catch (e: unknown) {
      const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message ?? "Login failed";
      setErr(msg);
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-muted/30 p-4">
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle className="text-2xl">PAS — Sign in</CardTitle>
          <p className="text-sm text-muted-foreground">Business Document Management System</p>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="username">Username / Email</Label>
              <Input id="username" placeholder="admin" {...register("username")} />
              {errors.username && <p className="text-sm text-destructive">{errors.username.message}</p>}
            </div>
            <div className="space-y-2">
              <Label htmlFor="password">Password</Label>
              <Input id="password" type="password" {...register("password")} />
              {errors.password && <p className="text-sm text-destructive">{errors.password.message}</p>}
            </div>
            {err && <div className="text-sm text-destructive bg-destructive/10 p-2 rounded">{err}</div>}
            <Button type="submit" className="w-full" disabled={isSubmitting}>
              {isSubmitting ? "Signing in..." : "Sign in"}
            </Button>
            <p className="text-xs text-muted-foreground">
              Default: <code>admin / admin12345</code> (SYSTEM_ADMIN). Forgot password is UI-only per db-identity.
            </p>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}
