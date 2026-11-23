// SPDX-License-Identifier: Apache-2.0
/*
 * Emphasis on the conversation view: a caller names, per span, the part a classifier judged and the parts
 * it read as context. The judged part is drawn as a flagged message is, a matched part with a dashed edge,
 * each with its label under it, and every other part of that trace is dimmed so the reader sees what was
 * read. A trace with no entry is drawn as it always was.
 */
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { api } from "../../test/api";
import { span, traceItem } from "../../test/fixtures";
import { currentLocation, renderRoute } from "../../test/render";
import { ConversationView, SessionConversationView, TreeView, type EmphasisBySpan } from "./detail-views";

vi.mock("../../tenant/TenantContext", () => import("../../test/api"));
let promptsOn = true;
vi.mock("../../capabilities/useCapabilities", () => ({
  useCapabilities: () => ({ isEnabled: (c: string) => c === "prompt_management_enabled" && promptsOn }),
}));

describe("the prompt chip on a span row", () => {
  const tree = (over: Parameters<typeof span>[0]) =>
    renderRoute(<TreeView spans={[span({ name: "summarize", ...over })]} focusId={null} onSelect={() => {}} />);

  // Bug: a span made from a managed prompt gives the reader no way to open that prompt and version.
  it("links a linked span to its prompt at the version it sent", async () => {
    promptsOn = true;
    api.listPrompts.mockResolvedValue({ items: [{ id: "pr_1" }], page: 1, limit: 50, total: 1 });
    tree({ id: "s1", prompt_name: "p", prompt_version: "3" });

    const link = await screen.findByRole("link", { name: "Open prompt p v3" });
    expect(link.getAttribute("href")).toBe("/orgs/acme/projects/default/prompts/pr_1?version=3");
    expect(api.listPrompts).toHaveBeenCalledWith({ name: "p" });
  });

  // Bug: the chip sits inside the row's select button, so opening the prompt also selects the span.
  it("opens the prompt without selecting the span", async () => {
    promptsOn = true;
    api.listPrompts.mockResolvedValue({ items: [{ id: "pr_1" }], page: 1, limit: 50, total: 1 });
    const onSelect = vi.fn();
    renderRoute(
      <TreeView spans={[span({ id: "s1", prompt_name: "p", prompt_version: "3" })]} focusId={null} onSelect={onSelect} />,
    );

    fireEvent.click(await screen.findByRole("link", { name: "Open prompt p v3" }));
    expect(onSelect).not.toHaveBeenCalled();
    expect(currentLocation()).toBe("/orgs/acme/projects/default/prompts/pr_1?version=3");
  });

  // Bug: the link was nested inside the row's select button, which is invalid markup: assistive tech folds the link
  // into the button's name and does not offer it as a link of its own.
  it("puts the link outside the row's select button", async () => {
    promptsOn = true;
    api.listPrompts.mockResolvedValue({ items: [{ id: "pr_1" }], page: 1, limit: 50, total: 1 });
    tree({ id: "s1", prompt_name: "p", prompt_version: "3" });

    const link = await screen.findByRole("link", { name: "Open prompt p v3" });
    expect(link.closest("button")).toBeNull();
  });

  // Bug: a span that sent a label linked to ?version=production, which the detail page reads as no version, so it
  // opened the latest version instead of the one the label is on.
  it.each([
    ["production", "/orgs/acme/projects/default/prompts/pr_1?version=2"],
    ["canary", "/orgs/acme/projects/default/prompts/pr_1"],
  ])("links a span that sent the label %s to the version that carries it", async (label, href) => {
    promptsOn = true;
    api.listPrompts.mockResolvedValue({
      items: [{ id: "pr_1", labels: [{ label: "production", version: 2 }] }], page: 1, limit: 50, total: 1,
    });
    tree({ id: "s1", prompt_name: "p", prompt_version: label });

    const link = await screen.findByRole("link", { name: `Open prompt p (${label})` });
    expect(link.getAttribute("href")).toBe(href);
  });

  it("links to the prompt alone when the span sent no version", async () => {
    promptsOn = true;
    api.listPrompts.mockResolvedValue({ items: [{ id: "pr_1" }], page: 1, limit: 50, total: 1 });
    tree({ id: "s1", prompt_name: "p", prompt_version: null });

    const link = await screen.findByRole("link", { name: "Open prompt p" });
    expect(link.getAttribute("href")).toBe("/orgs/acme/projects/default/prompts/pr_1");
  });

  // Bug: a name with no prompt in the project renders a link to a page that does not exist.
  it("shows a plain badge when no prompt in the project has that name", async () => {
    promptsOn = true;
    api.listPrompts.mockResolvedValue({ items: [], page: 1, limit: 50, total: 0 });
    tree({ id: "s1", prompt_name: "p", prompt_version: "prod" });

    expect((await screen.findByTitle("No prompt with this name in the project")).textContent).toBe("p (prod)");
    expect(screen.queryByRole("link")).toBeNull();
  });

  // Bug: an org that turned prompt management off still reads the prompts API from every trace.
  it("shows a plain badge and reads nothing when prompt management is off", () => {
    promptsOn = false;
    tree({ id: "s1", prompt_name: "p", prompt_version: "3" });

    expect(screen.getByText("p v3")).toBeTruthy();
    expect(screen.queryByRole("link")).toBeNull();
    expect(api.listPrompts).not.toHaveBeenCalled();
  });

  it("shows no chip on a span made from no prompt", () => {
    promptsOn = true;
    tree({ id: "s1", prompt_name: null, prompt_version: null });

    expect(screen.getByText("summarize")).toBeTruthy();
    expect(screen.queryByText(/^p\b/)).toBeNull();
    expect(api.listPrompts).not.toHaveBeenCalled();
  });
});

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
      span({ id: "t2-tool", trace_id: "t2", parent_span_id: "t2-agent", kind: "tool", started_at: "2026-09-25T10:00:00.700Z" }),
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

    // The emphasised trace's step, tool call and reply are dimmed, each with the caller's note; its question is not.
    expect(screen.getAllByText("Not read for this flag.")).toHaveLength(3);
    expect(bubble("Second step").className).toContain("text-muted");
    expect(bubble("Second reply").className).toContain("text-muted");
    expect(bubble("Second question").className).not.toContain("text-muted");
    // The trace with no entry reads as it always did.
    for (const text of ["First question", "First step", "First reply"]) {
      expect(bubble(text).className).not.toContain("text-muted");
    }
  });
});

// Bug: a turn mark pinned on the system prompt, as if the person had said it, when the turn holds no words of theirs.
it("draws a turn mark on no message when the turn has none from the person", () => {
  const input = j([{ role: "system", content: "Run the nightly sweep." }]);
  render(<ConversationView spans={[span({ id: "r", kind: "agent", input })]} focusId={null} marks={["Frustration"]} />);

  expect(screen.getByText("Run the nightly sweep.")).toBeTruthy();
  expect(screen.queryByText("Frustration")).toBeNull();
});
