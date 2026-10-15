// SPDX-License-Identifier: Apache-2.0
import { createContext, useContext, useMemo } from "react";
import { useParams } from "react-router-dom";
import { orgApi, projectApi, type OrgApi, type ProjectApi } from "../api/client";

interface TenantValue {
  orgSlug: string;
  projectSlug: string;
  api: ProjectApi;
  orgApi: OrgApi;
}

const TenantContext = createContext<TenantValue | null>(null);

export function TenantProvider({ children }: { children: React.ReactNode }) {
  const { orgSlug, projectSlug } = useParams<{ orgSlug: string; projectSlug: string }>();

  const value = useMemo<TenantValue>(
    () => ({
      orgSlug: orgSlug!,
      projectSlug: projectSlug!,
      api: projectApi(orgSlug!, projectSlug!),
      orgApi: orgApi(orgSlug!),
    }),
    [orgSlug, projectSlug],
  );

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

export function useOrgApi() {
  return useTenant().orgApi;
}
