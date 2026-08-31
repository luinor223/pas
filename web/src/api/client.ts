import axios, { type InternalAxiosRequestConfig } from "axios";
import { useAuthStore } from "@/stores/auth.store";

// Single axios instance — base /api/v1 so services can serve /auth, /users, /roles themselves (edge strips /api/v1)
export const api = axios.create({
  baseURL: "/api/v1",
  headers: { "Content-Type": "application/json" },
});

// Request: attach Bearer if present
api.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = useAuthStore.getState().accessToken;
  if (token && config.headers) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Response: single refreshPromise queue for parallel 401s
let refreshPromise: Promise<string> | null = null;
let isRefreshing = false;
const pendingQueue: Array<(token: string) => void> = [];
const pendingReject: Array<(err: unknown) => void> = [];

function onRefreshed(token: string) {
  pendingQueue.forEach((cb) => cb(token));
  pendingQueue.length = 0;
  pendingReject.length = 0;
}
function onRefreshFailed(err: unknown) {
  pendingReject.forEach((cb) => cb(err));
  pendingQueue.length = 0;
  pendingReject.length = 0;
}

api.interceptors.response.use(
  (r) => r,
  async (error) => {
    const original = error.config as InternalAxiosRequestConfig & { _retry?: boolean };
    const status = error.response?.status;

    // only handle 401 for non-auth endpoints and not already retried
    const isAuthUrl = original?.url?.includes("/auth/login") || original?.url?.includes("/auth/refresh");
    if (status !== 401 || isAuthUrl || original._retry) {
      return Promise.reject(error);
    }

    const { refreshToken, setTokens, clear } = useAuthStore.getState();
    if (!refreshToken) {
      clear();
      return Promise.reject(error);
    }

    // deduplicate: if already refreshing, queue this request
    if (isRefreshing && refreshPromise) {
      return new Promise((resolve, reject) => {
        pendingQueue.push((token: string) => {
          if (original.headers) (original.headers as Record<string, string>).Authorization = `Bearer ${token}`;
          original._retry = true;
          resolve(api(original));
        });
        pendingReject.push(reject);
      });
    }

    isRefreshing = true;
    // important: use plain axios for refresh to avoid interceptor loop
    refreshPromise = axios
      .post("/api/v1/auth/refresh", { refreshToken })
      .then((res) => {
        const data = res.data as { accessToken: string; refreshToken: string; expiresAt: string };
        setTokens({ accessToken: data.accessToken, refreshToken: data.refreshToken, expiresAt: data.expiresAt });
        onRefreshed(data.accessToken);
        return data.accessToken;
      })
      .catch((e) => {
        onRefreshFailed(e);
        clear();
        // force redirect to login if not already there
        if (window.location.pathname !== "/login") window.location.href = "/login";
        throw e;
      })
      .finally(() => {
        isRefreshing = false;
        refreshPromise = null;
      });

    const newToken = await refreshPromise;
    if (original.headers) (original.headers as Record<string, string>).Authorization = `Bearer ${newToken}`;
    original._retry = true;
    return api(original);
  }
);
