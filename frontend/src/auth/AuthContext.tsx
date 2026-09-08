// SPDX-License-Identifier: Apache-2.0
import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { ApiError } from "../api/types";
import { auth as authApi } from "../api/client";
import { preloadRouteChunk } from "../lib/routePreload";
import type { Me } from "../api/types-auth";

interface AuthState {
  user: Me | null;
  isLoading: boolean;
  isAuthenticated: boolean;
  refetch: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<Me | null>(null);
  const [isLoading, setLoading] = useState(true);

  const refetch = useCallback(async () => {
    setLoading(true);
    try {
      const me = await authApi.me();
      setUser(me);
    } catch (e) {
      // 401 → not signed in; any other error we still treat as "no user" so the
      // route guard redirects to WorkOS (/auth/login) rather than spinning forever.
      if (e instanceof ApiError && e.status !== 401) {
        // eslint-disable-next-line no-console
        console.warn("auth/me failed:", e.message);
      }
      setUser(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refetch();
    /*
     * Warm the current route's chunk in the same tick as the sign-in check rather than after it.
     * `ProtectedRoute` renders nothing until `refetch` resolves, so React.lazy's import used to be
     * strictly downstream of this round trip — a chunk whose bytes have nothing to do with identity
     * waiting on identity. Deliberately fire-and-forget: it warms the module cache and moves no
     * data request, so nothing here can fail in a way the user should see.
     */
    preloadRouteChunk(window.location.pathname);
  }, [refetch]);

  const value = useMemo<AuthState>(() => ({
    user,
    isLoading,
    isAuthenticated: user != null,
    refetch,
  }), [user, isLoading, refetch]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used inside <AuthProvider>");
  return ctx;
}
