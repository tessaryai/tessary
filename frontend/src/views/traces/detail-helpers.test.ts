// SPDX-License-Identifier: Apache-2.0
/*
 * The trace interior's planning and formatting helpers, on the inputs the rendered views do not
 * reach: equal start times, the `parts` vocabulary for tool calls, a tool's answer wrapped in the
 * message that carried it, corrupt parent chains, and the fallbacks for a span with no name.
 */
import { describe, expect, it } from "vitest";
import { span } from "../../test/fixtures";
import { chatItems, groupTools, planConversation, planTools, spanItems } from "./detail-chat";
import { clockLabel, depthOf, formatDuration, formatTokens, spanOrder, traceBounds, traceSummary } from "./detail-data";
import { spanKindLabel, spanLabel } from "./detail-icons";
import { toolStepOf } from "./detail-tool";
import { sessionSummary } from "./session-detail-data";
import { batchSteps, sessionBounds } from "./detail-views";
import { traceItem } from "../../test/fixtures";

const j = (v: unknown) => JSON.stringify(v);

describe("planConversation", () => {
  it("credits a message to the root, not the child, when both start at the same instant", () => {
    const question = j([{ role: "user", content: "same instant" }]);
    const plan = planConversation([
      span({ id: "child", parent_span_id: "root", started_at: "2026-09-25T10:00:00Z", input: question }),
      span({ id: "root", started_at: "2026-09-25T10:00:00Z", input: question }),
      span({ id: "sibling", parent_span_id: "root", started_at: "2026-09-25T10:00:00Z", input: question }),
    ]);
    expect(plan.bySpan.get("root")!.input).toHaveLength(1);
    expect(plan.bySpan.get("child")!.input).toHaveLength(0);
  });
});

describe("planTools", () => {
  it("claims an execution by name when the producer records no call id", () => {
    const plan = planTools([
      span({ id: "llm", output: j([{ role: "assistant", content: [{ type: "tool_use", name: "lookup" }] }]) }),
      span({ id: "exec", kind: "tool", name: "lookup", started_at: "2026-09-25T10:00:01Z" }),
    ]);
    expect(plan.parentOf.get("exec")).toBe("llm");
    expect(plan.orphans.size).toBe(0);
    expect(plan.byLlm.get("llm")!.map((s) => s.key)).toEqual(["exec"]);
  });
});

describe("planTools on a text-only turn", () => {
  it("claims nothing for a model turn that answered in plain text", () => {
    const plan = planTools([
      span({ id: "llm", output: j([{ role: "assistant", content: "Your refund is on its way." }]) }),
      span({ id: "exec", kind: "tool", name: "lookup", started_at: "2026-09-25T10:00:01Z" }),
    ]);
    expect(plan.byLlm.size).toBe(0);
    expect(plan.parentOf.size).toBe(0);
  });
});

describe("spanItems", () => {
  it("draws nothing for a span the plan has no entry for", () => {
    expect(spanItems(undefined)).toEqual([]);
  });
});

describe("groupTools", () => {
  it("runs consecutive tool calls into one group, and starts a new group after a message", () => {
    const step = (key: string) => ({ key, name: key, args: null, result: null, failed: false });
    const message = { kind: "message", text: "hi", rest: [] } as unknown as Parameters<typeof groupTools>[0][number];
    const grouped = groupTools([
      { kind: "tool", step: step("a") },
      { kind: "tool", step: step("b") },
      message,
      { kind: "tool", step: step("c") },
    ]);
    expect(grouped.map((g) => (g.kind === "tools" ? g.steps.map((s) => s.key) : g.kind))).toEqual([
      ["a", "b"],
      "message",
      ["c"],
    ]);
  });
});

describe("chatItems for earlier turns", () => {
  it("recovers tool calls from the messages, in either vocabulary, with their results", () => {
    const items = chatItems(
      [
        { role: "assistant", raw: { role: "assistant", content: [{ type: "tool_use", id: "c1", name: "lookup", input: { id: 1 } }] } },
        { role: "assistant", raw: { role: "assistant", parts: [{ type: "tool_call", id: "c2", name: "refund", arguments: { amount: 5 } }, { type: "tool_call" }, "stray"] } },
        { role: "tool", raw: { role: "tool", parts: [{ type: "tool_result", id: "c2", content: { error: "limit" } }, { type: "tool_result" }] } },
        { role: "user", raw: { role: "user", content: [{ type: "tool_result", tool_use_id: "c1", content: "found" }] } },
      ],
      true,
    );
    const steps = items.filter((i) => i.kind === "tool").map((i) => (i.kind === "tool" ? i.step : null));
    expect(steps).toEqual([
      { key: "c1", name: "lookup", args: '{"id":1}', result: "found", failed: false },
      { key: "c2", name: "refund", args: '{"amount":5}', result: '{"error":"limit"}', failed: false },
      { key: "m1-t1", name: "tool", args: null, result: null, failed: false },
    ]);
  });
});

describe("chatItems keeps what it cannot read as prose", () => {
  it("keeps a content block that is neither text nor an object beside the text", () => {
    const [item] = chatItems([{ role: "assistant", raw: { role: "assistant", content: ["see", 42, null] } }], false);
    expect(item).toMatchObject({ kind: "message", text: "see", rest: [42, null] });
  });
});

describe("toolStepOf", () => {
  const tool = (over: Parameters<typeof span>[0]) => toolStepOf(span({ kind: "tool", ...over }));

  it("reads the tool's own answer out of the message that carried it, and fails on an error answer", () => {
    const step = tool({
      id: "t1",
      input: j([{ role: "assistant", parts: [{ type: "tool_call", arguments: { q: "refund" } }] }]),
      output: j([{ role: "tool", parts: [{ type: "tool_result", content: '{"error":"member_not_on_policy"}' }] }]),
    });
    expect(step).toEqual({ key: "t1", name: "tool", args: '{"q":"refund"}', result: '{"error":"member_not_on_policy"}', failed: true });
  });

  it("leaves anything that is not one message around one tool block exactly as sent", () => {
    for (const payload of [
      "plain text result",
      j([{ role: "tool" }, { role: "tool" }]),
      j(["not an object"]),
      j({ role: "tool", content: "a string body" }),
      j({ role: "tool", content: [{ type: "tool_result", content: "a" }, { type: "tool_result", content: "b" }] }),
      j({ role: "tool", content: [{ type: "tool_result" }] }),
    ]) {
      expect(tool({ id: "t", output: payload })!.result).toBe(payload);
    }
    expect(tool({ id: "t", output: "" })!.result).toBe("");
  });

  it("names a step from its call, then its attribute, then its span, and is not a tool without either", () => {
    expect(tool({ id: "a", tool_calls: [{ name: "from_call", args: null, result: "not json", error: null, latency_ms: null, retries: null }] })!.name).toBe("from_call");
    expect(toolStepOf(span({ id: "b", kind: "mcp", attributes: { "gen_ai.tool.call.id": "x", "gen_ai.tool.name": "from_attr" } }))!.name).toBe("from_attr");
    expect(tool({ id: "c", name: "from_span" })!.name).toBe("from_span");
    expect(toolStepOf(span({ id: "d", kind: "retrieval" }))).toBeNull();
    expect(tool({ id: "e", tool_calls: [{ name: "x", args: null, result: null, error: "timeout", latency_ms: null, retries: null }] })!.failed).toBe(true);
  });
});

describe("labels", () => {
  it("falls back from the span name to the GenAI operation to the kind", () => {
    expect(spanLabel({ name: "  ", attributes: { "gen_ai.operation.name": "embeddings" }, kind: "embedding" })).toBe("embeddings");
    expect(spanLabel({ name: null, attributes: { "gen_ai.operation.name": "" }, kind: "embedding" })).toBe("embedding");
    expect(spanLabel({})).toBe("step");
    expect(spanKindLabel("mcp")).toBe("mcp");
    expect(spanKindLabel("custom")).toBe("custom");
    expect(spanKindLabel(null)).toBe("step");
  });
});

describe("formats", () => {
  it("reads a long step in minutes, and tokens by magnitude", () => {
    expect(formatDuration(1_029_680)).toBe("17m 10s");
    expect(formatDuration(1_500)).toBe("1.50s");
    expect(formatDuration(null)).toBe("—");
    expect(formatTokens(2_345_678)).toBe("2.3M tok");
    expect(formatTokens(12)).toBe("12 tok");
    expect(formatTokens(undefined)).toBe("—");
    expect(clockLabel("nope")).toBe("—");
    expect(clockLabel(null)).toBe("—");
  });

  it("counts models across the spans when the trace row cannot say", () => {
    expect(traceSummary(undefined, [span({ id: "a", model: "m1" }), span({ id: "b", model: "m2" })])).toBe("2 steps · 2 models");
  });
});

describe("corrupt parent chains", () => {
  it("gives each span of a cycle a depth instead of recursing forever", () => {
    const spans = [span({ id: "a", parent_span_id: "b" }), span({ id: "b", parent_span_id: "a" })];
    expect(spanOrder(spans).map((s) => s.id)).toEqual(["a", "b"]);
    expect([...depthOf(spans, new Map()).values()].every((d) => d >= 0)).toBe(true);
  });

  it("draws a span id sent twice once", () => {
    const spans = [span({ id: "root" }), span({ id: "dup", parent_span_id: "root" }), span({ id: "dup", parent_span_id: "root" })];
    expect(spanOrder(spans).map((s) => s.id)).toEqual(["root", "dup"]);
  });

  it("ignores a trace end that does not follow its start", () => {
    const t = traceItem({ started_at: "2026-09-25T10:00:05Z", ended_at: "2026-09-25T10:00:00Z" });
    expect(traceBounds(t, [span({ id: "a", started_at: "2026-09-25T10:00:00Z", ended_at: "2026-09-25T10:00:03Z" })])).toEqual({
      start: Date.parse("2026-09-25T10:00:00Z"),
      end: Date.parse("2026-09-25T10:00:03Z"),
    });
  });
});

describe("sessionSummary", () => {
  it("says nothing before the session has been read", () => {
    expect(sessionSummary(undefined, false)).toBe("");
  });
});

describe("batchSteps", () => {
  const step = (key: string) => ({ key, name: key, args: null, result: null, failed: false });

  it("runs an unrequested execution into the calls just before it rather than starting a new group", () => {
    const llm = span({ id: "llm", kind: "llm" });
    const orphan = span({ id: "exec", kind: "tool", name: "lookup", attributes: { "gen_ai.tool.call.id": "x" } } as never);
    const groups = batchSteps([llm, orphan], {
      byLlm: new Map([["llm", [step("requested")]]]),
      orphans: new Map([["exec", step("orphan")]]),
      parentOf: new Map(),
    });

    expect(groups.map((g) => (g.kind === "tools" ? g.steps.map((s) => s.key) : g.span.id))).toEqual([
      "llm",
      ["requested", "orphan"],
    ]);
  });
});

describe("sessionBounds", () => {
  it("spans every trace it can place in time, skipping one with no times and no spans", () => {
    const placed = traceItem({ id: "a", started_at: "2026-09-25T10:00:00Z", ended_at: "2026-09-25T10:05:00Z" });
    const later = traceItem({ id: "b", started_at: "2026-09-25T11:00:00Z", ended_at: "2026-09-25T11:01:00Z" });
    const unplaced = traceItem({ id: "c", started_at: null, ended_at: null } as never);

    expect(sessionBounds([placed, unplaced, later], new Map())).toEqual({
      start: Date.parse("2026-09-25T10:00:00Z"),
      end: Date.parse("2026-09-25T11:01:00Z"),
    });
    expect(sessionBounds([unplaced], new Map())).toBeNull();
  });
});
