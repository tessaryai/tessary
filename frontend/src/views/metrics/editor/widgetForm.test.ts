// SPDX-License-Identifier: Apache-2.0
/*
 * The widget editor's form rules apart from React: the draft a new or stored widget starts from, what a chart or view
 * switch keeps, the validation messages in order, the automatic title, the id and the wire shape saved. Expected
 * values come from the widget rules of the dashboard validator and the editor copy.
 */
import { describe, expect, it } from "vitest";
import { METRICS_CATALOG, widget } from "../../../test/metricsFixtures";
import type { MetricsChart } from "../wire";
import {
  autoTitle, autoTitleOf, draftOf, emptyDraft, MAX_TITLE, switchChart, switchView, topNOptions, validateDraft,
  type WidgetDraft, widgetId, widgetOf,
} from "./widgetForm";

const draft = (over: Partial<WidgetDraft> = {}): WidgetDraft => ({
  title: "", chart: "number", view: "llm_calls", measures: ["cost_usd"], note: null, dimension: null, topN: null,
  compare: false, ...over,
});

const viewNamed = (name: string) => METRICS_CATALOG.views.find((v) => v.name === name);

describe("emptyDraft and draftOf", () => {
  // Bug: a new widget opens on a view or measure the catalog does not list, so the first save is refused.
  it("emptyDraftStartsOnTheFirstViewAndMeasure", () => {
    expect(emptyDraft(METRICS_CATALOG)).toEqual(draft({ view: "traces", measures: ["traces"] }));
  });

  // Bug: editing the Spend tile shows two measures and no note, and saving turns the note into a second figure.
  it.each<[string, Parameters<typeof widget>[0], WidgetDraft]>([
    [
      "number with a note and a compare",
      { id: "spend", title: "Spend", measures: ["cost_usd", "usd_per_trace"], compare: "previous_window" },
      draft({ title: "Spend", note: "usd_per_trace", compare: true }),
    ],
    ["number without a note", { id: "n" }, draft()],
    [
      "time series",
      { id: "t", chart: "timeseries", measures: ["cost_usd"], dimension: "call_site", top_n: 5 },
      draft({ chart: "timeseries", dimension: "call_site", topN: 5 }),
    ],
    [
      "table",
      { id: "b", chart: "table", view: "traces", measures: ["cost_usd", "traces"], dimension: "call_site", top_n: 20 },
      draft({ chart: "table", view: "traces", measures: ["cost_usd", "traces"], dimension: "call_site", topN: 20 }),
    ],
  ])("draftOfSplitsANumberIntoMeasureAndNote (%s)", (_name, stored, expected) => {
    expect(draftOf(widget(stored))).toEqual(expected);
  });
});

describe("switchChart", () => {
  // Bug: a table dimension left on a number, a compare left on a time series, or a top N the new chart refuses.
  it.each<[string, WidgetDraft, MetricsChart, WidgetDraft]>([
    [
      "table to number",
      draft({ chart: "table", measures: ["cost_usd", "calls"], dimension: "model", topN: 20 }),
      "number",
      draft({ measures: ["cost_usd"] }),
    ],
    [
      "number to time series",
      draft({ note: "usd_per_trace", compare: true }),
      "timeseries",
      draft({ chart: "timeseries" }),
    ],
    [
      "table to time series drops a top N over 5",
      draft({ chart: "table", measures: ["cost_usd", "calls"], dimension: "model", topN: 20 }),
      "timeseries",
      draft({ chart: "timeseries", dimension: "model" }),
    ],
    [
      "table to time series keeps a top N of 5",
      draft({ chart: "table", dimension: "model", topN: 5 }),
      "timeseries",
      draft({ chart: "timeseries", dimension: "model", topN: 5 }),
    ],
    [
      "number to table",
      draft({ note: "usd_per_trace", compare: true }),
      "table",
      draft({ chart: "table" }),
    ],
    [
      "time series to table keeps its top N",
      draft({ chart: "timeseries", dimension: "call_site", topN: 3 }),
      "table",
      draft({ chart: "table", dimension: "call_site", topN: 3 }),
    ],
    [
      "a top N over 100 does not survive on a table",
      draft({ chart: "timeseries", dimension: "call_site", topN: 101 }),
      "table",
      draft({ chart: "table", dimension: "call_site" }),
    ],
  ])("switchChartDropsWhatTheChartCannotCarry (%s)", (_name, from, chart, expected) => {
    expect(switchChart(from, chart)).toEqual(expected);
  });
});

describe("switchView", () => {
  // Bug: a measure or dimension from the old view survives the switch, and the server answers INVALID_WIDGET on save.
  it.each<[string, WidgetDraft, string, WidgetDraft]>([
    [
      "number",
      draft({ note: "usd_per_trace", compare: true }),
      "cases",
      draft({ view: "cases", measures: ["cases"], compare: true }),
    ],
    [
      "time series",
      draft({ chart: "timeseries", dimension: "model", topN: 3 }),
      "tool_calls",
      draft({ chart: "timeseries", view: "tool_calls", measures: ["calls"], topN: 3 }),
    ],
    [
      "table takes the view's first dimension",
      draft({ chart: "table", measures: ["cost_usd", "calls"], dimension: "model" }),
      "tool_calls",
      draft({ chart: "table", view: "tool_calls", measures: ["calls"], dimension: "tool" }),
    ],
    [
      "a view the catalog does not list",
      draft({ chart: "table", dimension: "model" }),
      "spans",
      draft({ chart: "table", view: "spans", measures: [] }),
    ],
  ])("switchViewResetsMeasuresNoteAndDimension (%s)", (_name, from, view, expected) => {
    expect(switchView(from, view, METRICS_CATALOG)).toEqual(expected);
  });
});

describe("validateDraft", () => {
  const long = "x".repeat(MAX_TITLE + 1);

  // Bug: the editor saves a widget the dashboard validator refuses, or names the wrong problem first.
  it.each<[string, WidgetDraft, string | null]>([
    ["unknown view", draft({ view: "spans", measures: [], title: long }), "Pick a view."],
    ["no measure", draft({ measures: [], title: long }), "Pick a measure."],
    ["measure of another view", draft({ measures: ["cases"] }), "Pick a measure."],
    ["note of another view", draft({ note: "open_cases" }), "Pick a measure."],
    ["number with two measures", draft({ measures: ["cost_usd", "calls"], title: long }), "A number takes one measure."],
    [
      "time series with two measures",
      draft({ chart: "timeseries", measures: ["cost_usd", "calls"], topN: 9 }),
      "A time series takes one measure.",
    ],
    [
      "table with five measures",
      draft({ chart: "table", view: "traces", measures: ["traces", "errored_traces", "cost_usd", "usd_per_trace", "latency_n"] }),
      "A table takes 1 to 4 measures.",
    ],
    ["table without a dimension", draft({ chart: "table", topN: 500 }), "A table needs a dimension."],
    ["time series top 6", draft({ chart: "timeseries", topN: 6, title: long }), "Top N is 1 to 5 on a time series."],
    ["time series top 0", draft({ chart: "timeseries", topN: 0 }), "Top N is 1 to 5 on a time series."],
    ["table top 101", draft({ chart: "table", dimension: "model", topN: 101, title: long }), "Top N is 1 to 100 on a table."],
    ["table top 0", draft({ chart: "table", dimension: "model", topN: 0 }), "Top N is 1 to 100 on a table."],
    ["title of 81 characters", draft({ title: long }), "The title is longer than 80 characters."],
    ["title of 80 characters", draft({ title: "x".repeat(MAX_TITLE) }), null],
    ["number with a note", draft({ note: "usd_per_trace", compare: true }), null],
    ["time series top 5", draft({ chart: "timeseries", dimension: "call_site", topN: 5 }), null],
    ["time series top 1", draft({ chart: "timeseries", dimension: "call_site", topN: 1 }), null],
    ["table of four with top 100", draft({ chart: "table", measures: ["cost_usd", "calls", "tokens", "usd_per_trace"], dimension: "model", topN: 100 }), null],
    ["table top 1", draft({ chart: "table", dimension: "model", topN: 1 }), null],
  ])("validateDraftReturnsTheFirstMessageInOrder (%s)", (_name, d, message) => {
    expect(validateDraft(d, METRICS_CATALOG)).toBe(message);
  });
});

describe("autoTitle", () => {
  // Bug: a widget without a title shows the measure's wire name, or "Spend by Call site" with a capital mid-sentence.
  it.each<[WidgetDraft, string]>([
    [draft({ chart: "timeseries", dimension: "call_site" }), "Spend by call site"],
    [draft({ view: "traces", measures: ["latency_p95_ms"] }), "p95 latency"],
    [draft({ chart: "table", view: "llm_calls", measures: ["calls", "cost_usd"], dimension: "model" }), "LLM calls by model"],
    [draft({ view: "spans", measures: ["whatever"] }), "whatever"],
    [draft({ measures: [] }), ""],
  ])("autoTitleNamesMeasureAndDimension (%o)", (d, title) => {
    expect(autoTitle(d, METRICS_CATALOG)).toBe(title);
  });

  // Bug: a stored widget whose view or measure the catalog dropped renders an empty title bar.
  it.each<[Parameters<typeof widget>[0], string | undefined, string]>([
    [{ id: "spend-by-call-site", chart: "timeseries", dimension: "call_site" }, "llm_calls", "Spend by call site"],
    [{ id: "p95", view: "traces", measures: ["latency_p95_ms"] }, "traces", "p95 latency"],
    [{ id: "gone", view: "spans" }, undefined, "gone"],
    [{ id: "renamed", measures: ["old_measure"] }, "llm_calls", "renamed"],
    [{ id: "odd-dim", chart: "table", dimension: "region" }, "llm_calls", "Spend by region"],
  ])("autoTitleOfFallsBackToTheId (%o)", (stored, viewName, title) => {
    expect(autoTitleOf(widget(stored), viewName ? viewNamed(viewName) : undefined)).toBe(title);
  });
});

describe("widgetId", () => {
  // Bug: an id the validator refuses (over 40 characters, punctuation, empty) or one already on the dashboard.
  it.each<[string, string[], string]>([
    ["Spend by call site", [], "spend-by-call-site"],
    ["  p95 latency (UTC)! ", [], "p95-latency-utc"],
    ["a".repeat(41), [], "a".repeat(40)],
    [`${"b".repeat(39)} c`, [], "b".repeat(39)],
    ["Spend", ["spend"], "spend-2"],
    ["Spend", ["spend", "spend-2"], "spend-3"],
    ["a".repeat(41), ["a".repeat(40)], `${"a".repeat(38)}-2`],
    ["!!!", [], "widget"],
    ["", ["widget"], "widget-2"],
  ])("widgetIdSlugsCutsAndDeduplicates (%s)", (title, taken, id) => {
    const made = widgetId(title, taken);
    expect(made).toBe(id);
    expect(made).toMatch(/^[a-z0-9-]{1,40}$/);
  });
});

describe("widgetOf", () => {
  const at = { x: 4, y: 6, w: 6, h: 5 };

  // Bug: a blank title saved as "", the note lost, the compare saved as true, or filters left out of the wire shape.
  it.each<[string, WidgetDraft, Parameters<typeof widget>[0]]>([
    [
      "number with a note and a compare",
      draft({ title: "  ", note: "usd_per_trace", compare: true }),
      { id: "w1", measures: ["cost_usd", "usd_per_trace"], compare: "previous_window", ...at },
    ],
    ["number without a note", draft({ title: "Spend" }), { id: "w1", title: "Spend", ...at }],
    [
      "table",
      draft({ chart: "table", measures: ["cost_usd", "calls"], dimension: "model", topN: 20 }),
      { id: "w1", chart: "table", measures: ["cost_usd", "calls"], dimension: "model", top_n: 20, ...at },
    ],
  ])("widgetOfBuildsTheWireShape (%s)", (_name, d, expected) => {
    expect(widgetOf(d, "w1", at)).toEqual(widget(expected));
  });
});

describe("topNOptions", () => {
  // Bug: the picker offers a top N the validator refuses for that chart.
  it.each<[MetricsChart, number[]]>([
    ["timeseries", [1, 2, 3, 4, 5]],
    ["table", [5, 10, 20, 50, 100]],
    ["number", []],
  ])("topNOptionsPerChart (%s)", (chart, options) => {
    expect(topNOptions(chart)).toEqual(options);
  });
});
