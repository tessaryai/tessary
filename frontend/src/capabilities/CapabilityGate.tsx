// SPDX-License-Identifier: Apache-2.0
import { Navigate } from "react-router-dom";
import type { ReactNode } from "react";
import type { CapabilityWire } from "../api/types-auth";
import { useTenant } from "../tenant/TenantContext";
import { useCapabilities } from "./useCapabilities";

/**
 * The capability gate. While the capability read is loading (see {@link useCapabilities}, fail-closed),
 * renders a loading state rather than deciding — otherwise a hard navigation or reload to a gated route
 * reads `isEnabled` as false before the org's capabilities have arrived and redirects away before it ever
 * gets a real answer. Once the read settles: when the org has `capability`, renders `children`; when it
 * does not —
 *   - with a `fallback`, renders the fallback (pass `fallback={null}` to drop a nav link);
 *   - otherwise redirects to Triage, which is never gated (so no redirect loop).
 *
 * There is ONE gating axis now: this one. `CapabilityService.require` on each gated endpoint is the
 * authority; this only keeps a user from reaching a surface their org doesn't have.
 */
export function CapabilityGate({
  capability,
  children,
  fallback,
}: {
  capability: CapabilityWire;
  children: ReactNode;
  fallback?: ReactNode;
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
  if (fallback !== undefined) return <>{fallback}</>;
  return <Navigate to={`/orgs/${orgSlug}/projects/${projectSlug}/triage`} replace />;
}
