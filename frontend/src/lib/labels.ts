// SPDX-License-Identifier: Apache-2.0
import type { CallSite } from "../api/types";

/**
 * Human-readable labels for call sites.
 *
 * The pipeline identifies entities by machine keys that leak into the UI as
 * unreadable tokens:
 *  - grader ids follow the nested convention `callsite::grader_name::grader`
 *    (e.g. `epistemic_gate_analytics::prompt_injection_in_reasoning_chain::grader`);
 *  - call-site ids and grader `name`s are snake_case (`summarize_thread`,
 *    `decision_relevance_filtering`).
 *
 * These helpers turn either form into a readable sentence-cased label, so the
 * app can lead with "Prompt injection in reasoning chain" and keep the raw key
 * only where identity/traceability needs it (a tooltip or a dedicated id line).
 */

const KIND_SUFFIX = /^(grader|graders|check|judge)$/i;

/**
 * Turn a machine key into a readable label. Strips the nested `::` structure
 * down to the entity's own segment (dropping a trailing kind suffix like
 * `grader`), then sentence-cases the snake/kebab words. Anything already spaced
 * passes through with just its first letter capitalized.
 */
export function humanizeKey(raw: string | null | undefined): string {
  if (raw == null) return "";
  let s = raw.trim();
  if (!s) return "";

  if (s.includes("::")) {
    const parts = s
      .split("::")
      .map((p) => p.trim())
      .filter(Boolean);
    if (parts.length > 1 && KIND_SUFFIX.test(parts[parts.length - 1])) parts.pop();
    s = parts[parts.length - 1] ?? s;
  }

  s = s.replace(/[_-]+/g, " ").replace(/\s+/g, " ").trim();
  if (!s) return raw.trim();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/** Readable grader label — prefers the curated `name`, falls back to the id. */
/** Readable call-site label. Call sites carry no name field, so humanize the id. */
export function callSiteLabel(callSite: Pick<CallSite, "id">): string {
  return humanizeKey(callSite.id);
}

/** Readable call-site label from a bare id. */
export function callSiteLabelFromId(callSiteId: string | null | undefined): string {
  return humanizeKey(callSiteId);
}
