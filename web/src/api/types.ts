// Manual DTOs mirroring services/identity DTOs — keep in sync with backend
export type LoginRequest = { username: string; password: string };
export type RefreshRequest = { refreshToken: string };
export type TokenResponse = { accessToken: string; refreshToken: string; tokenType: string; expiresAt: string };
export type UserSummary = {
  id: string;
  username: string;
  fullName: string;
  department: string;
  roles: string[];
};
export type LoginResponse = {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresAt: string;
  user: UserSummary;
};
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

export type ApiError = { message: string; status: number; errors?: Record<string, string> };
