// SPDX-License-Identifier: Apache-2.0
import { useQuery } from "@tanstack/react-query";
import { useAuth } from "../../auth/AuthContext";
import { useProjectApi, useTenant } from "../../tenant/TenantContext";

/**
 * Shown only to a reader who can connect (owner-gated server-side), and only while the integration read
 * says there is no repository: a pending or failed read claims nothing.
 */
export function useRepoPrompt(): { canPrompt: boolean } {
  const api = useProjectApi();
  const { orgSlug } = useTenant();
  const { user } = useAuth();

  const integration = useQuery({
    queryKey: ["git-integration", api.base],
    queryFn: api.getGitIntegration,
  });

  const isOwner = user?.orgs.find((o) => o.slug === orgSlug)?.role === "owner";
  return { canPrompt: isOwner && integration.isSuccess && integration.data == null };
}
