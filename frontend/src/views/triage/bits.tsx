// SPDX-License-Identifier: Apache-2.0
/*
 * Small shared pieces for the Triage/Case surface.
 *
 * Everything here derives its display string from a real value. The redesign's
 * first pass took pre-formatted strings ("100%", "14:52", polyline points) off
 * the wire, which only a fixture could ever produce — formatting lives on the
 * client, values live on the server.
 */
import type { Case } from "../../api/types";
import { RCA_VERDICT_LABEL } from "../rcaLabels";

// The unified § citation renderer lives in ../components/citation; re-exported
// here so existing triage imports keep working.
export { CitedText } from "../components/citation";

const MONTHS = [
  "January", "February", "March", "April", "May", "June",
  "July", "August", "September", "October", "November", "December",
];

/**
 * Age from an ISO instant: relative under an hour ("2m ago"), an absolute
 * "July 29 14:30" beyond that — a bare "2h ago"/"3d ago" stops being useful once
 * you're deciding whether something is stale. Hand-formatted (not
 * `toLocaleString`) so the shape stays exact instead of picking up a
 * locale-dependent comma.
 */
export function timeAgo(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "—";
  const mins = Math.max(1, Math.round((Date.now() - d.getTime()) / 60_000));
  if (mins < 60) return `${mins}m ago`;
  const hh = String(d.getHours()).padStart(2, "0");
  const mm = String(d.getMinutes()).padStart(2, "0");
  return `${MONTHS[d.getMonth()]} ${d.getDate()} ${hh}:${mm}`;
}

/** IDs truncate the tail, never the head (constitution rule 5). */
export function truncateId(id: string, keep = 8): string {
  return id.length <= keep ? id : `${id.slice(0, keep)}…`;
}

/**
 * A 0–1 detector value as a percentage. Every grader lane normalizes onto 0–1
 * server-side, so one formatter reads for both pass rates and rubric scores.
 */
export function unitPercent(value: number | null | undefined): string {
  return value == null ? "—" : `${Math.round(value * 100)}%`;
}

/**
 * The magnitude pair a case row shows ("100% → 33%"), or null when the detector
 * has no before/after to show. Behaviour-drift and classifier cases count
 * occurrences rather than moving a rate, so they render their basis instead of a
 * pair that would misread as a percentage.
 */
export function magnitudePair(c: Case): string | null {
  if (c.detector !== "grader_degradation") return null;
  if (c.baseline_value == null || c.current_value == null) return null;
  return `${unitPercent(c.baseline_value)} → ${unitPercent(c.current_value)}`;
}

/** Human label for the detector tag on a case's meta line. */
export function detectorLabel(detector: string): string {
  switch (detector) {
    case "grader_degradation":
      return "Grader degradation";
    case "behavior_drift":
      return "Behavior drift";
    case "classifier":
      return "Classifier";
    case "metric_drift":
      return "Metric drift";
    case "tool_error":
      return "Tool errors";
    case "sop_conformance":
      return "SOP conformance";
    default:
      return detector;
  }
}

/**
 * The call site to SHOW, or null.
 *
 * `__unattributed__` is not a call site. It is the sentinel for turns nothing
 * could be attributed to, and a tool-error case carries it by design: a tool's
 * failure rate belongs to the tool, across every entry point it is reached
 * through. Right data, wrong word to put in front of a partner — so the chip is
 * dropped rather than translated, because there is no call site to name.
 */
export function displayCallSite(callSiteId: string | null | undefined): string | null {
  if (!callSiteId || callSiteId === "__unattributed__") return null;
  return callSiteId;
}

/**
 * The shared bordered-list chassis (triage rows, driving verdicts): a 1px row
 * gap over border-color background draws the dividers.
 */
export function ListChassis({ children }: { children: React.ReactNode }) {
  return (
    <div
      className="flex flex-col overflow-hidden gap-0.25"
      style={{ background: "var(--color-border)", border: "1px solid var(--color-border)", borderRadius: "var(--radius-card)" }}
    >
      {children}
    </div>
  );
}

/** The `·` meta separator, always in --color-subtle. */
export function Dot() {
  return (
    <span aria-hidden="true" className="text-subtle">
      ·
    </span>
  );
}

/**
 * A case row's ONE colored element (constitution rule 2), and it reads as how far along the case is:
 * red needs someone, amber has been explained and needs a decision, green is closed.
 *
 * <p>Muted is GREY, not amber. It used to be amber, which is the colour this palette uses for "look
 * at this" — the exact opposite of what muting a case means. A muted case is one somebody decided
 * to stop being told about, so it recedes.
 *
 * <p>`analysed` is a second axis rather than a fourth state: a case is open whether or not an RCA has
 * run, and the dot says which of those two an open case is. There is no claimed state — nothing in
 * this product is assigned.
 */
export function StateDot({ state, analysed }: { state: string; analysed?: boolean }) {
  if (state === "muted") {
    return <Dotted colour="var(--color-subtle)" label="Muted" />;
  }
  if (state === "resolved") {
    return <Dotted colour="var(--color-success)" label="Resolved" />;
  }
  return analysed ? (
    <Dotted colour="var(--color-warning)" label="Open, analyzed" />
  ) : (
    <Dotted colour="var(--color-error)" label="Open" />
  );
}

function Dotted({ colour, label }: { colour: string; label: string }) {
  return (
    <span
      role="img"
      aria-label={label}
      title={label}
      className="inline-block shrink-0 rounded-pill"
      style={{ width: 8, height: 8, background: colour }}
    />
  );
}

/**
 * What a finished RCA concluded, as one line — or null where none has finished.
 *
 * <p>The verdict gates the WORDING, not whether there is any. An `inconclusive` run still reaches a
 * leading hypothesis, and the two wrong answers are at opposite extremes: printing that hypothesis
 * after "caused by" asserts an attribution the analysis explicitly declined to make, and printing
 * "No cause located" throws away the most useful sentence on the page to say almost nothing. So an
 * inconclusive run is hedged rather than suppressed — the reader gets the explanation and its
 * epistemic status in the same breath.
 *
 * <p>Only a run that reached NO hypothesis at all says so plainly, because then there genuinely is
 * nothing to hedge. A run with a verdict but no hypothesis falls back to the verdict's own label,
 * which is a category ("Traffic mix shifted") and reads as one.
 */
export function causeLine(c: Pick<Case, "cause" | "rca_verdict">): { hedged: boolean; text: string } | null {
  if (c.rca_verdict == null) return null;
  if (c.cause == null) {
    const label = RCA_VERDICT_LABEL[c.rca_verdict];
    return label && c.rca_verdict !== "inconclusive"
      ? { hedged: false, text: label }
      : { hedged: false, text: "No cause located" };
  }
  return { hedged: c.rca_verdict === "inconclusive", text: c.cause };
}
