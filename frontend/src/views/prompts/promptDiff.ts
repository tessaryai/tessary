// SPDX-License-Identifier: Apache-2.0
import type { PromptBody, PromptConfig, PromptType } from "../../api/types";

export type DiffOp = { kind: "same" | "added" | "removed"; text: string };

/** Above this many tokens on either side the diff is the two whole sides: the LCS table is quadratic. */
export const DIFF_TOKEN_CAP = 4000;

export function tokenize(text: string): string[] {
  return text.split(/(\s+)/).filter(Boolean);
}

export function wordDiff(a: string, b: string): DiffOp[] {
  const x = tokenize(a);
  const y = tokenize(b);
  if (x.length > DIFF_TOKEN_CAP || y.length > DIFF_TOKEN_CAP) {
    return [
      { kind: "removed", text: a },
      { kind: "added", text: b },
    ];
  }
  const n = x.length;
  const m = y.length;
  const w = m + 1;
  // lcs[i * w + j] is the LCS length of x[i..] and y[j..].
  const lcs = new Uint32Array((n + 1) * w);
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      lcs[i * w + j] = x[i] === y[j] ? lcs[(i + 1) * w + j + 1] + 1 : Math.max(lcs[(i + 1) * w + j], lcs[i * w + j + 1]);
    }
  }
  const ops: DiffOp[] = [];
  const push = (kind: DiffOp["kind"], text: string) => {
    const prev = ops[ops.length - 1];
    if (prev?.kind === kind) prev.text += text;
    else ops.push({ kind, text });
  };
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (x[i] === y[j]) {
      push("same", x[i]);
      i++;
      j++;
    } else if (lcs[(i + 1) * w + j] >= lcs[i * w + j + 1]) {
      push("removed", x[i++]);
    } else {
      push("added", y[j++]);
    }
  }
  while (i < n) push("removed", x[i++]);
  while (j < m) push("added", y[j++]);
  return ops;
}

function sortKeys(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sortKeys);
  if (value !== null && typeof value === "object") {
    const obj = value as Record<string, unknown>;
    return Object.fromEntries(Object.keys(obj).sort().map((k) => [k, sortKeys(obj[k])]));
  }
  return value;
}

/** Keys sorted at every depth, two-space indent, so a key-order change compares as the same. */
export function canonicalJson(value: unknown): string {
  return JSON.stringify(sortKeys(value), null, 2);
}

export function diffBody(type: PromptType, a: PromptBody, b: PromptBody): DiffOp[] {
  return type === "text" ? wordDiff(a as string, b as string) : wordDiff(canonicalJson(a), canonicalJson(b));
}

export function diffConfig(a: PromptConfig, b: PromptConfig): DiffOp[] {
  return wordDiff(canonicalJson(a), canonicalJson(b));
}
