// SPDX-License-Identifier: Apache-2.0
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { auth } from "../../api/client";
import { ApiError, type CapabilityOverrideView } from "../../api/types";
import type { CapabilityWire } from "../../api/types-auth";
import { useAuth } from "../../auth/AuthContext";
import { useProjectApi, useTenant } from "../../tenant/TenantContext";
import { ErrorNote, PageBody, PageHeader, Section, Spinner, Toggle, useToast } from "../../ui";

/** Each row reads the build's default from the server; its toggle pins an override over it. */
type Feature = {
  capability: CapabilityWire;
  label: string;
  description: string;
  /** The toast after a change: on, off and refused. */
  said: [on: string, off: string, refused: string];
};

const FEATURES: Feature[] = [
  {
    capability: "triage_automatic_enabled",
    label: "Automatic triage",
    description:
      "Triage every finding as soon as it opens, instead of waiting for someone to press Run triage. Each run is an agent session on your organization's model provider, and there is no spending cap.",
    said: ["Automatic triage is on", "Automatic triage is off", "Could not change automatic triage"],
  },
  {
    capability: "rca_automatic_enabled",
    label: "Automatic RCA",
    description:
      "Run RCA on a case when new windows join it, at most once every 6 hours for each case, instead of waiting for someone to press Run RCA. Only windows that arrive after you turn this on count. Each run is an agent session on your organization's model provider, and there is no spending cap.",
    said: ["Automatic RCA is on", "Automatic RCA is off", "Could not change automatic RCA"],
  },
  {
    capability: "fix_pr_enabled",
    label: "Fix PRs",
    description:
      "After each RCA, open or update one pull request for each case that fixes the high-confidence causes RCA found in your repository. Tessary never merges it. Each run is an agent session on a frontier model from your organization's provider, and there is no spending cap.",
    said: ["Fix PRs are on", "Fix PRs are off", "Could not change Fix PRs"],
  },
];

type Blocked = { text: string; link: { label: string; to: string } };

/**
 * Why this project cannot open a fix pull request, as the first gate the backend would stop at: a repository, then
 * write access, then a frontier model. A read still on its way or failed claims nothing.
 */
function useFixBlocked(): Blocked | null {
  const api = useProjectApi();
  const git = useQuery({ queryKey: ["git-integration", api.base], queryFn: api.getGitIntegration });
  const models = useQuery({ queryKey: ["model-settings", api.base], queryFn: api.getModelSettings });
  const toGit = { label: "Open Git integration", to: "../git" };

  if (!git.isSuccess) return null;
  if (git.data == null) {
    return { text: "This project has no repository connected, so Tessary cannot open a pull request.", link: toGit };
  }
  if (!git.data.canWrite) {
    return { text: "This project's repository connection cannot write, so Tessary cannot open a pull request.", link: toGit };
  }
  const fix = models.data?.lanes.find((l) => l.id === "fix");
  if (fix && fix.effective_model_key == null) {
    return {
      text: "This project has no frontier model for the fix, so Tessary cannot open a pull request.",
      link: { label: "Open Models", to: "../models" },
    };
  }
  return null;
}

export function Features() {
  const { orgSlug } = useTenant();
  const { user } = useAuth();
  const qc = useQueryClient();
  const toast = useToast();
  const fixBlocked = useFixBlocked();

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

  const set = useMutation({
    mutationFn: ({ feature, enabled }: { feature: Feature; enabled: boolean }) =>
      auth.setCapabilityOverride(orgSlug, feature.capability, enabled),
    onSuccess: (view, { feature }) => {
      toast.success(view.enabled ? feature.said[0] : feature.said[1]);
      qc.invalidateQueries({ queryKey: ["capability-overrides", orgSlug] });
      qc.invalidateQueries({ queryKey: ["capabilities", orgSlug] });
    },
    onError: (err, { feature }) => toast.error(feature.said[2], (err as ApiError).message),
  });

  const rows = FEATURES.flatMap((feature) => {
    const view = overrides.data?.find((o) => o.capability === feature.capability);
    return view ? [{ feature, view }] : [];
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
        ) : (
          <div className="flex flex-col gap-2">
            {rows.map(({ feature, view }) => (
              <FeatureRow
                key={feature.capability}
                feature={feature}
                view={view}
                blocked={feature.capability === "fix_pr_enabled" ? fixBlocked : null}
                disabled={!canManage || set.isPending}
                onChange={(enabled) => set.mutate({ feature, enabled })}
              />
            ))}
          </div>
        )}
      </Section>
    </PageBody>
  );
}

/**
 * One feature. `blocked` is a fact about this project, not the organization, so it never disables the switch: an
 * owner can still turn the feature off, or on before the project is ready.
 */
function FeatureRow({
  feature,
  view,
  blocked,
  disabled,
  onChange,
}: {
  feature: Feature;
  view: CapabilityOverrideView;
  blocked: Blocked | null;
  disabled: boolean;
  onChange: (enabled: boolean) => void;
}) {
  return (
    <div className="rounded-card border border-border px-4 py-4 flex items-start justify-between gap-4">
      <div className="min-w-0">
        <div className="text-small text-fg">{feature.label}</div>
        <div className="text-label text-muted mt-0.5">{feature.description}</div>
        <div className="text-label text-muted mt-1">
          Default: <span className="text-fg">{view.default_enabled ? "on" : "off"}</span>
        </div>
        {blocked && (
          <div className="text-label text-warning mt-1">
            {blocked.text}{" "}
            <Link to={blocked.link.to} className="text-link hover:text-link-hover">
              {blocked.link.label}
            </Link>
          </div>
        )}
      </div>
      <Toggle checked={view.enabled} disabled={disabled} onChange={onChange} label={feature.label} />
    </div>
  );
}
