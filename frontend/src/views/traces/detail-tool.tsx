// SPDX-License-Identifier: Apache-2.0
// A tool call is not dialogue, so it is a pill with its payloads behind the chevron, in every view.
import { useState } from "react";
import { cn } from "../../ui";
import { PayloadBody } from "../components/PayloadViewer";
import type { Span } from "./detail-data";

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => !!v && typeof v === "object" && !Array.isArray(v);

export type ToolStep = {
  key: string;
  name: string;
  args: string | null;
  result: string | null;
  failed: boolean;
};

/** Conservative: anything that is not one message wrapping one tool block is returned untouched. */
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

/** A handler that answers `{"error": ...}` leaves the span `ok`, so read the payload too. */
function resultFailed(result: string | null): boolean {
  if (!result) return false;
  try {
    const parsed = JSON.parse(result);
    return isObj(parsed) && "error" in parsed && parsed.error != null;
  } catch {
    return false;
  }
}

export function toolStepFrom(fields: {
  key: string;
  name: string;
  args: string | null;
  result: string | null;
}): ToolStep {
  return {
    key: fields.key,
    name: fields.name,
    args: fields.args,
    result: fields.result,
    failed: resultFailed(fields.result),
  };
}

export const TOOL_CALL_ID = "gen_ai.tool.call.id";

/**
 * Keyed on `gen_ai.tool.call.id`, not kind: producers record tool use under other operations
 * (e.g. `retrieval`).
 */
export function toolStepOf(o: Span): ToolStep | null {
  const callId = o.attributes?.[TOOL_CALL_ID];
  if (o.kind !== "tool" && typeof callId !== "string") return null;

  const call = o.tool_calls[0];
  const attrName = o.attributes?.["gen_ai.tool.name"];
  const name =
    call?.name ?? (typeof attrName === "string" ? attrName : null) ?? o.name ?? o.kind ?? "tool";
  // The producer wraps `tool_calls[].result` in the same envelope.
  const result = unwrapToolPayload(call?.result ?? o.output);
  return {
    key: o.id,
    name,
    args: unwrapToolPayload(call?.args ?? o.input),
    result,
    failed: o.status === "error" || call?.error != null || resultFailed(result),
  };
}

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

/** Parallel calls sit on one line; latency and tokens belong to the span views. */
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
