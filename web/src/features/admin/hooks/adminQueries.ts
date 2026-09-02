import { queryOptions } from "@tanstack/react-query";
import { adminApi } from "../services/adminApi";

export const usersQuery = queryOptions({ queryKey: ["users"], queryFn: () => adminApi.listUsers() });
export const rolesQuery = queryOptions({ queryKey: ["roles"], queryFn: () => adminApi.listRoles() });
export const departmentsQuery = queryOptions({
  queryKey: ["departments"],
  queryFn: () => adminApi.listDepartments(),
  staleTime: 5 * 60_000,
});
export const permissionsQuery = queryOptions({
  queryKey: ["permissions"],
  queryFn: () => adminApi.listPermissions(),
  staleTime: 5 * 60_000,
});
