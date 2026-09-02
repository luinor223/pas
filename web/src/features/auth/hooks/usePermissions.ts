import { useCurrentUser } from "@/features/auth/hooks/useCurrentUser";

// Code checks permissions, never roles (00-registry.md:265)
// UI gating via permissions returned by GET /auth/me (authoritative).
// Server enforces via Redis perm:role:*; this hook is display-only.
export function usePermissions(): string[] {
  const user = useCurrentUser().data;
  if (!user) return [];
  return user.permissions ?? [];
}

export function useHasPermission(code: string): boolean {
  const perms = usePermissions();
  return perms.includes(code);
}

export function useHasAnyPermission(codes: string[]): boolean {
  const perms = usePermissions();
  return codes.some((c) => perms.includes(c));
}
