// SPDX-License-Identifier: Apache-2.0
import { useQuery } from "@tanstack/react-query";
import { capabilitiesQuery } from "../shell/shellQueries";
import type { CapabilityWire } from "../api/types-auth";
import { useTenant } from "../tenant/TenantContext";

/**
 * Fail-closed while loading or on error, unlike the backend, which knows the build defaults: a gated
 * surface stays hidden rather than flashing in. UX only; the server is the authority.
 */
export function useCapabilities() {
  const { orgSlug } = useTenant();
  const query = useQuery({ ...capabilitiesQuery(orgSlug), enabled: !!orgSlug });

  const capabilities = query.data?.capabilities;
  return {
    capabilities,
    isEnabled: (capability: CapabilityWire): boolean => capabilities?.[capability] === true,
    isLoading: query.isLoading,
  };
}
