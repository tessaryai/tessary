// SPDX-License-Identifier: Apache-2.0
import type { RcaCause } from "../api/types";
import type { BadgeTone, Status } from "../ui";

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

export const RCA_VERDICT_TONE: Record<string, BadgeTone> = {
  definition_change: "warning",
  model_change: "warning",
  traffic_shift: "warning",
  behavior_change: "error",
  inconclusive: "neutral",
  causes_identified: "error",
  no_cause_found: "neutral",
};

export function rcaRunning(status: string): boolean {
  return status === "pending" || status === "claimed";
}

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
  | "classifier"
  | "other";

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
  classifier: "Why it changed",
  other: "Why it changed",
};

export function isCurrentCause(cause: Pick<RcaCause, "change">): boolean {
  return cause.change != null;
}

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
    const standing = cause.change === "standing";
    return {
      change: standing ? "What the agent does" : "What changed",
      how: kind === "other" && standing ? "Why it happens" : HOW[kind],
      next: "What to do",
    };
  }
  const proven = cause.confidence === "high";
  const agent = kind === "frustration" || kind === "groundedness" || kind === "classifier";
  return {
    change: agent ? "What the agent did" : "What changed",
    how: proven ? HOW[kind] : "Why it might be",
    next: !proven ? "To confirm" : agent ? "What to do" : "What it means",
  };
}

export function shiftKind(measure: string, up: boolean): CauseKind | null {
  if (measure === "cost") return up ? "costlier" : "cheaper";
  if (measure === "duration" || measure === "tool_duration") return up ? "slower" : "faster";
  return null;
}
