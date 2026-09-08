// SPDX-License-Identifier: Apache-2.0
/**
 * Presentational trace model — the shared, data-source-agnostic shape the
 * Sources / NewDataset / EntryDetail screens map their data into before handing
 * it to <TracePanel/>. This is deliberately NOT the API type: screens own the
 * adapter from their own response (SourcePreviewEntry, run verdict rows, etc.)
 * into these flat presentational structs, so the trace components never fetch or
 * know about the network contract.
 *
 * A trace is a flat list of {@link TraceSpan} linked by `parentId` (root = null).
 * {@link buildSpanForest} turns that flat list into the nested tree the outline
 * renders. The thread view consumes a separate, already-ordered list of
 * {@link ThreadTurn}s (a conversation read top to bottom).
 */

/** The normalized span kinds the outline gives a glyph + accent treatment.
 *  Map an arbitrary OTel `operationKind` through {@link normalizeKind}. */
export type SpanKind =
  | "agent"
  | "chain"
  | "llm"
  | "tool"
  | "retrieval"
  | "guardrail"
  | "workflow"
  | "span";

/** One node in a trace's span tree. Pure presentational — no fetching, no verdicts. */
export interface TraceSpan {
  /** Stable id within the trace (the source's span/observation id). */
  id: string;
  /** Parent span id; null for a root span. Drives {@link buildSpanForest}. */
  parentId?: string | null;
  /** Display name (mono), e.g. "chat.completion", "vector.search". */
  name: string;
  /** Normalized kind for the glyph + label. Defaults to "span" when unknown. */
  kind: SpanKind;
  /** Model id shown muted next to the name (llm spans). */
  model?: string | null;
  /** One-line muted summary shown under the name in the outline. */
  summary?: string | null;
  /** Raw input payload string (json / chat / text), rendered by the inspector. */
  input?: string | null;
  /** Raw output payload string, rendered by the inspector. */
  output?: string | null;
  /** Resolved call site id, shown only when present (NOT a verdict). */
  callSite?: string | null;
  /** Genuine retrieval relevance scores — data, not grader verdicts. Shown for retrieval spans. */
  scoredChunks?: ScoredChunk[];
  /** ISO timestamp, shown muted in the inspector header when present. Also the
   *  span's start time for the trajectory duration/flame view when no explicit
   *  {@link startTime} is given. */
  timestamp?: string | null;
  /** ISO start time for the trajectory duration view. Falls back to {@link timestamp}. */
  startTime?: string | null;
  /** ISO end time for the trajectory duration view. When present (with a start),
   *  the trajectory viewer lays the span out as a bar on the trace timeline. */
  endTime?: string | null;
  /** Wall-clock duration in milliseconds, when the source supplies one directly.
   *  Takes precedence over an end−start computation. */
  durationMs?: number | null;
}

/** A retrieved passage with its (data, not grader) relevance score. */
export interface ScoredChunk {
  text: string;
  score?: number | null;
  /** Optional source / document id for the chunk. */
  ref?: string | null;
}

/** A role-tagged conversational turn for the thread view (system → user → tool → assistant). */
export interface ThreadTurn {
  /** Raw role string from the trace; {@link normalizeRole} maps it to the rail color. */
  role: string;
  /** The turn's body. Rendered as markdown/json/text by the thread renderer. */
  text: string;
  /** Optional speaker name (a tool name, an agent name) shown next to the role label. */
  name?: string | null;
}

/** A trace ready for the panel: its spans (flat) and, optionally, a pre-built thread. */
export interface TraceData {
  /** Trace id, shown in the panel header (mono). */
  id: string;
  spans: TraceSpan[];
  /** The conversation read top to bottom. When omitted the panel derives a thread
   *  from the spans' llm input/output (see deriveThread in TracePanel). */
  thread?: ThreadTurn[];
}

/* -------------------------------------------------------------------------- */
/* Kind + role normalization                                                  */
/* -------------------------------------------------------------------------- */

/** Map an arbitrary OTel operation kind (or null) to a {@link SpanKind}. */
export function normalizeKind(raw: string | null | undefined): SpanKind {
  const k = (raw ?? "").toLowerCase();
  if (k.includes("agent")) return "agent";
  if (k.includes("chain")) return "chain";
  if (k.includes("retriev") || k.includes("search") || k.includes("embed")) return "retrieval";
  if (k.includes("tool") || k.includes("function")) return "tool";
  if (k.includes("guard") || k.includes("moderation") || k.includes("safety")) return "guardrail";
  if (k.includes("workflow") || k.includes("graph") || k.includes("pipeline")) return "workflow";
  if (k.includes("llm") || k.includes("chat") || k.includes("completion") || k.includes("generation"))
    return "llm";
  return "span";
}

/** Canonical thread roles for rail coloring. */
export type ThreadRole = "system" | "user" | "assistant" | "tool" | "other";

export function normalizeRole(raw: string | null | undefined): ThreadRole {
  const r = (raw ?? "").toLowerCase();
  if (r === "system" || r === "developer") return "system";
  if (r === "user" || r === "human") return "user";
  if (r === "assistant" || r === "ai" || r === "model" || r === "bot") return "assistant";
  if (r === "tool" || r === "function" || r === "tool_result" || r === "tool_call") return "tool";
  return "other";
}

/* -------------------------------------------------------------------------- */
/* Forest                                                                     */
/* -------------------------------------------------------------------------- */

/** A span plus its resolved children, for the outline tree. */
export interface SpanNode {
  span: TraceSpan;
  depth: number;
  children: SpanNode[];
}

/**
 * Turn a flat span list into a forest by `parentId`. Spans whose parent is
 * missing (or null) become roots, preserving input order within each level.
 * Cycle-safe: a span never becomes its own ancestor.
 */
export function buildSpanForest(spans: TraceSpan[]): SpanNode[] {
  const byId = new Map<string, TraceSpan>();
  for (const s of spans) byId.set(s.id, s);

  const childrenOf = new Map<string | null, TraceSpan[]>();
  for (const s of spans) {
    const parent = s.parentId && s.parentId !== s.id && byId.has(s.parentId) ? s.parentId : null;
    const list = childrenOf.get(parent) ?? [];
    list.push(s);
    childrenOf.set(parent, list);
  }

  const seen = new Set<string>();
  const build = (span: TraceSpan, depth: number): SpanNode => {
    seen.add(span.id);
    const kids = (childrenOf.get(span.id) ?? [])
      .filter((c) => !seen.has(c.id))
      .map((c) => build(c, depth + 1));
    return { span, depth, children: kids };
  };

  const roots = childrenOf.get(null) ?? [];
  return roots.map((r) => build(r, 0));
}

/** Flatten a forest depth-first (for keyboard nav / "expand all" rendering). */
export function flattenForest(forest: SpanNode[]): SpanNode[] {
  const out: SpanNode[] = [];
  const walk = (n: SpanNode) => {
    out.push(n);
    n.children.forEach(walk);
  };
  forest.forEach(walk);
  return out;
}
