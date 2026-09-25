// SPDX-License-Identifier: Apache-2.0
/*
 * The traces index: a server-side filter, sort and page. Each control writes the URL and the URL is
 * the query, so the tests assert both halves: what the control wrote, and what the list was asked for.
 * A number that is not there says why (still arriving vs genuinely nothing), an empty list offers only
 * the ways out that would change it, and grouping by session reads sessions server-side and expands
 * one in place.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import type { SessionDetailView, SessionListItemView, TraceListItemView, TracesPageView } from "../../api/types";
import { currentParams, pending, renderRoute } from "../../test/render";
import { TracesIndex } from "./TracesIndex";

const api = vi.hoisted(() => ({
  base: "/api/orgs/acme/projects/default",
  listTraces: vi.fn(),
  listSessions: vi.fn(),
  getSession: vi.fn(),
  getTrace: vi.fn(),
  getSessionSpans: vi.fn(),
}));

vi.mock("../../tenant/TenantContext", () => ({
  useProjectApi: () => api,
  useTenant: () => ({ orgSlug: "acme", projectSlug: "default", api }),
}));

function trace(over: Partial<TraceListItemView> = {}): TraceListItemView {
  return {
    id: "tr-1",
    name: "checkout-agent",
    started_at: "2026-09-25T10:00:00Z",
    ended_at: "2026-09-25T10:00:02Z",
    status: "ok",
    is_settled: true,
    latency_ms: 2_000,
    input_preview: "where is my order",
    output_preview: "it ships today",
    total_cost: 0.0123,
    input_cost: 0.01,
    output_cost: 0.0023,
    span_count: 4,
    error_count: 0,
    input_tokens: 1_200,
    output_tokens: 300,
    cache_read_tokens: 0,
    cache_write_tokens: 0,
    reasoning_tokens: null,
    total_tokens: 1_500,
    call_site_id: "checkout",
    session: "sess-1",
    thread_id: null,
    user_id: null,
    unpriced_spans: 0,
    ...over,
  };
}

function session(over: Partial<SessionListItemView> = {}): SessionListItemView {
  return {
    id: "sess-1",
    started_at: "2026-09-25T10:00:00Z",
    last_activity_at: "2026-09-25T10:05:00Z",
    dominant_call_site_id: "checkout",
    call_site_count: 3,
    first_input_preview: "hi",
    last_output_preview: "bye",
    error_count: 1,
    unsettled_traces: 1,
    trace_count: 2,
    span_count: 9,
    total_cost: 0.5,
    input_cost: 0.3,
    output_cost: 0.2,
    input_tokens: 10,
    output_tokens: 20,
    cache_read_tokens: 0,
    cache_write_tokens: 0,
    reasoning_tokens: 0,
    total_tokens: 30,
    unpriced_spans: 0,
    user_id: null,
    ...over,
  };
}

const page = (traces: TraceListItemView[], next_cursor: string | null = null): TracesPageView => ({ traces, next_cursor });

beforeEach(() => {
  api.listTraces.mockResolvedValue(page([trace()]));
  api.listSessions.mockResolvedValue({ sessions: [], next_cursor: null });
  api.getTrace.mockImplementation(pending);
  api.getSession.mockImplementation(pending);
  api.getSessionSpans.mockImplementation(pending);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.useRealTimers();
  window.localStorage.clear();
});

const lastListCall = () => api.listTraces.mock.calls.at(-1)?.[0];

describe("the trace rows", () => {
  it("marks an errored and a still-arriving trace, and says which empty numbers are pending", async () => {
    api.listTraces.mockResolvedValue(
      page([
        trace({ id: "tr-err", name: null, status: "error" }),
        trace({ id: "tr-live", name: "live-one", is_settled: false, total_cost: null, span_count: null, total_tokens: null }),
        trace({ id: "tr-free", name: "free-one", total_cost: null, total_tokens: 1_234_567, span_count: null }),
      ]),
    );
    renderRoute(<TracesIndex />);

    const errored = (await screen.findByText("tr-err")).closest("tr")!;
    expect(within(errored).getByText("errored:")).toBeTruthy();

    const live = screen.getByText("live-one").closest("tr")!;
    expect(within(live).getByText("live")).toBeTruthy();
    expect(within(live).getAllByTitle("Still arriving. This trace has not rolled up yet.")).toHaveLength(3);

    const free = screen.getByText("free-one").closest("tr")!;
    expect(within(free).getAllByText("—").length).toBeGreaterThanOrEqual(2);
    expect(within(free).getByTitle("1,234,567 tokens").textContent).toBe("1.23M");
  });

  it("opens a trace in the rail by click or Enter, and closing clears the rail's params", async () => {
    renderRoute(<TracesIndex />, { route: "/traces?view=spans&span=s1" });

    fireEvent.click(await screen.findByText("checkout-agent"));
    expect(currentParams().get("trace")).toBe("tr-1");

    fireEvent.keyDown(screen.getByText("checkout-agent").closest("tr")!, { key: "Enter" });
    expect(currentParams().get("trace")).toBe("tr-1");
    await waitFor(() => expect(api.getTrace).toHaveBeenCalledWith("tr-1"));
  });
});

describe("filters", () => {
  it("searches on Enter, not per keystroke, and the chip removes it", async () => {
    renderRoute(<TracesIndex />);
    await screen.findByText("checkout-agent");
    const box = screen.getByRole("textbox", { name: "Filter traces" });

    fireEvent.change(box, { target: { value: "  refund  " } });
    expect(lastListCall().q).toBeUndefined();
    fireEvent.keyDown(box, { key: "Enter" });

    await waitFor(() => expect(lastListCall().q).toBe("refund"));
    fireEvent.click(screen.getByRole("button", { name: "Remove filter search: refund" }));
    await waitFor(() => expect(lastListCall().q).toBeUndefined());
  });

  it("reads a deep-linked call site as a removable chip and a server-side filter", async () => {
    renderRoute(<TracesIndex />, { route: "/traces?call_site=checkout" });

    await screen.findByText("call site: checkout");
    expect(lastListCall()).toMatchObject({ callSite: "checkout", sort: "when", limit: 50 });

    fireEvent.click(screen.getByRole("button", { name: "Remove filter call site: checkout" }));
    await waitFor(() => expect(currentParams().has("call_site")).toBe(false));
  });

  it("writes a facet to the URL and asks the server for it", async () => {
    renderRoute(<TracesIndex />, { route: "/traces?trace=tr-9" });
    await screen.findByText("checkout-agent");

    fireEvent.click(screen.getByRole("button", { name: /^Status/ }));
    fireEvent.click(within(screen.getByRole("group", { name: "Status" })).getByRole("button", { name: /error/ }));

    await waitFor(() => expect(lastListCall().status).toBe("error"));
    expect(currentParams().get("status")).toBe("error");
    expect(currentParams().get("trace")).toBe("tr-9");

    fireEvent.click(screen.getByRole("button", { name: /^Kind/ }));
    fireEvent.click(within(screen.getByRole("group", { name: "Kind" })).getByRole("button", { name: /retrieval/ }));
    await waitFor(() => expect(lastListCall().kind).toBe("retrieval"));

    fireEvent.click(screen.getByRole("button", { name: /^Call site/ }));
    fireEvent.click(within(screen.getByRole("group", { name: "Call site" })).getByRole("button", { name: /checkout/ }));
    await waitFor(() => expect(lastListCall().callSite).toBe("checkout"));

    fireEvent.click(screen.getByRole("button", { name: /^Status/ }));
    fireEvent.click(within(screen.getByRole("group", { name: "Status" })).getByRole("button", { name: /Any/ }));
    await waitFor(() => expect(currentParams().has("status")).toBe(false));

    fireEvent.click(screen.getByRole("button", { name: "Clear all" }));
    await waitFor(() => expect(currentParams().toString()).toBe("trace=tr-9"));
  });

  it("offers only the call sites the list has shown, and says so when there are none", async () => {
    api.listTraces.mockResolvedValue(page([trace({ call_site_id: null })]));
    renderRoute(<TracesIndex />);
    await screen.findByText("checkout-agent");

    fireEvent.click(screen.getByRole("button", { name: /^Call site/ }));
    expect(screen.getByText("No call site has been seen in the loaded traces yet.")).toBeTruthy();
    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("group", { name: "Call site" })).toBeNull();
  });
});

describe("the empty list", () => {
  it("in a rolling range with no filters, offers all time and points at sources", async () => {
    api.listTraces.mockResolvedValue(page([]));
    renderRoute(<TracesIndex />);

    expect(await screen.findByText("No traces in this range")).toBeTruthy();
    expect(screen.getByText(/No traces have arrived in past 30 days/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Clear all filters" })).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Search all time" }));
    expect(await screen.findByText("No traces yet")).toBeTruthy();
    expect(currentParams().get("range")).toBe("all");
    expect(screen.queryByRole("button", { name: "Search all time" })).toBeNull();
    expect(lastListCall().fromTimestamp).toBeUndefined();
  });

  it("under a filter, offers to clear it", async () => {
    api.listTraces.mockResolvedValue(page([]));
    renderRoute(<TracesIndex />, { route: "/traces?range=all&status=error" });

    expect(await screen.findByText("No traces match these filters")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Search all time" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Clear all filters" }));
    await waitFor(() => expect(currentParams().toString()).toBe(""));
  });

  it("shows the read's own error rather than an empty table", async () => {
    api.listTraces.mockRejectedValue(new Error("boom"));
    renderRoute(<TracesIndex />);
    expect(await screen.findByText(/boom/)).toBeTruthy();
  });
});

describe("paging", () => {
  it("loads the next page from the cursor, then says the list has ended", async () => {
    api.listTraces
      .mockResolvedValueOnce(page([trace({ id: "tr-1", name: "first" })], "c-2"))
      .mockResolvedValueOnce(page([trace({ id: "tr-2", name: "second" })], null));
    renderRoute(<TracesIndex />);

    fireEvent.click(await screen.findByRole("button", { name: "Load older traces" }));

    expect(await screen.findByText("second")).toBeTruthy();
    expect(screen.getByText("first")).toBeTruthy();
    expect(lastListCall().cursor).toBe("c-2");
    expect(screen.getByText("No older traces.")).toBeTruthy();
  });
});

describe("grouped by session", () => {
  it("lists sessions from the server and expands one in place", async () => {
    api.listSessions.mockResolvedValue({ sessions: [session()], next_cursor: null });
    const detail = { traces: [trace({ id: "tr-in-session", name: "turn-one" })] } as SessionDetailView;
    api.getSession.mockResolvedValue(detail);
    renderRoute(<TracesIndex />);
    await screen.findByText("checkout-agent");

    fireEvent.click(screen.getByRole("switch", { name: "Group by session" }));

    const row = (await screen.findAllByText("checkout +2"))[0].closest("tr")!;
    expect(currentParams().get("groupBy")).toBe("session");
    expect(api.listSessions).toHaveBeenCalledWith({ limit: 50, cursor: undefined, include: "totals" });
    expect(within(row).getByText("errored:")).toBeTruthy();
    expect(within(row).getByText("live")).toBeTruthy();
    expect(within(row).getByText("5m 0s")).toBeTruthy();

    fireEvent.click(within(row).getByRole("button", { name: "Expand session" }));
    expect(await screen.findByText("turn-one")).toBeTruthy();
    expect(currentParams().has("session")).toBe(false);

    fireEvent.click(screen.getByText("turn-one"));
    expect(currentParams().get("trace")).toBe("tr-in-session");

    fireEvent.click(within(row).getByRole("button", { name: "Collapse session" }));
    expect(screen.queryByText("turn-one")).toBeNull();

    fireEvent.click(row);
    expect(currentParams().get("session")).toBe("sess-1");
    expect(screen.getByText("No older sessions.")).toBeTruthy();
  });

  it("says when an expanded session's traces could not be read", async () => {
    api.listSessions.mockResolvedValue({ sessions: [session({ dominant_call_site_id: null, error_count: 0, unsettled_traces: 0 })], next_cursor: "s-2" });
    api.getSession.mockRejectedValue(new Error("gone"));
    renderRoute(<TracesIndex />, { route: "/traces?groupBy=session" });

    const row = (await screen.findAllByText("sess-1"))[0].closest("tr")!;
    expect(within(row).queryByText("live")).toBeNull();
    fireEvent.click(within(row).getByRole("button", { name: "Expand session" }));

    expect(await screen.findByText(/traces could not be loaded/)).toBeTruthy();
    expect(screen.getByRole("button", { name: "Load older sessions" })).toBeTruthy();

    fireEvent.click(screen.getByRole("switch", { name: "Group by session" }));
    await waitFor(() => expect(currentParams().has("groupBy")).toBe(false));
  });
});

describe("columns", () => {
  it("adds a column, keeps the choice across a reload, and never hides the last one", async () => {
    renderRoute(<TracesIndex />);
    await screen.findByText("checkout-agent");

    fireEvent.click(screen.getByRole("button", { name: /Columns/ }));
    const panel = screen.getByRole("group", { name: "Visible columns" });
    fireEvent.click(within(panel).getByRole("button", { name: "Trace ID" }));
    expect(screen.getByRole("columnheader", { name: "Trace ID" })).toBeTruthy();
    expect(JSON.parse(window.localStorage.getItem("tessary:traces:columns:v2")!)).toContain("traceId");

    fireEvent.click(within(panel).getByRole("button", { name: "Reset to default" }));
    expect(screen.queryByRole("columnheader", { name: "Trace ID" })).toBeNull();
    cleanup();

    window.localStorage.setItem("tessary:traces:columns:v2", JSON.stringify(["when", "gone-column"]));
    renderRoute(<TracesIndex />);
    await screen.findAllByRole("row");
    expect(screen.getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["Start time"]);
    fireEvent.click(screen.getByRole("button", { name: /Columns/ }));
    fireEvent.click(within(screen.getByRole("group", { name: "Visible columns" })).getByRole("button", { name: "Start time" }));
    expect(screen.getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["Start time"]);
  });

  it("renders every column a trace and a session row can carry", async () => {
    window.localStorage.setItem(
      "tessary:traces:columns:v2",
      JSON.stringify(["when", "endedAt", "name", "input", "output", "session", "traceId", "callSite", "latency", "cost", "costIn", "costOut", "spans", "errors", "tokensIn", "tokensOut", "cacheRead", "cacheWrite", "reasoning", "tokens"]),
    );
    api.listTraces.mockResolvedValue(page([trace({ input_preview: "  ", session: null, call_site_id: null })]));
    api.listSessions.mockResolvedValue({ sessions: [session({ dominant_call_site_id: null, total_cost: null, input_cost: null, output_cost: null, span_count: null, error_count: null })], next_cursor: null });
    renderRoute(<TracesIndex />);

    const row = (await screen.findByText("checkout-agent")).closest("tr")!;
    expect(within(row).getByText("tr-1")).toBeTruthy();
    expect(within(row).getByText("0.0123")).toBeTruthy();

    fireEvent.click(screen.getByRole("switch", { name: "Group by session" }));
    const srow = (await screen.findAllByText("sess-1"))[0].closest("tr")!;
    expect(within(srow).getByText("bye")).toBeTruthy();
  });

  it("drops a corrupt saved column set for the default", async () => {
    window.localStorage.setItem("tessary:traces:columns:v2", "{not json");
    renderRoute(<TracesIndex />);
    await screen.findByText("checkout-agent");
    expect(screen.getAllByRole("columnheader")).toHaveLength(8);
  });
});

describe("time range", () => {
  it("pins a preset to an absolute lower bound and names it", async () => {
    vi.useFakeTimers({ now: new Date("2026-09-25T12:00:00Z"), toFake: ["Date"] });
    renderRoute(<TracesIndex />);
    await screen.findByText("checkout-agent");

    fireEvent.click(screen.getByTitle("Time range: Past 30 days"));
    fireEvent.click(within(screen.getByRole("group", { name: "Time range" })).getByRole("button", { name: /Past 1 day/ }));

    await waitFor(() => expect(lastListCall().fromTimestamp).toBe("2026-09-24T12:00:00.000Z"));
    expect(currentParams().get("range")).toBe("1d");
    expect(screen.getByTitle("Time range: Past 1 day")).toBeTruthy();
  });

  it("applies a custom window from the calendar", async () => {
    renderRoute(<TracesIndex />);
    await screen.findByText("checkout-agent");

    fireEvent.click(screen.getByTitle("Time range: Past 30 days"));
    fireEvent.click(screen.getByRole("button", { name: /Select from calendar/ }));
    const apply = screen.getByRole("button", { name: "Apply" }) as HTMLButtonElement;
    expect(apply.disabled).toBe(true);

    const [from] = screen.getAllByDisplayValue("");
    fireEvent.change(from, { target: { value: "2026-09-01T00:00" } });
    fireEvent.click(apply);

    await waitFor(() => expect(currentParams().get("range")).toBe("custom"));
    expect(lastListCall().fromTimestamp).toBe(new Date("2026-09-01T00:00").toISOString());
    expect(lastListCall().toTimestamp).toBeUndefined();
    expect(screen.getByTitle(/→ now$/)).toBeTruthy();
  });

  it("reads a custom range from a link, open to the start", async () => {
    renderRoute(<TracesIndex />, { route: "/traces?range=custom&to=2026-09-10T00:00:00.000Z" });
    await screen.findByText("checkout-agent");

    expect(lastListCall()).toMatchObject({ fromTimestamp: undefined, toTimestamp: "2026-09-10T00:00:00.000Z" });
    expect(screen.getByTitle(/^Time range: the beginning →/)).toBeTruthy();
  });
});

describe("refresh", () => {
  it("re-pulls the list on demand", async () => {
    renderRoute(<TracesIndex />);
    await screen.findByText("checkout-agent");
    const before = api.listTraces.mock.calls.length;

    fireEvent.click(screen.getByRole("button", { name: "Refresh traces" }));

    await waitFor(() => expect(api.listTraces.mock.calls.length).toBe(before + 1));
  });

  it("auto-refreshes every 30s only while the reader is at the top, and remembers the choice", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const main = document.createElement("main");
    document.body.appendChild(main);
    try {
      renderRoute(<TracesIndex />);
      await screen.findByText("checkout-agent");

      fireEvent.click(screen.getByRole("button", { name: "Refresh options" }));
      fireEvent.click(screen.getByRole("button", { name: /Auto-refresh every 30s/ }));
      expect(window.localStorage.getItem("tessary:traces:auto-refresh")).toBe("on");
      expect(screen.getByTitle("Refresh now · auto-refreshing every 30s")).toBeTruthy();

      const before = api.listTraces.mock.calls.length;
      await act(async () => {
        vi.advanceTimersByTime(30_000);
      });
      await waitFor(() => expect(api.listTraces.mock.calls.length).toBe(before + 1));

      main.scrollTop = 500;
      fireEvent.scroll(main);
      expect(screen.getByTitle("Refresh now · auto-refresh held while you are scrolled down")).toBeTruthy();
      fireEvent.click(screen.getByRole("button", { name: "Refresh options" }));
      expect(screen.getByText("Held while you are scrolled down.")).toBeTruthy();
      const held = api.listTraces.mock.calls.length;
      await act(async () => {
        vi.advanceTimersByTime(30_000);
      });
      expect(api.listTraces.mock.calls.length).toBe(held);

      fireEvent.click(screen.getByRole("button", { name: /Auto-refresh every 30s/ }));
      expect(window.localStorage.getItem("tessary:traces:auto-refresh")).toBe("off");
      expect(screen.getByTitle("Refresh now · auto-refresh is off")).toBeTruthy();
    } finally {
      main.remove();
    }
  });
});

describe("the rails and keyboard paths", () => {
  it("closing the trace rail drops its params and keeps the rest", async () => {
    renderRoute(<TracesIndex />, { route: "/traces?status=ok&trace=tr-1&view=spans&span=s1" });
    await screen.findByText("checkout-agent");

    fireEvent.click(screen.getByRole("button", { name: "Close" }));

    expect(currentParams().toString()).toBe("status=ok");
  });

  it("opens and closes the session rail, and Enter works on session and nested rows", async () => {
    api.listSessions.mockResolvedValue({ sessions: [session()], next_cursor: null });
    api.getSession.mockResolvedValue({ traces: [trace({ id: "tr-nested", name: "nested-turn" })] } as SessionDetailView);
    renderRoute(<TracesIndex />, { route: "/traces?groupBy=session" });

    const row = (await screen.findAllByText("checkout +2"))[0].closest("tr")!;
    fireEvent.keyDown(row, { key: "Enter" });
    expect(currentParams().get("session")).toBe("sess-1");

    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    expect(currentParams().has("session")).toBe(false);

    fireEvent.click(within(row).getByRole("button", { name: "Expand session" }));
    fireEvent.keyDown((await screen.findByText("nested-turn")).closest("tr")!, { key: "Enter" });
    expect(currentParams().get("trace")).toBe("tr-nested");
  });
});

describe("infinite scroll", () => {
  it("loads the next page when the sentinel comes into view", async () => {
    const original = window.IntersectionObserver;
    class Intersecting {
      constructor(private cb: IntersectionObserverCallback) {}
      observe() {
        this.cb([{ isIntersecting: true } as IntersectionObserverEntry], this as unknown as IntersectionObserver);
      }
      disconnect() {}
    }
    window.IntersectionObserver = Intersecting as unknown as typeof IntersectionObserver;
    try {
      api.listTraces
        .mockResolvedValueOnce(page([trace({ id: "tr-1", name: "first" })], "c-2"))
        .mockResolvedValueOnce(page([trace({ id: "tr-2", name: "second" })], null));
      renderRoute(<TracesIndex />);

      expect(await screen.findByText("second")).toBeTruthy();
      expect(lastListCall().cursor).toBe("c-2");
    } finally {
      window.IntersectionObserver = original;
    }
  });
});

describe("dropdowns and stored choices", () => {
  it("closes each panel on Escape or a click outside it", async () => {
    renderRoute(<TracesIndex />);
    await screen.findByText("checkout-agent");

    fireEvent.click(screen.getByRole("button", { name: /Columns/ }));
    fireEvent.mouseDown(document.body);
    expect(screen.queryByRole("group", { name: "Visible columns" })).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Refresh options" }));
    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("group", { name: "Refresh" })).toBeNull();

    fireEvent.click(screen.getByTitle("Time range: Past 30 days"));
    fireEvent.mouseDown(screen.getByRole("group", { name: "Time range" }));
    expect(screen.getByRole("group", { name: "Time range" })).toBeTruthy();
    fireEvent.mouseDown(document.body);
    expect(screen.queryByRole("group", { name: "Time range" })).toBeNull();
  });

  it("hides a column it was showing", async () => {
    renderRoute(<TracesIndex />);
    await screen.findByText("checkout-agent");

    fireEvent.click(screen.getByRole("button", { name: /Columns/ }));
    fireEvent.click(within(screen.getByRole("group", { name: "Visible columns" })).getByRole("button", { name: "Latency" }));

    expect(screen.queryByRole("columnheader", { name: "Latency" })).toBeNull();
  });

  it("still renders with storage blocked, with auto-refresh off", async () => {
    const getItem = vi.spyOn(window.localStorage, "getItem").mockImplementation(() => {
      throw new Error("SecurityError");
    });
    const setItem = vi.spyOn(window.localStorage, "setItem").mockImplementation(() => {
      throw new Error("SecurityError");
    });
    try {
      renderRoute(<TracesIndex />);
      await screen.findByText("checkout-agent");
      expect(screen.getByTitle("Refresh now · auto-refresh is off")).toBeTruthy();

      fireEvent.click(screen.getByRole("button", { name: "Refresh options" }));
      fireEvent.click(screen.getByRole("button", { name: /Auto-refresh every 30s/ }));
      expect(screen.getByTitle("Refresh now · auto-refreshing every 30s")).toBeTruthy();

      fireEvent.click(screen.getByRole("button", { name: /Columns/ }));
      fireEvent.click(within(screen.getByRole("group", { name: "Visible columns" })).getByRole("button", { name: "Trace ID" }));
      expect(screen.getByRole("columnheader", { name: "Trace ID" })).toBeTruthy();
    } finally {
      getItem.mockRestore();
      setItem.mockRestore();
    }
  });

  it("picks all time, and a custom window closed at both ends", async () => {
    renderRoute(<TracesIndex />);
    await screen.findByText("checkout-agent");

    fireEvent.click(screen.getByTitle("Time range: Past 30 days"));
    fireEvent.click(screen.getByRole("button", { name: /All time/ }));
    await waitFor(() => expect(currentParams().get("range")).toBe("all"));

    fireEvent.click(screen.getByTitle("Time range: All time"));
    fireEvent.click(screen.getByRole("button", { name: /Select from calendar/ }));
    const [from, to] = screen.getAllByDisplayValue("");
    fireEvent.change(from, { target: { value: "2026-09-01T00:00" } });
    fireEvent.change(to, { target: { value: "2026-09-02T00:00" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply" }));

    await waitFor(() => expect(currentParams().get("to")).toBe(new Date("2026-09-02T00:00").toISOString()));
    expect(lastListCall().toTimestamp).toBe(new Date("2026-09-02T00:00").toISOString());
  });
});
