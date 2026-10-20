// SPDX-License-Identifier: Apache-2.0
// Nothing escalates on its own unless the org enabled `triage_automatic_enabled`: grading costs a
// grader call and ruling a finding a microVM, so this page is where a person starts both.
import { lazy, Suspense, useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";
import { AlertCircle, AlertTriangle } from "lucide-react";
import type {
  BehaviorFinding,
  Classifier,
  ClassifierDailyVolume,
  ClassifierEvent,
  ClassifierHealth,
  GroundednessStatus,
} from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import { invalidateClassifierReads } from "./classifierReads";
import { Button, ErrorNote, LoadingRow, PageHeader, Section, Spinner, Toggle, cn } from "../../ui";
import { ClassifierResetModal } from "./ClassifierResetModal";
import { FRUSTRATION_DETECTOR, FrustrationEnableModal } from "./FrustrationEnableModal";
import { GroundednessEnableModal } from "./GroundednessEnableModal";
import { GroundednessRestartModal } from "./GroundednessRestartModal";
import { GroundednessTurnOffModal } from "./GroundednessTurnOffModal";
import {
  GROUNDEDNESS_DETECTOR,
  GROUNDEDNESS_MODEL,
  MODE_LABEL,
  clearSetupFlag,
  clockTime,
  groundednessStatusKey,
  neverSetUp,
  notScoringLabel,
  readSetupFlag,
  rowState,
  writeSetupFlag,
} from "./groundedness";
import { CallSitesSection, UNSCOPED_DETECTORS } from "./CallSitesSection";
import { METRIC_DRIFT_DETECTORS, TuningSection } from "./TuningSection";
import {
  CONTAINER, Fact, PROVIDER_PAUSES, ResolveVerbs, RunTriageButton, TriageStateText, VerbButton, ago, chainWords,
  triagePoll, triageState,
} from "./shared";

const WAITING_ON_SCHEMAS = "waiting_on_schemas";

const SCHEMAS_EXPLAINED =
  "Waiting on schemas. No call site declares an output schema yet, so there is nothing to check outputs against. " +
  "Schemas arrive when your repository is connected and assessed.";

const RATE_SUBJECTS: Record<string, string> = {
  frustration: "conversations",
  groundedness: "answers",
  malformed_output: "outputs",
};

const DRIFT_SUBJECTS: Record<string, string> = {
  cost_drift: "how cost per turn usually spreads on each call site",
  duration_drift: "how durations usually spread on each call site and each tool it measures",
};

const DebugSection = lazy(() => import("./debug/DebugSection"));

const DETECTION_LIMIT = 25;

const GROUNDEDNESS_STATUS_POLL_MS = 30_000;

export function ClassifierConfigurePage() {
  const { classifierId } = useParams<{ classifierId: string }>();
  const { api, orgSlug, projectSlug } = useTenant();
  const basePath = `/orgs/${orgSlug}/projects/${projectSlug}`;
  const classifiersPath = `${basePath}/classifiers`;

  const classifiersQ = useQuery({ queryKey: ["classifiers", api.base], queryFn: api.listClassifiers });
  const classifier = classifiersQ.data?.find((c) => c.id === classifierId) ?? null;

  return (
    <div style={CONTAINER}>
      {classifiersQ.isLoading && <LoadingRow />}
      {classifiersQ.isError && <ErrorNote error={classifiersQ.error} />}
      {classifiersQ.isSuccess && !classifier && (
        <>
          <PageHeader breadcrumb={[{ label: "Classifiers", to: classifiersPath }]} title="Classifier not found" />
          <p className="text-body text-muted m-0">No classifier with this id in this project.</p>
          <Link to={classifiersPath} className="inline-block mt-3 text-body text-link hover:text-link-hover">
            Back to Classifiers
          </Link>
        </>
      )}
      {/* Keyed so a move to another classifier's page reseeds every form from that classifier. */}
      {classifier && <Configure key={classifier.id} classifier={classifier} basePath={basePath} />}
    </div>
  );
}

function Configure({ classifier, basePath }: { classifier: Classifier; basePath: string }) {
  const { api, orgSlug, projectSlug } = useTenant();
  const qc = useQueryClient();
  const providersPath = `${basePath}/settings/providers`;
  const isGroundedness = classifier.detector === GROUNDEDNESS_DETECTOR;

  const volumeQ = useQuery({
    queryKey: ["classifier-volume", api.base],
    queryFn: () => api.getClassifierDailyVolume(7),
  });
  const healthQ = useQuery({ queryKey: ["classifier-health", api.base], queryFn: api.listClassifierHealth });
  const health = (healthQ.data ?? []).find((h) => h.classifier_id === classifier.id);

  const groundednessQ = useQuery({
    queryKey: groundednessStatusKey(api.base, classifier.id),
    queryFn: () => api.getGroundednessStatus(classifier.id),
    enabled: isGroundedness,
    refetchInterval: GROUNDEDNESS_STATUS_POLL_MS,
  });
  const groundedness = isGroundedness ? groundednessQ.data : undefined;
  const [settingUp, setSettingUp] = useState(() => readSetupFlag(orgSlug, projectSlug));
  useEffect(() => {
    if (groundedness?.state !== "on") return;
    clearSetupFlag(orgSlug, projectSlug);
    setSettingUp(false);
  }, [groundedness?.state, orgSlug, projectSlug]);

  const [modal, setModal] = useState<
    "frustration-enable" | "groundedness-enable" | "groundedness-turn-off" | "restart" | "reset" | null
  >(null);
  const closeModal = () => setModal(null);

  const toggleM = useMutation({
    mutationFn: (enabled: boolean) => api.setClassifierEnabled(classifier.id, enabled),
    onSuccess: () => invalidateClassifierReads(qc, api.base),
  });
  const toggle = (enabled: boolean) => {
    if (enabled && classifier.detector === FRUSTRATION_DETECTOR) return setModal("frustration-enable");
    if (isGroundedness) {
      if (!enabled) return setModal("groundedness-turn-off");
      if (!groundedness || neverSetUp(groundedness)) return setModal("groundedness-enable");
    }
    toggleM.mutate(enabled);
  };

  const count = sevenDayCount(volumeQ.data, classifier.id);
  const isDrift = METRIC_DRIFT_DETECTORS.has(classifier.detector);

  return (
    <>
      <PageHeader
        breadcrumb={[{ label: "Classifiers", to: `${basePath}/classifiers` }, { label: classifier.name }]}
        kicker="Configure classifier"
        title={classifier.name}
        subtitle={classifier.description ?? "No description."}
        actions={
          <>
            {health?.status === "failed" && (
              <span className="flex items-center gap-1.5 text-small text-fg-secondary whitespace-nowrap">
                <AlertTriangle size={13} strokeWidth={1.75} className="text-warning shrink-0" aria-hidden="true" />
                Sweep failing
              </span>
            )}
            <StatusWords
              classifier={classifier}
              count={count}
              groundedness={groundedness}
              settingUp={settingUp}
              providersPath={providersPath}
            />
            <Toggle
              checked={classifier.enabled}
              onChange={toggle}
              label={`${classifier.enabled ? "Disable" : "Enable"} ${classifier.name}`}
            />
          </>
        }
      />
      {toggleM.isError && <ErrorNote className="mb-6" error={toggleM.error} />}

      <div style={{ maxWidth: 820 }}>
        <Section title="Status">
          <StatusBlock
            classifier={classifier}
            health={health}
            groundedness={groundedness}
            providersPath={providersPath}
            onRestart={() => setModal("restart")}
          />
        </Section>

        {!UNSCOPED_DETECTORS.has(classifier.detector) && (
          <Section title="Call sites">
            {classifier.detector === FRUSTRATION_DETECTOR && (
              <p className="text-muted m-0 mb-2.5 text-small">
                To spend less, limit it to the call sites that reply to the user. Routers and memory passes are not
                conversations.
              </p>
            )}
            <CallSitesSection key={classifier.id} classifier={classifier} />
          </Section>
        )}

        {isDrift && (
          <Section title="Tuning">
            <TuningSection classifier={classifier} />
          </Section>
        )}

        <Section title="How a finding opens">
          <p className="text-body text-fg-secondary m-0" style={{ maxWidth: 620 }}>
            {howAFindingOpens(classifier.detector)}
          </p>
        </Section>

        {isDrift && <Findings classifier={classifier} />}

        <Detections classifier={classifier} />

        <Section title="Reset">
          <p className="text-muted m-0 mb-2.25 text-small">
            Delete what this classifier found and learned, and check every kept trace again.
          </p>
          <Button size="sm" variant="danger" onClick={() => setModal("reset")}>
            Reset classifier
          </Button>
        </Section>

        <div className="border-t border-border pt-4.5">
          <Suspense fallback={<LoadingRow />}>
            <DebugSection classifier={classifier} />
          </Suspense>
        </div>
      </div>

      {modal === "frustration-enable" && (
        <FrustrationEnableModal classifierId={classifier.id} onClose={closeModal} onEnabled={closeModal} />
      )}
      {modal === "groundedness-enable" && (
        <GroundednessEnableModal
          classifierId={classifier.id}
          onClose={closeModal}
          onEnabled={() => {
            clearSetupFlag(orgSlug, projectSlug);
            setSettingUp(false);
          }}
          onPromptCopied={() => {
            writeSetupFlag(orgSlug, projectSlug);
            setSettingUp(true);
          }}
        />
      )}
      {modal === "groundedness-turn-off" && (
        <GroundednessTurnOffModal classifierId={classifier.id} mode={groundedness?.mode} onClose={closeModal} />
      )}
      {modal === "restart" && groundedness && (
        <GroundednessRestartModal
          classifierId={classifier.id}
          mode={groundedness.mode}
          setupRef={groundedness.setup_ref}
          onClose={closeModal}
        />
      )}
      {modal === "reset" && <ClassifierResetModal classifier={classifier} onClose={closeModal} />}
    </>
  );
}

function sevenDayCount(volume: ClassifierDailyVolume | undefined, id: string): number | undefined {
  if (!volume) return undefined;
  const row = (volume.classifiers ?? []).find((c) => c.classifier_id === id);
  return (row?.counts ?? []).reduce((a: number, b: number) => a + b, 0);
}

/** No numbers: the bar lives on the server. */
function howAFindingOpens(detector: string): string {
  if (detector === "tool_error") {
    return "This classifier learns the usual failure rate for each tool, across every call site that calls it. A finding opens when a tool's recent failure rate rises clearly above that baseline. It opens nothing while it is still learning.";
  }
  const rate = RATE_SUBJECTS[detector];
  if (rate) {
    return `This classifier learns the usual flagged rate for each call site. A finding opens when the share of ${rate} it flags in recent traffic rises clearly above that baseline. It opens nothing while it is still learning.`;
  }
  const drift = DRIFT_SUBJECTS[detector];
  if (drift) {
    return `This classifier learns ${drift}. A finding opens when recent traffic moves clearly away from that baseline. Tuning sets how big a move counts.`;
  }
  return "A finding opens when enough detections land within one window. This classifier learns no baseline, so it judges from its first detection.";
}

function StatusWords({
  classifier,
  count,
  groundedness,
  settingUp,
  providersPath,
}: {
  classifier: Classifier;
  count: number | undefined;
  groundedness: GroundednessStatus | undefined;
  settingUp: boolean;
  providersPath: string;
}) {
  const model = groundedness ? groundednessWords(groundedness, settingUp) : null;
  if (model) return <GroundednessWords status={model} />;

  const paused = classifier.readiness ? PROVIDER_PAUSES[classifier.readiness] : undefined;
  if (paused) {
    return (
      <Link
        to={providersPath}
        className="flex items-center gap-1.5 text-accent hover:text-accent-hover transition-colors text-small"
        title={paused.explained}
      >
        <AlertCircle size={13} strokeWidth={1.75} className="text-error" aria-hidden="true" />
        {paused.label}
      </Link>
    );
  }

  const waiting = classifier.readiness === WAITING_ON_SCHEMAS;
  // A silent tripwire is healthy: it reads faint, never as a broken dash.
  const quiet = count === 0;
  const words = waiting
    ? "waiting on schemas"
    : count === undefined
      ? "–"
      : quiet
        ? "quiet 7d"
        : `${count} detection${count === 1 ? "" : "s"} 7d`;
  return (
    <span className={cn("font-mono text-small", quiet || waiting ? "text-subtle" : "text-muted")}>{words}</span>
  );
}

type GroundednessStatusWords = { text: string; kind: "plain" | "faint" | "setting-up" | "alert" };

function groundednessWords(status: GroundednessStatus, settingUp: boolean): GroundednessStatusWords | null {
  switch (rowState(status)) {
    case "not_set_up":
      return settingUp ? { text: "Setting up...", kind: "setting-up" } : { text: "needs setup", kind: "faint" };
    case "not_scoring":
      return { text: notScoringLabel(status), kind: "alert" };
    case "off":
      return { text: "off", kind: "faint" };
    case "on":
      return status.mode === "production" && status.last_caught_up_at
        ? { text: `last run ${clockTime(status.last_caught_up_at)}`, kind: "plain" }
        : null;
  }
}

function GroundednessWords({ status }: { status: GroundednessStatusWords }) {
  if (status.kind === "setting-up" || status.kind === "alert") {
    return (
      <span className="flex items-center gap-1.5 text-muted text-small">
        {status.kind === "alert" ? (
          <AlertCircle size={13} strokeWidth={1.75} className="text-error" aria-hidden="true" />
        ) : (
          <Spinner size="sm" className="text-fg" />
        )}
        {status.text}
      </span>
    );
  }
  return (
    <span className={cn("font-mono text-small", status.kind === "faint" ? "text-subtle" : "text-muted")}>
      {status.text}
    </span>
  );
}

function callSitesFact(classifier: Classifier): string {
  if (UNSCOPED_DETECTORS.has(classifier.detector)) return "Every tool";
  const count = classifier.call_site_ids?.length;
  if (count === undefined) return "Every call site";
  if (count === 0) return "No call sites";
  return `${count} call site${count === 1 ? "" : "s"}`;
}

function StatusBlock({
  classifier,
  health,
  groundedness,
  providersPath,
  onRestart,
}: {
  classifier: Classifier;
  health: ClassifierHealth | undefined;
  groundedness: GroundednessStatus | undefined;
  providersPath: string;
  onRestart: () => void;
}) {
  const groundednessState = groundedness ? rowState(groundedness) : null;
  const modelFacts = groundedness != null && (groundednessState === "on" || groundednessState === "not_scoring");
  const paused = classifier.readiness ? PROVIDER_PAUSES[classifier.readiness] : undefined;
  const callSites = callSitesFact(classifier);

  return (
    <>
      {health?.status === "failed" && (
        <div
          className="flex items-start gap-2 border border-border-strong rounded-card py-2.25 px-2.75 mb-3 text-small"
          style={{ backgroundColor: "var(--color-warning-subtle)" }}
        >
          <AlertTriangle size={14} strokeWidth={1.75} className="text-warning mt-0.5 shrink-0" aria-hidden="true" />
          <span className="text-fg-secondary">Sweep failing: {health.last_error ?? "unknown error"}</span>
        </div>
      )}
      {classifier.readiness === WAITING_ON_SCHEMAS && <p className="text-muted m-0 mb-3 text-small">{SCHEMAS_EXPLAINED}</p>}
      {paused && <ProviderPauseCallout classifier={classifier} explained={paused.explained} providersPath={providersPath} />}
      {groundedness && groundednessState === "not_scoring" && (
        <NotScoringCallout label={notScoringLabel(groundedness)} onRestart={onRestart} />
      )}
      <dl className="gap-y-1.75 gap-x-3.5 m-0" style={{ display: "grid", gridTemplateColumns: "110px minmax(0, 1fr)" }}>
        {modelFacts ? (
          <>
            <Fact label="Runs on">{MODE_LABEL[groundedness.mode]}</Fact>
            <Fact label="Model">
              <span className="font-mono">{GROUNDEDNESS_MODEL}</span>
            </Fact>
            {groundedness.mode === "production" && <Fact label="Schedule">Hourly</Fact>}
            <Fact label="Last scored">
              {groundedness.last_scored_at ? clockTime(groundedness.last_scored_at) : "never"}
            </Fact>
          </>
        ) : (
          <>
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
          </>
        )}
        <Fact label="Mode">
          <span className="font-mono">{classifier.mode}</span>
        </Fact>
        <Fact label="Call sites">{callSites}</Fact>
      </dl>
    </>
  );
}

function ProviderPauseCallout({
  classifier,
  explained,
  providersPath,
}: {
  classifier: Classifier;
  explained: string;
  providersPath: string;
}) {
  const { api } = useTenant();
  const qc = useQueryClient();
  const retryM = useMutation({
    mutationFn: () => api.setClassifierEnabled(classifier.id, true),
    onSuccess: () => invalidateClassifierReads(qc, api.base),
  });

  return (
    <div className="border border-border-strong rounded-card py-2.25 px-2.75 mb-3 text-small">
      <div className="flex items-start gap-2">
        <AlertCircle size={14} strokeWidth={1.75} className="text-error mt-0.5 shrink-0" aria-hidden="true" />
        <p className="text-muted m-0">{explained}</p>
      </div>
      <div className="flex items-center gap-2 mt-2.25">
        <Button size="sm" variant="secondary" loading={retryM.isPending} onClick={() => retryM.mutate()}>
          Retry
        </Button>
        <Link to={providersPath} className="text-accent hover:text-accent-hover transition-colors text-small">
          Settings, Providers
        </Link>
      </div>
      {retryM.isError && <ErrorNote className="mt-2" error={retryM.error} />}
    </div>
  );
}

function NotScoringCallout({ label, onRestart }: { label: string; onRestart: () => void }) {
  return (
    <div className="border border-border-strong rounded-card py-2.25 px-2.75 mb-3 text-small">
      <div className="flex items-start gap-2">
        <AlertCircle size={14} strokeWidth={1.75} className="text-error mt-0.5 shrink-0" aria-hidden="true" />
        <div className="min-w-0">
          <div className="text-fg">{label}</div>
          <p className="text-muted m-0 mt-0.5">The model isn't responding. Restart it to resume scoring.</p>
        </div>
      </div>
      <div className="mt-2.25">
        <Button size="sm" variant="secondary" onClick={onRestart}>
          Restart model
        </Button>
      </div>
    </div>
  );
}

/** Ungated: the rulings are this classifier's only calibration signal. */
function Findings({ classifier }: { classifier: Classifier }) {
  const { api } = useTenant();
  const qc = useQueryClient();
  const key = ["classifier-findings", api.base, classifier.id];

  const findingsQ = useQuery({
    queryKey: key,
    queryFn: () => api.listBehaviorFindings(classifier.detector),
    refetchInterval: (q) => triagePoll(q.state.data?.findings),
  });
  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: key });
    void qc.invalidateQueries({ queryKey: ["behavior-findings", api.base] });
  };
  const analyzeM = useMutation({ mutationFn: (id: string) => api.analyzeBehaviorFinding(id), onSuccess: invalidate });
  const resolveM = useMutation({
    mutationFn: ({ id, action }: { id: string; action: "expected" | "not_expected" }) =>
      api.resolveBehaviorFinding(id, action),
    onSuccess: invalidate,
  });

  const findings = findingsQ.data?.findings ?? [];

  return (
    <Section title="Findings" count={findings.length === 0 ? undefined : findings.length}>
      {findingsQ.isLoading && <LoadingRow />}
      {findingsQ.isError && <ErrorNote error={findingsQ.error} />}
      {findingsQ.isSuccess && findings.length === 0 && (
        <p className="text-muted m-0 text-small">
          Nothing has drifted. This classifier opens a finding when a whole population moves, not when one trace looks
          odd.
        </p>
      )}
      {findings.length > 0 && (
        <>
          <div className="flex flex-col gap-3.5">
            {findings.map((f) => (
              <FindingRow
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
    </Section>
  );
}

/** Run triage spends a microVM, so it sits apart from the two human verdicts to avoid a reflex press. */
function FindingRow({
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
  const summary = finding.triageSummary;

  return (
    <div>
      <div className="text-fg text-small">{finding.title}</div>
      <div className="text-muted mt-0.75 text-label">{chainWords(finding)}</div>
      {summary && <p className={cn("text-muted mt-1.25 mx-0 mb-0 text-small", !expanded && "line-clamp-2")}>{summary}</p>}
      {expanded && <div className="font-mono text-muted mt-1.5 text-label">{finding.causeKey}</div>}
      <div className="flex flex-wrap items-center gap-2 mt-1.75">
        {triaged ? (
          <VerbButton kind="filled" disabled onClick={onAnalyze}>
            Triaged
          </VerbButton>
        ) : (
          <>
            {triageState(finding).tone === "failed" && <TriageStateText finding={finding} className="text-small" />}
            <RunTriageButton finding={finding} busy={busy} onAnalyze={onAnalyze} />
          </>
        )}
        {/* A ruling freezes the finding: every verb on it 409s. */}
        {!triaged && <ResolveVerbs busy={busy} onResolve={onResolve} />}
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

function Detections({ classifier }: { classifier: Classifier }) {
  const { api } = useTenant();
  const detectionsQ = useQuery({
    queryKey: ["classifier-events", api.base, classifier.id],
    queryFn: () => api.listClassifierEvents(classifier.id, DETECTION_LIMIT),
  });
  const detections = detectionsQ.data ?? [];

  return (
    <Section
      title="Recent detections"
      subtitle={
        detections.length === 0
          ? undefined
          : `${detections.length}${detections.length === DETECTION_LIMIT ? " most recent" : ""} · select one to open its session`
      }
    >
      {detectionsQ.isLoading && <LoadingRow />}
      {detectionsQ.isError && <ErrorNote error={detectionsQ.error} />}
      {detectionsQ.isSuccess && detections.length === 0 && (
        <p className="text-muted m-0 text-small">
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
    </Section>
  );
}

/** Evidence is small and bounded by construction (never the trace payload), so flatten the top level. */
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
 * A context-grain detection has no trace_id and stays inert. No per-detection "Run analysis": a
 * detection is a Layer-1 filter, not evidence.
 */
export function DetectionRow({ event }: { event: ClassifierEvent }) {
  const summary = evidenceSummary(event.evidence_json);
  const severe = event.severity === "warn" || event.severity === "critical";

  const body = (
    <>
      <div className="flex items-baseline gap-2.5">
        <span className={cn("shrink-0 font-mono text-label uppercase", severe ? "text-warning" : "text-muted")}>
          {event.severity ?? "–"}
        </span>
        <span className="min-w-0 flex-1 truncate font-mono text-fg text-small">
          {event.trace_id ?? `${event.subject_kind} ${event.subject_id}`}
        </span>
        {event.confidence === "low" && <span className="shrink-0 text-muted text-label">low confidence</span>}
        <span className="shrink-0 text-muted text-label">{ago(event.occurred_at ?? event.detected_at)}</span>
      </div>
      {summary && <div className="text-muted mt-1 text-small">{summary}</div>}
    </>
  );

  if (!event.trace_id) {
    return (
      <div className="flex items-start bg-surface gap-3 py-2.75 px-3.5">
        <div className="min-w-0 flex-1">{body}</div>
      </div>
    );
  }
  const to = event.session_id
    ? `../sessions/${encodeURIComponent(event.session_id)}${
        event.subject_kind === "span" ? `?span=${encodeURIComponent(event.subject_id)}` : ""
      }`
    : `../traces/${encodeURIComponent(event.trace_id)}`;
  return (
    <div className="flex items-start bg-surface gap-3 py-2.75 px-3.5">
      <Link
        to={to}
        className="min-w-0 flex-1 hover:opacity-80 transition-opacity"
        style={{ transitionDuration: "var(--duration-micro)" }}
      >
        {body}
      </Link>
    </div>
  );
}
