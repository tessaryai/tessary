// SPDX-License-Identifier: Apache-2.0
/*
 * The Conversation view's problem is that a trace is one *turn*, but its spans
 * each carry a different slice of the whole dialogue:
 *
 *   agent span   input [user]                output [assistant]      ← the turn
 *   llm   span   input [user…assistant,user] output [text|tool_use]  ← + all history
 *   tool  span   input {args}                output {result}         ← not messages
 *
 * and the llm inputs of one trace are prefix-nested (arr[3] ⊂ arr[5] ⊂ arr[31]),
 * because every tool round-trip re-sends everything before it. Rendering each
 * span's payload independently therefore prints the same history once per span
 * and prints the turn itself twice — once from the agent, once as the tail of
 * the llm call.
 *
 * So the view is planned for the trace, not the span: one transcript, each
 * message shown once, at the first span that carries it. The history that
 * precedes the turn is hoisted out and collapsed, because it is context the
 * reader may want but did not come for.
 */
import { useLayoutEffect, useRef, useState } from "react";
import { cn } from "../../ui";
import { Markdown, PayloadBody, parsePayload } from "../components/PayloadViewer";
import type { ChatMessage } from "../components/PayloadViewer";
import { TOOL_CALL_ID, ToolCallBatch, toolStepFrom, toolStepOf } from "./detail-tool";
import type { ToolStep } from "./detail-tool";
import type { Span } from "./detail-data";

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => !!v && typeof v === "object" && !Array.isArray(v);

/* ------------------------------------------------------------------ content */

/**
 * One message's `content` divided into prose and everything else. Covers the
 * three shapes that reach us: a plain string, OpenAI/Anthropic content blocks
 * (`{type:"text", text}`), and the platform's own (`{type, content}`).
 *
 * The split matters because a turn is routinely both: an assistant says "let me
 * check that for you" *and* emits `tool_use` blocks in the same message.
 * Flattening the whole thing loses the structure; refusing to flatten any of it
 * loses the sentence. So prose renders as prose, and the blocks this cannot
 * speak for — `tool_use`, `tool_result`, images — go to {@link PayloadBody},
 * which shows structure and decodes images rather than dropping them.
 *
 * Returns null when `content` is neither string nor array, leaving the caller to
 * render the message whole.
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
      // Before the prose branches: a `tool_result` part carries its payload as a
      // *string* `content`, so reading content-before-type would print a tool's
      // JSON output as if the model had said it.
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

/**
 * A roleless payload is the content-block array itself; a roled one nests it
 * under `content` (OpenAI/Anthropic) or under `parts` — the shape the OTLP
 * receiver normalises to and the one `ContentExtractor` reads on the backend.
 * Reading only `content` left every `parts` trace with no body at all, so the
 * whole message fell through to being printed as raw JSON.
 */
function bodyOf(message: ChatMessage): unknown {
  if (!isObj(message.raw)) return message.raw;
  return message.raw.content ?? message.raw.parts;
}

/**
 * Identity for dedup, deliberately ignoring role. The same reply arrives once as
 * the agent's `[{role:"assistant",…}]` and once as the llm's roleless
 * `[{type:"text",…}]`; keying on role would keep both and print it twice.
 */
function signatureOf(message: ChatMessage): string {
  const split = splitContent(bodyOf(message));
  if (!split) return canonical(message.raw);
  const text = split.text.replace(/\s+/g, " ").trim();
  return `${text} ${split.rest.length > 0 ? canonical(split.rest) : ""}`;
}

/**
 * Transport annotations that say nothing about what was said. `cache_control` is
 * stamped onto a block only when it is *replayed* into a later request for
 * prompt caching, so one assistant turn reaches us as
 * `{id, caller, input, name, type}` from the llm span that produced it and as
 * `{id, caller, input, name, type, cache_control}` from the span that replayed
 * it. Same turn, different bytes — enough to defeat dedup and print it twice.
 */
const TRANSPORT_KEYS = new Set(["cache_control"]);

/** Stable JSON for identity: transport noise dropped, key order made irrelevant. */
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

/**
 * True when this is a person talking. A `tool_result` is delivered under
 * `role:"user"` by both major SDKs, so role alone would put the tool's output in
 * the human's seat — right-aligned and labelled USER, which is what made the
 * transcript look like it was swapping sides.
 */
function isHumanSpeech(message: ChatMessage): boolean {
  const role = message.role.toLowerCase();
  if (role !== "user" && role !== "human") return false;
  const content = bodyOf(message);
  if (typeof content === "string") return true;
  if (!Array.isArray(content)) return false;
  return content.some((b) => !isToolBlock(b));
}

/**
 * How a message is labelled and placed. An llm span's own output carries no
 * role — it is a bare content-block array — but it is still the assistant
 * speaking, and leaving it blank put an unlabelled bubble next to an identical
 * labelled one further down the same transcript.
 */
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

/* ------------------------------------------------------------------ planning */

export type SpanPlan = {
  /** Input messages this span is the first to carry. */
  input: ChatMessage[];
  /** Output messages this span is the first to carry, or null when not a chat payload. */
  output: ChatMessage[] | null;
  /** True when the span's payloads are not messages at all (a tool's args/result). */
  opaque: boolean;
};

export type ConversationPlan = {
  /** Dialogue that precedes this trace's turn — real, but not what the reader came for. */
  prior: ChatMessage[];
  bySpan: Map<string, SpanPlan>;
};

/** Root before child, then by clock: the order the dialogue actually happened in. */
function chronological(spans: Span[]): Span[] {
  return [...spans].sort((a, b) => {
    const at = a.started_at ?? "";
    const bt = b.started_at ?? "";
    if (at !== bt) return at < bt ? -1 : 1;
    if (a.parent_span_id == null && b.parent_span_id != null) return -1;
    if (b.parent_span_id == null && a.parent_span_id != null) return 1;
    return 0;
  });
}

/**
 * Decide, once for the whole trace, who says what and where the history stops.
 *
 * The fullest input in the trace is the complete transcript — the last llm call
 * re-sends everything. Everything in it before the final human turn is prior
 * context; from that turn on is this trace. Each span then keeps only what no
 * earlier span already showed.
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

  const seen = new Set(prior.map(signatureOf));
  const take = (msgs: ChatMessage[]): ChatMessage[] => {
    const fresh: ChatMessage[] = [];
    for (const m of msgs) {
      const sig = signatureOf(m);
      if (seen.has(sig)) continue;
      seen.add(sig);
      fresh.push(m);
    }
    return fresh;
  };

  const bySpan = new Map<string, SpanPlan>();
  for (const o of ordered) {
    const inMsgs = chatMessages(o.input);
    const outMsgs = chatMessages(o.output);
    bySpan.set(o.id, {
      input: inMsgs ? take(inMsgs) : [],
      output: outMsgs ? take(outMsgs) : null,
      opaque: inMsgs == null && outMsgs == null,
    });
  }

  return { prior, bySpan };
}

/**
 * What a span will actually draw.
 *
 * This has to be measured in items, not messages: a span whose every new message
 * is a `tool_use` or a `tool_result` has messages but renders nothing, because
 * those are shown as pills instead. Asking the plan "do you have messages" left
 * an empty outlined box on screen where the span should have been skipped.
 */
export function spanItems(plan: SpanPlan | undefined): ChatItem[] {
  if (!plan) return [];
  return [...chatItems(plan.input, false), ...chatItems(plan.output ?? [], false)];
}

/** What a stretch of messages will render as; empty means draw nothing at all. */
export function messageItems(messages: ChatMessage[]): ChatItem[] {
  return chatItems(messages, false);
}

/* ------------------------------------------------------------- tool calls */

/**
 * Every tool call the trace made, attributed to the llm call that asked for it.
 *
 * Two records exist and neither is complete on its own. The model's output is
 * the authoritative statement of what was *requested*; the `execute_tool` span
 * is the record of what actually *ran*, with the latency and outcome. Traces
 * exist with one and not the other — one in forty here calls a tool, gets its
 * result back, and records no tool span at all — so reading only the spans
 * showed no tool calls whatsoever for that turn.
 *
 * So: walk the requests, claim a matching span for each where one exists, and
 * keep any span nothing claimed. Attribution is exact — the span carries the
 * request's own id as `gen_ai.tool.call.id` (OTel GenAI semconv), which matched
 * 15/15 requests across the sampled traces. Name-and-order is only the fallback
 * for a producer that omits it, and mismatches whenever a tool is called twice.
 */
export type ToolPlan = {
  /** Tool calls requested by a given llm span, in the order it asked. */
  byLlm: Map<string, ToolStep[]>;
  /** Executions no recorded request accounts for; shown where they ran. */
  orphans: Map<string, ToolStep>;
  /** Tool span id → the llm span whose output requested it, for display nesting. */
  parentOf: Map<string, string>;
};

export function planTools(spans: Span[]): ToolPlan {
  const ordered = chronological(spans);

  // A tool's result reaches us in the *next* call's input, never in its own span
  // when that span is missing — so harvest every replayed input in the trace.
  const results = new Map<string, string>();
  for (const o of ordered) {
    const msgs = chatMessages(o.input);
    if (!msgs) continue;
    for (const [id, value] of toolResults(msgs)) results.set(id, value);
  }

  // Any span that carries a request id is an execution, whatever kind it claims.
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

/* -------------------------------------------------------------- chat items */

/**
 * What a stretch of messages actually renders as. A tool call is pulled out of
 * the message it was embedded in, because `tool_use` is not speech and reading
 * it as JSON inside a chat bubble is what made these traces unreadable.
 */
export type ChatItem =
  | { kind: "message"; message: ChatMessage; text: string; rest: unknown[] }
  | { kind: "tool"; step: ToolStep };

/**
 * A block's type, with the two vocabularies folded into one: a `parts` payload
 * calls a request `tool_call` where a content block calls it `tool_use`. Every
 * tool-aware branch below keys on this, so the fold happens once, here.
 */
const blockType = (b: unknown): string => {
  if (!isObj(b) || typeof b.type !== "string") return "";
  const type = b.type.toLowerCase();
  return type === "tool_call" ? "tool_use" : type;
};

/** A request's arguments: `input` on a content block, `arguments` on a part. */
const toolArgs = (b: Obj): unknown => b.input ?? b.arguments;

/** The call a block belongs to: `tool_use_id` on a content block, `id` on a part. */
function callIdOf(b: Obj): string | null {
  if (typeof b.tool_use_id === "string") return b.tool_use_id;
  return typeof b.id === "string" ? b.id : null;
}

/** `{type:"tool_result", tool_use_id, content}` indexed by the call it answers. */
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

/** A message made of nothing but tool results is the transport talking, not a turn. */
function isOnlyToolResults(message: ChatMessage): boolean {
  const content = bodyOf(message);
  if (!Array.isArray(content) || content.length === 0) return false;
  return content.every((b) => blockType(b) === "tool_result");
}

/**
 * Flatten messages into what the reader sees.
 *
 * `deriveTools` is false wherever the trace also recorded `execute_tool` spans
 * for the same calls — those spans carry the real latency and outcome, so the
 * embedded `tool_use` blocks are dropped rather than shown twice. It is true for
 * prior context, where the earlier turns' spans are not part of this trace and
 * the message blocks are the only record left.
 */
export function chatItems(messages: ChatMessage[], deriveTools: boolean): ChatItem[] {
  const results = deriveTools ? toolResults(messages) : new Map<string, string>();
  const items: ChatItem[] = [];

  messages.forEach((message, mi) => {
    if (isOnlyToolResults(message)) return;
    const content = bodyOf(message);
    const split = splitContent(content);
    const toolUses = Array.isArray(content) ? content.filter((b) => blockType(b) === "tool_use") : [];
    // A tool's request and its answer are both shown as a pill, so neither
    // belongs in the bubble. `isOnlyToolResults` above drops the messages that
    // are nothing but transport; this drops the block from a *mixed* message —
    // the one that carries a `tool_result` alongside an `image_ref`, where the
    // reader wants the screenshot and not the JSON wrapped around it.
    const rest = (split?.rest ?? []).filter(
      (b) => blockType(b) !== "tool_use" && blockType(b) !== "tool_result",
    );
    const text = split?.text ?? "";

    if (split == null) {
      items.push({ kind: "message", message, text: "", rest: [message.raw] });
    } else if (text.length > 0 || rest.length > 0) {
      items.push({ kind: "message", message, text, rest });
    }

    // Without `deriveTools` the trace has real `execute_tool` spans for these
    // calls; emitting a row here too would show each call once per llm span
    // that carries it — three times over, for a two-step agent.
    if (!deriveTools) return;

    for (const [ti, block] of toolUses.entries()) {
      if (!isObj(block)) continue;
      const id = typeof block.id === "string" ? block.id : null;
      items.push({
        kind: "tool",
        step: {
          key: id ?? `m${mi}-t${ti}`,
          name: typeof block.name === "string" ? block.name : "tool",
          args: toolArgs(block) != null ? JSON.stringify(toolArgs(block)) : null,
          result: id != null ? (results.get(id) ?? null) : null,
          latencyMs: null,
          retries: null,
          failed: false,
        },
      });
    }
  });

  return items;
}

/**
 * Consecutive tool calls are one batch: the agent asked for them together, so
 * they render on one line rather than stacking into a column that reads like
 * five sequential steps.
 */
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

function Items({ items, failed }: { items: ChatItem[]; failed?: boolean }) {
  return (
    <div className="flex flex-col gap-2">
      {groupTools(items).map((group, i) =>
        group.kind === "tools" ? (
          <ToolCallBatch key={`t${i}`} steps={group.steps} />
        ) : (
          <Turn key={`m${i}`} item={group} failed={failed} />
        ),
      )}
    </div>
  );
}

/* ----------------------------------------------------------------- rendering */

/** The dialogue before this trace's turn, folded away until asked for. */
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

/** Rendered dialogue for a run of items — the caller decides there are any. */
export function ChatItems({ items, failed }: { items: ChatItem[]; failed?: boolean }) {
  return <Items items={items} failed={failed} />;
}

function Turn({ item, failed }: { item: Extract<ChatItem, { kind: "message" }>; failed?: boolean }) {
  const { label, side } = classify(item.message);

  return (
    <div className="flex flex-col gap-0.75">
      {label && (
        <span
          className={cn("text-label font-mono uppercase text-subtle", side === "right" ? "self-end" : "self-start")}
        >
          {label}
        </span>
      )}
      <Bubble side={side} failed={failed}>
        <Clamped>
          <div className="flex flex-col gap-2">
            {item.text.length > 0 && <Markdown>{item.text}</Markdown>}
            {item.rest.length > 0 && <PayloadBody payload={JSON.stringify(item.rest)} />}
          </div>
        </Clamped>
      </Bubble>
    </div>
  );
}

/**
 * Ten lines of the bubble's own line-height. A pasted skill prompt or a dumped
 * file runs to hundreds of lines, and one such message pushed every turn after
 * it off the screen — the transcript stopped being scannable. Ten is enough to
 * recognise a message and decide whether to open it.
 */
const CLAMP_EM = 16.5;

/**
 * A message folded to {@link CLAMP_EM}, with the rest one click away.
 *
 * The height is measured rather than guessed from the text, because a bubble is
 * not only prose — a markdown table, a code block and a JSON tree all render
 * here, and counting characters would clamp some messages that fit and leave
 * others overflowing. The observer is on the *content*, not the clamped box,
 * whose height is pinned and would therefore never report a change.
 */
function Clamped({ children }: { children: React.ReactNode }) {
  const outer = useRef<HTMLDivElement>(null);
  const inner = useRef<HTMLDivElement>(null);
  const [overflows, setOverflows] = useState(false);
  const [open, setOpen] = useState(false);

  useLayoutEffect(() => {
    const box = outer.current;
    const content = inner.current;
    if (!box || !content) return;
    const measure = () => {
      const limit = parseFloat(getComputedStyle(box).fontSize) * CLAMP_EM;
      // A hair of tolerance: a message one sub-pixel over the line would
      // otherwise get a "Show more" that reveals nothing.
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
          // The fade says "there is more" before the reader gets to the button,
          // and it has to match whichever side's bubble it sits in — hence the
          // custom property the bubble sets rather than a hard-coded colour.
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
  children,
}: {
  side: "left" | "right";
  failed?: boolean;
  children: React.ReactNode;
}) {
  const right = side === "right";
  return (
    <div className={cn("flex", right ? "justify-end" : "justify-start")}>
      <div
        className={cn(
          "chat-bubble min-w-0 text-body py-2.5 px-3.5",
          right ? "bg-raised text-fg" : "bg-bg text-fg",
          !right && "border border-border",
        )}
        style={
          {
            maxWidth: 720,
            borderRadius: right ? "12px 12px 4px 12px" : "12px 12px 12px 4px",
            wordBreak: "break-word",
            // Read by the fold's fade gradient, which has to end in this
            // bubble's own colour to look like the text runs out rather than
            // like a grey band was laid over it.
            "--bubble-bg": right ? "var(--color-raised)" : "var(--color-bg)",
            ...(failed ? { borderColor: "var(--color-error)" } : null),
          } as React.CSSProperties
        }
      >
        {children}
      </div>
    </div>
  );
}
