// SPDX-License-Identifier: Apache-2.0
/*
 * Steps of a triage run as the steps endpoints send them, worded as the approved design shows a run that is
 * on its seventh step. `stepsPage` builds a page from them; `numberedSteps` builds long runs.
 */
import type { RunStep, StepsPage } from "../views/components/RunBar";

export const TRIAGE_STEPS: RunStep[] = [
  { seq: 1, words: "Read the finding and its numbers", doing: "Reading the finding and its numbers", started_ms: 4_000, ended_ms: 6_000, ok: true },
  { seq: 2, words: "Read flagged answers 1 to 50 of 58", doing: "Reading flagged answers 1 to 50", started_ms: 19_000, ended_ms: 30_000, ok: true },
  { seq: 3, words: "Opened trace 4f1c9a07e2b84d0f", doing: "Opening trace 4f1c9a07e2b84d0f", started_ms: 41_000, ended_ms: 50_000, ok: true },
  { seq: 4, words: "Read the reference window", doing: "Reading the reference window", started_ms: 65_000, ended_ms: 80_000, ok: true },
  { seq: 5, words: "Could not run checks/refund_terms.py", doing: "Running checks/refund_terms.py", started_ms: 92_000, ended_ms: 140_000, ok: false },
  { seq: 6, words: "Wrote checks/unsupported_terms.py", doing: "Writing checks/unsupported_terms.py", started_ms: 160_000, ended_ms: 170_000, ok: true },
  { seq: 7, words: "Ran checks/unsupported_terms.py", doing: "Running checks/unsupported_terms.py", started_ms: 175_000, ended_ms: null, ok: null },
];

export function stepsPage(steps: RunStep[], { runId = "run-1", stepCount = steps.length } = {}): StepsPage {
  return { run_id: runId, step_count: stepCount, steps };
}

/** Steps `from` to `to`, each ended and ok, `Read file N` with a start one second apart. */
export function numberedSteps(from: number, to: number): RunStep[] {
  return Array.from({ length: to - from + 1 }, (_, i) => {
    const seq = from + i;
    return { seq, words: `Read file ${seq}`, doing: `Reading file ${seq}`, started_ms: seq * 1_000, ended_ms: seq * 1_000 + 500, ok: true };
  });
}
