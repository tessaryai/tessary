// SPDX-License-Identifier: Apache-2.0
/*
 * The widget editor rail. The bugs worth catching: a control shown for a chart that cannot use it, an invalid draft
 * that still reads or saves, a preview that asks for another window or drops the call-site filter, a new widget saved
 * at the wrong size or under an id already taken, and an edit that moves or renames the widget it edits.
 */
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../../../api/types";
import { api } from "../../../test/api";
import { DEFAULT_DASHBOARD, METRICS_CATALOG, resultFor, WINDOW_7D } from "../../../test/metricsFixtures";
import { renderRoute } from "../../../test/render";
import { widgetLimiter } from "../metricsQueries";
import type { RangeState, Window } from "../metricsRules";
import type { MetricsQueryRequest, MetricWidgetDefinition } from "../wire";
import { WidgetEditor } from "./WidgetEditor";

vi.mock("../../../tenant/TenantContext", () => import("../../../test/api"));

const WIDGETS = DEFAULT_DASHBOARD.definition.widgets;
const SPEND_BY_CALL_SITE = WIDGETS.find((w) => w.id === "spend-by-call-site")!;
const SEVEN_DAYS: RangeState = { kind: "preset", key: "7d" };

beforeEach(() => {
  api.queryMetrics.mockImplementation((req: MetricsQueryRequest) => Promise.resolve(resultFor(req)));
});
afterEach(() => widgetLimiter.reset());

function renderEditor(
  over: {
    mode?: "add" | "edit";
    widget?: MetricWidgetDefinition | null;
    widgets?: MetricWidgetDefinition[];
    callSite?: string | null;
    range?: RangeState;
    window?: Window;
  } = {},
) {
  const onSave = vi.fn();
  const onClose = vi.fn();
  renderRoute(
    <WidgetEditor
      open
      mode={over.mode ?? "add"}
      widget={over.widget ?? null}
      catalog={METRICS_CATALOG}
      widgets={over.widgets ?? WIDGETS}
      range={over.range ?? SEVEN_DAYS}
      window={over.window ?? WINDOW_7D}
      callSite={over.callSite ?? null}
      basePath="/orgs/acme/projects/default"
      onSave={onSave}
      onClose={onClose}
    />,
  );
  return { onSave, onClose };
}

const rail = () => screen.getByRole("dialog", { name: /widget$/ });
const select = (label: string | RegExp) => within(rail()).getByLabelText(label) as HTMLSelectElement;
const chart = (label: string) =>
  fireEvent.click(within(within(rail()).getByRole("group", { name: "Chart" })).getByRole("button", { name: label }));
const pick = (label: string | RegExp, value: string) => fireEvent.change(select(label), { target: { value } });
const compare = () => within(rail()).getByRole("switch", { name: "Compare with previous window" }) as HTMLButtonElement;
const save = (name: string) => within(rail()).getByRole("button", { name }) as HTMLButtonElement;
const preview = () => within(rail()).getByRole("region", { name: "Preview" });
const lastRequest = (): MetricsQueryRequest => api.queryMetrics.mock.calls.at(-1)![0];

describe("WidgetEditor", () => {
  // Bug: a number offers a breakdown it cannot draw, or hides the note and compare it can.
  it("addStartsEmptyAndShowsTheControlsForANumber", async () => {
    renderEditor();
    expect(within(rail()).getByText("Add widget", { selector: "div" })).toBeTruthy();
    expect(within(rail()).getByText("Leave blank to name it from the settings.")).toBeTruthy();
    expect((within(rail()).getByLabelText("Title") as HTMLInputElement).placeholder).toBe("Traces");
    expect(within(within(rail()).getByRole("group", { name: "Chart" })).getByRole("button", { name: "Number" })
      .getAttribute("aria-pressed")).toBe("true");
    expect([select("View").value, select("Measure").value, select("Note").value]).toEqual(["traces", "traces", ""]);
    expect(within(rail()).getByText("Shown under the number.")).toBeTruthy();
    expect(within(rail()).queryByLabelText("Break down by")).toBeNull();
    expect(within(rail()).queryByLabelText("Top N")).toBeNull();
    expect(compare().disabled).toBe(false);
    expect(within(rail()).queryByText("Available for Number charts.")).toBeNull();
    expect(save("Add widget").disabled).toBe(false);
    expect(await within(preview()).findByText("1")).toBeTruthy();
  });

  // Bug: compare is offered on a time series, which the server refuses with COMPARE_NEEDS_NO_AXIS.
  it("switchingToATimeSeriesDisablesCompareWithItsHint", () => {
    renderEditor();
    fireEvent.click(compare());
    expect(compare().getAttribute("aria-checked")).toBe("true");
    chart("Time series");
    expect(compare().disabled).toBe(true);
    expect(compare().getAttribute("aria-checked")).toBe("false");
    expect(within(rail()).getByText("Available for Number charts.")).toBeTruthy();
    expect(within(rail()).queryByLabelText("Note")).toBeNull();
    expect(select("Break down by").value).toBe("");
    expect([...select("Top N").options].map((o) => o.textContent)).toEqual(["Default", "1", "2", "3", "4", "5"]);
  });

  // Bug: a table hides its breakdown or its extra measures.
  it("switchingToATableShowsDimensionTopNAndAddMeasure", () => {
    renderEditor();
    chart("Table");
    expect(select("Break down by")).toBeTruthy();
    expect([...select("Top N").options].map((o) => o.value)).toEqual(["", "5", "10", "20", "50", "100"]);
    expect(within(rail()).getByRole("button", { name: "Add measure" })).toBeTruthy();
    pick("Break down by", "user");
    expect(within(rail()).getByText("Many distinct values; the chart keeps the top N.")).toBeTruthy();
    pick("Break down by", "call_site");
    expect(within(rail()).queryByText("Many distinct values; the chart keeps the top N.")).toBeNull();
  });

  // Bug: an invalid draft still reads, or its message is red small text the design system forbids.
  it("aTableWithoutADimensionShowsTheMessageAndNoPreview", async () => {
    renderEditor();
    await within(preview()).findByText("1");
    const reads = api.queryMetrics.mock.calls.length;
    chart("Table");
    const alert = within(preview()).getByRole("alert");
    expect(alert.textContent).toBe("A table needs a dimension.");
    expect(alert.className).toContain("text-fg-secondary");
    expect(within(preview()).queryByRole("region")).toBeNull();
    expect(save("Add widget").disabled).toBe(true);
    expect(api.queryMetrics).toHaveBeenCalledTimes(reads);
  });

  // Bug: the preview reads another window, drops the call-site filter, or asks for no axis on a time series.
  it("aValidDraftPreviewsWithTheRealRequestAndCaption", async () => {
    renderEditor({ callSite: "cs-a" });
    chart("Time series");
    pick("View", "llm_calls");
    pick("Measure", "cost_usd");
    pick("Break down by", "call_site");
    await waitFor(() =>
      expect(lastRequest()).toEqual({
        view: "llm_calls", measures: ["cost_usd"], dimension: "call_site",
        filters: [{ field: "call_site", op: "in", values: ["cs-a"] }], ...WINDOW_7D, granularity: "auto", top_n: null,
        compare: null,
      }),
    );
    expect(within(preview()).getByText("Last 7 days, daily")).toBeTruthy();
    expect(await within(preview()).findByRole("group", { name: /^Spend by call site, 2 buckets/ })).toBeTruthy();
  });

  // Bug: a 24-hour window is captioned daily although the server buckets it by the hour.
  it("theCaptionNamesHourlyBucketsForAShortWindow", () => {
    renderEditor({
      range: { kind: "preset", key: "24h" },
      window: { from: "2026-10-17T10:00:00.000Z", to: "2026-10-18T10:00:00.000Z" },
    });
    expect(within(preview()).getByText("Last 24 hours, hourly")).toBeTruthy();
  });

  // Bug: a new table is saved at a tile's size, under an id already taken, or with the auto title stored as its own.
  it("saveBuildsTheWidgetWithAFreshIdAndAPlacementSizedByTheChart", () => {
    const { onSave } = renderEditor();
    pick("Note", "errored_traces");
    fireEvent.click(compare());
    fireEvent.click(save("Add widget"));
    expect(onSave).toHaveBeenCalledWith({
      id: "traces-2", title: null, chart: "number", view: "traces", measures: ["traces", "errored_traces"],
      dimension: null, filters: [], top_n: null, compare: "previous_window", x: 0, y: 19, w: 4, h: 3,
    });
  });

  // Bug: a new table lands at a tile's 4x3, or its typed title is saved untrimmed or slugged wrong.
  it("aNewTableIsSavedFullWidthAtTheBottomUnderItsTypedTitle", () => {
    const { onSave } = renderEditor();
    fireEvent.change(within(rail()).getByLabelText("Title"), { target: { value: "  Spend per model " } });
    chart("Table");
    pick("View", "llm_calls");
    pick("Measure", "cost_usd");
    pick("Break down by", "model");
    pick("Top N", "10");
    fireEvent.click(save("Add widget"));
    expect(onSave).toHaveBeenCalledWith({
      id: "spend-per-model", title: "Spend per model", chart: "table", view: "llm_calls", measures: ["cost_usd"],
      dimension: "model", filters: [], top_n: 10, compare: null, x: 0, y: 19, w: 12, h: 5,
    });
  });

  // Bug: the preview keeps a tile's height after the chart changes, so the saved size and the preview disagree.
  it("thePreviewHeightFollowsTheChosenChart", async () => {
    renderEditor();
    await within(preview()).findByText("1");
    chart("Time series");
    const svg = await within(preview()).findByRole("group", { name: /buckets/ });
    expect(svg.getAttribute("viewBox")).toBe("0 0 520 316");
  });

  // Bug: editing a widget renames its id or moves it, so the dashboard loses its place or its links.
  it("editSeedsFromTheWidgetKeepsItsIdAndPlacementAndSaysSaveChanges", () => {
    const { onSave } = renderEditor({ mode: "edit", widget: SPEND_BY_CALL_SITE });
    expect(within(rail()).getByText("Edit widget", { selector: "div" })).toBeTruthy();
    expect((within(rail()).getByLabelText("Title") as HTMLInputElement).value).toBe("Spend by call site");
    expect([select("View").value, select("Measure").value, select("Break down by").value, select("Top N").value])
      .toEqual(["llm_calls", "cost_usd", "call_site", "5"]);
    fireEvent.change(within(rail()).getByLabelText("Title"), { target: { value: "Spend by site" } });
    pick("Top N", "");
    fireEvent.click(save("Save changes"));
    expect(onSave).toHaveBeenCalledWith({ ...SPEND_BY_CALL_SITE, title: "Spend by site", top_n: null });
  });

  // Bug: a table takes a fifth measure the server refuses, or a removed measure stays in the request.
  it("measuresCanBeAddedUpToFourAndRemoved", () => {
    const { onSave } = renderEditor();
    chart("Table");
    pick("View", "llm_calls");
    pick("Break down by", "model");
    const add = () => within(rail()).getByRole("button", { name: "Add measure" });
    fireEvent.click(add());
    fireEvent.click(add());
    fireEvent.click(add());
    expect([select("Measure"), select("Measure 2"), select("Measure 3"), select("Measure 4")].map((s) => s.value))
      .toEqual(["calls", "tokens", "cost_usd", "usd_per_trace"]);
    expect(within(rail()).queryByRole("button", { name: "Add measure" })).toBeNull();

    pick("Measure 3", "unpriced_calls");
    fireEvent.click(within(rail()).getAllByRole("button", { name: "Remove measure" })[0]);
    fireEvent.click(save("Add widget"));
    expect(onSave.mock.calls[0][0].measures).toEqual(["calls", "unpriced_calls", "usd_per_trace"]);
  });

  // Bug: Add measure on a view with no measure left adds a duplicate.
  it("addMeasureStopsWhenTheViewHasNoneLeft", () => {
    renderEditor();
    chart("Table");
    pick("View", "cases");
    fireEvent.click(within(rail()).getByRole("button", { name: "Add measure" }));
    fireEvent.click(within(rail()).getByRole("button", { name: "Add measure" }));
    expect([select("Measure"), select("Measure 2"), select("Measure 3")].map((s) => s.value)).toEqual([
      "cases", "open_cases", "resolved_cases",
    ]);
    expect(within(rail()).queryByRole("button", { name: "Add measure" })).toBeNull();
  });

  // Bug: a refused or slow preview read leaves the reader with no reason and no way to retry.
  it("aRefusedPreviewShowsTheServerErrorAndRetries", async () => {
    api.queryMetrics.mockRejectedValue(
      new ApiError(504, { code: "METRICS.TIMED_OUT", message: "Query took too long. Narrow the window." }),
    );
    renderEditor();
    expect(await within(preview()).findByText("Query took too long. Narrow the window.")).toBeTruthy();
    const reads = api.queryMetrics.mock.calls.length;
    fireEvent.click(within(preview()).getByRole("button", { name: "Try again" }));
    await waitFor(() => expect(api.queryMetrics).toHaveBeenCalledTimes(reads + 1));
  });

  // Bug: Cancel or Escape saves, or leaves the rail open.
  it("cancelAndEscapeClose", () => {
    const { onSave, onClose } = renderEditor();
    fireEvent.click(within(rail()).getByRole("button", { name: "Cancel" }));
    expect(onClose).toHaveBeenCalledTimes(1);
    fireEvent.keyDown(window, { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(2);
    expect(onSave).not.toHaveBeenCalled();
  });

  // Bug: a closed editor still mounts its rail and reads.
  it("aClosedEditorRendersNothing", () => {
    renderRoute(
      <WidgetEditor
        open={false} mode="add" widget={null} catalog={METRICS_CATALOG} widgets={[]} range={SEVEN_DAYS}
        window={WINDOW_7D} callSite={null} basePath="/orgs/acme/projects/default" onSave={vi.fn()} onClose={vi.fn()}
      />,
    );
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(api.queryMetrics).not.toHaveBeenCalled();
  });
});
