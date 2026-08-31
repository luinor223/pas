import { create } from "zustand";
import { persist } from "zustand/middleware";

export type UserSummary = {
  id: string;
  username: string;
  fullName: string;
  department: string;
  roles: string[];
};

type AuthState = {
  accessToken: string | null;
  refreshToken: string | null;
  expiresAt: string | null;
  user: UserSummary | null;
  isAuthenticated: boolean;
  setAuth: (p: {
    accessToken: string;
    refreshToken: string;
    expiresAt: string;
    user: UserSummary;
  }) => void;
  setTokens: (p: { accessToken: string; refreshToken: string; expiresAt: string }) => void;
  clear: () => void;
};

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      accessToken: null,
      refreshToken: null,
      expiresAt: null,
      user: null,
      isAuthenticated: false,
      setAuth: ({ accessToken, refreshToken, expiresAt, user }) =>
        set({ accessToken, refreshToken, expiresAt, user, isAuthenticated: true }),
      setTokens: ({ accessToken, refreshToken, expiresAt }) =>
        set({ accessToken, refreshToken, expiresAt }),
      clear: () => set({ accessToken: null, refreshToken: null, expiresAt: null, user: null, isAuthenticated: false }),
    }),
    {
      name: "pas-auth",
      partialize: (s) => ({
        accessToken: s.accessToken,
        refreshToken: s.refreshToken,
        expiresAt: s.expiresAt,
        user: s.user,
        isAuthenticated: s.isAuthenticated,
      }),
    }
  )
);
