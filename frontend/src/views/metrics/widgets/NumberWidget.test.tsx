// SPDX-License-Identifier: Apache-2.0
/*
 * The number tile as the reader meets it: the figure, the change against the previous window, the second measure's
 * note and the companion lines, three lines at most. The words and numbers come from metricsRules; these tests catch
 * the tile that prints the wrong line, colours the delta, or repeats one.
 */
import { fireEvent, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { METRICS_CATALOG, totalResult, widget, WINDOW_7D } from "../../../test/metricsFixtures";
import { currentLocation, renderRoute } from "../../../test/render";
import { viewOf, type Window } from "../metricsRules";
import type { MetricsQueryResult, MetricWidgetDefinition } from "../wire";
import { NumberWidget } from "./NumberWidget";

const BASE = "/orgs/acme/projects/default";

const SPEND = widget({ id: "spend", title: "Spend", measures: ["cost_usd", "usd_per_trace"], compare: "previous_window" });
const TRACES = widget({ id: "traces", title: "Traces", view: "traces", measures: ["traces", "unterminated_traces"] });
const CASES = widget({
  id: "cases-opened", title: "Cases opened", view: "cases", measures: ["cases", "open_cases"], compare: "previous_window",
});

const tile = (w: MetricWidgetDefinition, result: MetricsQueryResult, window: Window = WINDOW_7D) => (
  <NumberWidget key={w.id} widget={w} view={viewOf(METRICS_CATALOG, w.view)} result={result} window={window} basePath={BASE} />
);

function renderTile(w: MetricWidgetDefinition, result: MetricsQueryResult, window: Window = WINDOW_7D) {
  return renderRoute(tile(w, result, window), { route: `${BASE}/metrics` });
}

const delta = () => screen.getByLabelText("Change from the previous window");

describe("NumberWidget", () => {
  // Bug: the hero prints in sans or loses its unit; the delta sits beside it, carries a status colour, or is missing.
  it("printsTheValueTheDeltaLineAndTheNote", () => {
    renderTile(
      SPEND,
      totalResult("llm_calls", { cost_usd: 18.35, usd_per_trace: 0.042 }, 437, { cost_usd: 16.38, usd_per_trace: 0.04 }),
    );

    const value = screen.getByText("$18.35");
    expect(value.className).toContain("font-mono");
    expect(value.className).toContain("text-metric");
    expect(delta().textContent).toBe("+12% vs previous 7 days");
    expect(delta().className).toContain("text-muted");
    expect(delta().className).not.toMatch(/text-(error|success)/);
    expect(screen.getByText("$0.042 per priced trace")).toBeTruthy();
  });

  // Bug: a count's change printed as a percent of a small base ("+20%"), or the custom window named as a preset.
  it("aCountTilePrintsAnAbsoluteDelta", () => {
    renderTile(CASES, totalResult("cases", { cases: 12, open_cases: 3 }, 12, { cases: 10, open_cases: 1 }));
    expect(delta().textContent).toBe("+2 vs previous 7 days");
    expect(screen.getByText("3 still open")).toBeTruthy();

    renderTile(
      { ...CASES, id: "custom" },
      totalResult("cases", { cases: 4, open_cases: 0 }, 4, { cases: 4, open_cases: 0 }),
      { from: "2026-10-15T00:00:00Z", to: "2026-10-17T00:00:00Z" },
    );
    expect(screen.getAllByLabelText("Change from the previous window")[1].textContent).toBe("0 vs previous window");
  });

  // Bug: a missing value prints as 0 or "$NaN"; a tile that never asked for a comparison prints one.
  it("dashesANullValueAndHidesAMissingDelta", () => {
    const p95 = widget({ id: "p95", title: "p95 latency", view: "traces", measures: ["latency_p95_ms", "latency_p50_ms"] });
    renderTile(p95, totalResult("traces", { latency_p95_ms: null, latency_p50_ms: 1800 }));

    expect(screen.getByText("—").className).toContain("text-metric");
    expect(screen.queryByLabelText("Change from the previous window")).toBeNull();
    expect(screen.getByText("median 1.8s")).toBeTruthy();

    renderTile({ ...SPEND, id: "no-previous" }, totalResult("llm_calls", { cost_usd: 3, usd_per_trace: 0.01 }));
    expect(delta().textContent).toBe("— vs previous 7 days");
  });

  // Bug: a spend tile hides the calls its figure leaves out; the Traces tile prints "did not finish" twice.
  it("listsCompanionLinesForTheViewAndNeverRepeatsTheNoteMeasure", () => {
    renderTile(
      SPEND,
      totalResult("llm_calls", { cost_usd: 18.35, usd_per_trace: 0.042, unpriced_calls: 14, unsettled_calls: 3 }, 9412, {
        cost_usd: 18.35, usd_per_trace: 0.042,
      }),
    );
    expect(screen.getByText("$0.042 per priced trace")).toBeTruthy();
    expect(screen.getByText("14 unpriced calls not in this figure")).toBeTruthy();
    expect(screen.getByText("3 calls in traces still settling")).toBeTruthy();
    expect(delta().textContent).toBe("0% vs previous 7 days");

    renderTile(TRACES, totalResult("traces", { traces: 437, unterminated_traces: 2 }, 437));
    expect(screen.getByText("2 did not finish")).toBeTruthy();
    expect(screen.queryByText(/of 437 traces did not finish/)).toBeNull();
  });

  // Bug: a tile with many companions grows past its card and clips the figure.
  it("printsAtMostThreeNoteLines", () => {
    renderTile(
      widget({ id: "t", title: "Traces", view: "traces", measures: ["traces", "usd_per_trace"] }),
      totalResult(
        "traces",
        { traces: 437, usd_per_trace: 0.05, unpriced_traces: 1, unsettled_traces: 2, unterminated_traces: 3, rootless_traces: 4 },
        437,
      ),
    );

    expect(screen.getByText("$0.050 per priced trace")).toBeTruthy();
    expect(screen.getByText("1 unpriced traces not in this figure")).toBeTruthy();
    expect(screen.getByText("2 traces still settling")).toBeTruthy();
    expect(screen.queryByText("3 of 437 traces did not finish")).toBeNull();
    expect(screen.queryByText("4 traces have no root span")).toBeNull();
  });

  // Bug: the cases count links nowhere, or every tile links to Triage.
  it("casesTileLinksToTriage", () => {
    renderRoute(
      <>
        {tile(CASES, totalResult("cases", { cases: 12, open_cases: 3 }, 12, { cases: 10, open_cases: 1 }))}
        {tile(TRACES, totalResult("traces", { traces: 437, unterminated_traces: 0 }, 437))}
      </>,
      { route: `${BASE}/metrics` },
    );

    expect(screen.getAllByRole("link").map((l) => l.textContent)).toEqual(["12"]);
    fireEvent.click(screen.getByRole("link", { name: "12" }));
    expect(currentLocation()).toBe(`${BASE}/triage`);
  });

  // Bug: a measure the catalog no longer lists crashes the tile instead of printing its wire name.
  it("anUnknownMeasurePrintsItsWireName", () => {
    renderTile(
      widget({ id: "odd", title: "Odd", measures: ["mystery", "made_up"] }),
      totalResult("llm_calls", { mystery: 1234, made_up: 5 }),
    );

    expect(screen.getByText("1,234")).toBeTruthy();
    expect(screen.getByText("5 made_up")).toBeTruthy();
  });
});
