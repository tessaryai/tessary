// SPDX-License-Identifier: Apache-2.0
/*
 * The Metrics page end to end over the mocked API. The bugs worth catching: a widget that reads another window than the
 * URL names, reads that fire before the catalog and so miss the call-site filter, a viewer offered edits, a relative
 * navigation that nests /metrics under /metrics/<id>, an edit that saves part of the draft or edits the built-in
 * dashboard in place, a failed write that leaves edit mode or navigates anyway, an unpriced count summed twice, and a
 * BUSY read retried forever or never.
 */
import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { Link } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../../api/types";
import { api } from "../../test/api";
import {
  DASHBOARD_LIST, dashboard, DEFAULT_DASHBOARD, DEFAULT_SUMMARY, METRICS_CATALOG, resultFor, tableResult, totalResult,
  widget,
} from "../../test/metricsFixtures";
import { currentLocation, currentParams, pending, renderRoute } from "../../test/render";
import { widgetLimiter } from "./metricsQueries";
import { MetricsPage } from "./MetricsPage";
import type { MetricDashboard, MetricDashboardSummary, MetricsQueryRequest } from "./wire";

vi.mock("../../tenant/TenantContext", () => import("../../test/api"));

const BASE = "/orgs/acme/projects/default";
// 2026-10-18T09:58:48Z: every preset window ends at 10:00 UTC, the start of the next hour.
const NOW = new Date("2026-10-18T09:58:48Z");
const TO = "2026-10-18T10:00:00.000Z";

const COST = dashboard({
  id: "01JCOST",
  name: "Cost review",
  description: "Where the money goes",
  widgets: [
    widget({ id: "a", title: "A", x: 0, y: 0, w: 6, h: 3 }),
    widget({ id: "b", title: "B", view: "tool_calls", measures: ["calls"], x: 6, y: 0, w: 6, h: 3 }),
  ],
});
COST.definition.default_range = "24h";
const EMPTY = dashboard({ id: "01JEMPTY", name: "Q4 review" });
const summaryOf = (d: MetricDashboard): MetricDashboardSummary => ({
  id: d.id, name: d.name, description: d.description, built_in: d.built_in, widget_count: d.definition.widgets.length,
  updated_at: d.updated_at,
});
const LIST = { dashboards: [DEFAULT_SUMMARY, summaryOf(COST), summaryOf(EMPTY)], can_manage: true };
const SERVED = [DEFAULT_DASHBOARD, COST, EMPTY];

/** Serves the three listed dashboards plus `extra`; any other id is the server's 404. */
function serve(...extra: MetricDashboard[]) {
  const all = [...extra, ...SERVED];
  api.getMetricDashboard.mockImplementation((id: string) => {
    const d = all.find((x) => x.id === id);
    return d
      ? Promise.resolve(d)
      : Promise.reject(new ApiError(404, { code: "METRICS.DASHBOARD_NOT_FOUND", message: "No dashboard" }));
  });
}

const BUSY = new ApiError(503, {
  code: "METRICS.BUSY", message: "Metrics is busy. Other reads are running; try again in a moment.",
});

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(NOW);
  api.getMetricsCatalog.mockResolvedValue(METRICS_CATALOG);
  api.listMetricDashboards.mockResolvedValue(LIST);
  serve();
  api.queryMetrics.mockImplementation((req: MetricsQueryRequest) => Promise.resolve(resultFor(req)));
});
afterEach(() => widgetLimiter.reset());

function renderPage(route = `${BASE}/metrics`) {
  return renderRoute(<MetricsPage />, { route, parent: "/orgs/:orgSlug/projects/:projectSlug", path: "metrics/:dashboardId?" });
}

const requests = (): MetricsQueryRequest[] => api.queryMetrics.mock.calls.map((c) => c[0]);
const regions = () => screen.getAllByRole("region").map((r) => r.getAttribute("aria-label"));
const toolbar = () => screen.getByRole("group", { name: "Edit dashboard" });
const addWidget = () => within(toolbar()).getByRole("button", { name: "Add widget" });
const rail = () => screen.getByRole("dialog", { name: /widget$/ });
const pill = (name: string) => within(screen.getByRole("navigation", { name: "Dashboards" })).getByRole("button", { name });
const menuItem = (title: string, item: string) => {
  fireEvent.click(screen.getByRole("button", { name: `${title} actions` }));
  fireEvent.click(screen.getByRole("menuitem", { name: item }));
};
const dashboardMenu = (item: string) => {
  fireEvent.click(screen.getByRole("button", { name: "Dashboard actions" }));
  fireEvent.click(screen.getByRole("menuitem", { name: item }));
};
const toast = (title: string) => screen.findByText(title);

describe("reading", () => {
  // Bug: a widget reads another window than the URL names, or a preset shows a subtitle the screens do not.
  it("rendersTheDefaultDashboardsNineWidgetsFromTheUrlRange", async () => {
    renderPage(`${BASE}/metrics?range=30d`);
    await waitFor(() => expect(api.queryMetrics).toHaveBeenCalledTimes(9));
    expect(regions()).toEqual([
      "Spend", "Traces", "p95 latency", "Tokens", "Cases opened", "Spend by call site", "p95 latency over time",
      "By call site", "By model",
    ]);
    expect(new Set(requests().map((r) => `${r.from}/${r.to}`))).toEqual(new Set([`2026-09-18T10:00:00.000Z/${TO}`]));
    expect(screen.getByRole("heading", { level: 1, name: "Metrics" })).toBeTruthy();
    expect(within(screen.getByRole("group", { name: "Window" })).getByRole("button", { name: "30d" })
      .getAttribute("aria-pressed")).toBe("true");
    expect(screen.queryByText(/ to .* UTC$/)).toBeNull();
    expect(screen.queryByText(/ran on a model with no price on file/)).toBeNull();
    expect(api.getMetricDashboard).toHaveBeenCalledWith("default");
  });

  // Bug: a custom window renders in local time, or nowhere.
  it("aCustomWindowShowsItsSubtitleInUtc", async () => {
    renderPage(`${BASE}/metrics?range=custom&from=2026-10-01T09:00:00.000Z&to=2026-10-03T09:00:00.000Z`);
    expect(await screen.findByText("Oct 1, 2026, 09:00 to Oct 3, 2026, 09:00 UTC")).toBeTruthy();
    await waitFor(() => expect(api.queryMetrics).toHaveBeenCalled());
    expect(requests()[0]).toMatchObject({ from: "2026-10-01T09:00:00.000Z", to: "2026-10-03T09:00:00.000Z" });
  });

  // Bug: a dashboard without a range in the URL opens on a hard-coded 7 days instead of its own default.
  it("noRangeUsesTheDashboardDefault", async () => {
    renderPage(`${BASE}/metrics/01JCOST`);
    await waitFor(() => expect(api.queryMetrics).toHaveBeenCalledTimes(2));
    expect(requests().every((r) => r.from === "2026-10-17T10:00:00.000Z" && r.to === TO)).toBe(true);
    expect(within(screen.getByRole("group", { name: "Window" })).getByRole("button", { name: "24h" })
      .getAttribute("aria-pressed")).toBe("true");
  });

  // Bug: nine reads go out unfiltered before the catalog, then nine more filtered after it.
  it("widgetsWaitForTheCatalogBeforeTheyRead", async () => {
    let release!: (v: unknown) => void;
    api.getMetricsCatalog.mockReturnValue(new Promise((r) => (release = r)));
    renderPage(`${BASE}/metrics?call_site=cs-a`);
    expect(await screen.findByRole("region", { name: "Spend" })).toBeTruthy();
    expect(api.queryMetrics).not.toHaveBeenCalled();

    await act(async () => release(METRICS_CATALOG));
    await waitFor(() => expect(api.queryMetrics).toHaveBeenCalledTimes(9));
    expect(requests().every((r) => JSON.stringify(r.filters) === '[{"field":"call_site","op":"in","values":["cs-a"]}]'))
      .toBe(true);
  });

  // Bug: a preset leaves stale from/to in the URL, drops the call-site filter, or does not requery.
  it("aPresetWritesTheUrlAndRequeriesAndKeepsTheCallSite", async () => {
    renderPage(`${BASE}/metrics?range=custom&from=2026-10-01T09:00:00.000Z&to=2026-10-03T09:00:00.000Z&call_site=cs-a`);
    await waitFor(() => expect(api.queryMetrics).toHaveBeenCalledTimes(9));
    fireEvent.click(within(screen.getByRole("group", { name: "Window" })).getByRole("button", { name: "90d" }));
    await waitFor(() => expect(currentParams().toString()).toBe("range=90d&call_site=cs-a"));
    await waitFor(() => expect(api.queryMetrics).toHaveBeenCalledTimes(18));
    expect(requests().at(-1)).toMatchObject({ from: "2026-07-20T10:00:00.000Z", to: TO });
  });

  // Bug: a reversed pair in a shared link reaches the server, or the picker cannot write a custom window back.
  it("aCustomPairInTheUrlIsReadAndABadOneFallsBack", async () => {
    renderPage(`${BASE}/metrics?range=custom&from=2026-10-03T09:00:00.000Z&to=2026-10-01T09:00:00.000Z`);
    await waitFor(() => expect(api.queryMetrics).toHaveBeenCalledTimes(9));
    expect(requests()[0]).toMatchObject({ from: "2026-10-11T10:00:00.000Z", to: TO });
    expect(within(screen.getByRole("group", { name: "Window" })).getByRole("button", { name: "7d" })
      .getAttribute("aria-pressed")).toBe("true");

    fireEvent.click(within(screen.getByRole("group", { name: "Window" })).getByRole("button", { name: "Custom" }));
    fireEvent.change(screen.getByLabelText("From"), { target: { value: "2026-10-01T09:00" } });
    fireEvent.change(screen.getByLabelText("To"), { target: { value: "2026-10-02T09:00" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply" }));
    await waitFor(() =>
      expect(Object.fromEntries(currentParams())).toEqual({
        range: "custom", from: new Date("2026-10-01T09:00").toISOString(), to: new Date("2026-10-02T09:00").toISOString(),
      }),
    );
  });

  // Bug: the call-site filter is sent to a view that has no call site, which the server refuses.
  it("theCallSiteChipFiltersEveryWidgetThatHasTheDimensionAndClears", async () => {
    renderPage(`${BASE}/metrics/01JCOST?call_site=cs-a`);
    await waitFor(() => expect(api.queryMetrics).toHaveBeenCalledTimes(2));
    const byView = Object.fromEntries(requests().map((r) => [r.view, r.filters]));
    expect(byView).toEqual({ llm_calls: [{ field: "call_site", op: "in", values: ["cs-a"] }], tool_calls: null });
    expect(within(screen.getByRole("region", { name: "B" })).getByText("Not filtered by call site")).toBeTruthy();
    expect(within(screen.getByRole("region", { name: "A" })).queryByText("Not filtered by call site")).toBeNull();
    expect(screen.getByText("Call site: cs-a")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Clear the call-site filter" }));
    await waitFor(() => expect(currentParams().has("call_site")).toBe(false));
    await waitFor(() => expect(api.queryMetrics).toHaveBeenCalledTimes(3));
    expect(requests().at(-1)).toMatchObject({ view: "llm_calls", filters: null });
    expect(screen.queryByText("Call site: cs-a")).toBeNull();
  });

  // Bug: a viewer is offered edits the server refuses.
  it("viewersSeeNoEditNewOrKebab", async () => {
    api.listMetricDashboards.mockResolvedValue({ ...DASHBOARD_LIST, can_manage: false });
    api.getMetricDashboard.mockResolvedValue({ ...DEFAULT_DASHBOARD, can_manage: false });
    renderPage();
    expect(await screen.findByRole("region", { name: "Spend" })).toBeTruthy();
    await screen.findByRole("button", { name: "Default" });
    expect(screen.queryByRole("button", { name: "Edit" })).toBeNull();
    expect(screen.queryByRole("button", { name: "New dashboard" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Dashboard actions" })).toBeNull();
  });

  // Bug: a phone reader gets the drag grid and an Edit that cannot work.
  it("belowLgTheGridStacksAndEditIsHidden", async () => {
    vi.stubGlobal("matchMedia", (media: string) => ({
      media, matches: false, addEventListener: () => {}, removeEventListener: () => {},
    }));
    renderPage();
    expect(await screen.findByRole("region", { name: "By model" })).toBeTruthy();
    expect(regions()).toHaveLength(9);
    expect(screen.queryByRole("button", { name: "Edit" })).toBeNull();
  });

  // Bug: the default dashboard link nests /metrics under /metrics/<id>.
  it("notFoundOffersTheDefaultDashboard", async () => {
    renderPage(`${BASE}/metrics/01JAX`);
    expect(await screen.findByText("Dashboard not found")).toBeTruthy();
    expect(screen.getByText("It may have been deleted.")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Dashboard actions" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Open the default dashboard" }));
    await waitFor(() => expect(currentLocation()).toBe(`${BASE}/metrics`));
  });

  // Bug: a server error is swallowed or reworded.
  it("anotherErrorRendersTheErrorNote", async () => {
    api.getMetricDashboard.mockRejectedValue(new ApiError(500, { code: "INTERNAL", message: "The database is down" }));
    renderPage();
    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toBe("INTERNAL: The database is down");
    expect(screen.queryByText("Dashboard not found")).toBeNull();
  });

  // Bug: a failed list or catalog read leaves the page blank or loading forever with no reason.
  it("aFailedListOrCatalogSaysSo", async () => {
    api.listMetricDashboards.mockRejectedValue(new ApiError(500, { code: "INTERNAL", message: "List failed" }));
    api.getMetricsCatalog.mockRejectedValue(new ApiError(500, { code: "INTERNAL", message: "Catalog failed" }));
    renderPage();
    expect(await screen.findByText(/List failed$/)).toBeTruthy();
    expect((await screen.findAllByText(/Catalog failed$/)).length).toBeGreaterThan(0);
    expect(api.queryMetrics).not.toHaveBeenCalled();
  });

  // Bug: with the catalog refused every widget announces "Loading" forever beside the catalog's error.
  it("aFailedCatalogPutsEveryWidgetInItsError", async () => {
    api.getMetricsCatalog.mockRejectedValue(new ApiError(500, { code: "INTERNAL", message: "Catalog failed" }));
    renderPage(`${BASE}/metrics/01JCOST`);
    expect(await screen.findAllByText(/Catalog failed$/)).toHaveLength(3);
    await waitFor(() => expect(screen.queryAllByRole("status", { name: /^Loading/ })).toHaveLength(0));
    for (const name of ["A", "B"]) {
      expect(within(screen.getByRole("region", { name })).getByRole("alert").textContent).toBe("INTERNAL: Catalog failed");
    }
  });

  // Bug: the loading state nests two live regions or shows nothing.
  it("aLoadingDashboardSaysSo", async () => {
    api.getMetricDashboard.mockReturnValue(pending());
    renderPage();
    expect(screen.getByText("Loading metrics").getAttribute("role")).toBe("status");
    expect(screen.queryByRole("button", { name: "Edit" })).toBeNull();
  });

  // Bug: a footer sums the unpriced count of every llm_calls widget, or links nowhere.
  it("theUnpricedFooterSumsOnceAndLinksToModelSettings", async () => {
    api.queryMetrics.mockImplementation((req: MetricsQueryRequest) => {
      if (req.view === "llm_calls" && req.dimension === "model") {
        return Promise.resolve(
          tableResult("llm_calls", "model", [
            { key: "gpt", label: "gpt", n: 10, values: { cost_usd: 2, calls: 10, tokens: 100, unpriced_calls: 10 } },
            { key: "haiku", label: "haiku", n: 4, values: { cost_usd: 1, calls: 4, tokens: 40, unpriced_calls: 4 } },
          ]),
        );
      }
      if (req.view === "llm_calls" && req.measures[0] === "cost_usd" && req.granularity == null) {
        return Promise.resolve(totalResult("llm_calls", { cost_usd: 18.35, usd_per_trace: 0.042, unpriced_calls: 14 }));
      }
      return Promise.resolve(resultFor(req));
    });
    renderPage();
    const footer = await screen.findByText(
      /^14 LLM calls ran on a model with no price on file\. Their spend is left out, not counted as free\./,
    );
    expect(within(footer).getByRole("link", { name: "Review model settings" }).getAttribute("href")).toBe(
      `${BASE}/settings/models`,
    );
  });

  // Bug: only an llm_calls widget is consulted, so a traces-only dashboard hides its unpriced traces.
  it("theFooterFallsBackToUnpricedTraces", async () => {
    serve(dashboard({ id: "01JTRACES", widgets: [widget({ id: "t", title: "T", view: "traces", measures: ["cost_usd"] })] }));
    api.queryMetrics.mockResolvedValue(totalResult("traces", { cost_usd: 3, unpriced_traces: 1200 }));
    renderPage(`${BASE}/metrics/01JTRACES`);
    expect(await screen.findByText(/^1,200 traces ran on a model with no price on file\./)).toBeTruthy();
  });

  // Bug: a busy read is retried forever, or never, or the reader gets no way to retry.
  it("aBusyWidgetRetriesOnceThenOffersTryAgain", async () => {
    // retryDelay runs on setTimeout, so this test fakes setTimeout and clearTimeout as well as Date.
    vi.useFakeTimers({ toFake: ["Date", "setTimeout", "clearTimeout"] });
    vi.setSystemTime(NOW);
    api.queryMetrics.mockRejectedValue(BUSY);
    renderPage(`${BASE}/metrics/01JCOST`);
    await act(() => vi.advanceTimersByTimeAsync(10));
    expect(api.queryMetrics).toHaveBeenCalledTimes(2);
    expect(screen.queryByText(BUSY.detail)).toBeNull();

    // A zero-delay timer set during a fake tick is due 1 ms later, so react-query's render of the settled retry needs
    // a few more milliseconds than the retry delay itself.
    await act(() => vi.advanceTimersByTimeAsync(1500 + 10));
    const card = screen.getByRole("region", { name: "A" });
    expect(within(card).getByText(BUSY.detail)).toBeTruthy();
    expect(api.queryMetrics).toHaveBeenCalledTimes(4);
    expect(within(card).getByText("METRICS.BUSY")).toBeTruthy();

    fireEvent.click(within(card).getByRole("button", { name: "Try again" }));
    await act(() => vi.advanceTimersByTimeAsync(10));
    expect(api.queryMetrics).toHaveBeenCalledTimes(5);
  });

  // Bug: a timeout renders as a red refusal without the server's advice.
  it("aTimedOutWidgetShowsTheServerMessage", async () => {
    api.queryMetrics.mockRejectedValue(
      new ApiError(504, { code: "METRICS.TIMED_OUT", message: "Query took too long. Narrow the window or pick a coarser granularity." }),
    );
    renderPage(`${BASE}/metrics/01JCOST`);
    const card = await screen.findByRole("region", { name: "A" });
    expect(await within(card).findByText("Query took too long. Narrow the window or pick a coarser granularity."))
      .toBeTruthy();
    expect(within(card).getByText("METRICS.TIMED_OUT")).toBeTruthy();
    expect(api.queryMetrics).toHaveBeenCalledTimes(2);
  });

  // Bug: a call-site row opens Traces over another window than the page's.
  it("callSiteRowsOpenTracesForTheWindow", async () => {
    renderPage();
    const table = await screen.findByRole("region", { name: "By call site" });
    fireEvent.click(await within(table).findByRole("row", { name: "Open traces for Chat support" }));
    await waitFor(() =>
      expect(currentLocation()).toBe(
        `${BASE}/traces?call_site=chat&range=custom&from=${encodeURIComponent("2026-10-11T10:00:00.000Z")}&to=${encodeURIComponent(TO)}`,
      ),
    );
  });
});

describe("editing", () => {
  // Bug: Done saves part of the draft, saves twice, or stays in edit mode.
  it("editDoneWritesTheWholeDashboardOnceAndLeavesEditMode", async () => {
    api.updateMetricDashboard.mockImplementation((_id: string, body: { definition: MetricDashboard["definition"] }) => {
      const saved = { ...COST, definition: body.definition };
      serve(saved);
      return Promise.resolve(saved);
    });
    renderPage(`${BASE}/metrics/01JCOST`);
    fireEvent.click(await screen.findByRole("button", { name: "Edit" }));
    expect(toolbar()).toBeTruthy();
    expect(within(toolbar()).getByText("Drag a title bar to move a widget. Drag a corner to resize it.")).toBeTruthy();
    expect(screen.getAllByRole("region").every((r) => r.className.includes("border-dashed"))).toBe(true);
    expect((pill("Default") as HTMLButtonElement).disabled).toBe(true);

    menuItem("B", "Remove");
    fireEvent.click(screen.getByRole("button", { name: "Done" }));
    await toast("Dashboard saved");
    expect(api.updateMetricDashboard).toHaveBeenCalledTimes(1);
    expect(api.updateMetricDashboard).toHaveBeenCalledWith("01JCOST", {
      name: "Cost review", description: "Where the money goes",
      definition: { ...COST.definition, widgets: [COST.definition.widgets[0]] },
    });
    expect(screen.queryByRole("button", { name: "Done" })).toBeNull();
    expect(screen.queryByRole("group", { name: "Edit dashboard" })).toBeNull();
    expect(regions()).toEqual(["A"]);
  });

  // Bug: a failed save drops the draft and leaves edit mode as if it had saved.
  it("aFailedSaveStaysInEditModeAndSaysWhy", async () => {
    api.updateMetricDashboard.mockRejectedValue(new ApiError(400, { code: "METRICS.INVALID_WIDGET", message: "Bad widget" }));
    renderPage(`${BASE}/metrics/01JCOST`);
    fireEvent.click(await screen.findByRole("button", { name: "Edit" }));
    fireEvent.click(screen.getByRole("button", { name: "Done" }));
    expect(await toast("Could not save dashboard")).toBeTruthy();
    expect(screen.getByText("METRICS.INVALID_WIDGET: Bad widget")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Done" })).toBeTruthy();
    expect(toolbar()).toBeTruthy();
  });

  // Bug: the built-in dashboard is edited in place, or the copy starts without the edit just made.
  it("editOnTheDefaultOpensMakeAnEditableCopyAndNavigatesToTheCopy", async () => {
    const copy = dashboard({ id: "01JNEW", name: "Default (copy)" });
    serve(copy);
    api.createMetricDashboard.mockResolvedValue(copy);
    renderPage(`${BASE}/metrics?range=30d`);
    fireEvent.click(await screen.findByRole("button", { name: "Edit" }));
    menuItem("Tokens", "Remove");
    fireEvent.click(screen.getByRole("button", { name: "Done" }));
    expect(screen.getByRole("heading", { name: "Make an editable copy" })).toBeTruthy();
    expect((screen.getByLabelText(/^Name/) as HTMLInputElement).value).toBe("Default (copy)");
    fireEvent.click(screen.getByRole("button", { name: "Make a copy" }));
    await toast("Copy created");
    expect(api.updateMetricDashboard).not.toHaveBeenCalled();
    expect(api.createMetricDashboard).toHaveBeenCalledTimes(1);
    const body = api.createMetricDashboard.mock.calls[0][0];
    expect(body).toMatchObject({ name: "Default (copy)", clone_of: "default", definition: { version: 1, default_range: "7d" } });
    expect(body.definition.widgets.map((w: { id: string }) => w.id)).toEqual(
      DEFAULT_DASHBOARD.definition.widgets.filter((w) => w.id !== "tokens").map((w) => w.id),
    );
    await waitFor(() => expect(currentLocation()).toBe(`${BASE}/metrics/01JNEW?range=30d`));
    expect(screen.queryByRole("group", { name: "Edit dashboard" })).toBeNull();
  });

  // Bug: the draft outlives a change of dashboard (Back, a sidebar link), so the next dashboard shows the last one's
  // widgets and Done writes them over it.
  it("anotherDashboardWhileEditingDropsTheDraftAndNeverWritesIt", async () => {
    renderRoute(
      <>
        <MetricsPage />
        <Link to={`${BASE}/metrics`}>Leave</Link>
      </>,
      { route: `${BASE}/metrics/01JCOST`, parent: "/orgs/:orgSlug/projects/:projectSlug", path: "metrics/:dashboardId?" },
    );
    fireEvent.click(await screen.findByRole("button", { name: "Edit" }));
    menuItem("B", "Remove");

    fireEvent.click(screen.getByRole("link", { name: "Leave" }));
    expect(await screen.findByRole("region", { name: "By model" })).toBeTruthy();
    expect(screen.queryByRole("group", { name: "Edit dashboard" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Done" })).toBeNull();
    expect(regions()).toHaveLength(9);
    expect(api.updateMetricDashboard).not.toHaveBeenCalled();
  });

  // Bug: the window narrows below lg while editing, Done, Cancel and the toolbar vanish, and the switcher stays
  // disabled, so nothing on the page leaves edit mode.
  it("narrowingBelowLgWhileEditingLeavesEditMode", async () => {
    let onChange: (e: { matches: boolean }) => void = () => {};
    vi.stubGlobal("matchMedia", (media: string) => ({
      media, matches: true, addEventListener: (_: string, f: typeof onChange) => (onChange = f), removeEventListener: () => {},
    }));
    renderPage(`${BASE}/metrics/01JCOST`);
    fireEvent.click(await screen.findByRole("button", { name: "Edit" }));
    menuItem("B", "Remove");
    expect((pill("Default") as HTMLButtonElement).disabled).toBe(true);

    act(() => onChange({ matches: false }));
    expect((pill("Default") as HTMLButtonElement).disabled).toBe(false);
    expect(regions()).toEqual(["A", "B"]);
  });

  // Bug: Cancel keeps the draft, so a removed widget stays gone.
  it("cancelDropsTheDraft", async () => {
    renderPage(`${BASE}/metrics/01JCOST`);
    fireEvent.click(await screen.findByRole("button", { name: "Edit" }));
    menuItem("B", "Remove");
    expect(regions()).toEqual(["A"]);
    fireEvent.click(within(toolbar()).getByRole("button", { name: "Cancel" }));
    expect(regions()).toEqual(["A", "B"]);
    expect(screen.queryByRole("group", { name: "Edit dashboard" })).toBeNull();
    expect(api.updateMetricDashboard).not.toHaveBeenCalled();
  });

  // Bug: a new widget is lost, or a thirteenth is offered that the server refuses.
  it("addWidgetThroughTheRailAppendsToTheDraft", async () => {
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "Edit" }));
    fireEvent.click(addWidget());
    fireEvent.click(within(rail()).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog", { name: /widget$/ })).toBeNull();
    expect(regions()).toHaveLength(9);
    for (let n = 10; n <= 12; n++) {
      fireEvent.click(addWidget());
      fireEvent.click(within(rail()).getByRole("button", { name: "Add widget" }));
      expect(screen.queryByRole("dialog", { name: /widget$/ })).toBeNull();
      expect(regions()).toHaveLength(n);
    }
    expect(addWidget().getAttribute("aria-disabled")).toBe("true");
    expect((addWidget() as HTMLButtonElement).disabled).toBe(false);
    expect(within(toolbar()).getByText("A dashboard holds at most 12 widgets.")).toBeTruthy();
    expect(within(toolbar()).queryByText(/Drag a title bar/)).toBeNull();
    fireEvent.click(addWidget());
    expect(screen.queryByRole("dialog", { name: /widget$/ })).toBeNull();
  });

  // Bug: an edited widget is appended as a copy instead of replacing itself, or a move is not saved.
  it("editingAndMovingAWidgetThroughTheMenuChangesTheDraft", async () => {
    api.updateMetricDashboard.mockImplementation((_id: string, body: { definition: unknown }) =>
      Promise.resolve({ ...COST, definition: body.definition }),
    );
    renderPage(`${BASE}/metrics/01JCOST`);
    fireEvent.click(await screen.findByRole("button", { name: "Edit" }));
    menuItem("A", "Edit");
    expect(within(rail()).getByText("Edit widget", { selector: "div" })).toBeTruthy();
    fireEvent.change(within(rail()).getByLabelText("Title"), { target: { value: "Spend" } });
    fireEvent.click(within(rail()).getByRole("button", { name: "Save changes" }));
    expect(regions()).toEqual(["Spend", "B"]);

    menuItem("B", "Move earlier");
    fireEvent.click(screen.getByRole("button", { name: "Done" }));
    await toast("Dashboard saved");
    const saved = api.updateMetricDashboard.mock.calls[0][1].definition.widgets;
    expect(saved.map((w: { id: string; title: string; x: number }) => [w.id, w.title, w.x])).toEqual([
      ["a", "Spend", 6], ["b", "B", 0],
    ]);
  });

  // Bug: after Remove the focused kebab is gone and focus falls to the page body.
  it("removeWidgetThroughTheMenuMovesFocusToAddWidget", async () => {
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "Edit" }));
    menuItem("Spend", "Remove");
    expect(screen.queryByRole("region", { name: "Spend" })).toBeNull();
    expect(document.activeElement).toBe(addWidget());
  });

  // Bug: an empty dashboard is a blank page, or a viewer is offered a button the server refuses.
  it("theEmptyDashboardOffersAddWidget", async () => {
    renderPage(`${BASE}/metrics/01JEMPTY`);
    expect(await screen.findByText("No widgets yet")).toBeTruthy();
    expect(screen.getByText("Add a widget to chart traces, LLM calls, tool calls or cases.")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Add widget" }));
    expect(toolbar()).toBeTruthy();
    expect(within(rail()).getByText("Add widget", { selector: "div" })).toBeTruthy();
    expect(screen.queryByText("No widgets yet")).toBeNull();
  });

  // Bug: a viewer of an empty dashboard is offered a button whose save the server refuses.
  it("viewersOfAnEmptyDashboardSeeNoAddWidget", async () => {
    serve({ ...EMPTY, can_manage: false });
    api.listMetricDashboards.mockResolvedValue({ ...LIST, can_manage: false });
    renderPage(`${BASE}/metrics/01JEMPTY`);
    expect(await screen.findByText("No widgets yet")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Add widget" })).toBeNull();
  });
});

describe("dashboards", () => {
  // Bug: a duplicate-name refusal closes the modal, so the reader retypes everything.
  it("aDuplicateNameOnCloneShowsTheServerMessageAndKeepsTheModal", async () => {
    api.createMetricDashboard.mockRejectedValue(
      new ApiError(409, {
        code: "METRICS.DASHBOARD_NAME_TAKEN", message: "A dashboard named 'Default (copy)' already exists in this project",
      }),
    );
    renderPage();
    await screen.findByRole("region", { name: "Spend" });
    dashboardMenu("Duplicate");
    expect(screen.getByRole("heading", { name: "Duplicate dashboard" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Duplicate" }));
    expect(await toast("Could not create dashboard")).toBeTruthy();
    expect(screen.getByText(/already exists in this project$/)).toBeTruthy();
    expect(screen.getByRole("heading", { name: "Duplicate dashboard" })).toBeTruthy();
    expect(api.createMetricDashboard).toHaveBeenCalledWith({ name: "Default (copy)", clone_of: "default", definition: null });
  });

  // Bug: a created dashboard is not opened, a rename is toasted as a save, or delete lands on a nested path.
  it("newRenameAndDeleteRoundTrip", async () => {
    const q4 = dashboard({ id: "01JQ4", name: "Q4 spend" });
    serve(q4);
    api.createMetricDashboard.mockResolvedValue(q4);
    api.updateMetricDashboard.mockResolvedValue({ ...COST, name: "Spend review" });
    api.deleteMetricDashboard.mockResolvedValue(null);
    renderPage(`${BASE}/metrics/01JCOST?range=30d`);
    await screen.findByRole("region", { name: "A" });

    fireEvent.click(pill("New dashboard"));
    fireEvent.change(screen.getByLabelText(/^Name/), { target: { value: "Q4 spend" } });
    fireEvent.change(screen.getByLabelText("Description"), { target: { value: "Quarter close" } });
    fireEvent.click(screen.getByRole("button", { name: "Create" }));
    await toast("Dashboard created");
    expect(api.createMetricDashboard).toHaveBeenCalledWith({ name: "Q4 spend", description: "Quarter close" });
    await waitFor(() => expect(currentLocation()).toBe(`${BASE}/metrics/01JQ4?range=30d`));

    fireEvent.click(pill("Cost review"));
    await waitFor(() => expect(currentLocation()).toBe(`${BASE}/metrics/01JCOST?range=30d`));
    await screen.findByRole("region", { name: "A" });
    expect(screen.getAllByRole("button", { name: "Dashboard actions" })).toHaveLength(1);

    dashboardMenu("Rename");
    fireEvent.change(screen.getByLabelText(/^Name/), { target: { value: "Spend review" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await toast("Dashboard renamed");
    // Bug: the rename sends the cached definition and overwrites widgets saved since from another tab.
    expect(api.updateMetricDashboard.mock.calls[0]).toEqual([
      "01JCOST", { name: "Spend review", description: "Where the money goes" },
    ]);

    dashboardMenu("Delete");
    fireEvent.click(screen.getByRole("button", { name: "Delete dashboard" }));
    await toast("Dashboard deleted");
    expect(api.deleteMetricDashboard).toHaveBeenCalledWith("01JCOST");
    await waitFor(() => expect(currentLocation()).toBe(`${BASE}/metrics`));
  });

  // Bug: Rename is offered while the dashboard read failed or is still running, and saving it crashes on the missing
  // dashboard.
  it("renameWaitsForTheDashboardRead", async () => {
    api.getMetricDashboard.mockRejectedValue(new ApiError(500, { code: "INTERNAL", message: "Read failed" }));
    renderPage(`${BASE}/metrics/01JCOST`);
    expect((await screen.findByRole("alert")).textContent).toBe("INTERNAL: Read failed");

    fireEvent.click(screen.getByRole("button", { name: "Dashboard actions" }));
    expect(screen.getAllByRole("menuitem").map((m) => m.textContent)).toEqual(["Duplicate", "Delete"]);
  });

  // Bug: a failed create or delete closes its modal or navigates as if it had worked.
  it("aFailedCreateOrDeleteSaysWhyAndStays", async () => {
    api.createMetricDashboard.mockRejectedValue(new ApiError(409, { code: "METRICS.TOO_MANY_DASHBOARDS", message: "At most 20" }));
    api.deleteMetricDashboard.mockRejectedValue(new ApiError(500, { code: "INTERNAL", message: "Delete failed" }));
    renderPage(`${BASE}/metrics/01JCOST`);
    await screen.findByRole("region", { name: "A" });

    fireEvent.click(pill("New dashboard"));
    fireEvent.change(screen.getByLabelText(/^Name/), { target: { value: "One more" } });
    fireEvent.click(screen.getByRole("button", { name: "Create" }));
    expect(await toast("Could not create dashboard")).toBeTruthy();
    expect(screen.getByRole("heading", { name: "New dashboard" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("heading", { name: "New dashboard" })).toBeNull();

    dashboardMenu("Delete");
    fireEvent.click(screen.getByRole("button", { name: "Delete dashboard" }));
    expect(await toast("Could not delete dashboard")).toBeTruthy();
    expect(screen.getByText("INTERNAL: Delete failed")).toBeTruthy();
    expect(currentLocation()).toBe(`${BASE}/metrics/01JCOST`);
  });
});
