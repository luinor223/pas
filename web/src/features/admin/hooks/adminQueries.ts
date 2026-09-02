import { queryOptions } from "@tanstack/react-query";
import { adminApi } from "../services/adminApi";

export const usersQuery = queryOptions({ queryKey: ["users"], queryFn: () => adminApi.listUsers() });
export const rolesQuery = queryOptions({ queryKey: ["roles"], queryFn: () => adminApi.listRoles() });
