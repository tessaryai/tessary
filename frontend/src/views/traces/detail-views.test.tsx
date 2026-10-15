// SPDX-License-Identifier: Apache-2.0
/*
 * Emphasis on the conversation view: a caller names, per span, the part a classifier judged and the parts
 * it read as context. The judged part is drawn as a flagged message is, a matched part with a dashed edge,
 * each with its label under it, and every other part of that trace is dimmed so the reader sees what was
 * read. A trace with no entry is drawn as it always was.
 */
import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { span, traceItem } from "../../test/fixtures";
import { ConversationView, SessionConversationView, type EmphasisBySpan } from "./detail-views";

const j = (v: unknown) => JSON.stringify(v);

/** One turn: the agent span holds the question and the reply, one llm call between them says a step. */
function turn(trace: string, question: string, step: string, reply: string) {
  return [
    span({
      id: `${trace}-agent`,
      trace_id: trace, kind: "agent", name: "invoke_agent", started_at: "2026-09-25T10:00:00.000Z",
      input: j([{ role: "user", content: question }]),
      output: j([{ role: "assistant", content: reply }]),
    }),
    span({
      id: `${trace}-llm`,
      trace_id: trace,
      parent_span_id: `${trace}-agent`,
      kind: "llm",
      started_at: "2026-09-25T10:00:00.500Z",
      input: j([{ role: "user", content: question }]),
      output: j([{ role: "assistant", content: step }]),
    }),
  ];
}

const bubble = (text: string) => screen.getByText(text).closest(".chat-bubble") as HTMLElement;

describe("ConversationView emphasis", () => {
  it("marks the judged part with the error border and its label", () => {
    const emphasis: EmphasisBySpan = new Map([
      ["t1-agent", [{ part: "question", kind: "judged", label: "Judged part one" }]],
    ]);
    render(
      <ConversationView
        spans={turn("t1", "Where is my order?", "Looking it up.", "It ships today.")}
        focusId={null}
        emphasis={emphasis}
      />,
    );

    const judged = bubble("Where is my order?");
    expect(judged.style.border).toBe("1px solid var(--color-error)");
    expect(judged.dataset.flagged).toBe("true");
    expect(screen.getByText("Judged part one")).toBeTruthy();
    expect(bubble("It ships today.").dataset.flagged).toBeUndefined();
  });

  it("draws a matched part with a dashed border", () => {
    const emphasis: EmphasisBySpan = new Map([
      ["t1-llm", [{ part: "step", kind: "matched", label: "Matched step" }]],
      ["t1-agent", [{ part: "reply", kind: "matched", label: "Matched reply" }]],
    ]);
    render(
      <ConversationView
        spans={turn("t1", "Where is my order?", "Looking it up.", "It ships today.")}
        focusId={null}
        emphasis={emphasis}
      />,
    );

    for (const text of ["Looking it up.", "It ships today."]) {
      const matched = bubble(text);
      expect(matched.style.border).toBe("1px dashed var(--color-border-strong)");
      expect(matched.dataset.flagged).toBeUndefined();
    }
    expect(screen.getByText("Matched step")).toBeTruthy();
    expect(screen.getByText("Matched reply")).toBeTruthy();
    expect(bubble("Where is my order?").style.border).toBe("");
  });

  it("dims the parts of a trace with no emphasis entry and leaves a trace with none untouched", () => {
    const spans = [
      ...turn("t1", "First question", "First step", "First reply"),
      ...turn("t2", "Second question", "Second step", "Second reply"),
    ];
    const emphasis: EmphasisBySpan = new Map([
      ["t2-agent", [{ part: "question", kind: "judged", label: "Judged part" }]],
    ]);
    render(
      <SessionConversationView
        traces={[traceItem({ id: "t1" }), traceItem({ id: "t2" })]}
        spansByTrace={new Map([
          ["t1", spans.filter((o) => o.trace_id === "t1")],
          ["t2", spans.filter((o) => o.trace_id === "t2")],
        ])}
        focusId={null}
        emphasis={emphasis}
        contextNote="Not read for this flag."
      />,
    );

    // The emphasised trace's step and reply are dimmed, each with the caller's note; its question is not.
    expect(screen.getAllByText("Not read for this flag.")).toHaveLength(2);
    expect(bubble("Second step").className).toContain("text-muted");
    expect(bubble("Second reply").className).toContain("text-muted");
    expect(bubble("Second question").className).not.toContain("text-muted");
    // The trace with no entry reads as it always did.
    for (const text of ["First question", "First step", "First reply"]) {
      expect(bubble(text).className).not.toContain("text-muted");
    }
  });
});
