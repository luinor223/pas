// Auth DTOs, mirroring services/identity AuthController.
export type LoginRequest = { username: string; password: string };
export type RefreshRequest = { refreshToken: string };
export type TokenResponse = { accessToken: string; refreshToken: string; tokenType: string; expiresAt: string };

export type UserSummary = {
  id: string;
  username: string;
  fullName: string;
  department: string;
  roles: string[];
  permissions: string[];
};

export type LoginResponse = {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresAt: string;
  user: UserSummary;
};
