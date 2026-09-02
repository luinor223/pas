import { api } from "@/shared/api/client";
import type {
  CreateUserRequest,
  DepartmentResponse,
  PermissionResponse,
  RoleResponse,
  UpdateUserRequest,
  UserResponse,
} from "../types/adminTypes";

// Admin (identity) data layer: users + roles + departments + permissions.
export const adminApi = {
  listUsers: () => api.get<UserResponse[]>("/users").then((r) => r.data),
  getUser: (id: string) => api.get<UserResponse>(`/users/${id}`).then((r) => r.data),
  listRoles: () => api.get<RoleResponse[]>("/roles").then((r) => r.data),
  listDepartments: () => api.get<DepartmentResponse[]>("/departments").then((r) => r.data),
  listPermissions: () => api.get<PermissionResponse[]>("/permissions").then((r) => r.data),
  createUser: (data: CreateUserRequest) => api.post("/users", data).then((r) => r.data),
  updateUser: (id: string, data: UpdateUserRequest) =>
    api.put<UserResponse>(`/users/${id}`, data).then((r) => r.data),
  setUserEnabled: (id: string, enable: boolean) =>
    api.post(`/users/${id}/${enable ? "enable" : "disable"}`).then((r) => r.data),
  updateUserRoles: (id: string, roleCodes: string[]) =>
    api.put(`/users/${id}/roles`, { roleCodes }).then((r) => r.data),
  updateRolePermissions: (code: string, permissionCodes: string[]) =>
    api.put(`/roles/${code}/permissions`, { permissionCodes }).then((r) => r.data),
};
