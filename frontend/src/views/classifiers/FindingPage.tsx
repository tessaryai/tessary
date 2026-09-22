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
import { CONTAINER, ResolveVerbs, RunTriageButton, chainWords, detectorLabel, triageState } from "./shared";
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
import { LeakPins, LeakTimeline, SecretHeader } from "./secretStory";
import { HowOutputsBroke, MalformedHeader, MalformedRate } from "./malformedStory";
import { FrustrationHeader, FrustrationRate } from "./frustrationStory";
import { FrustratedConversations } from "./FrustratedConversations";
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
  /** Absolute, not `../traces/...`: these hand-build the href for a plain `<a>` rather than a
   *  react-router `<Link>` (the timeline and the failing-output viewer render dozens of these off
   *  data, not JSX), and a relative href on a plain anchor resolves against the URL rather than the
   *  route tree — exactly the mismatch this page's own top note warns `navigate()` about. */
  const traceLink = (traceId: string, spanId?: string | null) =>
    `${basePath}/traces/${encodeURIComponent(traceId)}${spanId ? `#${encodeURIComponent(spanId)}` : ""}`;

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
  const busy = resolveM.isPending || analyzeM.isPending;
  const state = triageState(finding);

  const shift = detail.metric;
  const rate = detail.toolError;
  const secretLeak = detail.secretLeak;
  const malformedOutput = detail.malformedOutput;
  const frustration = detail.frustration;
  /* All five tell a before-and-after story with a figure, pins and a ruling. Four put their verbs
     behind triage; frustration is ruled when it is filed. The rest of the detectors keep the older
     layout until they get a story of their own. */
  const story = shift ?? rate ?? secretLeak ?? malformedOutput ?? frustration;

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
      ) : malformedOutput ? (
        <MalformedHeader
          rate={malformedOutput.rate}
          finding={finding}
          basePath={basePath}
          busy={busy}
          onAnalyze={() => analyzeM.mutate()}
        />
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
              <RunTriageButton finding={finding} busy={busy} onAnalyze={() => analyzeM.mutate()} />
            )}
            {/* A ruling freezes the finding by construction (decision 1): once triaged is true a
                verdict is stood, and every verb on it — triage's own or a person's — 409s. So the
                verbs stop being offered the moment there is one, rather than staying up as an
                "override" that would just fail. */}
            {!triaged && (
              <ResolveVerbs causeKind={finding.causeKind} busy={busy} onResolve={(action) => resolveM.mutate(action)} />
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
      {/* A dead-lettered run is revived only once its cooldown has passed; before that the press lands
          on the same dead job, and saying nothing would read as the button not working. */}
      {analyzeM.data?.jobStatus === "dead" && (
        <p className="text-muted text-small mt-2 mb-0">
          Triage gave up on this finding recently. It can run again once its cooldown has passed.
        </p>
      )}

      {/* The ruling is the decision this finding ended on, so it sits above the evidence rather than
          under it. Its receipts do not: the citations and the check scripts are how a reader CHECKS
          the ruling, and checking comes after reading what was ruled on. No verbs here: a ruled
          finding is frozen (decision 1), so there is nothing left to override. A frustration finding
          states its ruling and links its case in its own header, so it skips this card. */}
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

      {secretLeak && (
        <section
          className={cn("flex flex-col gap-2.75", triaged && "border-t border-border")}
          style={{ marginTop: triaged ? 28 : 24, paddingTop: triaged ? 22 : 0 }}
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
            className={cn("flex flex-col gap-2.75", triaged && "border-t border-border")}
            style={{ marginTop: triaged ? 28 : 24, paddingTop: triaged ? 22 : 0 }}
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
            className={cn("flex flex-col gap-2.75", triaged && "border-t border-border")}
            style={{ marginTop: triaged ? 28 : 24, paddingTop: triaged ? 22 : 0 }}
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
            <FrustratedConversations
              findingId={findingId}
              first={{
                rows: frustration.conversations,
                nextCursor: frustration.conversationsNextCursor,
                total: frustration.rate.failuresCur,
              }}
              basePath={basePath}
            />
          </section>
        </>
      )}

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

      {triaged && !story && <TriageRuling finding={finding} basePath={basePath} />}

      {/* A frustration finding's evidence is its sessions, drawn above; the raw witness rows
          would list the same sessions again as bare ids. */}
      {!frustration && (
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
      )}
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
            <RunTriageButton finding={f} busy={disabled} onAnalyze={onAnalyze} />
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
            <RunTriageButton finding={f} busy={disabled} onAnalyze={onAnalyze} />
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
 * handed it to a person, `negative` closed it), and a reader who stops after one line should
 * have that fact rather than the prose.
 *
 * <p>Then the citations, which are two different objects wearing the same shape. An evidence
 * pointer is a claim about something already on this page ("window.n_cur", a trace id the agent
 * fetched) and reads as one line. A check script is code the agent wrote, ran in its sandbox, and
 * is offering as a receipt, so it is shown as code, with what it printed under it. Flattening a
 * script into a line of prose would hide the one part of a ruling a reader can actually re-run.
 */
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

/** The verdict as a sentence, because the bare word says what it concluded and not what it did. */
const VERDICT_WORDS: Record<string, string> = {
  positive: "Positive: the claim holds, and a case is open on it.",
  negative: "Negative: the measurement is wrong, so there is nothing to explain.",
};

/** One check script: the file, what it established, and its output. */
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
