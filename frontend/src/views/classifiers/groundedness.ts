// SPDX-License-Identifier: Apache-2.0
// Guide URLs are written at `blob/main` because the link check reads them from this file; the prompts
// swap `main` for the running version's tag so a release links its own guide and serve.py.
import { ApiError, type GroundednessMode, type GroundednessStatus } from "../../api/types";

export const GROUNDEDNESS_DETECTOR = "groundedness";

export const GROUNDEDNESS_MODEL = "groundedness-classifier-v1";

const GROUNDEDNESS_MAC_MD =
  "https://github.com/tessaryai/tessary/blob/main/classifiers/groundedness/setup/groundedness-setup-mac.md";
export const GROUNDEDNESS_AWS_MD =
  "https://github.com/tessaryai/tessary/blob/main/classifiers/groundedness/setup/groundedness-setup-aws.md";

export function atRef(url: string, ref: string): string {
  return url.replace("/blob/main/", `/blob/${ref}/`);
}

export function setupGuide(mode: GroundednessMode, ref: string): string {
  return atRef(mode === "production" ? GROUNDEDNESS_AWS_MD : GROUNDEDNESS_MAC_MD, ref);
}

export function setupPrompt(mode: GroundednessMode, ref: string): string {
  return mode === "production"
    ? `Deploy the Groundedness classifier's model on AWS by following ${setupGuide(mode, ref)}`
    : `Set up the Groundedness classifier on this Mac by following ${setupGuide(mode, ref)}`;
}

export function restartPrompt(mode: GroundednessMode, ref: string): string {
  return mode === "production"
    ? `Restart the Groundedness model on AWS by following ${setupGuide(mode, ref)}#restart`
    : `Restart the Groundedness model on this Mac by following ${setupGuide(mode, ref)}#restart`;
}

export const MODE_LABEL: Record<GroundednessMode, string> = {
  dev: "This Mac (dev)",
  production: "AWS (production)",
};

export const SETUP_REQUIREMENTS: Record<GroundednessMode, string> = {
  dev: "Requires Apple silicon, 16 GB of memory, and 6 GB of free disk.",
  production: "Requires a signed-in AWS CLI and Tessary running on AWS, in the same region.",
};

export function groundednessStatusKey(base: string, classifierId: string | undefined) {
  return ["groundedness-status", base, classifierId] as const;
}

/** The server reports `not_set_up` only for an enabled row, so this reads it from the two fields. */
export function neverSetUp(status: GroundednessStatus): boolean {
  return !status.available && !status.ever_swept;
}

export function rowState(status: GroundednessStatus): GroundednessStatus["state"] {
  return status.state === "off" && neverSetUp(status) ? "not_set_up" : status.state;
}

/**
 * A status read that failed because Tessary itself is restarting, which the setup expects: no answer at
 * all, a proxy's 502, 503 or 504, or a body that isn't the API's JSON (a dev proxy's bare 500).
 */
export function isRestartingError(error: unknown): boolean {
  if (!(error instanceof ApiError)) return true;
  return error.status === 502 || error.status === 503 || error.status === 504 || error.code === "COMMON.INVALID_BODY";
}

export function clockTime(iso: string, now: Date = new Date()): string {
  const d = new Date(iso);
  // Newer ICU puts a narrow no-break space before "PM"; the copy uses a plain one.
  const time = d.toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" }).replace(/[  ]/g, " ");
  return d.toDateString() === now.toDateString()
    ? time
    : `${d.toLocaleDateString("en-US", { month: "short", day: "numeric" })}, ${time}`;
}

export function dateTime(iso: string): string {
  return new Date(iso)
    .toLocaleString("en-US", { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" })
    .replace(/[\u202f\u00a0]/g, " ");
}

/** A production sweep that found nothing new to score still ran. */
function lastSignOfScoring(status: GroundednessStatus): string | null {
  const scored = status.last_scored_at;
  const caughtUp = status.mode === "production" ? status.last_caught_up_at : null;
  if (scored && caughtUp) return Date.parse(caughtUp) > Date.parse(scored) ? caughtUp : scored;
  return scored ?? caughtUp;
}

export function notScoringLabel(status: GroundednessStatus, now: Date = new Date()): string {
  const since = lastSignOfScoring(status);
  return since ? `No scores since ${clockTime(since, now)}` : "Not scoring";
}

/** Storage can be missing or throw; the row then never shows the flag. */
function setupFlagKey(org: string, project: string): string {
  return `tsy-groundedness-setup:${org}/${project}`;
}

export function readSetupFlag(org: string, project: string): boolean {
  try {
    return window.localStorage.getItem(setupFlagKey(org, project)) != null;
  } catch {
    return false;
  }
}

export function writeSetupFlag(org: string, project: string): void {
  try {
    window.localStorage.setItem(setupFlagKey(org, project), new Date().toISOString());
  } catch {
  }
}

export function clearSetupFlag(org: string, project: string): void {
  try {
    window.localStorage.removeItem(setupFlagKey(org, project));
  } catch {
  }
}
