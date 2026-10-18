// SPDX-License-Identifier: Apache-2.0
/*
 * The time-series widget around its chart: the height its rows give the chart, the note that says keys were folded
 * into Other, and the granularity caption in the card's title bar.
 */
import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { METRICS_CATALOG, seriesResult, widget, WINDOW_7D } from "../../../test/metricsFixtures";
import { viewOf } from "../metricsRules";
import type { MetricsQueryResult, MetricWidgetDefinition } from "../wire";
import { WidgetCard } from "../WidgetCard";
import { TimeSeriesWidget } from "./TimeSeriesWidget";

const BASE = "/orgs/acme/projects/default";

const BY_CALL_SITE = widget({
  id: "spend-by-call-site", title: "Spend by call site", chart: "timeseries", measures: ["cost_usd"],
  dimension: "call_site", top_n: 5, x: 0, y: 5, w: 6, h: 5,
});

const keyed = (granularity: "hour" | "day" | "week" = "day") =>
  seriesResult(
    "llm_calls",
    "cost_usd",
    ["2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z"].map((bucket) => ({
      bucket,
      keys: [
        { key: "chat", label: "chat", v: 1.2, n: 10 },
        { key: "__other__", label: "Other (7 more)", v: 0.4, n: 3 },
      ],
    })),
    granularity,
  );

const folded = (): MetricsQueryResult => ({ ...keyed(), top_n: { shown: 5, total_keys: 12 } });

function renderBody(w: MetricWidgetDefinition, result: MetricsQueryResult) {
  render(<TimeSeriesWidget widget={w} view={viewOf(METRICS_CATALOG, w.view)} result={result} window={WINDOW_7D} basePath={BASE} />);
}

const chartHeight = (name: RegExp) => screen.getByRole("group", { name }).getAttribute("viewBox")!.split(" ")[3];

describe("TimeSeriesWidget", () => {
  // Bug: the chart overflows or underfills its card at a stored height; a folded chart says nothing about the rest.
  it("drawsTheChartAtTheHeightOfItsRowsAndNotesTheFold", () => {
    renderBody(BY_CALL_SITE, folded());

    expect(screen.getByText("Top 5 of 12 by call site; the rest are in Other")).toBeTruthy();
    // h5 is 316 px of chart; the fold note takes its 18 px line and 8 px gap from it, so the card does not clip.
    expect(chartHeight(/^Spend by call site/)).toBe("290");
    expect(screen.getByRole("group", { name: /^Spend by call site/ }).getAttribute("aria-roledescription")).toBe(
      "line chart",
    );
    expect(within(screen.getByRole("list", { name: "Legend" })).getAllByRole("listitem").map((i) => i.textContent))
      .toEqual(["chat", "Other (7 more)"]);
  });

  // Bug: a note under every keyed chart, or a chart that shrinks when nothing was folded.
  it("noFoldNoteWhenNothingWasFolded", () => {
    renderBody(BY_CALL_SITE, keyed());
    renderBody(
      widget({ id: "calls", title: "Calls", chart: "timeseries", measures: ["calls"], h: 3 }),
      seriesResult("llm_calls", "calls", [{ bucket: "2026-10-17T00:00:00Z", keys: [{ key: null, label: null, v: 5, n: 5 }] }]),
    );

    expect(screen.queryByText(/the rest are in Other/)).toBeNull();
    expect(chartHeight(/^Spend by call site/)).toBe("316");
    expect(chartHeight(/^Calls/)).toBe("140");
    expect(screen.getByRole("group", { name: /^Calls/ }).getAttribute("aria-roledescription")).toBe("bar chart");
  });

  // Bug: the reader cannot tell a daily chart from an hourly one, or reads the buckets in local time.
  it("captionsTheGranularityInUtc", () => {
    const card = (g: "hour" | "day" | "week", id: string) => (
      <WidgetCard
        key={id}
        widget={{ ...BY_CALL_SITE, id, title: id }}
        view={viewOf(METRICS_CATALOG, "llm_calls")}
        state={{ status: "ready", result: keyed(g), stale: false }}
        window={WINDOW_7D}
        basePath={BASE}
        callSite={null}
        editing={false}
      />
    );
    render(
      <>
        {card("hour", "Hourly chart")}
        {card("day", "Daily chart")}
        {card("week", "Weekly chart")}
      </>,
    );

    expect(within(screen.getByRole("region", { name: "Hourly chart" })).getByText("Hourly, UTC")).toBeTruthy();
    expect(within(screen.getByRole("region", { name: "Daily chart" })).getByText("Daily, UTC")).toBeTruthy();
    expect(within(screen.getByRole("region", { name: "Weekly chart" })).getByText("Weekly, UTC")).toBeTruthy();
  });
});
