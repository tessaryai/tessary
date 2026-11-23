// SPDX-License-Identifier: Apache-2.0
import type { PromptBody, PromptChatBody, PromptConfig, PromptType } from "../../api/types";
import { isPlaceholder } from "./promptCompile";

export type DiffOp = { kind: "same" | "added" | "removed"; text: string };

/**
 * The most edits one diff pass looks for. Past it the pass gives the two sides whole: time grows with the input
 * length times the edits found, and a block that changed this much reads better whole anyway.
 */
const MAX_EDITS = 1000;

type Edit<T> = { kind: DiffOp["kind"]; item: T };

/**
 * The shortest edit script from `a` to `b` (Myers, "An O(ND) difference algorithm"), removals before additions in a
 * change, or null when it needs more than `MAX_EDITS` edits. The common prefix and suffix are cut first, so a long
 * text with one edit costs a linear scan.
 */
function editScript<T>(a: T[], b: T[]): Edit<T>[] | null {
  let start = 0;
  while (start < a.length && start < b.length && a[start] === b[start]) start++;
  let endA = a.length;
  let endB = b.length;
  while (endA > start && endB > start && a[endA - 1] === b[endB - 1]) {
    endA--;
    endB--;
  }
  const middle = shortestEdits(a.slice(start, endA), b.slice(start, endB));
  if (middle === null) return null;
  const same = (items: T[]) => items.map((item) => ({ kind: "same" as const, item }));
  return [...same(a.slice(0, start)), ...middle, ...same(a.slice(endA))];
}

function shortestEdits<T>(a: T[], b: T[]): Edit<T>[] | null {
  const n = a.length;
  const m = b.length;
  const limit = Math.min(n + m, MAX_EDITS);
  const offset = limit + 1;
  // v[offset + k] is the furthest x reached on diagonal k = x - y.
  const v = new Int32Array(2 * limit + 3);
  // trace[d][k + d] is v after d edits, kept only for the diagonals d edits can reach.
  const trace: Int32Array[] = [];
  let found = -1;
  for (let d = 0; d <= limit && found < 0; d++) {
    for (let k = -d; k <= d; k += 2) {
      const down = k === -d || (k !== d && v[offset + k - 1] < v[offset + k + 1]);
      let x = down ? v[offset + k + 1] : v[offset + k - 1] + 1;
      let y = x - k;
      while (x < n && y < m && a[x] === b[y]) {
        x++;
        y++;
      }
      v[offset + k] = x;
      if (x >= n && y >= m) found = d;
    }
    trace.push(v.slice(offset - d, offset + d + 1));
  }
  if (found < 0) return null;

  const out: Edit<T>[] = [];
  let x = n;
  let y = m;
  // Walk back from the end; before the first edit the path is one snake from (0, 0).
  for (let d = found; d >= 0; d--) {
    let prevX = 0;
    let prevY = 0;
    let down = false;
    if (d > 0) {
      const prev = trace[d - 1];
      const at = (k: number) => prev[k + d - 1];
      const k = x - y;
      down = k === -d || (k !== d && at(k - 1) < at(k + 1));
      const prevK = down ? k + 1 : k - 1;
      prevX = at(prevK);
      prevY = prevX - prevK;
    }
    while (x > prevX && y > prevY) {
      out.push({ kind: "same", item: a[--x] });
      y--;
    }
    if (d === 0) break;
    if (down) out.push({ kind: "added", item: b[--y] });
    else out.push({ kind: "removed", item: a[--x] });
  }
  return out.reverse();
}

/** Words, whitespace runs, and sentence punctuation before a space or the end, so "name." and "name" share a word. */
export function tokenize(text: string): string[] {
  return text.split(/(\s+|[.,;:!?](?=\s|$))/).filter(Boolean);
}

/** Lines with their line break kept, so joining them gives the text back. */
function splitLines(text: string): string[] {
  return text.split(/(?<=\n)/).filter(Boolean);
}

function opList() {
  const ops: DiffOp[] = [];
  const push = (kind: DiffOp["kind"], text: string) => {
    if (!text) return;
    const prev = ops[ops.length - 1];
    if (prev?.kind === kind) prev.text += text;
    else ops.push({ kind, text });
  };
  return { ops, push };
}

/** Removed words on the left of a change, added words on the right: lines first, then words inside each changed block. */
export function wordDiff(a: string, b: string): DiffOp[] {
  const { ops, push } = opList();
  const changeBlock = (removed: string, added: string) => {
    const words = removed && added ? editScript(tokenize(removed), tokenize(added)) : null;
    if (words === null) {
      push("removed", removed);
      push("added", added);
      return;
    }
    for (const w of words) push(w.kind, w.item);
  };
  const lines = editScript(splitLines(a), splitLines(b));
  if (lines === null) {
    changeBlock(a, b);
    return ops;
  }
  let removed = "";
  let added = "";
  for (const line of lines) {
    if (line.kind === "removed") removed += line.item;
    else if (line.kind === "added") added += line.item;
    else {
      changeBlock(removed, added);
      removed = added = "";
      push("same", line.item);
    }
  }
  changeBlock(removed, added);
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

type ChatEntry = PromptChatBody[number];

/** One message or placeholder in a compare; a `changed` row pairs an edited message with a word diff of its content. */
export type ChatRow =
  | { kind: "same"; left: ChatEntry; right: ChatEntry }
  | { kind: "changed"; left: ChatEntry; right: ChatEntry; ops: DiffOp[] }
  | { kind: "removed"; left: ChatEntry }
  | { kind: "added"; right: ChatEntry };

/**
 * Messages are matched whole first, so one inserted message does not shift the rest into the change. Inside each run of
 * changes, removed and added messages pair up in order; a pair of two messages is one changed row.
 */
export function diffChat(a: PromptChatBody, b: PromptChatBody): ChatRow[] {
  const keys = (body: PromptChatBody) => body.map((e) => canonicalJson(e));
  const ka = keys(a);
  const kb = keys(b);
  const script = editScript(ka, kb) ?? [
    ...ka.map((item) => ({ kind: "removed" as const, item })),
    ...kb.map((item) => ({ kind: "added" as const, item })),
  ];
  const rows: ChatRow[] = [];
  let i = 0;
  let j = 0;
  let removed: ChatEntry[] = [];
  let added: ChatEntry[] = [];
  const flush = () => {
    for (let p = 0; p < Math.max(removed.length, added.length); p++) {
      const left = removed[p];
      const right = added[p];
      if (left && right && !isPlaceholder(left) && !isPlaceholder(right)) {
        rows.push({ kind: "changed", left, right, ops: wordDiff(left.content, right.content) });
      } else {
        if (left) rows.push({ kind: "removed", left });
        if (right) rows.push({ kind: "added", right });
      }
    }
    removed = [];
    added = [];
  };
  for (const step of script) {
    if (step.kind === "removed") removed.push(a[i++]);
    else if (step.kind === "added") added.push(b[j++]);
    else {
      flush();
      rows.push({ kind: "same", left: a[i++], right: b[j++] });
    }
  }
  flush();
  return rows;
}

export type ConfigRow =
  | { key: string; kind: "same"; left: string; right: string }
  | { key: string; kind: "changed"; left: string; right: string; ops: DiffOp[] }
  | { key: string; kind: "removed"; left: string }
  | { key: string; kind: "added"; right: string };

/** One row per top-level key, in the old config's order with new keys last; values compare as canonical JSON. */
export function diffConfigKeys(a: PromptConfig, b: PromptConfig): ConfigRow[] {
  const keys = [...Object.keys(a), ...Object.keys(b).filter((k) => !Object.hasOwn(a, k))];
  return keys.map((key): ConfigRow => {
    const left = Object.hasOwn(a, key) ? canonicalJson(a[key]) : null;
    const right = Object.hasOwn(b, key) ? canonicalJson(b[key]) : null;
    if (left === null) return { key, kind: "added", right: right as string };
    if (right === null) return { key, kind: "removed", left };
    if (left === right) return { key, kind: "same", left, right };
    return { key, kind: "changed", left, right, ops: wordDiff(left, right) };
  });
}
