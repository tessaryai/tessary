// SPDX-License-Identifier: Apache-2.0
import { createContext, useContext, useMemo } from "react";
import { useParams } from "react-router-dom";
import { orgApi, projectApi, type OrgApi, type ProjectApi } from "../api/client";

interface TenantValue {
  orgSlug: string;
  projectSlug: string;
  api: ProjectApi;
  /** Org-scoped API — provider credentials and anything else keyed to the org, not the project. */
  orgApi: OrgApi;
}

const TenantContext = createContext<TenantValue | null>(null);

/**
 * Reads {orgSlug, projectSlug} from the URL and binds a {@link ProjectApi}
 * to it. Throws if used outside a tenant-prefixed route.
 */
export function TenantProvider({ children }: { children: React.ReactNode }) {
  const { orgSlug, projectSlug } = useParams<{ orgSlug: string; projectSlug: string }>();

  const value = useMemo<TenantValue | null>(() => {
    if (!orgSlug || !projectSlug) return null;
    return {
      orgSlug,
      projectSlug,
      api: projectApi(orgSlug, projectSlug),
      orgApi: orgApi(orgSlug),
    };
  }, [orgSlug, projectSlug]);

  if (!value) {
    throw new Error("TenantProvider used outside /orgs/:orgSlug/projects/:projectSlug");
  }
  return <TenantContext.Provider value={value}>{children}</TenantContext.Provider>;
}

export function useTenant() {
  const ctx = useContext(TenantContext);
  if (!ctx) throw new Error("useTenant must be used inside <TenantProvider>");
  return ctx;
}

export function useProjectApi() {
  return useTenant().api;
}

/** Provider credentials (Settings → Providers) are org-scoped, not project-scoped. */
export function useOrgApi() {
  return useTenant().orgApi;
}
