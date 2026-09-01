// Cross-cutting types shared across features.
export type ApiError = { message: string; status: number; errors?: Record<string, string> };
