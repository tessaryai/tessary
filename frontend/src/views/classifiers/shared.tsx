// SPDX-License-Identifier: Apache-2.0
import { Link } from "react-router-dom";
import type { BehaviorFinding } from "../../api/types";
import { cn } from "../../ui";

export const CONTAINER: React.CSSProperties = { padding: "36px 40px 56px" };

export const PROVIDER_PAUSES: Record<string, { label: string; explained: string }> = {
  provider_rejected: {
    label: "Provider rejected the key",
    explained:
      "The provider rejected the stored key, so no messages are being scored. Fix the key under Settings, Providers, then retry.",
  },
  request_refused: {
    label: "Provider refused the request",
    explained:
      "The provider accepted the stored key but refused the request itself, so no messages are being scored. Usually the model this classifier is set to is not one the provider serves: check it under Settings, Models, then retry.",
  },
  no_provider: {
    label: "No provider key",
    explained:
      "There is no key for the provider this classifier runs on, so no messages are being scored. Add one under Settings, Providers, then retry.",
  },
  no_credit: {
    label: "No credit left",
    explained:
      "This organization has used all of its credit on the provider this classifier runs on, so no messages are being scored. Top up that provider, or add another key under Settings, Providers, then retry.",
  },
};

export function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <>
      <dt className="text-subtle text-small">
        {label}
      </dt>
      <dd className="min-w-0 text-muted m-0 text-small">
        {children}
      </dd>
    </>
  );
}

/** Absorbing re-pins a population's reference and closes the finding, so it is never the filled default. */
export function VerbButton({
  kind,
  onClick,
  disabled,
  children,
}: {
  kind: "filled" | "outline";
  onClick: () => void;
  disabled?: boolean;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={cn(
        "rounded-control border border-border-strong px-3 text-small whitespace-nowrap transition-colors disabled:opacity-50",
        kind === "filled" ? "bg-raised text-fg hover:bg-overlay" : "bg-transparent text-muted hover:text-fg",
      )}
      style={{ height: 29, transitionDuration: "var(--duration-micro)" }}
    >
      {children}
    </button>
  );
}

/** How often a page reads a finding again while its run waits or runs. */
export const TRIAGE_POLL_MS = 4000;

const WAITING_LABEL: Partial<Record<BehaviorFinding["triageStatus"], string>> = {
  queued: "Queued…",
  in_flight: "Triaging…",
  retrying: "Retrying…",
};

/** A run that can still end on its own, so the page keeps reading it. A held run waits on a person. */
export function isTriageWaiting(finding: BehaviorFinding | undefined): boolean {
  return finding != null && WAITING_LABEL[finding.triageStatus] != null;
}

/** For a list's `refetchInterval`: keep reading while any run in it can still end on its own. */
export function triagePoll(findings: BehaviorFinding[] | undefined): number | false {
  return findings?.some(isTriageWaiting) ? TRIAGE_POLL_MS : false;
}

/** The server sends the code of the refusal a held run waits on; this words what to fix. */
const HELD_BY: Record<string, string> = {
  "CLASSIFIER.TRIAGE_PROVIDER_REJECTED":
    "The provider rejected the stored key. Fix the key under Settings, Providers, then run triage again.",
  "CLASSIFIER.TRIAGE_REQUEST_REFUSED":
    "The provider refused the request. Usually the triage model is not one the provider serves: check it under Settings, Models, then run triage again.",
  "MODEL_CONFIG.MISSING_CREDENTIALS":
    "There is no key for the provider triage runs on. Add one under Settings, Providers, then run triage again.",
  "MODEL_CONFIG.AGENTIC_IAM_ROLE_UNSUPPORTED":
    "Triage cannot use a key set to an IAM role. Set it to an access key under Settings, Providers, then run triage again.",
  "CLASSIFIER.TRIAGE_LAUNCHER_MISCONFIGURED":
    "The sandbox launcher refused the run. Its key or its version does not match this deployment. Fix the launcher, then run triage again.",
};

/** One line on where a run that has not ruled stands, or null when there is nothing to add. */
export function triageProgressWords(finding: BehaviorFinding): string | null {
  const progress = finding.triageProgress;
  if (!progress) return null;
  const of = `${progress.attempts} of ${progress.maxAttempts}`;
  switch (finding.triageStatus) {
    case "queued":
      return "Waiting for the triage worker.";
    case "in_flight":
      if (!progress.startedAt) return null;
      return `Started ${ago(progress.startedAt)}${progress.attempts > 1 ? `, attempt ${of}` : ""}.`;
    case "retrying":
      return `Attempt ${of} ended with no ruling. Triage tries again shortly.`;
    case "blocked":
      return (progress.heldBy && HELD_BY[progress.heldBy])
        ?? "Triage is waiting for a setting to change. Fix it, then run triage again.";
    default:
      return finding.triageStatus === "failed" ? `Triage tried ${progress.attempts} times and gave up.` : null;
  }
}

/**
 * A run that gave up or is held says so and offers the run again rather than staying on "Triaging…"; a run that
 * waits or runs says where it stands.
 */
export function RunTriageButton({
  finding,
  busy,
  onAnalyze,
}: {
  finding: BehaviorFinding;
  busy: boolean;
  onAnalyze: () => void;
}) {
  const waiting = WAITING_LABEL[finding.triageStatus];
  const stopped = finding.triageStatus === "failed" || finding.triageStatus === "blocked";
  const words = triageProgressWords(finding);
  const button = (
    <VerbButton kind="filled" disabled={busy || waiting != null} onClick={onAnalyze}>
      {waiting ?? (stopped ? "Run triage again" : "Run triage")}
    </VerbButton>
  );
  if (!stopped && !words) return button;
  return (
    <span className="flex flex-wrap items-center gap-2.5">
      {stopped && <span className="text-error text-small">{triageState(finding).label}</span>}
      {button}
      {words && <span className="text-muted text-small">{words}</span>}
    </span>
  );
}

/**
 * Rendered only while the finding is unruled: the first ruling, triage's or a person's, freezes it and
 * every later verb 409s.
 */
export function ResolveVerbs({
  busy,
  onResolve,
}: {
  busy: boolean;
  onResolve: (action: "expected" | "not_expected") => void;
}) {
  return (
    <>
      <VerbButton kind="outline" disabled={busy} onClick={() => onResolve("expected")}>
        Absorb as legitimate
      </VerbButton>
      <VerbButton kind="outline" disabled={busy} onClick={() => onResolve("not_expected")}>
        Confirm and open a case
      </VerbButton>
    </>
  );
}

/** The props every finding header with verbs takes. */
export type FindingHeaderProps = {
  finding: BehaviorFinding;
  basePath: string;
  busy: boolean;
  onAnalyze: () => void;
  onResolve: (action: "expected" | "not_expected") => void;
};

/** The detector's name, and the call site when the finding has one. */
export function FindingKicker({ finding }: { finding: BehaviorFinding }) {
  return (
    <span className="flex flex-wrap items-center gap-2">
      <span className="text-muted">{finding.detector ? detectorLabel(finding.detector) : "Finding"}</span>
      {finding.callSiteId && <span className="text-muted">{finding.callSiteId}</span>}
    </span>
  );
}

export function TriageBadge({ finding }: { finding: BehaviorFinding }) {
  return (
    <span className="font-mono text-label uppercase text-muted rounded-control border border-border-strong bg-raised py-1.25 px-2.75">
      {triageState(finding).label}
    </span>
  );
}

/** The ruling once triage is done, else the button that runs it. */
export function TriageAction({ finding, busy, onAnalyze }: Omit<FindingHeaderProps, "basePath" | "onResolve">) {
  if (finding.triageStatus === "done") return <TriageBadge finding={finding} />;
  return <RunTriageButton finding={finding} busy={busy} onAnalyze={onAnalyze} />;
}

/** The ruling and, once the finding has one, a way to its case. */
export function RulingWithCase({ finding, basePath }: { finding: BehaviorFinding; basePath: string }) {
  return (
    <span className="flex items-center gap-2">
      <TriageBadge finding={finding} />
      {finding.caseId && (
        <Link
          to={`${basePath}/cases/${encodeURIComponent(finding.caseId)}`}
          className="inline-flex items-center h-7 px-2.5 rounded-control border border-border-strong bg-raised text-small text-fg hover:bg-hover transition-colors"
          style={{ transitionDuration: "var(--duration-micro)" }}
        >
          View case
        </Link>
      )}
    </span>
  );
}

/** The ruling and its case once triage is done, else the verbs that rule the finding. */
export function RulingActions({ finding, basePath, busy, onAnalyze, onResolve }: FindingHeaderProps) {
  if (finding.triageStatus === "done") return <RulingWithCase finding={finding} basePath={basePath} />;
  return (
    <span className="flex flex-wrap items-center gap-2">
      <ResolveVerbs busy={busy} onResolve={onResolve} />
      <RunTriageButton finding={finding} busy={busy} onAnalyze={onAnalyze} />
    </span>
  );
}

/**
 * `positive` is the only state that pages a person, so it alone is emphatic. A `done` status with no
 * verdict cannot happen; the fallback avoids a blank cell.
 */
export type TriageState = { label: string; tone: "positive" | "closed" | "waiting" | "failed" };

export function triageState(finding: BehaviorFinding): TriageState {
  if (finding.triageStatus === "pending") return { label: "Pending", tone: "waiting" };
  if (finding.triageStatus === "queued") return { label: "Queued", tone: "waiting" };
  if (finding.triageStatus === "in_flight") return { label: "Triaging", tone: "waiting" };
  if (finding.triageStatus === "retrying") return { label: "Retrying", tone: "waiting" };
  if (finding.triageStatus === "blocked") return { label: "Triage blocked", tone: "failed" };
  if (finding.triageStatus === "failed") return { label: "Triage failed", tone: "failed" };
  // `humanVerdictAt` means a person's ruling landed first, so name the verb they pressed.
  const byPerson = finding.humanVerdictAt != null;
  if (finding.triageVerdict === "positive") {
    return byPerson ? { label: "Real deviation", tone: "positive" } : { label: "Positive", tone: "positive" };
  }
  if (finding.triageVerdict === "negative") {
    return byPerson
      ? { label: "Legitimate, absorb", tone: "closed" }
      : { label: "Closed · negative", tone: "closed" };
  }
  return { label: "Closed", tone: "closed" };
}

export function isClosedByTriage(finding: BehaviorFinding): boolean {
  return finding.triageAction === "closed";
}

const CHAIN_WORDS: Partial<Record<BehaviorFinding["triageStatus"], string>> = {
  queued: "triage queued",
  in_flight: "triage running",
  retrying: "triage retrying",
  blocked: "triage blocked",
  failed: "triage failed",
};

export function chainWords(finding: BehaviorFinding): string {
  return [
    `seen ${finding.traceCount}×`,
    finding.triageStatus === "done"
      ? `${finding.humanVerdictAt != null ? "a person" : "triage"} ruled ${finding.triageVerdict ?? "unknown"}`
      : CHAIN_WORDS[finding.triageStatus] ?? "not triaged yet",
  ]
    .filter(Boolean)
    .join(" · ");
}

/** The server sends the key; this only words it. Never re-derive it from the cause. */
export function detectorLabel(key: string): string {
  return key
    .split("_")
    .map((word, i) => (i === 0 ? word.charAt(0).toUpperCase() + word.slice(1) : word))
    .join(" ");
}

export function stamp(iso: string): string {
  const d = new Date(iso);
  return `${d.toLocaleDateString(undefined, { day: "numeric", month: "short" })} ${d.toLocaleTimeString(
    undefined,
    { hour: "2-digit", minute: "2-digit", hour12: false },
  )}`;
}

export function ago(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime();
  if (ms < 0) return absoluteDate(iso);
  const mins = Math.floor(ms / 60_000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return days < 30 ? `${days}d ago` : absoluteDate(iso);
}

function absoluteDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { month: "long", day: "numeric", year: "numeric" });
}
