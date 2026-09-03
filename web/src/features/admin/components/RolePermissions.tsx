import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApi } from "../services/adminApi";
import { rolesQuery, permissionsQuery } from "../hooks/adminQueries";
import { useState } from "react";
import { Button } from "@/shared/components/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { getApiErrorMessage } from "@/shared/api/errors";
import { permissionLabel, roleLabel } from "@/shared/lib/labels";
import { MODULE_LABELS } from "@/shared/lib/modules";
import { humanize } from "@/shared/lib/text";

export function RolePermissionEditor() {
  const qc = useQueryClient();
  const rolesQ = useQuery(rolesQuery);
  const permsQ = useQuery(permissionsQuery);
  const allPerms = permsQ.data ?? [];
  const permissionGroups = Object.entries(
    allPerms.reduce<Record<string, typeof allPerms>>((groups, permission) => {
      const module = permission.code.split(":", 1)[0];
      (groups[module] ??= []).push(permission);
      return groups;
    }, {})
  );
  const [selected, setSelected] = useState<string | null>(null);
  const [codes, setCodes] = useState<string[]>([]);

  const selRole = rolesQ.data?.find((r) => r.code === selected);

  const mut = useMutation({
    mutationFn: () => adminApi.updateRolePermissions(selected!, codes),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["roles"] }),
  });

  function toggle(code: string) {
    setCodes((prev) => (prev.includes(code) ? prev.filter((c) => c !== code) : [...prev, code]));
  }

  return (
    <div className="grid md:grid-cols-3 gap-4">
      <Card className="md:col-span-1">
        <CardHeader><CardTitle>Roles ({rolesQ.data?.length ?? "-"})</CardTitle></CardHeader>
        <CardContent className="space-y-1">
          {rolesQ.isLoading ? "Loading..." : rolesQ.data?.map((r) => (
            <button
              key={r.code}
              onClick={() => { setSelected(r.code); setCodes(r.permissions); }}
              className={`w-full text-left px-3 py-2 rounded text-sm ${selected === r.code ? "bg-primary text-white" : "hover:bg-muted"}`}
            >
              <div className="font-medium">{roleLabel(r.code)}</div>
              <div className="truncate text-xs opacity-70">{r.permissions.length} permissions</div>
            </button>
          ))}
        </CardContent>
      </Card>

      <Card className="md:col-span-2">
        <CardHeader><CardTitle>{selRole ? `Permissions for ${roleLabel(selRole.code)}` : "Permissions"}</CardTitle></CardHeader>
        <CardContent>
          {!selRole ? <div className="text-sm text-muted-foreground">Select a role left.</div> : (
            <div className="space-y-4">
              {permsQ.isLoading ? <div className="text-sm text-muted-foreground">Loading permissions...</div> : (
                <div className="grid gap-4 sm:grid-cols-2">
                  {permissionGroups.map(([module, permissions]) => (
                    <fieldset key={module} className="rounded-lg border border-border p-3">
                      <legend className="px-1 text-sm font-semibold">{MODULE_LABELS[module] ?? humanize(module)}</legend>
                      <div className="mt-1 space-y-2">
                        {permissions.map((permission) => (
                          <label key={permission.code} className="flex cursor-pointer items-start gap-2 rounded-md p-1.5 text-sm hover:bg-muted">
                            <input
                              type="checkbox"
                              className="mt-0.5"
                              checked={codes.includes(permission.code)}
                              onChange={() => toggle(permission.code)}
                            />
                            <span title={permission.code}>
                              {permissionLabel(permission.code)}
                            </span>
                          </label>
                        ))}
                      </div>
                    </fieldset>
                  ))}
                </div>
              )}
              <div className="flex gap-2">
                <Button onClick={() => mut.mutate()} disabled={mut.isPending}>{mut.isPending ? "Saving..." : "Save permissions"}</Button>
                <Button variant="outline" onClick={() => setCodes(selRole.permissions)}>Reset</Button>
              </div>
              {mut.isSuccess && <div className="text-sm text-green-600">Permissions saved successfully.</div>}
              {mut.isError && <div className="text-sm text-destructive">{getApiErrorMessage(mut.error, "Save failed")}</div>}
              <div className="text-xs text-muted-foreground">{codes.length} permissions selected</div>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
