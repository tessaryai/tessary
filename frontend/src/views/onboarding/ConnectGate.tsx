// SPDX-License-Identifier: Apache-2.0
import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronRight, Copy } from "lucide-react";
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
import { PromptBlock } from "../components/PromptBlock";
import { Button, Spinner, useToast, writeClipboard } from "../../ui";

/**
 * Stands in for the whole shell until a span carrying `tessary.call_site.id` lands; ProjectShell decides
 * whether to mount it. The ingest token is minted from the Bearer Token field's click, never on mount,
 * which left a live key per render.
 */
export function ConnectGate() {
  const { orgSlug, projectSlug } = useTenant();
  const api = useProjectApi();
  const { user } = useAuth();
  const nav = useNavigate();
  const toast = useToast();
  const qc = useQueryClient();

  // Same queryKey ProjectShell polls, so this shares its cache entry rather than adding a poll.
  const status = useQuery({
    queryKey: ["substrate-status", api.base],
    queryFn: api.substrateStatus,
    refetchInterval: 3500,
  });
  const hasTaggedSpan = status.data?.has_tagged_span ?? false;

  const { token, issueAsync, issuing } = useIngestToken();
  // Mint succeeded but the clipboard write failed: the key is unrecoverable after this render.
  const [revealed, setRevealed] = useState(false);

  async function mintAndCopy() {
    let plaintext: string;
    try {
      plaintext = await issueAsync();
    } catch {
      return; // useIngestToken toasts the reason
    }
    if (await writeClipboard(plaintext)) {
      toast.success("Copied");
      return;
    }
    setRevealed(true);
    toast.error("Could not copy the token", "It is on screen in full. Select it to copy it by hand.");
  }

  useEffect(() => {
    if (hasTaggedSpan) nav(`/orgs/${orgSlug}/projects/${projectSlug}/traces`, { replace: true });
  }, [hasTaggedSpan, nav, orgSlug, projectSlug]);

  const endpoint = `${window.location.origin}/v1/traces`;

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
      // The route pattern does not change, so ProjectShell would resolve the sample's slug against a stale
      // project list without this.
      await qc.invalidateQueries({ queryKey: ["projects", orgSlug] });
      nav(`/orgs/${orgSlug}/projects/${project.slug}/triage`, { replace: true });
    },
    onError: () => toast.error("Could not start the sample project"),
  });

  // Avoids a flash of the connect screen on the tick the poll flips.
  if (hasTaggedSpan) return null;

  if (status.data && status.data.untagged_spans > 0) {
    return (
      <UntaggedScreen
        email={user?.email}
        spansReceived={status.data.spans_received}
        taggedSpans={status.data.tagged_spans}
        lastSpanAt={status.data.last_span_at}
        serviceName={status.data.service_name}
      />
    );
  }

  return (
    <GateShell email={user?.email}>
      <div>
        <div className="text-label uppercase text-muted mb-2.5">Step 2 of 2</div>
        <h1 className="text-[28px] leading-[1.3] font-semibold tracking-[-0.01em]">Connect your traces</h1>
      </div>

      <PromptBlock
        label="Paste into your coding agent"
        prompt={OTLP_PROMPT}
        highlight={INSTRUMENT_DOC_URL}
        onCopy={() => toast.success("Prompt copied")}
      />

      <div className="flex flex-col gap-3">
        <CopyField label="Endpoint" value={endpoint} onCopy={() => toast.success("Copied")} />
        {token ? (
          <CopyField
            label="Bearer Token"
            value={revealed ? token : elide(token)}
            copyValue={token}
            onCopy={() => toast.success("Copied")}
          />
        ) : (
          <MintTokenField pending={issuing} onMint={mintAndCopy} />
        )}
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

/** Opens in place: navigating away would cost the endpoint and freshly minted header on this screen. */
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
        highlight={INSTRUMENT_DOC_URL}
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

/** The button says it writes a key that cannot be shown again, which a bare "Copy" does not promise. */
function MintTokenField({ pending, onMint }: { pending: boolean; onMint: () => void }) {
  return (
    <div>
      <div className="text-label uppercase text-muted mb-1.5">Bearer Token</div>
      <div className="flex items-center gap-2 rounded-control border border-border-strong bg-surface px-2.5 py-2">
        <code className="flex-1 font-mono text-small text-subtle overflow-hidden text-ellipsis whitespace-nowrap">
          tsy_w_…
        </code>
        <Button
          type="button"
          variant="secondary"
          size="sm"
          loading={pending}
          onClick={onMint}
          leadingIcon={<Copy size={13} strokeWidth={1.8} aria-hidden="true" />}
          className="font-medium flex-none"
        >
          {pending ? "Creating" : "Create and copy"}
        </Button>
      </div>
    </div>
  );
}

function elide(token: string): string {
  return `${token.slice(0, 8)}…${token.slice(-4)}`;
}
