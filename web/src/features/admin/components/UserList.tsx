import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import type { CreateUserRequest, UserResponse } from "../types/adminTypes";
import { adminApi } from "../services/adminApi";
import { usersQuery, rolesQuery } from "../hooks/adminQueries";
import { useState, useMemo } from "react";
import { Button } from "@/shared/components/button";
import { Input } from "@/shared/components/input";
import { Label } from "@/shared/components/label";
import { Select } from "@/shared/components/select";
import { Badge } from "@/shared/components/badge";
import { StatusBadge } from "@/shared/components/status-badge";
import { DataTable } from "@/shared/components/data-table";
import type { ColumnDef } from "@tanstack/react-table";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from "@/shared/components/dialog";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useCurrentUser } from "@/features/auth/hooks/useCurrentUser";
import { getApiErrorMessage } from "@/shared/api/errors";
import { useNavigate } from "@tanstack/react-router";

const createSchema = z.object({
  username: z.string().min(2),
  email: z.string().email(),
  password: z.string().min(8),
  fullName: z.string().min(1),
  departmentCode: z.string().min(1),
  roleCodes: z.array(z.string()).min(1, "Select at least one role"),
});

type FormCreate = z.infer<typeof createSchema>;

const DEPARTMENTS = ["SALES", "LEGAL", "ACCOUNTING", "OPERATIONS", "BOARD", "IT"];

export function UserTable() {
  const qc = useQueryClient();
  const currentUser = useCurrentUser().data;
  const navigate = useNavigate();
  const [q, setQ] = useState("");
  const [dept, setDept] = useState("All");
  const [role, setRole] = useState("All");
  const [status, setStatus] = useState("All");
  const [openCreate, setOpenCreate] = useState(false);
  const [editRolesId, setEditRolesId] = useState<string | null>(null);
  const [confirmDisable, setConfirmDisable] = useState<UserResponse | null>(null);

  const usersQ = useQuery(usersQuery);
  const rolesQ = useQuery(rolesQuery);

  const users = usersQ.data ?? [];
  const roles = rolesQ.data ?? [];

  const filtered = useMemo(() => {
    return users.filter((u) => {
      if (dept !== "All" && u.department !== dept) return false;
      if (status !== "All" && u.status !== status) return false;
      if (role !== "All" && !u.roles.includes(role)) return false;
      if (q) {
        const s = q.toLowerCase();
        if (![u.username, u.email, u.fullName].some((v) => v.toLowerCase().includes(s))) return false;
      }
      return true;
    });
  }, [users, dept, role, status, q]);

  const createMut = useMutation({
    mutationFn: (data: CreateUserRequest) => adminApi.createUser(data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["users"] });
      setOpenCreate(false);
      reset();
    },
  });

  const toggleMut = useMutation({
    mutationFn: ({ id, enable }: { id: string; enable: boolean }) => adminApi.setUserEnabled(id, enable),
    onSuccess: (_data, vars) => {
      qc.invalidateQueries({ queryKey: ["users"] });
      setConfirmDisable(null);
      // self-disable: access token still valid for 15m, but UI must force re-login
      if (!vars.enable && vars.id === currentUser?.id) {
        qc.clear();
        navigate({ to: "/login" });
      }
    },
  });

  const setRolesMut = useMutation({
    mutationFn: ({ id, codes }: { id: string; codes: string[] }) => adminApi.updateUserRoles(id, codes),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["users"] });
      setEditRolesId(null);
    },
  });

  const { register, handleSubmit, reset, watch, setValue, formState: { errors } } = useForm<FormCreate>({
    resolver: zodResolver(createSchema),
    defaultValues: { username: "", email: "", password: "", fullName: "", departmentCode: "SALES", roleCodes: ["SALES_OFFICER"] },
  });
  const watchedRoles = watch("roleCodes");

  const editUser = users.find((u) => u.id === editRolesId);
  const [editCodes, setEditCodes] = useState<string[]>([]);

  const columns = useMemo<ColumnDef<UserResponse>[]>(() => [
    {
      accessorKey: "fullName",
      header: "USER",
      cell: ({ row }) => {
        const u = row.original;
        const isSelf = u.id === currentUser?.id;
        return (
          <div>
            <div className="font-medium">{u.fullName} {isSelf && <span className="text-xs text-muted-foreground">(you)</span>}</div>
            <div className="text-xs text-muted-foreground">{u.username} · {u.email}</div>
          </div>
        );
      },
    },
    { accessorKey: "department", header: "DEPARTMENT" },
    {
      id: "roles",
      header: "ROLE",
      enableSorting: false,
      cell: ({ row }) => <div className="flex flex-wrap gap-1">{row.original.roles.map((r) => <Badge key={r} variant="secondary" className="text-xs">{r}</Badge>)}</div>,
    },
    { accessorKey: "status", header: "STATUS", cell: ({ row }) => <StatusBadge status={row.original.status} /> },
    {
      accessorKey: "lastLoginAt",
      header: "LAST LOGIN",
      cell: ({ row }) => <span className="text-xs">{row.original.lastLoginAt ? new Date(row.original.lastLoginAt).toLocaleString() : "-"}</span>,
    },
    {
      id: "actions",
      header: "Actions",
      enableSorting: false,
      cell: ({ row }) => {
        const u = row.original;
        const isSelf = u.id === currentUser?.id;
        return (
          <div className="space-x-1">
            <Button size="sm" variant="outline" onClick={() => { setEditRolesId(u.id); setEditCodes(u.roles); }}>Roles</Button>
            {u.status === "ACTIVE" ? (
              <Button size="sm" variant="destructive" onClick={() => setConfirmDisable(u)} title={isSelf ? "You are about to disable yourself" : undefined}>Disable</Button>
            ) : (
              <Button size="sm" onClick={() => toggleMut.mutate({ id: u.id, enable: true })}>Enable</Button>
            )}
          </div>
        );
      },
    },
  ], [currentUser?.id]);

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle>Users ({filtered.length}/{users.length})</CardTitle>
          <Button onClick={() => setOpenCreate(true)}>+ New User</Button>
        </CardHeader>
        <CardContent className="space-y-3">
          {/* single row filters - wraps on mobile but not full-line per filter */}
          <div className="flex flex-col lg:flex-row gap-2">
            <Input placeholder="Search users..." value={q} onChange={(e) => setQ(e.target.value)} className="lg:max-w-sm flex-1" />
            <Select className="w-full lg:w-[170px]" value={dept} onChange={(e) => setDept(e.target.value)}>
              <option value="All">Department: All</option>
              {DEPARTMENTS.map((d) => <option key={d} value={d}>{d}</option>)}
            </Select>
            <Select className="w-full lg:w-[190px]" value={role} onChange={(e) => setRole(e.target.value)}>
              <option value="All">Role: All</option>
              {roles.map((r) => <option key={r.code} value={r.code}>{r.code}</option>)}
            </Select>
            <Select className="w-full lg:w-[160px]" value={status} onChange={(e) => setStatus(e.target.value)}>
              <option value="All">Status: All</option>
              <option value="ACTIVE">ACTIVE</option>
              <option value="DISABLED">DISABLED</option>
            </Select>
          </div>

          {usersQ.isLoading ? (
            <div className="text-sm text-muted-foreground">Loading...</div>
          ) : usersQ.isError ? (
            <div className="text-sm text-destructive">Failed to load users: {(usersQ.error as { message: string })?.message}</div>
          ) : (
            <DataTable
              columns={columns}
              data={filtered}
              emptyMessage="No users"
              rowClassName={(u) => (u.id === currentUser?.id ? "bg-blue-50/50" : undefined)}
            />
          )}
        </CardContent>
      </Card>

      {/* Create dialog - checkbox roles */}
      <Dialog open={openCreate} onOpenChange={setOpenCreate}>
        <DialogContent className="max-w-lg">
          <DialogHeader><DialogTitle>Create user</DialogTitle></DialogHeader>
          <form onSubmit={handleSubmit((d) => createMut.mutate(d))} className="space-y-3">
            <div><Label>Username</Label><Input {...register("username")} />{errors.username && <p className="text-xs text-destructive">{errors.username.message}</p>}</div>
            <div><Label>Email</Label><Input {...register("email")} />{errors.email && <p className="text-xs text-destructive">{errors.email.message}</p>}</div>
            <div><Label>Password (min 8)</Label><Input type="password" {...register("password")} />{errors.password && <p className="text-xs text-destructive">{errors.password.message}</p>}</div>
            <div><Label>Full name</Label><Input {...register("fullName")} />{errors.fullName && <p className="text-xs text-destructive">{errors.fullName.message}</p>}</div>
            <div><Label>Department</Label><Select {...register("departmentCode")}>{DEPARTMENTS.map((d) => <option key={d} value={d}>{d}</option>)}</Select></div>
            <div>
              <Label>Roles</Label>
              <div className="grid grid-cols-2 gap-2 border rounded p-3 max-h-48 overflow-auto">
                {roles.map((r) => (
                  <label key={r.code} className="flex items-start gap-2 text-sm cursor-pointer">
                    <input
                      type="checkbox"
                      checked={watchedRoles.includes(r.code)}
                      onChange={(e) => {
                        const next = e.target.checked ? [...watchedRoles, r.code] : watchedRoles.filter((c) => c !== r.code);
                        setValue("roleCodes", next, { shouldValidate: true });
                      }}
                      className="mt-0.5"
                    />
                    <span><span className="font-medium">{r.code}</span><span className="text-xs text-muted-foreground block">{r.name}</span></span>
                  </label>
                ))}
              </div>
              {errors.roleCodes && <p className="text-xs text-destructive">{errors.roleCodes.message}</p>}
            </div>
            {createMut.isError && <div className="text-sm text-destructive">{getApiErrorMessage(createMut.error, "Create failed")}</div>}
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => { setOpenCreate(false); reset(); }}>Cancel</Button>
              <Button type="submit" disabled={createMut.isPending}>{createMut.isPending ? "Creating..." : "Create"}</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Edit roles dialog - checkboxes */}
      <Dialog open={!!editRolesId} onOpenChange={(o) => !o && setEditRolesId(null)}>
        <DialogContent>
          <DialogHeader><DialogTitle>Edit roles - {editUser?.username}</DialogTitle></DialogHeader>
          <div className="space-y-2">
            <Label>Select roles</Label>
            <div className="grid grid-cols-1 gap-2 border rounded p-3 max-h-64 overflow-auto">
              {roles.map((r) => (
                <label key={r.code} className="flex items-center gap-2 text-sm cursor-pointer hover:bg-muted px-2 py-1 rounded">
                  <input type="checkbox" checked={editCodes.includes(r.code)} onChange={(e) => setEditCodes(e.target.checked ? [...editCodes, r.code] : editCodes.filter((c) => c !== r.code))} />
                  <span className="font-medium">{r.code}</span>
                  <span className="text-xs text-muted-foreground">- {r.name}</span>
                  {editUser?.roles.includes(r.code) && <Badge variant="secondary" className="ml-auto text-xs">current</Badge>}
                </label>
              ))}
            </div>
            {editCodes.length === 0 && <p className="text-xs text-destructive">Select at least one role</p>}
            {setRolesMut.isError && <div className="text-sm text-destructive">{getApiErrorMessage(setRolesMut.error, "Save failed")}</div>}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setEditRolesId(null)}>Cancel</Button>
            <Button onClick={() => editRolesId && setRolesMut.mutate({ id: editRolesId, codes: editCodes })} disabled={setRolesMut.isPending || editCodes.length === 0}>Save</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Disable confirm */}
      <Dialog open={!!confirmDisable} onOpenChange={(o) => !o && setConfirmDisable(null)}>
        <DialogContent>
          <DialogHeader><DialogTitle>Disable user?</DialogTitle></DialogHeader>
          <div className="text-sm">
            {confirmDisable?.id === currentUser?.id ? (
              <div className="space-y-2">
                <p>You are about to <span className="font-semibold text-destructive">disable your own account</span> (<code>{confirmDisable?.username}</code>).</p>
                <p className="text-muted-foreground">You will be logged out immediately and your refresh tokens revoked. Continue?</p>
              </div>
            ) : (
              <p>Disable <span className="font-medium">{confirmDisable?.fullName}</span> (<code>{confirmDisable?.username}</code>)? Their refresh tokens will be revoked.</p>
            )}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setConfirmDisable(null)}>Cancel</Button>
            <Button variant="destructive" disabled={toggleMut.isPending} onClick={() => confirmDisable && toggleMut.mutate({ id: confirmDisable.id, enable: false })}>
              {toggleMut.isPending ? "Disabling..." : "Confirm Disable"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
