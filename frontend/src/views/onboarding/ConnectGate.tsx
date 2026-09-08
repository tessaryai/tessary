// SPDX-License-Identifier: Apache-2.0
import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronRight, CodeXml } from "lucide-react";
import { useAuth } from "../../auth/AuthContext";
import { useProjectApi, useTenant } from "../../tenant/TenantContext";
import { auth } from "../../api/client";
import { isSampleProject, type Project } from "../../api/types-auth";
import { relativeTime } from "../../lib/relativeTime";
import {
  INSTRUMENT_DOC_URL,
  OTLP_PROMPT,
  TAG_REPAIR_PROMPT,
  CopyField,
  ListeningBanner,
  useIngestToken,
} from "../components/SourceConnect";
import { CopyButton, Spinner, useToast } from "../../ui";

/**
 * The first-run gate (#1227, design-spec.md Screen 2): stands in place of the whole shell
 * (sidebar included) until this project has landed a span carrying `tessary.call_site.id`.
 * Mounted by `ProjectShell` in App.tsx, which decides WHETHER to render this instead of
 * `<ShellChrome>` — this component owns only what the gate looks like, not the decision to show it.
 *
 * Three states, matching the design exactly:
 *   1. nothing received      — the connect prompt + fields + a pulsing "listening" banner + one
 *                               quiet escape hatch. Which escape hatch depends on whether this org
 *                               has anywhere else to go: a brand-new account gets "Start with a
 *                               sample project", and an org that already has other real projects
 *                               (i.e. someone just created a second one) instead gets "Check
 *                               existing projects", which discloses them inline rather than
 *                               navigating away from a screen the user is mid-way through.
 *   2. spans received,
 *      none tagged           — a full replacement screen (four-stat row, one paragraph, the repair
 *                               prompt). No sample-project link and no other exit on this state.
 *   3. a tagged span arrives — no screen; the parent's poll (in App.tsx) sees `has_tagged_span` flip
 *                               and stops rendering this component at all, in favor of the real shell.
 *
 * The gate mints its own ingest token on mount (`useIngestToken`, auto-issue) rather than showing a
 * "Create a connection token" button — the design has no such control; the header field is always
 * populated. A hard reload of this screen therefore mints a fresh write-scoped key each time, an
 * accepted cost for a first-run screen with no button to avoid it (see `useIngestToken`'s own doc).
 */
export function ConnectGate() {
  const { orgSlug, projectSlug } = useTenant();
  const api = useProjectApi();
  const { user } = useAuth();
  const nav = useNavigate();
  const toast = useToast();
  const qc = useQueryClient();

  // Same queryKey shape ProjectShell (App.tsx) polls to decide whether to mount this component at
  // all — react-query dedupes both hooks against one shared cache entry, so this is a second
  // subscriber, not a second live poll.
  const status = useQuery({
    queryKey: ["substrate-status", api.base],
    queryFn: api.substrateStatus,
    refetchInterval: 3500,
  });
  const hasTaggedSpan = status.data?.has_tagged_span ?? false;

  const { token, issue } = useIngestToken();
  useEffect(() => {
    // Auto-issue: this screen's design has no "Create a connection token" button, so the token has
    // to exist before the user ever looks at the Header field. Runs once, on mount — `token`/`issue`
    // deliberately excluded from the deps array, since including them would re-fire this on every
    // token-state change instead of exactly once.
    issue();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (hasTaggedSpan) nav(`/orgs/${orgSlug}/projects/${projectSlug}/traces`, { replace: true });
  }, [hasTaggedSpan, nav, orgSlug, projectSlug]);

  const endpoint = `${typeof window !== "undefined" ? window.location.origin : ""}/v1/traces`;
  const untagged = status.data ? status.data.untagged_spans > 0 && status.data.spans_received > 0 : false;

  // Same queryKey ProjectShell/OrgRedirect/Sidebar already use, so this is a cache read in the
  // common case rather than a third request. It decides which escape hatch this gate offers.
  const projects = useQuery({
    queryKey: ["projects", orgSlug],
    queryFn: () => auth.listProjects(orgSlug),
  });
  const otherProjects = (projects.data ?? []).filter(
    (p) => p.slug !== projectSlug && !isSampleProject(p),
  );

  const sample = useMutation({
    mutationFn: () => auth.ensureSampleProject(orgSlug),
    onSuccess: async (project) => {
      // The route pattern (projectSlug only) doesn't change, so ProjectShell/Sidebar don't remount
      // and would otherwise keep serving this org's pre-creation project list -- the new sample
      // project would then fail to resolve by slug, isSample would default to false, and the
      // sample banner + gate-skip both silently break. Invalidate before navigating so
      // ['projects', orgSlug] is refetched by the time ProjectShell mounts for the new slug.
      await qc.invalidateQueries({ queryKey: ["projects", orgSlug] });
      nav(`/orgs/${orgSlug}/projects/${project.slug}/triage`, { replace: true });
    },
    onError: () => toast.error("Could not start the sample project"),
  });

  // ProjectShell already gated on has_tagged_span before mounting this component, so this only ever
  // fires from a TRUE poll transition during this mount (the redirect effect above then fires too) —
  // rendering null for that one tick avoids a flash of the connect screen after the flip.
  if (hasTaggedSpan) return null;

  if (untagged) {
    return (
      <UntaggedScreen
        email={user?.email}
        spansReceived={status.data?.spans_received ?? 0}
        taggedSpans={status.data?.tagged_spans ?? 0}
        lastSpanAt={status.data?.last_span_at ?? null}
        serviceName={status.data?.service_name ?? null}
      />
    );
  }

  return (
    <GateShell email={user?.email}>
      <div>
        <div className="text-label uppercase text-muted mb-2.5">Step 2 of 2</div>
        <h1 className="text-[28px] leading-[1.3] font-semibold tracking-[-0.01em]">Connect your traces</h1>
      </div>

      <PromptBlock label="Paste into your coding agent" prompt={OTLP_PROMPT} onCopy={() => toast.success("Prompt copied")} />

      <div className="flex flex-col gap-3">
        <CopyField label="Endpoint" value={endpoint} onCopy={() => toast.success("Copied")} />
        <CopyField
          label="Bearer Token"
          value={token ? elide(token) : "…"}
          copyValue={token ?? null}
          onCopy={() => toast.success("Copied")}
        />
      </div>

      <ListeningBanner label="Listening on /v1/traces. Nothing has arrived yet." />

      {otherProjects.length > 0 ? (
        <ExistingProjects orgSlug={orgSlug} projects={otherProjects} />
      ) : (
        <div className="text-small text-subtle -mt-2">
          {sample.isPending ? (
            <span className="inline-flex items-center gap-1.5 text-muted">
              <Spinner size="sm" />
              Setting up your sample project…
            </span>
          ) : (
            <button
              type="button"
              onClick={() => sample.mutate()}
              className="text-link hover:text-link-hover underline underline-offset-2"
            >
              Start with a sample project
            </button>
          )}
        </div>
      )}
    </GateShell>
  );
}

/**
 * The second-project escape hatch, as progressive disclosure rather than a link.
 *
 * Someone who just created a project and is staring at a connect screen has one plausible other
 * intent: they wanted a project they already have. Navigating them to a switcher to find out would
 * cost them this screen — the endpoint and the freshly-minted header are on it — so the list opens
 * in place, and only if they ask for it. Closed, it is one line of text and no claim about how many
 * projects exist; open, it is the org's other real projects, each a direct way in.
 *
 * The sample project is filtered out by the caller for the same reason every other implicit
 * "which project" resolution filters it: it is a deletable demo row, not somewhere to send someone
 * who is looking for their own work.
 */
function ExistingProjects({ orgSlug, projects }: { orgSlug: string; projects: Project[] }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="-mt-2">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        className="flex items-center gap-1 text-small text-link hover:text-link-hover"
      >
        <ChevronRight
          size={13}
          strokeWidth={2}
          aria-hidden="true"
          className="transition-transform"
          style={{ transform: open ? "rotate(90deg)" : "none", transitionDuration: "var(--duration-micro)" }}
        />
        Check existing projects
      </button>

      {open && (
        <ul className="mt-2.5 flex flex-col rounded-card border border-border-strong bg-surface overflow-hidden">
          {projects.map((p) => (
            <li key={p.id} className="border-b border-border last:border-b-0">
              <Link
                to={`/orgs/${orgSlug}/projects/${p.slug}/triage`}
                className="flex items-baseline gap-2 px-4 py-2.5 hover:bg-raised transition-colors"
                style={{ transitionDuration: "var(--duration-micro)" }}
              >
                <span className="text-small text-fg truncate">{p.name}</span>
                <span className="font-mono text-label text-subtle truncate">{p.slug}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function UntaggedScreen({
  email,
  spansReceived,
  taggedSpans,
  lastSpanAt,
  serviceName,
}: {
  email?: string;
  spansReceived: number;
  taggedSpans: number;
  lastSpanAt: string | null;
  serviceName: string | null;
}) {
  const toast = useToast();
  return (
    <GateShell email={email}>
      <div>
        <div className="text-label uppercase text-muted mb-2.5">Step 2 of 2</div>
        <h1 className="text-[28px] leading-[1.3] font-semibold tracking-[-0.01em]">
          Spans are arriving without a call site
        </h1>
      </div>

      <div className="flex gap-[34px] py-4 border-t border-b border-border-strong">
        <Stat label="Spans received" value={spansReceived.toLocaleString()} />
        <Stat label="Tagged" value={taggedSpans.toLocaleString()} tone={taggedSpans === 0 ? "error" : undefined} />
        <Stat label="Last span" value={relativeTime(lastSpanAt)} />
        <Stat label="Service" value={serviceName ?? "—"} mono />
      </div>

      <p className="text-body text-fg-secondary leading-relaxed">
        Your exporter is working. Classifiers group traces by call site, so spans without{" "}
        <code className="font-mono text-fg">tessary.call_site.id</code> cannot be baselined.
      </p>

      <PromptBlock
        label="Paste into your coding agent"
        prompt={TAG_REPAIR_PROMPT}
        onCopy={() => toast.success("Prompt copied")}
      />
    </GateShell>
  );
}

function GateShell({ email, children }: { email?: string; children: React.ReactNode }) {
  return (
    <div className="min-h-screen bg-bg text-fg flex flex-col">
      <header className="flex items-center justify-between px-14 py-6">
        <div className="flex items-center gap-2.5">
          <img src="/tessary-logo.png" alt="" className="size-[17px] shrink-0 rounded-control" />
          <span className="text-[14px] font-semibold tracking-[-0.01em]">tessary</span>
        </div>
        {email && <span className="text-small text-subtle">{email}</span>}
      </header>
      <div className="flex-1 flex justify-center px-6 pb-16" style={{ paddingTop: "64px" }}>
        <div className="w-full max-w-[560px] flex flex-col gap-7">{children}</div>
      </div>
    </div>
  );
}

/**
 * The connect/repair prompt anatomy — eyebrow + code surface (the guide's URL bright) + Copy button.
 *
 * The bright token used to be `tessary.call_site.id`, back when the prompt spelled the whole ask out
 * inline. Both prompts are now one sentence pointing at `instrument.md`, so the URL is the part a
 * reader's eye should land on: it is the only thing in the sentence they can go and check.
 */
function PromptBlock({ label, prompt, onCopy }: { label: string; prompt: string; onCopy: () => void }) {
  const parts = prompt.split(INSTRUMENT_DOC_URL);
  return (
    <div>
      <div className="flex items-center gap-1.5 text-label uppercase text-muted mb-2.5">
        <CodeXml size={13} strokeWidth={1.8} aria-hidden="true" />
        {label}
      </div>
      <div className="rounded-card border border-border-strong bg-surface p-4">
        <code className="font-mono text-small text-fg-secondary leading-[1.75] whitespace-pre-wrap">
          {parts.map((part, i) => (
            <span key={i}>
              {part}
              {i < parts.length - 1 && <span className="text-fg">{INSTRUMENT_DOC_URL}</span>}
            </span>
          ))}
        </code>
      </div>
      <CopyButton
        value={prompt}
        label="Copy prompt"
        variant="primary"
        size="md"
        className="mt-3 font-medium"
        onCopied={onCopy}
      />
    </div>
  );
}

function Stat({ label, value, tone, mono }: { label: string; value: string; tone?: "error"; mono?: boolean }) {
  return (
    <div>
      <div className="text-label uppercase text-muted mb-1">{label}</div>
      <div
        className={`text-h2 font-semibold tabular-nums ${mono ? "font-mono text-[16px] pt-1" : ""} ${
          tone === "error" ? "text-error" : "text-fg"
        }`}
      >
        {value}
      </div>
    </div>
  );
}

function elide(token: string): string {
  if (token.length <= 12) return token;
  return `${token.slice(0, 8)}…${token.slice(-4)}`;
}
