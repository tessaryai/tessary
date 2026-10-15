// SPDX-License-Identifier: Apache-2.0
import { useMemo, useState } from "react";
import { cn } from "../../ui";
import { ChatItems, PriorContext, messageItems, planConversation, planTools, spanItems } from "./detail-chat";
import type { Emphasis } from "./detail-chat";
import { Chevron, Pane, ToolCallBatch, ToolDetails, toolStepOf } from "./detail-tool";
import type { ToolPlan } from "./detail-chat";
import { SpanIcon, spanKindLabel, spanLabel } from "./detail-icons";
import { DetectionMarker } from "./detection-marker";
import type { ToolStep } from "./detail-tool";
import type { TraceListItemView as TraceListItem } from "../../api/types";
import type { Span, TraceRollup } from "./detail-data";
import { clockLabel, depthOf, formatDuration, formatTokens, spanOrder, traceBounds } from "./detail-data";

export type { Emphasis } from "./detail-chat";

export type EmphasisBySpan = Map<string, Emphasis[]>;

const NO_EMPHASIS: EmphasisBySpan = new Map();

const ofPart = (entries: Emphasis[] | undefined, part: Emphasis["part"]) =>
  (entries ?? []).filter((e) => e.part === part);

/**
 * The root span holds both the question (input) and the answer (output), so it is split around the
 * steps rather than rendered in sequence, which would answer before it works.
 */
export function ConversationView({
  spans,
  focusId,
  marks = [],
  showPrior = true,
  emphasis = NO_EMPHASIS,
  dim = true,
  contextNote,
}: {
  spans: Span[];
  focusId: string | null;
  marks?: string[];
  showPrior?: boolean;
  emphasis?: EmphasisBySpan;
  dim?: boolean;
  contextNote?: string;
}) {
  const plan = useMemo(() => planConversation(spans), [spans]);
  const tools = useMemo(() => planTools(spans), [spans]);
  const ordered = useMemo(() => spanOrder(spans), [spans]);

  if (spans.length === 0) {
    return (
      <p className="text-subtle py-6 px-0 text-small">
        This trace recorded no steps.
      </p>
    );
  }

  const root = ordered[0] ?? null;
  const rootPlan = root ? plan.bySpan.get(root.id) : undefined;
  const steps = ordered.filter((o) => o !== root);
  const question = rootPlan ? messageItems(rootPlan.input) : [];
  const reply = rootPlan ? messageItems(rootPlan.output ?? []) : [];

  const dimming = dim && spans.some((o) => (emphasis.get(o.id)?.length ?? 0) > 0);
  const rootEntries = root ? emphasis.get(root.id) : undefined;
  const questionEmphasis = ofPart(rootEntries, "question");
  const replyEmphasis = ofPart(rootEntries, "reply");
  const questionDimmed = dimming && questionEmphasis.length === 0;
  const replyDimmed = dimming && replyEmphasis.length === 0;

  const chrome = (o: Span, body: React.ReactNode, dimmed = false) => (
    <SpanBlock key={o.id} o={o} focused={focusId === o.id} body={body} dimmed={dimmed} note={contextNote} />
  );

  return (
    <div className="flex flex-col gap-4.5">
      {showPrior && <PriorContext messages={plan.prior} />}

      {root &&
        question.length > 0 &&
        chrome(
          root,
          <ChatItems items={question} marks={marks} emphasis={questionEmphasis} dimmed={questionDimmed} />,
          questionDimmed,
        )}

      {batchSteps(steps, tools).map((group, i) =>
        group.kind === "tools" ? (
          <Dimmable
            key={`tools-${i}`}
            dimmed={dimming && !group.steps.some((step) => ofPart(emphasis.get(step.key), "step").length > 0)}
            note={contextNote}
          >
            <ToolCallBatch steps={group.steps} />
          </Dimmable>
        ) : (
          (() => {
            const o = group.span;
            const items = spanItems(plan.bySpan.get(o.id));
            if (items.length === 0) return null;
            const stepEmphasis = ofPart(emphasis.get(o.id), "step");
            const dimmed = dimming && stepEmphasis.length === 0;
            return chrome(
              o,
              <ChatItems items={items} failed={o.status === "error"} emphasis={stepEmphasis} dimmed={dimmed} />,
              dimmed,
            );
          })()
        ),
      )}

      {root && reply.length > 0 && (
        <Dimmable dimmed={replyDimmed} note={contextNote}>
          <ChatItems items={reply} failed={root.status === "error"} emphasis={replyEmphasis} dimmed={replyDimmed} />
        </Dimmable>
      )}
    </div>
  );
}

type StepGroup = { kind: "tools"; steps: ToolStep[] } | { kind: "span"; span: Span };

/**
 * Tool calls come from planTools, not the tool spans: a trace can request a tool it never records a span
 * for, and vice versa.
 */
export function batchSteps(steps: Span[], tools: ToolPlan): StepGroup[] {
  const out: StepGroup[] = [];
  const pushTools = (list: ToolStep[]) => {
    const last = out[out.length - 1];
    if (last?.kind === "tools") last.steps.push(...list);
    else out.push({ kind: "tools", steps: [...list] });
  };

  for (const o of steps) {
    // Keyed on the execution record, not the kind: a tool reached through a retrieval or mcp span is still a tool call.
    if (toolStepOf(o) != null) {
      const orphan = tools.orphans.get(o.id);
      if (orphan) pushTools([orphan]);
      continue;
    }
    out.push({ kind: "span", span: o });
    const requested = tools.byLlm.get(o.id);
    if (requested?.length) pushTools(requested);
  }
  return out;
}

/**
 * Focus outline around a span's messages, deliberately with no header: latency and tokens belong to the
 * trace summary and to Tree and Timeline. The anchor stays so a link can scroll here.
 */
function SpanBlock({
  o,
  focused,
  body,
  dimmed,
  note,
}: {
  o: Span;
  focused: boolean;
  body: React.ReactNode;
  dimmed: boolean;
  note?: string;
}) {
  return (
    <div
      id={`obs-${o.id}`}
      className={cn("flex flex-col gap-2", focused && "rounded-card")}
      style={{ ...(focused ? { outline: "1px solid var(--color-border-strong)", padding: 12, margin: -12 } : null) }}
    >
      <Dimmable dimmed={dimmed} note={note}>
        {body}
      </Dimmable>
    </div>
  );
}

function Dimmable({ dimmed, note, children }: { dimmed: boolean; note?: string; children: React.ReactNode }) {
  if (!dimmed) return <>{children}</>;
  return (
    <div className="flex flex-col gap-1">
      <div className="opacity-60">{children}</div>
      {note && <p className="m-0 text-label text-subtle">{note}</p>}
    </div>
  );
}

/* -------------------------------------------------------------------- tree */

const NO_MARKS = new Map<string, string[]>();

/** Nested spans with inline duration · tokens · cost — the conforming grammar. */
export function TreeView({
  spans,
  focusId,
  onSelect,
  marksBySpan = NO_MARKS,
}: {
  spans: Span[];
  focusId: string | null;
  onSelect: (id: string) => void;
  /** The classifiers that flagged each span, by span id. */
  marksBySpan?: Map<string, string[]>;
}) {
  // A trace parents its tool spans to the agent; planTools recovers the requesting llm call from the request ids.
  const parentOf = planTools(spans).parentOf;
  const depths = depthOf(spans, parentOf);
  return (
    <div className="flex flex-col gap-0.25">
      {spanOrder(spans, parentOf).map((o) => (
        <ExpandableRow
          key={o.id}
          o={o}
          indent={(depths.get(o.id) ?? 0) * 16}
          focused={focusId === o.id}
          onSelect={() => onSelect(o.id)}
        >
          <SpanIcon kind={o.kind} />
          <span className="font-mono text-fg min-w-0 flex-1 truncate text-small">
            {spanLabel(o)}
          </span>
          <DetectionMarker names={marksBySpan.get(o.id) ?? []} className="shrink-0" />
          <SpanStats o={o} />
        </ExpandableRow>
      ))}
    </div>
  );
}

/** Timing, cost and outcome for one span — the bookkeeping Conversation omits. */
function SpanStats({ o }: { o: Span }) {
  const step = toolStepOf(o);
  const failed = o.status === "error" || (step?.failed ?? false);
  return (
    <>
      {failed && (
        <span className="text-error shrink-0 text-label" style={{ fontFamily: "var(--font-mono)" }}>
          {step ? "failed" : "error"}
        </span>
      )}
      {o.model && (
        <span className="font-mono text-subtle shrink-0 truncate text-label" style={{ maxWidth: 180 }}>
          {o.model}
        </span>
      )}
      <span
        className="font-mono text-subtle shrink-0 text-label"
        style={{ fontVariantNumeric: "tabular-nums" }}
      >
        {formatDuration(o.duration_ms)}
        {o.total_tokens != null && ` · ${formatTokens(o.total_tokens)}`}
        {costLabel(o) && ` · ${costLabel(o)}`}
      </span>
    </>
  );
}

/**
 * What this span cost, or why we cannot say. Reads `cost_source`, the only correct way to read a null cost:
 * `unpriced` means real spend we hold no rate for. The `> 0` token test matches `trace.unpriced_spans`.
 */
function costLabel(o: Span): string | null {
  if (o.total_cost != null) return `$${o.total_cost < 0.01 ? o.total_cost.toFixed(4) : o.total_cost.toFixed(2)}`;
  if (o.cost_source === "unpriced" && (o.total_tokens ?? 0) > 0) return "unpriced";
  return null;
}

/**
 * A span row that opens to show what went in and what came out. Selection and expansion are separate
 * controls, because reading a payload should not move the focus.
 */
function ExpandableRow({
  o,
  indent,
  focused,
  onSelect,
  children,
}: {
  o: Span;
  indent: number;
  focused: boolean;
  onSelect: () => void;
  children: React.ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const step = toolStepOf(o);
  // A purged payload is a third state: retention can age the span_payload row out ahead of the span, and
  // "no payload" would misstate a span whose prompt we deleted.
  const expired = !o.payload_available && o.kind !== "tool";
  const hasPayload = step != null || o.input != null || o.output != null || expired;
  return (
    <div className={cn("flex flex-col", focused ? "bg-selected" : "hover:bg-hover")} style={{ borderRadius: "var(--radius-control)" }}>
      <div className="flex items-center w-full gap-2 py-1.75 px-2.5">
        <span style={{ width: indent, flexShrink: 0 }} aria-hidden="true" />
        {hasPayload ? (
          <button
            type="button"
            onClick={() => setOpen((v) => !v)}
            aria-expanded={open}
            aria-label={open ? "Hide payloads" : "Show payloads"}
            className="shrink-0 text-subtle hover:text-fg transition-colors cursor-pointer"
            style={{ transitionDuration: "var(--duration-micro)", display: "flex" }}
          >
            <Chevron open={open} />
          </button>
        ) : (
          <span style={{ width: 12, flexShrink: 0 }} aria-hidden="true" />
        )}
        <button
          type="button"
          onClick={onSelect}
          title={`${spanKindLabel(o.kind)} · ${o.name ?? o.kind ?? "step"}`}
          className="flex items-center min-w-0 flex-1 text-left cursor-pointer gap-2.5">
          {children}
        </button>
      </div>
      {hasPayload && open && (
        <div className="pr-2.5 pb-2" style={{ paddingLeft: indent + 10 }}>
          {step ? (
            <ToolDetails step={step} />
          ) : (
            <div className="flex flex-col gap-2.5 pl-5.5 pt-2">
              {expired ? (
                <p className="text-subtle text-small">
                  Payload expired. Prompts and completions age out ahead of the spans themselves, so this
                  step&rsquo;s timing, tokens, and cost are still exact. Only its text is gone.
                </p>
              ) : (
                <>
                  <Pane label="Input" payload={o.input} />
                  <Pane label={o.status === "error" ? "Output · error" : "Output"} payload={o.output} />
                </>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/* ---------------------------------------------------------------- timeline */

/**
 * The waterfall. The scale is the trace's own start-to-end where it has one, not the span envelope, so
 * time no span covers (queueing, gaps before a tool) stays visible.
 */
export function TimelineView({
  trace,
  spans,
  focusId,
  onSelect,
  marksBySpan = NO_MARKS,
}: {
  trace: TraceRollup | undefined;
  spans: Span[];
  focusId: string | null;
  onSelect: (id: string) => void;
  marksBySpan?: Map<string, string[]>;
}) {
  const bounds = traceBounds(trace, spans);
  if (!bounds) {
    return (
      <p className="text-subtle py-6 px-0 text-small">
        No step recorded a start time, so there is no waterfall to draw.
      </p>
    );
  }
  return (
    <div className="flex flex-col gap-0.75">
      {spanOrder(spans).map((o) => (
        <TimelineRow
          key={o.id}
          o={o}
          bounds={bounds}
          focusId={focusId}
          onSelect={onSelect}
          marks={marksBySpan.get(o.id) ?? []}
        />
      ))}
    </div>
  );
}

/** One span's bar, scaled against `bounds`, so the session timeline can plot several traces on one scale. */
function TimelineRow({
  o,
  bounds,
  focusId,
  onSelect,
  marks,
}: {
  o: Span;
  bounds: { start: number; end: number };
  focusId: string | null;
  onSelect: (id: string) => void;
  marks: string[];
}) {
  const s = new Date(o.started_at).getTime();
  if (Number.isNaN(s)) return null;
  const e = o.ended_at ? new Date(o.ended_at).getTime() : s + (o.duration_ms ?? 0);
  const span = bounds.end - bounds.start;
  const left = ((s - bounds.start) / span) * 100;
  const width = Math.max(0.5, ((e - s) / span) * 100);
  const failed = o.status === "error" || (toolStepOf(o)?.failed ?? false);
  return (
    <ExpandableRow o={o} indent={0} focused={focusId === o.id} onSelect={() => onSelect(o.id)}>
      <SpanIcon kind={o.kind} size={12} />
      <span className="flex shrink-0 items-center gap-2 min-w-0" style={{ width: 220 }}>
        <span className="font-mono text-fg truncate text-label">{spanLabel(o)}</span>
        <DetectionMarker names={marks} />
      </span>
      <span className="relative flex-1" style={{ height: 10 }}>
        <span
          title={formatDuration(o.duration_ms)}
          style={{
            position: "absolute",
            left: `${left}%`,
            width: `${width}%`,
            minWidth: 4,
            height: 10,
            borderRadius: "var(--radius-micro)",
            background: failed ? "var(--color-error)" : "var(--color-subtle)" }}
        />
      </span>
      <span
        className="font-mono text-subtle shrink-0 text-label"
        style={{ width: 130, textAlign: "right", fontVariantNumeric: "tabular-nums" }}
      >
        {formatDuration(o.duration_ms)}
        {o.total_tokens != null && ` · ${formatTokens(o.total_tokens)}`}
      </span>
    </ExpandableRow>
  );
}

/* ------------------------------------------------------------------ session */

/** `t.id → its spans`, the shape every session view below groups by. */
export type SpansByTrace = Map<string, Span[]>;

function traceLabel(t: TraceListItem): string {
  const parts = [clockLabel(t.started_at)];
  if (t.name) parts.push(t.name);
  if (t.latency_ms != null) parts.push(formatDuration(t.latency_ms));
  return parts.join(" · ");
}

function TraceDivider({ trace, first }: { trace: TraceListItem; first: boolean }) {
  return (
    <div
      className="flex items-center text-subtle font-mono gap-2.5 text-label"
      style={{ margin: first ? "0 0 14px" : "22px 0 14px" }}
    >
      <span className="flex-1" style={{ height: 1, background: "var(--color-border)" }} />
      <span className="shrink-0">{traceLabel(trace)}</span>
      <span className="flex-1" style={{ height: 1, background: "var(--color-border)" }} />
    </div>
  );
}

/**
 * The session's whole conversation, each trace's ConversationView with a divider between them. Only the
 * first trace offers earlier messages; a later trace's history is the traces drawn above it.
 */
export function SessionConversationView({
  traces,
  spansByTrace,
  focusId,
  marksByTrace = NO_MARKS,
  emphasis = NO_EMPHASIS,
  dim = true,
  contextNote,
}: {
  traces: TraceListItem[];
  spansByTrace: SpansByTrace;
  focusId: string | null;
  /** The classifiers that flagged each trace's turn, by trace id. */
  marksByTrace?: Map<string, string[]>;
  /** The parts a caller marks, by span id, across every trace drawn. */
  emphasis?: EmphasisBySpan;
  /** Whether a marked trace dims its parts with no entry; a trace with no entry is never dimmed. */
  dim?: boolean;
  /** A line drawn under each dimmed part, saying why it is dimmed. */
  contextNote?: string;
}) {
  if (traces.length === 0) {
    return (
      <p className="text-subtle py-6 px-0 text-small">
        This session recorded no traces.
      </p>
    );
  }
  return (
    <div className="flex flex-col">
      {traces.map((t, i) => (
        <div key={t.id}>
          <TraceDivider trace={t} first={i === 0} />
          <ConversationView
            spans={spansByTrace.get(t.id) ?? []}
            focusId={focusId}
            marks={marksByTrace.get(t.id) ?? []}
            showPrior={i === 0}
            emphasis={emphasis}
            dim={dim}
            contextNote={contextNote}
          />
        </div>
      ))}
    </div>
  );
}

function TraceHeadRow({
  trace,
  open,
  onToggle,
}: {
  trace: TraceListItem;
  open: boolean;
  onToggle: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-expanded={open}
      className="flex w-full items-center text-left cursor-pointer bg-surface hover:bg-hover transition-colors gap-2.5 py-2.5 px-3"
      style={{ transitionDuration: "var(--duration-micro)" }}
    >
      <Chevron open={open} />
      <span className="font-mono text-fg min-w-0 flex-1 truncate text-small">
        {traceLabel(trace)}
      </span>
      <span
        className="font-mono text-subtle shrink-0 text-label"
        style={{ fontVariantNumeric: "tabular-nums" }}
      >
        {trace.total_tokens != null && `${formatTokens(trace.total_tokens)} · `}
        {trace.total_cost != null
          ? `$${trace.total_cost < 0.01 ? trace.total_cost.toFixed(4) : trace.total_cost.toFixed(2)}`
          : "—"}
      </span>
    </button>
  );
}

/**
 * Every trace as a collapsible node with TreeView nested inside. Spans never share a parent across traces,
 * so per-trace `spanOrder`/`depthOf` is correct.
 */
export function SessionTreeView({
  traces,
  spansByTrace,
  focusId,
  onSelect,
  marksBySpan = NO_MARKS,
}: {
  traces: TraceListItem[];
  spansByTrace: SpansByTrace;
  focusId: string | null;
  onSelect: (id: string) => void;
  marksBySpan?: Map<string, string[]>;
}) {
  // The first trace opens, and so does every trace a classifier flagged, so the marks are in view.
  const [open, setOpen] = useState<Set<string>>(
    () =>
      new Set([
        ...(traces[0] ? [traces[0].id] : []),
        ...traces
          .filter((t) => (spansByTrace.get(t.id) ?? []).some((o) => marksBySpan.has(o.id)))
          .map((t) => t.id),
      ]),
  );
  const toggle = (id: string) =>
    setOpen((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  if (traces.length === 0) {
    return (
      <p className="text-subtle py-6 px-0 text-small">
        This session recorded no traces.
      </p>
    );
  }
  return (
    <div className="flex flex-col gap-2">
      {traces.map((t) => {
        const isOpen = open.has(t.id);
        return (
          <div key={t.id} className="border border-border overflow-hidden" style={{ borderRadius: "var(--radius-card)" }}>
            <TraceHeadRow trace={t} open={isOpen} onToggle={() => toggle(t.id)} />
            {isOpen && (
              <div className="pt-1 px-2 pb-2">
                <TreeView
                  spans={spansByTrace.get(t.id) ?? []}
                  focusId={focusId}
                  onSelect={onSelect}
                  marksBySpan={marksBySpan}
                />
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

/** Wall-clock bounds of a whole session, in the same preference order as traceBounds. */
export function sessionBounds(
  traces: TraceListItem[],
  spansByTrace: SpansByTrace,
): { start: number; end: number } | null {
  let start = Number.POSITIVE_INFINITY;
  let end = Number.NEGATIVE_INFINITY;
  for (const t of traces) {
    const bounds = traceBounds(t, spansByTrace.get(t.id) ?? []);
    if (!bounds) continue;
    start = Math.min(start, bounds.start);
    end = Math.max(end, bounds.end);
  }
  return Number.isFinite(start) ? { start, end } : null;
}

/**
 * One waterfall across the whole session on a shared axis, so idle gaps and overlaps between traces show.
 * Traces are walked oldest first rather than by global span start, so each trace's bars read as one block.
 */
export function SessionTimelineView({
  traces,
  spansByTrace,
  focusId,
  onSelect,
  marksBySpan = NO_MARKS,
}: {
  traces: TraceListItem[];
  spansByTrace: SpansByTrace;
  focusId: string | null;
  onSelect: (id: string) => void;
  marksBySpan?: Map<string, string[]>;
}) {
  const bounds = sessionBounds(traces, spansByTrace);
  if (!bounds) {
    return (
      <p className="text-subtle py-6 px-0 text-small">
        No trace in this session recorded a start time, so there is no waterfall to draw.
      </p>
    );
  }
  return (
    <div className="flex flex-col">
      {traces.map((t, i) => (
        <div key={t.id}>
          <TraceDivider trace={t} first={i === 0} />
          <div className="flex flex-col gap-0.75">
            {spanOrder(spansByTrace.get(t.id) ?? []).map((o) => (
              <TimelineRow
                key={o.id}
                o={o}
                bounds={bounds}
                focusId={focusId}
                onSelect={onSelect}
                marks={marksBySpan.get(o.id) ?? []}
              />
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}
