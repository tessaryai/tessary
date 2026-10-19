// SPDX-License-Identifier: Apache-2.0
/*
 * Where a metrics row opens: a call site opens its traces over the same window, a case opens Triage, and every key
 * the Traces filter bar cannot express links nowhere. Expected hrefs come from the Traces URL params
 * (`views/traces/index-filters.tsx`).
 */
import { describe, expect, it } from "vitest";
import { WINDOW_7D } from "../../test/metricsFixtures";
import { metricsLink, OTHER_KEY, UNATTRIBUTED_KEY } from "./metricsLinks";

const BASE = "/o/acme-org/p/demo";
const FROM = "2026-10-11T00%3A00%3A00Z";
const TO = "2026-10-18T00%3A00%3A00Z";

const link = (view: string, dimension: string | null, key: string | null) =>
  metricsLink({ basePath: BASE, view, dimension, key, window: WINDOW_7D });

describe("metricsLink", () => {
  // Bug: a call-site row opens an unfiltered Traces list, or one over Traces' own default range instead of the window.
  it.each([
    ["traces", "chat", `${BASE}/traces?call_site=chat&range=custom&from=${FROM}&to=${TO}`],
    ["llm_calls", "billing/refund agent", `${BASE}/traces?call_site=billing%2Frefund%20agent&range=custom&from=${FROM}&to=${TO}`],
  ])("callSiteKeysOpenTracesFilteredToTheWindow (%s, %s)", (view, key, href) => {
    expect(link(view, "call_site", key)).toBe(href);
  });

  // Bug: the unattributed bucket opens a search for a call site literally named "__unattributed__", which is empty.
  it.each(["traces", "llm_calls"])("unattributedOpensTheNoCallSiteScope (%s)", (view) => {
    expect(link(view, "call_site", UNATTRIBUTED_KEY)).toBe(
      `${BASE}/traces?call_site_scope=none&range=custom&from=${FROM}&to=${TO}`,
    );
  });

  // Bug: the fold or a dimension-less row becomes a link to a filter that matches nothing.
  it.each([
    ["traces", "call_site", OTHER_KEY],
    ["llm_calls", "model", OTHER_KEY],
    ["cases", "call_site", OTHER_KEY],
    ["traces", null, null],
    ["cases", null, null],
  ])("otherAndNullKeysLinkNowhere (%s, %s, %s)", (view, dimension, key) => {
    expect(link(view, dimension, key)).toBeNull();
  });

  // Bug: a model or tool row opens Traces with a param the filter bar ignores, so the reader sees every trace.
  it.each([
    ["llm_calls", "model"], ["llm_calls", "cost_source"], ["tool_calls", "tool"], ["tool_calls", "error_type"],
    ["tool_calls", "scope"], ["tool_calls", "tool_type"], ["traces", "user"], ["traces", "session"],
    ["traces", "name"], ["traces", "outcome"],
  ])("modelAndEveryNonCallSiteDimensionLinkNowhere (%s, %s)", (view, dimension) => {
    expect(link(view, dimension, "gpt-5")).toBeNull();
  });

  // Bug: a cases row opens Traces filtered by call site, where cases do not show.
  it.each([
    ["call_site", "chat"], ["call_site", UNATTRIBUTED_KEY], ["detector", "frustration"], ["state", "open"],
  ])("casesRowsOpenTriage (%s, %s)", (dimension, key) => {
    expect(link("cases", dimension, key)).toBe(`${BASE}/triage`);
  });
});
