// SPDX-License-Identifier: Apache-2.0
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
};

/** behavior_change is the "real degradation" verdict (error tone); the structural causes are
 *  warnings — the agent didn't get worse, something around it moved. */
export const RCA_VERDICT_TONE: Record<string, BadgeTone> = {
  definition_change: "warning",
  model_change: "warning",
  traffic_shift: "warning",
  behavior_change: "error",
  inconclusive: "neutral",
};

/** True while the analysis is still queued or running — the report has no findings to show yet. */
export function rcaRunning(status: string): boolean {
  return status === "pending" || status === "claimed";
}
