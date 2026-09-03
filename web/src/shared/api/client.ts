import axios, { type InternalAxiosRequestConfig } from "axios";
import { queryClient } from "@/shared/api/queryClient";

// Auth rides HttpOnly cookies (withCredentials); the edge validates them and injects X-User-*.
// Mutations echo the pas_csrf cookie as X-CSRF-Token for the edge's double-submit check.
export const api = axios.create({
  baseURL: "/api/v1",
  headers: { "Content-Type": "application/json" },
  withCredentials: true,
});

const SAFE_METHODS = new Set(["get", "head", "options"]);

function readCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`));
  return match ? decodeURIComponent(match[1]) : null;
}

// Request: attach the CSRF token on unsafe methods.
api.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const method = (config.method ?? "get").toLowerCase();
  if (!SAFE_METHODS.has(method) && config.headers) {
    const csrf = readCookie("pas_csrf");
    if (csrf) config.headers["X-CSRF-Token"] = csrf;
  }
  return config;
});

// One shared refresh in flight; concurrent 401s await it, then retry once.
let refreshPromise: Promise<void> | null = null;

function refresh(): Promise<void> {
  if (refreshPromise) return refreshPromise;
  const csrf = readCookie("pas_csrf");
  const p = axios
    .post("/api/v1/auth/refresh", null, {
      withCredentials: true,
      headers: csrf ? { "X-CSRF-Token": csrf } : {},
    })
    .then(() => undefined)
    .catch((e: unknown) => {
      queryClient.clear();
      if (window.location.pathname !== "/login") window.location.href = "/login";
      throw e;
    })
    .finally(() => {
      if (refreshPromise === p) refreshPromise = null;
    });
  refreshPromise = p;
  return p;
}

export type PageMeta = { page: number; size: number; totalElements: number; totalPages: number };

// Backend envelope is {data, meta} (ApiResponseAdvice: Page -> {data: content[], meta: PageMeta}).
// Unwrap data but preserve meta for paged callers (meta is lost if we only keep data).
api.interceptors.response.use(
  (r) => {
    if (r.data && typeof r.data === "object" && "data" in r.data) {
      const meta = (r.data as { meta?: PageMeta }).meta;
      r.data = (r.data as { data: unknown }).data;
      if (meta !== undefined && meta !== null) {
        (r as unknown as { meta: PageMeta }).meta = meta;
      }
    }
    return r;
  },
  async (error) => {
    const original = error.config as (InternalAxiosRequestConfig & { _retry?: boolean }) | undefined;
    const status = error.response?.status;

    // Refresh once for a 401, except on the auth endpoints themselves.
    const isAuthUrl = original?.url?.includes("/auth/login") || original?.url?.includes("/auth/refresh");
    if (status !== 401 || isAuthUrl || !original || original._retry) {
      return Promise.reject(error);
    }

    original._retry = true;
    await refresh();
    return api(original);
  }
);

export type PageResponse<T> = {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
};

// Query string from a params object, dropping unset values.
export function toParams(obj: Record<string, unknown>) {
  const p = new URLSearchParams();
  Object.entries(obj).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== "") p.set(k, String(v));
  });
  return p.toString() ? `?${p.toString()}` : "";
}

// Only for endpoints returning Page<T>: the advice sends {data: content[], meta}.
// A plain body arrives as {data: body} with no meta, and would yield an empty page.
export function toPage<T>(res: { data: unknown; meta?: PageMeta }): PageResponse<T> {
  const content = Array.isArray(res.data) ? (res.data as T[]) : [];
  const meta = res.meta;
  return {
    content,
    totalElements: meta?.totalElements ?? content.length,
    totalPages: meta?.totalPages ?? 1,
    size: meta?.size ?? content.length,
    number: meta?.page ?? 0,
  };
}
