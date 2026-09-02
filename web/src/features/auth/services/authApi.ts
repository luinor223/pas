import { api } from "@/shared/api/client";
import type { LoginRequest, LoginResponse, UserSummary } from "../types/authTypes";

// Auth data layer. Cookies carry the session; the shared axios instance handles CSRF + refresh.
export const authApi = {
  login: (data: LoginRequest) => api.post<LoginResponse>("/auth/login", data).then((r) => r.data),
  logout: () => api.post("/auth/logout").then((r) => r.data),
  me: () => api.get<UserSummary>("/auth/me").then((r) => r.data),
};
