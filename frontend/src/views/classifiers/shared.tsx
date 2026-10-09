// SPDX-License-Identifier: Apache-2.0
/*
 * The pieces the Classifiers surface shares: Triage's findings list, the charts page, a classifier's configure page
 * and a finding's own page. The verbs, the labels and the chain sentence are the things genuinely common to them.
 */
import type { BehaviorFinding } from "../../api/types";
import { cn } from "../../ui";

export const CONTAINER: React.CSSProperties = { padding: "36px 40px 56px" };

/**
 * `ClassifierView.readiness` while a classifier that calls a provider is paused: the short label beside the switch
 * and the sentence in Status. A paused sweep sends nothing until the provider works again.
 */
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
  platform_unavailable: {
    label: "Provider unavailable",
    explained:
      "The provider this classifier runs on is not accepting requests, so no messages are being scored. Nothing needs to change on your side. It retries on its own.",
  },
};

/** One label/value fact in a fact grid; the `<dl>` wrapper supplies the columns. */
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

/**
 * A verb on a finding. `filled` is the move the row is asking for; `outline` is everything else.
 *
 * <p>Which verb gets the fill is not decoration: absorbing re-pins the reference a whole
 * population is compared against and closes the finding, the most consequential thing on this
 * surface, so it is deliberately never the one styled as the default.
 */
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

/**
 * The triage verb a finding's own page offers before there is a ruling.
 *
 * <p>A run that gave up says so beside the button, in the label and tone the findings list uses, and
 * the button offers the run again rather than staying on "Triaging…" for a job nothing is running.
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
  const inFlight = finding.triageStatus === "in_flight";
  const failed = finding.triageStatus === "failed";
  const button = (
    <VerbButton kind="filled" disabled={busy || inFlight} onClick={onAnalyze}>
      {inFlight ? "Triaging…" : failed ? "Run triage again" : "Run triage"}
    </VerbButton>
  );
  if (!failed) return button;
  return (
    <span className="flex flex-wrap items-center gap-2.5">
      <span className="text-error text-small">{triageState(finding).label}</span>
      {button}
    </span>
  );
}

/**
 * The human verdicts on one finding. Both are `outline` for the reason VerbButton's own note
 * gives.
 *
 * <p>A ruling freezes the finding by construction (decision 1): the first one to land, triage's or
 * a person's, leaves it outside `ux_finding_live` and every verb on it after that 409s. So a
 * caller renders this only while the finding is still unruled (`!triaged`) — there is no override
 * of a standing ruling any more, machine or human; a cause that disagrees with the traffic again
 * simply opens a fresh finding, which is triaged like any other.
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

/**
 * Where one finding stands with triage, in the words the queue and the finding's own page both use.
 *
 * <p>`positive` is the only state that puts a finding in front of a person, so it is the only one
 * that gets the emphatic tone; the two closures read the same weight as each other because they
 * are the same act (triage ended, nobody was paged) and differ only in why. `pending` is not a
 * failure and not a waiting room: it is a finding whose one run has not been scheduled yet.
 *
 * <p>A `done` status with no verdict cannot happen: the ruling and its action are written in one
 * statement, and a run that did not happen leaves the whole set null. But the fallback is here
 * rather than a cast, because the alternative to a word is a blank cell.
 */
export type TriageState = { label: string; tone: "positive" | "closed" | "waiting" | "failed" };

export function triageState(finding: BehaviorFinding): TriageState {
  if (finding.triageStatus === "pending") return { label: "Pending", tone: "waiting" };
  if (finding.triageStatus === "in_flight") return { label: "Triaging", tone: "waiting" };
  // Without this branch, a run that gave up is indistinguishable from one in flight and sits on
  // "Triaging" forever, the row looking busy while nothing is happening to it.
  if (finding.triageStatus === "failed") return { label: "Triage failed", tone: "failed" };
  // `humanVerdictAt` set means a PERSON's ruling won the race to land first, not triage's — say so
  // in the verb they actually pressed rather than in triage's own words, which would claim a run
  // that never happened.
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

/** Whether triage ended this finding rather than handing it on. The queue's one split. */
export function isClosedByTriage(finding: BehaviorFinding): boolean {
  return finding.triageAction === "closed";
}

/**
 * The chain on one line: how much traffic, and what triage made of it.
 *
 * <p>No recurrence count any more: a ruling freezes the finding, so a cause that fires again after
 * one opens a fresh finding rather than reopening this one — there is nothing left to count here.
 */
export function chainWords(finding: BehaviorFinding): string {
  return [
    `seen ${finding.traceCount}×`,
    finding.triageStatus === "done"
      ? `${finding.humanVerdictAt != null ? "a person" : "triage"} ruled ${finding.triageVerdict ?? "unknown"}`
      : finding.triageStatus === "in_flight"
        ? "triage running"
        : finding.triageStatus === "failed"
          ? "triage failed"
          : "not triaged yet",
  ]
    .filter(Boolean)
    .join(" · ");
}

/**
 * A detector key as a person would say it: `cost_drift` → `Cost drift`.
 *
 * <p>The key itself comes from the server on `finding.detector` and is never re-derived here. Three
 * classifiers share one findings table with no column saying which wrote a row, so the mapping is
 * reconstructed from the cause kind and the cause key, and its own javadoc says the two copies that
 * already exist must not drift. A third copy in TypeScript is exactly the drift it warns about.
 */
export function detectorLabel(key: string): string {
  return key
    .split("_")
    .map((word, i) => (i === 0 ? word.charAt(0).toUpperCase() + word.slice(1) : word))
    .join(" ");
}

/** `2026-08-24T18:00:00Z` → `24 Aug 18:00`. A window is a story's spine, so it reads as a clock. */
export function stamp(iso: string): string {
  const d = new Date(iso);
  return `${d.toLocaleDateString(undefined, { day: "numeric", month: "short" })} ${d.toLocaleTimeString(
    undefined,
    { hour: "2-digit", minute: "2-digit", hour12: false },
  )}`;
}

/** Coarse age: an exact second never changes what you do next. */
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

/** An unambiguous date ("July 1, 2026"), never the locale's all-numeric form. */
function absoluteDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { month: "long", day: "numeric", year: "numeric" });
}
