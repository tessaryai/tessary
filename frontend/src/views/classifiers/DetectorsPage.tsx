// SPDX-License-Identifier: Apache-2.0
/*
 * Detectors: the catalog, and everything about one detector.
 *
 * Enabling a detector and tuning its bar are things you do when you set the product up and then
 * rarely again, while clearing findings is daily, so the catalog lives on its own page and the
 * queue keeps one card naming what is switched on.
 *
 * Row click opens the detail rail (URL-addressable via `?classifier=<id>`): the description in
 * full, status, this detector's own findings un-gated, tuning, the traces it tripped on, and the
 * baseline changelog. The rail's Findings block stays un-gated on purpose: the queue is the alert
 * list, this is the lead list for one detector, and a gated rail would be empty forever.
 *
 * NOTHING ESCALATES ON ITS OWN, unless an org has asked it to. A classifier firing writes a row and
 * stops: grading a flagged trace costs a grader call and ruling a finding costs an E2B microVM, so
 * the sweep is not entitled to spend either. That makes this rail the place both escalations start,
 * from a person who has looked. (`triage_automatic_enabled` presses the second button unattended,
 * off by default and bounded when on; nothing on this page changes when it is.)
 */
import { lazy, Suspense, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router-dom";
import type {
  BehaviorFinding,
  Classifier,
  ClassifierDailyVolume,
  ClassifierEvent,
  ClassifierHealth,
} from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import { ErrorNote, LoadingRow, PageHeader, Rail, Toggle, cn } from "../../ui";
import { METRIC_DRIFT_DETECTORS, TuningSection } from "./TuningSection";
import {
  BEHAVIOR_DETECTOR,
  CONTAINER,
  Fact,
  RailBlock,
  ResolveVerbs,
  SOP_CONFORMANCE_DETECTOR,
  SectionLabel,
  VerbButton,
  ago,
  chainWords,
  firstSentence,
} from "./shared";
// This build's `@paid` alias; see src/paid/index.ts for the mechanism.
import { paid } from "@paid";

// A separate lazy chunk, not a static import: nobody opening the Classifiers page pays for the
// debug section's code until they actually expand its disclosure. See `views/classifiers/debug/`.
const DebugSection = lazy(() => import("./debug/DebugSection"));

/** The detectors that open findings. Their rails get a Findings block; nobody else's does. */
const FINDING_DETECTORS: ReadonlySet<string> = new Set([
  BEHAVIOR_DETECTOR,
  ...METRIC_DRIFT_DETECTORS,
  SOP_CONFORMANCE_DETECTOR,
]);

/** Detections shown in the rail. Enough to read a pattern, short of a second page. */
const DETECTION_LIMIT = 25;


export function DetectorsPage() {
  // Slugs are for the breadcrumb: `..` resolves against the ROUTE, and `classifiers/detectors` is one
  // flat route, so a single step up lands on the project root rather than on Classifiers.
  const { api, orgSlug, projectSlug } = useTenant();
  const qc = useQueryClient();
  const [params, setParams] = useSearchParams();
  const selectedId = params.get("classifier");

  const classifiersQ = useQuery({ queryKey: ["classifiers", api.base], queryFn: api.listClassifiers });
  const volumeQ = useQuery({
    queryKey: ["classifier-volume", api.base],
    queryFn: () => api.getClassifierDailyVolume(7),
  });
  const healthQ = useQuery({ queryKey: ["classifier-health", api.base], queryFn: api.listClassifierHealth });

  const toggleM = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) => api.setClassifierEnabled(id, enabled),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["classifiers", api.base] }),
  });

  const classifiers = classifiersQ.data ?? [];
  const selected = classifiers.find((c) => c.id === selectedId) ?? null;

  /** 7d detection counts per classifier: the status label's only source. */
  const counts = useMemo(() => sevenDayCounts(volumeQ.data), [volumeQ.data]);

  const openRail = (id: string) => {
    const next = new URLSearchParams(params);
    next.set("classifier", id);
    setParams(next, { replace: false });
  };
  const closeRail = () => {
    const next = new URLSearchParams(params);
    next.delete("classifier");
    setParams(next, { replace: false });
  };

  return (
    <div style={CONTAINER}>
      <PageHeader
        breadcrumb={[
          { label: "Classifiers", to: `/orgs/${orgSlug}/projects/${projectSlug}/classifiers` },
          { label: "Catalog" },
        ]}
        kicker="Monitor"
        title="Catalog"
      />

      {classifiersQ.isLoading && <LoadingRow />}
      {classifiersQ.isError && <ErrorNote error={classifiersQ.error} />}

      {classifiersQ.data && classifiers.length === 0 && (
        <p className="text-subtle mt-6 text-body" style={{ maxWidth: 520 }}>
          No classifiers yet. Tessary seeds them when a project is created, and again on the next sweep once
          traffic arrives. There is nothing to fix here.
        </p>
      )}

      {classifiers.length > 0 && (
        <>
          <SectionLabel label="Classifiers" meta={`${classifiers.length}`} />
          <div className="rounded-card border border-border overflow-hidden">
            <div className="flex flex-col gap-px bg-border">
              {classifiers.map((c) => (
                <DetectorRow
                  key={c.id}
                  classifier={c}
                  count={counts.get(c.id) ?? 0}
                  volumeKnown={volumeQ.data != null}
                  health={(healthQ.data ?? []).find((h) => h.classifier_id === c.id)}
                  onOpen={() => openRail(c.id)}
                  onToggle={(enabled) => toggleM.mutate({ id: c.id, enabled })}
                />
              ))}
            </div>
          </div>
          <p className="text-subtle mt-3 text-small" style={{ maxWidth: 560 }}>
            The complete set for this organization, not a filtered view. Nothing is watching that isn't listed
            here.
          </p>
        </>
      )}

      <ClassifierRail
        classifier={selected}
        health={(healthQ.data ?? []).find((h) => h.classifier_id === selected?.id)}
        onClose={closeRail}
      />
    </div>
  );
}

/** Detections per classifier over the returned window, summed from the daily buckets. */
function sevenDayCounts(volume: ClassifierDailyVolume | undefined): Map<string, number> {
  const out = new Map<string, number>();
  for (const row of volume?.classifiers ?? []) {
    out.set(row.classifier_id, (row.counts ?? []).reduce((a: number, b: number) => a + b, 0));
  }
  return out;
}

function DetectorRow({
  classifier,
  count,
  volumeKnown,
  health,
  onOpen,
  onToggle,
}: {
  classifier: Classifier;
  count: number;
  volumeKnown: boolean;
  health: ClassifierHealth | undefined;
  onOpen: () => void;
  onToggle: (enabled: boolean) => void;
}) {
  const failing = health?.status === "failed";
  // A silent tripwire is healthy: it reads faint, never as a broken dash.
  const quiet = count === 0;
  const status = !volumeKnown ? "–" : quiet ? "quiet 7d" : `${count} detection${count === 1 ? "" : "s"} 7d`;

  return (
    <div className="flex items-center bg-surface hover:bg-hover transition-colors gap-3.5 py-3.25 px-4">
      <button type="button" onClick={onOpen} className="flex-1 min-w-0 text-left cursor-pointer">
        <div className="text-fg text-body">
          {classifier.name}
        </div>
        <div className="text-muted truncate mt-0.75 text-small">
          {classifier.description ? firstSentence(classifier.description) : "No description."}
        </div>
      </button>

      {failing && (
        <span className="shrink-0 text-warning text-label"  title={health?.last_error ?? undefined}>
          sweep failing
        </span>
      )}

      <span className={cn("shrink-0 font-mono text-small", quiet ? "text-subtle" : "text-muted")} >
        {status}
      </span>

      <Toggle
        checked={classifier.enabled}
        onChange={onToggle}
        label={`${classifier.enabled ? "Disable" : "Enable"} ${classifier.name}`}
      />
    </div>
  );
}


/**
 * This classifier's findings: everything it has opened, with its ruling.
 *
 * <p>Ungated, like the page-level list. The gate's whole job was to hide leads until a machine
 * confirmed them, and every finding now carries its own ruling, so the thing to show is what triage
 * made of this detector's output, which is also the only calibration signal it has. A detector
 * producing nothing but `negative` rulings is one measuring the wrong thing.
 */
function RailFindings({ classifier }: { classifier: Classifier }) {
  const { api } = useTenant();
  const qc = useQueryClient();
  const key = ["classifier-findings", api.base, classifier.id];

  const findingsQ = useQuery({
    queryKey: key,
    queryFn: () => api.listBehaviorFindings("open", "all", undefined, classifier.detector),
  });
  const invalidate = () => void qc.invalidateQueries({ queryKey: key });
  const analyzeM = useMutation({ mutationFn: (id: string) => api.analyzeBehaviorFinding(id), onSuccess: invalidate });
  const resolveM = useMutation({
    mutationFn: ({ id, action }: { id: string; action: "expected" | "not_expected" }) =>
      api.resolveBehaviorFinding(id, action),
    onSuccess: invalidate,
  });

  const findings = findingsQ.data?.findings ?? [];

  return (
    <RailBlock
      label="Findings"
      meta={findings.length === 0 ? undefined : `${findings.length}`}
    >
      {findingsQ.isLoading && <LoadingRow />}
      {findingsQ.isError && <ErrorNote error={findingsQ.error} />}
      {findingsQ.isSuccess && findings.length === 0 && (
        <p className="text-subtle m-0 text-small">
          Nothing has drifted. This classifier opens a finding when a whole population moves, not when
          one trace looks odd.
        </p>
      )}
      {findings.length > 0 && (
        <>
          <div className="flex flex-col gap-3.5">
            {findings.map((f) => (
              <RailFindingRow
                key={f.id}
                finding={f}
                busy={analyzeM.isPending || resolveM.isPending}
                onAnalyze={() => analyzeM.mutate(f.id)}
                onResolve={(action) => resolveM.mutate({ id: f.id, action })}
              />
            ))}
          </div>
          {analyzeM.isError && <ErrorNote error={analyzeM.error} />}
          {resolveM.isError && <ErrorNote error={resolveM.error} />}
        </>
      )}
    </RailBlock>
  );
}

/**
 * One finding in the rail: what moved, where triage left it, and the three things you can do about it.
 *
 * <p>The machine action and the two human verdicts sit on opposite ends of the row because they are
 * different kinds of act: <em>Run triage</em> spends a microVM to audit the claim, the other two are
 * the decision, and stacking all three as peers invited pressing the expensive one by reflex.
 */
function RailFindingRow({
  finding,
  busy,
  onAnalyze,
  onResolve,
}: {
  finding: BehaviorFinding;
  busy: boolean;
  onAnalyze: () => void;
  onResolve: (action: "expected" | "not_expected") => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const triaged = finding.triageStatus === "done";
  const inFlight = finding.triageStatus === "in_flight";
  const summary = finding.triageSummary;

  return (
    <div>
      <div className="text-fg text-small">
        {finding.title}
      </div>
      <div className="text-subtle mt-0.75 text-label">
        {chainWords(finding)}
      </div>
      {summary && (
        <p
          className={cn("text-muted mt-1.25 mx-0 mb-0 text-small", !expanded && "line-clamp-2")}
          
        >
          {summary}
        </p>
      )}
      {expanded && (
        <div className="font-mono text-subtle mt-1.5 text-label">
          {finding.causeKey}
        </div>
      )}
      <div className="flex flex-wrap items-center gap-2 mt-1.75">
        <VerbButton
          kind="filled"
          // Once per cause: a second press lands on the same job rather than buying a second ruling,
          // so the button stops offering after it has been pressed.
          disabled={busy || triaged || inFlight}
          onClick={onAnalyze}
        >
          {triaged ? "Triaged" : inFlight ? "Triaging…" : "Run triage"}
        </VerbButton>
        <ResolveVerbs causeKind={finding.causeKind} busy={busy} onResolve={onResolve} />
        {summary && (
          <button
            type="button"
            onClick={() => setExpanded((v) => !v)}
            className="text-subtle hover:text-fg transition-colors text-label"
            style={{ transitionDuration: "var(--duration-micro)" }}
          >
            {expanded ? "Less" : "Full ruling"}
          </button>
        )}
      </div>
    </div>
  );
}

/**
 * Detector evidence is heterogeneous JSON: `{matched, pattern, field}` from the regex
 * detectors, `{missing, missing_total}` from omission, `{surprisal, threshold}` from
 * surprisal. Flatten the top level rather than special-casing each detector: every
 * detector's evidence is small and bounded by construction (never the trace payload).
 */
function evidenceSummary(json: string | null | undefined): string | null {
  if (!json) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(json);
  } catch {
    return truncate(json, 200);
  }
  if (parsed == null || typeof parsed !== "object") return truncate(String(parsed), 200);
  const parts: string[] = [];
  for (const [key, value] of Object.entries(parsed as Record<string, unknown>)) {
    if (value == null || value === "") continue;
    const text = Array.isArray(value) ? value.join(", ") : String(value);
    if (!text) continue;
    parts.push(`${key.replace(/_/g, " ")} ${truncate(text, 90)}`);
  }
  return parts.length > 0 ? parts.join(" · ") : null;
}

function truncate(s: string, max: number): string {
  return s.length > max ? `${s.slice(0, max)}…` : s;
}

/**
 * One detection = one trace this classifier tripped on. Rows link into the trace; a
 * context-grain detection (no trace_id) has nothing to open and stays inert rather than
 * pretending to be a link.
 *
 * <p>There is no per-detection "Run analysis". A detection is a Layer-1 flag, a filter, not
 * evidence, and running graders on one would confirm something nobody had decided was worth
 * confirming. Analysis is offered on the finding, where the cause has already been made, and the
 * grader lane is one of the choices there.
 */
function DetectionRow({ event }: { event: ClassifierEvent }) {
  const summary = evidenceSummary(event.evidence_json);
  // Severity reads as text, never a red pill: Classifiers is amber-only by design.
  const severe = event.severity === "warn" || event.severity === "critical";

  const body = (
    <>
      <div className="flex items-baseline gap-2.5">
        <span className={cn("shrink-0 font-mono text-label uppercase", severe ? "text-warning" : "text-subtle")}>
          {event.severity ?? "–"}
        </span>
        <span className="min-w-0 flex-1 truncate font-mono text-fg text-small">
          {event.trace_id ?? `${event.subject_kind} ${event.subject_id}`}
        </span>
        {event.confidence === "low" && (
          <span className="shrink-0 text-subtle text-label">
            low confidence
          </span>
        )}
        <span className="shrink-0 text-subtle text-label">
          {ago(event.detected_at)}
        </span>
      </div>
      {summary && (
        <div className="text-muted mt-1 text-small">
          {summary}
        </div>
      )}
    </>
  );

  if (!event.trace_id) {
    return (
      <div className="flex items-start bg-surface gap-3 py-2.75 px-3.5">
        <div className="min-w-0 flex-1">{body}</div>
      </div>
    );
  }
  return (
    <div className="flex items-start bg-surface gap-3 py-2.75 px-3.5">
      <Link
        to={`../traces/${encodeURIComponent(event.trace_id)}`}
        className="min-w-0 flex-1 hover:opacity-80 transition-opacity"
        style={{ transitionDuration: "var(--duration-micro)" }}
      >
        {body}
      </Link>
    </div>
  );
}

function ClassifierRail({
  classifier,
  health,
  onClose,
}: {
  classifier: Classifier | null;
  health: ClassifierHealth | undefined;
  onClose: () => void;
}) {
  const { api } = useTenant();
  const isBehavior = classifier?.detector === BEHAVIOR_DETECTOR;
  const isConformance = classifier?.detector === SOP_CONFORMANCE_DETECTOR;
  const isMetricDrift = classifier != null && METRIC_DRIFT_DETECTORS.has(classifier.detector);
  const hasFindings = classifier != null && FINDING_DETECTORS.has(classifier.detector);

  const changelogQ = useQuery({
    queryKey: ["behavior-baseline-events", api.base],
    queryFn: () => api.listBehaviorBaselineEvents(50),
    enabled: isBehavior,
  });

  // The traces that tripped this classifier. Keyed on the id so switching rows refetches
  // rather than showing the previous classifier's detections under a new title.
  const detectionsQ = useQuery({
    queryKey: ["classifier-events", api.base, classifier?.id],
    queryFn: () => api.listClassifierEvents(classifier!.id, DETECTION_LIMIT),
    enabled: classifier != null,
  });

  if (!classifier) return null;

  const detections = detectionsQ.data ?? [];

  return (
    <Rail
      open
      onClose={onClose}
      title={classifier.name}
      meta={`${classifier.enabled ? "Enabled" : "Disabled"} · ${classifier.mode}`}
      aria-label={`${classifier.name} detail`}
    >
      <p className="text-muted m-0 text-body">
        {classifier.description ?? "No description."}
      </p>

      <RailBlock label="Status">
        {/* A failing sweep is the one thing here that changes what you do next, so it sits
            above the facts as a callout rather than as one more amber line inside them. */}
        {health?.status === "failed" && (
          <div
            className="text-warning border border-border-strong rounded-card py-2.25 px-2.75 mb-3 text-small"
            style={{ backgroundColor: "var(--color-warning-subtle)" }}
          >
            Sweep failing: {health.last_error ?? "unknown error"}
          </div>
        )}
        <dl
      className="gap-y-1.75 gap-x-3.5 m-0"
      style={{ display: "grid", gridTemplateColumns: "94px minmax(0, 1fr)" }}
        >
          <Fact label="Type">
            <span className="font-mono">{classifier.detector}</span>
            <span className="text-subtle"> · {classifier.built_in ? "built in" : "custom"}</span>
          </Fact>
          <Fact label="Version">
            <span className="font-mono">{classifier.version}</span>
          </Fact>
          <Fact label="Last swept">
            {health?.last_swept_at ? new Date(health.last_swept_at).toLocaleString() : "never"}
          </Fact>
        </dl>
      </RailBlock>

      {/* Above Findings on purpose: whether a rule is armed decides whether the absence of findings
          under it means anything, so it has to be read first.

          This build renders no Rulebook block: paid.classifierRail() returns null here. The
          `isConformance` guard stays in this file since the detector key is open vocabulary, so a
          build with no rulebook implementation simply shows nothing in its place. */}
      {isConformance && paid.classifierRail(SOP_CONFORMANCE_DETECTOR)}

      {hasFindings && <RailFindings classifier={classifier} />}

      {isMetricDrift && (
        <RailBlock label="Tuning">
          <TuningSection classifier={classifier} />
        </RailBlock>
      )}

      <RailBlock
        label="Detections"
        meta={
          detections.length === 0
            ? undefined
            : `${detections.length}${detections.length === DETECTION_LIMIT ? " most recent" : ""} · select one to open the trace`
        }
      >
        {detectionsQ.isLoading && <LoadingRow />}
        {detectionsQ.isError && <ErrorNote error={detectionsQ.error} />}
        {detectionsQ.isSuccess && detections.length === 0 && (
          <p className="text-subtle m-0 text-small">
            {classifier.enabled
              ? "This classifier hasn't fired on anything yet. Quiet is healthy."
              : "Disabled, so it isn't sweeping. Past detections stay on the traces they were written to."}
          </p>
        )}
        {detections.length > 0 && (
          <div className="rounded-card border border-border overflow-hidden">
            <div className="flex flex-col gap-px bg-border">
              {detections.map((e) => (
                <DetectionRow key={e.id} event={e} />
              ))}
            </div>
          </div>
        )}
      </RailBlock>

      {/* This build renders no Baselines block: paid.classifierRail() returns null here. The
          `isBehavior` guard stays in this file since the detector key is open vocabulary, so a
          build with no baselines implementation simply shows nothing in its place. */}
      {isBehavior && paid.classifierRail(BEHAVIOR_DETECTOR)}

      {isBehavior && (
        <RailBlock label="Baseline changelog">
          {changelogQ.isLoading && <LoadingRow />}
          {changelogQ.isError && <ErrorNote error={changelogQ.error} />}
          {/*
            * `isSuccess`, not "no rows and not loading": a failed read also has no rows too, and
            * rendering "Nothing has changed the baseline yet." there would be an assertion about
            * the baseline made from the absence of an answer about it.
            */}
          {changelogQ.isSuccess && (changelogQ.data ?? []).length === 0 && (
            <p className="text-subtle m-0 text-small">
              Nothing has changed the baseline yet.
            </p>
          )}
          {/* Date in a fixed gutter so the events line up as a column, not a ragged list. */}
          <ul className="m-0 p-0" style={{ listStyle: "none" }}>
            {(changelogQ.data ?? []).map((e) => (
              <li key={e.id} className="flex items-baseline gap-3 py-1 px-0 text-small">
                <span className="shrink-0 font-mono text-subtle" style={{ width: 72 }}>
                  {new Date(e.occurredAt).toLocaleDateString()}
                </span>
                <span className="min-w-0 text-muted">
                  {e.event}
                  {e.gramKey && (
                    <>
                      {" · "}
                      <span className="font-mono">{e.gramKey}</span>
                    </>
                  )}
                </span>
              </li>
            ))}
          </ul>
        </RailBlock>
      )}

      <div className="border-t border-border mt-4.5 pt-4.5">
        <Suspense fallback={<LoadingRow />}>
          <DebugSection classifier={classifier} />
        </Suspense>
      </div>
    </Rail>
  );
}
