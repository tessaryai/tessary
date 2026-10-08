// SPDX-License-Identifier: Apache-2.0
/*
 * The Classifiers page: charts for one call site and one tool, a range for both, and a menu into each classifier's
 * configure page. The bugs worth catching are the page opening on the wrong call site or tool, a control that does
 * not reach the read, a classifier with no card and no word why, and a failed or empty read that looks like a
 * quiet project.
 */
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../../api/types";
import type { ChartCard, ChartScopes, ClassifierCharts } from "../../api/types";
import { currentLocation, renderRoute } from "../../test/render";
import { ClassifiersPage } from "./ClassifiersPage";

const api = vi.hoisted(() => ({
  base: "/api/orgs/acme/projects/default",
  listClassifiers: vi.fn(),
  listBehaviorFindings: vi.fn(),
  getClassifierChartScopes: vi.fn(),
  getClassifierCharts: vi.fn(),
}));

vi.mock("../../tenant/TenantContext", () => ({
  useTenant: () => ({ api, orgSlug: "acme", projectSlug: "default" }),
}));

function scopes(s: Partial<ChartScopes> = {}): ChartScopes {
  return {
    days: 28,
    call_sites: [
      { call_site_id: "support_agent.reply", open_cases: 0, learning: false, turns: 9_000 },
      { call_site_id: "kb_answer.generate", open_cases: 2, learning: false, turns: 40 },
      { call_site_id: "triage_router.classify", open_cases: 0, learning: true, turns: 12 },
    ],
    tools: [
      { tool_key: "tool:search_orders", label: "search_orders", open_cases: 0, calls: 5_000, callers: ["kb_answer.generate", "support_agent.reply"] },
      { tool_key: "tool:lookup_customer", label: "lookup_customer", open_cases: 0, calls: 300, callers: ["kb_answer.generate"] },
      { tool_key: "tool:classify_ticket", label: "classify_ticket", open_cases: 0, calls: 90_000, callers: ["triage_router.classify"] },
    ],
    classifiers: [
      { id: "id-frustration", classifier_key: "frustration", name: "Frustration", status: "on", waiting_reason: null, covers: "call_sites", all_call_sites: false, call_site_count: 2 },
      { id: "id-tool-error", classifier_key: "tool_error", name: "Tool Errors", status: "on", waiting_reason: null, covers: "tools", all_call_sites: true, call_site_count: 0 },
      { id: "id-groundedness", classifier_key: "groundedness", name: "Groundedness", status: "off", waiting_reason: null, covers: "call_sites", all_call_sites: true, call_site_count: 0 },
    ],
    ...s,
  };
}

const frustrationCard: ChartCard = {
  classifier_id: "id-frustration",
  classifier_key: "frustration",
  name: "Frustration",
  kind: "rate",
  measure: null,
  unit: "fraction",
  learning: null,
  headline: { value: 0.072, delta: 0.03 },
  baseline: { calls: 1000, failures: 42, rate: 0.042, pinned: false, p50: null, p95: null },
  arming: null,
  days: [{ date: "2026-10-08", checked: 100, flagged: 7, n: null, p50: null, p95: null, count: null, total: null }],
  cases: { open_cases: 0, spans: [] },
};

const toolErrorCard: ChartCard = {
  ...frustrationCard,
  classifier_id: "id-tool-error",
  classifier_key: "tool_error",
  name: "Tool Errors",
  headline: { value: 0.018, delta: -0.013 },
};

function charts(scope: { callSiteId: string } | { tool: string }): ClassifierCharts {
  if ("tool" in scope) {
    return { scope: "tool", scope_id: scope.tool, days: 28, from_day: "2026-09-11", to_day: "2026-10-08", cards: [toolErrorCard], chips: [] };
  }
  return {
    scope: "call_site",
    scope_id: scope.callSiteId,
    days: 28,
    from_day: "2026-09-11",
    to_day: "2026-10-08",
    cards: [frustrationCard],
    chips: [
      { classifier_id: "id-malformed", classifier_key: "malformed_output", name: "Malformed Output", state: "waiting", reason: "no_schema", since: null },
      { classifier_id: "id-groundedness", classifier_key: "groundedness", name: "Groundedness", state: "off", reason: null, since: null },
    ],
  };
}

beforeEach(() => {
  api.listClassifiers.mockResolvedValue([]);
  api.listBehaviorFindings.mockResolvedValue({ findings: [] });
  api.getClassifierChartScopes.mockResolvedValue(scopes());
  api.getClassifierCharts.mockImplementation((scope: { callSiteId: string } | { tool: string }) => Promise.resolve(charts(scope)));
});

const renderPage = () =>
  renderRoute(<ClassifiersPage />, {
    route: "/orgs/acme/projects/default/classifiers",
    parent: "/orgs/:orgSlug/projects/:projectSlug",
    path: "classifiers",
  });

const callSiteSection = () => screen.findByRole("region", { name: "Call site" });
const toolSection = () => screen.findByRole("region", { name: "Tool" });

describe("ClassifiersPage", () => {
  // Bug: the page opens on the busiest call site while a quieter one has open cases, or reads 28 days as anything else.
  it("opens on the call site with the most open cases, over 28 days", async () => {
    renderPage();

    await within(await callSiteSection()).findByRole("region", { name: "Frustration" });
    expect(api.getClassifierChartScopes).toHaveBeenCalledWith(28);
    expect(api.getClassifierCharts).toHaveBeenCalledWith({ callSiteId: "kb_answer.generate" }, 28);
    expect(within(await callSiteSection()).getByRole("button", { name: "Call site" }).textContent).toContain("kb_answer.generate");
  });

  // Bug: the Tool section charts a tool the selected call site never calls, though one it calls is busy.
  it("opens the Tool section on the call site's most-called tool and says who calls it", async () => {
    renderPage();

    await within(await toolSection()).findByRole("region", { name: "Tool Errors" });
    expect(api.getClassifierCharts).toHaveBeenCalledWith({ tool: "tool:search_orders" }, 28);
    expect(within(await toolSection()).getByText("All call sites. Called by 2 call sites.")).toBeTruthy();
  });

  // Bug: a classifier that is off or waiting leaves no card and no word, so it reads as missing.
  it("names each classifier that has no card for this call site, and why", async () => {
    renderPage();

    expect(await within(await callSiteSection()).findByText("Malformed Output waiting for a schema")).toBeTruthy();
    expect(within(await callSiteSection()).getByText("Groundedness off")).toBeTruthy();
  });

  // Bug: the search box filters nothing, or picking a call site leaves the charts on the old one.
  it("finds a call site by search and charts it", async () => {
    renderPage();
    await within(await callSiteSection()).findByRole("region", { name: "Frustration" });

    fireEvent.click(within(await callSiteSection()).getByRole("button", { name: "Call site" }));
    const list = screen.getByRole("listbox", { name: "Call sites" });
    expect(within(list).getByRole("option", { name: /kb_answer\.generate.*2 open cases/ })).toBeTruthy();
    expect(within(list).getByRole("option", { name: /triage_router\.classify.*New, learning/ })).toBeTruthy();
    fireEvent.change(within(list).getByRole("textbox", { name: "Search call sites" }), { target: { value: "triage" } });
    expect(within(list).queryByRole("option", { name: /support_agent/ })).toBeNull();
    fireEvent.click(within(list).getByRole("option", { name: /triage_router\.classify/ }));

    await waitFor(() => expect(api.getClassifierCharts).toHaveBeenCalledWith({ callSiteId: "triage_router.classify" }, 28));
    // The new call site's own tool comes first, and it is the only one it calls.
    await waitFor(() => expect(api.getClassifierCharts).toHaveBeenCalledWith({ tool: "tool:classify_ticket" }, 28));
  });

  // Bug: tools listed in one flat list, so the reader cannot tell which ones this call site calls.
  it("groups the tools this call site calls before the others", async () => {
    renderPage();
    await within(await toolSection()).findByRole("region", { name: "Tool Errors" });

    fireEvent.click(within(await toolSection()).getByRole("button", { name: "Tool" }));
    const list = screen.getByRole("listbox", { name: "Tools" });
    const groups = within(list).getAllByRole("group");
    expect(groups.map((g) => g.getAttribute("aria-label"))).toEqual(["Called by this call site", "Other tools"]);
    expect(within(groups[1]).getByRole("option", { name: /classify_ticket/ })).toBeTruthy();

    fireEvent.click(within(groups[0]).getByRole("option", { name: /lookup_customer/ }));
    await waitFor(() => expect(api.getClassifierCharts).toHaveBeenCalledWith({ tool: "tool:lookup_customer" }, 28));
  });

  // Bug: the range control redraws nothing, or moves only one of the two sections.
  it("reads both sections again over the range picked", async () => {
    renderPage();
    await within(await callSiteSection()).findByRole("region", { name: "Frustration" });

    fireEvent.click(screen.getByRole("button", { name: "7d" }));

    await waitFor(() => expect(api.getClassifierChartScopes).toHaveBeenCalledWith(7));
    await waitFor(() => expect(api.getClassifierCharts).toHaveBeenCalledWith({ callSiteId: "kb_answer.generate" }, 7));
    await waitFor(() => expect(api.getClassifierCharts).toHaveBeenCalledWith({ tool: "tool:search_orders" }, 7));
  });

  // Bug: with the catalog gone, a classifier's configure page is reachable only by typing its URL.
  it("lists every classifier with its status in the Configure menu, and opens its configure page", async () => {
    renderPage();
    await within(await callSiteSection()).findByRole("region", { name: "Frustration" });

    fireEvent.click(screen.getByRole("button", { name: "Configure classifiers" }));
    const menu = screen.getByRole("menu", { name: "Configure a classifier" });
    expect(within(menu).getByRole("menuitem", { name: /Tool Errors.*On, every tool/ })).toBeTruthy();
    expect(within(menu).getByRole("menuitem", { name: /Groundedness.*Off/ })).toBeTruthy();
    fireEvent.click(within(menu).getByRole("menuitem", { name: /Frustration.*On, 2 call sites/ }));

    expect(currentLocation()).toBe("/orgs/acme/projects/default/classifiers/id-frustration");
  });

  // Bug: while the classifiers load, the menu says there are none, and it is the only way to a configure page.
  it("says the Configure menu is loading, not empty, while the classifiers load", async () => {
    api.getClassifierChartScopes.mockReturnValue(new Promise(() => {}));
    renderPage();

    fireEvent.click(screen.getByRole("button", { name: "Configure classifiers" }));
    const menu = screen.getByRole("menu", { name: "Configure a classifier" });

    expect(within(menu).getByRole("status")).toBeTruthy();
    expect(within(menu).queryByText("No classifiers yet.")).toBeNull();
  });

  // Bug: a failed read leaves the menu saying there are no classifiers, which is false.
  it("says why the Configure menu could not be read, not that it is empty", async () => {
    api.getClassifierChartScopes.mockRejectedValue(new ApiError(500, { code: "INTERNAL", message: "scopes failed" }));
    renderPage();
    await screen.findAllByText(/scopes failed/);

    fireEvent.click(screen.getByRole("button", { name: "Configure classifiers" }));
    const menu = screen.getByRole("menu", { name: "Configure a classifier" });

    expect(within(menu).getByText(/scopes failed/)).toBeTruthy();
    expect(within(menu).queryByText("No classifiers yet.")).toBeNull();
  });

  // Bug: a stale or mistyped call site reads as a quiet chart instead of saying the server refused it.
  it("says why the charts could not be read, and keeps the other section", async () => {
    api.getClassifierCharts.mockImplementation((scope: { callSiteId: string } | { tool: string }) =>
      "callSiteId" in scope
        ? Promise.reject(new ApiError(422, { code: "CLASSIFIER.UNKNOWN_CALL_SITE", message: "No call site kb_answer.generate" }))
        : Promise.resolve(charts(scope)),
    );
    renderPage();

    const alert = await within(await callSiteSection()).findByRole("alert");
    expect(alert.textContent).toContain("CLASSIFIER.UNKNOWN_CALL_SITE");
    expect(await within(await toolSection()).findByRole("region", { name: "Tool Errors" })).toBeTruthy();
  });

  // Bug: a project with no traffic shows two empty sections, or asks the server for charts of nothing.
  it("says there is nothing to chart yet when no call site or tool has been seen", async () => {
    api.getClassifierChartScopes.mockResolvedValue(scopes({ call_sites: [], tools: [] }));
    renderPage();

    expect(await screen.findByText("No traffic yet")).toBeTruthy();
    expect(screen.queryByRole("region", { name: "Call site" })).toBeNull();
    expect(api.getClassifierCharts).not.toHaveBeenCalled();
  });

  // Bug: a call site with no classifier on it renders an empty grid that looks like a page still loading.
  it("says so when a call site has nothing to chart", async () => {
    api.getClassifierCharts.mockImplementation((scope: { callSiteId: string } | { tool: string }) =>
      Promise.resolve({ ...charts(scope), cards: [], chips: [] }),
    );
    renderPage();

    expect(await within(await callSiteSection()).findByText("Nothing to chart for this call site in this range.")).toBeTruthy();
  });

  it("says when no tool was called in the range", async () => {
    api.getClassifierChartScopes.mockResolvedValue(scopes({ tools: [] }));
    renderPage();

    expect(await within(await toolSection()).findByText("No tool was called in this range.")).toBeTruthy();
  });

  it("says why the call sites could not be read", async () => {
    api.getClassifierChartScopes.mockRejectedValue(new Error("chart scopes unavailable"));
    renderPage();

    expect(await screen.findByText("chart scopes unavailable")).toBeTruthy();
  });

  // Bug: the open findings listed here as well as on Triage, two lists of one queue that drift apart.
  it("lists no findings: they live on Triage", async () => {
    renderPage();

    await within(await callSiteSection()).findByRole("region", { name: "Frustration" });
    expect(api.listBehaviorFindings).not.toHaveBeenCalled();
    expect(screen.queryByRole("table")).toBeNull();
  });
});
