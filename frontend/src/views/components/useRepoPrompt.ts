// SPDX-License-Identifier: Apache-2.0
import { useQuery } from "@tanstack/react-query";
import { useAuth } from "../../auth/AuthContext";
import { useProjectApi, useTenant } from "../../tenant/TenantContext";

/**
 * Whether to offer "Connect repository" on a surface that is not Settings.
 *
 * Two conditions, and both have to be known rather than assumed. Connecting is owner-gated
 * server-side, so a reader who cannot act on the offer is not shown it. And the offer is only
 * honest while the project genuinely has no repository: a pending or failed read of the
 * integration claims nothing, because prompting someone to connect a repo they already connected
 * reads as the product having lost it.
 *
 * The integration query shares its key with Settings → Git integration, so opening a case after
 * connecting one costs no extra request and the button disappears on the same cache entry.
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
