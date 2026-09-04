import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { Badge } from "@/shared/components/badge";
import { getApiErrorMessage } from "@/shared/api/errors";
import { departmentLabel, permissionLabel, roleLabel } from "@/shared/lib/labels";
import { MODULE_LABELS } from "@/shared/lib/modules";
import { humanize } from "@/shared/lib/text";
import { useCurrentUser } from "../hooks/useCurrentUser";

export function UserProfile() {
  const { data: user, isLoading, isError, error } = useCurrentUser();

  if (isLoading) return <div className="text-sm text-muted-foreground">Loading...</div>;
  if (isError || !user) {
    return <div className="text-sm text-destructive">{getApiErrorMessage(error, "Could not load your profile")}</div>;
  }

  const initials = user.fullName.split(" ").map((part) => part[0]).slice(0, 2).join("").toUpperCase();
  // Permissions are grouped by the module prefix so the list reads as areas of
  // access rather than a flat wall of entries.
  const byModule = user.permissions.reduce<Record<string, string[]>>((groups, code) => {
    const module = code.split(":", 1)[0];
    (groups[module] ??= []).push(code);
    return groups;
  }, {});
  const modules = Object.entries(byModule).sort(([a], [b]) =>
    (MODULE_LABELS[a] ?? a).localeCompare(MODULE_LABELS[b] ?? b)
  );

  return (
    <div className="mx-auto max-w-3xl space-y-4">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">My profile</h1>
        <p className="mt-1 text-sm text-muted-foreground">Your account details and access.</p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Account details</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-wrap items-start gap-5">
          <div className="flex h-14 w-14 shrink-0 items-center justify-center rounded-full bg-primary text-lg font-semibold text-primary-foreground">
            {initials}
          </div>
          <dl className="grid min-w-0 flex-1 gap-x-8 gap-y-3 sm:grid-cols-3">
            <div>
              <dt className="text-xs font-medium text-muted-foreground">Full name</dt>
              <dd className="mt-0.5 break-words text-sm font-medium">{user.fullName}</dd>
            </div>
            <div>
              <dt className="text-xs font-medium text-muted-foreground">Username</dt>
              <dd className="mt-0.5 break-words text-sm">{user.username}</dd>
            </div>
            <div>
              <dt className="text-xs font-medium text-muted-foreground">Department</dt>
              <dd className="mt-0.5 break-words text-sm">{departmentLabel(user.department)}</dd>
            </div>
          </dl>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Assigned roles</CardTitle>
          <p className="text-sm text-muted-foreground">Roles determine which parts of PAS you can use.</p>
        </CardHeader>
        <CardContent>
          {user.roles.length === 0 ? (
            <p className="text-sm text-muted-foreground">No roles assigned.</p>
          ) : (
            <div className="flex flex-wrap gap-2">
              {user.roles.map((role) => (
                <Badge key={role} variant="secondary">{roleLabel(role)}</Badge>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Your access</CardTitle>
          <p className="text-sm text-muted-foreground">
            These are the actions currently available to you. Contact an administrator if you need additional access.
          </p>
        </CardHeader>
        <CardContent>
          {modules.length === 0 ? (
            <p className="text-sm text-muted-foreground">No access has been granted yet.</p>
          ) : (
            <dl className="grid gap-4 sm:grid-cols-2">
              {modules.map(([module, codes]) => (
                <div key={module}>
                  <dt className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                    {MODULE_LABELS[module] ?? humanize(module)}
                  </dt>
                  <dd>
                    <ul className="mt-1 space-y-0.5 text-sm">
                      {codes.map((code) => (
                        <li key={code}>{permissionLabel(code)}</li>
                      ))}
                    </ul>
                  </dd>
                </div>
              ))}
            </dl>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
