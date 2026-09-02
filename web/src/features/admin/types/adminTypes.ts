// Admin (identity) DTOs, mirroring services/identity user + role controllers.
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

export type UpdateUserRolesRequest = { roleCodes: string[] };
export type RoleResponse = { code: string; name: string; permissions: string[] };
export type RolePermissionsRequest = { permissionCodes: string[] };
