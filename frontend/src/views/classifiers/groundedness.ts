// SPDX-License-Identifier: Apache-2.0
/*
 * Groundedness's setup, restart and status copy: the frontend's single source for the setup guides'
 * links, the prompts that point at them, and the row's status words.
 *
 * The guide URLs are written at `blob/main` on purpose. The link check reads them from this file to
 * prove each path exists in the tree; the prompts swap `main` for the running version's tag
 * (`setup_ref`) so a release links the guide, and the `serve.py` it downloads, from its own tag.
 */
import { ApiError, type GroundednessMode, type GroundednessStatus } from "../../api/types";

/** Groundedness's detector key. */
export const GROUNDEDNESS_DETECTOR = "groundedness";

/** The model the setup guides install, as the rail names it. */
export const GROUNDEDNESS_MODEL = "groundedness-classifier-v1";

export const GROUNDEDNESS_MAC_MD =
  "https://github.com/tessaryai/tessary/blob/main/classifiers/groundedness/setup/groundedness-setup-mac.md";
export const GROUNDEDNESS_AWS_MD =
  "https://github.com/tessaryai/tessary/blob/main/classifiers/groundedness/setup/groundedness-setup-aws.md";

/** `url` at `ref` instead of `main`: a release's prompts link the guide from its own tag. */
export function atRef(url: string, ref: string): string {
  return url.replace("/blob/main/", `/blob/${ref}/`);
}

/** The setup guide for `mode`, at `ref`. */
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

/** Where the model runs, as the mode control and the rail word it. */
export const MODE_LABEL: Record<GroundednessMode, string> = {
  dev: "This Mac (dev)",
  production: "AWS (production)",
};

export const SETUP_REQUIREMENTS: Record<GroundednessMode, string> = {
  dev: "Requires Apple silicon, 16 GB of memory, and 6 GB of free disk.",
  production: "Requires a signed-in AWS CLI and Tessary running on AWS, in the same region.",
};

/** The status query's key, shared by the row, the rail and the modals so one answer serves all of them. */
export function groundednessStatusKey(base: string, classifierId: string | undefined) {
  return ["groundedness-status", base, classifierId] as const;
}

/**
 * The model has never answered here: not answering now, and no sweep ever moved the cursor. The
 * server's `not_set_up`, which it only reports for an enabled row, so a disabled row that was never set
 * up reads the same way from these two fields.
 */
export function neverSetUp(status: GroundednessStatus): boolean {
  return !status.available && !status.ever_swept;
}

/** The row's state: the server's, except a disabled row that was never set up reads `not_set_up`. */
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

/** "2:02 PM" today, "Sep 22, 2:02 PM" on any other day, in local time. */
export function clockTime(iso: string, now: Date = new Date()): string {
  const d = new Date(iso);
  // Newer ICU puts a narrow no-break space before "PM"; the copy uses a plain one.
  const time = d.toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" }).replace(/[  ]/g, " ");
  return d.toDateString() === now.toDateString()
    ? time
    : `${d.toLocaleDateString("en-US", { month: "short", day: "numeric" })}, ${time}`;
}

/**
 * The newest sign of scoring: the last score, or in production a later caught-up sweep, since a run
 * that found nothing new to score still ran.
 */
export function lastSignOfScoring(status: GroundednessStatus): string | null {
  const scored = status.last_scored_at;
  const caughtUp = status.mode === "production" ? status.last_caught_up_at : null;
  if (scored && caughtUp) return Date.parse(caughtUp) > Date.parse(scored) ? caughtUp : scored;
  return scored ?? caughtUp;
}

/** "No scores since 2:02 PM", or "Not scoring" when nothing was ever scored. */
export function notScoringLabel(status: GroundednessStatus, now: Date = new Date()): string {
  const since = lastSignOfScoring(status);
  return since ? `No scores since ${clockTime(since, now)}` : "Not scoring";
}

/**
 * The "Setting up..." flag: set in this browser when a setup prompt is copied, cleared once the model
 * answers. Storage can be missing or throw (a private window, blocked site data), and the row then
 * simply never shows it.
 */
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
    /* no storage: the row just won't say "Setting up..." */
  }
}

export function clearSetupFlag(org: string, project: string): void {
  try {
    window.localStorage.removeItem(setupFlagKey(org, project));
  } catch {
    /* nothing to clear */
  }
}
