// SPDX-License-Identifier: Apache-2.0
/*
 * One finding, drawn from its own evidence.
 *
 * This page shows the numbers: the measure's own quantiles then and now, the workload block
 * beside them, and for a cost shift the token decomposition that names what got more expensive.
 * Triage's ruling sits under the figures rather than in place of them, and it is a decision, not
 * a second opinion: it is what settled whether anyone was ever paged about this. So the citations
 * that back it are shown in full, including the check scripts the agent wrote, which is the only
 * part of a ruling a reader can re-run for themselves.
 *
 * The order is the argument: measure first (what moved), then workload (whether the input moved
 * with it), then the decomposition (which component of the measure carries it). That is the order
 * the ruling is made in, and reversing it would present the explanation before the thing being
 * explained.
 */
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "react-router-dom";
import type { BehaviorFindingDetail, EvidenceRef, TriageCitation } from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import { ErrorNote, LoadingRow, PageHeader, StatusPill, cn } from "../../ui";
import { CONTAINER, ResolveVerbs, VerbButton, chainWords, detectorLabel, triageState } from "./shared";
import { PatternBlock } from "./findingCharts";
import {
  ShiftBehind,
  ShiftChart,
  ShiftPins,
  formatMeasure,
  measureNoun,
  referenceWords,
  toneOf,
  toneTextClass,
} from "./shiftStory";
import { RateChart, RatePins, formatRate, rateToneOf, rateToneTextClass } from "./rateStory";
import { EvidenceTable } from "./EvidenceTable";
// This build's baseline renderer returns null by default.
import { paid } from "@paid";

type Detail = BehaviorFindingDetail;

export function FindingPage() {
  const { api, orgSlug, projectSlug } = useTenant();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const { findingId = "" } = useParams();
  /*
   * Absolute, like every other detail page in the app. A relative `..` was pointing at Triage: this
   * route's path is the flat `classifiers/findings/:findingId`, and React Router resolves `..` against
   * the ROUTE rather than the URL, so one step up pops all three segments and lands on the project
   * root. Nothing about that is visible from here, which is exactly why the rest of the app builds
   * these from the slugs instead.
   */
  const basePath = `/orgs/${orgSlug}/projects/${projectSlug}`;

  const detailQ = useQuery({
    queryKey: ["behavior-finding", api.base, findingId],
    queryFn: () => api.getBehaviorFinding(findingId),
    enabled: findingId !== "",
  });

  // Both verbs remove this finding from the queue, so the page it was opened from is where to land.
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

  if (detailQ.isLoading) return <div style={CONTAINER}><LoadingRow /></div>;
  if (detailQ.isError) return <div style={CONTAINER}><ErrorNote error={detailQ.error} /></div>;
  if (!detailQ.data) return null;

  const detail: Detail = detailQ.data;
  const finding = detail.finding;
  const triaged = finding.triageStatus === "done";
  const inFlight = finding.triageStatus === "in_flight";
  const busy = resolveM.isPending || analyzeM.isPending;
  const state = triageState(finding);

  const shift = detail.metric;
  const rate = detail.toolError;
  /* Both tell a before-and-after story with a figure, pins and a ruling, and both put their verbs
     behind triage. The rest of the detectors keep the older layout until they get a story of their own. */
  const story = shift ?? rate;

  return (
    <div style={CONTAINER}>
      {shift ? (
        <ShiftHeader shift={shift} finding={finding} inFlight={inFlight} busy={busy} onAnalyze={() => analyzeM.mutate()} />
      ) : rate ? (
        <RateHeader rate={rate} finding={finding} inFlight={inFlight} busy={busy} onAnalyze={() => analyzeM.mutate()} />
      ) : (
        <>
          <PageHeader
            breadcrumb={[{ label: "Classifiers", to: `${basePath}/classifiers` }, { label: "Finding" }]}
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
              <VerbButton kind="filled" disabled={busy || inFlight} onClick={() => analyzeM.mutate()}>
                {inFlight ? "Triaging…" : "Run triage"}
              </VerbButton>
            )}
            <ResolveVerbs causeKind={finding.causeKind} busy={busy} onResolve={(action) => resolveM.mutate(action)} />
          </div>
        </>
      )}

      {resolveM.isError && <ErrorNote error={resolveM.error} />}
      {analyzeM.isError && <ErrorNote error={analyzeM.error} />}

      {/* The ruling is the decision this finding ended on, so it sits above the evidence rather than
          under it. Its receipts do not: the citations and the check scripts are how a reader CHECKS
          the ruling, and checking comes after reading what was ruled on. */}
      {story && triaged && (
        <div
          className="flex flex-col rounded-card border border-border-strong bg-surface gap-2.5 mt-5 py-4.25 px-4.75">
          {finding.triageSummary && (
            <p className="text-body font-medium text-fg m-0" style={{ maxWidth: 700 }}>
              {finding.triageSummary}
            </p>
          )}
          <div className="flex flex-wrap items-center gap-2 mt-1">
            <ResolveVerbs causeKind={finding.causeKind} busy={busy} onResolve={(action) => resolveM.mutate(action)} />
            {finding.triageAction === "closed" && (
              <span className="text-subtle ml-1 text-small">
                Triage closed this finding. These override the ruling.
              </span>
            )}
          </div>
        </div>
      )}

      {shift && (
        <section
          className={cn("flex flex-col gap-2.75", triaged && "border-t border-border")}
          style={{ marginTop: triaged ? 28 : 24, paddingTop: triaged ? 22 : 0 }}
        >
          <div className="flex items-baseline gap-3">
            <h2 className="font-mono text-label uppercase text-muted">What moved</h2>
            <span className="text-subtle text-small">
              median to 95th percentile, log scale
            </span>
          </div>
          <ShiftChart shift={shift} />
          <ShiftPins shift={shift} />
        </section>
      )}
      {shift && <ShiftBehind shift={shift} />}

      {rate && (
        <section
          className={cn("flex flex-col gap-2.75", triaged && "border-t border-border")}
          style={{ marginTop: triaged ? 28 : 24, paddingTop: triaged ? 22 : 0 }}
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
      {/* Only an SOP-conformance finding carries a baseline. The nullability check stays here;
          the two `!detail.baseline` siblings below decide what renders in its place when there
          isn't one. */}
      {detail.baseline && paid.findingEvidence(detail.baseline)}
      {!story && !detail.baseline && (
        <p className="text-subtle mt-6 text-body" style={{ maxWidth: 560 }}>
          This cause carries no measured shift. It is a claim about the shape of what the agent did
          rather than about a number that moved, so there is nothing here to plot.
        </p>
      )}

      {triaged && !story && <TriageRuling finding={finding} />}

      <section className="mt-7">
        <h2 className="font-mono text-label uppercase text-muted mb-1.5">
          Evidence
        </h2>
        {finding.detector === "sop_conformance" ? (
          !detail.baseline && <EvidenceLinks evidence={finding.evidence} basePath={basePath} />
        ) : (
          <EvidenceTable findingId={findingId} basePath={basePath} />
        )}
      </section>
    </div>
  );

  /**
   * The header of a shift finding: what this is, what the measure did, and the one action available.
   *
   * <p>The right slot holds exactly one thing, and which one is the whole state of the finding. Before
   * triage it is the button that runs it; after, it is what triage ruled. A label reading "Not triaged"
   * beside a button that runs triage said the same thing twice and gave the eye two places to land.
   */
  function ShiftHeader({
    shift: sh,
    finding: f,
    inFlight: running,
    busy: disabled,
    onAnalyze,
  }: {
    shift: NonNullable<Detail["metric"]>;
    finding: Detail["finding"];
    inFlight: boolean;
    busy: boolean;
    onAnalyze: () => void;
  }) {
    const p50 = sh.quantiles.find((q) => q.key === "p50");
    const tone = toneOf(sh);
    const bucket = sh.bucketKey !== f.callSiteId ? sh.bucketKey : null;
    const window =
      sh.windowOpenedAt && sh.windowClosedAt
        ? `${new Date(sh.windowOpenedAt).toLocaleDateString(undefined, { day: "numeric", month: "short" })} – ${new Date(sh.windowClosedAt).toLocaleDateString(undefined, { day: "numeric", month: "short" })}`
        : null;
    return (
      <PageHeader
        breadcrumb={[{ label: "Classifiers", to: `${basePath}/classifiers` }, { label: "Finding" }]}
        kicker={
          <span className="flex flex-wrap items-center gap-2">
            <span className="text-muted">{f.detector ? detectorLabel(f.detector) : "Finding"}</span>
            {f.callSiteId && <span className="text-muted">{f.callSiteId}</span>}
          </span>
        }
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
          /* Everything a reader needs about identity (which bucket, over what window, against
             what) says itself here in words; the raw cause key stays reachable as the line's
             title. */
          <span title={f.causeKey}>
            {[bucket, window && `${window} vs ${referenceWords(sh.reference)}`].filter(Boolean).join(" · ")}
          </span>
        }
        actions={
          f.triageStatus === "done" ? (
            <span
              className="font-mono text-label uppercase text-muted rounded-control border border-border-strong bg-raised py-1.25 px-2.75">
              {triageState(f).label}
            </span>
          ) : (
            <VerbButton kind="filled" disabled={disabled || running} onClick={onAnalyze}>
              {running ? "Triaging…" : "Run triage"}
            </VerbButton>
          )
        }
      />
    );
  }

  /**
   * The header of a rate finding. Same anatomy as {@link ShiftHeader}, different claim.
   *
   * <p>The one action lives here and nowhere else before triage. The resolve verbs appear only
   * inside the ruling, as overrides of a decision that has actually been made, not as a way to
   * skip past the evidence for it.
   */
  function RateHeader({
    rate: r,
    finding: f,
    inFlight: running,
    busy: disabled,
    onAnalyze,
  }: {
    rate: NonNullable<Detail["toolError"]>;
    finding: Detail["finding"];
    inFlight: boolean;
    busy: boolean;
    onAnalyze: () => void;
  }) {
    const tone = rateToneOf(r);
    const since = r.onsetAt
      ? new Date(r.onsetAt).toLocaleDateString(undefined, { day: "numeric", month: "short" })
      : null;
    return (
      <PageHeader
        breadcrumb={[{ label: "Classifiers", to: `${basePath}/classifiers` }, { label: "Finding" }]}
        kicker={
          <span className="flex flex-wrap items-center gap-2">
            <span className="text-muted">{f.detector ? detectorLabel(f.detector) : "Finding"}</span>
            {f.callSiteId && <span className="text-muted">{f.callSiteId}</span>}
          </span>
        }
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
        actions={
          f.triageStatus === "done" ? (
            <span
              className="font-mono text-label uppercase text-muted rounded-control border border-border-strong bg-raised py-1.25 px-2.75">
              {triageState(f).label}
            </span>
          ) : (
            <VerbButton kind="filled" disabled={disabled || running} onClick={onAnalyze}>
              {running ? "Triaging…" : "Run triage"}
            </VerbButton>
          )
        }
      />
    );
  }
}

/**
 * What triage ruled, and everything it ruled on.
 *
 * <p>The verdict line first, because the verdict is what happened to this finding (`positive`
 * handed it to a person, the other two ended it), and a reader who stops after one line should
 * have that fact rather than the prose.
 *
 * <p>Then the citations, which are now two different objects wearing the same shape. An evidence
 * pointer is a claim about something already on this page ("window.n_cur", a trace id the agent
 * fetched) and reads as one line. A check script is code the agent wrote, ran in its sandbox, and
 * is offering as a receipt, so it is shown as code, with what it printed under it and the
 * detector numbers it re-derived beside that. Flattening a script into a line of prose would hide
 * the one part of a ruling a reader can actually re-run.
 */
function TriageRuling({ finding }: { finding: Detail["finding"] }) {
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
            Scripts the agent wrote and ran against the evidence, with what they printed. A number one
            of these re-derived that disagreed with the detector's own would have aborted the run
            instead of becoming a ruling, so every figure below already agrees with the payload.
          </p>
          {scripts.map((c, i) => (
            <CheckScript key={`${c.path}-${i}`} citation={c} />
          ))}
        </>
      )}
    </section>
  );
}

/** The verdict as a sentence, because the bare word says what it concluded and not what it did. */
const VERDICT_WORDS: Record<string, string> = {
  positive: "Positive: the claim holds, and a case is open on it.",
  negative: "Negative: the measurement is wrong, so there is nothing to explain.",
  unclear: "Unclear: the evidence could not settle it, so the finding closed.",
};

/** One check script: the file, what it established, its output, and the numbers it re-derived. */
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
      {citation.recomputed.length > 0 && (
        <div className="flex flex-wrap gap-3 mt-2">
          {citation.recomputed.map((r) => (
            <span key={r.pointer} className="font-mono text-subtle text-label">
              {r.pointer} = <span className="text-fg">{r.value}</span>
            </span>
          ))}
        </div>
      )}
    </div>
  );
}

/**
 * What is behind a rate shift: the failure signatures, then instances of them.
 *
 * <p>The rate itself is not repeated here: stating it as a percent change of a percentage (e.g.
 * `1.28 → 2.49  +94%`) is the single most misleading way to write it. The chart above says it as
 * a share of calls and the pins say it as a count per hundred; a table here would only
 * reintroduce that column.
 */
function ToolErrorEvidence({ rate }: { rate: NonNullable<Detail["toolError"]> }) {
  return (
    <>
      {/* Direction from the rates, which are measured, rather than from the pattern rows, which
          carry no reference side. */}
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

/**
 * What the detector wrote down, as links into the substrate it read.
 *
 * <p>This replaced a single "Exemplar trace →" link, and the difference is what the finding can now
 * say: a rate shift names the witnesses beside its exemplar, a conformance rule lists its violating
 * turns in the order it ranked them, and a distribution shift can point at members of the window it
 * measured. One nullable id could carry the first of those and silently drop the rest.
 *
 * Renders nothing when the set is empty: a finding whose traces have aged out keeps its claim and
 * loses its evidence, and an empty list under a heading reads as a bug rather than as history.
 */
function EvidenceLinks({ evidence, basePath }: { evidence: EvidenceRef[]; basePath: string }) {
  const traces = evidence.filter((e) => e.traceId !== null);
  if (traces.length === 0) return null;
  return (
    <div className="mt-2 gap-1" style={{ display: "flex", flexDirection: "column" }}>
      {traces.map((e) => (
        <Link
          key={`${e.role}-${e.traceId}-${e.spanId ?? ""}`}
          to={`${basePath}/traces/${encodeURIComponent(e.traceId as string)}`}
          className="text-link hover:text-link-hover transition-colors text-small">
          {ROLE_LABEL[e.role] ?? e.role} trace
        </Link>
      ))}
    </div>
  );
}

/** The detector's own vocabulary, spelled for a reader rather than passed through raw. */
const ROLE_LABEL: Record<string, string> = {
  exemplar: "Exemplar",
  member: "Window member",
  baseline: "Baseline",
  witness: "Witness",
  changepoint: "Changepoint",
};
