// SPDX-License-Identifier: Apache-2.0
import type { Window } from "./metricsRules";

export const OTHER_KEY = "__other__";
export const UNATTRIBUTED_KEY = "__unattributed__";

/** The Traces filter bar has a call-site facet only, so every other key links nowhere. */
export function metricsLink(args: {
  basePath: string;
  view: string;
  dimension: string | null;
  key: string | null;
  window: Window;
}): string | null {
  const { basePath, view, dimension, key, window } = args;
  if (key === OTHER_KEY || key == null) return null;
  if (view === "cases") return `${basePath}/triage`;
  if (dimension !== "call_site") return null;
  const range = `range=custom&from=${encodeURIComponent(window.from)}&to=${encodeURIComponent(window.to)}`;
  if (key === UNATTRIBUTED_KEY) return `${basePath}/traces?call_site_scope=none&${range}`;
  return `${basePath}/traces?call_site=${encodeURIComponent(key)}&${range}`;
}
