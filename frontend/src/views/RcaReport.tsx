// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { Info } from "lucide-react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useProjectApi, useTenant } from "../tenant/TenantContext";
import { Badge, Button, Card, PageBody, PageHeader, Spinner, StatusPill, cn, type BadgeTone } from "../ui";
import { Markdown } from "./components/PayloadViewer";
import { RCA_JOB_STATUS, RCA_VERDICT_LABEL, RCA_VERDICT_TONE, rcaRunning } from "./rcaLabels";
import { ConnectRepositoryDialog } from "./components/ConnectRepositoryDialog";
import { useRepoPrompt } from "./components/useRepoPrompt";
import type { RcaHypothesis, RcaRuledOutCheck } from "../api/types";

/**
 * One RCA report — the immutable per-finding drill-in behind the case page's "Run RCA" action.
 * Reads top-to-bottom the way the analysis ran: the movement, the verdict + summary, the checklist
 * of structural causes with the analysis's call on each (shown even when all were ruled out — the
 * eliminated boring causes are what make the hypotheses trustworthy), then ranked hypotheses whose
 * evidence links open the real traces, then the agent's full write-up.
 */

const METRIC_LABEL: Record<string, string> = {
  pass_rate: "Pass rate",
  score: "Score",
};

const SUBJECT_LABEL: Record<string, string> = {
  grader: "Grader",
  call_site: "Call site",
};

const CONFIDENCE_TONE: Record<string, BadgeTone> = {
  high: "error",
  medium: "warning",
  low: "neutral",
};

const pct = (value: number) => `${Math.round(value * 100)}%`;

function windowLabel(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleString(undefined, { month: "short", day: "numeric", hour: "numeric" });
}

/** How each checklist item renders. Reports written before the checks became subjective carry no
 *  `assessment`, so those fall back to the old pass/fail glyph. */
const ASSESSMENT: Record<string, { glyph: string; label: string; className: string }> = {
  ruled_out: { glyph: "✓", label: "Ruled out", className: "bg-success-subtle text-success" },
  contributing: { glyph: "!", label: "Contributing", className: "bg-warning-subtle text-warning" },
  explains: { glyph: "✗", label: "Explains the movement", className: "bg-error-subtle text-error" },
  unknown: { glyph: "?", label: "Unresolved", className: "bg-surface-2 text-muted" },
};

function ChecklistList({ checks }: { checks: RcaRuledOutCheck[] }) {
  return (
    <Card className="border border-border">
      <div className="px-3.5 pt-3.5 pb-1">
        <div className="text-h3 text-fg">Checklist</div>
        <p className="text-small text-muted mt-0.5">
          Structural causes measured before the investigation, each assessed by the analysis against your repo. An
          item marked <span className="text-fg">explains</span> is the root cause.
        </p>
      </div>
      <div className="flex flex-col gap-0.5 px-1.5 pb-2.5">
        {checks.map((c) => {
          const state = ASSESSMENT[c.assessment ?? (c.passed ? "ruled_out" : "explains")] ?? ASSESSMENT.unknown;
          return (
            <div key={c.check} className="flex items-start gap-3 px-2 py-2 rounded-card">
              <span
                className={cn(
                  "mt-0.5 size-4 flex-none rounded-pill text-label font-medium inline-flex items-center justify-center",
                  state.className,
                )}
                title={state.label}
                aria-label={state.label}
              >
                {state.glyph}
              </span>
              <div className="min-w-0">
                <span className="text-body font-medium text-fg font-mono">{c.check}</span>
                <p className="text-small text-muted mt-0.5">{c.detail}</p>
                {c.measurement && (
                  <pre className="text-label text-subtle font-mono mt-1.5 whitespace-pre-wrap break-words">
                    {c.measurement}
                  </pre>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </Card>
  );
}

function HypothesisCard({ hypothesis, rank, exploreBase }: { hypothesis: RcaHypothesis; rank: number; exploreBase: string }) {
  return (
    <Card className="border border-border">
      <div className="px-3.5 py-3">
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-label text-subtle tabular-nums">#{rank}</span>
          <span className="text-body font-medium text-fg">{hypothesis.title}</span>
          <Badge tone={CONFIDENCE_TONE[hypothesis.confidence] ?? "neutral"}>{hypothesis.confidence} confidence</Badge>
        </div>
        <p className="text-small text-muted mt-1.5">{hypothesis.rationale}</p>
        {hypothesis.evidence_trace_ids.length > 0 && (
          <div className="flex items-center gap-2 flex-wrap mt-2.5">
            <span className="text-label uppercase text-subtle">Evidence</span>
            {hypothesis.evidence_trace_ids.map((traceId) => (
              <Link
                key={traceId}
                to={`${exploreBase}?trace=${encodeURIComponent(traceId)}`}
                className="font-mono text-label text-link hover:text-link-hover hover:underline"
                title={traceId}
              >
                trace …{traceId.slice(-8)}
              </Link>
            ))}
          </div>
        )}
      </div>
    </Card>
  );
}

export function RcaReport() {
  const { reportId = "" } = useParams<{ reportId: string }>();
  const { orgSlug, projectSlug } = useTenant();
  const api = useProjectApi();
  const navigate = useNavigate();
  const { canPrompt: canPromptRepo } = useRepoPrompt();
  const [connectRepoOpen, setConnectRepoOpen] = useState(false);

  const report = useQuery({
    queryKey: ["rca-report", api.base, reportId],
    queryFn: () => api.getRcaReport(reportId),
    refetchInterval: (q) => (q.state.data && rcaRunning(q.state.data.status) ? 2000 : false),
  });

  const base = `/orgs/${orgSlug}/projects/${projectSlug}`;

  // A report is immutable, so re-running produces a NEW one and we navigate to it. The common
  // failure is RCA.NOT_A_MOVER — the subject has stopped moving, so there is nothing to snapshot;
  // surface the API's own message rather than a bare retry.
  const rerun = useMutation({
    mutationFn: () => api.rerunRca(reportId),
    onSuccess: (fresh) => navigate(`${base}/rca/${fresh.id}`),
  });
  const rerunError = rerun.isError
    ? rerun.error.message.split(": ").slice(1).join(": ") || rerun.error.message
    : null;

  if (report.isLoading) {
    return (
      <PageBody>
        <div className="flex justify-center py-12">
          <Spinner size="lg" />
        </div>
      </PageBody>
    );
  }

  const r = report.data;
  if (!r) {
    return (
      <PageBody>
        <div className="text-small text-muted">This RCA report could not be found. It may have been deleted.</div>
      </PageBody>
    );
  }

  const running = rcaRunning(r.status);
  const worse = r.delta < 0;

  return (
    <PageBody>
      <PageHeader
        eyebrow={
          <Link to={`${base}/triage`} className="hover:text-fg transition-colors">
            Back to Triage
          </Link>
        }
        title={
          <span className="flex items-center gap-3">
            <span>RCA (root-cause analysis): {r.subject_label}</span>
            <StatusPill status={RCA_JOB_STATUS[r.status] ?? "pending"} label={r.status} />
          </span>
        }
        subtitle={`${SUBJECT_LABEL[r.subject_kind] ?? r.subject_kind} · ${METRIC_LABEL[r.metric] ?? r.metric} ${
          worse ? "fell" : "moved"
        } to ${pct(r.current_value)} from ${pct(r.prior_value)} · ${windowLabel(r.window_split)} onward vs the 24h before`}
        actions={
          running ? undefined : (
            <span className="flex items-center gap-2">
              <Button
                variant="secondary"
                size="sm"
                disabled={rerun.isPending}
                onClick={() => {
                  if (!rerun.isPending) rerun.mutate();
                }}
                title={rerunError ?? "Analyze this degradation again against the latest traces"}
              >
                {rerun.isPending ? "Starting…" : "Re-run RCA"}
              </Button>
            </span>
          )
        }
      />

      {rerunError && (
        <div
          className="rounded-card border border-[color:var(--color-error)] px-3 py-2 text-small text-error mb-6"
          style={{ backgroundColor: "var(--color-error-subtle)" }}
        >
          <span className="font-medium">Could not re-run:</span> {rerunError}
        </div>
      )}

      {running && (
        <div className="py-6 flex items-center gap-2 text-small text-muted">
          <Spinner size="sm" />
          {r.engine === "agentic"
            ? "Analyzing. Tessary measures the structural causes, then an agent reads your repo and traces. This can take several minutes."
            : "Analyzing. Ruling out the structural causes and diffing the failing cohort…"}
        </div>
      )}

      {r.status === "failed" && (
        <div
          className="rounded-card border border-[color:var(--color-error)] px-3 py-2 text-small text-error mb-6"
          style={{ backgroundColor: "var(--color-error-subtle)" }}
        >
          <span className="font-medium">The analysis failed:</span> {r.summary ?? "unknown error"}. Select
          Re-run RCA to try again.
        </div>
      )}

      {/* A run that had no repository could show what changed in production and not what changed in
          the code. That ceiling belongs above the verdict, because it qualifies the verdict — until
          now it existed only as a sentence the agent wrote into the markdown body, where it reads as
          part of the analysis rather than as a limit on it. `repo_available` is null on reports
          written before it was recorded: unknown, which must not render as "no repository". */}
      {!running && r.status === "done" && r.repo_available === false && (
        <div
          className="rounded-card border border-[color:var(--color-info)] px-3 py-2 mb-6 flex items-center justify-between gap-4"
          style={{ backgroundColor: "var(--color-info-subtle)" }}
        >
          <div className="flex items-start gap-2">
            <Info size={13} strokeWidth={1.75} aria-hidden="true" className="text-info shrink-0 mt-0.5" />
            <span className="text-small text-fg-secondary">
              <span className="font-medium text-fg">Analyzed without repository access.</span> Tessary analyzed trace
              evidence only and could not check code changes. Connect a repository to include code in the next
              analysis.
            </span>
          </div>
          {canPromptRepo && (
            <Button size="sm" variant="secondary" className="shrink-0" onClick={() => setConnectRepoOpen(true)}>
              Connect repository
            </Button>
          )}
        </div>
      )}

      {!running && r.status === "done" && (
        <div className="flex flex-col gap-5">
          {r.verdict && (
            <Card className="border border-border">
              <div className="px-3.5 py-3">
                <div className="flex items-center gap-2.5 flex-wrap">
                  <span className="text-label uppercase text-subtle">Verdict</span>
                  <Badge tone={RCA_VERDICT_TONE[r.verdict] ?? "neutral"}>
                    {RCA_VERDICT_LABEL[r.verdict] ?? r.verdict}
                  </Badge>
                </div>
                {r.summary && <p className="text-small text-fg mt-2">{r.summary}</p>}
              </div>
            </Card>
          )}

          {r.ruled_out.length > 0 && <ChecklistList checks={r.ruled_out} />}

          {r.hypotheses.length > 0 && (
            <div>
              <div className="text-h3 text-fg mb-2">Hypotheses</div>
              <div className="flex flex-col gap-2.5">
                {r.hypotheses.map((h, i) => (
                  <HypothesisCard key={i} hypothesis={h} rank={i + 1} exploreBase={`${base}/explore`} />
                ))}
              </div>
            </div>
          )}

          {r.detailed_report && (
            <Card className="border border-border">
              <div className="px-3.5 py-3">
                <div className="text-h3 text-fg mb-2">Detailed analysis</div>
                <div className="text-small text-fg">
                  <Markdown>{r.detailed_report}</Markdown>
                </div>
              </div>
            </Card>
          )}
        </div>
      )}

      <ConnectRepositoryDialog open={connectRepoOpen} onClose={() => setConnectRepoOpen(false)} />
    </PageBody>
  );
}
