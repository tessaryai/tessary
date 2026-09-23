// SPDX-License-Identifier: Apache-2.0
/*
 * FrustratedConversations: the list shows each flagged message, selecting one reads its turns from the
 * real traces and draws the flagged user message in the flagged tint, the list reads the next page of
 * sessions when asked, and a session whose traces have aged out says so instead of drawing nothing.
 * Switching to a filter whose read is still in flight keeps the last list up, and only a slow read swaps
 * it for the skeleton, which then holds (#109).
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import type { FrustratedConversation, FrustratedSessionPage, TraceDetailView } from "../../api/types";
import { FrustratedConversations } from "./FrustratedConversations";

const getTrace = vi.fn<(id: string) => Promise<TraceDetailView>>();
const getFrustratedSessions =
  vi.fn<
    (id: string, params?: { cursor?: string | null; cause?: { rcaReport: string; index: number } }) => Promise<FrustratedSessionPage>
  >();

vi.mock("../../tenant/TenantContext", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../tenant/TenantContext")>();
  return {
    ...actual,
    useTenant: () => ({
      orgSlug: "acme",
      projectSlug: "default",
      api: { base: "/api/orgs/acme/projects/default", getTrace, getFrustratedSessions },
    }),
  };
});

afterEach(() => {
  vi.useRealTimers();
  cleanup();
  getTrace.mockReset();
  getFrustratedSessions.mockReset();
});

function conversation(over: Partial<FrustratedConversation> = {}): FrustratedConversation {
  return {
    conversationId: "conv-1",
    traceId: "t-3",
    score: 0.98,
    callSiteId: "policy.conversation",
    flaggedAt: "2026-07-27T16:57:00Z",
    cleared: false,
    sessionId: "sess-1",
    contextTraceIds: ["t-1", "t-2", "t-3"],
    message: "that cant be right, she is covered",
    ...over,
  };
}

/** One turn: a root agent span whose input is the user's message and whose output is the reply. */
function trace(id: string, user: string, reply: string, at: string): TraceDetailView {
  const span = {
    id: `s-${id}`,
    trace_id: id,
    parent_span_id: null,
    kind: "agent",
    name: "invoke_agent",
    status: "ok",
    started_at: at,
    ended_at: at,
    input: JSON.stringify([{ role: "user", content: user }]),
    output: JSON.stringify([{ role: "assistant", content: reply }]),
    attributes: null,
    tool_calls: [],
    retrieval_documents: [],
    payload_available: true,
    is_logical_root: true,
  } as unknown as TraceDetailView["spans"][number];
  return {
    trace: { id, started_at: at, name: "turn", latency_ms: 1000 } as unknown as TraceDetailView["trace"],
    spans: [span],
  };
}

function renderList(
  rows: FrustratedConversation[],
  total = rows.length,
  nextCursor: string | null = null,
  filter?: { rcaReport: string; index: number },
) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <FrustratedConversations
          findingId="f-1"
          first={{ rows, nextCursor, total }}
          filter={filter}
          basePath="/orgs/acme/projects/default"
        />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("FrustratedConversations", () => {
  it("draws the flagged turn after the turns before it, from the traces, with the flagged message tinted", async () => {
    getTrace.mockImplementation(async (id) =>
      id === "t-1"
        ? trace("t-1", "policy no PRG-8298-1753", "I need your full name.", "2026-07-27T16:55:00Z")
        : id === "t-2"
          ? trace("t-2", "policy holder anjali patil", "Your policy is cancelled.", "2026-07-27T16:56:00Z")
          : trace("t-3", "that cant be right, she is covered", "Let me check again.", "2026-07-27T16:57:00Z"),
    );
    renderList([conversation()], 71);

    expect(screen.getByText("1 of 71 sessions")).toBeTruthy();
    await screen.findByText("policy holder anjali patil");
    expect(getTrace.mock.calls.map((c) => c[0])).toEqual(["t-1", "t-2", "t-3"]);

    // The list's preview and the flagged turn both carry the message; the flagged one is in a bubble.
    const flagged = screen
      .getAllByText("that cant be right, she is covered")
      .map((el) => el.closest(".chat-bubble"))
      .find((el): el is HTMLElement => el != null);
    expect(flagged?.style.border).toContain("var(--color-error)");
    const earlier = screen.getByText("policy holder anjali patil").closest(".chat-bubble") as HTMLElement;
    expect(earlier.style.border).toBe("");
    expect(screen.getByText(/Showing the 2 turns before it/)).toBeTruthy();
  });

  it("scrolls the conversation pane so the flagged message is the last thing in view", async () => {
    const top = vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (
      this: HTMLElement,
    ) {
      // The pane ends at 820; the flagged message ends at 1500, below it.
      const bottom = this.dataset.flagged === "true" ? 1500 : 820;
      return { top: 0, bottom, left: 0, right: 0, width: 0, height: 0, x: 0, y: 0, toJSON: () => ({}) } as DOMRect;
    });
    getTrace.mockImplementation(async (id) => trace(id, `message ${id}`, "reply", "2026-07-27T16:00:00Z"));
    renderList([conversation()]);

    await screen.findByText("message t-3");
    const flagged = screen.getAllByText("message t-3").map((el) => el.closest(".chat-bubble")).find(Boolean);
    let pane = flagged?.parentElement ?? null;
    while (pane && !pane.className.includes("overflow-y-auto")) pane = pane.parentElement;
    // Its bottom lands at the pane's bottom, with the gap left under it.
    await waitFor(() => expect(pane?.scrollTop).toBe(1500 - 820 + 24));
    top.mockRestore();
  });

  it("reads another conversation's traces when it is selected", async () => {
    getTrace.mockImplementation(async (id) => trace(id, `message ${id}`, "reply", "2026-07-27T16:00:00Z"));
    renderList([
      conversation(),
      conversation({ conversationId: "conv-2", traceId: "t-9", contextTraceIds: ["t-9"], message: "second" }),
    ]);
    await screen.findByText("message t-1");

    fireEvent.click(screen.getByRole("button", { name: /second/ }));

    await screen.findByText("message t-9");
    expect(getTrace).toHaveBeenCalledWith("t-9");
  });

  it("reads the next page of sessions and adds it below the first", async () => {
    getTrace.mockImplementation(async (id) => trace(id, `message ${id}`, "reply", "2026-07-27T16:00:00Z"));
    getFrustratedSessions.mockResolvedValue({
      rows: [conversation({ conversationId: "conv-2", traceId: "t-9", contextTraceIds: ["t-9"], message: "older" })],
      total: 2,
      nextCursor: null,
    });
    renderList([conversation()], 2, "1");
    expect(screen.getByText("1 of 2 sessions")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /Load more sessions/ }));

    await screen.findByRole("button", { name: /older/ });
    expect(getFrustratedSessions).toHaveBeenCalledWith("f-1", expect.objectContaining({ cursor: "1" }));
    expect(screen.getByText("2 sessions")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Load more sessions/ })).toBeNull();
  });

  it("reads one cause's sessions from the server rather than the first page", async () => {
    getTrace.mockImplementation(async (id) => trace(id, `message ${id}`, "reply", "2026-07-27T16:00:00Z"));
    getFrustratedSessions.mockResolvedValue({
      rows: [conversation({ conversationId: "conv-7", traceId: "t-7", contextTraceIds: ["t-7"], message: "caused" })],
      total: 1,
      nextCursor: null,
    });
    renderList([conversation()], 80, "50", { rcaReport: "rca-1", index: 2 });

    await screen.findByRole("button", { name: /caused/ });
    expect(getFrustratedSessions).toHaveBeenCalledWith(
      "f-1",
      expect.objectContaining({ cursor: null, cause: { rcaReport: "rca-1", index: 2 } }),
    );
    expect(screen.queryByRole("button", { name: /that cant be right/ })).toBeNull();
  });

  it("says so when the session's traces are no longer stored", async () => {
    getTrace.mockRejectedValue(new Error("not found"));
    renderList([conversation()]);

    await waitFor(() => expect(screen.getByText(/traces are no longer stored/)).toBeTruthy());
  });

  it("marks a cleared conversation and falls back to its id when the message aged out", async () => {
    getTrace.mockImplementation(async (id) => trace(id, "hello", "hi", "2026-07-27T16:00:00Z"));
    renderList([conversation({ cleared: true, message: null })]);

    expect(screen.getAllByText("conv-1").length).toBeGreaterThan(0);
    expect(screen.getAllByText(/Cleared/).length).toBeGreaterThan(0);
  });

  // Bug (#109): switching cause chips on a frustration case collapsed the list to a one-line loader
  // and the page jumped. The last list must stand in for 200ms, then a skeleton that holds 400ms.
  it("keeps the last list up while a switched filter loads, then shows a skeleton that holds", async () => {
    vi.useFakeTimers();
    getTrace.mockReturnValue(new Promise(() => {}));
    let resolveCause!: (page: FrustratedSessionPage) => void;
    getFrustratedSessions.mockReturnValue(new Promise((resolve) => (resolveCause = resolve)));
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const ui = (filter?: { rcaReport: string; index: number }) => (
      <QueryClientProvider client={qc}>
        <MemoryRouter>
          <FrustratedConversations
            findingId="f-1"
            first={{ rows: [conversation()], nextCursor: "50", total: 80 }}
            filter={filter}
            basePath="/orgs/acme/projects/default"
          />
        </MemoryRouter>
      </QueryClientProvider>
    );
    const { rerender } = render(ui());
    screen.getByRole("button", { name: /Load more sessions/ });

    rerender(ui({ rcaReport: "rca-1", index: 2 }));
    const tick = (ms: number) => act(() => vi.advanceTimersByTimeAsync(ms));

    // 0 to 199ms: the old rows stand in, with no loader and no way to page them.
    await tick(199);
    screen.getByRole("button", { name: /that cant be right/ });
    expect(screen.queryByRole("status", { name: "Loading sessions" })).toBeNull();
    expect(screen.queryByRole("button", { name: /Load more sessions/ })).toBeNull();

    // 200ms: the skeleton replaces the list.
    await tick(1);
    screen.getByRole("status", { name: "Loading sessions" });
    expect(screen.queryByRole("button", { name: /that cant be right/ })).toBeNull();

    // The read lands at 250ms, but the skeleton holds until 600ms (drawn at 200, held 400).
    await tick(50);
    resolveCause({
      rows: [conversation({ conversationId: "conv-7", traceId: "t-7", contextTraceIds: ["t-7"], message: "caused" })],
      total: 1,
      nextCursor: null,
    });
    await tick(349);
    screen.getByRole("status", { name: "Loading sessions" });

    await tick(1);
    expect(screen.queryByRole("status", { name: "Loading sessions" })).toBeNull();
    screen.getByRole("button", { name: /caused/ });
  });
});
