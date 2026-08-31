import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/api/client";
import type { CreateUserRequest, UserResponse, RoleResponse } from "@/api/types";
import { useState, useMemo, useEffect } from "react";
import { Button } from "@/shared/components/ui/button";
import { Input } from "@/shared/components/ui/input";
import { Label } from "@/shared/components/ui/label";
import { Select } from "@/shared/components/ui/select";
import { Badge } from "@/shared/components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/shared/components/ui/table";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from "@/shared/components/ui/dialog";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/ui/card";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";

const createSchema = z.object({
  username: z.string().min(2),
  email: z.string().email(),
  password: z.string().min(8),
  fullName: z.string().min(1),
  departmentCode: z.string().min(1),
  roleCodes: z.array(z.string()).min(1),
});

type FormCreate = z.infer<typeof createSchema>;

const DEPARTMENTS = ["SALES", "LEGAL", "ACCOUNTING", "OPERATIONS", "BOARD", "IT"];

export function UserTable() {
  const qc = useQueryClient();
  const [q, setQ] = useState("");
  const [dept, setDept] = useState("All");
  const [role, setRole] = useState("All");
  const [status, setStatus] = useState("All");
  const [openCreate, setOpenCreate] = useState(false);
  const [editRolesId, setEditRolesId] = useState<string | null>(null);

  const usersQ = useQuery({ queryKey: ["users"], queryFn: async () => (await api.get<UserResponse[]>("/users")).data });
  const rolesQ = useQuery({ queryKey: ["roles"], queryFn: async () => (await api.get<RoleResponse[]>("/roles")).data });

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
    mutationFn: async (data: CreateUserRequest) => (await api.post("/users", data)).data,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["users"] }); setOpenCreate(false); },
  });

  const toggleMut = useMutation({
    mutationFn: async ({ id, enable }: { id: string; enable: boolean }) => (await api.post(`/users/${id}/${enable ? "enable" : "disable"}`)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["users"] }),
  });

  const setRolesMut = useMutation({
    mutationFn: async ({ id, codes }: { id: string; codes: string[] }) => (await api.put(`/users/${id}/roles`, { roleCodes: codes })).data,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["users"] }); setEditRolesId(null); },
  });

  const { register, handleSubmit, reset, formState: { errors } } = useForm<FormCreate>({
    resolver: zodResolver(createSchema),
    defaultValues: { username: "", email: "", password: "", fullName: "", departmentCode: "SALES", roleCodes: ["SALES_OFFICER"] },
  });

  const editUser = users.find((u) => u.id === editRolesId);
  const [editCodes, setEditCodes] = useState<string[]>([]);
  useEffect(() => {
    if (editUser) setEditCodes(editUser.roles);
  }, [editUser?.id]);

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle>Users ({filtered.length}/{users.length})</CardTitle>
          <Button onClick={() => setOpenCreate(true)}>+ New User</Button>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex flex-wrap gap-2">
            <Input placeholder="Search users..." value={q} onChange={(e) => setQ(e.target.value)} className="max-w-xs" />
            <Select value={dept} onChange={(e) => setDept(e.target.value)}><option value="All">Department: All</option>{DEPARTMENTS.map((d) => <option key={d} value={d}>{d}</option>)}</Select>
            <Select value={role} onChange={(e) => setRole(e.target.value)}><option value="All">Role: All</option>{roles.map((r) => <option key={r.code} value={r.code}>{r.code}</option>)}</Select>
            <Select value={status} onChange={(e) => setStatus(e.target.value)}><option value="All">Status: All</option><option value="ACTIVE">ACTIVE</option><option value="DISABLED">DISABLED</option></Select>
          </div>

          {usersQ.isLoading ? <div className="text-sm text-muted-foreground">Loading...</div> : usersQ.isError ? <div className="text-sm text-destructive">Failed to load users: {(usersQ.error as { message: string })?.message}</div> : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>USER</TableHead>
                  <TableHead>DEPARTMENT</TableHead>
                  <TableHead>ROLE</TableHead>
                  <TableHead>STATUS</TableHead>
                  <TableHead>LAST LOGIN</TableHead>
                  <TableHead>Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filtered.map((u) => (
                  <TableRow key={u.id}>
                    <TableCell>
                      <div className="font-medium">{u.fullName}</div>
                      <div className="text-xs text-muted-foreground">{u.username} · {u.email}</div>
                    </TableCell>
                    <TableCell>{u.department}</TableCell>
                    <TableCell><div className="flex flex-wrap gap-1">{u.roles.map((r) => <Badge key={r} variant="secondary" className="text-xs">{r}</Badge>)}</div></TableCell>
                    <TableCell><Badge variant={u.status === "ACTIVE" ? "default" : "destructive"}>{u.status}</Badge></TableCell>
                    <TableCell className="text-xs">{u.lastLoginAt ? new Date(u.lastLoginAt).toLocaleString() : "-"}</TableCell>
                    <TableCell className="space-x-1">
                      <Button size="sm" variant="outline" onClick={() => setEditRolesId(u.id)}>Roles</Button>
                      {u.status === "ACTIVE" ? <Button size="sm" variant="destructive" onClick={() => toggleMut.mutate({ id: u.id, enable: false })}>Disable</Button> : <Button size="sm" onClick={() => toggleMut.mutate({ id: u.id, enable: true })}>Enable</Button>}
                    </TableCell>
                  </TableRow>
                ))}
                {filtered.length === 0 && <TableRow><TableCell colSpan={6} className="text-center text-muted-foreground">No users</TableCell></TableRow>}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {/* Create dialog */}
      <Dialog open={openCreate} onOpenChange={setOpenCreate}>
        <DialogContent>
          <DialogHeader><DialogTitle>Create user</DialogTitle></DialogHeader>
          <form onSubmit={handleSubmit((d) => createMut.mutate({ username: d.username, email: d.email, password: d.password, fullName: d.fullName, departmentCode: d.departmentCode, roleCodes: d.roleCodes }))} className="space-y-3">
            <div><Label>Username</Label><Input {...register("username")} />{errors.username && <p className="text-xs text-destructive">{errors.username.message}</p>}</div>
            <div><Label>Email</Label><Input {...register("email")} />{errors.email && <p className="text-xs text-destructive">{errors.email.message}</p>}</div>
            <div><Label>Password (min 8)</Label><Input type="password" {...register("password")} />{errors.password && <p className="text-xs text-destructive">{errors.password.message}</p>}</div>
            <div><Label>Full name</Label><Input {...register("fullName")} />{errors.fullName && <p className="text-xs text-destructive">{errors.fullName.message}</p>}</div>
            <div><Label>Department</Label><Select {...register("departmentCode")}>{DEPARTMENTS.map((d) => <option key={d} value={d}>{d}</option>)}</Select></div>
            <div><Label>Roles</Label>
              <select multiple {...register("roleCodes")} className="w-full border rounded p-2 h-32 text-sm">
                {roles.map((r) => <option key={r.code} value={r.code}>{r.code} — {r.name}</option>)}
              </select>
              <p className="text-xs text-muted-foreground">Hold Ctrl/Cmd to multi-select</p>
              {errors.roleCodes && <p className="text-xs text-destructive">{errors.roleCodes.message}</p>}
            </div>
            {createMut.isError && <div className="text-sm text-destructive">{(createMut.error as { response?: { data?: { message?: string } } })?.response?.data?.message ?? "Create failed"}</div>}
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => { setOpenCreate(false); reset(); }}>Cancel</Button>
              <Button type="submit" disabled={createMut.isPending}>{createMut.isPending ? "Creating..." : "Create"}</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Edit roles dialog */}
      <Dialog open={!!editRolesId} onOpenChange={(o) => !o && setEditRolesId(null)}>
        <DialogContent>
          <DialogHeader><DialogTitle>Edit roles — {editUser?.username}</DialogTitle></DialogHeader>
          <div className="space-y-2">
            <Label>Roles (multi)</Label>
            <select multiple value={editCodes} onChange={(e) => setEditCodes([...e.target.selectedOptions].map((o) => o.value))} className="w-full border rounded p-2 h-40 text-sm">
              {roles.map((r) => <option key={r.code} value={r.code}>{r.code}</option>)}
            </select>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setEditRolesId(null)}>Cancel</Button>
            <Button onClick={() => editRolesId && setRolesMut.mutate({ id: editRolesId, codes: editCodes })} disabled={setRolesMut.isPending}>Save</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
