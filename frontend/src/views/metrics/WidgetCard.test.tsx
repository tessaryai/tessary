// SPDX-License-Identifier: Apache-2.0
/*
 * The widget frame in every state a read can be in: loading, refused, busy or timed out, empty, ready and stale, and a
 * stored widget whose view the catalog no longer serves; plus the title bar's two looks and the edit-mode chrome.
 */
import { fireEvent, screen, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../../api/types";
import {
  EMPTY_METRICS_RESULT, METRICS_CATALOG, seriesResult, tableResult, totalResult, widget, WINDOW_7D,
} from "../../test/metricsFixtures";
import { renderRoute } from "../../test/render";
import { viewOf } from "./metricsRules";
import type { MetricWidgetDefinition } from "./wire";
import { type WidgetState, WidgetCard } from "./WidgetCard";

const BASE = "/orgs/acme/projects/default";

const SPEND = widget({ id: "spend", title: "Spend" });
const ready = (result = totalResult("llm_calls", { cost_usd: 12.4 }), stale = false): WidgetState => ({
  status: "ready", result, stale,
});

function card(
  w: MetricWidgetDefinition,
  state: WidgetState,
  more: { callSite?: string | null; editing?: boolean; menu?: ReactNode; sub?: string; view?: string | null } = {},
) {
  return (
    <WidgetCard
      key={w.id}
      widget={w}
      view={more.view === null ? undefined : viewOf(METRICS_CATALOG, more.view ?? w.view)}
      state={state}
      window={WINDOW_7D}
      basePath={BASE}
      callSite={more.callSite ?? null}
      editing={more.editing ?? false}
      menu={more.menu}
      sub={more.sub}
    />
  );
}

const renderCards = (...cards: ReactNode[]) => renderRoute(<>{cards}</>, { route: `${BASE}/metrics` });
const region = (name: string) => screen.getByRole("region", { name });

describe("WidgetCard", () => {
  // Bug: a loading card tells a screen reader nothing about which widget waits.
  it("loadingNamesTheWidget", () => {
    renderCards(card(SPEND, { status: "loading" }));
    expect(within(region("Spend")).getByRole("status", { name: "Loading Spend" })).toBeTruthy();
  });

  // Bug: a refused read prints "Something went wrong" or loses the server's code.
  it("errorRendersTheCodeAndDetail", () => {
    const error = new ApiError(400, { code: "METRICS.UNKNOWN_MEASURE", message: "No measure named 'x' on llm_calls" });
    renderCards(card(SPEND, { status: "error", error, onRetry: vi.fn() }));

    const alert = within(region("Spend")).getByRole("alert");
    expect(alert.textContent).toBe("METRICS.UNKNOWN_MEASURE: No measure named 'x' on llm_calls");
    expect(screen.queryByRole("button", { name: "Try again" })).toBeNull();
  });

  // Bug: a busy or slow server reads as a red failure with no way to retry, or the retry button does nothing.
  it("busyAndTimedOutShowTheMessageTheCodeAndTryAgain", () => {
    const busyRetry = vi.fn();
    const slowRetry = vi.fn();
    const busy = new ApiError(503, {
      code: "METRICS.BUSY", message: "Metrics is busy. Other reads are running; try again in a moment.",
    });
    const slow = new ApiError(504, {
      code: "METRICS.TIMED_OUT", message: "Query took too long. Narrow the window or pick a coarser granularity.",
    });
    renderCards(
      card(SPEND, { status: "error", error: busy, onRetry: busyRetry }),
      card({ ...SPEND, id: "slow", title: "Slow" }, { status: "error", error: slow, onRetry: slowRetry }),
    );

    const busyAlert = within(region("Spend")).getByRole("alert");
    const detail = within(busyAlert).getByText("Metrics is busy. Other reads are running; try again in a moment.");
    expect(detail.className).toContain("text-fg-secondary");
    expect(within(busyAlert).getByText("METRICS.BUSY").className).toContain("font-mono");
    fireEvent.click(within(busyAlert).getByRole("button", { name: "Try again" }));
    expect(busyRetry).toHaveBeenCalledTimes(1);

    const slowAlert = within(region("Slow")).getByRole("alert");
    expect(within(slowAlert).getByText("METRICS.TIMED_OUT")).toBeTruthy();
    expect(slowAlert.className).not.toContain("text-error");
    fireEvent.click(within(slowAlert).getByRole("button", { name: "Try again" }));
    expect(slowRetry).toHaveBeenCalledTimes(1);
    expect(busyRetry).toHaveBeenCalledTimes(1);
  });

  // Bug: an empty window draws an empty axis or an empty table instead of saying nothing ran.
  it("anEmptyChartOrTableSaysNothingRan", () => {
    const chart = widget({ id: "chart", title: "Spend over time", chart: "timeseries" });
    const table = widget({ id: "table", title: "By model", chart: "table", dimension: "model" });
    renderCards(card(chart, ready(EMPTY_METRICS_RESULT)), card(table, ready(EMPTY_METRICS_RESULT)));
    expect(within(region("Spend over time")).getByText("Nothing ran in this window.")).toBeTruthy();
    expect(within(region("By model")).getByText("Nothing ran in this window.")).toBeTruthy();
  });

  // Bug: a number tile over an empty window says "Nothing ran" and hides the 0 and its delta, so cases dropping from
  // 5 to none reads as no news.
  it("anEmptyNumberShowsZeroAndItsDelta", () => {
    const cases = widget({ id: "cases", title: "Cases opened", view: "cases", measures: ["cases"], compare: "previous_window" });
    renderCards(card(cases, ready(totalResult("cases", { cases: 0 }, 0, { cases: 5 }))));
    expect(within(region("Cases opened")).getByText("0")).toBeTruthy();
    expect(within(region("Cases opened")).getByLabelText("Change from the previous window").textContent)
      .toBe("-5 vs previous 7 days");
    expect(screen.queryByText("Nothing ran in this window.")).toBeNull();
  });

  // Bug: a widget rendered by the wrong body, or a chart and a table that both draw as numbers.
  it("readyDispatchesByChart", () => {
    const chart = widget({ id: "chart", title: "Spend over time", chart: "timeseries" });
    const table = widget({ id: "table", title: "By model", chart: "table", dimension: "model" });
    renderCards(
      card(SPEND, ready()),
      card(chart, ready(seriesResult("llm_calls", "cost_usd", [
        { bucket: "2026-10-17T00:00:00Z", keys: [{ key: null, label: null, v: 3, n: 4 }] },
      ]))),
      card(table, ready(tableResult("llm_calls", "model", [{ key: "gpt-4o", label: "gpt-4o", n: 3, values: { cost_usd: 2 } }]))),
    );

    expect(within(region("Spend")).getByText("$12.40")).toBeTruthy();
    expect(within(region("Spend over time")).getByRole("group", { name: /^Spend over time/ })).toBeTruthy();
    expect(within(region("By model")).getByRole("table")).toBeTruthy();
    expect(within(region("By model")).getByText("gpt-4o")).toBeTruthy();
  });

  // Bug: a refetch for a new window shows the old figures as if they were current.
  it("staleIsBusyAndDimmed", () => {
    renderCards(card(SPEND, ready(undefined, true)), card({ ...SPEND, id: "fresh", title: "Fresh" }, ready()));

    const stale = within(region("Spend")).getByText("$12.40").closest("[aria-busy]") as HTMLElement;
    expect(stale.getAttribute("aria-busy")).toBe("true");
    expect(stale.className).toContain("opacity-40");
    expect(within(region("Fresh")).getByText("$12.40").closest("[aria-busy]")).toBeNull();
  });

  // Bug: a stored widget whose view was removed crashes the dashboard instead of naming the view.
  it("unknownViewNamesIt", () => {
    renderCards(card(widget({ id: "gone", title: "Gone", view: "spans" }), ready(), { view: null }));
    expect(within(region("Gone")).getByRole("alert").textContent).toBe("No metrics view named 'spans'");
  });

  // Bug: a tool-call widget looks filtered by the call-site chip it cannot honour, or every widget says it is not.
  it("notFilteredNoteOnlyWithACallSiteAndAViewWithoutIt", () => {
    const tools = widget({ id: "tools", title: "Tool calls", view: "tool_calls", measures: ["calls"] });
    renderCards(
      card(tools, ready(totalResult("tool_calls", { calls: 4 })), { callSite: "chat" }),
      card(SPEND, ready(), { callSite: "chat" }),
      card({ ...tools, id: "unfiltered", title: "Unfiltered" }, ready(totalResult("tool_calls", { calls: 4 }))),
    );

    expect(within(region("Tool calls")).getByText("Not filtered by call site")).toBeTruthy();
    expect(within(region("Spend")).queryByText("Not filtered by call site")).toBeNull();
    expect(within(region("Unfiltered")).queryByText("Not filtered by call site")).toBeNull();
  });

  // Bug: in edit mode a call-site table still says "Select a row to open its traces" while its rows open nothing.
  it("aTableOffersNoRowLinksInEditMode", () => {
    const table = widget({ id: "table", title: "By call site", chart: "table", view: "traces", measures: ["traces"], dimension: "call_site" });
    const result = tableResult("traces", "call_site", [{ key: "chat", label: "chat", n: 3, values: { traces: 3 } }]);
    renderCards(card(table, ready(result)), card({ ...table, id: "edit", title: "Editing" }, ready(result), { editing: true }));
    expect(within(region("By call site")).getByText("Select a row to open its traces")).toBeTruthy();
    expect(within(region("Editing")).queryByText("Select a row to open its traces")).toBeNull();
    expect(within(region("Editing")).queryByRole("row", { name: "Open traces for chat" })).toBeNull();
  });

  // Bug: edit mode gives no handle to drag by and no menu; read mode shows a grip that does nothing.
  it("editModeShowsTheGripTheDashedOutlineAndTheMenu", () => {
    renderCards(
      card(SPEND, ready(), { editing: true, menu: <button type="button">Spend actions</button> }),
      card({ ...SPEND, id: "read", title: "Read" }, ready()),
    );

    const grip = within(region("Spend")).getByTitle("Drag to move");
    expect(grip.parentElement!.className).toContain("metrics-drag-handle");
    expect(region("Spend").className).toContain("border-dashed");
    expect(within(region("Spend")).getByRole("button", { name: "Spend actions" })).toBeTruthy();

    expect(within(region("Read")).queryByTitle("Drag to move")).toBeNull();
    expect(region("Read").className).not.toContain("border-dashed");
    expect(within(region("Read")).getByRole("heading", { name: "Read" }).parentElement!.className).not.toContain(
      "metrics-drag-handle",
    );
  });

  // Bug: a tile's title competes with its figure, or a chart's title shrinks to a label; the caption goes missing.
  it("tilesUseTheEyebrowTitleAndChartsTheHeading", () => {
    const chart = widget({ id: "chart", title: "Spend over time", chart: "timeseries" });
    renderCards(
      card(SPEND, ready()),
      card(chart, ready(seriesResult("llm_calls", "cost_usd", [
        { bucket: "2026-10-17T00:00:00Z", keys: [{ key: null, label: null, v: 3, n: 4 }] },
      ])), { sub: "Last 7 days" }),
    );

    const tile = within(region("Spend")).getByRole("heading", { level: 3, name: "Spend" });
    expect(tile.className).toContain("text-label");
    expect(tile.className).toContain("uppercase");
    const heading = within(region("Spend over time")).getByRole("heading", { level: 3, name: "Spend over time" });
    expect(heading.className).toContain("text-h3");
    expect(within(region("Spend over time")).getByText("Last 7 days")).toBeTruthy();
    expect(within(region("Spend over time")).queryByText("Daily, UTC")).toBeNull();
  });

  // Bug: a widget saved with a blank title renders untitled.
  it("titleFallsBackToTheAutomaticOne", () => {
    renderCards(
      card(widget({ id: "auto", title: null, chart: "table", dimension: "call_site" }), { status: "loading" }),
    );
    expect(screen.getByRole("region", { name: "Spend by call site" })).toBeTruthy();
    expect(screen.getByRole("status", { name: "Loading Spend by call site" })).toBeTruthy();
  });
});
