// SPDX-License-Identifier: Apache-2.0
/*
 * One presentation of a tool call, shared by all three trace views.
 *
 * A tool call is not dialogue — nobody is speaking — so it does not get a chat
 * bubble. In Conversation it is a pill carrying the tool's name and nothing
 * else: arguments are frequently large, and a batch of five calls has to read
 * as one line, so the payloads wait behind the chevron. Tree and Timeline hang
 * the same panes off their span rows, so "what did this tool actually return"
 * is one click from wherever the reader is.
 */
import { useState } from "react";
import { cn } from "../../ui";
import { PayloadBody } from "../components/PayloadViewer";
import type { Span } from "./detail-data";

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => !!v && typeof v === "object" && !Array.isArray(v);

export type ToolStep = {
  /** Span id when this came from an `execute_tool` span; a synthetic key otherwise. */
  key: string;
  name: string;
  args: string | null;
  result: string | null;
  latencyMs: number | null;
  retries: number | null;
  failed: boolean;
};

/**
 * The tool's own payload, dug out of the message envelope it arrives in.
 *
 * A span's raw output is not the tool's answer but a message carrying it —
 * `[{"role":"tool","parts":[{"type":"tool_result","content":"…"}]}]` — so the
 * Result pane printed three levels of transport around the one string the
 * reader wanted, and {@link resultFailed} looked for `error` at a depth where
 * it could never be. Unwrapping is conservative: anything that is not a single
 * message wrapping a single tool block is returned untouched.
 */
function unwrapToolPayload(raw: string | null): string | null {
  if (raw == null || raw === "") return raw;
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return raw;
  }
  const message = Array.isArray(parsed) ? (parsed.length === 1 ? parsed[0] : null) : parsed;
  if (!isObj(message)) return raw;
  const blocks = message.parts ?? message.content;
  if (!Array.isArray(blocks)) return raw;
  const tools = blocks.filter((b) => isObj(b) && /^tool_(result|call|use)$/.test(String(b.type).toLowerCase()));
  if (tools.length !== 1 || !isObj(tools[0])) return raw;
  const block = tools[0];
  const payload =
    String(block.type).toLowerCase() === "tool_result" ? block.content : (block.input ?? block.arguments);
  if (payload == null) return raw;
  return typeof payload === "string" ? payload : JSON.stringify(payload);
}

/**
 * A tool can fail three ways and the span only records one of them: the
 * transport error. A handler that catches its own problem and answers
 * `{"error": "member_not_on_policy"}` returns HTTP-fine, so the span stays `ok`
 * while the agent was, in fact, told no. Read the payload as well.
 */
function resultFailed(result: string | null): boolean {
  if (!result) return false;
  try {
    const parsed = JSON.parse(result);
    return isObj(parsed) && "error" in parsed && parsed.error != null;
  } catch {
    return false;
  }
}

/**
 * A tool call built from the model's own request, for when the execution was
 * never instrumented as a span. The result, if it came back, is recovered from
 * the `tool_result` the next llm call was handed.
 */
export function toolStepFrom(fields: {
  key: string;
  name: string;
  args: string | null;
  result: string | null;
  latencyMs?: number | null;
  retries?: number | null;
}): ToolStep {
  return {
    key: fields.key,
    name: fields.name,
    args: fields.args,
    result: fields.result,
    latencyMs: fields.latencyMs ?? null,
    retries: fields.retries ?? null,
    failed: resultFailed(fields.result),
  };
}

/** The OTel GenAI id linking an execution back to the model's request for it. */
export const TOOL_CALL_ID = "gen_ai.tool.call.id";

/**
 * A span's record of executing a tool, or null if it is not one.
 *
 * The test is `gen_ai.tool.call.id`, not the kind. A tool the agent reaches for
 * may be recorded under any operation the producer thinks fits — this project
 * emits its document search as `retrieval`, with an empty `tool_calls` array and
 * the name and arguments only in the GenAI attributes. Keying on `kind === "tool"`
 * silently dropped those: the calls showed no latency, nested under nothing, and
 * looked to me like an instrumentation gap when they were fully instrumented.
 */
export function toolStepOf(o: Span): ToolStep | null {
  const callId = o.attributes?.[TOOL_CALL_ID];
  if (o.kind !== "tool" && typeof callId !== "string") return null;

  const call = o.tool_calls[0];
  const attrName = o.attributes?.["gen_ai.tool.name"];
  const name =
    call?.name ?? (typeof attrName === "string" ? attrName : null) ?? o.name ?? o.kind ?? "tool";
  // Unwrap the resolved value, not just the span fallback: the producer wraps
  // the envelope around `tool_calls[].result` too, so unwrapping only `o.output`
  // left the Result pane exactly as it was.
  const result = unwrapToolPayload(call?.result ?? o.output);
  return {
    key: o.id,
    name,
    args: unwrapToolPayload(call?.args ?? o.input),
    result,
    latencyMs: call?.latency_ms ?? o.duration_ms,
    retries: call?.retries ?? null,
    failed: o.status === "error" || call?.error != null || resultFailed(result),
  };
}

/** The payloads, revealed on demand. Shared by all three views. */
export function ToolDetails({ step }: { step: ToolStep }) {
  return (
    <div
      className="flex flex-col gap-2.5 pl-5.5 pt-2 pb-0.5">
      <Pane label="Arguments" payload={step.args} />
      <Pane label={step.failed ? "Result · error" : "Result"} payload={step.result} />
    </div>
  );
}

export function Pane({ label, payload }: { label: string; payload: string | null }) {
  return (
    <div className="flex flex-col gap-1">
      <span className="text-subtle uppercase tracking-wider text-label" style={{ fontFamily: "var(--font-mono)" }}>
        {label}
      </span>
      <div
        className="bg-surface border border-border min-w-0 py-2 px-2.5"
        style={{ borderRadius: "var(--radius-card)", overflowX: "auto" }}
      >
        <PayloadBody payload={payload} />
      </div>
    </div>
  );
}

export function Chevron({ open }: { open: boolean }) {
  return (
    <svg
      viewBox="0 0 24 24"
      aria-hidden="true"
      className="shrink-0"
      style={{
        width: 12,
        height: 12,
        fill: "currentColor",
        transform: open ? "rotate(90deg)" : "none",
        transition: `transform var(--duration-micro)` }}
    >
      <path d="M9 6l6 6-6 6z" />
    </svg>
  );
}

/**
 * A batch of tool calls the agent issued together.
 *
 * Each call is a pill sized to its own signature rather than a full-width row,
 * so calls made in parallel sit on one line and read as one step, while calls
 * made in sequence stack. Latency and token counts are not on the pill: they
 * belong to the span, and Tree and Timeline are where spans are read. What the
 * pill has to carry is what was asked and whether it worked.
 */
export function ToolCallBatch({ steps }: { steps: ToolStep[] }) {
  const [openKey, setOpenKey] = useState<string | null>(null);
  const open = steps.find((s) => s.key === openKey) ?? null;

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex flex-wrap items-center gap-1.5">
        {steps.map((step) => (
          <ToolPill
            key={step.key}
            step={step}
            open={step.key === openKey}
            onToggle={() => setOpenKey(step.key === openKey ? null : step.key)}
          />
        ))}
      </div>
      {open && (
        <div className="bg-surface border border-border min-w-0 pt-0.5 px-2.5 pb-2" style={{ borderRadius: "var(--radius-card)" }}>
          <ToolDetails step={open} />
        </div>
      )}
    </div>
  );
}

function ToolPill({ step, open, onToggle }: { step: ToolStep; open: boolean; onToggle: () => void }) {
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-expanded={open}
      // The tint is the whole signal visually; the word stays here so a screen
      // reader and anyone who cannot separate the hue still gets it.
      aria-label={step.failed ? `${step.name}, failed` : step.name}
      title={step.failed ? `${step.name}, failed` : step.name}
      className={cn(
        "inline-flex items-center rounded-pill border cursor-pointer transition-colors min-w-0 gap-1.5 py-0.75 px-2.5",
        step.failed
          ? "border-error text-error bg-error-subtle"
          : open
            ? "border-border-strong bg-selected text-fg"
            : "border-border bg-surface text-muted hover:text-fg",
      )}
      style={{ transitionDuration: "var(--duration-micro)" }}
    >
      <Chevron open={open} />
      <span className="font-mono truncate text-label" style={{ maxWidth: 320 }}>
        {step.name}
      </span>
    </button>
  );
}
