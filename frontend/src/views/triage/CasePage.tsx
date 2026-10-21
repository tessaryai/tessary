// SPDX-License-Identifier: Apache-2.0
// Case page: headline, why (once RCA has run), how big, then the failures. The causes sit above the
// figure; after RCA the cause is appended to the detector's own sentence, never a re-worded one.
// Lifecycle is open → resolved, plus mute. Nothing is assigned, so there is no claim.
import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type {
  CaseDetail,
  FrustrationDetail,
  GroundednessDetail,
  RcaCause,
  RcaReport,
  RcaRun,
} from "../../api/types";
import { ApiError } from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import { useCapabilities } from "../../capabilities/useCapabilities";
import { Button, ErrorNote, Modal, PageHeader, Table, TableSkeleton, TBody, TD, TH, THead, TR } from "../../ui";
import { rcaRunning, shiftKind, shownAsCause, RCA_VERDICT_LABEL, type CauseKind } from "../rcaLabels";
import { CauseCard } from "../components/CauseCard";
import { RateChart, RatePins } from "../classifiers/rateStory";
import { ShiftChart, ShiftPins } from "../classifiers/shiftStory";
import { LeakPins, LeakTimeline } from "../classifiers/secretStory";
import { HowOutputsBroke, MalformedRate } from "../classifiers/malformedStory";
import {
  FRUSTRATION_LIST_COPY,
  FrustrationRate,
  frustrationLoader,
  frustrationRows,
} from "../classifiers/frustrationStory";
import { ConversationFilter, FlaggedList } from "../classifiers/FlaggedList";
import { GroundednessRate } from "../classifiers/groundednessStory";
import { ClassifierRate, FLAGGED_LIST_ID } from "../classifiers/classifierStory";
import { FlaggedAnswers } from "../classifiers/FlaggedAnswers";
import { Dot, ListChassis, StateDot, detectorLabel, displayCallSite, timeAgo } from "./bits";
import { ConnectRepositoryDialog } from "../components/ConnectRepositoryDialog";
import { useRepoPrompt } from "../components/useRepoPrompt";
import { traceLinker } from "../traceLinker";
import { stamp, useNow } from "../classifiers/shared";
import { RunBar, RunFold, type StepsSource } from "../components/RunBar";
import { clock, elapsed, QUEUED_LINE, RUN_POLL_MS, stepMeta, stopFix, stopReason, THINKING } from "../components/runWords";
import type { ProjectApi } from "../../api/client";
import { useEffect, useRef, type ComponentProps } from "react";

export function CasePage() {
  const { caseId } = useParams<{ caseId: string }>();
  const { orgSlug, projectSlug, api } = useTenant();
  const qc = useQueryClient();
  const basePath = `/orgs/${orgSlug}/projects/${projectSlug}`;

  const detailQ = useQuery({
    queryKey: ["case", api.base, caseId],
    queryFn: () => api.getCase(caseId ?? ""),
    enabled: caseId != null,
  });

  const detail: CaseDetail | undefined = detailQ.data;
  const c = detail?.case;

  // The finished report is inlined on the case read; this query only polls between "run started" and "run
  // finished", when the case carries an id and no report.
  const stillRunning = detail?.rca_report_id != null && detail.rca == null;
  const rcaQ = useQuery({
    queryKey: ["rca", api.base, detail?.rca_report_id],
    queryFn: () => api.getRcaReport(detail?.rca_report_id ?? ""),
    enabled: stillRunning,
    refetchInterval: (q) => (q.state.data && rcaLive(q.state.data) ? RUN_POLL_MS : false),
  });
  const report: RcaReport | undefined = detail?.rca ?? rcaQ.data;
  const run = runOf(report, detail?.case);

  /* The case read inlines the report once it ends, and the cases list marks a running RCA; both go stale here. */
  const polledState = rcaQ.data?.run?.state;
  const seenState = useRef(polledState);
  useEffect(() => {
    if (polledState === seenState.current) return;
    const was = seenState.current;
    seenState.current = polledState;
    if (was === undefined) return;
    void qc.invalidateQueries({ queryKey: ["cases", api.base] });
    void qc.invalidateQueries({ queryKey: ["case", api.base, caseId] });
  }, [qc, api.base, caseId, polledState]);
  const now = useNow(run?.state === "queued" || run?.state === "running");


  const capabilities = useCapabilities();
  const rcaEnabled = capabilities.isEnabled("rca_enabled");
  const canProviders = capabilities.isEnabled("byo_provider_keys_enabled");
  const { canPrompt: canPromptRepo } = useRepoPrompt();
  const [connectRepoOpen, setConnectRepoOpen] = useState(false);

  const [closeOpen, setCloseOpen] = useState(false);
  // Which cause's conversations the list shows: "all", or a cause's index in the report.
  const [causeFilter, setCauseFilter] = useState("all");
  const [absorbOpen, setAbsorbOpen] = useState(false);

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: ["case", api.base, caseId] });
    void qc.invalidateQueries({ queryKey: ["cases", api.base] });
  };

  const closeM = useMutation({
    mutationFn: () => api.closeCase(c?.id ?? ""),
    onSuccess: () => {
      setCloseOpen(false);
      invalidate();
    },
  });
  const absorbM = useMutation({
    mutationFn: () => api.absorbCase(c?.id ?? ""),
    onSuccess: () => {
      setAbsorbOpen(false);
      invalidate();
      // The reference moved, so the finding behind this case is closed too.
      void qc.invalidateQueries({ queryKey: ["behavior-findings", api.base] });
    },
  });
  // The first press sends only the case id; the server resolves the finding behind it. Once a report exists
  // the case trigger would coalesce onto it, so "Re-run RCA" re-runs that report.
  const rcaM = useMutation({
    mutationFn: () =>
      detail?.rca_report_id != null ? api.rerunRca(detail.rca_report_id) : api.runCaseRca(caseId ?? ""),
    onSuccess: invalidate,
  });
  // A report exists from the moment the run is queued, so `analysing` covers the whole in-flight span. Once
  // the polled report is in hand its own status decides; the case read that started the poll carries none.
  const analysing = rcaM.isPending || (report != null ? rcaRunning(report.status) : stillRunning);
  const analysed = report != null && !analysing;

  if (detailQ.isLoading) {
    return (
      <div className="pt-7 px-10 pb-14">
        <TableSkeleton rows={6} cols={3} />
      </div>
    );
  }
  if (detailQ.isError || !detail || !c) {
    return (
      <div className="pt-7 px-10 pb-14">
        <PageHeader
          breadcrumb={[
            { label: "Triage", to: `${basePath}/triage` },
            { label: <span className="font-mono">{caseId}</span> },
          ]}
          title="Case not found"
        />
        {detailQ.isError && <ErrorNote error={detailQ.error} />}
      </div>
    );
  }

  const live = c.state !== "resolved";
  const steps = run && detail.rca_report_id ? rcaSteps(api, detail.rca_report_id, run) : undefined;
  const bar = run ? rcaBar(run, steps, basePath, canProviders, now) : null;
  const frustration = detail.frustration ?? null;
  const groundedness = detail.groundedness ?? null;
  const classifierDetail = detail.classifier_detail ?? null;
  const frustrationCauses =
    frustration && report?.report_kind === "frustration_causes" && !analysing ? report.causes : [];
  const groundednessCauses =
    groundedness && report?.report_kind === "groundedness_causes" && !analysing ? report.causes : [];

  // The detector's own window wins where it has one. A drift window is taken whole or not at all: the case's
  // last seen is wall clock, and pairing it with an event-time open draws a span that never happened.
  const driftWindow =
    detail.metric?.windowOpenedAt && detail.metric.windowClosedAt
      ? { openedAt: detail.metric.windowOpenedAt, closedAt: detail.metric.windowClosedAt }
      : null;
  // A case that gathered several findings spans from the first window's open to the newest window's close.
  const firstOpenedAt =
    detail.findings.length > 1
      ? detail.findings.reduce((a, f) => (Date.parse(f.window_opened_at) < Date.parse(a) ? f.window_opened_at : a),
          detail.findings[0].window_opened_at)
      : null;
  const openedAt =
    firstOpenedAt ??
    detail.tool_error?.onsetAt ??
    detail.malformed_output?.rate?.onsetAt ??
    detail.secret_leak?.firstAt ??
    detail.frustration?.rate.onsetAt ??
    detail.groundedness?.rate.onsetAt ??
    detail.classifier_detail?.rate.onsetAt ??
    driftWindow?.openedAt ??
    c.onset_at;
  const closedAt =
    detail.tool_error?.windowClosedAt ??
    detail.malformed_output?.rate?.windowClosedAt ??
    detail.secret_leak?.lastAt ??
    driftWindow?.closedAt ??
    (c.state === "resolved" ? c.resolved_at : c.last_seen_at);

  return (
    <div className="pt-7 px-10 pb-14">
      <nav aria-label="Breadcrumb" className="flex items-center gap-1.75 mb-5 text-small">
        <Link to={`${basePath}/triage`} className="text-muted hover:text-fg transition-colors">
          Triage
        </Link>
        <span aria-hidden="true" className="text-subtle">
          ›
        </span>
        <span className="font-mono text-fg">{c.reference}</span>
        <StateDot state={c.state} analysed={c.rca_verdict != null} />
      </nav>

      {/* ------------------------------------------------- the story, in one line */}
      <header className="mb-7.5">
        <div className="flex items-start gap-6">
          {/* Tool and grader ids are long unbroken tokens; without an explicit break they overflow the flex track. */}
          <h1
            className="min-w-0 flex-1 text-h1 text-fg"
            style={{
              overflowWrap: "anywhere",
              textWrap: "balance",
              maxWidth: "30ch" }}
          >
            {c.title}
          </h1>

          {/* One control beside the headline. Resolve and Absorb are the disposition, reached at the end of the
             story, so they sit in the closing bar with Mute. */}
          {live && detail.detector_available && rcaEnabled && detail.rca_available && (
            <div className="ml-auto flex shrink-0 items-center gap-2">
              {/* Deliberate and temporary: an RCA with no repo rules on trace evidence alone. Owner-gated, and it
                 disappears once a repository is connected. */}
              {canPromptRepo && (
                <Button size="sm" variant="secondary" onClick={() => setConnectRepoOpen(true)}>
                  Connect repository
                </Button>
              )}
              <Button
                size="sm"
                variant={analysed ? "secondary" : "primary"}
                onClick={() => rcaM.mutate()}
                disabled={analysing}
              >
                {analysing ? "Analyzing…" : detail.rca_report_id != null ? "Re-run RCA" : "Run RCA"}
              </Button>
            </div>
          )}
        </div>

        <div className="flex items-center text-muted gap-2 mt-2.75 text-small" style={{ flexWrap: "wrap" }}>
          <span className="font-mono">
            {stamp(openedAt)}
            {closedAt && ` → ${stamp(closedAt)}`}
          </span>
          <Dot />
          <span className="font-mono text-subtle">{detectorLabel(c.detector)}</span>
          {/* `__unattributed__` is not a call site; a tool's failure rate belongs to the tool across entry points. */}
          {displayCallSite(c.call_site_id) && (
            <>
              <Dot />
              <span className="font-mono">{displayCallSite(c.call_site_id)}</span>
            </>
          )}
          {report?.status === "done" && (
            <>
              <Dot />
              <span>analyzed {timeAgo(report.completed_at ?? report.created_at)}</span>
            </>
          )}
        </div>

        {c.state === "resolved" && (
          <p className="text-subtle mt-2.5 mx-0 mb-0 text-small">
            {c.resolution === "absorbed" ? "Absorbed" : "Closed"}
            {dispositionPhrase(c.disposition)} {timeAgo(c.resolved_at ?? c.opened_at)}
            {c.resolved_by ? ` by ${c.resolved_by}` : ""}
            {c.resolution !== "absorbed" && c.resolution_reason ? `: ${c.resolution_reason}` : ""}
          </p>
        )}

        {/* A case whose classifier is gone still renders, but every action is withheld (they would 409) and the page says why. */}
        {live && !detail.detector_available && (
          <p className="text-warning mt-4 mx-0 mb-0 text-small" style={{ maxWidth: 720 }}>
            The classifier behind this case ({detectorLabel(c.detector)}) is no longer available to this
            organization, so this case is read-only. It stays here because it happened, not because
            anything is still watching for it.
          </p>
        )}

        {rcaM.isError && <RcaErrorNote error={rcaM.error} />}
      </header>
      {bar && <RunBar {...bar} />}

      {/* ----------------------------------------------------------------- why */}
      {(rcaEnabled || detail.rca_report_id != null) && (
        <Why
          report={report}
          analysing={analysing}
          steps={steps}
          actions={
            live && detail.detector_available ? (
              <>
                {detail.absorb_available && (
                  <Button size="sm" variant="secondary" onClick={() => setAbsorbOpen(true)}>
                    Absorb
                  </Button>
                )}
                <Button size="sm" variant="secondary" onClick={() => setCloseOpen(true)}>
                  Close
                </Button>
              </>
            ) : undefined
          }
          kind={causeKind(detail)}
          affected={affectedUnit(detail)}
          basePath={basePath}
          show={
            frustration && report?.report_kind === "frustration_causes"
              ? {
                  count: causeSessionCount,
                  unit: ["session", "sessions"],
                  onShow: (i) => {
                    setCauseFilter(String(i));
                    document.getElementById("frustrated-sessions")?.scrollIntoView({ behavior: "smooth", block: "start" });
                  },
                }
              : groundedness && report?.report_kind === "groundedness_causes"
                ? {
                    count: causeTraceCount,
                    unit: ["answer", "answers"],
                    onShow: (i) => {
                      setCauseFilter(String(i));
                      document.getElementById("flagged-answers")?.scrollIntoView({ behavior: "smooth", block: "start" });
                    },
                  }
                : classifierDetail && report?.report_kind === "owned_causes"
                  ? {
                      count: causeTraceCount,
                      unit: [classifierDetail.words.unitOne, classifierDetail.words.unitMany],
                      onShow: (i) => {
                        setCauseFilter(String(i));
                        document.getElementById(FLAGGED_LIST_ID)?.scrollIntoView({ behavior: "smooth", block: "start" });
                      },
                    }
                  : undefined
          }
        />
      )}

      {/* -------------------------------------------------------------- how big */}
      <Magnitude detail={detail} basis={c.basis} basePath={basePath} />

      {/* ---------------------------------------------------- how outputs broke */}
      {detail.malformed_output && detail.latest_finding_id && (
        <Block label="How outputs broke" note="each schema field with its failures, beside one failing output">
          <HowOutputsBroke
            findingId={detail.latest_finding_id}
            detail={detail.malformed_output}
            linkToTrace={traceLinker(basePath)}
          />
        </Block>
      )}

      {/* ------------------------------------------------------ the rest of the run */}
      {(rcaEnabled || detail.rca_report_id != null) && (
        <Checks report={report} analysing={analysing} />
      )}

      {/* ------------------------------------------------------- the failures */}
      {frustration && detail.latest_finding_id ? (
        <FrustrationList
          findingId={detail.latest_finding_id}
          rcaReportId={report?.id ?? null}
          frustration={frustration}
          causes={frustrationCauses}
          filter={causeFilter}
          onFilter={setCauseFilter}
          basePath={basePath}
        />
      ) : groundedness && detail.latest_finding_id ? (
        <GroundednessList
          findingId={detail.latest_finding_id}
          rcaReportId={report?.id ?? null}
          groundedness={groundedness}
          causes={groundednessCauses}
          filter={causeFilter}
          onFilter={setCauseFilter}
          basePath={basePath}
        />
      ) : null}

      {/* ----------------------------------------------------- every window */}
      {detail.findings.length > 0 && <CaseFindings findings={detail.findings} basePath={basePath} />}

      <Activity events={detail.events} />

      {/* Close and Absorb sit on the Why line, so they only appear once there is an analysis to act on. */}
      <Modal open={closeOpen} onClose={() => setCloseOpen(false)} title="Close this case?">
        <p className="text-muted m-0 text-body">
          The evidence in this case's findings is left out when the baseline is set.
        </p>
        {closeM.isError && <ErrorNote error={closeM.error} />}
        <div className="flex justify-end gap-2 mt-4.5">
          <Button variant="ghost" size="sm" onClick={() => setCloseOpen(false)}>
            Cancel
          </Button>
          <Button
            size="sm"
            variant="primary"
            aria-label="Close case"
            onClick={() => closeM.mutate()}
            disabled={closeM.isPending}
          >
            {closeM.isPending ? "Closing…" : "Close"}
          </Button>
        </div>
      </Modal>

      <Modal open={absorbOpen} onClose={() => setAbsorbOpen(false)} title="Absorb this level?">
        <p className="text-muted m-0 text-body">The evidence in this case's findings counts toward the baseline.</p>
        {absorbM.isError && <ErrorNote error={absorbM.error} />}
        <div className="flex justify-end gap-2 mt-4.5">
          <Button variant="ghost" size="sm" onClick={() => setAbsorbOpen(false)}>
            Cancel
          </Button>
          <Button size="sm" variant="primary" onClick={() => absorbM.mutate()} disabled={absorbM.isPending}>
            {absorbM.isPending ? "Absorbing…" : "Absorb"}
          </Button>
        </div>
      </Modal>

      <ConnectRepositoryDialog open={connectRepoOpen} onClose={() => setConnectRepoOpen(false)} />
    </div>
  );
}

/* ------------------------------------------------------------------ pieces */

/** How a closed case's disposition reads in its closing line, or nothing for a case without one. */
function dispositionPhrase(disposition: string | null | undefined): string {
  if (disposition === "fixed") return " as fixed";
  if (disposition === "false_alarm") return " as a false alarm";
  return "";
}

function Block({
  label,
  note,
  actions,
  children,
}: {
  label: string;
  note?: string;
  actions?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <section className="mt-8.5">
      <div className="flex items-baseline gap-2.5 mb-3.5">
        <h2 className="font-mono uppercase text-subtle text-label">
          {label}
        </h2>
        {note && (
          <span className="text-subtle text-small">
            {note}
          </span>
        )}
        {actions && <div className="ml-auto flex items-center gap-2">{actions}</div>}
      </div>
      {children}
    </section>
  );
}

/** Every window this case gathered, oldest first, each opening its own finding page. */
function CaseFindings({ findings, basePath }: { findings: CaseDetail["findings"]; basePath: string }) {
  const findingPath = (id: string) => `${basePath}/classifiers/findings/${encodeURIComponent(id)}`;
  return (
    <Block label="Findings">
      <Table>
        <THead>
          <TR>
            <TH style={{ width: 280 }}>Window</TH>
            <TH>Finding</TH>
            <TH style={{ width: 120 }} />
          </TR>
        </THead>
        <TBody>
          {findings.map((f) => (
            <TR key={f.id}>
              <TD className="font-mono text-muted text-small">
                {stamp(f.window_opened_at)} → {stamp(f.window_closed_at)}
              </TD>
              <TD className="truncate" style={{ maxWidth: 0 }} title={f.title}>
                {f.title}
              </TD>
              <TD className="text-right text-small">
                <Link to={findingPath(f.id)} className="text-link hover:text-link-hover transition-colors">
                  Open finding
                </Link>
              </TD>
            </TR>
          ))}
        </TBody>
      </Table>
    </Block>
  );
}

/**
 * How big it was, drawn with the finding page's own figures from the same parsed blob, so the two cannot
 * disagree. The detector's `basis` is the caption. A shift with no drawable shape says so instead.
 */
function Magnitude({ detail, basis, basePath }: { detail: CaseDetail; basis: string; basePath: string }) {
  // A frustration case draws the finding page's own figure, whose first note already states the basis.
  if (detail.frustration) {
    return (
      <Block label="What changed" note="Share of sessions with a user frustrated with the agent">
        <FrustrationRate detail={detail.frustration} />
      </Block>
    );
  }
  if (detail.groundedness) {
    return (
      <Block label="What changed" note="Share of traces with a flagged answer">
        <GroundednessRate detail={detail.groundedness} opened="this case" />
      </Block>
    );
  }
  if (detail.classifier_detail) {
    return (
      <Block label="What changed" note={detail.classifier_detail.words.figureNote}>
        <ClassifierRate detail={detail.classifier_detail} />
      </Block>
    );
  }
  const rate = detail.tool_error;
  const shift = detail.metric;
  const secretLeak = detail.secret_leak;
  const malformedOutput = detail.malformed_output;
  // A case holding several windows draws the one that moved furthest, and says which.
  const worst =
    detail.findings.length > 1 ? detail.findings.find((f) => f.id === detail.worst_finding_id) : undefined;
  const worstNote = worst ? ` · worst of ${detail.findings.length} windows, ${stamp(worst.window_opened_at)}` : "";

  return (
    <Block
      label="How big"
      note={
        rate
          ? `share of calls that failed${worstNote}`
          : shift
            ? `median to 95th percentile, log scale${worstNote}`
            : secretLeak
              ? "one dot per leaking output, one lane per key"
              : malformedOutput
                ? "share of outputs that failed their schema"
                : undefined
      }
    >
      {rate ? (
        <>
          <RateChart rate={rate} />
          <RatePins rate={rate} />
        </>
      ) : shift ? (
        <>
          <ShiftChart shift={shift} />
          <ShiftPins shift={shift} />
        </>
      ) : secretLeak ? (
        <>
          <LeakTimeline secretLeak={secretLeak} />
          <LeakPins
            secretLeak={secretLeak}
            linkToTrace={traceLinker(basePath)}
          />
        </>
      ) : malformedOutput ? (
        <MalformedRate rate={malformedOutput.rate} />
      ) : (
        <p className="text-subtle m-0 text-body" style={{ maxWidth: 560 }}>
          This classifier's movement carries no measured shift. It is a claim about the shape of what
          the agent did rather than about a number that moved, so there is nothing here to plot.
        </p>
      )}
      <p className="text-muted mt-3.5 mx-0 mb-0 text-small" style={{ maxWidth: 720 }}>
        {basis}
      </p>
    </Block>
  );
}

/** Which movement this case's causes explain, from the detector blob the case carries. */
function causeKind(detail: CaseDetail): CauseKind {
  if (detail.frustration) return "frustration";
  if (detail.groundedness) return "groundedness";
  if (detail.classifier_detail) return "classifier";
  if (detail.tool_error) return "tool_error";
  if (detail.malformed_output) return "malformed";
  if (detail.secret_leak) return "secret_leak";
  if (detail.metric) return shiftKind(detail.metric.measure, detail.metric.direction !== "down") ?? "other";
  return "other";
}

/** What a cause's affected count counts, by case type. */
function affectedUnit(detail: CaseDetail): [string, string] {
  if (detail.frustration) return ["frustrated session", "frustrated sessions"];
  if (detail.groundedness) return ["flagged answer", "flagged answers"];
  if (detail.classifier_detail) {
    const words = detail.classifier_detail.words;
    return [`flagged ${words.unitOne}`, `flagged ${words.unitMany}`];
  }
  return ["flagged trace", "flagged traces"];
}

/**
 * The causes, carded under the headline. With no proven cause in an older report, the summary comes first
 * and the leads are labelled as leads.
 */
function Why({
  report,
  analysing,
  actions,
  kind,
  affected,
  basePath,
  show,
  steps,
}: {
  report: RcaReport | undefined;
  analysing: boolean;
  /** The finished run's steps, behind the fold under the analysis. */
  steps?: StepsSource;
  /** Close and Absorb. Shown only beside a finished analysis, never while one runs. */
  actions?: React.ReactNode;
  kind: CauseKind;
  /** What a cause's affected count counts, singular and plural. */
  affected: [string, string];
  basePath: string;
  /** How a cause filters the flagged list below; the index is the cause's stored one. */
  show?: { count: (cause: RcaCause) => number; unit: [string, string]; onShow: (index: number) => void };
}) {
  // Nothing to say before a run or while one runs, and the bar under the header says a failed run stopped.
  if (analysing || !report || report.status === "failed") return null;

  const indexed = report.causes.map((cause, index) => ({ cause, index }));
  const causes = indexed.filter((k) => shownAsCause(k.cause));
  const shown = causes.length > 0 ? causes : indexed;
  const summary = report.summary ?? (report.verdict ? RCA_VERDICT_LABEL[report.verdict] : null);

  return (
    <Block label="Why" note={causes.length > 0 ? undefined : "No cause proven"} actions={actions}>
      <div className="flex flex-col gap-3">
        {causes.length === 0 && summary && (
          <p className="m-0 text-body text-fg" style={{ maxWidth: 700 }}>
            {summary}
          </p>
        )}
        {shown.map(({ cause, index }) => (
          <CauseCard
            key={index}
            cause={cause}
            kind={kind}
            basePath={basePath}
            affected={affected}
            repoRead={report.repo_available !== false}
            show={
              show && {
                count: show.count(cause),
                unit: show.unit,
                onShow: () => show.onShow(index),
              }
            }
          />
        ))}
        {report.causes.length > 0 && report.repo_available === false && (
          <p className="m-0 text-small text-muted">
            {report.causes.length === 1 ? "This cause isn't" : "These causes aren't"} linked to a prompt or code
            because no repository was connected. Connect a repository and run RCA again to find them.
          </p>
        )}
      </div>
      <div className="mt-2.5 text-small">
        <Link to={`${basePath}/rca/${encodeURIComponent(report.id)}`} className="text-link hover:text-link-hover">
          Read the full analysis
        </Link>
        {steps && <AnalysisFold run={report.run} steps={steps} />}
      </div>
    </Block>
  );
}

/** What else was checked: the candidates the analysis ruled out, below the figure since none is the conclusion. */
function Checks({ report, analysing }: { report: RcaReport | undefined; analysing: boolean }) {
  // The bar under the header owns the in-flight and failed states, and there is nothing to say before a run.
  if (analysing || !report || report.status === "failed") return null;

  // Every check, not just the eliminated ones: `contributing` and `explains` are part of the story.
  const checks = report.ruled_out;
  if (checks.length === 0) return null;
  const eliminated = checks.filter((c) => c.assessment === "ruled_out").length;
  const reasoned = checks.some((c) => c.detail);

  return (
    <Block label="What else was checked">
      <p className="text-muted mt-0 mx-0 mb-4 text-body">
        {checks.length} {checks.length === 1 ? "explanation" : "explanations"} tested, {eliminated} eliminated.
      </p>
      <ListChassis>
        {checks.map((c) => (
          <div
            key={c.check}
            className="grid items-baseline bg-surface gap-4 py-2.75 px-3.5"
            style={{ gridTemplateColumns: reasoned ? "minmax(0, 260px) 92px 1fr" : "minmax(0, 1fr) 92px" }}
          >
            <span className="flex min-w-0 flex-col gap-0.5">
              <span className="text-fg text-small">{c.question ?? c.check}</span>
              {c.question && c.detail && (
                <span className="font-mono text-subtle text-label" style={{ overflowWrap: "anywhere" }}>
                  {c.check}
                </span>
              )}
            </span>
            <span
              className="font-mono uppercase text-label"
              style={{ color: assessmentColour(c.assessment) }}
            >
              {(c.assessment ?? "unknown").replace(/_/g, " ")}
            </span>
            {reasoned && <span className="text-muted text-small">{c.detail}</span>}
          </div>
        ))}
      </ListChassis>
    </Block>
  );
}

/** The assessment word's one colour. `ruled_out` is the quiet majority and must not compete. */
function assessmentColour(assessment: string | null): string {
  switch (assessment) {
    case "explains":
      return "var(--color-error)";
    case "contributing":
      return "var(--color-warning)";
    case "unknown":
      return "var(--color-muted)";
    default:
      return "var(--color-subtle)";
  }
}

/** What has happened to this case, collapsed. A case that recovered and re-fired is a fact nothing else shows. */
function Activity({ events }: { events: CaseDetail["events"] }) {
  const [open, setOpen] = useState(false);
  if (events.length === 0) return null;

  return (
    <section className="mt-5.5">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="font-mono uppercase text-label text-subtle hover:text-muted transition-colors p-0"
        style={{ background: "none", border: 0, cursor: "pointer" }}
      >
        Activity ({events.length}) {open ? "−" : "+"}
      </button>
      {open && (
        <ol className="mt-3 mx-0 mb-0 p-0" style={{ listStyle: "none" }}>
          {events.map((e) => (
            <li key={e.id} className="flex items-baseline gap-3 py-1.5 px-0 text-small">
              <span className="font-mono text-subtle shrink-0 text-label" style={{ width: 84 }}>
                {stamp(e.created_at)}
              </span>
              <span className="min-w-0 flex-1 text-muted">{e.summary}</span>
              <span className="text-subtle shrink-0 text-label">
                {e.actor ?? "Tessary"}
              </span>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

/** A report waits or runs while its run says so; a report from before runs were recorded says it by its status. */
function rcaLive(report: RcaReport): boolean {
  return report.run ? report.run.state === "queued" || report.run.state === "running" : rcaRunning(report.status);
}

/**
 * Where the case's analysis stands: the report's own run, else what the case read says while the report is on
 * its way, else a stop for a report that failed before runs were recorded.
 */
function runOf(report: RcaReport | undefined, c: CaseDetail["case"] | undefined): RcaRun | null {
  if (report?.run) return report.run;
  if (report?.status === "failed") {
    return {
      state: "stopped", queued_at: report.created_at, run_id: null, started_at: null,
      ended_at: report.completed_at, step_count: 0, doing: null, last_step: null, last_step_ms: null, note: null,
      note_ms: null, stop_code: null,
    };
  }
  const state = c?.rca_run_state;
  if (!report && c && (state === "queued" || state === "running")) {
    const since = c.rca_run_since ?? c.opened_at;
    return {
      state, queued_at: since, run_id: null, started_at: state === "running" ? since : null, ended_at: null,
      step_count: 0, doing: null, last_step: null, last_step_ms: null, note: null, note_ms: null, stop_code: null,
    };
  }
  return null;
}

/** The steps of a report's newest run, once the run has any; read only when someone opens them. */
function rcaSteps(api: ProjectApi, reportId: string, run: RcaRun): StepsSource | undefined {
  if (!run.run_id) return undefined;
  return { load: (after) => api.getRcaSteps(reportId, after), live: run.state === "running" };
}

/** The bar after the header while an RCA waits, runs or stopped; a finished one has its fold instead. */
function rcaBar(
  run: RcaRun,
  steps: StepsSource | undefined,
  basePath: string,
  canProviders: boolean,
  now: number,
): ComponentProps<typeof RunBar> | null {
  const since = (iso: string | null) => (iso ? now - Date.parse(iso) : null);
  if (run.state === "queued") {
    const waited = since(run.queued_at);
    return { kind: "RCA", state: "queued", line: QUEUED_LINE, meta: waited == null ? undefined : clock(waited) };
  }
  if (run.state === "running") {
    const ran = since(run.started_at);
    return {
      kind: "RCA",
      state: "running",
      line: run.doing ? `${run.doing}…` : THINKING,
      meta: ran == null ? undefined : run.step_count > 0 ? stepMeta(run.step_count, ran) : elapsed(ran),
      steps,
    };
  }
  if (run.state !== "stopped") return null;
  const reason = stopReason(run.stop_code ?? "RCA.UPSTREAM_FAILED", "RCA");
  return {
    kind: "RCA",
    state: "stopped",
    line: run.step_count > 0 ? `Stopped at step ${run.step_count}: ${reason}` : `Stopped: ${reason}`,
    fix: stopFix(run.stop_code, basePath, canProviders),
    steps,
  };
}

/** `› Did 23 steps in 9m` beside the link to the full analysis, for the run that wrote it. */
function AnalysisFold({ run, steps }: { run: RcaRun | null; steps: StepsSource }) {
  if (run?.state !== "done" || !run.started_at || !run.ended_at || run.step_count === 0) return null;
  return (
    <RunFold
      stepCount={run.step_count}
      durationMs={Date.parse(run.ended_at) - Date.parse(run.started_at)}
      steps={steps}
      className="ml-4"
    />
  );
}

function RcaErrorNote({ error }: { error: unknown }) {
  // Reachable despite the `rca_available` gate: the finding can be resolved between page load and click.
  if (error instanceof ApiError && error.code === "RCA.SUBJECT_NOT_FOUND") {
    return (
      <p className="text-warning mt-3 mx-0 mb-0 text-small">
        The finding behind this case is no longer there, so there is nothing for the analysis to read.
        Resolve the case by hand if it is done.
      </p>
    );
  }
  return <ErrorNote error={error} />;
}

/** How many sessions a cause names. */
function causeSessionCount(cause: RcaCause) {
  return new Set(cause.evidence_session_ids).size;
}

/** The case's frustrated sessions, filtered to one cause when the reader asked for it. */
function FrustrationList({
  findingId,
  rcaReportId,
  frustration,
  causes,
  filter,
  onFilter,
  basePath,
}: {
  findingId: string;
  /** The report the causes come from, which the server reads a cause's sessions off. */
  rcaReportId: string | null;
  frustration: FrustrationDetail;
  causes: RcaCause[];
  filter: string;
  onFilter: (key: string) => void;
  basePath: string;
}) {
  const { api } = useTenant();
  const index = filter === "all" ? -1 : Number(filter);
  const cause = index >= 0 ? causes[index] : undefined;
  const options = [
    { key: "all", label: `All · ${frustration.rate.failuresCur.toLocaleString()}` },
    ...causes
      .map((k, i) => ({ key: String(i), label: `Cause ${i + 1} · ${causeSessionCount(k)}` }))
      .filter((o) => !o.label.endsWith(" · 0")),
  ];
  return (
    <div id="frustrated-sessions">
      <Block label="Frustrated sessions" note="Each flagged message with the turns before it">
        {options.length > 1 && (
          <ConversationFilter options={options} value={cause ? filter : "all"} onChange={onFilter} />
        )}
        <FlaggedList
          {...FRUSTRATION_LIST_COPY}
          listKey={findingId}
          first={frustrationRows(frustration)}
          loadPage={frustrationLoader(api, findingId)}
          filter={cause && rcaReportId ? { rcaReport: rcaReportId, index } : undefined}
          readAhead={
            rcaReportId
              ? options.filter((o) => o.key !== "all").map((o) => ({ rcaReport: rcaReportId, index: Number(o.key) }))
              : []
          }
          basePath={basePath}
        />
      </Block>
    </div>
  );
}

/** Counted as answers, as the rate is: a trace with two flagged answers is still one. */
function causeTraceCount(cause: RcaCause) {
  return new Set(cause.evidence_trace_ids).size;
}

/** The case's flagged answers, filtered to one cause when the reader asked for it. */
function GroundednessList({
  findingId,
  rcaReportId,
  groundedness,
  causes,
  filter,
  onFilter,
  basePath,
}: {
  findingId: string;
  /** The report the causes come from, which the server reads a cause's answers off. */
  rcaReportId: string | null;
  groundedness: GroundednessDetail;
  causes: RcaCause[];
  filter: string;
  onFilter: (key: string) => void;
  basePath: string;
}) {
  const index = filter === "all" ? -1 : Number(filter);
  const cause = index >= 0 ? causes[index] : undefined;
  const all = groundedness.rate.failuresCur;
  const options = [
    { key: "all", label: `All · ${all.toLocaleString()}` },
    ...causes
      .map((k, i) => ({ key: String(i), label: `Cause ${i + 1} · ${causeTraceCount(k)}` }))
      .filter((o) => !o.label.endsWith(" · 0")),
  ];
  return (
    <div id="flagged-answers">
      <Block label="Flagged answers" note={`Marked sentences scored ${groundedness.flagThreshold} or higher`}>
        {options.length > 1 && (
          <ConversationFilter
            options={options}
            value={cause ? filter : "all"}
            onChange={onFilter}
            label="Filter answers"
          />
        )}
        <FlaggedAnswers
          findingId={findingId}
          first={{ rows: groundedness.answers, nextCursor: groundedness.answersNextCursor }}
          traces={cause ? causeTraceCount(cause) : all}
          filter={cause && rcaReportId ? { rcaReport: rcaReportId, index } : undefined}
          readAhead={
            rcaReportId
              ? options.filter((o) => o.key !== "all").map((o) => ({ rcaReport: rcaReportId, index: Number(o.key) }))
              : []
          }
          basePath={basePath}
        />
      </Block>
    </div>
  );
}
