// SPDX-License-Identifier: Apache-2.0
/*
 * Case page — the story of one thing going wrong.
 *
 * Four beats, in order: what happened and over what window, how big it was,
 * why (once RCA has run), and the failures themselves. Everything else is gone.
 *
 * <h2>The headline IS the story, and it rewrites itself</h2>
 * Before RCA the title is the detector's own sentence plus the window it spans.
 * After, the cause is appended to that same sentence — never a re-worded one,
 * because a case that re-words its finding leaves a reader matching two names
 * for one event. The ruling that opened the case does not vanish when the
 * attribution arrives; it demotes to a line, because "is this claim true" and
 * "what changed" are different questions and must not look alike.
 *
 * <h2>One figure, and it is the finding's figure</h2>
 * The magnitude is drawn by the same components the finding page uses, from the
 * same bytes: `RateChart` for a rate shift, `ShiftChart` for a distribution
 * shift. A detector whose movement has no drawable shape gets no chart and says
 * so — the rule this file already held, now with something real to hold it
 * against.
 *
 * <h2>Colour is the problem, and the one action</h2>
 * The direction of the shift carries error/success, and the primary button
 * carries the accent. Nothing else is tinted: a page that colour-codes five
 * kinds of row tells a reader everything is important, which is the same as
 * telling them nothing is.
 *
 * Lifecycle here is open → resolved, plus mute. There is no claim: nothing in
 * this product is assigned.
 */
import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { CaseDetail, EvidenceSpan, RcaReport } from "../../api/types";
import { ApiError } from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import { useCapabilities } from "../../capabilities/useCapabilities";
import { Button, ErrorNote, Input, Modal, PageHeader, StatusPill, TableSkeleton, cn } from "../../ui";
import { rcaRunning, RCA_VERDICT_LABEL } from "../rcaLabels";
import { RateChart, RatePins } from "../classifiers/rateStory";
import { ShiftChart, ShiftPins } from "../classifiers/shiftStory";
import { Dot, ListChassis, StateDot, causeLine, detectorLabel, displayCallSite, timeAgo, truncateId } from "./bits";
import { formatDuration } from "../traces/detail-data";

/** `2026-08-24T18:00:00Z` → `24 Aug 18:00`. The window is the story's spine, so it reads as a clock. */
function stamp(iso: string): string {
  const d = new Date(iso);
  return `${d.toLocaleDateString(undefined, { day: "numeric", month: "short" })} ${d.toLocaleTimeString(
    undefined,
    { hour: "2-digit", minute: "2-digit", hour12: false },
  )}`;
}

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

  // The finished report is inlined on the case read, which is why it is inlined at all. The separate
  // query exists only for the gap between "a run was started" and "a run finished": the case carries
  // an id and no report in that window, and this is what polls it to completion.
  const stillRunning = detail?.rca_report_id != null && detail.rca == null;
  const rcaQ = useQuery({
    queryKey: ["rca", api.base, detail?.rca_report_id],
    queryFn: () => api.getRcaReport(detail?.rca_report_id ?? ""),
    enabled: stillRunning,
    refetchInterval: (q) =>
      q.state.data && (q.state.data.status === "pending" || q.state.data.status === "claimed") ? 5000 : false,
  });
  const report: RcaReport | undefined = detail?.rca ?? rcaQ.data;


  const rcaEnabled = useCapabilities().isEnabled("rca_enabled");

  const [resolveOpen, setResolveOpen] = useState(false);
  const [absorbOpen, setAbsorbOpen] = useState(false);
  const [reason, setReason] = useState("");

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: ["case", api.base, caseId] });
    void qc.invalidateQueries({ queryKey: ["cases", api.base] });
  };

  const resolveM = useMutation({
    mutationFn: (r: string) => api.resolveCase(c?.id ?? "", r),
    onSuccess: () => {
      setResolveOpen(false);
      setReason("");
      invalidate();
    },
  });
  const muteM = useMutation({
    mutationFn: () => (c?.state === "muted" ? api.unmuteCase(c.id) : api.muteCase(c?.id ?? "")),
    onSuccess: invalidate,
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
  // The press sends the case id and nothing else: the server resolves the finding behind it, and
  // that id is all that reaches the analysis lane. A case with no finding cannot be analysed, which
  // is what `rca_available` already says.
  const rcaM = useMutation({
    mutationFn: () => api.runCaseRca(caseId ?? ""),
    onSuccess: invalidate,
  });
  // A report EXISTS from the moment the run is queued, so "is there a report" is the wrong question
  // for anything a reader acts on. `analysing` covers the whole in-flight span — the mutation, the
  // gap before the case read carries an id, and the queued/claimed statuses — and `analysed` is the
  // only state in which this page knows anything it did not know before the press.
  const analysing =
    rcaM.isPending || stillRunning || (report != null && rcaRunning(report.status));
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
  const cause = causeLine(c);

  // The window the spell spans. The rate blob's own window wins where it has one — it is what the
  // detector actually measured — and the case's timestamps answer for every other detector.
  const openedAt = detail.tool_error?.onsetAt ?? c.onset_at;
  const closedAt =
    detail.tool_error?.windowClosedAt ?? (c.state === "resolved" ? c.resolved_at : c.last_seen_at);

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
        {/* State as a dot rather than a pill. It is one bit of information and it was the loudest
            thing on the page — a filled chip in error red, competing with the headline it sat
            beside. Beside the reference it is still the first thing scanned and no longer shouts. */}
        <StateDot state={c.state} analysed={c.rca_verdict != null} />
      </nav>

      {/* ------------------------------------------------- the story, in one line */}
      <header className="mb-7.5">
        <div className="flex items-start gap-6">
          {/* Tool and grader ids are long unbroken snake/kebab tokens; without an explicit break
              they overflow the flex track and run under the state chip. */}
          <h1
            className="min-w-0 flex-1 text-h1 text-fg"
            style={{
              overflowWrap: "anywhere",
              textWrap: "balance",
              maxWidth: "30ch" }}
          >
            {c.title}
          </h1>

          {/* ONE control beside the headline, and it is always the same verb: analyse this.
              Four buttons used to sit here, which crowded the title off its line and — worse —
              put "Resolve" and "Legitimate — absorb" ABOVE the report that justifies either one.
              Those two are the disposition of the case, and a disposition is reached at the END
              of a story, not offered at the top of it; they moved to the closing bar with Mute.
              What stays is the one thing the reader can do before they have read anything. */}
          {live && detail.detector_available && rcaEnabled && detail.rca_available && (
            <div className="flex shrink-0 items-center">
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

        {/* The cause on its own line. It used to be appended to the headline as ", caused by …",
            which made a sentence that was already two clauses into four and pushed the window date
            below the fold on a narrow window. A title names the event; this names the finding. */}
        {cause && (
          <p
            className="text-body font-medium text-fg mt-2.5 mx-0 mb-0"
            style={{ maxWidth: "52ch" }}
          >
            {cause.hedged && (
              <span className="font-normal text-muted">
                Likely:{" "}
              </span>
            )}
            {cause.text}
          </p>
        )}

        <div className="flex items-center text-muted gap-2 mt-2.75 text-small" style={{ flexWrap: "wrap" }}>
          <span className="font-mono">
            {stamp(openedAt)}
            {closedAt && ` → ${stamp(closedAt)}`}
          </span>
          <Dot />
          <span className="font-mono text-subtle">{detectorLabel(c.detector)}</span>
          {/* `__unattributed__` is not a call site — a tool's failure rate belongs to the tool
              across every entry point, so there is nothing to name here. */}
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
          {/* The way out, for the reader who wants the ruling's prose and its check scripts.
              Deliberately quiet: it is an escape hatch, not a step in the story. */}
          {detail.finding_id && (
            <>
              <Dot />
              <Link
                to={`${basePath}/classifiers/findings/${encodeURIComponent(detail.finding_id)}`}
                className="text-link hover:text-link-hover transition-colors">
                Finding
              </Link>
            </>
          )}
        </div>

        {c.state === "resolved" && c.resolution_reason && (
          <p className="text-subtle mt-2.5 mx-0 mb-0 text-small">
            Resolved {timeAgo(c.resolved_at ?? c.opened_at)}
            {c.resolved_by ? ` by ${c.resolved_by}` : ""}: {c.resolution_reason}
          </p>
        )}

        {/* A case whose classifier the organization no longer has still renders — it is a record
            of something that happened — but every action on it is withheld, and the page says
            why rather than offering buttons that 409 on the press. */}
        {live && !detail.detector_available && (
          <p className="text-warning mt-4 mx-0 mb-0 text-small" style={{ maxWidth: 720 }}>
            The classifier behind this case ({detectorLabel(c.detector)}) is no longer available to this
            organization, so this case is read-only. It stays here because it happened, not because
            anything is still watching for it.
          </p>
        )}

        {rcaM.isError && <RcaErrorNote error={rcaM.error} />}
      </header>

      {/* -------------------------------------------------------------- how big */}
      <Magnitude detail={detail} basis={c.basis} />

      {/* ----------------------------------------------------------------- why */}
      {(rcaEnabled || detail.rca_report_id != null) && (
        <Attribution report={report} analysing={analysing} basePath={basePath} />
      )}

      {/* ------------------------------------------------------- the failures */}
      <Failures detail={detail} basePath={basePath} />

      <Activity events={detail.events} />

      {/* --------------------------------------------------------- what now */}
      {/* The end of the story is what to do about it, so the verbs that end a case live at the
          end of the page. Resolving or absorbing a case nobody has explained yet is a decision
          taken without the fact that would inform it, so neither appears until there is a report;
          Mute is not a claim about the shift and is always available. */}
      {live && detail.detector_available && (
        <footer className="mt-8.5 pt-3.75" style={{ borderTop: "1px solid var(--color-border)" }}>
          <div className="flex flex-wrap items-center gap-3">
            {analysed && (
              <span className="text-subtle text-small">
                Close this case
              </span>
            )}
            <div className="ml-auto flex items-center gap-2">
              {analysed && (
                <Button size="sm" variant="ghost" onClick={() => setResolveOpen(true)}>
                  Resolve
                </Button>
              )}
              {analysed && detail.absorb_available && (
                <Button size="sm" variant="ghost" onClick={() => setAbsorbOpen(true)}>
                  Absorb as legitimate
                </Button>
              )}
              <Button size="sm" variant="ghost" onClick={() => muteM.mutate()} disabled={muteM.isPending}>
                {c.state === "muted" ? "Unmute" : "Mute"}
              </Button>
            </div>
          </div>
          {muteM.isError && <ErrorNote error={muteM.error} />}
        </footer>
      )}

      <Modal open={resolveOpen} onClose={() => setResolveOpen(false)} title="Resolve this case?">
        <p className="text-muted mt-0 mx-0 mb-3.5 text-body">
          One line on what this turned out to be. It is the only thing that makes a resolved case worth reading later.
        </p>
        <Input
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder="Traffic mix shifted toward enterprise leads"
          autoFocus
        />
        {resolveM.isError && <ErrorNote error={resolveM.error} />}
        <div className="flex justify-end gap-2 mt-4.5">
          <Button variant="ghost" size="sm" onClick={() => setResolveOpen(false)}>
            Cancel
          </Button>
          <Button
            size="sm"
            onClick={() => resolveM.mutate(reason)}
            disabled={reason.trim().length === 0 || resolveM.isPending}
          >
            {resolveM.isPending ? "Resolving…" : "Resolve case"}
          </Button>
        </div>
      </Modal>

      <Modal open={absorbOpen} onClose={() => setAbsorbOpen(false)} title="Absorb as legitimate?">
        <p className="text-muted mt-0 mx-0 mb-3.5 text-body">
          This tells the classifier that where things sit now is correct. It moves the reference this is
          measured against, so the same level stops firing and only a further move opens a new case.
        </p>
        <p className="text-subtle mt-0 mx-0 mb-3.5 text-small">
          Resolving instead closes this case and leaves the bar where it is, so an unchanged population
          opens another case within a day. Absorbing ends the argument rather than this instance of it.
          It does not silence the classifier: a further shift still fires.
        </p>
        {absorbM.isError && <ErrorNote error={absorbM.error} />}
        <div className="flex justify-end gap-2 mt-4.5">
          <Button variant="ghost" size="sm" onClick={() => setAbsorbOpen(false)}>
            Cancel
          </Button>
          <Button size="sm" onClick={() => absorbM.mutate()} disabled={absorbM.isPending}>
            {absorbM.isPending ? "Absorbing…" : "Absorb as legitimate"}
          </Button>
        </div>
      </Modal>
    </div>
  );
}

/* ------------------------------------------------------------------ pieces */

function Block({ label, note, children }: { label: string; note?: string; children: React.ReactNode }) {
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
      </div>
      {children}
    </section>
  );
}

/**
 * How big it was, drawn by whichever figure this detector's movement honestly has.
 *
 * <p>Both figures and both readouts come from the finding page, from the same parsed blob, so the
 * two surfaces cannot disagree about the magnitude of the same event. The detector's own `basis`
 * sentence sits under the figure as its caption rather than above it as prose: it says why THIS
 * detector considers this crossed, which is a caption's job, and the ranked list mixes detectors
 * with different bars so the case has to say which one it means.
 *
 * <p>Neither figure present is the normal state for a shift with no drawable shape, and it says so.
 * That is the rule this file has always held; it now has something real to hold it against.
 */
function Magnitude({ detail, basis }: { detail: CaseDetail; basis: string }) {
  const rate = detail.tool_error;
  const shift = detail.metric;

  return (
    <Block
      label="How big"
      note={rate ? "share of calls that failed" : shift ? "median to 95th percentile, log scale" : undefined}
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

/**
 * Why — the analysis, once it has run.
 *
 * <p>Ranked hypotheses with their evidence, then the checks that were measured and came back clean.
 * Ruled-out is one line rather than a list of rows: what was eliminated is worth knowing and is not
 * worth a quarter of the page, and the finding's own report carries the full checklist.
 *
 * <p>An inconclusive run renders as itself. "Nothing happened here" is a supported conclusion of
 * this lane and the only independent check on the gate triage applies, so it must not read as a
 * failed run or as an empty one.
 */
function Attribution({
  report,
  analysing,
  basePath,
}: {
  report: RcaReport | undefined;
  analysing: boolean;
  basePath: string;
}) {
  if (analysing) {
    return (
      <Block label="Why">
        <div className="flex items-center gap-2.5">
          <StatusPill status="running" label="Analyzing" />
          <span className="text-muted text-body">
            Reading the evidence and bracketing the change point.
          </span>
        </div>
      </Block>
    );
  }

  // Nothing to say before a run. The old empty state described what the button does, with the
  // button already on screen a scroll above it — a heading and a paragraph that added no fact and
  // pushed the failures below the fold. Reached only when nothing is in flight: the running branch
  // above returns first.
  if (!report) return null;

  if (report.status === "failed") {
    return (
      <Block label="Why">
        <p className="text-warning m-0 text-body" style={{ maxWidth: 640 }}>
          The analysis did not finish, so there is nothing to show. Re-running is safe: it starts the same
          analysis again from the beginning.
        </p>
      </Block>
    );
  }

  const hypotheses = report.hypotheses ?? [];
  // EVERY check, not just the eliminated ones. This used to filter to assessment === "ruled_out",
  // which silently dropped the two assessments that actually bear on the cause: a `contributing`
  // check is part of the story and an `explains` check IS the story. On the run that prompted this
  // redesign, `failing_cohort_shape` came back "contributing" and never reached the page at all.
  const checks = report.ruled_out ?? [];
  const eliminated = checks.filter((c) => c.assessment === "ruled_out").length;
  const [lead, ...rest] = hypotheses;

  return (
    <Block label="Why" note={report.verdict ? RCA_VERDICT_LABEL[report.verdict] ?? undefined : undefined}>
      {/* The verdict itself is the Block's note; this line says how much work stands behind it.
          Counted, never written — the old five-sentence summary paragraph opened with a prose
          version of the same claim and then repeated the leading hypothesis almost verbatim. */}
      {checks.length > 0 && (
        <p className="text-muted mt-0 mx-0 mb-4 text-body">
          {checks.length} {checks.length === 1 ? "explanation" : "explanations"} tested, {eliminated}{" "}
          eliminated.
        </p>
      )}

      {/* The investigation, one row per check. This is the pattern-matching the analysis actually
          did, and a row is scanned rather than read — the previous rendering compressed all of it
          to a comma-separated list of check names and threw every `detail` away. */}
      {checks.length > 0 && (
        <ListChassis>
          {checks.map((c) => (
            <div
              key={c.check}
              className="grid items-baseline bg-surface gap-4 py-2.75 px-3.5"
              style={{ gridTemplateColumns: "150px 92px 1fr" }}
            >
              <span className="font-mono text-fg text-small" style={{ overflowWrap: "anywhere" }}>
                {c.check}
              </span>
              <span
                className="font-mono uppercase text-label"
                style={{ color: assessmentColour(c.assessment) }}
              >
                {(c.assessment ?? "unknown").replace(/_/g, " ")}
              </span>
              <span className="text-muted text-small">
                {c.detail}
              </span>
            </div>
          ))}
        </ListChassis>
      )}

      {/* A report with no hypotheses is a legitimate outcome — nothing survived as a lead — and
          there the summary is the only prose there is. Where hypotheses exist they make the same
          claim with a confidence and evidence attached, so the summary is the redundant copy and
          the one worth dropping. */}
      {hypotheses.length === 0 && report.summary && (
        <p className="text-fg mt-4.5 mx-0 mb-0 text-body" style={{ maxWidth: 700 }}>
          {report.summary}
        </p>
      )}

      {lead && (
        <div className="mt-5.5">
          <p className="text-body font-medium text-fg mt-0 mx-0 mb-1.25">
            What&rsquo;s left
          </p>
          <Lead h={lead} basePath={basePath} />
        </div>
      )}

      {/* Ranked "most likely first" by the schema, so everything past the first is a weaker lead.
          Rendering them as peers of the leading explanation — which is what a flat <ol> did — gave
          a low-confidence aside the same weight as the answer. */}
      {rest.map((h, i) => (
        <details key={`${h.title}-${i}`} className="mt-3">
          <summary
            className="flex cursor-pointer select-none items-center text-subtle hover:text-muted transition-colors gap-2 text-small"
            style={{ transitionDuration: "var(--duration-micro)" }}
          >
            <span className="font-mono">+</span>
            <span>{h.title}</span>
            <Confidence level={h.confidence} />
          </summary>
          <div className="mt-2.5 pl-4.5">
            <Lead h={h} basePath={basePath} hideTitle />
          </div>
        </details>
      ))}
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

/** How sure the analysis is, in the one place it can change a reader's mind about a claim. */
function Confidence({ level }: { level: string }) {
  const warn = level === "high" || level === "medium";
  return (
    <span
      className="font-mono uppercase text-label rounded-control px-1.5 py-0.5"
      style={{
        color: warn ? "var(--color-warning)" : "var(--color-subtle)",
        background: warn ? "var(--color-warning-subtle)" : "var(--color-raised)" }}
    >
      {level}
    </span>
  );
}

/** One hypothesis: the claim, how sure, why, and the traces it was read off. */
function Lead({
  h,
  basePath,
  hideTitle,
}: {
  h: RcaReport["hypotheses"][number];
  basePath: string;
  hideTitle?: boolean;
}) {
  return (
    <>
      {!hideTitle && (
        <p className="flex flex-wrap items-center mt-0 mx-0 mb-1.5 gap-2.25">
          <span className="text-body font-medium text-fg">
            {h.title}
          </span>
          <Confidence level={h.confidence} />
        </p>
      )}
      <p className="text-muted m-0 text-body" style={{ maxWidth: 680 }}>
        {h.rationale}
      </p>
      {h.evidence_trace_ids.length > 0 && (
        <div className="flex flex-wrap gap-1.5 mt-2.25">
          {h.evidence_trace_ids.map((id) => (
            <Link
              key={id}
              to={`${basePath}/traces/${encodeURIComponent(id)}`}
              className="rounded-control border border-border-strong font-mono text-link hover:text-link-hover transition-colors py-0.5 px-1.75 text-label"
              style={{ transitionDuration: "var(--duration-micro)" }}
            >
              {truncateId(id)}
            </Link>
          ))}
        </div>
      )}
    </>
  );
}

/**
 * The failures themselves — the actual error spans, a page at a time.
 *
 * <p>Spans rather than traces or signatures. A signature summary answers "which failure took over",
 * which is a question about the population; a reader here is asking "what actually broke", which is
 * answered by the error a call returned. `EvidenceSpanView` carries `errorType` beside the span's
 * own name and clock, so this is the failure itself rather than a description of it.
 *
 * <p>Paged off the finding's evidence rather than the case's exemplars: the case caps at five refs
 * per role for the header's sake, and a tool-error cause can cite tens of thousands. `nextCursor`
 * is what makes "more if they want" real instead of a truncation nobody was told about.
 */
function Failures({ detail, basePath }: { detail: CaseDetail; basePath: string }) {
  const { api } = useTenant();
  const rate = detail.tool_error;
  const findingId = detail.finding_id;
  const [cursor, setCursor] = useState<string | undefined>(undefined);
  const [rows, setRows] = useState<EvidenceSpan[]>([]);

  const evidenceQ = useQuery({
    queryKey: ["case-evidence", api.base, findingId, cursor],
    queryFn: () => api.getBehaviorFindingEvidence(findingId ?? "", { role: "witness", limit: 8, cursor }),
    enabled: findingId != null,
  });

  // Accumulate pages rather than replace: "show more" grows the list a reader is already reading.
  const page = evidenceQ.data;
  const seen = rows.length > 0 ? rows : (page?.rows ?? []);
  const all = cursor && page ? [...rows, ...page.rows] : seen;

  if (!findingId) return null;

  const total = rate?.failuresCur ?? page?.recordedCounts?.witness;

  return (
    <Block label="The failures" note={total != null ? `${total.toLocaleString()} in this window` : undefined}>
      {evidenceQ.isLoading && all.length === 0 ? (
        <TableSkeleton rows={4} cols={3} />
      ) : all.length === 0 ? (
        <p className="text-subtle m-0 text-body" style={{ maxWidth: 560 }}>
          The spans behind this finding have aged out of retention. The claim stands on the counts it
          was measured with; the individual calls are gone.
        </p>
      ) : (
        <>
          <div className="rounded-card border border-border overflow-hidden">
            {/* Column widths are duplicated between this row and ErrorSpanRow rather than shared
                through a grid: the rows are anchors, and wrapping them in a grid to inherit tracks
                would put the click target on the cell instead of the row. */}
            <div
              className="flex items-baseline border-b border-border bg-raised text-column-header text-muted gap-3.5 py-1.75 px-3">
              <span className="shrink-0" style={{ width: 88 }}>
                Time
              </span>
              <span className="min-w-0 flex-1">Input</span>
              <span className="min-w-0 flex-1">Output</span>
              <span className="shrink-0" style={{ width: 118 }}>
                Call site
              </span>
              <span className="shrink-0 text-right" style={{ width: 68 }}>
                Duration
              </span>
            </div>
            <ul className="m-0 p-0" style={{ listStyle: "none", maxHeight: 300, overflowY: "auto" }}>
              {all.map((s, i) => (
                <ErrorSpanRow key={`${s.traceId}-${s.spanId}-${i}`} span={s} basePath={basePath} />
              ))}
            </ul>
          </div>
          {page?.nextCursor && (
            <Button
              size="sm"
              variant="ghost"
              onClick={() => {
                setRows(all);
                setCursor(page.nextCursor ?? undefined);
              }}
      className="mt-2.5">
              {evidenceQ.isFetching ? "Loading…" : "Show more"}
            </Button>
          )}
        </>
      )}
    </Block>
  );
}

/** One failing call: when, what it was, and what it returned. */
function ErrorSpanRow({ span, basePath }: { span: EvidenceSpan; basePath: string }) {
  const to =
    span.traceId != null
      ? `${basePath}/traces/${encodeURIComponent(span.traceId)}${span.spanId ? `#${encodeURIComponent(span.spanId)}` : ""}`
      : null;

  const body = (
    <>
      <span className="font-mono text-subtle shrink-0 text-label" style={{ width: 88 }}>
        {span.startedAt ? stamp(span.startedAt) : "—"}
      </span>
      {/* What the call was given and what came back, truncated server-side. One line each: enough
          to recognise the call and read the error it returned, and the row opens the trace for the
          rest. An aged-out payload renders empty rather than as a dash pretending to be a value. */}
      <span className="min-w-0 flex-1 truncate font-mono text-subtle text-label">
        {span.inputPreview ?? ""}
      </span>
      <span
        className={cn("min-w-0 flex-1 truncate font-mono text-label", span.errorType ? "text-error" : "text-muted")}
        
      >
        {span.outputPreview ?? span.errorType ?? ""}
      </span>
      <span className="shrink-0 truncate text-muted text-small" style={{ width: 118 }}>
        {displayCallSite(span.callSiteId) ?? ""}
      </span>
      {/* Last, and the only figure on the row. These span 25ms to seventeen minutes: one end is a
          call refused on arrival, the other one that hung until something gave up. */}
      <span
        className="font-mono text-fg shrink-0 text-right text-small"
        style={{ width: 68, fontVariantNumeric: "tabular-nums" }}
      >
        {formatDuration(span.latencyMs)}
      </span>
    </>
  );

  return (
    <li className="border-b border-border last:border-b-0">
      {to ? (
        <Link
          to={to}
          className="flex items-baseline hover:bg-hover transition-colors gap-3.5 py-2 px-3"
          style={{ transitionDuration: "var(--duration-micro)" }}
        >
          {body}
        </Link>
      ) : (
        <div className="flex items-baseline gap-3.5 py-2 px-3">
          {body}
        </div>
      )}
    </li>
  );
}

/**
 * What has happened to this case, collapsed.
 *
 * <p>Kept because a case that recovered and re-fired is a fact the story needs and nothing else on
 * the page carries it. Collapsed because it is the least urgent thing here and used to be a quarter
 * of the scroll.
 */
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

function RcaErrorNote({ error }: { error: unknown }) {
  // Reachable even though the button is gated on `rca_available`: the analysis runs against a live
  // finding, and it can be resolved between this page loading and the click.
  if (error instanceof ApiError && error.code === "RCA.SUBJECT_NOT_FOUND") {
    return (
      <p className="text-warning mt-3 mx-0 mb-0 text-small">
        The finding behind this case is no longer there, so there is nothing for the analysis to read. If the
        case is still open, it will close itself on the next check.
      </p>
    );
  }
  return <ErrorNote error={error} />;
}
