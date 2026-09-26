// SPDX-License-Identifier: Apache-2.0
/*
 * A session is its traces, in order: each view draws every trace's block under a divider naming it,
 * Tree opens the first trace and folds the rest, and Timeline plots every span against ONE
 * session-wide scale. The summary line is the session's own rollup, and says when it is still
 * arriving or was cut short. The rails are the same views in a side panel, opening the full page in
 * a new tab.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, screen, within } from "@testing-library/react";
import type { SessionDetailView, SessionSpansView, TraceDetailView } from "../../api/types";
import { span, traceItem } from "../../test/fixtures";
import { currentParams, pending, renderRoute } from "../../test/render";
import { SessionDetail } from "./SessionDetail";
import { SessionRail } from "./SessionRail";
import { TraceRail } from "./TraceRail";

const api = vi.hoisted(() => ({
  base: "/api/orgs/acme/projects/default",
  getSession: vi.fn(),
  getSessionSpans: vi.fn(),
  getTrace: vi.fn(),
}));

vi.mock("../../tenant/TenantContext", () => ({
  useProjectApi: () => api,
  useTenant: () => ({ orgSlug: "acme", projectSlug: "default", api }),
}));

const j = (v: unknown) => JSON.stringify(v);
const TRACE_A = traceItem({ id: "tr-a", name: "first-turn", started_at: "2026-09-25T10:00:00Z", ended_at: "2026-09-25T10:00:02Z", latency_ms: 2_000, total_tokens: 1_500, total_cost: 0.004 });
const TRACE_B = traceItem({ id: "tr-b", name: null, started_at: "2026-09-25T10:01:00Z", ended_at: null, latency_ms: null, total_cost: 1.5 });

function session(over: Partial<SessionDetailView> = {}): SessionDetailView {
  return {
    id: "sess-1",
    started_at: "2026-09-25T10:00:00Z",
    last_activity_at: "2026-09-25T10:01:05Z",
    trace_count: 2,
    span_count: 3,
    total_tokens: 2_500,
    total_cost: 1.504,
    unpriced_spans: 2,
    unsettled_traces: 1,
    traces_truncated: false,
    error_count: 0,
    user_id: null,
    traces: [TRACE_A, TRACE_B],
    ...over,
  } as SessionDetailView;
}

function spans(over: Partial<SessionSpansView> = {}): SessionSpansView {
  return {
    spans: [
      span({ id: "a-root", trace_id: "tr-a", kind: "agent", name: "turn one", started_at: "2026-09-25T10:00:00Z", duration_ms: 2_000, input: j([{ role: "user", content: "first question" }]) }),
      span({ id: "b-root", trace_id: "tr-b", kind: "agent", name: "turn two", started_at: "2026-09-25T10:01:00Z", duration_ms: 5_000, input: j([{ role: "user", content: "second question" }]) }),
      span({ id: "b-llm", trace_id: "tr-b", parent_span_id: "b-root", name: "chat", started_at: "2026-09-25T10:01:01Z", duration_ms: 3_000 }),
    ],
    spans_truncated: true,
    ...over,
  } as SessionSpansView;
}

beforeEach(() => {
  api.getSession.mockResolvedValue(session());
  api.getSessionSpans.mockResolvedValue(spans());
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

function renderSession(search = "") {
  renderRoute(<SessionDetail />, {
    route: `/orgs/acme/projects/default/sessions/sess-1${search}`,
    path: "/orgs/:orgSlug/projects/:projectSlug/sessions/:sessionId",
  });
}

describe("SessionDetail", () => {
  it("summarises the session and draws every trace's turn under its own divider, in order", async () => {
    renderSession();

    expect(await screen.findByText("2 traces · 3 steps · 3k tok · 2 unpriced · still arriving · truncated")).toBeTruthy();
    const first = await screen.findByText("first question");
    const second = screen.getByText("second question");
    expect(first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getByText(/first-turn · 2\.00s$/)).toBeTruthy();
    expect(api.getSession).toHaveBeenCalledWith("sess-1");
  });

  it("opens the first trace in Tree and folds the rest, one click each", async () => {
    renderSession("?view=tree");

    expect(await screen.findByTitle("agent · turn one")).toBeTruthy();
    expect(screen.queryByTitle("agent · turn two")).toBeNull();
    const heads = screen.getAllByRole("button", { expanded: false }).filter((b) => /tok|\$|—/.test(b.textContent ?? ""));
    expect(heads).toHaveLength(1);
    expect(heads[0].textContent).toContain("$1.50");

    fireEvent.click(heads[0]);
    expect(screen.getByTitle("agent · turn two")).toBeTruthy();
    fireEvent.click(screen.getByTitle("agent · turn two"));
    expect(currentParams().get("span")).toBe("b-root");

    const firstHead = screen.getByText(/first-turn/).closest("button")!;
    expect(firstHead.textContent).toContain("2k tok · $0.0040");
    fireEvent.click(firstHead);
    expect(screen.queryByTitle("agent · turn one")).toBeNull();
  });

  it("plots every trace against one session-wide scale", async () => {
    renderSession("?view=timeline");

    await screen.findByTitle("agent · turn two");
    const bar = (title: string) => within(screen.getByTitle(title).parentElement!).getByTitle(/s$/);
    // The session runs 10:00:00 to 10:01:05 (the open trace ends with its last span); turn two starts 60s in.
    expect(bar("agent · turn two").style.left).toBe(`${(60 / 65) * 100}%`);
    fireEvent.click(screen.getByTitle("llm · chat"));
    expect(currentParams().get("span")).toBe("b-llm");
  });

  it("says when there is nothing to draw in each view", async () => {
    api.getSession.mockResolvedValue(session({ traces: [], trace_count: 1, span_count: null, total_tokens: null, unpriced_spans: 0, unsettled_traces: 0 }));
    api.getSessionSpans.mockResolvedValue(spans({ spans: [], spans_truncated: false }));
    renderSession();
    expect(await screen.findByText("This session recorded no traces.")).toBeTruthy();
    expect(screen.getByText("1 trace")).toBeTruthy();

    fireEvent.click(screen.getByRole("tab", { name: "Tree" }));
    expect(screen.getByText("This session recorded no traces.")).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: "Timeline" }));
    expect(screen.getByText("No trace in this session recorded a start time, so there is no waterfall to draw.")).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: "Conversation" }));
    expect(currentParams().has("view")).toBe(false);
  });

  it("names the failure when the session cannot be read, and never goes blank", async () => {
    api.getSession.mockRejectedValue(new Error("session sess-1 not found"));
    renderSession();
    expect(await screen.findByText(/session sess-1 not found/)).toBeTruthy();
    cleanup();

    api.getSession.mockClear();
    renderRoute(<SessionDetail />, { route: "/sessions", path: "/sessions" });
    expect(await screen.findByText(/This session could not be loaded/)).toBeTruthy();
    expect(api.getSession).not.toHaveBeenCalled();
  });
});

describe("TraceRail", () => {
  const detail: TraceDetailView = {
    trace: TRACE_A,
    spans: [span({ id: "a-root", trace_id: "tr-a", kind: "agent", name: "turn one", input: j([{ role: "user", content: "first question" }]) })],
  };

  it("renders nothing without a trace, and the trace's views with one", async () => {
    api.getTrace.mockResolvedValue(detail);
    const open = vi.spyOn(window, "open").mockImplementation(() => null);
    const onClose = vi.fn();
    renderRoute(<TraceRail traceId="tr-a" onClose={onClose} />);

    expect(await screen.findByText("first question")).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: "Tree" }));
    fireEvent.click(screen.getByTitle("agent · turn one"));
    fireEvent.click(screen.getByRole("tab", { name: "Timeline" }));
    expect(screen.getByTitle("agent · turn one")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Open trace" }));
    expect(open).toHaveBeenCalledWith("/orgs/acme/projects/default/traces/tr-a", "_blank");
    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    expect(onClose).toHaveBeenCalled();
    open.mockRestore();
    cleanup();

    renderRoute(<TraceRail traceId={null} onClose={onClose} />);
    expect(screen.queryByRole("tab")).toBeNull();
  });

  it("shows the read's error", async () => {
    api.getTrace.mockRejectedValue(new Error("rail read failed"));
    renderRoute(<TraceRail traceId="tr-a" onClose={() => {}} />);
    expect(await screen.findByText(/rail read failed/)).toBeTruthy();
  });
});

describe("SessionRail", () => {
  it("renders the session's views, and opens the full session in a new tab", async () => {
    const open = vi.spyOn(window, "open").mockImplementation(() => null);
    renderRoute(<SessionRail sessionId="sess-1" onClose={() => {}} />);

    expect(await screen.findByText("second question")).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: "Tree" }));
    fireEvent.click(await screen.findByTitle("agent · turn one"));
    fireEvent.click(screen.getByRole("tab", { name: "Timeline" }));
    fireEvent.click(screen.getByTitle("llm · chat"));

    fireEvent.click(screen.getByRole("button", { name: "Open session" }));
    expect(open).toHaveBeenCalledWith("/orgs/acme/projects/default/sessions/sess-1", "_blank");
    open.mockRestore();
  });

  it("waits on both reads, and shows either one's error", async () => {
    api.getSessionSpans.mockImplementation(pending);
    renderRoute(<SessionRail sessionId="sess-1" onClose={() => {}} />);
    expect((await screen.findAllByRole("status")).length).toBeGreaterThan(0);
    cleanup();

    api.getSessionSpans.mockRejectedValue(new Error("spans unavailable"));
    renderRoute(<SessionRail sessionId="sess-1" onClose={() => {}} />);
    expect(await screen.findByText(/spans unavailable/)).toBeTruthy();
    cleanup();

    renderRoute(<SessionRail sessionId={null} onClose={() => {}} />);
    expect(screen.queryByRole("tab")).toBeNull();
  });
});
