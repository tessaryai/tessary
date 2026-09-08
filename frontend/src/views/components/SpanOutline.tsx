// SPDX-License-Identifier: Apache-2.0
import { cn } from "../../ui";
import { buildSpanForest, type SpanKind, type SpanNode, type TraceSpan } from "./trace";

/**
 * The hierarchical reading of a trace: a span tree (agent → chain → llm → tool →
 * retrieval → guardrail). Each row is a per-kind glyph, the mono span name, an
 * optional muted model, and a one-line summary. Selecting a row drives the
 * inspector. Structure is shown with indentation + a quiet guide line, never
 * nested cards. Verdicts are deliberately absent here (these spans are
 * pulled-but-ungraded); only a resolved call site (when present) is surfaced.
 */
export function SpanOutline({
  spans,
  selectedId,
  onSelect,
  showCallSites = true,
}: {
  spans: TraceSpan[];
  selectedId: string | null;
  onSelect: (span: TraceSpan) => void;
  /** Show the resolved call-site chip on each row. Not a verdict. */
  showCallSites?: boolean;
}) {
  const forest = buildSpanForest(spans);
  if (forest.length === 0) {
    return <p className="text-small text-muted italic">No spans in this trace.</p>;
  }
  return (
    <div className="flex flex-col">
      {forest.map((node) => (
        <OutlineRow
          key={node.span.id}
          node={node}
          selectedId={selectedId}
          onSelect={onSelect}
          showCallSites={showCallSites}
        />
      ))}
    </div>
  );
}

function OutlineRow({
  node,
  selectedId,
  onSelect,
  showCallSites,
}: {
  node: SpanNode;
  selectedId: string | null;
  onSelect: (span: TraceSpan) => void;
  showCallSites: boolean;
}) {
  const { span, depth, children } = node;
  const active = span.id === selectedId;
  return (
    <>
      <button
        type="button"
        onClick={() => onSelect(span)}
        className={cn(
          "group relative flex w-full items-start gap-2.5 rounded-control px-2 py-1.5 text-left transition-colors",
          active ? "bg-selected" : "hover:bg-hover",
        )}
        style={{ paddingLeft: 8 + depth * 16, transitionDuration: "var(--duration-micro)" }}
      >
        {active && (
          <span className="absolute left-0 top-1.5 bottom-1.5 w-0.5 rounded-pill bg-accent" aria-hidden="true" />
        )}
        <span className={cn("mt-0.5 shrink-0", active ? "text-accent" : "text-muted")}>
          <KindGlyph kind={span.kind} />
        </span>
        <span className="min-w-0 flex-1">
          <span className="flex items-baseline gap-2">
            <span className="font-mono text-small text-fg truncate">{span.name}</span>
            {span.model && <span className="font-mono text-label text-subtle truncate">{span.model}</span>}
          </span>
          {span.summary && (
            <span className="mt-0.5 block text-label text-muted truncate">{span.summary}</span>
          )}
        </span>
        {showCallSites && span.callSite && (
          <span className="mt-0.5 shrink-0 font-mono text-label text-accent">{span.callSite}</span>
        )}
      </button>
      {children.map((child) => (
        <OutlineRow
          key={child.span.id}
          node={child}
          selectedId={selectedId}
          onSelect={onSelect}
          showCallSites={showCallSites}
        />
      ))}
    </>
  );
}

/* -------------------------------------------------------------------------- */
/* Per-kind glyphs (hand-drawn, currentColor, 14px)                           */
/* -------------------------------------------------------------------------- */

export function KindGlyph({ kind, size = 14 }: { kind: SpanKind; size?: number }) {
  const p = { width: size, height: size, viewBox: "0 0 16 16", fill: "none" as const, "aria-hidden": true };
  switch (kind) {
    case "agent":
      return (
        <svg {...p}>
          <path
            d="M8 2.4c.5 3.1 1.5 4.1 4.6 4.6-3.1.5-4.1 1.5-4.6 4.6-.5-3.1-1.5-4.1-4.6-4.6 3.1-.5 4.1-1.5 4.6-4.6Z"
            fill="currentColor"
          />
        </svg>
      );
    case "chain":
      return (
        <svg {...p}>
          <path d="M6 6.5L4.5 8a2.1 2.1 0 0 0 3 3l1-1" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
          <path d="M10 9.5L11.5 8a2.1 2.1 0 0 0-3-3l-1 1" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
        </svg>
      );
    case "llm":
      return (
        <svg {...p}>
          <rect x="2.5" y="3.5" width="11" height="8" rx="1.5" stroke="currentColor" strokeWidth="1.3" />
          <path d="M5.5 13.5l1.4-2M10.5 13.5L9.1 11.5" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
          <path d="M5 6.5h6M5 8.5h4" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
        </svg>
      );
    case "tool":
      return (
        <svg {...p}>
          <path
            d="M10.5 2.5a3 3 0 0 0-3.7 3.7l-4 4a1.2 1.2 0 0 0 1.7 1.7l4-4a3 3 0 0 0 3.7-3.7L10.3 4.1 9 5.4 7.7 4.1 9 2.7Z"
            stroke="currentColor"
            strokeWidth="1.2"
            strokeLinejoin="round"
          />
        </svg>
      );
    case "retrieval":
      return (
        <svg {...p}>
          <circle cx="7" cy="7" r="4" stroke="currentColor" strokeWidth="1.3" />
          <path d="M10 10l3.5 3.5" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
        </svg>
      );
    case "guardrail":
      return (
        <svg {...p}>
          <path d="M8 2l5 1.8v3.6c0 3-2.1 5-5 6.2-2.9-1.2-5-3.2-5-6.2V3.8L8 2Z" stroke="currentColor" strokeWidth="1.2" strokeLinejoin="round" />
        </svg>
      );
    case "workflow":
      return (
        <svg {...p}>
          <rect x="2.5" y="2.5" width="4" height="4" rx="1" stroke="currentColor" strokeWidth="1.2" />
          <rect x="9.5" y="9.5" width="4" height="4" rx="1" stroke="currentColor" strokeWidth="1.2" />
          <path d="M6.5 4.5h3a2 2 0 0 1 2 2v3" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" />
        </svg>
      );
    default:
      return (
        <svg {...p}>
          <circle cx="8" cy="8" r="2" fill="currentColor" />
        </svg>
      );
  }
}

const KIND_LABEL: Record<SpanKind, string> = {
  agent: "Agent",
  chain: "Chain",
  llm: "LLM call",
  tool: "Tool",
  retrieval: "Retrieval",
  guardrail: "Guardrail",
  workflow: "Workflow",
  span: "Span",
};

export function kindLabel(kind: SpanKind): string {
  return KIND_LABEL[kind];
}

/* -------------------------------------------------------------------------- */
/* Per-kind color + geometric indicator                                       */
/* -------------------------------------------------------------------------- */

/**
 * Stable per-kind color, read by the square indicator and the flame legend.
 *
 * Span kind is categorical data, so it walks the Siblings chart-series ramp in slot order and
 * nothing else. Not the accent (that is the grey interactive color — a data encoding wearing it
 * would read as clickable) and not a status hue (red/green/blue are verdicts; a green `guardrail`
 * would read as "passed"). `span` is the unclassified fallback and is deliberately off the ramp.
 */
const KIND_COLOR_VAR: Record<SpanKind, string> = {
  agent: "var(--color-chart-series-1)", // lavender
  llm: "var(--color-chart-series-2)", // orange
  retrieval: "var(--color-chart-series-3)", // cyan
  tool: "var(--color-chart-series-4)", // amber
  guardrail: "var(--color-chart-series-5)", // teal
  chain: "var(--color-chart-series-6)", // violet
  workflow: "var(--color-chart-series-7)", // rose
  span: "var(--color-muted)", // unclassified — grey, off the ramp
};

/** The themed CSS color for a span kind, for inline styling (squares, legends). */
export function kindColorVar(kind: SpanKind): string {
  return KIND_COLOR_VAR[kind] ?? KIND_COLOR_VAR.span;
}

/**
 * The minimalist span-kind indicator: a small rounded square tinted with the
 * kind's color (a low-opacity wash via color-mix) holding a solid inner dot.
 * Matches the trajectory design's geometric, glyph-free kind markers. Purely
 * decorative — the kind is also conveyed by the mono name + label elsewhere.
 */
export function SpanKindIndicator({
  kind,
  size = 14,
  active = false,
}: {
  kind: SpanKind;
  size?: number;
  /** When the row is selected, lean on the accent tint for the square. */
  active?: boolean;
}) {
  const color = active ? "var(--color-accent)" : kindColorVar(kind);
  const dot = Math.max(Math.round(size * 0.42), 5);
  return (
    <span
      aria-hidden="true"
      className="flex shrink-0 items-center justify-center rounded-micro"
      style={{
        width: size,
        height: size,
        background: `color-mix(in srgb, ${color} 22%, transparent)` }}
    >
      <span
        className="rounded-micro"
        style={{ width: dot, height: dot, background: color }}
      />
    </span>
  );
}
