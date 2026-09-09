// SPDX-License-Identifier: Apache-2.0
/*
 * The three renderings of one trace's execution: Conversation (default) · Tree ·
 * Timeline. All read the same observation list — they differ only in what they
 * make legible.
 *
 * A fourth surface, the Verdicts panel, sat over them until grading left the platform: it read the
 * grader rulings on the trace. What a trace is judged by now is a classifier finding,
 * which has its own page.
 */
import { useMemo, useState } from "react";
import { cn } from "../../ui";
import { ChatItems, PriorContext, messageItems, planConversation, planTools, spanItems } from "./detail-chat";
import { Chevron, Pane, ToolCallBatch, ToolDetails, toolStepOf } from "./detail-tool";
import type { ToolPlan } from "./detail-chat";
import { SpanIcon, spanKindLabel, spanLabel } from "./detail-icons";
import type { ToolStep } from "./detail-tool";
import type { TraceListItemView as TraceListItem } from "../../api/types";
import type { Span, TraceRollup } from "./detail-data";
import { clockLabel, depthOf, formatDuration, formatTokens, spanOrder, traceBounds } from "./detail-data";

/* ------------------------------------------------------------ conversation */

/**
 * Chat grammar: one transcript for the trace, read in the order it happened —
 * the question, then the work, then the answer.
 *
 * That order has to be built rather than followed, because the root agent span
 * holds *both* ends of the turn: the user's message is its input and the final
 * reply is its output, with every tool call recorded in between by child spans
 * that start later. Rendering spans in sequence therefore answers before it
 * works. So the root is split — its question opens the view, its answer closes
 * it — and the steps sit where they belong.
 *
 * The planning (whose message is whose, what is prior context, what repeats) is
 * a trace-wide question, so it happens once in {@link planConversation}.
 */
export function ConversationView({
  spans,
  focusId,
}: {
  spans: Span[];
  focusId: string | null;
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

  // `spanOrder` walks roots first, so the head of the list is the outermost span
  // even when this trace's parent lives outside it.
  const root = ordered[0] ?? null;
  const rootPlan = root ? plan.bySpan.get(root.id) : undefined;
  const steps = ordered.filter((o) => o !== root);
  const question = rootPlan ? messageItems(rootPlan.input) : [];
  const reply = rootPlan ? messageItems(rootPlan.output ?? []) : [];

  const chrome = (o: Span, body: React.ReactNode) => (
    <SpanBlock key={o.id} o={o} focused={focusId === o.id} body={body} />
  );

  return (
    <div className="flex flex-col gap-4.5">
      <PriorContext messages={plan.prior} />

      {/* The question the turn is answering. */}
      {root && question.length > 0 && chrome(root, <ChatItems items={question} />)}

      {/* The work, in the order it ran: what each model call said, then the tools
          it asked for, batched onto one line. */}
      {batchSteps(steps, tools).map((group, i) =>
        group.kind === "tools" ? (
          <ToolCallBatch key={`tools-${i}`} steps={group.steps} />
        ) : (
          (() => {
            const o = group.span;
            // Skip the whole block, chrome included, when the span draws nothing:
            // an outlined empty box is worse than an absent one.
            const items = spanItems(plan.bySpan.get(o.id));
            if (items.length === 0) return null;
            return chrome(o, <ChatItems items={items} failed={o.status === "error"} />);
          })()
        ),
      )}

      {/* The answer, last, where the reader expects it. */}
      {root && reply.length > 0 && <ChatItems items={reply} failed={root.status === "error"} />}
    </div>
  );
}

type StepGroup = { kind: "tools"; steps: ToolStep[] } | { kind: "span"; span: Span };

/**
 * The step list as rows: each llm span, then the calls it requested.
 *
 * Tool calls come from {@link planTools} rather than from the tool spans in this
 * list, because the two disagree — a trace can request a tool it never records a
 * span for, and vice versa. Tool spans already accounted for are skipped here so
 * nothing is drawn twice; the rest are shown where they ran.
 */
function batchSteps(steps: Span[], tools: ToolPlan): StepGroup[] {
  const out: StepGroup[] = [];
  const pushTools = (list: ToolStep[]) => {
    const last = out[out.length - 1];
    if (last?.kind === "tools") last.steps.push(...list);
    else out.push({ kind: "tools", steps: [...list] });
  };

  for (const o of steps) {
    if (o.kind === "tool") {
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
 * Focus outline around a span's messages — and deliberately no header. A latency-and-token line
 * between two sentences is span bookkeeping, not dialogue; it belongs to the trace summary above and
 * to Tree and Timeline, which exist to read spans. The anchor stays so a link can still scroll here.
 */
function SpanBlock({ o, focused, body }: { o: Span; focused: boolean; body: React.ReactNode }) {
  return (
    <div
      id={`obs-${o.id}`}
      className={cn("flex flex-col gap-2", focused && "rounded-card")}
      style={{ ...(focused ? { outline: "1px solid var(--color-border-strong)", padding: 12, margin: -12 } : null) }}
    >
      {body}

    </div>
  );
}

/* -------------------------------------------------------------------- tree */

/** Nested spans with inline duration · tokens · cost — the conforming grammar. */
export function TreeView({
  spans,
  focusId,
  onSelect,
}: {
  spans: Span[];
  focusId: string | null;
  onSelect: (id: string) => void;
}) {
  // A trace parents its tool spans to the agent, so the llm call that requested
  // them is their sibling. planTools recovers the real link from the request ids.
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
 * What this span cost, or why we cannot say.
 *
 * `cost_source` is the only correct way to read a null cost, so the label reads
 * it rather than inferring from the number: `unpriced` means we hold no rate for
 * the model and the span really did have a bill, whereas a priced span with no
 * usage has nothing to charge for. Rendering both as a blank — which is what the
 * old client-side pricing table did whenever it did not recognise a model —
 * turns real spend into apparently free work.
 *
 * The token test is `> 0`, matching `trace.unpriced_spans`: a span whose usage is
 * an explicit zero reported nothing to bill, so labelling it "unpriced" claims a
 * missing rate for a call that had no spend. Those placeholder spans are 58% of
 * the corpus, and a trace that now reports `unpriced_spans: 0` must not open onto
 * spans badged unpriced.
 */
function costLabel(o: Span): string | null {
  if (o.total_cost != null) return `$${o.total_cost < 0.01 ? o.total_cost.toFixed(4) : o.total_cost.toFixed(2)}`;
  if (o.cost_source === "unpriced" && (o.total_tokens ?? 0) > 0) return "unpriced";
  return null;
}

/**
 * A span row that opens to show what went in and what came out — every span, not
 * only tool calls, since "what did this llm step actually receive" is the same
 * question one row up. Selection and expansion are separate controls rather than
 * one click doing both, because reading a payload should not move the focus.
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
  // A purged payload is a THIRD state, and it has to be openable to be explained. `payload_available`
  // is false when the span_payload row is gone — either it never existed or retention aged it out
  // ahead of the span, which the substrate does deliberately. Collapsing that into "no payload" would
  // present a span whose prompt we deleted as a span that never had one.
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
 * The waterfall: every span positioned by its real wall-clock start and duration.
 *
 * The scale is the TRACE's own start→end where it has one, not the envelope of
 * the spans on screen. A turn routinely spends time no span covers — queueing
 * before the first call, the gap between an llm reply and the tool it asked for
 * — and a chart scaled to the spans hides exactly that time by construction,
 * which is the time a reader opening a waterfall is usually looking for.
 */
export function TimelineView({
  trace,
  spans,
  focusId,
  onSelect,
}: {
  trace: TraceRollup | undefined;
  spans: Span[];
  focusId: string | null;
  onSelect: (id: string) => void;
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
        <TimelineRow key={o.id} o={o} bounds={bounds} focusId={focusId} onSelect={onSelect} />
      ))}
    </div>
  );
}

/**
 * One span's waterfall bar, scaled against `bounds` — factored out of {@link TimelineView} so
 * {@link SessionTimelineView} can plot spans from several traces against ONE shared bounds instead of
 * each trace computing (and being positioned against) its own. Everything about a single bar — the icon,
 * the label, the failed/ok color, the exact-duration tooltip — is unchanged; only where `bounds` comes
 * from differs between the two callers.
 */
function TimelineRow({
  o,
  bounds,
  focusId,
  onSelect,
}: {
  o: Span;
  bounds: { start: number; end: number };
  focusId: string | null;
  onSelect: (id: string) => void;
}) {
  const s = o.started_at ? new Date(o.started_at).getTime() : NaN;
  if (Number.isNaN(s)) return null;
  const e = o.ended_at ? new Date(o.ended_at).getTime() : s + (o.duration_ms ?? 0);
  const span = bounds.end - bounds.start;
  const left = ((s - bounds.start) / span) * 100;
  const width = Math.max(0.5, ((e - s) / span) * 100);
  const failed = o.status === "error" || (toolStepOf(o)?.failed ?? false);
  return (
    <ExpandableRow o={o} indent={0} focused={focusId === o.id} onSelect={() => onSelect(o.id)}>
      <SpanIcon kind={o.kind} size={12} />
      <span className="font-mono text-fg shrink-0 truncate text-label" style={{ width: 220 }}>
        {spanLabel(o)}
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

/* ------------------------------------------------------------------ session
 *
 * The three renderings above, extended to a whole session: several traces'
 * worth of spans instead of one. Deliberately NOT a rewrite — a session is
 * traces grouped, not a fourth kind of thing to render, so each of the three
 * below loops over the session's traces (oldest first, as SessionDetail
 * already orders them) and either reuses the exact view above unchanged
 * (Conversation, Tree) or reuses only its per-row presentation (Timeline,
 * which needs bars plotted against one shared session-wide scale rather than
 * each trace's own). The one new visual element either way is the thin
 * divider naming which trace a block of content belongs to.
 */

/** `t.id → its spans`, the shape every session view below groups by. */
export type SpansByTrace = Map<string, Span[]>;

/** A trace's own line — reused by all three session views as the "which trace is this" label. */
function traceLabel(t: TraceListItem): string {
  const parts = [clockLabel(t.started_at)];
  if (t.name) parts.push(t.name);
  if (t.latency_ms != null) parts.push(formatDuration(t.latency_ms));
  return parts.join(" · ");
}

/** The divider between one trace's block and the next — the "minor addition" for Conversation. */
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
 * The session's whole conversation, end to end — every trace's turn in order, with a divider between
 * them rather than a table of traces. Each trace's own {@link ConversationView} is reused completely
 * unchanged; verdicts are per-trace judgment and out of scope for a session read, so each call gets an
 * empty list rather than a session-wide verdicts fetch this view does not have.
 */
export function SessionConversationView({
  traces,
  spansByTrace,
  focusId,
}: {
  traces: TraceListItem[];
  spansByTrace: SpansByTrace;
  focusId: string | null;
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
          <ConversationView spans={spansByTrace.get(t.id) ?? []} focusId={focusId} />
        </div>
      ))}
    </div>
  );
}

/** One trace's collapsible header in {@link SessionTreeView} — name, time, duration, cost, tokens. */
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
 * Every trace in the session as its own collapsible node, spans nested underneath exactly like the
 * single-trace {@link TreeView} already renders them — reused unchanged inside each node. Spans never
 * share a parent across traces, so keeping `spanOrder`/`depthOf` scoped per trace (which reusing
 * {@link TreeView} directly does) is correct, not a shortcut. The first trace opens by default; the rest
 * are one click away.
 */
export function SessionTreeView({
  traces,
  spansByTrace,
  focusId,
  onSelect,
}: {
  traces: TraceListItem[];
  spansByTrace: SpansByTrace;
  focusId: string | null;
  onSelect: (id: string) => void;
}) {
  const [open, setOpen] = useState<Set<string>>(() => new Set(traces[0] ? [traces[0].id] : []));
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
                <TreeView spans={spansByTrace.get(t.id) ?? []} focusId={focusId} onSelect={onSelect} />
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

/**
 * Wall-clock bounds of a whole session, for {@link SessionTimelineView}'s shared scale — the same
 * preference order {@link traceBounds} uses (a trace's own started_at/ended_at over scanning its spans),
 * just min/maxed across every trace in the session instead of read from one.
 */
function sessionBounds(traces: TraceListItem[]): { start: number; end: number } | null {
  let start = Number.POSITIVE_INFINITY;
  let end = Number.NEGATIVE_INFINITY;
  for (const t of traces) {
    const s = t.started_at ? new Date(t.started_at).getTime() : NaN;
    if (Number.isNaN(s)) continue;
    start = Math.min(start, s);
    const e = t.ended_at ? new Date(t.ended_at).getTime() : s + (t.latency_ms ?? 0);
    if (!Number.isNaN(e)) end = Math.max(end, e);
  }
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) return null;
  return { start, end };
}

/**
 * One waterfall across the whole session — every span from every trace, on ONE shared time axis, so an
 * idle gap between traces or two traces overlapping is visible the way it never can be from a single
 * trace's own Timeline. Reuses {@link TimelineRow} (the exact bar {@link TimelineView} draws) for every
 * span; the "minor addition" is the same trace divider {@link SessionConversationView} uses, so a reader
 * can still tell which trace a cluster of bars belongs to without the axis breaking into separate scales.
 *
 * Traces are still walked in {@link SessionDetail}'s own oldest-first order rather than a raw global sort
 * of every span's start time, so a trace's bars always read together as one block — consistent with how
 * {@link SessionTreeView} and {@link SessionConversationView} group, and readable even when two traces
 * genuinely overlap in wall-clock time.
 */
export function SessionTimelineView({
  traces,
  spansByTrace,
  focusId,
  onSelect,
}: {
  traces: TraceListItem[];
  spansByTrace: SpansByTrace;
  focusId: string | null;
  onSelect: (id: string) => void;
}) {
  const bounds = sessionBounds(traces);
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
              <TimelineRow key={o.id} o={o} bounds={bounds} focusId={focusId} onSelect={onSelect} />
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}
