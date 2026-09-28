// SPDX-License-Identifier: Apache-2.0
import { useQuery } from "@tanstack/react-query";
import { auth } from "../api/client";
import type { CapabilityWire } from "../api/types-auth";
import { useTenant } from "../tenant/TenantContext";

/**
 * Reads the active org's capability object (`GET /api/orgs/{org}/capabilities`) — the single thing the SPA is
 * assembled from. The backend resolves the build's defaults and the org's own overrides server-side and
 * returns one boolean per capability; the browser holds no plan logic and no flag defaults.
 *
 * FAIL-CLOSED on the client, deliberately, and NOT the same posture as the backend's. The backend falls back
 * to the build's default when the flag store has no answer, because it knows what the defaults are.
 * The browser doesn't: it only knows the read failed. So while the query is loading, or on error, `isEnabled`
 * returns `false` — a gated surface stays hidden rather than flashing in and then vanishing. This is UX only;
 * `CapabilityService.require` on each gated endpoint is the authority, so a briefly-hidden surface the org
 * does have is harmless (it appears once the read settles).
 */
export function useCapabilities() {
  const { orgSlug } = useTenant();
  const query = useQuery({
    queryKey: ["capabilities", orgSlug],
    queryFn: () => auth.getCapabilities(orgSlug),
    enabled: !!orgSlug,
  });

  const capabilities = query.data?.capabilities;
  return {
    /** The resolved map, or undefined until the first read settles. */
    capabilities,
    /** Fail-closed: on only when the resolved object explicitly says so. */
    isEnabled: (capability: CapabilityWire): boolean => capabilities?.[capability] === true,
    isLoading: query.isLoading,
  };
}
