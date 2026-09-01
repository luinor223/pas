import { api } from "@/shared/api/client";
import type { LoginRequest, LoginResponse } from "../types/authTypes";

// Auth data layer. The shared axios instance handles the token refresh interceptor.
export const authApi = {
  login: (data: LoginRequest) => api.post<LoginResponse>("/auth/login", data).then((r) => r.data),
  logout: (refreshToken: string) => api.post("/auth/logout", { refreshToken }).then((r) => r.data),
};
