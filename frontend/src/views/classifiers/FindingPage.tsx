// SPDX-License-Identifier: Apache-2.0
// Measure, then workload, then decomposition: the order the ruling is made in. The ruling's citations
// are shown in full, check scripts included, since they are the part a reader can re-run.
import { useEffect } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "react-router-dom";
import type { BehaviorFindingDetail, TriageCitation } from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import { ErrorNote, LoadingRow, PageHeader, StatusPill, cn } from "../../ui";
import { useCapabilities } from "../../capabilities/useCapabilities";
import { RunBar } from "../components/RunBar";
import {
  CONTAINER, FindingKicker, ResolveVerbs, RunTriageButton, TRIAGE_POLL_MS, TriageAction, chainWords, detectorLabel,
  isTriageWaiting, stamp, triageBar, triageState, useNow,
} from "./shared";
import { PatternBlock } from "./findingCharts";
import {
  ShiftBehind,
  ShiftChart,
  ShiftMean,
  ShiftPins,
  formatMeasure,
  measureNoun,
  referenceWords,
  toneOf,
  toneTextClass,
} from "./shiftStory";
import { RateChart, RatePins, formatRate, rateToneOf, rateToneTextClass } from "./rateStory";
import { LeakPins, LeakTimeline, SecretHeader } from "./secretStory";
import { HowOutputsBroke, MalformedHeader, MalformedRate } from "./malformedStory";
import {
  FRUSTRATION_LIST_COPY,
  FrustrationHeader,
  FrustrationRate,
  frustrationLoader,
  frustrationRows,
} from "./frustrationStory";
import { FlaggedList } from "./FlaggedList";
import { GroundednessHeader, GroundednessRate } from "./groundednessStory";
import { ClassifierHeader, ClassifierRate } from "./classifierStory";
import { FlaggedAnswers } from "./FlaggedAnswers";
import { EvidenceTable } from "./EvidenceTable";
import { traceLinker } from "../traceLinker";

type Detail = BehaviorFindingDetail;

export function FindingPage() {
  const { api, orgSlug, projectSlug } = useTenant();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const { findingId = "" } = useParams();
  /* Absolute: React Router resolves `..` against the flat route, which would land on the project root. */
  const basePath = `/orgs/${orgSlug}/projects/${projectSlug}`;
  const traceLink = traceLinker(basePath);

  const detailQ = useQuery({
    queryKey: ["behavior-finding", api.base, findingId],
    queryFn: () => api.getBehaviorFinding(findingId),
    enabled: findingId !== "",
    refetchInterval: (q) => (isTriageWaiting(q.state.data?.finding) ? TRIAGE_POLL_MS : false),
  });
  const triageStatus = detailQ.data?.finding.triageStatus;
  const canProviders = useCapabilities().isEnabled("byo_provider_keys_enabled");
  const now = useNow(triageStatus === "queued" || triageStatus === "in_flight");
  /* The lists cache this finding's row; a run that moves on here would read as stuck there until they go stale. */
  useEffect(() => {
    if (triageStatus === undefined) return;
    void qc.invalidateQueries({ queryKey: ["behavior-findings", api.base] });
    void qc.invalidateQueries({ queryKey: ["classifier-findings", api.base] });
  }, [qc, api.base, triageStatus]);

  const done = () => {
    void qc.invalidateQueries({ queryKey: ["behavior-findings", api.base] });
    navigate("..");
  };
  const resolveM = useMutation({
    mutationFn: (action: "expected" | "not_expected") => api.resolveBehaviorFinding(findingId, action),
    onSuccess: done,
  });
  const analyzeM = useMutation({
    mutationFn: () => api.analyzeBehaviorFinding(findingId),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["behavior-finding", api.base, findingId] }),
  });

  if (detailQ.isError) return <div style={CONTAINER}><ErrorNote error={detailQ.error} /></div>;
  if (!detailQ.data) return <div style={CONTAINER}><LoadingRow /></div>;

  const detail: Detail = detailQ.data;
  const finding = detail.finding;
  const triaged = finding.triageStatus === "done";
  const busy = resolveM.isPending || analyzeM.isPending;
  const state = triageState(finding);
  const bar = triageBar(finding, basePath, canProviders, now, api);
  /* A ruling, or a run under the header, sets the first section apart from what sits above it. */
  const divided = triaged || bar != null;

  const shift = detail.metric;
  const rate = detail.toolError;
  const secretLeak = detail.secretLeak;
  const malformedOutput = detail.malformedOutput;
  const frustration = detail.frustration;
  const groundedness = detail.groundedness;
  const classifierDetail = detail.classifierDetail;
  const story = shift ?? rate ?? secretLeak ?? malformedOutput ?? frustration ?? groundedness ?? classifierDetail;

  return (
    <div style={CONTAINER}>
      {shift ? (
        <ShiftHeader shift={shift} finding={finding} busy={busy} onAnalyze={() => analyzeM.mutate()} />
      ) : rate ? (
        <RateHeader rate={rate} finding={finding} busy={busy} onAnalyze={() => analyzeM.mutate()} />
      ) : secretLeak ? (
        <SecretHeader
          secretLeak={secretLeak}
          finding={finding}
          basePath={basePath}
          busy={busy}
          onAnalyze={() => analyzeM.mutate()}
        />
      ) : frustration ? (
        <FrustrationHeader finding={finding} basePath={basePath} />
      ) : groundedness ? (
        <GroundednessHeader
          finding={finding}
          detail={groundedness}
          basePath={basePath}
          busy={busy}
          onAnalyze={() => analyzeM.mutate()}
          onResolve={(action) => resolveM.mutate(action)}
        />
      ) : malformedOutput ? (
        <MalformedHeader
          rate={malformedOutput.rate}
          finding={finding}
          basePath={basePath}
          busy={busy}
          onAnalyze={() => analyzeM.mutate()}
        />
      ) : classifierDetail ? (
        <ClassifierHeader
          finding={finding}
          basePath={basePath}
          busy={busy}
          onAnalyze={() => analyzeM.mutate()}
          onResolve={(action) => resolveM.mutate(action)}
        />
      ) : (
        <>
          <PageHeader
            breadcrumb={[{ label: "Triage", to: `${basePath}/triage` }, { label: "Finding" }]}
            kicker={finding.detector ? detectorLabel(finding.detector) : "Finding"}
            title={finding.title}
          />
          <div className="flex flex-wrap items-center gap-2.5 mt-2.5">
            <span className="text-subtle text-small">
              {chainWords(finding)}
            </span>
            {finding.callSiteId && (
              <span className="font-mono text-subtle text-small">
                {finding.callSiteId}
              </span>
            )}
            {triaged && finding.triageAction === "closed" && <StatusPill status="skipped" label={state.label} />}
          </div>
          <div className="flex flex-wrap items-center gap-2 mt-4">
            {!triaged && (
              <RunTriageButton finding={finding} busy={busy} onAnalyze={() => analyzeM.mutate()} />
            )}
            {/* A ruling freezes the finding: every verb on it 409s. */}
            {!triaged && (
              <ResolveVerbs busy={busy} onResolve={(action) => resolveM.mutate(action)} />
            )}
            {finding.caseId && (
              <Link
                to={`${basePath}/cases/${encodeURIComponent(finding.caseId)}`}
                className="text-link hover:text-link-hover text-small"
              >
                Opened case →
              </Link>
            )}
          </div>
        </>
      )}

      {resolveM.isError && <ErrorNote error={resolveM.error} />}
      {analyzeM.isError && <ErrorNote error={analyzeM.error} />}
      {bar && <RunBar {...bar} />}

      {/* The ruling sits above the evidence; its receipts come after. A frustration finding states its ruling
         in its own header. */}
      {story && triaged && !frustration && (
        <div
          className="flex flex-col rounded-card border border-border-strong bg-surface gap-2.5 mt-5 py-4.25 px-4.75">
          {finding.triageSummary && (
            <p className="text-body font-medium text-fg m-0" style={{ maxWidth: 700 }}>
              {finding.triageSummary}
            </p>
          )}
          {finding.caseId && (
            <Link
              to={`${basePath}/cases/${encodeURIComponent(finding.caseId)}`}
              className="text-link hover:text-link-hover text-small"
            >
              Opened case →
            </Link>
          )}
        </div>
      )}

      {shift && (
        <section
          className={cn("flex flex-col gap-2.75", divided && "border-t border-border")}
          style={{ marginTop: divided ? 28 : 24, paddingTop: divided ? 22 : 0 }}
        >
          <div className="flex items-baseline gap-3">
            <h2 className="font-mono text-label uppercase text-muted">What moved</h2>
            <span className="text-subtle text-small">
              median to 95th percentile, log scale
            </span>
          </div>
          <ShiftChart shift={shift} />
          <ShiftPins shift={shift} />
          <ShiftMean shift={shift} />
        </section>
      )}
      {shift && <ShiftBehind shift={shift} />}

      {rate && (
        <section
          className={cn("flex flex-col gap-2.75", divided && "border-t border-border")}
          style={{ marginTop: divided ? 28 : 24, paddingTop: divided ? 22 : 0 }}
        >
          <div className="flex items-baseline gap-3">
            <h2 className="font-mono text-label uppercase text-muted">What moved</h2>
            <span className="text-subtle text-small">
              share of calls that failed
            </span>
          </div>
          <RateChart rate={rate} />
          <RatePins rate={rate} />
        </section>
      )}
      {rate && <ToolErrorEvidence rate={rate} />}

      {secretLeak && (
        <section
          className={cn("flex flex-col gap-2.75", divided && "border-t border-border")}
          style={{ marginTop: divided ? 28 : 24, paddingTop: divided ? 22 : 0 }}
        >
          <div className="flex items-baseline gap-3">
            <h2 className="font-mono text-label uppercase text-muted">When it leaked</h2>
            <span className="text-subtle text-small">one dot per leaking output, one lane per key</span>
          </div>
          <LeakTimeline secretLeak={secretLeak} />
          <LeakPins secretLeak={secretLeak} linkToTrace={traceLink} />
        </section>
      )}

      {malformedOutput && (
        <>
          <section
            className={cn("flex flex-col gap-2.75", divided && "border-t border-border")}
            style={{ marginTop: divided ? 28 : 24, paddingTop: divided ? 22 : 0 }}
          >
            <div className="flex items-baseline gap-3">
              <h2 className="font-mono text-label uppercase text-muted">What moved</h2>
              <span className="text-subtle text-small">share of outputs that failed their schema</span>
            </div>
            <MalformedRate rate={malformedOutput.rate} />
          </section>
          <section className="mt-7">
            <div className="flex items-baseline gap-3 mb-1.5">
              <h2 className="font-mono text-label uppercase text-muted">How outputs broke</h2>
              <span className="text-subtle text-small">
                each schema field with its failures, beside one failing output
              </span>
            </div>
            <HowOutputsBroke findingId={findingId} detail={malformedOutput} linkToTrace={traceLink} />
          </section>
        </>
      )}

      {frustration && (
        <>
          <section
            className={cn("flex flex-col gap-2.75", divided && "border-t border-border")}
            style={{ marginTop: divided ? 28 : 24, paddingTop: divided ? 22 : 0 }}
          >
            <div className="flex items-baseline gap-3">
              <h2 className="font-mono text-label uppercase text-muted">What changed</h2>
              <span className="text-subtle text-small">Share of sessions with a user frustrated with the agent</span>
            </div>
            <FrustrationRate detail={frustration} />
          </section>
          <section className="mt-7">
            <div className="flex items-baseline gap-3 mb-2.75">
              <h2 className="font-mono text-label uppercase text-muted">Frustrated sessions</h2>
              <span className="text-subtle text-small">
                {frustration.conversations.length > 0 && frustration.conversations.every((c) => c.cleared)
                  ? "Cleared when the case was resolved as a false alarm. They no longer count toward the rate."
                  : "Each flagged message with the turns before it"}
              </span>
            </div>
            <FlaggedList
              {...FRUSTRATION_LIST_COPY}
              listKey={findingId}
              first={frustrationRows(frustration)}
              loadPage={frustrationLoader(api, findingId)}
              basePath={basePath}
            />
          </section>
        </>
      )}

      {groundedness && (
        <>
          <section
            className={cn("flex flex-col gap-2.75", divided && "border-t border-border")}
            style={{ marginTop: divided ? 28 : 24, paddingTop: divided ? 22 : 0 }}
          >
            <div className="flex items-baseline gap-3">
              <h2 className="font-mono text-label uppercase text-muted">What changed</h2>
              <span className="text-subtle text-small">Share of traces with a flagged answer</span>
            </div>
            <GroundednessRate detail={groundedness} />
          </section>
          <section className="mt-7">
            <div className="flex items-baseline gap-3 mb-2.75">
              <h2 className="font-mono text-label uppercase text-muted">Flagged answers</h2>
              <span className="text-subtle text-small">
                Marked sentences scored {groundedness.flagThreshold} or higher
              </span>
            </div>
            <FlaggedAnswers
              findingId={findingId}
              first={{ rows: groundedness.answers, nextCursor: groundedness.answersNextCursor }}
              traces={groundedness.rate.failuresCur}
              basePath={basePath}
            />
          </section>
        </>
      )}

      {classifierDetail && (
        <section
          className={cn("flex flex-col gap-2.75", divided && "border-t border-border")}
          style={{ marginTop: divided ? 28 : 24, paddingTop: divided ? 22 : 0 }}
        >
          <div className="flex items-baseline gap-3">
            <h2 className="font-mono text-label uppercase text-muted">What changed</h2>
            <span className="text-subtle text-small">{classifierDetail.words.figureNote}</span>
          </div>
          <ClassifierRate detail={classifierDetail} />
        </section>
      )}

      {!story && (
        <p className="text-subtle mt-6 text-body" style={{ maxWidth: 560 }}>
          This cause carries no measured shift. It is a claim about the shape of what the agent did
          rather than about a number that moved, so there is nothing here to plot.
        </p>
      )}

      {triaged && !story && <TriageRuling finding={finding} basePath={basePath} />}

      {!frustration && !groundedness && !classifierDetail && (
        <section className="mt-7">
          <h2 className="font-mono text-label uppercase text-muted mb-1.5">
            Evidence
          </h2>
          <EvidenceTable findingId={findingId} basePath={basePath} />
        </section>
      )}
    </div>
  );

  function ShiftHeader({
    shift: sh,
    finding: f,
    busy: disabled,
    onAnalyze,
  }: {
    shift: NonNullable<Detail["metric"]>;
    finding: Detail["finding"];
    busy: boolean;
    onAnalyze: () => void;
  }) {
    const p50 = sh.quantiles.find((q) => q.key === "p50");
    const tone = toneOf(sh);
    const bucket = sh.bucketKey !== f.callSiteId ? sh.bucketKey : null;
    const window =
      sh.windowOpenedAt && sh.windowClosedAt
        ? `${stamp(sh.windowOpenedAt)} → ${stamp(sh.windowClosedAt)}`
        : null;
    return (
      <PageHeader
        breadcrumb={[{ label: "Triage", to: `${basePath}/triage` }, { label: "Finding" }]}
        kicker={<FindingKicker finding={f} />}
        title={
          <span>
            {measureNoun(sh.measure)}{" "}
            <span className="font-mono font-medium text-subtle">
              {formatMeasure(sh.measure, p50?.then)}
            </span>{" "}
            <span className="text-border-strong">→</span>{" "}
            <span className={cn("font-mono font-medium", toneTextClass(tone))}>
              {formatMeasure(sh.measure, p50?.now)}
            </span>
          </span>
        }
        subtitle={
          <span title={f.causeKey}>
            {[bucket, window && `${window} vs ${referenceWords(sh.reference)}`].filter(Boolean).join(" · ")}
          </span>
        }
        actions={<TriageAction finding={f} busy={disabled} onAnalyze={onAnalyze} />}
      />
    );
  }

  function RateHeader({
    rate: r,
    finding: f,
    busy: disabled,
    onAnalyze,
  }: {
    rate: NonNullable<Detail["toolError"]>;
    finding: Detail["finding"];
    busy: boolean;
    onAnalyze: () => void;
  }) {
    const tone = rateToneOf(r);
    const since = r.onsetAt
      ? new Date(r.onsetAt).toLocaleDateString(undefined, { day: "numeric", month: "short" })
      : null;
    return (
      <PageHeader
        breadcrumb={[{ label: "Triage", to: `${basePath}/triage` }, { label: "Finding" }]}
        kicker={<FindingKicker finding={f} />}
        title={
          <span>
            Failure rate{" "}
            <span className="font-mono font-medium text-subtle">
              {formatRate(r.refRate)}
            </span>{" "}
            <span className="text-border-strong">→</span>{" "}
            <span className={cn("font-mono font-medium", rateToneTextClass(tone))}>
              {formatRate(r.curRate)}
            </span>
          </span>
        }
        subtitle={
          <span title={f.causeKey}>
            {[r.bucketKey, since && `since ${since} vs the rate it was fitted at`]
              .filter(Boolean)
              .join(" · ")}
          </span>
        }
        actions={<TriageAction finding={f} busy={disabled} onAnalyze={onAnalyze} />}
      />
    );
  }
}

function TriageRuling({ finding, basePath }: { finding: Detail["finding"]; basePath: string }) {
  const scripts = finding.triageCitations.filter((c) => c.stdout !== null);
  const pointers = finding.triageCitations.filter((c) => c.stdout === null);
  return (
    <section className="mt-7">
      <h2 className="font-mono text-label uppercase text-muted mb-1.5">
        What triage ruled
      </h2>
      <div className="flex flex-wrap items-baseline gap-2.5 mb-2">
        <span className="text-fg text-body">
          {VERDICT_WORDS[finding.triageVerdict ?? ""] ?? finding.triageVerdict}
        </span>
        {finding.caseId && (
          <Link
            to={`${basePath}/cases/${encodeURIComponent(finding.caseId)}`}
            className="text-link hover:text-link-hover text-small"
          >
            Opened case →
          </Link>
        )}
        {finding.triagedAt && (
          <span className="text-subtle text-small">
            {new Date(finding.triagedAt).toLocaleString()}
          </span>
        )}
      </div>
      {finding.triageSummary && (
        <p className="text-muted m-0 text-body" style={{ maxWidth: 720 }}>
          {finding.triageSummary}
        </p>
      )}

      {pointers.length > 0 && (
        <>
          <h3 className="font-mono text-label uppercase text-muted mt-5.5 mx-0 mb-1.5">
            What it read
          </h3>
          <ul className="m-0 p-0" style={{ listStyle: "none" }}>
            {pointers.map((c, i) => (
              <li key={`${c.path}-${i}`} className="text-subtle mt-1 text-small">
                <span className="font-mono text-muted">{c.path}</span>
                {c.reason && <>: {c.reason}</>}
              </li>
            ))}
          </ul>
        </>
      )}

      {scripts.length > 0 && (
        <>
          <h3 className="font-mono text-label uppercase text-muted mt-5.5 mx-0 mb-1.5">
            What it computed
          </h3>
          <p className="text-subtle mt-0 mx-0 mb-2.5 text-small" style={{ maxWidth: 620 }}>
            Scripts the agent wrote and ran against the evidence, with what they printed.
          </p>
          {scripts.map((c, i) => (
            <CheckScript key={`${c.path}-${i}`} citation={c} />
          ))}
        </>
      )}
    </section>
  );
}

const VERDICT_WORDS: Record<string, string> = {
  positive: "Positive: the claim holds, and a case is open on it.",
  negative: "Negative: the measurement is wrong, so there is nothing to explain.",
};

function CheckScript({ citation }: { citation: TriageCitation }) {
  return (
    <div className="rounded-card border border-border bg-surface py-2.5 px-3 mt-2.5">
      <div className="font-mono text-fg text-small">
        {citation.path}
      </div>
      {citation.reason && (
        <p className="text-muted mt-1.25 mx-0 mb-0 text-small" style={{ maxWidth: 640 }}>
          {citation.reason}
        </p>
      )}
      {citation.stdout && (
        <pre
          className="font-mono text-muted bg-raised rounded-control mt-2.25 mx-0 mb-0 py-2 px-2.5 text-label"
          style={{ overflowX: "auto", whiteSpace: "pre" }}
        >
          {citation.stdout}
        </pre>
      )}
    </div>
  );
}

/** The rate is not repeated: a percent change of a percentage is the most misleading way to write it. */
function ToolErrorEvidence({ rate }: { rate: NonNullable<Detail["toolError"]> }) {
  return (
    <>
      <PatternBlock
        patterns={rate.patterns}
        truncated={rate.patternsTruncated}
        elevated={rate.curRate > rate.refRate}
      />

      {rate.failingTraces.length > 0 && (
        <section className="mt-6">
          <h2 className="font-mono text-label uppercase text-muted mb-1.5">
            Failing traces
          </h2>
          <p className="text-subtle mt-0 mx-0 mb-2 text-small">
            A rate is a claim about a population. These are instances of it.
          </p>
          <div className="flex flex-wrap gap-1.5">
            {rate.failingTraces.map((id) => (
              <Link
                key={id}
                to={`../traces/${encodeURIComponent(id)}`}
                className="rounded-control border border-border-strong font-mono text-link hover:text-link-hover transition-colors py-0.5 px-2 text-label"
                style={{ transitionDuration: "var(--duration-micro)" }}
              >
                {id.slice(0, 10)}…
              </Link>
            ))}
          </div>
        </section>
      )}
    </>
  );
}
