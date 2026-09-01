import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApi } from "../services/adminApi";
import { rolesQuery } from "../hooks/adminQueries";
import { useState } from "react";
import { Button } from "@/shared/components/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { Badge } from "@/shared/components/badge";
import { getApiErrorMessage } from "@/shared/api/errors";

const ALL_PERMS = [
  "customer:read","customer:write","contract:read","contract:write","contract:cancel_active",
  "addendum:read","addendum:write","pricelist:read","pricelist:write",
  "volume:read","volume:write","volume:lock_period","volume:edit_locked",
  "statement:read","statement:write","statement:cancel_approved","approval:act",
  "esign:send","esign:cancel","user:manage","workflow:configure","doctype:configure","audit:view_all",
];

export function RolePermissionEditor() {
  const qc = useQueryClient();
  const rolesQ = useQuery(rolesQuery);
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
              <div className="font-medium">{r.code}</div>
              <div className="text-xs opacity-70 truncate">{r.name} · {r.permissions.length} perms</div>
            </button>
          ))}
        </CardContent>
      </Card>

      <Card className="md:col-span-2">
        <CardHeader><CardTitle>Permissions {selRole ? `- ${selRole.code}` : ""}</CardTitle></CardHeader>
        <CardContent>
          {!selRole ? <div className="text-sm text-muted-foreground">Select a role left.</div> : (
            <div className="space-y-4">
              <div className="flex flex-wrap gap-2">
                {ALL_PERMS.map((p) => (
                  <label key={p} className="flex items-center gap-2 border rounded px-2 py-1 text-sm cursor-pointer">
                    <input type="checkbox" checked={codes.includes(p)} onChange={() => toggle(p)} />
                    {p}
                  </label>
                ))}
              </div>
              <div className="flex gap-2">
                <Button onClick={() => mut.mutate()} disabled={mut.isPending}>{mut.isPending ? "Saving..." : "Save permissions"}</Button>
                <Button variant="outline" onClick={() => setCodes(selRole.permissions)}>Reset</Button>
              </div>
              {mut.isSuccess && <div className="text-sm text-green-600">Saved. Permission cache revokes within 1h or next admin edit (M1).</div>}
              {mut.isError && <div className="text-sm text-destructive">{getApiErrorMessage(mut.error, "Save failed")}</div>}
              <div className="text-xs text-muted-foreground">PUT /roles/{"{code}"}/permissions - locks role FOR UPDATE (db-identity). No roles in bundle for volume:edit_locked / statement:cancel_approved by design.</div>
              <div className="flex flex-wrap gap-1 pt-2">{codes.map((c) => <Badge key={c} variant="secondary">{c}</Badge>)}</div>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
