// Admin (identity) DTOs, mirroring services/identity user + role + department + permission controllers.
export type UserResponse = {
  id: string;
  username: string;
  email: string;
  fullName: string;
  department: string;
  status: "ACTIVE" | "DISABLED";
  roles: string[];
  lastLoginAt: string | null;
};

export type CreateUserRequest = {
  username: string;
  email: string;
  password: string;
  fullName: string;
  departmentCode: string;
  roleCodes: string[];
};

export type UpdateUserRequest = {
  fullName: string;
  email: string;
  departmentCode: string;
};

export type DepartmentResponse = { id: string; code: string; name: string };
export type PermissionResponse = { id: string; code: string; description: string | null };

export type UpdateUserRolesRequest = { roleCodes: string[] };
export type RoleResponse = { code: string; name: string; permissions: string[] };
export type RolePermissionsRequest = { permissionCodes: string[] };
