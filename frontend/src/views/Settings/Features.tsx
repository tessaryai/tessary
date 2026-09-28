// SPDX-License-Identifier: Apache-2.0
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { auth } from "../../api/client";
import { ApiError } from "../../api/types";
import type { CapabilityWire } from "../../api/types-auth";
import { useAuth } from "../../auth/AuthContext";
import { useTenant } from "../../tenant/TenantContext";
import { ErrorNote, PageBody, PageHeader, Section, Spinner, Toggle, useToast } from "../../ui";

/**
 * Settings → Features. The org's capability overrides, limited to the ones an operator should decide:
 * today only automatic triage. The row reads the build's own default from the server, so a build that
 * ships it on shows "Default: on" without this page knowing which build it is in. Flipping the toggle
 * pins an override; the server resolves the override over the default.
 */

const TRIAGE_AUTOMATIC: CapabilityWire = "triage_automatic_enabled";

export function Features() {
  const { orgSlug } = useTenant();
  const { user } = useAuth();
  const qc = useQueryClient();
  const toast = useToast();

  const overrides = useQuery({
    queryKey: ["capability-overrides", orgSlug],
    queryFn: () => auth.listCapabilityOverrides(orgSlug),
  });
  const members = useQuery({
    queryKey: ["org-members", orgSlug],
    queryFn: () => auth.listMembers(orgSlug),
  });

  const me = members.data?.find((m) => m.user_id === user?.id);
  // Mirrors the server CAPABILITIES_MANAGE permission (owner + admin). Affordance only: the backend
  // re-checks on every write.
  const canManage = me?.role === "owner" || me?.role === "admin";
  const triage = overrides.data?.find((o) => o.capability === TRIAGE_AUTOMATIC);

  const set = useMutation({
    mutationFn: (enabled: boolean) => auth.setCapabilityOverride(orgSlug, TRIAGE_AUTOMATIC, enabled),
    onSuccess: (view) => {
      toast.success(view.enabled ? "Automatic triage is on" : "Automatic triage is off");
      qc.invalidateQueries({ queryKey: ["capability-overrides", orgSlug] });
      qc.invalidateQueries({ queryKey: ["capabilities", orgSlug] });
    },
    onError: (err) => toast.error("Could not change automatic triage", (err as ApiError).message),
  });

  return (
    <PageBody size="narrow">
      <PageHeader
        eyebrow="Organization"
        title="Features"
        subtitle="What Tessary does on its own for this organization. An owner or admin can change these."
      />

      <Section>
        {overrides.isLoading ? (
          <div className="flex items-center gap-2 py-12 text-small text-muted">
            <Spinner size="sm" />
            Loading features…
          </div>
        ) : overrides.isError ? (
          <ErrorNote error={overrides.error} />
        ) : triage ? (
          <div className="rounded-card border border-border px-4 py-4 flex items-start justify-between gap-4">
            <div className="min-w-0">
              <div className="text-small text-fg">Automatic triage</div>
              <div className="text-label text-muted mt-0.5">
                Triage every finding as soon as it opens, instead of waiting for someone to press Run triage. Each
                run is an agent session on your organization's model provider, and there is no spending cap.
              </div>
              <div className="text-label text-muted mt-1">
                Default: <span className="text-fg">{triage.default_enabled ? "on" : "off"}</span>
              </div>
            </div>
            <Toggle
              checked={triage.enabled}
              disabled={!canManage || set.isPending}
              onChange={(on: boolean) => set.mutate(on)}
              label="Automatic triage"
            />
          </div>
        ) : null}
      </Section>
    </PageBody>
  );
}
