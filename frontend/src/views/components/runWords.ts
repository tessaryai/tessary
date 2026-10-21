// SPDX-License-Identifier: Apache-2.0

/** How often a page reads a run again while it is queued or running, and an open steps list reads new steps. */
export const RUN_POLL_MS = 4000;

export const QUEUED_LINE = "Queued. It starts when a worker is free.";

/** The live line before the agent's first step or words. */
export const STARTING = "Starting the agent…";

/** What a running run last did, with each time counted from the run's start. */
export type LiveRun = {
  doing: string | null;
  lastStep: string | null;
  lastStepMs: number | null;
  note: string | null;
  noteMs: number | null;
};

/**
 * The step in flight, else the newer of the newest step and the agent's own words. A note as old as the step comes
 * after it: the agent speaks once a call ends.
 */
export function liveLine(run: LiveRun): string {
  if (run.doing) return `${run.doing}…`;
  if (run.note && (run.lastStepMs == null || (run.noteMs ?? 0) >= run.lastStepMs)) return `${run.note}…`;
  return run.lastStep ?? STARTING;
}

export type RunLane = "triage" | "RCA";

const pad = (n: number) => String(n).padStart(2, "0");

function parts(ms: number) {
  const s = Math.floor(Math.max(0, ms) / 1000);
  return { h: Math.floor(s / 3600), m: Math.floor((s % 3600) / 60), s: s % 60 };
}

/** `0:06`, `2:40`, `1:02:03`: the time since a run was queued, or a step's start within its run. */
export function clock(ms: number): string {
  const { h, m, s } = parts(ms);
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`;
}

/** `14s`, `2m 14s`, `1h 05m`: how long a run has been going. */
export function elapsed(ms: number): string {
  const { h, m, s } = parts(ms);
  if (h > 0) return `${h}h ${pad(m)}m`;
  return m > 0 ? `${m}m ${pad(s)}s` : `${s}s`;
}

/** `40s`, `3m`, `2h`: a glance at how long, for a hover tip. */
export function brief(ms: number): string {
  const { h, m, s } = parts(ms);
  if (h > 0) return `${h}h`;
  return m > 0 ? `${m}m` : `${s}s`;
}

/** `Did 12 steps in 4m`: the fold under a ruling or an analysis. */
export function didSteps(n: number, ms: number): string {
  const { h, m, s } = parts(ms);
  const took = h > 0 ? `${h}h ${m}m` : m > 0 ? `${m}m` : `${s}s`;
  return `Did ${n} ${n === 1 ? "step" : "steps"} in ${took}`;
}

export function stepMeta(step: number, ms: number): string {
  return `step ${step} · ${elapsed(ms)}`;
}

/** The cause a stop code names, the same for triage (`CLASSIFIER.TRIAGE_X`) and RCA (`RCA.X`). */
function cause(code: string | null | undefined): string {
  return (code ?? "").replace(/^[A-Z_]+\./, "").replace(/^TRIAGE_/, "");
}

const TO_PROVIDERS = new Set(["PROVIDER_REJECTED", "MISSING_CREDENTIALS", "AGENTIC_IAM_ROLE_UNSUPPORTED"]);

/** The settings page that fixes a stopped run, when there is one the person can open. */
export function stopFix(
  code: string | null | undefined,
  basePath: string,
  canProviders: boolean,
): { label: string; to: string } | undefined {
  const c = cause(code);
  if (TO_PROVIDERS.has(c)) return canProviders ? { label: "Open Providers", to: `${basePath}/settings/providers` } : undefined;
  return c === "REQUEST_REFUSED" ? { label: "Open Models", to: `${basePath}/settings/models` } : undefined;
}

const REASON: Record<string, (lane: RunLane) => string> = {
  PROVIDER_REJECTED: (l) => `the provider rejected the stored key. Fix the key, then run ${l} again.`,
  REQUEST_REFUSED: (l) =>
    `the provider refused the request. Usually the model is not one the provider serves. Check the model, then run ${l} again.`,
  MISSING_CREDENTIALS: (l) => `there is no key for the provider ${l} runs on. Add one, then run ${l} again.`,
  AGENTIC_IAM_ROLE_UNSUPPORTED: (l) =>
    `${l} cannot use a key set to an IAM role. Set it to an access key, then run ${l} again.`,
  LAUNCHER_MISCONFIGURED: (l) =>
    `the sandbox launcher refused the run. Its key or its version does not match this deployment. Fix the launcher, then run ${l} again.`,
  LAUNCHER_UNREACHABLE: (l) => `the sandbox launcher did not answer. Check that it runs, then run ${l} again.`,
  PROVIDER_UNAVAILABLE: (l) => `the provider did not answer. Run ${l} again once it is back.`,
  TIMED_OUT: (l) => `the run did not finish in its time limit. Run ${l} again.`,
  UPSTREAM_FAILED: (l) => `the agent ended without an answer. Run ${l} again.`,
};

/** The sentence after `Stopped:` for a run that a stop code ended. */
export function stopReason(code: string | null | undefined, lane: RunLane): string {
  const reason = REASON[cause(code)];
  return reason ? reason(lane) : `the run hit a setting it cannot use. Fix it, then run ${lane} again.`;
}
