// SPDX-License-Identifier: Apache-2.0
import { useEffect } from "react";
import { useLocation } from "react-router-dom";
import { auth } from "../api/client";
import { useAuth } from "./AuthContext";

export function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { isAuthenticated, isLoading } = useAuth();
  const location = useLocation();

  useEffect(() => {
    if (!isLoading && !isAuthenticated) {
      const returnTo = location.pathname + location.search;
      window.location.replace(auth.loginUrl(returnTo));
    }
  }, [isLoading, isAuthenticated, location]);

  if (isLoading || !isAuthenticated) {
    return (
      <div className="h-full flex items-center justify-center text-muted">
        Loading…
      </div>
    );
  }
  return <>{children}</>;
}
