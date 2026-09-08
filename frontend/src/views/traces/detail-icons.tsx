// SPDX-License-Identifier: Apache-2.0
/*
 * What kind of step this is, as a glyph rather than a prefix.
 *
 * Span names repeat their own kind — `execute_tool verify_member`,
 * `invoke_agent policy-gpt`, `llm -> answer` — so every row spent a dozen
 * characters restating a column the icon can carry, and the part that differs
 * started at a different offset on every line. Icon plus the distinguishing
 * name puts the differences in one place and gives them the width.
 */
import {
  Binary,
  Bot,
  Circle,
  Database,
  ListOrdered,
  Plug,
  Sparkles,
  Wrench,
  type LucideIcon,
} from "lucide-react";

/**
 * Every kind the substrate emits. The backend's vocabulary is
 * `llm | agent | tool | mcp | retrieval | embedding | reranker` — it appears
 * as that exact set in six places, from `TrajectoryAssembler` to the read
 * repositories — and this map used to cover four of them. The other three fell
 * through to {@link Circle}, so 39,308 MCP spans in one project alone read as
 * "some step we have no word for" next to the wrench of an ordinary tool call.
 *
 * The glyphs say what distinguishes each: an MCP call is a tool reached through
 * a connector, not a different kind of work, so it is a plug beside the wrench
 * rather than another tool-shaped thing. Retrieval fetches, embedding encodes,
 * reranking reorders.
 */
const BY_KIND: Record<string, LucideIcon> = {
  agent: Bot,
  llm: Sparkles,
  tool: Wrench,
  mcp: Plug,
  retrieval: Database,
  embedding: Binary,
  reranker: ListOrdered,
};

/** Kinds a reader should be told in words, since the glyph alone is a guess. */
export function spanKindLabel(kind: string | null | undefined): string {
  return kind && kind in BY_KIND ? kind : (kind ?? "step");
}

export function SpanIcon({ kind, size = 13 }: { kind: string | null | undefined; size?: number }) {
  const Icon = (kind && BY_KIND[kind]) || Circle;
  return (
    <Icon
      size={size}
      strokeWidth={1.75}
      aria-hidden="true"
      className="shrink-0 text-subtle"
      style={{ alignSelf: "center" }}
    />
  );
}

/** OTel GenAI semconv v1.37.0. The operation name is the one label nobody invented. */
export function operationName(o: { attributes?: Record<string, unknown> | null; kind?: string | null }): string | null {
  const raw = o.attributes?.["gen_ai.operation.name"];
  return typeof raw === "string" && raw.length > 0 ? raw : null;
}

/**
 * The span's name, exactly as its emitter sent it.
 *
 * Nothing is stripped. Span names are free text and every framework spells them
 * differently, so any rule for removing a "redundant" prefix is a guess about a
 * producer we have not met — and the guesses fail loudly: matching a word list
 * turned `retrieveContext` into `Context` and `execute_toolchain` into `chain`.
 * The kind is carried by the icon; the name is carried verbatim.
 *
 * The operation name is used only when there is no name at all.
 */
export function spanLabel(o: {
  name?: string | null;
  kind?: string | null;
  attributes?: Record<string, unknown> | null;
}): string {
  const named = (o.name ?? "").trim();
  if (named.length > 0) return named;
  return operationName(o) || o.kind || "step";
}
