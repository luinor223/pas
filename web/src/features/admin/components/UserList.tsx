import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import type { CreateUserRequest, UpdateUserRequest, UserResponse } from "../types/adminTypes";
import { adminApi } from "../services/adminApi";
import { usersQuery, rolesQuery, departmentsQuery } from "../hooks/adminQueries";
import { useState, useMemo, useEffect } from "react";
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
import { RowMenu } from "@/shared/components/row-menu";
import { departmentLabel, roleLabel } from "@/shared/lib/labels";
import { formatDateTime } from "@/shared/lib/format";
import { ConfirmDialog } from "@/shared/components/confirm-dialog";

const createSchema = z.object({
  username: z.string().min(2),
  email: z.string().email(),
  password: z.string().min(8),
  fullName: z.string().min(1),
  departmentCode: z.string().min(1),
  roleCodes: z.array(z.string()).min(1, "Select at least one role"),
});

const editSchema = z.object({
  fullName: z.string().min(1, "Required"),
  email: z.string().email(),
  departmentCode: z.string().min(1, "Required"),
});

type FormCreate = z.infer<typeof createSchema>;
type FormEdit = z.infer<typeof editSchema>;

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
  const [editUserId, setEditUserId] = useState<string | null>(null);
  const [confirmDisable, setConfirmDisable] = useState<UserResponse | null>(null);

  const usersQ = useQuery(usersQuery);
  const rolesQ = useQuery(rolesQuery);
  const deptsQ = useQuery(departmentsQuery);

  const users = usersQ.data ?? [];
  const roles = rolesQ.data ?? [];
  const departments = deptsQ.data ?? [];

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

  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateUserRequest }) => adminApi.updateUser(id, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["users"] });
      setEditUserId(null);
    },
  });

  const { register, handleSubmit, reset, watch, setValue, formState: { errors } } = useForm<FormCreate>({
    resolver: zodResolver(createSchema),
    defaultValues: { username: "", email: "", password: "", fullName: "", departmentCode: "", roleCodes: [] },
  });
  const watchedRoles = watch("roleCodes");

  const editUser = users.find((u) => u.id === editRolesId);
  const profileUser = users.find((u) => u.id === editUserId);
  const [editCodes, setEditCodes] = useState<string[]>([]);

  const { register: regEdit, handleSubmit: submitEdit, reset: resetEdit, formState: { errors: editErrors } } = useForm<FormEdit>({
    resolver: zodResolver(editSchema),
    defaultValues: { fullName: "", email: "", departmentCode: "" },
  });

  // Default department/role after real data loads (no hardcode).
  useEffect(() => {
    if (departments.length > 0 && !watch("departmentCode")) {
      setValue("departmentCode", departments[0].code);
    }
  }, [departments, watch, setValue]);
  useEffect(() => {
    if (roles.length > 0 && watch("roleCodes").length === 0) {
      setValue("roleCodes", [roles[0].code], { shouldValidate: false });
    }
  }, [roles, watch, setValue]);

  useEffect(() => {
    if (profileUser) {
      resetEdit({ fullName: profileUser.fullName, email: profileUser.email, departmentCode: profileUser.department });
    }
  }, [profileUser, resetEdit]);

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
    { accessorKey: "department", header: "DEPARTMENT", cell: ({ row }) => departmentLabel(row.original.department) },
    {
      id: "roles",
      header: "ROLE",
      enableSorting: false,
      cell: ({ row }) => <div className="flex flex-wrap gap-1">{row.original.roles.map((code) => <Badge key={code} variant="secondary" className="text-xs">{roleLabel(code)}</Badge>)}</div>,
    },
    { accessorKey: "status", header: "STATUS", cell: ({ row }) => <StatusBadge status={row.original.status} /> },
    {
      accessorKey: "lastLoginAt",
      header: "LAST LOGIN",
      cell: ({ row }) => <span className="text-xs">{formatDateTime(row.original.lastLoginAt)}</span>,
    },
    {
      id: "actions",
      header: () => <span className="sr-only">Actions</span>,
      enableSorting: false,
      cell: ({ row }) => {
        const u = row.original;
        const isSelf = u.id === currentUser?.id;
        const items = [
          { label: "Edit user", onClick: () => setEditUserId(u.id) },
          { label: "Manage roles", onClick: () => { setEditRolesId(u.id); setEditCodes(u.roles); } },
          u.status === "ACTIVE"
            ? { label: isSelf ? "Disable my account" : "Disable user", onClick: () => setConfirmDisable(u), danger: true }
            : { label: "Enable user", onClick: () => toggleMut.mutate({ id: u.id, enable: true }) },
        ];
        return (
          <div className="flex justify-end">
            <RowMenu items={items} title={`Actions for ${u.fullName}`} />
          </div>
        );
      },
    },
  ], [currentUser?.id, roles]);

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle>Users ({filtered.length}/{users.length})</CardTitle>
          <Button onClick={() => setOpenCreate(true)}>+ New User</Button>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex flex-col lg:flex-row gap-2">
            <Input placeholder="Search users..." value={q} onChange={(e) => setQ(e.target.value)} className="lg:max-w-sm flex-1" />
            <Select className="w-full lg:w-[170px]" value={dept} onChange={(e) => setDept(e.target.value)}>
              <option value="All">Department: All</option>
              {deptsQ.isLoading ? <option disabled>Loading...</option> : departments.map((d) => <option key={d.code} value={d.code}>{departmentLabel(d.code)}</option>)}
            </Select>
            <Select className="w-full lg:w-[190px]" value={role} onChange={(e) => setRole(e.target.value)}>
              <option value="All">Role: All</option>
              {roles.map((r) => <option key={r.code} value={r.code}>{roleLabel(r.code)}</option>)}
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
          {deptsQ.isError && <div className="text-xs text-destructive">Failed to load departments: {getApiErrorMessage(deptsQ.error, "")}</div>}
        </CardContent>
      </Card>

      {/* Create dialog */}
      <Dialog open={openCreate} onOpenChange={setOpenCreate}>
        <DialogContent className="max-w-lg overflow-hidden p-0">
          <DialogHeader className="shrink-0 px-6 pt-6"><DialogTitle>Create user</DialogTitle></DialogHeader>
          <form onSubmit={handleSubmit((d) => createMut.mutate(d))} className="flex min-h-0 flex-1 flex-col">
            <div className="min-h-0 space-y-3 overflow-y-auto px-6 pb-4">
            <div><Label>Username</Label><Input {...register("username")} />{errors.username && <p className="text-xs text-destructive">{errors.username.message}</p>}</div>
            <div><Label>Email</Label><Input {...register("email")} />{errors.email && <p className="text-xs text-destructive">{errors.email.message}</p>}</div>
            <div><Label>Password (min 8)</Label><Input type="password" {...register("password")} />{errors.password && <p className="text-xs text-destructive">{errors.password.message}</p>}</div>
            <div><Label>Full name</Label><Input {...register("fullName")} />{errors.fullName && <p className="text-xs text-destructive">{errors.fullName.message}</p>}</div>
            <div><Label>Department</Label><Select {...register("departmentCode")}>{deptsQ.isLoading ? <option disabled>Loading...</option> : departments.map((d) => <option key={d.code} value={d.code}>{departmentLabel(d.code)}</option>)}</Select></div>
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
                    <span className="font-medium">{roleLabel(r.code)}</span>
                  </label>
                ))}
              </div>
              {errors.roleCodes && <p className="text-xs text-destructive">{errors.roleCodes.message}</p>}
            </div>
            {createMut.isError && <div className="text-sm text-destructive">{getApiErrorMessage(createMut.error, "Create failed")}</div>}
            </div>
            <DialogFooter className="mt-0 shrink-0 border-t bg-card px-6 py-4">
              <Button type="button" variant="outline" onClick={() => { setOpenCreate(false); reset(); }}>Cancel</Button>
              <Button type="submit" disabled={createMut.isPending}>{createMut.isPending ? "Creating..." : "Create"}</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Edit profile dialog */}
      <Dialog open={!!editUserId} onOpenChange={(o) => !o && setEditUserId(null)}>
        <DialogContent>
          <DialogHeader><DialogTitle>Edit user - {profileUser?.username}</DialogTitle></DialogHeader>
          <form onSubmit={submitEdit((d) => editUserId && updateMut.mutate({ id: editUserId, data: d }))} className="space-y-3">
            <div><Label>Full name</Label><Input {...regEdit("fullName")} />{editErrors.fullName && <p className="text-xs text-destructive">{editErrors.fullName.message}</p>}</div>
            <div><Label>Email</Label><Input {...regEdit("email")} />{editErrors.email && <p className="text-xs text-destructive">{editErrors.email.message}</p>}</div>
            <div><Label>Department</Label><Select {...regEdit("departmentCode")}>{deptsQ.isLoading ? <option disabled>Loading...</option> : departments.map((d) => <option key={d.code} value={d.code}>{departmentLabel(d.code)}</option>)}</Select></div>
            {updateMut.isError && <div className="text-sm text-destructive">{getApiErrorMessage(updateMut.error, "Update failed")}</div>}
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setEditUserId(null)}>Cancel</Button>
              <Button type="submit" disabled={updateMut.isPending}>{updateMut.isPending ? "Saving..." : "Save"}</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Edit roles dialog */}
      <Dialog open={!!editRolesId} onOpenChange={(o) => !o && setEditRolesId(null)}>
        <DialogContent>
          <DialogHeader><DialogTitle>Manage roles for {editUser?.fullName}</DialogTitle></DialogHeader>
          <div className="space-y-2">
            <Label>Select roles</Label>
            <div className="grid grid-cols-1 gap-2 border rounded p-3 max-h-64 overflow-auto">
              {roles.map((r) => (
                <label key={r.code} className="flex items-center gap-2 text-sm cursor-pointer hover:bg-muted px-2 py-1 rounded">
                  <input type="checkbox" checked={editCodes.includes(r.code)} onChange={(e) => setEditCodes(e.target.checked ? [...editCodes, r.code] : editCodes.filter((c) => c !== r.code))} />
                  <span className="font-medium">{roleLabel(r.code)}</span>
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

      <ConfirmDialog
        open={!!confirmDisable}
        title="Disable user?"
        body={confirmDisable?.id === currentUser?.id ? (
          <div className="space-y-2">
            <p>You are about to <span className="font-semibold text-destructive">disable your own account</span> ({confirmDisable?.username}).</p>
            <p className="text-muted-foreground">You will be signed out immediately and unable to sign in again until another administrator re-enables your account.</p>
          </div>
        ) : (
          <p>Disable <span className="font-medium">{confirmDisable?.fullName}</span> ({confirmDisable?.username})? They will be signed out and unable to sign in until their account is re-enabled.</p>
        )}
        confirmLabel="Disable user"
        pendingLabel="Disabling..."
        pending={toggleMut.isPending}
        error={toggleMut.isError ? toggleMut.error : undefined}
        onConfirm={() => confirmDisable && toggleMut.mutate({ id: confirmDisable.id, enable: false })}
        onCancel={() => setConfirmDisable(null)}
      />
    </div>
  );
}
