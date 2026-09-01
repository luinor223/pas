import { queryOptions, useQuery } from "@tanstack/react-query";
import { authApi } from "@/features/auth/services/authApi";

// The signed-in user, sourced from the server (cookie-authenticated GET /users/me).
// retry:false so a 401 resolves to "not authenticated" instead of retrying.
export const currentUserQuery = queryOptions({
  queryKey: ["currentUser"],
  queryFn: () => authApi.me(),
  retry: false,
  staleTime: Infinity,
});

export function useCurrentUser() {
  return useQuery(currentUserQuery);
}
