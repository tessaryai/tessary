// SPDX-License-Identifier: Apache-2.0
/*
 * The table widget as the reader meets it: columns from the catalog, rows in the server's rank order with the fold
 * last under every sort, the share bar, the companion sub-lines, "Show all" and the rows that open Traces.
 */
import { fireEvent, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { METRICS_CATALOG, tableResult, widget, WINDOW_7D } from "../../../test/metricsFixtures";
import { currentLocation, renderRoute } from "../../../test/render";
import { viewOf } from "../metricsRules";
import type { MetricsQueryResult, MetricWidgetDefinition } from "../wire";
import { WidgetCard } from "../WidgetCard";
import { TableWidget } from "./TableWidget";

const BASE = "/orgs/acme/projects/default";
const RANGE = "range=custom&from=2026-10-11T00%3A00%3A00Z&to=2026-10-18T00%3A00%3A00Z";

const BY_CALL_SITE = widget({
  id: "by-call-site", title: "By call site", chart: "table", view: "traces",
  measures: ["cost_usd", "usd_per_trace", "latency_p95_ms", "traces"], dimension: "call_site", top_n: 20, w: 12, h: 5,
});
const BY_MODEL = widget({
  id: "by-model", title: "By model", chart: "table", measures: ["cost_usd", "calls", "tokens"], dimension: "model",
  top_n: 20, w: 12, h: 4,
});

const callSites = () =>
  tableResult(
    "traces",
    "call_site",
    [
      {
        key: "chat", label: "chat", n: 182,
        values: { cost_usd: 7.42, usd_per_trace: 0.041, latency_p95_ms: 5200, traces: 182, unpriced_traces: 4, unterminated_traces: 9 },
      },
      { key: "summarize", label: "summarize", n: 96, values: { cost_usd: 4.1, usd_per_trace: 0.043, latency_p95_ms: 8100, traces: 96 } },
      { key: "search-rerank", label: "search-rerank", n: 74, values: { cost_usd: 2.85, usd_per_trace: 0.039, latency_p95_ms: null, traces: 74 } },
      { key: "__other__", label: "Other (7 more)", n: 10, values: { cost_usd: 1.19, usd_per_trace: 0.119, latency_p95_ms: 6000, traces: 10 } },
    ],
    10,
  );

const models = (): MetricsQueryResult =>
  tableResult("llm_calls", "model", [
    { key: "claude-sonnet-4", label: "claude-sonnet-4", n: 3120, values: { cost_usd: 9.84, calls: 3120, tokens: 1_900_000, unpriced_calls: 0 } },
    { key: "gpt-4o", label: "gpt-4o", n: 2208, values: { cost_usd: 4.31, calls: 2208, tokens: 1_100_000, unpriced_calls: 14 } },
  ]);

const body = (w: MetricWidgetDefinition, result: MetricsQueryResult, editing = false) => (
  <TableWidget
    key={w.id}
    widget={w}
    view={viewOf(METRICS_CATALOG, w.view)}
    result={result}
    window={WINDOW_7D}
    basePath={BASE}
    editing={editing}
  />
);

const renderTable = (w: MetricWidgetDefinition, result: MetricsQueryResult) =>
  renderRoute(body(w, result), { route: `${BASE}/metrics` });

const card = (w: MetricWidgetDefinition, result: MetricsQueryResult) => (
  <WidgetCard
    key={w.id}
    widget={w}
    view={viewOf(METRICS_CATALOG, w.view)}
    state={{ status: "ready", result, stale: false }}
    window={WINDOW_7D}
    basePath={BASE}
    callSite={null}
    editing={false}
  />
);

/** Body rows only: the first row of the table is the header. */
const bodyRows = (table = screen.getByRole("table")) => within(table).getAllByRole("row").slice(1);
const firstCells = (table?: HTMLElement) => bodyRows(table).map((r) => within(r).getAllByRole("cell")[0].textContent);
const header = (name: string) => screen.getByRole("columnheader", { name });

describe("TableWidget", () => {
  // Bug: columns named by wire key; the server's rank order lost; the fold drawn before a named key.
  it("listsColumnsFromTheCatalogAndRowsInRankOrderWithOtherLast", () => {
    renderTable(BY_CALL_SITE, callSites());

    expect(screen.getAllByRole("columnheader").map((h) => h.textContent)).toEqual([
      "Call site", "Spend↓", "Spend per priced trace", "p95 latency", "Traces",
    ]);
    expect(header("Spend").getAttribute("aria-sort")).toBe("descending");
    expect(firstCells()).toEqual(["chat", "summarize", "search-rerank", "Other (7 more)"]);
    const chat = within(bodyRows()[0]).getAllByRole("cell").map((c) => c.textContent);
    expect(chat).toEqual(["chat", "$7.424 unpriced", "$0.041", "5.2s9 did not finish", "182"]);
    const [name, , perTrace] = within(bodyRows()[3]).getAllByRole("cell");
    expect(name.className).toContain("text-muted");
    expect(perTrace.className).toContain("text-right");
    expect(within(bodyRows()[0]).getAllByRole("cell")[0].className).toContain("text-fg");
  });

  // Bug: numbers sorted as text ("8100" before "900"), a missing value sorted first, or the fold sorted into the keys.
  it("sortsNumbersAsNumbersAndNamesAsTextAndKeepsOtherLast", () => {
    renderTable(BY_CALL_SITE, callSites());

    fireEvent.click(within(header("p95 latency")).getByRole("button"));
    expect(header("p95 latency").getAttribute("aria-sort")).toBe("descending");
    expect(header("Spend").getAttribute("aria-sort")).toBeNull();
    expect(firstCells()).toEqual(["summarize", "chat", "search-rerank", "Other (7 more)"]);

    fireEvent.click(within(header("p95 latency")).getByRole("button"));
    expect(header("p95 latency").getAttribute("aria-sort")).toBe("ascending");
    expect(firstCells()).toEqual(["chat", "summarize", "search-rerank", "Other (7 more)"]);

    fireEvent.click(within(header("Call site")).getByRole("button"));
    expect(header("Call site").getAttribute("aria-sort")).toBe("ascending");
    expect(firstCells()).toEqual(["chat", "search-rerank", "summarize", "Other (7 more)"]);
    fireEvent.click(within(header("Call site")).getByRole("button"));
    expect(firstCells()).toEqual(["summarize", "search-rerank", "chat", "Other (7 more)"]);

    fireEvent.click(within(header("Traces")).getByRole("button"));
    expect(firstCells()).toEqual(["chat", "summarize", "search-rerank", "Other (7 more)"]);
  });

  // Bug: every usd column draws a bar, or the bar's share leaves the fold out of the total.
  it("drawsTheShareBarOnTheFirstUsdColumnOnly", () => {
    renderTable(BY_CALL_SITE, callSites());

    const fill = (cell: HTMLElement) => (cell.firstElementChild!.firstElementChild!.firstElementChild as HTMLElement).style.width;
    const column = (i: number) => bodyRows().map((r) => within(r).getAllByRole("cell")[i]);
    expect(column(1).map(fill)).toEqual(["48%", "26%", "18%", "8%"]);
    expect(header("Spend").style.width).toBe("40%");
    expect(column(2).map((c) => c.children.length)).toEqual([0, 0, 0, 0]);
    expect(header("Spend per priced trace").style.width).toBe("");
  });

  // Bug: a spend figure that leaves out unpriced calls, or a p95 over traces that never finished, says nothing.
  it("printsCompanionSubLines", () => {
    renderRoute(
      <>
        {body(BY_CALL_SITE, callSites())}
        {body(BY_MODEL, models())}
      </>,
      { route: `${BASE}/metrics` },
    );
    const [traces, llm] = screen.getAllByRole("table");

    const chat = within(bodyRows(traces)[0]).getAllByRole("cell");
    expect(within(chat[1]).getByText("4 unpriced").className).toContain("text-subtle");
    expect(within(chat[3]).getByText("9 did not finish")).toBeTruthy();
    expect(within(bodyRows(traces)[1]).queryByText(/unpriced|did not finish/)).toBeNull();
    expect(within(within(bodyRows(llm)[1]).getAllByRole("cell")[1]).getByText("14 unpriced")).toBeTruthy();
    expect(within(bodyRows(llm)[0]).queryByText(/unpriced/)).toBeNull();
  });

  // Bug: a long table pushes the card's other rows off the grid, or hides the rows with no way to see them.
  it("showAllExpandsTheTableAndShowFewerCollapsesIt", () => {
    const rows = (k: number) =>
      Array.from({ length: k }, (_, i) => ({ key: `site-${i}`, label: `site-${i}`, n: 100 - i, values: { cost_usd: 100 - i } }));
    const one = widget({ id: "spend", title: "Spend", chart: "table", measures: ["cost_usd"], dimension: "call_site" });
    renderTable(one, tableResult("llm_calls", "call_site", rows(12)));

    expect(bodyRows()).toHaveLength(6);
    fireEvent.click(screen.getByRole("button", { name: "Show all 12" }));
    expect(bodyRows()).toHaveLength(12);
    fireEvent.click(screen.getByRole("button", { name: "Show fewer" }));
    expect(bodyRows()).toHaveLength(6);

    renderTable({ ...one, id: "five" }, tableResult("llm_calls", "call_site", rows(5)));
    expect(screen.getAllByRole("button", { name: /^Show/ })).toHaveLength(1);
  });

  // Bug: a call-site row opens an unfiltered Traces list or drops the window; a model row pretends to link.
  it("callSiteRowsOpenTracesForTheWindowAndModelRowsDoNot", () => {
    renderRoute(
      <>
        {card(BY_CALL_SITE, callSites())}
        {card(BY_MODEL, models())}
      </>,
      { route: `${BASE}/metrics` },
    );

    expect(within(screen.getByRole("region", { name: "By call site" })).getByText("Select a row to open its traces"))
      .toBeTruthy();
    expect(within(screen.getByRole("region", { name: "By model" })).queryByText(/Select a row/)).toBeNull();
    expect(screen.queryByRole("row", { name: /Other/ })).toBeTruthy();
    expect(screen.queryByRole("row", { name: "Open traces for Other (7 more)" })).toBeNull();
    expect(screen.queryByRole("row", { name: /claude-sonnet-4/ })?.getAttribute("tabindex")).toBeNull();
    fireEvent.click(screen.getByRole("row", { name: /claude-sonnet-4/ }));
    expect(currentLocation()).toBe(`${BASE}/metrics`);

    fireEvent.keyDown(screen.getByRole("row", { name: "Open traces for summarize" }), { key: "Enter" });
    expect(currentLocation()).toBe(`${BASE}/traces?call_site=summarize&${RANGE}`);
  });

  // Bug: in edit mode a row still opens Traces, so a click meant for the card leaves the page and drops the draft.
  it("rowsDoNotOpenAnythingInEditMode", () => {
    renderRoute(body(BY_CALL_SITE, callSites(), true), { route: `${BASE}/metrics` });
    expect(screen.queryByRole("row", { name: /^Open traces/ })).toBeNull();
    expect(bodyRows().every((r) => r.getAttribute("tabindex") == null)).toBe(true);
    fireEvent.click(bodyRows()[0]);
    fireEvent.keyDown(bodyRows()[0], { key: "Enter" });
    expect(currentLocation()).toBe(`${BASE}/metrics`);
  });

  // Bug: Space or a click on a call-site row does nothing; a cases row opens Traces, which has no cases.
  it("rowsOpenOnClickAndSpaceAndCasesRowsOpenTriage", () => {
    renderTable(BY_CALL_SITE, callSites());
    fireEvent.keyDown(screen.getByRole("row", { name: "Open traces for chat" }), { key: "a" });
    expect(currentLocation()).toBe(`${BASE}/metrics`);
    fireEvent.keyDown(screen.getByRole("row", { name: "Open traces for chat" }), { key: " " });
    expect(currentLocation()).toBe(`${BASE}/traces?call_site=chat&${RANGE}`);

    const cases = widget({ id: "c", title: "Cases by detector", chart: "table", view: "cases", measures: ["cases"], dimension: "detector" });
    const result = tableResult("cases", "detector", [{ key: "secret_leak", label: "Secret leak", n: 3, values: { cases: 3 } }]);
    renderRoute(body(cases, result), { route: `${BASE}/metrics` });
    fireEvent.click(screen.getByRole("row", { name: "Open Triage" }));
    expect(screen.getAllByLabelText("location")[1].textContent).toBe(`${BASE}/triage`);
  });

  // Bug: the unattributed group links to a call site named "__unattributed__", or reads like a real call site.
  it("unattributedOpensTheNoCallSiteScope", () => {
    renderTable(
      BY_CALL_SITE,
      tableResult("traces", "call_site", [
        { key: "__unattributed__", label: "Unattributed", n: 4, values: { cost_usd: 0.5, traces: 4 } },
      ]),
    );

    const row = screen.getByRole("row", { name: "Open traces for Unattributed" });
    expect(within(row).getAllByRole("cell")[0].className).toContain("text-muted");
    expect(within(row).getAllByRole("cell")[2].textContent).toBe("—");
    fireEvent.click(row);
    expect(currentLocation()).toBe(`${BASE}/traces?call_site_scope=none&${RANGE}`);
  });

  // Bug: an empty grouped result draws a bare header with no word on why.
  it("emptyRowsSayNothingRan", () => {
    renderTable(BY_CALL_SITE, tableResult("traces", "call_site", []));

    const [cell] = within(bodyRows()[0]).getAllByRole("cell");
    expect(cell.textContent).toBe("Nothing ran in this window.");
    expect(cell.getAttribute("colspan")).toBe("5");
    expect(screen.queryByRole("button", { name: /^Show/ })).toBeNull();
  });
});
