// SPDX-License-Identifier: Apache-2.0
// A trace is one turn, but each span carries a different slice of the dialogue and llm inputs are
// prefix-nested (every tool round-trip re-sends what came before). So the view is planned per trace:
// each message shown once, at the first span that carries it, with prior history hoisted and folded.
import { useLayoutEffect, useRef, useState } from "react";
import { cn } from "../../ui";
import { Markdown, PayloadBody, parsePayload } from "../components/PayloadViewer";
import type { ChatMessage } from "../components/PayloadViewer";
import { DetectionMarker } from "./detection-marker";
import { TOOL_CALL_ID, ToolCallBatch, toolStepFrom, toolStepOf } from "./detail-tool";
import type { ToolStep } from "./detail-tool";
import type { Span } from "./detail-data";

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => !!v && typeof v === "object" && !Array.isArray(v);

/**
 * Prose renders as prose and everything else (tool_use, tool_result, images) goes to PayloadBody, since
 * one message is routinely both. Null when `content` is neither string nor array.
 */
type ContentSplit = { text: string; rest: unknown[] };

function splitContent(content: unknown): ContentSplit | null {
  if (typeof content === "string") return { text: content, rest: [] };
  if (!Array.isArray(content)) return null;
  const parts: string[] = [];
  const rest: unknown[] = [];
  for (const block of content) {
    if (typeof block === "string") {
      parts.push(block);
    } else if (!isObj(block)) {
      rest.push(block);
    } else if (isToolBlock(block)) {
      // Before the prose branches: a `tool_result` carries its payload as a string `content`.
      rest.push(block);
    } else if (typeof block.text === "string") {
      parts.push(block.text);
    } else if (typeof block.content === "string") {
      parts.push(block.content);
    } else {
      rest.push(block);
    }
  }
  return { text: parts.join("\n\n"), rest };
}

/** Roled payloads nest blocks under `content` or `parts`, the shape the OTLP receiver normalises to. */
function bodyOf(message: ChatMessage): unknown {
  if (!isObj(message.raw)) return message.raw;
  return message.raw.content ?? message.raw.parts;
}

/** Ignores role: the same reply arrives roled from the agent span and roleless from the llm span. */
function signatureOf(message: ChatMessage): string {
  const split = splitContent(bodyOf(message));
  if (!split) return canonical(message.raw);
  const text = split.text.replace(/\s+/g, " ").trim();
  return `${text} ${split.rest.length > 0 ? canonical(split.rest) : ""}`;
}

/** `cache_control` is stamped only on replayed blocks, so the same turn arrives in different bytes. */
const TRANSPORT_KEYS = new Set(["cache_control"]);

function canonical(value: unknown): string {
  if (value === null || typeof value !== "object") return JSON.stringify(value) ?? "null";
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  const obj = value as Obj;
  return `{${Object.keys(obj)
    .filter((k) => !TRANSPORT_KEYS.has(k))
    .sort()
    .map((k) => `${JSON.stringify(k)}:${canonical(obj[k])}`)
    .join(",")}}`;
}

const isToolBlock = (b: unknown): boolean =>
  isObj(b) && typeof b.type === "string" && b.type.toLowerCase().includes("tool");

/** Both major SDKs deliver a `tool_result` under `role:"user"`. */
function isHumanSpeech(message: ChatMessage): boolean {
  const role = message.role.toLowerCase();
  if (role !== "user" && role !== "human") return false;
  const content = bodyOf(message);
  if (typeof content === "string") return true;
  if (!Array.isArray(content)) return false;
  return content.some((b) => !isToolBlock(b));
}

/** An llm span's own output is roleless but still the assistant speaking. */
function classify(message: ChatMessage): { label: string; side: "left" | "right" } {
  if (isHumanSpeech(message)) return { label: message.role || "user", side: "right" };
  const role = message.role.toLowerCase();
  if (role === "user" || role === "human") return { label: "tool result", side: "left" };
  return { label: message.role || "assistant", side: "left" };
}

function chatMessages(payload: string | null): ChatMessage[] | null {
  if (payload == null || payload === "") return null;
  const parsed = parsePayload(payload);
  return parsed.kind === "chat" ? parsed.messages : null;
}

export type SpanPlan = {
  input: ChatMessage[];
  output: ChatMessage[] | null;
};

export type ConversationPlan = {
  prior: ChatMessage[];
  bySpan: Map<string, SpanPlan>;
};

function chronological(spans: Span[]): Span[] {
  return [...spans].sort((a, b) => {
    const at = a.started_at;
    const bt = b.started_at;
    if (at !== bt) return at < bt ? -1 : 1;
    if (a.parent_span_id == null && b.parent_span_id != null) return -1;
    if (b.parent_span_id == null && a.parent_span_id != null) return 1;
    return 0;
  });
}

/**
 * The fullest input is the complete transcript. Everything before its final human turn is prior context;
 * each span then keeps only what no earlier span showed.
 */
export function planConversation(spans: Span[]): ConversationPlan {
  const ordered = chronological(spans);

  let fullest: ChatMessage[] = [];
  for (const o of ordered) {
    const msgs = chatMessages(o.input);
    if (msgs && msgs.length > fullest.length) fullest = msgs;
  }

  let lastHuman = -1;
  for (let i = fullest.length - 1; i >= 0; i--) {
    if (isHumanSpeech(fullest[i])) {
      lastHuman = i;
      break;
    }
  }
  const prior = lastHuman > 0 ? fullest.slice(0, lastHuman) : [];

  // The turn's question is drawn even when earlier history said the same words.
  const questionSig = lastHuman >= 0 ? signatureOf(fullest[lastHuman]) : null;
  let questionDrawn = false;
  const seen = new Set(prior.map(signatureOf));
  const take = (msgs: ChatMessage[]): ChatMessage[] => {
    let questionAt = -1;
    if (questionSig != null && !questionDrawn) {
      for (let i = msgs.length - 1; i >= 0; i--) {
        if (isHumanSpeech(msgs[i])) {
          questionAt = i;
          break;
        }
      }
    }
    const fresh: ChatMessage[] = [];
    msgs.forEach((m, i) => {
      const sig = signatureOf(m);
      if (i === questionAt && sig === questionSig) {
        questionDrawn = true;
      } else if (seen.has(sig)) {
        return;
      }
      seen.add(sig);
      fresh.push(m);
    });
    return fresh;
  };

  const bySpan = new Map<string, SpanPlan>();
  for (const o of ordered) {
    const inMsgs = chatMessages(o.input);
    const outMsgs = chatMessages(o.output);
    bySpan.set(o.id, {
      input: inMsgs ? take(inMsgs) : [],
      output: outMsgs ? take(outMsgs) : null,
    });
  }

  return { prior, bySpan };
}

/** Measured in items, not messages: a span of only tool_use/tool_result renders nothing. */
export function spanItems(plan: SpanPlan | undefined): ChatItem[] {
  if (!plan) return [];
  return [...chatItems(plan.input, false), ...chatItems(plan.output ?? [], false)];
}

export function messageItems(messages: ChatMessage[]): ChatItem[] {
  return chatItems(messages, false);
}

/**
 * The model's output says what was requested and an `execute_tool` span says what ran, and traces exist
 * with only one. Spans are matched by `gen_ai.tool.call.id`; name and order is the fallback, which
 * mismatches when a tool is called twice.
 */
export type ToolPlan = {
  byLlm: Map<string, ToolStep[]>;
  orphans: Map<string, ToolStep>;
  parentOf: Map<string, string>;
};

export function planTools(spans: Span[]): ToolPlan {
  const ordered = chronological(spans);

  // A tool's result reaches us in the next call's input when its span is missing.
  const results = new Map<string, string>();
  for (const o of ordered) {
    const msgs = chatMessages(o.input);
    if (!msgs) continue;
    for (const [id, value] of toolResults(msgs)) results.set(id, value);
  }

  const toolSpans = ordered.filter((o) => toolStepOf(o) != null);
  const claimed = new Set<string>();
  const byLlm = new Map<string, ToolStep[]>();
  const parentOf = new Map<string, string>();

  for (const o of ordered) {
    if (o.kind !== "llm") continue;
    const requests: Obj[] = [];
    for (const m of chatMessages(o.output) ?? []) {
      const body = bodyOf(m);
      if (!Array.isArray(body)) continue;
      for (const b of body) if (blockType(b) === "tool_use" && isObj(b)) requests.push(b);
    }
    if (requests.length === 0) continue;

    byLlm.set(
      o.id,
      requests.map((block, i) => {
        const name = typeof block.name === "string" ? block.name : "tool";
        const callId = typeof block.id === "string" ? block.id : null;
        const span =
          (callId != null
            ? toolSpans.find((s) => !claimed.has(s.id) && s.attributes?.[TOOL_CALL_ID] === callId)
            : undefined) ??
          toolSpans.find((s) => !claimed.has(s.id) && toolStepOf(s)?.name === name);
        if (span) {
          claimed.add(span.id);
          parentOf.set(span.id, o.id);
          const step = toolStepOf(span);
          if (step) return step;
        }
        return toolStepFrom({
          key: callId ?? `${o.id}-${i}`,
          name,
          args: toolArgs(block) != null ? JSON.stringify(toolArgs(block)) : null,
          result: callId != null ? (results.get(callId) ?? null) : null,
        });
      }),
    );
  }

  const orphans = new Map<string, ToolStep>();
  for (const s of toolSpans) {
    if (claimed.has(s.id)) continue;
    const step = toolStepOf(s);
    if (step) orphans.set(s.id, step);
  }

  return { byLlm, orphans, parentOf };
}

export type ChatItem =
  | { kind: "message"; message: ChatMessage; text: string; rest: unknown[] }
  | { kind: "tool"; step: ToolStep };

/** A `parts` payload calls a request `tool_call` where a content block calls it `tool_use`. */
const blockType = (b: unknown): string => {
  if (!isObj(b) || typeof b.type !== "string") return "";
  const type = b.type.toLowerCase();
  return type === "tool_call" ? "tool_use" : type;
};

const toolArgs = (b: Obj): unknown => b.input ?? b.arguments;

function callIdOf(b: Obj): string | null {
  if (typeof b.tool_use_id === "string") return b.tool_use_id;
  return typeof b.id === "string" ? b.id : null;
}

function toolResults(messages: ChatMessage[]): Map<string, string> {
  const byId = new Map<string, string>();
  for (const m of messages) {
    const content = bodyOf(m);
    if (!Array.isArray(content)) continue;
    for (const b of content) {
      if (blockType(b) !== "tool_result" || !isObj(b)) continue;
      const id = callIdOf(b);
      if (id == null) continue;
      byId.set(id, typeof b.content === "string" ? b.content : JSON.stringify(b.content ?? null));
    }
  }
  return byId;
}

function isOnlyToolResults(message: ChatMessage): boolean {
  const content = bodyOf(message);
  if (!Array.isArray(content) || content.length === 0) return false;
  return content.every((b) => blockType(b) === "tool_result");
}

/**
 * `deriveTools` is false where `execute_tool` spans record the same calls, so they are not shown twice;
 * true for prior context, where the blocks are the only record.
 */
export function chatItems(messages: ChatMessage[], deriveTools: boolean): ChatItem[] {
  const results = deriveTools ? toolResults(messages) : new Map<string, string>();
  const items: ChatItem[] = [];

  messages.forEach((message, mi) => {
    if (isOnlyToolResults(message)) return;
    const content = bodyOf(message);
    const split = splitContent(content);
    const toolUses = Array.isArray(content) ? content.filter((b): b is Obj => blockType(b) === "tool_use") : [];
    // Drops tool blocks from a mixed message, where the reader wants the screenshot, not the JSON around it.
    const rest = (split?.rest ?? []).filter(
      (b) => blockType(b) !== "tool_use" && blockType(b) !== "tool_result",
    );
    const text = split?.text ?? "";

    if (split == null) {
      items.push({ kind: "message", message, text: "", rest: [message.raw] });
    } else if (text.length > 0 || rest.length > 0) {
      items.push({ kind: "message", message, text, rest });
    }

    if (!deriveTools) return;

    for (const [ti, block] of toolUses.entries()) {
      const id = typeof block.id === "string" ? block.id : null;
      items.push({
        kind: "tool",
        step: {
          key: id ?? `m${mi}-t${ti}`,
          name: typeof block.name === "string" ? block.name : "tool",
          args: toolArgs(block) != null ? JSON.stringify(toolArgs(block)) : null,
          result: id != null ? (results.get(id) ?? null) : null,
          failed: false,
        },
      });
    }
  });

  return items;
}

/** Consecutive tool calls were asked for together, so they render on one line. */
type MessageItem = Extract<ChatItem, { kind: "message" }>;
export type GroupedItem = MessageItem | { kind: "tools"; steps: ToolStep[] };

export function groupTools(items: ChatItem[]): GroupedItem[] {
  const out: GroupedItem[] = [];
  for (const item of items) {
    const last = out[out.length - 1];
    if (item.kind === "tool" && last?.kind === "tools") last.steps.push(item.step);
    else if (item.kind === "tool") out.push({ kind: "tools", steps: [item.step] });
    else out.push(item);
  }
  return out;
}

/** `judged` is the part a classifier ruled on; `matched` a part it read as context. */
export type Emphasis = { part: "question" | "reply" | "step"; kind: "judged" | "matched"; label: string };

const NO_EMPHASIS: Emphasis[] = [];

function lastMessage(grouped: GroupedItem[], pick: (g: MessageItem) => boolean): number {
  for (let i = grouped.length - 1; i >= 0; i--) {
    const g = grouped[i];
    if (g.kind === "message" && pick(g)) return i;
  }
  return -1;
}

function Items({
  items,
  failed,
  marks = [],
  agentMarks = [],
  emphasis = NO_EMPHASIS,
  dimmed = false,
}: {
  items: ChatItem[];
  failed?: boolean;
  marks?: string[];
  agentMarks?: string[];
  emphasis?: Emphasis[];
  dimmed?: boolean;
}) {
  const grouped = groupTools(items);
  const markedAt = marks.length > 0 ? lastMessage(grouped, (g) => isHumanSpeech(g.message)) : -1;
  const agentMarkedAt = agentMarks.length > 0 ? lastMessage(grouped, (g) => !isHumanSpeech(g.message)) : -1;
  const question = emphasis.some((e) => e.part === "question");
  const emphasisAt =
    emphasis.length > 0 ? lastMessage(grouped, (g) => !question || isHumanSpeech(g.message)) : -1;
  return (
    <div className="flex flex-col gap-2">
      {grouped.map((group, i) =>
        group.kind === "tools" ? (
          <ToolCallBatch key={`t${i}`} steps={group.steps} />
        ) : (
          <Turn
            key={`m${i}`}
            item={group}
            failed={failed}
            marks={[...(i === markedAt ? marks : []), ...(i === agentMarkedAt ? agentMarks : [])]}
            emphasis={i === emphasisAt ? emphasis : NO_EMPHASIS}
            dimmed={dimmed}
          />
        ),
      )}
    </div>
  );
}

export function PriorContext({ messages }: { messages: ChatMessage[] }) {
  const [open, setOpen] = useState(false);
  if (messages.length === 0) return null;
  return (
    <div className="flex flex-col gap-2">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="self-center text-subtle hover:text-muted transition-colors cursor-pointer text-label"
        style={{ transitionDuration: "var(--duration-micro)" }}
      >
        {open
          ? "Hide earlier conversation"
          : `Show ${messages.length} earlier ${messages.length === 1 ? "message" : "messages"}`}
      </button>
      {open && (
        <div className="pl-3" style={{ borderLeft: "1px solid var(--color-border)" }}>
          <Items items={chatItems(messages, true)} />
        </div>
      )}
    </div>
  );
}

/**
 * `marks` is drawn on the person's message only, `agentMarks` on the last thing the agent said; neither is
 * `failed`, since nothing broke.
 */
export function ChatItems({
  items,
  failed,
  marks,
  agentMarks,
  emphasis,
  dimmed,
}: {
  items: ChatItem[];
  failed?: boolean;
  marks?: string[];
  agentMarks?: string[];
  emphasis?: Emphasis[];
  dimmed?: boolean;
}) {
  return (
    <Items items={items} failed={failed} marks={marks} agentMarks={agentMarks} emphasis={emphasis} dimmed={dimmed} />
  );
}

function Turn({
  item,
  failed,
  marks,
  emphasis,
  dimmed,
}: {
  item: Extract<ChatItem, { kind: "message" }>;
  failed?: boolean;
  marks: string[];
  emphasis: Emphasis[];
  dimmed: boolean;
}) {
  const { label, side } = classify(item.message);
  const flags = [...marks, ...emphasis.filter((e) => e.kind === "judged").map((e) => e.label)];
  const matched = emphasis.filter((e) => e.kind === "matched").map((e) => e.label);
  const edge = flags.length > 0 ? "judged" : matched.length > 0 ? "matched" : undefined;
  const align = side === "right" ? "self-end" : "self-start";

  return (
    <div className="flex flex-col gap-0.75">
      {label && (
        <span
          className={cn("text-label font-mono uppercase text-subtle", side === "right" ? "self-end" : "self-start")}
        >
          {label}
        </span>
      )}
      <Bubble side={side} failed={failed} edge={edge} dimmed={dimmed}>
        <Clamped>
          <div className="flex flex-col gap-2">
            {item.text.length > 0 && <Markdown>{item.text}</Markdown>}
            {item.rest.length > 0 && <PayloadBody payload={JSON.stringify(item.rest)} />}
          </div>
        </Clamped>
      </Bubble>
      {flags.length > 0 && <DetectionMarker names={flags} className={align} />}
      {matched.map((m) => (
        <span key={m} className={cn("text-label text-muted", align)}>
          {m}
        </span>
      ))}
    </div>
  );
}

/** Ten lines: a pasted prompt or dumped file pushed every later turn off screen. */
const CLAMP_EM = 16.5;

/**
 * Measured, not guessed: a bubble can hold tables, code and JSON trees. The observer watches the content,
 * since the clamped box's height is pinned.
 */
function Clamped({ children }: { children: React.ReactNode }) {
  const outer = useRef<HTMLDivElement>(null);
  const inner = useRef<HTMLDivElement>(null);
  const [overflows, setOverflows] = useState(false);
  const [open, setOpen] = useState(false);

  useLayoutEffect(() => {
    const box = outer.current!;
    const content = inner.current!;
    const measure = () => {
      const limit = parseFloat(getComputedStyle(box).fontSize) * CLAMP_EM;
      setOverflows(content.getBoundingClientRect().height > limit + 2);
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(content);
    return () => observer.disconnect();
  }, []);

  const folded = overflows && !open;

  return (
    <div className="flex flex-col gap-1.5">
      <div
        ref={outer}
        style={{
          position: "relative",
          maxHeight: folded ? `${CLAMP_EM}em` : undefined,
          overflow: folded ? "hidden" : undefined }}
      >
        <div ref={inner}>{children}</div>
        {folded && (
          // The fade uses the bubble's own custom property to match whichever side it sits in.
          <div
            aria-hidden="true"
            style={{
              position: "absolute",
              insetInline: 0,
              bottom: 0,
              height: "3em",
              background: "linear-gradient(to bottom, transparent, var(--bubble-bg))",
              pointerEvents: "none" }}
          />
        )}
      </div>
      {overflows && (
        <button
          type="button"
          onClick={() => setOpen((v) => !v)}
          className="self-start inline-flex items-center text-subtle hover:text-fg transition-colors cursor-pointer gap-1 text-label"
          style={{ transitionDuration: "var(--duration-micro)" }}
        >
          <svg
            viewBox="0 0 24 24"
            aria-hidden="true"
            className="shrink-0"
            style={{
              width: 12,
              height: 12,
              fill: "currentColor",
              transform: open ? "rotate(180deg)" : "none",
              transition: "transform var(--duration-micro)" }}
          >
            <path d="M6 9l6 6 6-6z" />
          </svg>
          {open ? "Show less" : "Show more"}
        </button>
      )}
    </div>
  );
}

function Bubble({
  side,
  failed,
  edge,
  dimmed,
  children,
}: {
  side: "left" | "right";
  failed?: boolean;
  edge?: "judged" | "matched";
  dimmed?: boolean;
  children: React.ReactNode;
}) {
  const right = side === "right";
  return (
    <div className={cn("flex", right ? "justify-end" : "justify-start")}>
      <div
        data-flagged={edge === "judged" ? "true" : undefined}
        className={cn(
          "chat-bubble min-w-0 text-body py-2.5 px-3.5",
          right ? "bg-raised" : "bg-bg",
          dimmed ? "text-muted" : "text-fg",
          !right && "border border-border",
        )}
        style={
          {
            maxWidth: 720,
            borderRadius: right ? "12px 12px 4px 12px" : "12px 12px 12px 4px",
            wordBreak: "break-word",
            "--bubble-bg": right ? "var(--color-raised)" : "var(--color-bg)",
            ...(failed ? { borderColor: "var(--color-error)" } : null),
            ...(edge === "judged" ? { border: "1px solid var(--color-error)" } : null),
            ...(edge === "matched" ? { border: "1px dashed var(--color-border-strong)" } : null),
          } as React.CSSProperties
        }
      >
        {children}
      </div>
    </div>
  );
}
