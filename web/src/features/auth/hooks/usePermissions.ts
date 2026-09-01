import { useAuthStore } from "@/features/auth/store/authStore";

// Code checks permissions, never roles (00-registry.md:265)
// For identity section, we derive permissions from roles via a static map for UI gating only.
// Real enforcement is server-side via PermissionCache (Redis perm:role:*). This hook is for hiding UI.
const ROLE_PERMISSIONS: Record<string, string[]> = {
  SALES_OFFICER: [
    "customer:read",
    "customer:write",
    "contract:read",
    "contract:write",
    "addendum:read",
    "addendum:write",
    "pricelist:read",
    "pricelist:write",
    "volume:read",
    "statement:read",
    "esign:send",
    "esign:cancel",
  ],
  SALES_MANAGER: [
    "customer:read",
    "customer:write",
    "contract:read",
    "contract:write",
    "contract:cancel_active",
    "addendum:read",
    "addendum:write",
    "pricelist:read",
    "pricelist:write",
    "volume:read",
    "statement:read",
    "esign:send",
    "esign:cancel",
    "approval:act",
  ],
  LEGAL_REVIEWER: ["customer:read", "contract:read", "addendum:read", "pricelist:read", "approval:act"],
  ACCOUNTANT: [
    "customer:read",
    "contract:read",
    "pricelist:read",
    "volume:read",
    "statement:read",
    "statement:write",
    "esign:send",
    "esign:cancel",
    "approval:act",
  ],
  OPS_OFFICER: ["contract:read", "volume:read", "volume:write", "volume:lock_period"],
  DIRECTOR: [
    "customer:read",
    "contract:read",
    "addendum:read",
    "pricelist:read",
    "volume:read",
    "statement:read",
    "approval:act",
  ],
  SYSTEM_ADMIN: [
    "customer:read",
    "contract:read",
    "addendum:read",
    "pricelist:read",
    "volume:read",
    "statement:read",
    "user:manage",
    "workflow:configure",
    "doctype:configure",
    "audit:view_all",
  ],
};

export function usePermissions(): string[] {
  const user = useAuthStore((s) => s.user);
  if (!user) return [];
  const perms = new Set<string>();
  for (const role of user.roles) {
    const arr = ROLE_PERMISSIONS[role] ?? [];
    for (const p of arr) perms.add(p);
  }
  // everyone has notification:read per registry
  perms.add("notification:read");
  return [...perms];
}

export function useHasPermission(code: string): boolean {
  const perms = usePermissions();
  return perms.includes(code);
}

export function useHasAnyPermission(codes: string[]): boolean {
  const perms = usePermissions();
  return codes.some((c) => perms.includes(c));
}
