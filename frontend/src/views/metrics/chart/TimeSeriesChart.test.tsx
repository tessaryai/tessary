// SPDX-License-Identifier: Apache-2.0
/*
 * The multi-series chart as a reader meets it at the 520 px jsdom width: its name, the legend, the hover and keyboard
 * read-outs and which kind of chart it is. Path geometry is proven in metricsRules.test.ts (seriesPaths), so these
 * tests assert roles and text only.
 */
import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { seriesResult, WINDOW_7D } from "../../../test/metricsFixtures";
import { seriesOf, timeTicks } from "../metricsRules";
import type { MetricsUnit } from "../wire";
import { TimeSeriesChart } from "./TimeSeriesChart";

const D16 = "2026-10-16T00:00:00Z";
const D17 = "2026-10-17T00:00:00Z";

const KEYS = [
  { key: "chat", label: "chat" },
  { key: "summarize", label: "summarize" },
  { key: "search-rerank", label: "search-rerank" },
  { key: "extract-fields", label: "extract-fields" },
  { key: "classify-intent", label: "classify-intent" },
  { key: "__other__", label: "Other (7 more)" },
];

/** Six keys over two days; chat has no value on the 16th, so that bucket is a gap in its line. */
const sixSeries = () =>
  seriesOf(
    seriesResult("llm_calls", "cost_usd", [
      { bucket: D16, keys: KEYS.map((k, i) => ({ ...k, v: k.key === "chat" ? null : 1 + i / 10, n: 10 + i })) },
      { bucket: D17, keys: KEYS.map((k, i) => ({ ...k, v: 2.2 - i / 10, n: 20 + i })) },
    ]),
    "cost_usd",
    "Spend",
  );

const singleSeries = (measure = "latency_p95_ms", v16: number | null = 4200, v17: number | null = 4800) =>
  seriesOf(
    seriesResult("traces", measure, [
      { bucket: D16, keys: [{ key: null, label: null, v: v16, n: 30 }] },
      { bucket: D17, keys: [{ key: null, label: null, v: v17, n: 41 }] },
    ]),
    measure,
    "p95 latency",
  );

function renderChart(props: { title?: string; unit?: MetricsUnit; series?: ReturnType<typeof sixSeries>; bars?: boolean }) {
  render(
    <TimeSeriesChart
      title={props.title ?? "Spend by call site"}
      unit={props.unit ?? "usd"}
      window={WINDOW_7D}
      granularity="day"
      series={props.series ?? sixSeries()}
      height={316}
      bars={props.bars ?? false}
    />,
  );
  return screen.getByRole("group", { name: new RegExp(props.title ?? "Spend by call site") });
}

describe("TimeSeriesChart", () => {
  // Bug: the legend loses the server's rank order or draws the fold before a named key; or every ranked line is the
  // same solid stroke, so in greyscale series 3 to 6 cannot be told apart by line or by legend swatch.
  it("namesTheChartAndGivesEachRankItsOwnDashInTheLinesAndTheLegend", () => {
    const chart = renderChart({});

    expect(chart.getAttribute("aria-label")).toBe(
      "Spend by call site, 2 buckets, 6 series. The arrow keys move through the buckets.",
    );
    const items = within(screen.getByRole("list", { name: "Legend" })).getAllByRole("listitem");
    expect(items.map((i) => i.textContent)).toEqual(KEYS.map((k) => k.label));
    const swatch = (item: HTMLElement) => item.querySelector("line")!;
    const lines = [...chart.querySelectorAll("g > path:first-child")];
    expect(lines.map((l) => l.getAttribute("stroke"))).toEqual([1, 2, 3, 4, 5].map((r) => `var(--color-series-${r})`)
      .concat("var(--color-series-other)"));
    const dashes = lines.map((l) => l.getAttribute("stroke-dasharray"));
    expect(new Set(dashes).size).toBe(6);
    expect(dashes[0]).toBeNull();
    expect(items.map((i) => swatch(i).getAttribute("stroke-dasharray"))).toEqual(dashes);
    expect(items.map((i) => swatch(i).getAttribute("stroke"))).toEqual(lines.map((l) => l.getAttribute("stroke")));
    expect(within(chart).getByText("Oct 11")).toBeTruthy();
    expect(within(chart).getByText("Oct 18")).toBeTruthy();
    expect(within(chart).getByText("$3.00")).toBeTruthy();
  });

  // Bug: a legend that wraps to a second line pushes its last items past the card's bottom edge, which clips them; or
  // the plot stays short after the legend goes away (the editor switching to one series).
  it("aWrappedLegendTakesItsSecondLineFromThePlot", () => {
    vi.spyOn(HTMLElement.prototype, "clientHeight", "get").mockReturnValue(40);
    const props = { title: "Spend", unit: "usd" as const, window: WINDOW_7D, granularity: "day" as const, height: 316, bars: false };
    const { rerender } = render(<TimeSeriesChart {...props} series={sixSeries()} />);
    const chart = () => screen.getByRole("group", { name: /^Spend/ });

    expect(chart().getAttribute("viewBox")).toBe("0 0 520 294");
    rerender(<TimeSeriesChart {...props} series={singleSeries()} />);
    expect(chart().getAttribute("viewBox")).toBe("0 0 520 316");
  });

  // Bug: a 90-day window labels every week along a 464 px plot, so the dates print on top of each other.
  it("aLongWindowLabelsEveryOtherWeekWhereEveryWeekWouldCollide", () => {
    const window = { from: "2026-07-20T10:00:00Z", to: "2026-10-18T10:00:00Z" };
    const weeks = timeTicks(window, "week").map((t) => t.label);
    render(<TimeSeriesChart title="p95" unit="ms" window={window} granularity="week" series={[]} height={316} bars={false} />);
    const chart = screen.getByRole("group", { name: /^p95/ });

    expect(weeks).toHaveLength(12);
    expect(weeks.filter((w) => within(chart).queryByText(w) != null)).toEqual(weeks.filter((_, i) => i % 2 === 0));
  });

  // Bug: a lone p95 line carries a legend that names the measure the title already names.
  it("aSingleSeriesHasNoLegend", () => {
    const chart = renderChart({ title: "p95 latency over time", unit: "ms", series: singleSeries() });

    expect(chart.getAttribute("aria-label")).toContain("2 buckets, 1 series.");
    expect(screen.queryByRole("list", { name: "Legend" })).toBeNull();
    expect(within(chart).getByText("4s")).toBeTruthy();
  });

  // Bug: the hover reads one series, the wrong bucket, or no row count; the keyboard read-out talks while a mouse moves.
  it("hoverAndArrowKeysReadTheBucketWithEverySeriesAndTheRowCount", () => {
    const chart = renderChart({});
    vi.spyOn(chart, "getBoundingClientRect").mockReturnValue({ left: 0, width: 520 } as DOMRect);
    const live = screen.getByRole("status");

    fireEvent.mouseMove(chart, { clientX: 400 });
    const tip = () => [...screen.getByRole("tooltip").children].map((c) => c.textContent);
    expect(tip()).toEqual([
      "Oct 16", "chat: —", "summarize: $1.10", "search-rerank: $1.20", "extract-fields: $1.30",
      "classify-intent: $1.40", "Other (7 more): $1.50", "75 rows",
    ]);
    expect(live.textContent).toBe("");
    fireEvent.mouseMove(chart, { clientX: 505 });
    expect(tip()[0]).toBe("Oct 17");
    expect(tip()[1]).toBe("chat: $2.20");
    expect(tip().at(-1)).toBe("135 rows");
    fireEvent.mouseLeave(chart);
    expect(screen.queryByRole("tooltip")).toBeNull();

    fireEvent.focus(chart);
    expect(live.textContent).toBe(
      "Oct 17, chat: $2.20, summarize: $2.10, search-rerank: $2.00, extract-fields: $1.90, classify-intent: $1.80, " +
        "Other (7 more): $1.70, 135 rows",
    );
    fireEvent.keyDown(chart, { key: "ArrowLeft" });
    expect(live.textContent).toMatch(/^Oct 16, chat: —/);
    fireEvent.keyDown(chart, { key: "ArrowLeft" });
    expect(live.textContent).toMatch(/^Oct 16/);
    fireEvent.keyDown(chart, { key: "End" });
    expect(live.textContent).toMatch(/^Oct 17/);
    fireEvent.keyDown(chart, { key: "Home" });
    expect(live.textContent).toMatch(/^Oct 16/);
    fireEvent.keyDown(chart, { key: "ArrowRight" });
    expect(live.textContent).toMatch(/^Oct 17/);
    expect(screen.getByRole("group", { description: /^Oct 17/ })).toBe(chart);
    // Bug: the chart swallows Tab, so a keyboard reader cannot leave it.
    expect(fireEvent.keyDown(chart, { key: "Tab" })).toBe(true);
    fireEvent.blur(chart);
    expect(live.textContent).toBe("");
    expect(screen.queryByRole("tooltip")).toBeNull();
  });

  // Bug: a bucket with no value is read and drawn as zero; one row reads "1 rows".
  it("aNullBucketDrawsAGapNotAZero", () => {
    const chart = renderChart({ title: "p95 latency over time", unit: "ms", series: singleSeries("latency_p95_ms", null) });

    fireEvent.focus(chart);
    fireEvent.keyDown(chart, { key: "Home" });
    expect(screen.getByRole("status").textContent).toBe("Oct 16, p95 latency: —, 30 rows");

    const one = seriesOf(
      seriesResult("traces", "traces", [{ bucket: D16, keys: [{ key: null, label: null, v: 1, n: 1 }] }]),
      "traces",
      "Traces",
    );
    const counted = renderChart({ title: "Traces over time", unit: "count", series: one, bars: true });
    fireEvent.focus(counted);
    expect(screen.getAllByRole("status")[1].textContent).toBe("Oct 16, Traces: 1, 1 row");
  });

  // Bug: a count drawn as a step line, or a spend drawn as bars; a screen reader cannot tell the two apart.
  it("countUnitsDrawBarChartsAndOthersDrawLineCharts", () => {
    const counts = seriesOf(
      seriesResult("llm_calls", "calls", [
        { bucket: D16, keys: [{ key: "chat", label: "chat", v: 12, n: 12 }, { key: "__other__", label: "Other (2 more)", v: 4, n: 4 }] },
        { bucket: D17, keys: [{ key: "chat", label: "chat", v: null, n: 0 }, { key: "__other__", label: "Other (2 more)", v: 6, n: 6 }] },
      ]),
      "calls",
      "LLM calls",
    );
    const bars = renderChart({ title: "LLM calls by call site", unit: "count", series: counts, bars: true });
    expect(bars.getAttribute("aria-roledescription")).toBe("bar chart");
    // Bug: the fold drawn at half opacity, under the 3:1 a series value must clear on the card.
    const other = [...bars.querySelectorAll("rect[stroke]")];
    expect(other).toHaveLength(2);
    for (const r of other) {
      expect(r.getAttribute("fill")).toBe("none");
      expect(r.getAttribute("stroke")).toBe("var(--color-series-other)");
      expect(r.getAttribute("fill-opacity")).toBeNull();
    }
    expect(renderChart({ title: "Traces over time", unit: "count", series: singleSeries("traces", 30, 41), bars: true })
      .getAttribute("aria-roledescription")).toBe("bar chart");
    expect(renderChart({ title: "Spend by call site" }).getAttribute("aria-roledescription")).toBe("line chart");
  });

  // Bug: a widget with no keys crashes the chart or draws a legend with nothing in it.
  it("noSeriesRendersAxesAndNoLegend", () => {
    const chart = renderChart({ title: "Errored traces", unit: "count", series: [], bars: true });

    expect(chart.getAttribute("aria-label")).toBe(
      "Errored traces, 0 buckets, 0 series. The arrow keys move through the buckets.",
    );
    expect(within(chart).getByText("0")).toBeTruthy();
    expect(within(chart).getByText("Oct 11")).toBeTruthy();
    expect(screen.queryByRole("list", { name: "Legend" })).toBeNull();
    vi.spyOn(chart, "getBoundingClientRect").mockReturnValue({ left: 0, width: 520 } as DOMRect);
    fireEvent.mouseMove(chart, { clientX: 300 });
    fireEvent.focus(chart);
    expect(fireEvent.keyDown(chart, { key: "ArrowLeft" })).toBe(true);
    expect(screen.queryByRole("tooltip")).toBeNull();
    expect(screen.getByRole("status").textContent).toBe("");
  });
});
