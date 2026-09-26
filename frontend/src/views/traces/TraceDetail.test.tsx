// SPDX-License-Identifier: Apache-2.0
/*
 * One agent turn, the way producers actually record it: the root agent span holds the question and
 * the answer, every llm call re-sends the whole history, the model asks for two tools but only one
 * execution is recorded as a span, and a retrieval runs that no request accounts for.
 *
 * Conversation has to turn that into one transcript: earlier history folded away, each message once,
 * the question first and the answer last, and every tool call as a pill (the unrecorded one recovered
 * from the tool_result the next call was handed, a tool that answered {"error": …} marked failed).
 * Tree nests each execution under the llm call that asked for it; Timeline scales bars to the trace.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, screen, within } from "@testing-library/react";
import type { TraceDetailView } from "../../api/types";
import { span, traceItem, type SpanView } from "../../test/fixtures";
import { currentParams, renderRoute } from "../../test/render";
import { TraceDetail } from "./TraceDetail";

const api = vi.hoisted(() => ({ base: "/api/orgs/acme/projects/default", getTrace: vi.fn() }));

vi.mock("../../tenant/TenantContext", () => ({
  useProjectApi: () => api,
  useTenant: () => ({ orgSlug: "acme", projectSlug: "default", api }),
}));

const j = (v: unknown) => JSON.stringify(v);
const HISTORY = [
  { role: "system", content: "Be brief." },
  { role: "user", content: "hi there" },
  { role: "assistant", content: "hello, how can I help" },
];
const QUESTION = { role: "user", content: "Where is order 881?" };
const TOOL_REQUEST = {
  role: "assistant",
  content: [
    { type: "text", text: "Let me check that." },
    { type: "tool_use", id: "call_1", name: "lookup_order", input: { id: 881 } },
    { type: "tool_use", id: "call_2", name: "lookup_shipping", input: { id: 881 } },
  ],
};
/** The same request as the next call replays it: stamped for prompt caching, otherwise identical. */
const TOOL_REQUEST_REPLAYED = {
  ...TOOL_REQUEST,
  content: TOOL_REQUEST.content.map((b, i) => (i === 1 ? { ...b, cache_control: { type: "ephemeral" } } : b)),
};
const TOOL_RESULTS = {
  role: "user",
  content: [
    { type: "tool_result", tool_use_id: "call_1", content: '{"status":"packed"}' },
    { type: "tool_result", tool_use_id: "call_2", content: { error: "carrier down" } },
  ],
};

function turn(): SpanView[] {
  return [
    span({
      id: "tr-tool",
      kind: "tool",
      name: "execute_tool lookup_order",
      parent_span_id: "sp-agent",
      started_at: "2026-09-25T10:00:00.600Z",
      ended_at: "2026-09-25T10:00:00.900Z",
      duration_ms: 300,
      attributes: { "gen_ai.tool.call.id": "call_1" },
      tool_calls: [{ name: "lookup_order", args: '{"id":881}', result: '{"status":"packed"}', error: null, latency_ms: 300, retries: 0 }],
    }),
    span({
      id: "sp-agent",
      kind: "agent",
      name: "invoke_agent policy-gpt",
      started_at: "2026-09-25T10:00:00.000Z",
      ended_at: "2026-09-25T10:00:02.000Z",
      duration_ms: 2_000,
      input: j([QUESTION]),
      output: j([{ role: "assistant", content: "It ships today." }]),
    }),
    span({
      id: "sp-llm-1",
      parent_span_id: "sp-agent",
      name: "chat claude",
      model: "claude-sonnet-5",
      started_at: "2026-09-25T10:00:00.100Z",
      ended_at: "2026-09-25T10:00:00.500Z",
      duration_ms: 400,
      total_tokens: 12_000,
      total_cost: 0.0042,
      input: j([...HISTORY, QUESTION]),
      output: j([TOOL_REQUEST]),
    }),
    span({
      id: "sp-llm-2",
      parent_span_id: "sp-agent",
      name: "chat claude",
      model: "claude-sonnet-5",
      started_at: "2026-09-25T10:00:01.000Z",
      duration_ms: 500,
      total_tokens: 900,
      total_cost: null,
      cost_source: "unpriced",
      input: j([...HISTORY, QUESTION, TOOL_REQUEST_REPLAYED, TOOL_RESULTS]),
      output: j([{ type: "text", text: "It ships today." }]),
    }),
    span({
      id: "sp-search",
      kind: "retrieval",
      name: "search_docs",
      parent_span_id: "sp-agent",
      started_at: "2026-09-25T10:00:01.600Z",
      duration_ms: 50,
      status: "error",
      attributes: { "gen_ai.tool.call.id": "call_x", "gen_ai.tool.name": "search_docs" },
      input: j({ q: "shipping policy" }),
      output: null,
    }),
    span({ id: "sp-expired", kind: "llm", name: "summarize", parent_span_id: "sp-agent", started_at: "2026-09-25T10:00:01.800Z", payload_available: false }),
  ];
}

function detail(over: Partial<TraceDetailView> = {}): TraceDetailView {
  return {
    trace: traceItem({ id: "tr-1", name: "policy-gpt", span_count: 6, latency_ms: 2_000, total_tokens: 12_900, unpriced_spans: 1, is_settled: false }),
    spans: turn(),
    ...over,
  };
}

function renderTrace(search = "") {
  return renderRoute(<TraceDetail />, {
    route: `/orgs/acme/projects/default/traces/tr-1${search}`,
    path: "/orgs/:orgSlug/projects/:projectSlug/traces/:traceId",
  });
}

beforeEach(() => {
  api.getTrace.mockResolvedValue(detail());
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("the page", () => {
  it("summarises the trace from its own row, and says when it is still arriving", async () => {
    renderTrace();

    expect(await screen.findByText(/^6 steps · 2\.00s · 13k tok · 1 unpriced · claude-sonnet-5 · still arriving · started /)).toBeTruthy();
    expect(api.getTrace).toHaveBeenCalledWith("tr-1");
    expect(screen.getByRole("link", { name: "Traces" }).getAttribute("href")).toBe("/orgs/acme/projects/default/traces");
  });

  it("names the failure instead of going blank when a trace cannot be read", async () => {
    api.getTrace.mockRejectedValue(new Error("trace tr-1 not found"));
    renderTrace();
    expect(await screen.findByText(/trace tr-1 not found/)).toBeTruthy();
    cleanup();

    api.getTrace.mockResolvedValue({ spans: [] } as unknown as TraceDetailView);
    renderTrace();
    expect(await screen.findByText(/may have been deleted or aged out of retention/)).toBeTruthy();
  });

  it("says a trace with no steps has none", async () => {
    api.getTrace.mockResolvedValue(detail({ spans: [], trace: traceItem({ span_count: 1, is_settled: true }) }));
    renderTrace();
    expect(await screen.findByText("This trace recorded no steps.")).toBeTruthy();
    expect(screen.getByText(/^1 step · /)).toBeTruthy();
  });
});

describe("Conversation", () => {
  it("folds the earlier history, and shows each message of this turn once, question first and answer last", async () => {
    renderTrace();

    const question = await screen.findByText("Where is order 881?");
    expect(screen.getAllByText("Where is order 881?")).toHaveLength(1);
    expect(screen.getAllByText("It ships today.")).toHaveLength(1);
    expect(screen.getAllByText("Let me check that.")).toHaveLength(1);
    const order = [question, screen.getByText("Let me check that."), screen.getByText("It ships today.")];
    for (let i = 1; i < order.length; i++) {
      expect(order[i - 1].compareDocumentPosition(order[i]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    }
    expect(screen.queryByText("hi there")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Show 3 earlier messages" }));
    expect(screen.getByText("hi there")).toBeTruthy();
    expect(screen.getByText("Be brief.")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Hide earlier conversation" }));
    expect(screen.queryByText("hi there")).toBeNull();
  });

  it("shows every tool call as a pill, recovering the unrecorded one and marking both failures", async () => {
    renderTrace();
    await screen.findByText("Let me check that.");

    expect(screen.getByRole("button", { name: "lookup_order" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "lookup_shipping, failed" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "search_docs, failed" })).toBeTruthy();
    expect(screen.queryByText(/tool_use/)).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "lookup_shipping, failed" }));
    expect(screen.getByText("Result · error")).toBeTruthy();
    expect(screen.getByText(/carrier down/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "lookup_shipping, failed" }));
    expect(screen.queryByText("Result · error")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "lookup_order" }));
    expect(screen.getByText("Arguments")).toBeTruthy();
    expect(screen.getByText(/packed/)).toBeTruthy();
  });

  it("keeps a screenshot delivered beside a tool result, and seats a structured user-role body as a tool result", async () => {
    const mixed = { role: "user", content: [{ type: "tool_result", tool_use_id: "call_9", content: "ok" }, { type: "image_ref", data: "m-1" }] };
    const structured = { role: "user", content: { functionResponse: { name: "lookup", response: { ok: true } } } };
    api.getTrace.mockResolvedValue(
      detail({
        spans: [
          span({ id: "root", kind: "agent", input: j([QUESTION]) }),
          span({ id: "l", parent_span_id: "root", started_at: "2026-09-25T10:00:01Z", input: j([QUESTION, mixed, structured]), output: j([{ role: "assistant", parts: [{ type: "text", content: "Here is the screenshot." }] }]) }),
        ],
      }),
    );
    renderTrace();

    expect(await screen.findByText("tool result")).toBeTruthy();
    expect(screen.getByRole("img").getAttribute("src")).toBe("/api/orgs/acme/projects/default/media/m-1");
    expect(screen.queryByText(/tool_result/)).toBeNull();
    expect(screen.getByText("Here is the screenshot.")).toBeTruthy();
  });

  it("folds a very long message behind Show more", async () => {
    const rect = vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockReturnValue({ height: 2_000 } as DOMRect);
    try {
      api.getTrace.mockResolvedValue(detail({ spans: [span({ id: "root", kind: "agent", input: j([{ role: "user", content: "a very long pasted prompt" }]) })] }));
      renderTrace();

      fireEvent.click(await screen.findByRole("button", { name: "Show more" }));
      expect(screen.getByRole("button", { name: "Show less" })).toBeTruthy();
    } finally {
      rect.mockRestore();
    }
  });
});

describe("Tree", () => {
  it("nests each tool execution under the llm call that asked for it, with its cost or why it has none", async () => {
    renderTrace("?view=tree");

    await screen.findByTitle("agent · invoke_agent policy-gpt");
    const rows = screen.getAllByTitle(/^(agent|llm|tool|retrieval) · /).map((b) => b.getAttribute("title"));
    expect(rows).toEqual([
      "agent · invoke_agent policy-gpt",
      "llm · chat claude",
      "tool · execute_tool lookup_order",
      "llm · chat claude",
      "retrieval · search_docs",
      "llm · summarize",
    ]);
    expect(screen.getByText("400ms · 12k tok · $0.0042")).toBeTruthy();
    expect(screen.getByText("500ms · 900 tok · unpriced")).toBeTruthy();
    expect(screen.getByText("failed")).toBeTruthy();
  });

  it("selects a span into the URL without opening it, and opens its payloads separately", async () => {
    renderTrace("?view=tree");
    const agent = await screen.findByTitle("agent · invoke_agent policy-gpt");

    fireEvent.click(agent);
    expect(currentParams().get("span")).toBe("sp-agent");
    expect(screen.queryByText("Input")).toBeNull();

    const expanders = screen.getAllByRole("button", { name: "Show payloads" });
    fireEvent.click(expanders[0]);
    expect(screen.getByText("Input")).toBeTruthy();
    expect(screen.getByText("Output")).toBeTruthy();

    fireEvent.click(expanders[expanders.length - 1]);
    expect(screen.getByText(/Payload expired/)).toBeTruthy();
  });

  it("switches views through the URL, back to Conversation without a param", async () => {
    renderTrace("?view=tree&span=sp-agent");
    await screen.findByTitle("agent · invoke_agent policy-gpt");

    fireEvent.click(screen.getByRole("tab", { name: "Timeline" }));
    expect(currentParams().get("view")).toBe("timeline");
    fireEvent.click(screen.getByRole("tab", { name: "Conversation" }));
    expect(currentParams().has("view")).toBe(false);
    expect(currentParams().get("span")).toBe("sp-agent");
  });
});

describe("Timeline", () => {
  it("draws one bar per span and opens a tool step's arguments and result", async () => {
    renderTrace("?view=timeline");

    const tool = await screen.findByTitle("tool · execute_tool lookup_order");
    fireEvent.click(tool);
    expect(currentParams().get("span")).toBe("tr-tool");
    expect(screen.getAllByTitle("300ms").length).toBeGreaterThan(0);

    const row = tool.parentElement!;
    fireEvent.click(within(row).getByRole("button", { name: "Show payloads" }));
    expect(screen.getByText("Arguments")).toBeTruthy();
  });

  it("scales to the spans while the trace has no end, and says so when nothing has a start", async () => {
    api.getTrace.mockResolvedValue(detail({ trace: traceItem({ ended_at: null }), spans: [span({ id: "only", name: "only", started_at: "2026-09-25T10:00:00Z", duration_ms: 1_000 }), span({ id: "bad", started_at: "not a time" })] }));
    renderTrace("?view=timeline");
    expect(await screen.findByTitle("llm · only")).toBeTruthy();
    expect(screen.queryByTitle("llm · llm")).toBeNull();
    cleanup();

    api.getTrace.mockResolvedValue(detail({ trace: traceItem({ ended_at: null }), spans: [span({ id: "bad", started_at: "not a time" })] }));
    renderTrace("?view=timeline");
    expect(await screen.findByText("No step recorded a start time, so there is no waterfall to draw.")).toBeTruthy();
  });
});

