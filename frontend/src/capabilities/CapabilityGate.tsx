// SPDX-License-Identifier: Apache-2.0
import { Navigate } from "react-router-dom";
import type { ReactNode } from "react";
import type { CapabilityWire } from "../api/types-auth";
import { useTenant } from "../tenant/TenantContext";
import { useCapabilities } from "./useCapabilities";

/**
 * Renders a loading state until the capability read settles, or a reload would redirect away first.
 * Without the capability it redirects to Triage, which is never gated. The server is the authority.
 */
export function CapabilityGate({
  capability,
  children,
}: {
  capability: CapabilityWire;
  children: ReactNode;
}) {
  const { isEnabled, isLoading } = useCapabilities();
  const { orgSlug, projectSlug } = useTenant();
  if (isLoading) {
    return (
      <div className="h-full flex items-center justify-center text-muted">
        Loading…
      </div>
    );
  }
  if (isEnabled(capability)) return <>{children}</>;
  return <Navigate to={`/orgs/${orgSlug}/projects/${projectSlug}/triage`} replace />;
}
