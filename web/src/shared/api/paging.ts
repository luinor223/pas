export type PageMeta = { page: number; size: number; totalElements: number; totalPages: number };

export type PageResponse<T> = {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
};

/** Builds a query string while dropping unset values. */
export function toParams(obj: Record<string, unknown>) {
  const params = new URLSearchParams();
  Object.entries(obj).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") params.set(key, String(value));
  });
  return params.toString() ? `?${params.toString()}` : "";
}

/** Converts the API page envelope metadata retained by the HTTP interceptor. */
export function toPage<T>(response: { data: unknown; meta?: PageMeta }): PageResponse<T> {
  const content = Array.isArray(response.data) ? (response.data as T[]) : [];
  const meta = response.meta;
  return {
    content,
    totalElements: meta?.totalElements ?? content.length,
    totalPages: meta?.totalPages ?? 1,
    size: meta?.size ?? content.length,
    number: meta?.page ?? 0,
  };
}
