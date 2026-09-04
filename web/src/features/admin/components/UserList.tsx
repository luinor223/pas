import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import type { CreateUserRequest, UpdateUserRequest, UserResponse } from "../types/adminTypes";
import { adminApi } from "../services/adminApi";
import { usersQuery, rolesQuery, departmentsQuery } from "../hooks/adminQueries";
import { useState, useMemo, useEffect } from "react";
import { ChevronRight } from "lucide-react";
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
import { FilterBar } from "@/shared/components/filter-bar";
import { SearchInput } from "@/shared/components/search-input";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useCurrentUser } from "@/features/auth/hooks/useCurrentUser";
import { getApiErrorMessage } from "@/shared/api/errors";
import { useNavigate } from "@tanstack/react-router";
import { departmentLabel, roleLabel } from "@/shared/lib/labels";
import { formatDateTime } from "@/shared/lib/format";

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
type DetailMode = "view" | "edit" | "roles" | "toggle";

export function UserTable() {
  const qc = useQueryClient();
  const currentUser = useCurrentUser().data;
  const navigate = useNavigate();
  const [q, setQ] = useState("");
  const [dept, setDept] = useState("All");
  const [role, setRole] = useState("All");
  const [status, setStatus] = useState("All");
  const [openCreate, setOpenCreate] = useState(false);
  const [detailId, setDetailId] = useState<string | null>(null);
  const [detailMode, setDetailMode] = useState<DetailMode>("view");

  const usersQ = useQuery(usersQuery);
  const rolesQ = useQuery(rolesQuery);
  const deptsQ = useQuery(departmentsQuery);

  const users = usersQ.data ?? [];
  const roles = rolesQ.data ?? [];
  const departments = deptsQ.data ?? [];
  const detailUser = users.find((u) => u.id === detailId) ?? null;
  const isSelf = detailUser?.id === currentUser?.id;

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
      if (!vars.enable && vars.id === currentUser?.id) {
        qc.clear();
        navigate({ to: "/login" });
      } else {
        setDetailId(null);
      }
    },
  });

  const setRolesMut = useMutation({
    mutationFn: ({ id, codes }: { id: string; codes: string[] }) => adminApi.updateUserRoles(id, codes),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["users"] });
      setDetailMode("view");
    },
  });

  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateUserRequest }) => adminApi.updateUser(id, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["users"] });
      setDetailMode("view");
    },
  });

  const { register, handleSubmit, reset, watch, setValue, formState: { errors } } = useForm<FormCreate>({
    resolver: zodResolver(createSchema),
    defaultValues: { username: "", email: "", password: "", fullName: "", departmentCode: "", roleCodes: [] },
  });
  const watchedRoles = watch("roleCodes");

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

  function openDetail(u: UserResponse) {
    resetEdit({ fullName: u.fullName, email: u.email, departmentCode: u.department });
    setEditCodes(u.roles);
    updateMut.reset();
    setRolesMut.reset();
    toggleMut.reset();
    setDetailMode("view");
    setDetailId(u.id);
  }

  const columns = useMemo<ColumnDef<UserResponse>[]>(() => [
    {
      accessorKey: "fullName",
      header: "USER",
      cell: ({ row }) => {
        const u = row.original;
        const self = u.id === currentUser?.id;
        return (
          <div>
            <div className="font-medium">{u.fullName} {self && <span className="text-xs text-muted-foreground">(you)</span>}</div>
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
      id: "open",
      header: () => <span className="sr-only">View details</span>,
      enableSorting: false,
      cell: () => <span className="text-muted-foreground"><ChevronRight size={16} aria-hidden="true" /></span>,
    },
  ], [currentUser?.id]);

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <CardTitle>Users ({filtered.length}/{users.length})</CardTitle>
          <div className="flex items-center gap-2">
            <SearchInput
              className="w-56 lg:w-72"
              label="Search users"
              placeholder="Search users..."
              value={q}
              onChange={setQ}
            />
            <Button onClick={() => setOpenCreate(true)}>+ New User</Button>
          </div>
        </CardHeader>
        <CardContent className="space-y-3">
          <FilterBar>
            <Select className="w-full sm:w-44" aria-label="Filter by department" value={dept} onChange={(e) => setDept(e.target.value)}>
              <option value="All">Department: All</option>
              {deptsQ.isLoading ? <option disabled>Loading...</option> : departments.map((d) => <option key={d.code} value={d.code}>{departmentLabel(d.code)}</option>)}
            </Select>
            <Select className="w-full sm:w-48" aria-label="Filter by role" value={role} onChange={(e) => setRole(e.target.value)}>
              <option value="All">Role: All</option>
              {roles.map((r) => <option key={r.code} value={r.code}>{roleLabel(r.code)}</option>)}
            </Select>
            <Select className="w-full sm:w-44" aria-label="Filter by status" value={status} onChange={(e) => setStatus(e.target.value)}>
              <option value="All">Status: All</option>
              <option value="ACTIVE">ACTIVE</option>
              <option value="DISABLED">DISABLED</option>
            </Select>
          </FilterBar>

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
              onRowClick={openDetail}
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

      {/* Row-click detail: full info, inline edit, roles, disable */}
      <Dialog open={!!detailId} onOpenChange={(o) => !o && setDetailId(null)}>
        <DialogContent className="max-w-lg">
          {detailUser && (
            <>
              <DialogHeader>
                <DialogTitle>{detailUser.fullName} {isSelf && <span className="text-sm font-normal text-muted-foreground">(you)</span>}</DialogTitle>
                <p className="text-sm text-muted-foreground">{detailUser.username} · {detailUser.email}</p>
              </DialogHeader>

              {detailMode === "view" && (
                <div className="space-y-4">
                  <dl className="grid gap-x-8 gap-y-3 rounded-md border bg-muted/20 p-4 text-sm sm:grid-cols-2">
                    <DetailItem label="Username" value={detailUser.username} />
                    <DetailItem label="Email" value={detailUser.email} />
                    <DetailItem label="Department" value={departmentLabel(detailUser.department)} />
                    <DetailItem label="Status" value={<StatusBadge status={detailUser.status} />} />
                    <DetailItem label="Last login" value={formatDateTime(detailUser.lastLoginAt)} />
                    <div className="sm:col-span-2">
                      <dt className="text-xs text-muted-foreground">Roles</dt>
                      <dd className="mt-1 flex flex-wrap gap-1">
                        {detailUser.roles.map((code) => <Badge key={code} variant="secondary" className="text-xs">{roleLabel(code)}</Badge>)}
                      </dd>
                    </div>
                  </dl>
                  <DialogFooter className="flex-col gap-2 sm:flex-row">
                    {detailUser.status === "ACTIVE" ? (
                      <Button type="button" variant="destructive" onClick={() => setDetailMode("toggle")}>
                        {isSelf ? "Disable my account" : "Disable user"}
                      </Button>
                    ) : (
                      <Button
                        type="button"
                        disabled={toggleMut.isPending}
                        onClick={() => toggleMut.mutate({ id: detailUser.id, enable: true })}
                      >
                        {toggleMut.isPending ? "Enabling..." : "Enable user"}
                      </Button>
                    )}
                    <span className="flex-1" />
                    <Button type="button" variant="outline" onClick={() => setDetailId(null)}>Close</Button>
                    <Button type="button" variant="outline" onClick={() => setDetailMode("roles")}>Manage roles</Button>
                    <Button type="button" variant="outline" onClick={() => setDetailMode("edit")}>Edit profile</Button>
                  </DialogFooter>
                  {toggleMut.isError && <div className="text-sm text-destructive">{getApiErrorMessage(toggleMut.error, "Action failed")}</div>}
                </div>
              )}

              {detailMode === "edit" && (
                <form onSubmit={submitEdit((d) => updateMut.mutate({ id: detailUser.id, data: d }))} className="space-y-3">
                  <div><Label>Full name</Label><Input {...regEdit("fullName")} />{editErrors.fullName && <p className="text-xs text-destructive">{editErrors.fullName.message}</p>}</div>
                  <div><Label>Email</Label><Input {...regEdit("email")} />{editErrors.email && <p className="text-xs text-destructive">{editErrors.email.message}</p>}</div>
                  <div><Label>Department</Label><Select {...regEdit("departmentCode")}>{deptsQ.isLoading ? <option disabled>Loading...</option> : departments.map((d) => <option key={d.code} value={d.code}>{departmentLabel(d.code)}</option>)}</Select></div>
                  {updateMut.isError && <div className="text-sm text-destructive">{getApiErrorMessage(updateMut.error, "Update failed")}</div>}
                  <DialogFooter>
                    <Button type="button" variant="outline" onClick={() => setDetailMode("view")}>Back</Button>
                    <Button type="submit" disabled={updateMut.isPending}>{updateMut.isPending ? "Saving..." : "Save"}</Button>
                  </DialogFooter>
                </form>
              )}

              {detailMode === "roles" && (
                <div className="space-y-3">
                  <div className="grid grid-cols-1 gap-2 border rounded p-3 max-h-64 overflow-auto">
                    {roles.map((r) => (
                      <label key={r.code} className="flex items-center gap-2 text-sm cursor-pointer hover:bg-muted px-2 py-1 rounded">
                        <input type="checkbox" checked={editCodes.includes(r.code)} onChange={(e) => setEditCodes(e.target.checked ? [...editCodes, r.code] : editCodes.filter((c) => c !== r.code))} />
                        <span className="font-medium">{roleLabel(r.code)}</span>
                        {detailUser.roles.includes(r.code) && <Badge variant="secondary" className="ml-auto text-xs">current</Badge>}
                      </label>
                    ))}
                  </div>
                  {editCodes.length === 0 && <p className="text-xs text-destructive">Select at least one role</p>}
                  {setRolesMut.isError && <div className="text-sm text-destructive">{getApiErrorMessage(setRolesMut.error, "Save failed")}</div>}
                  <DialogFooter>
                    <Button type="button" variant="outline" onClick={() => setDetailMode("view")}>Back</Button>
                    <Button
                      type="button"
                      disabled={setRolesMut.isPending || editCodes.length === 0}
                      onClick={() => setRolesMut.mutate({ id: detailUser.id, codes: editCodes })}
                    >
                      {setRolesMut.isPending ? "Saving..." : "Save"}
                    </Button>
                  </DialogFooter>
                </div>
              )}

              {detailMode === "toggle" && (
                <div className="space-y-3">
                  {isSelf ? (
                    <p className="text-sm">You are about to <span className="font-semibold text-destructive">disable your own account</span> ({detailUser.username}). You will be signed out immediately and unable to sign in again until another administrator re-enables your account.</p>
                  ) : (
                    <p className="text-sm">Disable <span className="font-medium">{detailUser.fullName}</span> ({detailUser.username})? They will be signed out and unable to sign in until their account is re-enabled.</p>
                  )}
                  {toggleMut.isError && <div className="text-sm text-destructive">{getApiErrorMessage(toggleMut.error, "Action failed")}</div>}
                  <DialogFooter>
                    <Button type="button" variant="outline" onClick={() => setDetailMode("view")}>Back</Button>
                    <Button
                      type="button"
                      variant="destructive"
                      disabled={toggleMut.isPending}
                      onClick={() => toggleMut.mutate({ id: detailUser.id, enable: false })}
                    >
                      {toggleMut.isPending ? "Disabling..." : "Disable user"}
                    </Button>
                  </DialogFooter>
                </div>
              )}
            </>
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}

function DetailItem({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="mt-0.5 font-medium">{value}</dd>
    </div>
  );
}
