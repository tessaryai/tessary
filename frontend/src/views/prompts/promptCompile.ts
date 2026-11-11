// SPDX-License-Identifier: Apache-2.0
import type { PromptBody, PromptChatBody, PromptChatMessage, PromptPlaceholder } from "../../api/types";

/** The server's PromptNames.VARIABLE and both SDKs carry the same pattern; a parity gate compares the four. */
export const VARIABLE_PATTERN = /\{\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*\}\}/g;
export const PLACEHOLDER_NAME = /^[A-Za-z_][A-Za-z0-9_]*$/;

export type BodyToken = { kind: "text"; text: string } | { kind: "variable"; text: string; name: string };

export function isPlaceholder(entry: PromptChatMessage | PromptPlaceholder): entry is PromptPlaceholder {
  return "type" in entry;
}

export function tokenizeVariables(text: string): BodyToken[] {
  const tokens: BodyToken[] = [];
  let last = 0;
  for (const m of text.matchAll(VARIABLE_PATTERN)) {
    if (m.index > last) tokens.push({ kind: "text", text: text.slice(last, m.index) });
    tokens.push({ kind: "variable", text: m[0], name: m[1] });
    last = m.index + m[0].length;
  }
  if (last < text.length) tokens.push({ kind: "text", text: text.slice(last) });
  return tokens;
}

function texts(body: PromptBody): string[] {
  if (typeof body === "string") return [body];
  return body.flatMap((e) => (isPlaceholder(e) ? [] : [e.content]));
}

/** In first-appearance order, once each, across every message; placeholders hold none. */
export function extractVariables(body: PromptBody): string[] {
  const names = texts(body).flatMap((t) => [...t.matchAll(VARIABLE_PATTERN)].map((m) => m[1]));
  return [...new Set(names)];
}

export function extractPlaceholders(body: PromptBody): string[] {
  if (typeof body === "string") return [];
  return [...new Set(body.filter(isPlaceholder).map((p) => p.name))];
}

/** An unfilled placeholder stays in the result as the placeholder entry; the caller reports it. */
export function compile(
  body: PromptBody,
  vars: Record<string, string>,
  placeholders: Record<string, PromptChatMessage[]> = {},
): PromptBody {
  const fill = (text: string) =>
    text.replace(VARIABLE_PATTERN, (whole, name: string) => (Object.hasOwn(vars, name) ? vars[name] : whole));
  if (typeof body === "string") return fill(body);
  return body.flatMap((e): PromptChatBody =>
    isPlaceholder(e)
      ? Object.hasOwn(placeholders, e.name)
        ? placeholders[e.name]
        : [e]
      : [{ role: e.role, content: fill(e.content) }],
  );
}
