// SPDX-License-Identifier: Apache-2.0
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { auth } from "../../api/client";
import type { ApiError, CapabilityOverrideView } from "../../api/types";
import type { CapabilityWire } from "../../api/types-auth";
import { useAuth } from "../../auth/AuthContext";
import { useTenant } from "../../tenant/TenantContext";
import { ErrorNote, PageBody, PageHeader, Section, Spinner, Toggle, useToast } from "../../ui";

type Feature = {
  capability: CapabilityWire;
  title: string;
  description: string;
  onText: string;
  offText: string;
  errorTitle: string;
};

const FEATURES: Feature[] = [
  {
    capability: "triage_automatic_enabled",
    title: "Automatic triage",
    description:
      "Triage every finding as soon as it opens, instead of waiting for someone to press Run triage. Each run is an " +
      "agent session on your organization's model provider, and there is no spending cap.",
    onText: "Automatic triage is on",
    offText: "Automatic triage is off",
    errorTitle: "Could not change automatic triage",
  },
  {
    capability: "prompt_management_enabled",
    title: "Prompt management",
    description:
      "Prompts, versions, and deployment labels for this organization, with the Prompts page, the /v1/prompts API, " +
      "and the playground. Off hides the page and returns 403 on every prompt route.",
    onText: "Prompt management is on",
    offText: "Prompt management is off",
    errorTitle: "Could not change prompt management",
  },
];

export function Features() {
  const { orgSlug } = useTenant();
  const { user } = useAuth();

  const overrides = useQuery({
    queryKey: ["capability-overrides", orgSlug],
    queryFn: () => auth.listCapabilityOverrides(orgSlug),
  });
  const members = useQuery({
    queryKey: ["org-members", orgSlug],
    queryFn: () => auth.listMembers(orgSlug),
  });

  const me = members.data?.find((m) => m.user_id === user?.id);
  // Affordance only: the backend re-checks CAPABILITIES_MANAGE on every write.
  const canManage = me?.role === "owner" || me?.role === "admin";

  return (
    <PageBody size="narrow">
      <PageHeader
        eyebrow="Organization"
        title="Features"
        subtitle="What Tessary does on its own for this organization. An owner or admin can change these."
      />

      <Section>
        {overrides.isPending ? (
          <div className="flex items-center gap-2 py-12 text-small text-muted">
            <Spinner size="sm" />
            Loading features…
          </div>
        ) : overrides.isError ? (
          <ErrorNote error={overrides.error} />
        ) : (
          <div className="flex flex-col gap-3">
            {FEATURES.map((feature) => {
              const view = overrides.data.find((o) => o.capability === feature.capability);
              return view ? <FeatureRow key={feature.capability} view={view} feature={feature} canManage={canManage} /> : null;
            })}
          </div>
        )}
      </Section>
    </PageBody>
  );
}

/** One capability: the row reads the build's default from the server, and the toggle pins an override over it. */
function FeatureRow({ view, feature, canManage }: { view: CapabilityOverrideView; feature: Feature; canManage: boolean }) {
  const { orgSlug } = useTenant();
  const qc = useQueryClient();
  const toast = useToast();

  const set = useMutation({
    mutationFn: (enabled: boolean) => auth.setCapabilityOverride(orgSlug, feature.capability, enabled),
    onSuccess: (next) => {
      toast.success(next.enabled ? feature.onText : feature.offText);
      qc.invalidateQueries({ queryKey: ["capability-overrides", orgSlug] });
      qc.invalidateQueries({ queryKey: ["capabilities", orgSlug] });
    },
    onError: (err) => toast.error(feature.errorTitle, (err as ApiError).message),
  });

  return (
    <div className="rounded-card border border-border px-4 py-4 flex items-start justify-between gap-4">
      <div className="min-w-0">
        <div className="text-small text-fg">{feature.title}</div>
        <div className="text-label text-muted mt-0.5">{feature.description}</div>
        <div className="text-label text-muted mt-1">
          Default: <span className="text-fg">{view.default_enabled ? "on" : "off"}</span>
        </div>
      </div>
      <Toggle
        checked={view.enabled}
        disabled={!canManage || set.isPending}
        onChange={(on: boolean) => set.mutate(on)}
        label={feature.title}
      />
    </div>
  );
}
