// SPDX-License-Identifier: Apache-2.0
import type { RcaCause } from "../api/types";
import type { BadgeTone, Status } from "../ui";

/**
 * Wire → human vocabulary for RCA reports, shared by the Overview (which lists the reports a
 * project has run) and the report page itself, so a verdict never reads one way on the board and
 * another on the drill-in.
 */

/** Queue status of the analysis job, as the design system's status vocabulary. */
export const RCA_JOB_STATUS: Record<string, Status> = {
  pending: "pending",
  claimed: "running",
  done: "passed",
  failed: "failed",
};

export const RCA_VERDICT_LABEL: Record<string, string> = {
  definition_change: "Grader definition changed",
  model_change: "Serving model changed",
  traffic_shift: "Traffic mix shifted",
  behavior_change: "Agent behavior changed",
  inconclusive: "Inconclusive",
  causes_identified: "Causes identified",
  no_cause_found: "No cause found",
};

/** behavior_change is the "real degradation" verdict (error tone); the structural causes are
 *  warnings — the agent didn't get worse, something around it moved. A frustration report that
 *  identified causes found the agent at fault, so it takes the error tone too. */
export const RCA_VERDICT_TONE: Record<string, BadgeTone> = {
  definition_change: "warning",
  model_change: "warning",
  traffic_shift: "warning",
  behavior_change: "error",
  inconclusive: "neutral",
  causes_identified: "error",
  no_cause_found: "neutral",
};

/** True while the analysis is still queued or running — the report has no findings to show yet. */
export function rcaRunning(status: string): boolean {
  return status === "pending" || status === "claimed";
}

/** What kind of movement a cause explains, which decides how its rows are labelled. */
export type CauseKind =
  | "slower"
  | "faster"
  | "costlier"
  | "cheaper"
  | "tool_error"
  | "malformed"
  | "secret_leak"
  | "frustration"
  | "groundedness"
  | "other";

/** The three row labels of a cause card. The agent never writes these: they follow from the cause and the case type. */
export type CauseLabels = { change: string; how: string; next: string };

const HOW: Record<CauseKind, string> = {
  slower: "Why it's slower",
  faster: "Why it's faster",
  costlier: "Why it costs more",
  cheaper: "Why it costs less",
  tool_error: "Why it fails more",
  malformed: "Why outputs break the schema",
  secret_leak: "How the key got into the output",
  frustration: "Why users got frustrated",
  groundedness: "Why answers went unsupported",
  other: "Why it changed",
};

/** A cause written by the current analysis carries `change`. A cause on an older report has none, and is still
 *  shown as it was written. */
export function isCurrentCause(cause: Pick<RcaCause, "change">): boolean {
  return cause.change != null;
}

/** Whether a cause is shown as a cause. Every current cause is; on an older report only a high one is, and the
 *  rest are leads. */
export function shownAsCause(cause: Pick<RcaCause, "change" | "confidence">): boolean {
  return isCurrentCause(cause) || cause.confidence === "high";
}

/**
 * A current cause takes its first label from whether something changed or the agent always does it, and reads
 * the same at high and medium. An older cause takes it from the case type, and a lead says why it might be and
 * how to confirm it.
 */
export function causeLabels(kind: CauseKind, cause: Pick<RcaCause, "change" | "confidence">): CauseLabels {
  if (isCurrentCause(cause)) {
    return {
      change: cause.change === "standing" ? "What the agent does" : "What changed",
      how: HOW[kind],
      next: "What to do",
    };
  }
  const proven = cause.confidence === "high";
  const agent = kind === "frustration" || kind === "groundedness";
  return {
    change: agent ? "What the agent did" : "What changed",
    how: proven ? HOW[kind] : "Why it might be",
    next: !proven ? "To confirm" : agent ? "What to do" : "What it means",
  };
}

/** The kind of a cost or duration movement, by which way it went. Null for any other measure. */
export function shiftKind(measure: string, up: boolean): CauseKind | null {
  if (measure === "cost") return up ? "costlier" : "cheaper";
  if (measure === "duration" || measure === "tool_duration") return up ? "slower" : "faster";
  return null;
}
