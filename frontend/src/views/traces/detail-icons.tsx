// SPDX-License-Identifier: Apache-2.0
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

/** Every kind the substrate emits: llm, agent, tool, mcp, retrieval, embedding, reranker. */
const BY_KIND: Record<string, LucideIcon> = {
  agent: Bot,
  llm: Sparkles,
  tool: Wrench,
  mcp: Plug,
  retrieval: Database,
  embedding: Binary,
  reranker: ListOrdered,
};

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

export function operationName(o: { attributes?: Record<string, unknown> | null; kind?: string | null }): string | null {
  const raw = o.attributes?.["gen_ai.operation.name"];
  return typeof raw === "string" && raw.length > 0 ? raw : null;
}

/**
 * Verbatim: any rule for stripping a "redundant" prefix guesses about producers and fails (it turned
 * `retrieveContext` into `Context`). The operation name is used only when there is no name.
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
