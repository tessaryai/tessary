// SPDX-License-Identifier: Apache-2.0
/*
 * The rules Metrics draws by, apart from React: the window a range names, the URL state, the request a widget sends,
 * every number and line of copy a tile prints, the series a chart draws and its axes. Every expected value comes from
 * the Metrics design, the backend contract or a hand calculation.
 */
import { describe, expect, it } from "vitest";
import {
  METRICS_CATALOG, seriesResult, tableResult, totalResult, widget, WINDOW_7D,
} from "../../test/metricsFixtures";
import {
  axisTicks, axisUnit, barsFor, bucketLabel, chartHeightFor, companionNotes, copyName, deltaText, emptyResult,
  filteredByCallSite, formatValue, hasDimension, lowerFirst, measureOf, nextHour, noteText, parseRange, RANGE_PRESETS,
  rangeLabel, rangeNoun, rangeParams, type RangeState, resolveWindow, type Series, seriesColour, seriesOf, seriesPaths,
  timeTicks, unpricedOf, unpricedTotal, validateWindow, viewOf, widgetRequest,
} from "./metricsRules";
import type { MetricsCatalogMeasure, MetricsQueryResult, MetricsUnit } from "./wire";

const HOUR = 3_600_000;
const DAY = 86_400_000;
const NOW = Date.parse("2026-10-18T09:58:48Z");

const view = (name: string) => viewOf(METRICS_CATALOG, name);
const catalogMeasure = (viewName: string, name: string) => measureOf(view(viewName), name)!;

describe("windows", () => {
  // Bug: a window that ends at `now` changes on every render, so the query key never settles; or a preset spans the
  // wrong length.
  it.each<[string, string, string]>([
    ["24h", "2026-10-17T10:00:00.000Z", "2026-10-18T10:00:00.000Z"],
    ["7d", "2026-10-11T10:00:00.000Z", "2026-10-18T10:00:00.000Z"],
    ["30d", "2026-09-18T10:00:00.000Z", "2026-10-18T10:00:00.000Z"],
    ["90d", "2026-07-20T10:00:00.000Z", "2026-10-18T10:00:00.000Z"],
  ])("presetWindowsEndAtTheNextHourAndSpanTheirLength (%s)", (key, from, to) => {
    const preset = RANGE_PRESETS.find((p) => p.key === key)!;
    expect(resolveWindow({ kind: "preset", key: preset.key }, NOW)).toEqual({ from, to });
    expect(resolveWindow({ kind: "preset", key: preset.key }, NOW + 60_000)).toEqual({ from, to });
  });

  // Bug: a reload on the hour keeps a window that ends now, so the current hour is cut off.
  it("presetWindowsEndAtTheNextHourAndSpanTheirLength (on the hour)", () => {
    expect(nextHour(Date.parse("2026-10-18T10:00:00Z"))).toBe(Date.parse("2026-10-18T11:00:00Z"));
    expect(resolveWindow({ kind: "custom", from: "2026-10-01T09:00:00Z", to: "2026-10-03T09:00:00Z" }, NOW)).toEqual({
      from: "2026-10-01T09:00:00Z",
      to: "2026-10-03T09:00:00Z",
    });
  });

  // Bug: a preset label or length typo reaches the range control, or the presets come out of order.
  it("names the four presets in order", () => {
    expect(RANGE_PRESETS.map((p) => [p.key, p.label, p.ms])).toEqual([
      ["24h", "Last 24 hours", 24 * HOUR],
      ["7d", "Last 7 days", 7 * DAY],
      ["30d", "Last 30 days", 30 * DAY],
      ["90d", "Last 90 days", 90 * DAY],
    ]);
  });
});

describe("URL range state", () => {
  const custom = (from: string, to: string): RangeState => ({ kind: "custom", from, to });

  // Bug: a bad link renders an error or a wrong window instead of the dashboard's own default, or the fallback is a
  // hard-coded 7d.
  it.each<[string, RangeState]>([
    ["", { kind: "preset", key: "30d" }],
    ["range=24h", { kind: "preset", key: "24h" }],
    ["range=90d", { kind: "preset", key: "90d" }],
    ["range=1y", { kind: "preset", key: "30d" }],
    ["range=custom&from=2026-10-01T09:00:00Z&to=2026-10-03T09:00:00Z", custom("2026-10-01T09:00:00Z", "2026-10-03T09:00:00Z")],
    ["range=custom&from=2026-10-03T09:00:00Z&to=2026-10-01T09:00:00Z", { kind: "preset", key: "30d" }],
    ["range=custom&from=2026-06-01T00:00:00Z&to=2026-10-01T00:00:00Z", { kind: "preset", key: "30d" }],
    ["range=custom&from=&to=2026-10-03T09:00:00Z", { kind: "preset", key: "30d" }],
    ["range=custom&from=2026-10-01T09:00:00Z", { kind: "preset", key: "30d" }],
    ["range=custom&from=yesterday&to=2026-10-03T09:00:00Z", { kind: "preset", key: "30d" }],
    ["from=2026-10-01T09:00:00Z&to=2026-10-03T09:00:00Z", { kind: "preset", key: "30d" }],
  ])("parseRangeReadsPresetsCustomPairsAndFallsBackOnEverythingElse (%s)", (query, range) => {
    expect(parseRange(new URLSearchParams(query), "30d")).toEqual(range);
  });

  // Bug: a range written to the URL reads back as a different range, so a shared link opens another window.
  it.each<RangeState>([
    { kind: "preset", key: "24h" },
    { kind: "preset", key: "90d" },
    custom("2026-10-01T09:00:00Z", "2026-10-03T09:00:00Z"),
  ])("rangeParamsRoundTripThroughParseRange (%o)", (range) => {
    const params = new URLSearchParams();
    for (const [k, v] of Object.entries(rangeParams(range))) if (v != null) params.set(k, v);
    expect(parseRange(params, "7d")).toEqual(range);
  });

  // Bug: a preset link carries stale from and to params, or a custom link drops one side.
  it("rangeParamsRoundTripThroughParseRange (the params themselves)", () => {
    expect(rangeParams({ kind: "preset", key: "7d" })).toEqual({ range: "7d", from: null, to: null });
    expect(rangeParams(custom("a", "b"))).toEqual({ range: "custom", from: "a", to: "b" });
  });

  // Bug: the picker applies a reversed or over-long window the server refuses, or names the wrong problem first.
  it.each<[string | null, string | null, { window: { from: string; to: string } } | { error: string }]>([
    [null, "2026-10-03T09:00:00Z", { error: "Pick a start and an end." }],
    ["2026-10-01T09:00:00Z", null, { error: "Pick a start and an end." }],
    ["", "", { error: "Pick a start and an end." }],
    ["soon", "2026-10-03T09:00:00Z", { error: "Pick a start and an end." }],
    ["2026-10-03T09:00:00Z", "2026-10-03T09:00:00Z", { error: "The start must be before the end." }],
    ["2026-10-03T09:00:00Z", "2026-07-01T09:00:00Z", { error: "The start must be before the end." }],
    ["2026-07-20T10:00:00Z", "2026-10-18T10:00:00Z", { window: { from: "2026-07-20T10:00:00Z", to: "2026-10-18T10:00:00Z" } }],
    ["2026-07-20T09:59:00Z", "2026-10-18T10:00:00Z", { error: "Pick a window of 90 days or less." }],
  ])("validateWindowMessagesInOrder (%s, %s)", (from, to, result) => {
    expect(validateWindow(from, to)).toEqual(result);
  });

  // Bug: a custom window printed in the reader's local zone while every bucket is in UTC, or at 24:00.
  it.each<[RangeState, string]>([
    [{ kind: "preset", key: "7d" }, "Last 7 days"],
    [{ kind: "preset", key: "24h" }, "Last 24 hours"],
    [custom("2026-10-01T09:00:00Z", "2026-10-03T09:00:00Z"), "Oct 1, 2026, 09:00 to Oct 3, 2026, 09:00 UTC"],
    [custom("2026-10-01T00:00:00Z", "2026-10-01T11:30:00+02:00"), "Oct 1, 2026, 00:00 to Oct 1, 2026, 09:30 UTC"],
  ])("rangeLabelWordsThePresetsAndTheCustomPairInUtc (%o)", (range, label) => {
    expect(rangeLabel(range)).toBe(label);
  });

  // Bug: the delta line reads "vs previous Last 7 days" or names a length for a custom window.
  it.each<[RangeState, string]>([
    [{ kind: "preset", key: "24h" }, "24 hours"],
    [{ kind: "preset", key: "7d" }, "7 days"],
    [{ kind: "preset", key: "30d" }, "30 days"],
    [{ kind: "preset", key: "90d" }, "90 days"],
    [custom("2026-10-01T09:00:00Z", "2026-10-03T09:00:00Z"), "window"],
  ])("rangeNounPerPreset (%o)", (range, noun) => {
    expect(rangeNoun(range)).toBe(noun);
  });
});

describe("widgetRequest", () => {
  const callSite = { field: "call_site", op: "in" as const, values: ["chat"] };
  const model = { field: "model", op: "in" as const, values: ["gpt-5"] };

  // Bug: a call-site filter on a view without that dimension, which the server refuses with UNKNOWN_FILTER_FIELD;
  // stored filters dropped; an empty list sent as []; granularity on a number.
  it.each<[string, Parameters<typeof widget>[0], string | null, string, object]>([
    [
      "tool_calls has no call site",
      { id: "t", view: "tool_calls", measures: ["calls"] },
      "chat",
      "tool_calls",
      { view: "tool_calls", measures: ["calls"], dimension: null, filters: null, granularity: null, top_n: null, compare: null },
    ],
    [
      "llm_calls gains the filter",
      { id: "s", measures: ["cost_usd", "usd_per_trace"], compare: "previous_window" },
      "chat",
      "llm_calls",
      { view: "llm_calls", measures: ["cost_usd", "usd_per_trace"], dimension: null, filters: [callSite], granularity: null, top_n: null, compare: "previous_window" },
    ],
    [
      "stored filters come first",
      { id: "ts", chart: "timeseries", dimension: "call_site", top_n: 5, filters: [model] },
      "chat",
      "llm_calls",
      { view: "llm_calls", measures: ["cost_usd"], dimension: "call_site", filters: [model, callSite], granularity: "auto", top_n: 5, compare: null },
    ],
    [
      "no call site keeps stored filters",
      { id: "ts", chart: "timeseries", filters: [model] },
      null,
      "llm_calls",
      { view: "llm_calls", measures: ["cost_usd"], dimension: null, filters: [model], granularity: "auto", top_n: null, compare: null },
    ],
    [
      "a stored null stays null",
      { id: "b", chart: "table", view: "traces", measures: ["traces"], dimension: "call_site", top_n: 20, filters: null },
      null,
      "traces",
      { view: "traces", measures: ["traces"], dimension: "call_site", filters: null, granularity: null, top_n: 20, compare: null },
    ],
    [
      "the catalog has not loaded",
      { id: "s" },
      "chat",
      "missing",
      { view: "llm_calls", measures: ["cost_usd"], dimension: null, filters: null, granularity: null, top_n: null, compare: null },
    ],
  ])("widgetRequestAddsTheCallSiteFilterOnlyWhenTheViewHasTheDimension (%s)", (_name, stored, site, viewName, expected) => {
    expect(widgetRequest(widget(stored), WINDOW_7D, site, view(viewName))).toEqual({ ...expected, ...WINDOW_7D });
  });

  // Bug: "Not filtered by call site" missing on a tool-calls tile, or shown on a tile the filter does narrow.
  it.each<[string, string | null, boolean]>([
    ["llm_calls", "llm_calls", true],
    ["cases", "cases", true],
    ["tool_calls", "tool_calls", false],
    ["llm_calls", null, false],
    ["llm_calls", "traces", false],
  ])("filteredByCallSite (widget %s, catalog view %s)", (widgetView, viewName, filtered) => {
    expect(filteredByCallSite(widget({ id: "w", view: widgetView }), viewName ? view(viewName) : undefined)).toBe(filtered);
  });

  // Bug: a lookup that throws while the catalog is still loading, so the page crashes on first paint.
  it("looks views, measures and dimensions up by name and answers nothing before the catalog loads", () => {
    expect(viewOf(undefined, "traces")).toBeUndefined();
    expect(measureOf(undefined, "traces")).toBeUndefined();
    expect(hasDimension(undefined, "call_site")).toBe(false);
    expect(hasDimension(view("cases"), "call_site")).toBe(true);
    expect(measureOf(view("traces"), "latency_p95_ms")?.label).toBe("p95 latency");
  });
});

describe("formatting", () => {
  // Bug: a null printed as 0 or "NaN", cents lost under a dollar, milliseconds past a second, raw token counts.
  it.each<[MetricsUnit, number | null | undefined, string]>([
    ["usd", null, "—"],
    ["count", undefined, "—"],
    ["usd", 0.044, "$0.044"],
    ["usd", 0.0441, "$0.044"],
    ["usd", 0, "$0.00"],
    ["usd", 1, "$1.00"],
    ["usd", 12.4, "$12.40"],
    ["usd", 512, "$512"],
    ["ms", 850, "850ms"],
    ["ms", 849.6, "850ms"],
    ["ms", 1000, "1.0s"],
    ["ms", 4200, "4.2s"],
    ["tokens", 1_200_000, "1.2M"],
    ["tokens", 12_400, "12.4K"],
    ["tokens", 950, "950"],
    ["ratio", 0.123, "12.3%"],
    ["ratio", 0, "0.0%"],
    ["count", 1930, "1,930"],
    ["count", 0, "0"],
  ])("formatValuePerUnit (%s %s)", (unit, v, text) => {
    expect(formatValue(unit, v)).toBe(text);
  });

  // Bug: a delta over a zero or missing baseline prints "Infinity%", a count prints a percent, or 0.2% rounds to "+0%".
  it.each<[number | null | undefined, number | null | undefined, MetricsUnit, string]>([
    [10, null, "usd", "—"],
    [null, 10, "usd", "—"],
    [10, 0, "usd", "—"],
    [128.4, 100, "usd", "+28%"],
    [93.4, 100, "usd", "-7%"],
    [100.2, 100, "usd", "0%"],
    [99.8, 100, "usd", "0%"],
    [1284, 1000, "ms", "+28%"],
    [5, 0, "ms", "—"],
    [0.2, 0.1, "ratio", "+100%"],
    [0, 5, "tokens", "-100%"],
    [12, 10, "count", "+2"],
    [10, 12, "count", "-2"],
    [10, 10, "count", "0"],
    [2, 0, "count", "+2"],
    [1200, 0, "count", "+1,200"],
    [null, 3, "count", "—"],
    [3, undefined, "count", "—"],
  ])("deltaTextSignsRoundsAndDashesTheUndefined (%s vs %s, %s)", (current, previous, unit, text) => {
    expect(deltaText(current, previous, unit)).toBe(text);
  });

  const custom = (name: string, label: string, unit: MetricsUnit): MetricsCatalogMeasure => ({
    name, label, unit, gap_fill: "zero", companions: [],
  });

  // Bug: the default tiles print "$0.042 spend per priced trace" instead of the designed copy, "lLM calls", or a
  // tool-calls note that claims LLM calls.
  it.each<[MetricsCatalogMeasure, number | null, string]>([
    [catalogMeasure("llm_calls", "usd_per_trace"), 0.042, "$0.042 per priced trace"],
    [catalogMeasure("traces", "unterminated_traces"), 2, "2 did not finish"],
    [catalogMeasure("traces", "latency_p50_ms"), 1800, "median 1.8s"],
    [catalogMeasure("traces", "latency_p50_ms"), null, "median —"],
    [catalogMeasure("llm_calls", "calls"), 9412, "across 9,412 LLM calls"],
    [catalogMeasure("tool_calls", "calls"), 12, "across 12 tool calls"],
    [catalogMeasure("cases", "open_cases"), 3, "3 still open"],
    [catalogMeasure("traces", "errored_traces"), 1930, "1,930 errored traces"],
    [custom("llm_call_count", "LLM calls", "count"), 1930, "1,930 LLM calls"],
    [custom("usd_per_priced", "Spend per priced trace", "usd"), 0.042, "$0.042 spend per priced trace"],
  ])("noteTextUsesThePerMeasureCopyThenTheGenericRule (%o)", (measure, v, text) => {
    expect(noteText(measure, v)).toBe(text);
  });

  // Bug: "lLM calls" or "P95 latency" mid-sentence.
  it.each<[string, string]>([
    ["Spend per priced trace", "spend per priced trace"],
    ["Call site", "call site"],
    ["LLM calls", "LLM calls"],
    ["p95 latency", "p95 latency"],
    ["", ""],
  ])("noteTextUsesThePerMeasureCopyThenTheGenericRule (lowerFirst %s)", (label, lowered) => {
    expect(lowerFirst(label)).toBe(lowered);
  });
});

describe("companionNotes", () => {
  // Bug: a zero or missing companion prints "0 unpriced calls", a note measure prints twice, or a view without
  // companions borrows another's lines.
  it.each<[string, string, Record<string, number | null>, number, string[], string[]]>([
    [
      "llm_calls above zero",
      "llm_calls",
      { cost_usd: 88.2, unpriced_calls: 1200, unsettled_calls: 3, priced_traces: 1702 },
      1930,
      ["cost_usd", "usd_per_trace"],
      ["1,200 unpriced calls not in this figure", "3 calls in traces still settling"],
    ],
    ["llm_calls at zero", "llm_calls", { unpriced_calls: 0, unsettled_calls: 0 }, 10, [], []],
    ["llm_calls missing or null", "llm_calls", { unpriced_calls: null }, 10, [], []],
    [
      "traces, every line in order",
      "traces",
      { rootless_traces: 3, unterminated_traces: 9, unsettled_traces: 1, unpriced_traces: 4 },
      1402,
      [],
      [
        "4 unpriced traces not in this figure",
        "1 traces still settling",
        "9 of 1,402 traces did not finish",
        "3 traces have no root span",
      ],
    ],
    ["traces skips its own note measure", "traces", { unterminated_traces: 2 }, 50, ["traces", "unterminated_traces"], []],
    ["tool_calls has none", "tool_calls", { errored_calls: 5, latency_n: 9 }, 10, [], []],
    ["cases has none", "cases", { open_cases: 3 }, 10, [], []],
  ])("companionNotesPerViewOnlyAboveZeroAndSkipTheWidgetsOwnMeasures (%s)", (_name, viewName, values, n, skip, lines) => {
    expect(companionNotes(viewName, values, n, skip)).toEqual(lines);
  });
});

describe("unpriced", () => {
  const llmTotal = (n: number) => totalResult("llm_calls", { cost_usd: 1, unpriced_calls: n });
  const llmTable = tableResult("llm_calls", "model", [
    { key: "gpt-5", label: "gpt-5", n: 40, values: { cost_usd: 1, unpriced_calls: 10 } },
    { key: "__other__", label: "Other (2 more)", n: 5, values: { cost_usd: 1, unpriced_calls: 4 } },
  ]);
  const traces = (n: number) => totalResult("traces", { cost_usd: 1, unpriced_traces: n });

  // Bug: the footer adds the Spend tile and the By model table together and reports every unpriced call twice.
  it.each<[string, (MetricsQueryResult | undefined)[], { count: number; noun: string } | null]>([
    ["tiles and tables agree", [llmTotal(14), llmTable, traces(4)], { count: 14, noun: "LLM calls" }],
    ["the largest llm_calls figure", [llmTotal(6), undefined, llmTotal(14)], { count: 14, noun: "LLM calls" }],
    ["traces when llm_calls has none", [llmTotal(0), traces(2), undefined], { count: 2, noun: "traces" }],
    ["traces alone", [traces(4)], { count: 4, noun: "traces" }],
    ["zero everywhere", [llmTotal(0), traces(0), totalResult("cases", { cases: 3 })], null],
    ["nothing loaded", [undefined, undefined], null],
  ])("unpricedTotalTakesTheLargestLlmCallsFigureThenTraces (%s)", (_name, results, total) => {
    expect(unpricedTotal(results)).toEqual(total);
  });

  // Bug: a table or series that reports only its first row's unpriced count, or a view without one that reads NaN.
  it("unpricedTotalTakesTheLargestLlmCallsFigureThenTraces (sums over rows)", () => {
    expect(unpricedOf(llmTable)).toBe(14);
    expect(unpricedOf(traces(4))).toBe(4);
    expect(unpricedOf(totalResult("cases", { cases: 3 }))).toBe(0);
  });
});

describe("series", () => {
  const D1 = "2026-10-11T00:00:00Z";
  const D2 = "2026-10-12T00:00:00Z";

  // Bug: a null value drawn as zero, the fold drawn before a named key, or a dimension-less line labelled "null".
  it("seriesOfKeepsRankOrderPutsOtherLastGapsMissingValuesAndNamesANullKeyByTheMeasure", () => {
    const result = seriesResult("llm_calls", "cost_usd", [
      {
        bucket: D1,
        keys: [
          { key: "chat", label: "Chat support", v: 12.4, n: 412 },
          { key: "__unattributed__", label: "Unattributed", v: 0.31, n: 20 },
          { key: "__other__", label: "Other (7 more)", v: 0.9, n: 58 },
        ],
      },
      {
        bucket: D2,
        keys: [
          { key: "chat", label: "Chat support", v: null, n: 0 },
          { key: "__other__", label: "Other (7 more)", v: 1.1, n: 61 },
        ],
      },
    ]);
    result.rows[4].values = {};
    expect(seriesOf(result, "cost_usd", "Spend")).toEqual<Series[]>([
      {
        key: "chat", label: "Chat support", rank: 1, other: false,
        points: [{ at: Date.parse(D1), v: 12.4, n: 412 }, { at: Date.parse(D2), v: null, n: 0 }],
      },
      {
        key: "__unattributed__", label: "Unattributed", rank: 2, other: false,
        points: [{ at: Date.parse(D1), v: 0.31, n: 20 }, { at: Date.parse(D2), v: null, n: 0 }],
      },
      {
        key: "__other__", label: "Other (7 more)", rank: 3, other: true,
        points: [{ at: Date.parse(D1), v: 0.9, n: 58 }, { at: Date.parse(D2), v: null, n: 61 }],
      },
    ]);

    const p95 = seriesResult("traces", "latency_p95_ms", [
      { bucket: D1, keys: [{ key: null, label: null, v: 8120, n: 1390 }] },
      { bucket: D2, keys: [{ key: null, label: null, v: 7900, n: 1200 }] },
    ]);
    expect(seriesOf(p95, "latency_p95_ms", "p95 latency")).toEqual<Series[]>([
      {
        key: null, label: "p95 latency", rank: 1, other: false,
        points: [{ at: Date.parse(D1), v: 8120, n: 1390 }, { at: Date.parse(D2), v: 7900, n: 1200 }],
      },
    ]);
  });

  // Bug: a number result kept on screen while a time series loads draws its point at NaN.
  it("seriesOfKeepsRankOrderPutsOtherLastGapsMissingValuesAndNamesANullKeyByTheMeasure (a total result)", () => {
    expect(seriesOf(totalResult("traces", { traces: 7 }, 7), "traces", "Traces")).toEqual<Series[]>([
      { key: null, label: "Traces", rank: 1, other: false, points: [{ at: Date.parse(WINDOW_7D.from), v: 7, n: 7 }] },
    ]);
  });

  // Bug: a series past the sixth reads an undefined token, or the fold takes a rank colour.
  it.each<[Pick<Series, "rank" | "other">, string]>([
    [{ rank: 1, other: false }, "var(--color-series-1)"],
    [{ rank: 6, other: false }, "var(--color-series-6)"],
    [{ rank: 9, other: false }, "var(--color-series-6)"],
    [{ rank: 2, other: true }, "var(--color-series-other)"],
  ])("seriesColourMapsRankAndOther (%o)", (s, colour) => {
    expect(seriesColour({ key: "k", label: "k", points: [], ...s })).toBe(colour);
  });

  // Bug: a line drawn through a quiet hour (stepPaths alone joins steps an hour apart), or a finished last bucket drawn
  // dashed.
  it.each<[string, (number | null)[], number, number, { solid: string; dashed: string }]>([
    ["hour data with a null in the middle", [5, null, 7], HOUR, 3 * HOUR, { solid: "M0.0 5.0H1.0M2.0 7.0H3.0", dashed: "" }],
    ["day data ending inside the last bucket", [2, 4], DAY, 34 * HOUR, { solid: "M0.0 2.0H24.0", dashed: "M24.0 2.0H24.0V4.0H48.0" }],
    ["day data ending on a bucket boundary", [2, 4], DAY, 2 * DAY, { solid: "M0.0 2.0H24.0H24.0V4.0H48.0", dashed: "" }],
    ["a null last bucket leaves the earlier step solid", [2, null], DAY, 34 * HOUR, { solid: "M0.0 2.0H24.0", dashed: "" }],
    ["every bucket null", [null, null], HOUR, 2 * HOUR, { solid: "", dashed: "" }],
  ])("seriesPathsSplitsRunsAtNullBucketsAndDashesTheOpenLastStep (%s)", (_name, values, bucketMs, end, paths) => {
    const t0 = Date.parse(D1);
    const points = values.map((v, i) => ({ at: t0 + i * bucketMs, v, n: 1 }));
    expect(seriesPaths(points, bucketMs, t0 + end, (t) => (t - t0) / HOUR, (v) => v)).toEqual(paths);
  });
});

describe("time axis", () => {
  // Bug: a tooltip that drops the hour on hourly data, or a week bucket named by a day the reader cannot place.
  it.each<[string, "hour" | "day" | "week", string]>([
    ["2026-10-11T14:00:00Z", "hour", "Oct 11, 14:00 UTC"],
    ["2026-10-11T00:00:00Z", "hour", "Oct 11, 00:00 UTC"],
    ["2026-10-11T00:00:00Z", "day", "Oct 11"],
    ["2026-10-05T00:00:00Z", "week", "Week of Oct 5"],
  ])("bucketLabelAndTimeTicksPerUnit (bucket %s, %s)", (iso, unit, label) => {
    expect(bucketLabel(iso, unit)).toBe(label);
  });

  const labels = (from: string, to: string, unit: "hour" | "day" | "week" | null) =>
    timeTicks({ from, to }, unit).map((t) => t.label);

  // Bug: a tick every hour crowds the axis, a 90-day axis prints ninety dates, or week ticks fall mid-week.
  it.each<[string, string, "hour" | "day" | "week" | null, string[]]>([
    ["2026-10-17T10:00:00Z", "2026-10-18T10:00:00Z", "hour", ["12:00", "18:00", "00:00", "06:00"]],
    ["2026-10-17T12:00:00Z", "2026-10-18T06:00:00Z", "hour", ["12:00", "18:00", "00:00", "06:00"]],
    ["2026-10-11T10:00:00Z", "2026-10-18T10:00:00Z", "day", ["Oct 12", "Oct 13", "Oct 14", "Oct 15", "Oct 16", "Oct 17", "Oct 18"]],
    [
      "2026-10-04T00:00:00Z", "2026-10-18T00:00:00Z", "day",
      ["Oct 4", "Oct 5", "Oct 6", "Oct 7", "Oct 8", "Oct 9", "Oct 10", "Oct 11", "Oct 12", "Oct 13", "Oct 14", "Oct 15", "Oct 16", "Oct 17", "Oct 18"],
    ],
    ["2026-10-03T00:00:00Z", "2026-10-18T00:00:00Z", "day", ["Oct 3", "Oct 10", "Oct 17"]],
    ["2026-09-18T10:00:00Z", "2026-10-18T10:00:00Z", "day", ["Sep 19", "Sep 26", "Oct 3", "Oct 10", "Oct 17"]],
    ["2026-09-28T00:00:00Z", "2026-10-18T00:00:00Z", "week", ["Sep 28", "Oct 5", "Oct 12"]],
    ["2026-09-30T00:00:00Z", "2026-10-18T00:00:00Z", "week", ["Oct 5", "Oct 12"]],
    ["2026-10-15T10:00:00Z", "2026-10-18T10:00:00Z", null, ["Oct 16", "Oct 17", "Oct 18"]],
  ])("bucketLabelAndTimeTicksPerUnit (ticks %s to %s, %s)", (from, to, unit, expected) => {
    expect(labels(from, to, unit)).toEqual(expected);
  });

  // Bug: a tick labelled 12:00 drawn at the window start instead of at noon.
  it("bucketLabelAndTimeTicksPerUnit (tick positions)", () => {
    expect(timeTicks({ from: "2026-10-17T10:00:00Z", to: "2026-10-17T13:00:00Z" }, "hour")).toEqual([
      { at: Date.parse("2026-10-17T12:00:00Z"), label: "12:00" },
    ]);
  });
});

describe("value axis", () => {
  // Bug: a seconds ladder built from milliseconds ("5000s"), a percent ladder from a 0..1 ratio, dollars without cents.
  it.each<[MetricsUnit, number, number, string[]]>([
    ["ms", 6000, 6, ["0s", "2s", "4s", "6s"]],
    ["ms", 8120, 10, ["0s", "5s", "10s"]],
    ["ms", 1200, 1.5, ["0s", "0.5s", "1s", "1.5s"]],
    ["ratio", 0.3, 30, ["0%", "10%", "20%", "30%"]],
    ["ratio", 0.009, 1, ["0%", "0.5%", "1%"]],
    ["usd", 1.42, 1.5, ["$0.00", "$0.50", "$1.00", "$1.50"]],
    ["usd", 0.0036, 0.004, ["$0.000", "$0.001", "$0.002", "$0.003", "$0.004"]],
    ["tokens", 3_400_000, 4_000_000, ["0", "1M", "2M", "3M", "4M"]],
    ["count", 3, 3, ["0", "1", "2", "3"]],
  ])("axisTicksConvertsUnitsBeforeTheLadderAndRelabels (%s %s)", (unit, max, top, expected) => {
    const axis = axisTicks(unit, max);
    expect(axis.top).toBe(top);
    expect(axis.ticks.map((t) => t.label)).toEqual(expected);
  });

  // Bug: tokens or ratios drawn as bars, or counts as lines.
  it.each<[MetricsUnit, boolean, string]>([
    ["count", true, "count"],
    ["tokens", false, "count"],
    ["usd", false, "usd"],
    ["ms", false, "seconds"],
    ["ratio", false, "percent"],
  ])("countUnitsDrawBarsAndMapToTheirAxis (%s)", (unit, bars, axis) => {
    expect(barsFor(unit)).toBe(bars);
    expect(axisUnit(unit)).toBe(axis);
  });

  // Bug: a chart taller than its card, or an h2 chart with no room to draw.
  it.each<[number, number]>([
    [5, 316],
    [4, 228],
    [3, 140],
    [2, 72],
  ])("chartHeightForFollowsRowsWithAFloor (h%s)", (h, height) => {
    expect(chartHeightFor(h)).toBe(height);
  });
});

describe("dashboard helpers", () => {
  // Bug: a duplicate name the server refuses with DUPLICATE_NAME.
  it.each<[string, string[], string]>([
    ["Default", [], "Default (copy)"],
    ["Default", ["Default (copy)"], "Default (copy 2)"],
    ["Default", ["Default (copy)", "Default (copy 2)"], "Default (copy 3)"],
  ])("copyNameSkipsTakenNames (%s, %o)", (name, taken, copy) => {
    expect(copyName(name, taken)).toBe(copy);
  });

  // Bug: an empty window drawn as a flat zero chart instead of "Nothing ran in this window."
  it.each<[string, MetricsQueryResult, boolean]>([
    ["no rows", tableResult("traces", "call_site", []), true],
    ["the empty total", totalResult("traces", { latency_p95_ms: null, latency_n: 0 }, 0), true],
    ["filled buckets only", seriesResult("traces", "traces", [{ bucket: "2026-10-11T00:00:00Z", keys: [{ key: null, label: null, v: 0, n: 0 }] }]), true],
    ["one row ran", totalResult("traces", { traces: 1 }, 1), false],
  ])("emptyResultIsNoRowsOrAllZeroRows (%s)", (_name, result, empty) => {
    expect(emptyResult(result)).toBe(empty);
  });
});
