// SPDX-License-Identifier: Apache-2.0
/*
 * The pieces the Classifiers surface's three pages share: the findings queue, the detectors page and
 * a finding's own page.
 *
 * They were one file until the queue stopped being a list of everything: a page that shows findings and
 * a page that configures detectors answer different questions and are visited on different days, and the
 * verbs, the labels and the chain sentence are the only things genuinely common to both.
 */
import type { BehaviorFinding, Classifier } from "../../api/types";
import { cn } from "../../ui";

export const CONTAINER: React.CSSProperties = { padding: "36px 40px 56px" };

/** The behaviour-drift detector is the one classifier with a fitted baseline. */
export const BEHAVIOR_DETECTOR = "behavior_drift";

/**
 * SOP conformance opens findings too: one row per authored rule, served through the same findings
 * endpoint with `causeKind: "sop_conformance"` and the rule slug as its causeKey.
 */
export const SOP_CONFORMANCE_DETECTOR = "sop_conformance";

/**
 * One section of a detail rail. Sections are separated by a hairline rather than by bare
 * whitespace: five stacked blocks with only margins between them read as one long column
 * of text, which is what this rail was before.
 *
 * <p>Here rather than in `DetectorsPage.tsx`, where it was file-private until #846: the SOP
 * rulebook block that renders one of these moved to the paid overlay, which reaches open code
 * across the boundary and can only import what is exported. Same reason for {@link Fact}.
 */
export function RailBlock({ label, meta, children }: { label: string; meta?: string; children: React.ReactNode }) {
  return (
    <section className="border-t border-border pt-4.5 mt-4.5">
      <div className="flex items-baseline gap-2.5 mb-2.5">
        <h3 className="font-mono text-label uppercase text-muted">{label}</h3>
        {meta && (
          <span className="min-w-0 truncate text-subtle text-label">
            {meta}
          </span>
        )}
      </div>
      {children}
    </section>
  );
}

/** One label/value fact in a rail's fact grid; the `<dl>` wrapper supplies the columns. */
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

export function SectionLabel({ label, meta }: { label: string; meta?: string }) {
  return (
    <div className="flex items-baseline gap-3 mb-2.5 mt-7.5">
      <h2 className="font-mono text-label uppercase text-muted">{label}</h2>
      {meta && <span className="text-small text-subtle">{meta}</span>}
    </div>
  );
}

/**
 * A verb on a finding. `filled` is the move the row is asking for; `outline` is everything else.
 *
 * <p>Which verb gets the fill is not decoration. `Absorb as legitimate` used to carry it on every row:
 * absorbing re-pins the reference a whole population is compared against and closes the finding, which
 * is the most consequential thing on this surface and was also the one styled as the default.
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
 * The human verdicts on one finding — the override, available on every finding including one triage
 * already closed. Both are `outline` for the reason VerbButton's own note gives.
 *
 * <p>They stay reachable after a closure because triage closing a finding is a machine ruling on a
 * claim, not a decision about what matters, and a person who disagrees needs somewhere to say so. A
 * human verdict outranks the machine's wherever the two are read together.
 *
 * <p>Conformance gets ONE verb. The two-verb split exists to correct a fitted reference (absorbing a
 * gram or re-pinning a baseline teaches the detector that what it saw is normal) and an SOP rule has no
 * such reference to correct: the authored SOP IS the reference, and changing it is a repo edit rather
 * than a button here. So resolving simply closes the row, and a deviation that persists opens a fresh
 * one, which is why closing is never suppression.
 */
export function ResolveVerbs({
  causeKind,
  busy,
  deviationLabel = "Confirm and open a case",
  onResolve,
}: {
  causeKind: string;
  busy: boolean;
  deviationLabel?: string;
  onResolve: (action: "expected" | "not_expected") => void;
}) {
  if (causeKind === "sop_conformance") {
    return (
      <VerbButton kind="outline" disabled={busy} onClick={() => onResolve("expected")}>
        Resolve
      </VerbButton>
    );
  }
  return (
    <>
      <VerbButton kind="outline" disabled={busy} onClick={() => onResolve("expected")}>
        Absorb as legitimate
      </VerbButton>
      <VerbButton kind="outline" disabled={busy} onClick={() => onResolve("not_expected")}>
        {deviationLabel}
      </VerbButton>
    </>
  );
}

/**
 * Where one finding stands with triage, in the words the queue and the finding's own page both use.
 *
 * <p>`positive` is the only state that puts a finding in front of a person, so it is the only one that
 * gets the emphatic tone; the two closures read the same weight as each other because they are the
 * same act — triage ended, nobody was paged — and differ only in why. `pending` is not a failure and
 * not a waiting room: it is a finding whose one run has not been scheduled yet.
 *
 * <p>A `done` status with no verdict cannot happen — the ruling and its action are written in one
 * statement, and a run that did not happen leaves the whole set null — but the fallback is here
 * rather than a cast, because the alternative to a word is a blank cell.
 */
export type TriageState = { label: string; tone: "positive" | "closed" | "waiting" | "failed" };

export function triageState(finding: BehaviorFinding): TriageState {
  if (finding.triageStatus === "pending") return { label: "Pending", tone: "waiting" };
  if (finding.triageStatus === "in_flight") return { label: "Triaging", tone: "waiting" };
  // Before this branch existed, a run that had given up was indistinguishable from one in flight and
  // sat on "Triaging" forever — the row looked busy while nothing was happening to it.
  if (finding.triageStatus === "failed") return { label: "Triage failed", tone: "failed" };
  if (finding.triageVerdict === "positive") return { label: "Positive", tone: "positive" };
  if (finding.triageVerdict === "negative") return { label: "Closed · negative", tone: "closed" };
  if (finding.triageVerdict === "unclear") return { label: "Closed · unclear", tone: "closed" };
  return { label: "Closed", tone: "closed" };
}

/** Whether triage ended this finding rather than handing it on. The queue's one split. */
export function isClosedByTriage(finding: BehaviorFinding): boolean {
  return finding.triageAction === "closed";
}

/**
 * Whether this row is the "this has always been broken" claim rather than the "this got worse" one.
 * Only an SOP-conformance finding is ever either.
 */
export function isBaselineFinding(finding: BehaviorFinding): boolean {
  return finding.conformanceKind === "baseline";
}

/**
 * The chain on one line: how much traffic, what triage made of it, and what has happened since.
 *
 * <p>A baseline finding's traffic is not firings. Its count is the population its violations were
 * counted over, once, at fit time — so "seen 257×" would report a fitted fact as a recurring event.
 *
 * <p>The recurrence count is what a closed finding is read by: triage closing a claim it could not
 * settle is a bet that the cause has stopped, and the counter is the bet being called. At the
 * threshold the finding re-opens and goes back through triage once.
 */
export function chainWords(finding: BehaviorFinding): string {
  return [
    isBaselineFinding(finding)
      ? `${finding.traceCount} applicable turns when the rule was fitted`
      : `seen ${finding.traceCount}×`,
    finding.triageStatus === "done"
      ? `triage ruled ${finding.triageVerdict ?? "unclear"}`
      : finding.triageStatus === "in_flight"
        ? "triage running"
        : finding.triageStatus === "failed"
          ? "triage failed"
          : "not triaged yet",
    finding.recurrencesSinceVerdict > 0 ? `${finding.recurrencesSinceVerdict} since that ruling` : null,
  ]
    .filter(Boolean)
    .join(" · ");
}

/** Key fragments that are acronyms, so the generic casing below does not render `sop` as `Sop`. */
const ACRONYMS = new Set(["sop"]);

/**
 * A detector key as a person would say it: `cost_drift` → `Cost drift`, `sop_conformance` →
 * `SOP conformance`.
 *
 * <p>The key itself comes from the server on `finding.detector` and is never re-derived here. Three
 * classifiers share one findings table with no column saying which wrote a row, so the mapping is
 * reconstructed from the cause kind and the cause key, and its own javadoc says the two copies that
 * already exist must not drift. A third copy in TypeScript is exactly the drift it warns about.
 *
 * <p>{@link ACRONYMS} is not that third copy: it spells words, not detectors. A key it says nothing
 * about still gets a label, which is the property that keeps this generic: adding a detector never
 * requires touching this file, and only a detector whose name contains an initialism ever does.
 */
export function detectorLabel(key: string): string {
  return key
    .split("_")
    .map((word, i) => {
      if (ACRONYMS.has(word)) return word.toUpperCase();
      return i === 0 ? word.charAt(0).toUpperCase() + word.slice(1) : word;
    })
    .join(" ");
}

/**
 * A detector description's opening sentence, which is the one that says what it watches. The rest (how
 * the bar is set, what it deliberately does not label) belongs on the detectors page, where the full
 * text is shown untouched. A summary row used to render the whole thing on one clamped line, so every
 * detector ended mid-word in an ellipsis.
 */
export function firstSentence(text: string): string {
  const trimmed = text.trim();
  const end = /[.!?](\s|$)/.exec(trimmed);
  return end ? trimmed.slice(0, end.index + 1) : trimmed;
}

/** Coarse age: an exact second never changes what you do next. */
export function ago(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime();
  if (!Number.isFinite(ms) || ms < 0) return absoluteDate(iso);
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

/** Detectors that are switched on, in catalog order: the summary card's whole content. */
export function enabledDetectors(classifiers: Classifier[]): Classifier[] {
  return classifiers.filter((c) => c.enabled);
}
