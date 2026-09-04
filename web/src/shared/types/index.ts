// Cross-cutting types shared across features.
export type ApiError = {
  timestamp: string;
  status: number;
  error: string;
  code: string;
  message: string;
  path: string;
  violations: Array<{ field: string; message: string }>;
};
