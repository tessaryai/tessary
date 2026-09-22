// SPDX-License-Identifier: Apache-2.0
/*
 * FrustratedConversations: the list shows each flagged message, selecting one reads its turns from the
 * real traces and draws the flagged user message in the flagged tint, and a conversation whose traces
 * have aged out says so instead of drawing nothing.
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import type { FrustratedConversation, TraceDetailView } from "../../api/types";
import { FrustratedConversations } from "./FrustratedConversations";

const getTrace = vi.fn<(id: string) => Promise<TraceDetailView>>();

vi.mock("../../tenant/TenantContext", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../tenant/TenantContext")>();
  return {
    ...actual,
    useTenant: () => ({
      orgSlug: "acme",
      projectSlug: "default",
      api: { base: "/api/orgs/acme/projects/default", getTrace },
    }),
  };
});

afterEach(() => {
  cleanup();
  getTrace.mockReset();
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

function renderList(rows: FrustratedConversation[], total = rows.length) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <FrustratedConversations conversations={rows} total={total} basePath="/orgs/acme/projects/default" />
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

    expect(screen.getByText("1 of 71 kept as evidence")).toBeTruthy();
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

  it("says so when the conversation's traces are no longer stored", async () => {
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
});
