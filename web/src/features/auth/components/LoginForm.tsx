import { useNavigate } from "@tanstack/react-router";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useState } from "react";
import { Logo } from "@/shared/components/Logo";
import { useAuthStore } from "../store/authStore";
import { authApi } from "../services/authApi";

const schema = z.object({
  username: z.string().min(1, "Required"),
  password: z.string().min(1, "Required"),
});
type Form = z.infer<typeof schema>;

export function LoginForm() {
  const navigate = useNavigate();
  const setAuth = useAuthStore((s) => s.setAuth);
  const [err, setErr] = useState<string | null>(null);
  const { register, handleSubmit, formState: { isSubmitting, errors } } = useForm<Form>({
    resolver: zodResolver(schema),
    defaultValues: { username: "admin", password: "admin12345" },
  });

  async function onSubmit(data: Form) {
    setErr(null);
    try {
      const d = await authApi.login(data);
      setAuth({ accessToken: d.accessToken, refreshToken: d.refreshToken, expiresAt: d.expiresAt, user: d.user });
      navigate({ to: "/" });
    } catch (e: unknown) {
      setErr((e as { response?: { data?: { message?: string } } })?.response?.data?.message ?? "Sign in failed. Check your credentials.");
    }
  }

  return (
    <div className="flex min-h-screen">
      {/* Brand panel */}
      <div className="relative hidden w-[46%] max-w-[620px] flex-col justify-between bg-navy p-16 text-navy-foreground lg:flex">
        <Logo tone="light" />
        <div>
          <h1 className="text-[40px] font-bold leading-[1.1] tracking-tight text-white">
            Business Document<br />Management System
          </h1>
          <p className="mt-5 max-w-md text-[15px] leading-relaxed text-white/60">
            Manage the full lifecycle of contracts, price lists, volumes and payment statements,
            with configurable approvals, e-signature and a complete audit trail.
          </p>
        </div>
        <p className="text-xs text-white/40">© 2026 Company ABC · Logistics &amp; Port Operations</p>
      </div>

      {/* Form panel */}
      <div className="flex flex-1 items-center justify-center bg-card px-6 py-12">
        <div className="w-full max-w-sm">
          <div className="mb-8 lg:hidden"><Logo /></div>
          <h2 className="text-2xl font-bold tracking-tight">Sign in</h2>
          <p className="mt-1 text-sm text-muted-foreground">Use your company account to continue.</p>

          <form onSubmit={handleSubmit(onSubmit)} className="mt-8 space-y-5">
            <div className="space-y-1.5">
              <label htmlFor="username" className="text-[13px] font-medium">Email address</label>
              <input
                id="username" autoFocus autoComplete="username"
                placeholder="you@abclogistics.com"
                className="h-11 w-full rounded-lg border border-input bg-card px-3.5 text-sm outline-none focus:border-primary focus:ring-2 focus:ring-primary/20"
                {...register("username")}
              />
              {errors.username && <p className="text-xs text-destructive">{errors.username.message}</p>}
            </div>

            <div className="space-y-1.5">
              <label htmlFor="password" className="text-[13px] font-medium">Password</label>
              <input
                id="password" type="password" autoComplete="current-password"
                className="h-11 w-full rounded-lg border border-input bg-card px-3.5 text-sm outline-none focus:border-primary focus:ring-2 focus:ring-primary/20"
                {...register("password")}
              />
              {errors.password && <p className="text-xs text-destructive">{errors.password.message}</p>}
            </div>

            <div className="flex items-center justify-between">
              <label className="flex items-center gap-2 text-sm text-muted-foreground">
                <input type="checkbox" className="h-4 w-4 rounded border-input accent-primary" defaultChecked />
                Remember me
              </label>
              <a href="#" className="text-sm font-medium text-primary hover:underline">Forgot password?</a>
            </div>

            {err && <div className="rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive">{err}</div>}

            <button
              type="submit" disabled={isSubmitting}
              className="h-11 w-full rounded-lg bg-primary text-sm font-semibold text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-60"
            >
              {isSubmitting ? "Signing in…" : "Sign in"}
            </button>

            <p className="text-center text-xs text-muted-foreground">
              Demo: <span className="font-mono">admin</span> / <span className="font-mono">admin12345</span>
            </p>
          </form>
        </div>
      </div>
    </div>
  );
}
